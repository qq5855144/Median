package com.xinyv.median;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * MITM 代理（v2）：动态证书签发 + TLS 隧道。
 *
 * - 首次使用时生成 RSA-2048 根 CA（持久化到应用私有目录）；
 * - 为每个 CONNECT 目标域名动态签发叶子证书（X509Generator，纯 Java DER）；
 * - 用 SSLSocket 对客户端完成 TLS 握手（域名证书 + 私钥），然后与真实服务器建连并双向转发；
 * - 隧道字节计数（up/down），供 proxy_ctl 查询统计。
 *
 * 注意：客户端（浏览器/curl）需信任本代理签发的 CA 证书（proxy_ctl export_ca 获取 PEM）。
 */
public final class MitmProxy {

    private static final String CA_KEY_FILE = "median_mitm_ca_key.der";
    private static final String CA_CERT_FILE = "median_mitm_ca.der";
    private static final int MAX_TUNNEL_BYTES = 64 * 1024 * 1024;

    private final File dir;
    private KeyPair caKey;
    private byte[] caCertDer;
    private String caPem;
    private final Map<String, SSLContext> ctxCache = new HashMap<String, SSLContext>();
    private final AtomicLong tunnelCount = new AtomicLong(0);
    private final AtomicLong bytesUp = new AtomicLong(0);
    private final AtomicLong bytesDown = new AtomicLong(0);
    private volatile boolean enabled = false;

    public MitmProxy(File filesDir) {
        this.dir = filesDir;
    }

    // ---------- 开关 ----------
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean on) { this.enabled = on; }
    public long tunnelCount() { return tunnelCount.get(); }
    public long bytesUp() { return bytesUp.get(); }
    public long bytesDown() { return bytesDown.get(); }

    // ---------- CA 生命周期 ----------
    /** 确保 CA 存在（生成并持久化），返回是否已就绪。 */
    public synchronized boolean ensureCa() {
        try {
            if (caKey != null && caCertDer != null) return true;
            File keyFile = new File(dir, CA_KEY_FILE);
            File certFile = new File(dir, CA_CERT_FILE);
            if (keyFile.exists() && certFile.exists()) {
                byte[] keyDer = readAll(keyFile);
                byte[] certDer = readAll(certFile);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(keyDer));
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                PublicKey pub = cf.generateCertificate(new ByteArrayInputStream(certDer)).getPublicKey();
                caKey = new KeyPair(pub, priv);
                caCertDer = certDer;
            } else {
                caKey = X509Generator.generateKeyPair();
                caCertDer = derFromPem(X509Generator.generateCaPem(caKey));
                if (!dir.exists()) dir.mkdirs();
                writeAll(keyFile, caKey.getPrivate().getEncoded());
                writeAll(certFile, caCertDer);
            }
            caPem = X509Generator.pem("CERTIFICATE", caCertDer);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derFromPem(String pem) {
        String body = pem.replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        return android.util.Base64.decode(body, android.util.Base64.DEFAULT);
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            return buf;
        } finally { in.close(); }
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(data); } finally { out.close(); }
    }

    public synchronized String caPem() {
        if (caPem == null) ensureCa();
        return caPem;
    }

    /** CA 证书 SHA-256 指纹（安装后核对用）。 */
    public synchronized String caFingerprint() {
        if (caCertDer == null) ensureCa();
        return caCertDer == null ? "" : X509Generator.sha256Hex(caCertDer);
    }

    // ---------- 域名证书签发 + SSLContext ----------
    /** 为域名获取（或创建）SSLContext：签发叶子证书并构建 KeyManager。 */
    public synchronized SSLContext contextFor(String host) throws Exception {
        if (caKey == null) ensureCa();
        if (caKey == null) throw new IOException("CA not ready");
        SSLContext cached = ctxCache.get(host);
        if (cached != null) return cached;

        KeyPair leaf = X509Generator.generateKeyPair();
        byte[] leafDer = X509Generator.signHostCertificate(leaf, caKey.getPrivate(), host);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate cert = cf.generateCertificate(new ByteArrayInputStream(leafDer));

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        char[] pass = "median".toCharArray();
        ks.setKeyEntry("leaf", leaf.getPrivate(), pass, new Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        ctxCache.put(host, ctx);
        return ctx;
    }

    // ---------- CONNECT 隧道 ----------
    /**
     * 处理 CONNECT 隧道（IO 线程调用）：
     * 1. 回复 200 Connection Established；
     * 2. 与客户端做 TLS 握手（域名证书）；
     * 3. 连接真实服务器并双向转发，直到任一端关闭。
     * 返回 true=已处理（连接由本方法接管并最终关闭）。
     */
    public boolean handleConnect(String hostPort, Socket clientSocket) {
        String host;
        int port = 443;
        try {
            String hp = hostPort.trim();
            if (hp.startsWith("[")) {
                int end = hp.indexOf(']');
                host = hp.substring(1, end);
                String rest = hp.substring(end + 1);
                if (rest.startsWith(":")) port = Integer.parseInt(rest.substring(1));
            } else {
                int colon = hp.lastIndexOf(':');
                if (colon > 0) { host = hp.substring(0, colon); port = Integer.parseInt(hp.substring(colon + 1)); }
                else host = hp;
            }
            if (host.isEmpty()) return false;
        } catch (Exception e) {
            return false;
        }
        tunnelCount.incrementAndGet();
        try {
            OutputStream rawOut = clientSocket.getOutputStream();
            rawOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            rawOut.flush();

            SSLContext ctx = contextFor(host);
            SSLSocket tls = (SSLSocket) ctx.getSocketFactory().createSocket(clientSocket, host, port, true);
            tls.setUseClientMode(false);
            tls.startHandshake();

            // 连接真实服务器（客户端 TLS 握手，使用系统默认信任库验证真实证书）
            SSLContext clientCtx = SSLContext.getInstance("TLS");
            clientCtx.init(null, null, null);
            Socket upstream = clientCtx.getSocketFactory().createSocket(host, port);
            try {
                upstream.setSoTimeout(0);
                tls.setSoTimeout(0);
                InputStream fromClient = tls.getInputStream();
                OutputStream toServer = upstream.getOutputStream();
                InputStream fromServer = upstream.getInputStream();
                OutputStream toClient = tls.getOutputStream();
                Thread a = new Thread(new Pump(fromClient, toServer, bytesUp), "mitm-up");
                Thread b = new Thread(new Pump(fromServer, toClient, bytesDown), "mitm-down");
                a.setDaemon(true); b.setDaemon(true);
                a.start(); b.start();
                try { a.join(); } catch (InterruptedException ignored) { }
                try { b.join(); } catch (InterruptedException ignored) { }
            } finally {
                try { upstream.close(); } catch (IOException ignored) { }
                try { tls.close(); } catch (IOException ignored) { }
            }
            return true;
        } catch (Exception e) {
            try { clientSocket.close(); } catch (IOException ignored) { }
            return true; // 已尝试接管（即使失败也关闭连接）
        }
    }

    /** 单向管道：读 in 写 out，统计字节数（受上限保护）。 */
    private static final class Pump implements Runnable {
        private final InputStream in;
        private final OutputStream out;
        private final AtomicLong counter;
        Pump(InputStream in, OutputStream out, AtomicLong counter) {
            this.in = in; this.out = out; this.counter = counter;
        }
        @Override public void run() {
            try {
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    out.flush();
                    total += n;
                    counter.addAndGet(n);
                    if (total > MAX_TUNNEL_BYTES) break;
                }
            } catch (IOException ignored) { }
            finally {
                try { out.flush(); } catch (IOException ignored) { }
            }
        }
    }
}
package com.xinyv.median;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 纯 Java X.509 v3 证书生成器（零第三方依赖）。
 *
 * - 自实现 ASN.1 DER 编码（TLV 基础 + X.509 结构）；
 * - 生成自签名 CA（RSA-2048 / SHA256withRSA）；
 * - 为任意域名签发叶子证书（CN + SAN），供 MITM 代理 TLS 使用；
 * - 导出 PEM 格式（证书 / 私钥），便于用户安装 CA 到系统/用户信任库。
 *
 * 用途说明：该能力用于用户自建抓包调试代理（配合 Median 代理端口）。
 */
public final class X509Generator {

    // ---------- ASN.1 基础 TLV ----------
    private static final int TAG_INTEGER = 0x02;
    private static final int TAG_BIT_STRING = 0x03;
    private static final int TAG_OCTET_STRING = 0x04;
    private static final int TAG_NULL = 0x05;
    private static final int TAG_OID = 0x06;
    private static final int TAG_UTF8 = 0x0c;
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_SET = 0x31;
    private static final int TAG_IA5 = 0x16;
    private static final int TAG_UTCTIME = 0x17;
    private static final int TAG_GENERALIZED_TIME = 0x18;
    private static final int TAG_CONTEXT_0 = 0xa0;
    private static final int TAG_CONTEXT_3 = 0xa3;

    private X509Generator() { }

    /** 生成 DER 长度前缀。 */
    private static byte[] derLen(int len) {
        if (len < 128) return new byte[]{(byte) len};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n = len;
        int bytes = 0;
        int tmp = len;
        while (tmp > 0) { bytes++; tmp >>= 8; }
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) out.write((len >>> (i * 8)) & 0xff);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        byte[] len = derLen(content.length);
        out.write(len, 0, len.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return tlv(TAG_SEQUENCE, out.toByteArray());
    }

    private static byte[] integer(BigInteger v) {
        byte[] raw = v.toByteArray();
        return tlv(TAG_INTEGER, raw);
    }

    private static byte[] bitString(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0); // 未使用位
        out.write(bytes, 0, bytes.length);
        return tlv(TAG_BIT_STRING, out.toByteArray());
    }

    private static byte[] utf8(String s) {
        return tlv(TAG_UTF8, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] ia5(String s) {
        return tlv(TAG_IA5, s.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (arcs.length < 2) throw new IllegalArgumentException("oid too short");
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            long v = arcs[i] & 0xffffffffL;
            ByteArrayOutputStream tmp = new ByteArrayOutputStream();
            tmp.write((byte) (v & 0x7f));
            v >>= 7;
            while (v > 0) {
                tmp.write((byte) ((v & 0x7f) | 0x80));
                v >>= 7;
            }
            byte[] arr = tmp.toByteArray();
            for (int j = arr.length - 1; j >= 0; j--) out.write(arr[j]);
        }
        return tlv(TAG_OID, out.toByteArray());
    }

    private static byte[] utcTime(Date d) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(TAG_UTCTIME, fmt.format(d).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static byte[] generalizedTime(Date d) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(TAG_GENERALIZED_TIME, fmt.format(d).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    // ---------- X.509 名称 / 算法 ----------

    private static final int[] OID_CN = {2, 5, 4, 3};
    private static final int[] OID_O = {2, 5, 4, 10};
    private static final int[] OID_SHA256_RSA = {1, 2, 840, 113549, 1, 1, 11};
    private static final int[] OID_RSA = {1, 2, 840, 113549, 1, 1, 1};
    private static final int[] OID_BASIC_CONSTRAINTS = {2, 5, 29, 19};
    private static final int[] OID_SUBJECT_ALT_NAME = {2, 5, 29, 17};
    private static final int[] OID_KEY_USAGE = {2, 5, 29, 15};
    private static final int[] OID_EXT_KEY_USAGE = {2, 5, 29, 37};
    private static final int[] OID_SERVER_AUTH = {1, 3, 6, 1, 5, 5, 7, 3, 1};

    /** 生成 RSA 密钥对（2048 位）。 */
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), new SecureRandom());
        return kpg.generateKeyPair();
    }

    /** Name = SEQUENCE OF SET OF SEQUENCE{OID, value}。 */
    private static byte[] name(String cn, String org) {
        byte[] cnAttr = seq(oid(OID_CN), utf8(cn));
        byte[] cnSet = tlv(TAG_SET, cnAttr);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(cnSet, 0, cnSet.length);
        if (org != null && !org.isEmpty()) {
            byte[] oAttr = seq(oid(OID_O), utf8(org));
            byte[] oSet = tlv(TAG_SET, oAttr);
            out.write(oSet, 0, oSet.length);
        }
        return tlv(TAG_SEQUENCE, out.toByteArray());
    }

    /** AlgorithmIdentifier = SEQUENCE{OID, NULL}。 */
    private static byte[] algId(int[] arcs) {
        return seq(oid(arcs), tlv(TAG_NULL, new byte[0]));
    }

    /** SubjectPublicKeyInfo：RSA 公钥 DER（modulus + exponent）。 */
    private static byte[] subjectPublicKeyInfo(PublicKey publicKey) {
        RSAPublicKey rsa = (RSAPublicKey) publicKey;
        byte[] rsaPub = seq(integer(rsa.getModulus()), integer(rsa.getPublicExponent()));
        return seq(algId(OID_RSA), bitString(rsaPub));
    }

    /** 构造并签名 X.509 v3 证书。 */
    private static byte[] buildCertificate(KeyPair keyPair, PrivateKey signerKey,
                                           BigInteger serial, String subjectCn, String issuerCn,
                                           Date notBefore, Date notAfter,
                                           boolean isCa, String sanHost) throws Exception {
        // TBSCertificate
        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        // version [0] EXPLICIT INTEGER 2（X.509 v3）
        byte[] versionDer = tlv(TAG_CONTEXT_0, integer(BigInteger.valueOf(2)));
        tbs.write(versionDer, 0, versionDer.length);
        tbs.write(integer(serial), 0, integer(serial).length);
        tbs.write(algId(OID_SHA256_RSA), 0, algId(OID_SHA256_RSA).length);
        tbs.write(name(issuerCn, "Median MITM"), 0, name(issuerCn, "Median MITM").length);
        tbs.write(seq(utcTime(notBefore), utcTime(notAfter)), 0, seq(utcTime(notBefore), utcTime(notAfter)).length);
        tbs.write(name(subjectCn, isCa ? "Median MITM CA" : ""), 0, name(subjectCn, isCa ? "Median MITM CA" : "").length);
        byte[] spki = subjectPublicKeyInfo(keyPair.getPublic());
        tbs.write(spki, 0, spki.length);

        // 扩展
        ByteArrayOutputStream exts = new ByteArrayOutputStream();
        if (isCa) {
            // basicConstraints critical CA:TRUE（BOOLEAN TRUE 显式编码）
            exts.write(extension2(OID_BASIC_CONSTRAINTS, true, seq(tlv(0x01, new byte[]{(byte) 0xff}))));
            byte[] ku = bitString(new byte[]{(byte) 0x06}); // keyCertSign|crlSign
            exts.write(extension2(OID_KEY_USAGE, true, ku));
        } else {
            byte[] bc = seq(tlv(0x01, new byte[]{0x00})); // CA:FALSE
            exts.write(extension2(OID_BASIC_CONSTRAINTS, true, bc));
            byte[] ku = bitString(new byte[]{(byte) 0xa0}); // digitalSignature|keyEncipherment
            exts.write(extension2(OID_KEY_USAGE, true, ku));
            // 域名 SAN：GeneralName dNSName = [2] IA5String
            byte[] san = seq(tlv(0x82, sanHost.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            exts.write(extension2(OID_SUBJECT_ALT_NAME, false, san));
            // serverAuth
            exts.write(extension2(OID_EXT_KEY_USAGE, false, oid(OID_SERVER_AUTH)));
        }
        byte[] extsDer = tlv(TAG_CONTEXT_3, tlv(TAG_SEQUENCE, exts.toByteArray()));
        tbs.write(extsDer, 0, extsDer.length);

        byte[] tbsDer = tlv(TAG_SEQUENCE, tbs.toByteArray());

        // 签名
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(signerKey);
        sig.update(tbsDer);
        byte[] signature = sig.sign();

        byte[] cert = seq(tbsDer, algId(OID_SHA256_RSA), bitString(signature));
        return cert;
    }

    /** 简化扩展构造（修正内部布尔/值顺序）。 */
    private static byte[] extension2(int[] oidArcs, boolean critical, byte[] derValue) throws java.io.IOException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        content.write(oid(oidArcs), 0, oid(oidArcs).length);
        if (critical) content.write(tlv(0x01, new byte[]{(byte) 0xff}));
        content.write(tlv(TAG_OCTET_STRING, derValue));
        return tlv(TAG_SEQUENCE, content.toByteArray());
    }

    /** 自签名 CA 证书（PEM）。 */
    public static String generateCaPem(KeyPair caKey) throws Exception {
        Date now = new Date();
        Date notBefore = new Date(now.getTime() - 24L * 3600 * 1000);
        Date notAfter = new Date(now.getTime() + 3650L * 24 * 3600 * 1000);
        byte[] der = buildCertificate(caKey, caKey.getPrivate(), BigInteger.valueOf(0x4d454449414eL),
                "Median MITM Root CA", "Median MITM Root CA", notBefore, notAfter, true, null);
        return pem("CERTIFICATE", der);
    }

    /** 为域名签发叶子证书（DER）。 */
    public static byte[] signHostCertificate(KeyPair leafKey, PrivateKey caKey, String host) throws Exception {
        Date now = new Date();
        Date notBefore = new Date(now.getTime() - 3600L * 1000);
        Date notAfter = new Date(now.getTime() + 365L * 24 * 3600 * 1000);
        BigInteger serial = new BigInteger(64, new SecureRandom());
        return buildCertificate(leafKey, caKey, serial, host, "Median MITM Root CA",
                notBefore, notAfter, false, host);
    }

    /** DER → PEM。 */
    public static String pem(String type, byte[] der) {
        String b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }

    /** 私钥 → PKCS#8 PEM（DER 编码的 PrivateKeyInfo 由标准库 getEncoded 提供）。 */
    public static String privateKeyPem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    /** 计算文件指纹（SHA-256 hex），用于安装 CA 后核对。 */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}

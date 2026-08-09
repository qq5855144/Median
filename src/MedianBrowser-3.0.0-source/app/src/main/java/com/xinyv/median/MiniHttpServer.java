package com.xinyv.median;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简 HTTP/1.1 服务器（零第三方依赖）。
 *
 * - 单 ServerSocket + 固定线程池；
 * - 支持 GET / POST / OPTIONS（CORS 预检）；
 * - 请求体按 Content-Length 读取，限制 1MB；
 * - 每连接超时保护，避免慢客户端占满线程；
 * - 路由统一交给 McpService 处理。
 */
public final class MiniHttpServer {
    /** HTTP 响应。 */
    public static final class Response {
        public final int status;
        public final String contentType;
        public final byte[] body;
        public Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType == null ? "application/json; charset=utf-8" : contentType;
            this.body = body == null ? new byte[0] : body;
        }
        public static Response json(int status, String json) {
            return new Response(status, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }
        public static Response ok(String json) { return json(200, json); }
        public static Response unauthorized() { return json(401, "{\"error\":\"unauthorized\"}"); }
        public static Response error(int status, String message) {
            return json(status, "{\"error\":\"" + message.replace("\"", "'") + "\"}");
        }
    }

    public interface Handler {
        Response handle(String method, String path, Map<String, String> headers, byte[] body);
    }

    private static final int MAX_BODY = 1024 * 1024;
    private static final int SO_TIMEOUT_MS = 15000;

    private final String host;
    private final int port;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;

    public MiniHttpServer(String host, int port, Handler handler) {
        this.host = host;
        this.port = port;
        this.handler = handler;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(host, port));
        running.set(true);
        pool = Executors.newFixedThreadPool(6);
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "median-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
    }

    public int port() { return serverSocket == null ? port : serverSocket.getLocalPort(); }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (!running.get()) { try { socket.close(); } catch (IOException ignored) { } return; }
                socket.setSoTimeout(SO_TIMEOUT_MS);
                pool.execute(new ConnectionTask(socket));
            } catch (IOException e) {
                if (running.get()) {
                    // 短暂退避后继续接受连接
                    try { Thread.sleep(20); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private final class ConnectionTask implements Runnable {
        private final Socket socket;
        ConnectionTask(Socket s) { socket = s; }

        @Override public void run() {
            try {
                socket.setSoTimeout(SO_TIMEOUT_MS);
                InputStream in = new BufferedInputStream(socket.getInputStream());
                OutputStream out = new BufferedOutputStream(socket.getOutputStream());
                // 仅处理单请求（每次连接处理一个请求后关闭，保持简单可靠）
                byte[] response = readAndHandle(in);
                out.write(response);
                out.flush();
            } catch (SocketTimeoutException ignored) { }
            catch (IOException ignored) { }
            finally {
                try { socket.close(); } catch (IOException ignored) { }
            }
        }

        private byte[] readAndHandle(InputStream in) throws IOException {
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return plain(400, "text/plain; charset=utf-8", "bad request");
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) return plain(400, "text/plain; charset=utf-8", "bad request line");
            String method = parts[0];
            String path = parts[1];

            Map<String, String> headers = new HashMap<String, String>();
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String name = line.substring(0, idx).trim().toLowerCase();
                    String value = line.substring(idx + 1).trim();
                    headers.put(name, value);
                    if ("content-length".equals(name)) {
                        try { contentLength = Math.min(Integer.parseInt(value), MAX_BODY); }
                        catch (NumberFormatException ignored) { }
                    }
                }
            }
            byte[] body = new byte[0];
            if (contentLength > 0) {
                body = readBody(in, contentLength);
            }
            if ("OPTIONS".equalsIgnoreCase(method)) {
                return plain(204, "text/plain", "");
            }
            Response response = handler.handle(method, path, headers, body);
            if (response == null) response = Response.error(500, "no response");
            return buildResponse(response.status, response.contentType, response.body);
        }
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(length);
        byte[] buffer = new byte[4096];
        int remaining = length;
        while (remaining > 0) {
            int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (n < 0) break;
            out.write(buffer, 0, n);
            remaining -= n;
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
            if (out.size() > 8192) break;
        }
        if (out.size() == 0 && c == -1) return null;
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] plain(int status, String contentType, String text) {
        return buildResponse(status, contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildResponse(int status, String contentType, byte[] body) {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        head.append("Content-Type: ").append(contentType == null ? "application/json; charset=utf-8" : contentType).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        head.append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Median-Token, MCP-Protocol-Version, Mcp-Method, Mcp-Session-Id\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[headBytes.length + body.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(body, 0, out, headBytes.length, body.length);
        return out;
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            default: return "Status";
        }
    }
}
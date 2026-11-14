package com.qinghaipao.autorun.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal HTTP/1.1 server on a plain {@link ServerSocket}.
 *
 * <p>Why hand-rolled: the WebView loads the Vue bundle over {@code http://} and
 * the bundle talks to relative paths, so a real origin is required. Android's
 * {@code android.jar} does not ship {@code com.sun.net.httpserver}, and pulling
 * in NanoHTTPD/Ktor for something this small is not worth the dex. What is
 * actually needed is a handful of routes plus static files.
 *
 * <p>Deliberate simplifications, all safe for this workload:
 * <ul>
 *   <li>{@code Connection: close} on every response — one request per socket.
 *       The WebView keeps its own connection pool, so the extra handshakes are
 *       negligible next to parsing a request body twice.</li>
 *   <li>No chunked request bodies. The browser always sends Content-Length for
 *       JSON payloads.</li>
 *   <li>Binds to loopback only. Nothing outside the device can reach the API
 *       that holds the session token.</li>
 * </ul>
 */
public final class HttpServer {

    public static final class Request {
        public String method = "GET";
        public String path = "/";
        public String queryString = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** Parsed query params (last value wins; the APIs never repeat a key). */
        public Map<String, String> queryParams() {
            Map<String, String> out = new LinkedHashMap<>();
            if (queryString == null || queryString.isEmpty()) return out;
            for (String pair : queryString.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                out.put(decode(k), decode(v));
            }
            return out;
        }

        private static String decode(String s) {
            try {
                return URLDecoder.decode(s, "UTF-8");
            } catch (Exception e) {
                return s;
            }
        }
    }

    public static final class Response {
        public int status = 200;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public void setHeader(String k, String v) {
            headers.put(k, v);
        }

        public void json(int status, String json) {
            this.status = status;
            setHeader("Content-Type", "application/json; charset=utf-8");
            this.body = json.getBytes(StandardCharsets.UTF_8);
        }

        public void text(int status, String text) {
            this.status = status;
            setHeader("Content-Type", "text/plain; charset=utf-8");
            this.body = text.getBytes(StandardCharsets.UTF_8);
        }

        public void bytes(int status, String contentType, byte[] data) {
            this.status = status;
            if (contentType != null) setHeader("Content-Type", contentType);
            this.body = data == null ? new byte[0] : data;
        }
    }

    public interface Handler {
        void handle(Request req, Response res) throws Exception;
    }

    private static final int MAX_BODY_BYTES = 12 * 1024 * 1024;

    private final int port;
    private final Handler handler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService pool;

    public HttpServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) return;
        serverSocket = new ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"));
        pool = Executors.newFixedThreadPool(6);
        acceptThread = new Thread(this::acceptLoop, "autorun-http");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket socket = serverSocket.accept();
                pool.execute(() -> serve(socket));
            } catch (IOException e) {
                if (running.get()) {
                    L.w("Autorun/Http", "accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(20000);
            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            Request req = readRequest(in);
            Response res = new Response();
            if (req == null) {
                res.text(400, "bad request");
            } else {
                try {
                    handler.handle(req, res);
                } catch (Exception e) {
                    L.e("Autorun/Http", req.method + " " + req.path + " failed", e);
                    res.json(500, "{\"code\":1,\"msg\":\"" + escape(e.getMessage()) + "\"}");
                }
            }
            writeResponse(s.getOutputStream(), req, res);
        } catch (Exception e) {
            L.w("Autorun/Http", "connection error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- parsing

    private Request readRequest(InputStream in) throws IOException {
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isEmpty()) return null;

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;

        Request req = new Request();
        req.method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        int q = target.indexOf('?');
        req.path = q < 0 ? target : target.substring(0, q);
        req.queryString = q < 0 ? "" : target.substring(q + 1);

        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            req.headers.put(
                    line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    line.substring(colon + 1).trim());
        }

        int len = 0;
        String cl = req.headers.get("content-length");
        if (cl != null) {
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (len > MAX_BODY_BYTES) throw new IOException("body too large: " + len);
        if (len > 0) {
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
            req.body = buf;
        }
        return req;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') bos.write(c);
        }
        if (c == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void writeResponse(OutputStream out, Request req, Response res) throws IOException {
        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(res.status).append(' ').append(reason(res.status)).append("\r\n");
        if (!res.headers.containsKey("Content-Type")) {
            res.headers.put("Content-Type", "text/plain; charset=utf-8");
        }
        for (Map.Entry<String, String> e : res.headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("Content-Length: ").append(res.body.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (!"HEAD".equals(req == null ? "" : req.method)) out.write(res.body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default:  return "OK";
        }
    }

    // --------------------------------------------------------------- static

    public static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".ico")) return "image/x-icon";
        if (n.endsWith(".woff2")) return "font/woff2";
        if (n.endsWith(".woff")) return "font/woff";
        if (n.endsWith(".ttf")) return "font/ttf";
        if (n.endsWith(".eot")) return "application/vnd.ms-fontobject";
        return "application/octet-stream";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}

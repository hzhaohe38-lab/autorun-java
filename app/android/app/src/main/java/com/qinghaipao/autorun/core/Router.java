package com.qinghaipao.autorun.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;

/**
 * Request routing: local engines first, upstream proxy second, static last.
 *
 * <p>The route table is a copy of the Node server's, because the Vue bundle was
 * built against it and must not be touched:
 * <pre>
 *   /api/auto-sign/*  → SignEngine
 *   /api/auto-run/*   → RunEngine
 *   /auth  /clubactivity  /unirun  → proxy to run-lb.tanmasports.com/v1
 *   everything else   → assets/web, with index.html fallback (history router)
 * </pre>
 *
 * <p>The proxy deliberately does <b>not</b> recompute the signature: the client
 * already sent one for the exact bytes it produced, and re-serializing the body
 * here would produce a different string and a rejected request.
 */
public final class Router implements HttpServer.Handler {

    private static final String[] PASSTHROUGH = {
            "content-type", "appkey", "sign", "token", "user-agent",
            "accept", "accept-language"
    };

    /** Where the built frontend lives: assets inside the APK, a directory on desktop. */
    public interface WebAssets {
        InputStream open(String path) throws IOException;
    }

    private final WebAssets web;
    private final SignEngine signEngine;
    private final RunEngine runEngine;

    public Router(WebAssets web, SignEngine signEngine, RunEngine runEngine) {
        this.web = web;
        this.signEngine = signEngine;
        this.runEngine = runEngine;
    }

    @Override
    public void handle(HttpServer.Request req, HttpServer.Response res) throws Exception {
        String path = req.path == null ? "/" : req.path;

        if ("OPTIONS".equals(req.method)) {
            cors(res);
            res.status = 204;
            return;
        }

        if (path.startsWith("/api/auto-sign/")) {
            handleSign(req, res, path.substring("/api/auto-sign/".length()));
            return;
        }
        if (path.startsWith("/api/auto-run/")) {
            handleRun(req, res, path.substring("/api/auto-run/".length()));
            return;
        }
        if (isUpstreamPath(path)) {
            proxy(req, res);
            return;
        }
        if ("GET".equals(req.method) || "HEAD".equals(req.method)) {
            serveStatic(path, res);
            return;
        }
        res.text(404, "not found");
    }

    // -------------------------------------------------------------- static

    /** Serves {@code assets/web/**}; unknown document paths fall back to index.html. */
    private void serveStatic(String path, HttpServer.Response res) {
        String rel = "/".equals(path) ? "index.html" : path.substring(1);
        if (rel.contains("..")) {
            res.text(404, "not found");
            return;
        }
        if (!assetExists("web/" + rel)) {
            // vue-router runs in history mode, so a bare path is a page, not a 404.
            if (!rel.contains(".")) {
                serveAsset("web/index.html", res);
            } else {
                res.text(404, "not found");
            }
            return;
        }
        serveAsset("web/" + rel, res);
    }

    private boolean assetExists(String name) {
        try (InputStream in = web.open(name)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    private void serveAsset(String name, HttpServer.Response res) {
        try (InputStream in = web.open(name);
             ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);
            res.bytes(200, HttpServer.mimeOf(name), bos.toByteArray());
        } catch (IOException e) {
            res.text(404, "not found");
        }
    }

    private static boolean isUpstreamPath(String path) {
        // /auth is both an API prefix and the login page route. Only the prefix
        // with a following segment is an API call; a bare /auth must fall through
        // to the SPA, otherwise refreshing the login page answers 404.
        if ("/auth".equals(path)) return false;
        return path.startsWith("/auth/")
                || path.startsWith("/clubactivity")
                || path.startsWith("/unirun");
    }

    // ------------------------------------------------------------- engines

    private void handleSign(HttpServer.Request req, HttpServer.Response res, String action) {
        cors(res);
        JSONObject body = bodyOf(req);
        String studentId = req.queryParams().getOrDefault("studentId", body.optString("studentId", ""));
        JSONObject out;
        switch (action) {
            case "schedule":
                out = signEngine.schedule(body);
                break;
            case "cancel":
                out = signEngine.cancel(body);
                break;
            case "status":
                out = signEngine.status(studentId);
                break;
            case "watch":
                out = signEngine.watch(body);
                break;
            case "token":
                out = signEngine.setToken(body);
                break;
            case "exec-now":
                out = signEngine.execNow(body);
                break;
            case "cron":
                out = new JSONObject();
                try {
                    out.put("code", 10000);
                    out.put("msg", "ok");
                    out.put("executed", signEngine.tick());
                } catch (Exception ignored) {
                }
                break;
            default:
                out = new JSONObject();
                try {
                    out.put("code", 1);
                    out.put("msg", "unknown action");
                } catch (Exception ignored) {
                }
        }
        res.json(200, out.toString());
    }

    private void handleRun(HttpServer.Request req, HttpServer.Response res, String action) {
        cors(res);
        JSONObject body = bodyOf(req);
        String studentId = req.queryParams().getOrDefault("studentId", body.optString("studentId", ""));
        JSONObject out;
        switch (action) {
            case "config":
                out = runEngine.saveConfig(body);
                break;
            case "token":
                out = runEngine.setToken(body.optString("studentId", ""), body.optString("token", ""));
                break;
            case "status":
                out = runEngine.status(studentId);
                break;
            case "cancel":
                out = runEngine.cancel(body.optString("studentId", studentId));
                break;
            case "cron":
                out = new JSONObject();
                try {
                    out.put("code", 10000);
                    out.put("msg", "ok");
                    out.put("executed", runEngine.tick());
                } catch (Exception ignored) {
                }
                break;
            default:
                out = new JSONObject();
                try {
                    out.put("code", 1);
                    out.put("msg", "unknown action");
                } catch (Exception ignored) {
                }
        }
        res.json(200, out.toString());
    }

    private static JSONObject bodyOf(HttpServer.Request req) {
        String text = req.bodyText();
        if (text == null || text.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // --------------------------------------------------------------- proxy

    /**
     * Forwards to {@code https://run-lb.tanmasports.com/v1<path>} byte-for-byte.
     *
     * <p>No Accept-Encoding is set on the outgoing request, so HttpURLConnection
     * negotiates gzip itself and transparently decompresses; the corresponding
     * response headers are then stripped, since what the WebView receives is
     * already plain. Letting the original header through instead would hand the
     * browser gzip bytes labelled as identity.
     */
    private void proxy(HttpServer.Request req, HttpServer.Response res) throws IOException {
        HttpURLConnection c = null;
        try {
            String url = Const.BASE + req.path
                    + (req.queryString == null || req.queryString.isEmpty() ? "" : "?" + req.queryString);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(req.method);
            c.setConnectTimeout(Const.UPSTREAM_TIMEOUT_MS);
            c.setReadTimeout(Const.UPSTREAM_TIMEOUT_MS);
            c.setInstanceFollowRedirects(false);

            for (String name : PASSTHROUGH) {
                String v = req.header(name);
                if (v != null && !v.isEmpty()) c.setRequestProperty(name, v);
            }
            if (req.header("user-agent") == null) c.setRequestProperty("User-Agent", Const.UA);

            boolean hasBody = req.body != null && req.body.length > 0;
            if (hasBody && !"GET".equals(req.method) && !"HEAD".equals(req.method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(req.body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(req.body);
                }
            }

            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] payload = in == null ? new byte[0] : readAll(in);

            String contentType = c.getContentType();
            res.bytes(status, contentType, payload);

            cors(res);
            res.setHeader("Cache-Control", "no-store");
        } catch (IOException e) {
            res.json(502, "{\"code\":1,\"msg\":\"upstream unreachable\"}");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream src = in; ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) >= 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void cors(HttpServer.Response res) {
        res.setHeader("Access-Control-Allow-Origin", "*");
        res.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", "*");
    }

    /** Kept for callers that want the lower-cased header map (debug screens). */
    static String dumpHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    static String mime(String name) {
        return HttpServer.mimeOf(name.toLowerCase(Locale.ROOT));
    }
}

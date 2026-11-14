package com.qinghaipao.autorun.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Thin signed HTTP client for the campus platform.
 *
 * <p>Uses {@link HttpURLConnection} rather than OkHttp: the only features needed
 * are "set four headers, send a body, read the text", and OkHttp would add
 * ~800 KB to the APK for connection pooling this app's request volume (~a few
 * calls per day) cannot benefit from.
 */
public final class ApiClient {

    public static final class Resp {
        public final int status;
        public final String raw;
        public final JSONObject data;   // null when the body was not JSON
        public final boolean transportOk;

        Resp(int status, String raw, JSONObject data, boolean transportOk) {
            this.status = status;
            this.raw = raw;
            this.data = data;
            this.transportOk = transportOk;
        }

        public int code() {
            return data == null ? -1 : data.optInt("code", -1);
        }

        public JSONObject response() {
            return data == null ? null : data.optJSONObject("response");
        }
    }

    private ApiClient() {}

    public static Resp get(String path, Map<String, ?> query, String token) throws IOException {
        return get(path, query, token, Const.UPSTREAM_TIMEOUT_MS);
    }

    public static Resp get(String path, Map<String, ?> query, String token, int timeoutMs)
            throws IOException {
        String sign = Signer.genSign(query, null);
        StringBuilder url = new StringBuilder(Const.BASE).append(path);
        if (query != null && !query.isEmpty()) {
            url.append('?').append(toQueryString(query));
        }
        return exec("GET", url.toString(), sign, token, null, timeoutMs);
    }

    public static Resp post(String path, Map<String, ?> body, String token, int timeoutMs)
            throws IOException {
        String sign = Signer.genSign(null, body);
        String json = Json.stringify(body);
        return exec("POST", Const.BASE + path, sign, token, json, timeoutMs);
    }

    public static Resp post(String path, Map<String, ?> body, String token) throws IOException {
        return post(path, body, token, Const.UPSTREAM_TIMEOUT_MS);
    }

    private static Resp exec(String method, String url, String sign, String token,
                             String jsonBody, int timeoutMs) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", Const.UA);
            c.setRequestProperty("appKey", Const.APP_KEY);
            c.setRequestProperty("sign", sign);
            c.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) c.setRequestProperty("token", token);

            if (jsonBody != null) {
                c.setDoOutput(true);
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }

            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : readAll(in);

            JSONObject parsed = null;
            if (!text.trim().isEmpty()) {
                try {
                    parsed = new JSONObject(text);
                } catch (Exception ignored) {
                    // WAF / HTML error page: caller sees data == null
                }
            }
            return new Resp(status, text, parsed, true);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String toQueryString(Map<String, ?> query) {
        StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, ?> e : query.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(encode(e.getKey())).append('=').append(encode(jsString(e.getValue())));
        }
        return sb.toString();
    }

    private static String jsString(Object v) {
        if (v instanceof Number) return Json.number((Number) v);
        return v == null ? "" : String.valueOf(v);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unsupported", e);
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream src = in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) >= 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

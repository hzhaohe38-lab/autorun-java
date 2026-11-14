package com.qinghaipao.autorun.core;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Port of {@code genSign} from app/src/utils/sign.js (identical to the server copy).
 *
 * <pre>
 * query 非空：键名字典序排序，跳过空值，拼 "key" + "value"
 *  + APP_KEY + APP_SECRET
 *  + JSON.stringify(body)         （body 非空时）
 * 剔除 " " "~" "!" "(" ")" "'"    （只要命中过就置 replaced）
 * replaced → encodeURIComponent(整串)
 * MD5 转大写；replaced → 追加 "encodeutf8"
 * </pre>
 *
 * <p>The {@code encodeutf8} suffix is not a hint — the platform treats the two
 * digest forms as different values, so the flag must survive every refactor.
 */
public final class Signer {

    private Signer() {}

    /**
     * @param query query parameters in the shape the JS object had, or null.
     *              Values are stringified; nulls become empty and are skipped.
     * @param body  request body as an ordered map/list/primitive, or null.
     */
    public static String genSign(Map<String, ?> query, Object body) {
        StringBuilder sb = new StringBuilder(128);

        if (query != null) {
            List<String> keys = new ArrayList<>(query.keySet());
            Collections.sort(keys);
            for (String key : keys) {
                Object raw = query.get(key);
                String value = raw == null ? "" : jsString(raw);
                if (!value.isEmpty()) sb.append(key).append(value);
            }
        }

        sb.append(Const.APP_KEY).append(Const.APP_SECRET);

        if (body != null) sb.append(Json.stringify(body));

        String signStr = sb.toString();

        boolean replaced = false;
        for (String ch : new String[]{" ", "~", "!", "(", ")", "'"}) {
            if (signStr.contains(ch)) {
                signStr = signStr.replace(ch, "");
                replaced = true;
            }
        }

        if (replaced) signStr = encodeURIComponent(signStr);

        String sign = md5Upper(signStr);
        return replaced ? sign + "encodeutf8" : sign;
    }

    /** {@code String(v)} in JS: whole numbers lose their ".0". */
    private static String jsString(Object v) {
        if (v instanceof Number) return Json.number((Number) v);
        return String.valueOf(v);
    }

    /**
     * Equivalent of JS {@code encodeURIComponent}. URLEncoder leaves {@code *}
     * alone like the JS version does, but space-encodes differently and escapes
     * {@code !~'()}, so those are folded back.
     */
    private static String encodeURIComponent(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8")
                    .replace("+", "%20")
                    .replace("%21", "!")
                    .replace("%27", "'")
                    .replace("%28", "(")
                    .replace("%29", ")")
                    .replace("%7E", "~");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 unsupported", e);
        }
    }

    private static String md5Upper(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString().toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}

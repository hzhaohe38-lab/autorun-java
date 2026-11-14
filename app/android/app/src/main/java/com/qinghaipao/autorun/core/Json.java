package com.qinghaipao.autorun.core;

import java.util.List;
import java.util.Map;

/**
 * Compact JSON writer that matches JavaScript's {@code JSON.stringify} byte for
 * byte, because the request signature is an MD5 over the serialized body. A
 * single stray space or a reordered key invalidates the signature and the
 * upstream answers 400.
 *
 * <p>Three deliberate choices, each of which differs from a naive writer:
 * <ul>
 *   <li>Maps are written in <b>insertion order</b> (pass a {@link java.util.LinkedHashMap}).
 *       Android's {@code org.json.JSONObject} keeps a HashMap, so its output order is
 *       unpredictable — unusable for signing.</li>
 *   <li>Whole doubles are written without a decimal point ({@code 2.0} → {@code 2}),
 *       like JS numbers.</li>
 *   <li>Non-ASCII is emitted raw as UTF-8; JS does not escape it either.</li>
 * </ul>
 *
 * <p>For parsing, {@code org.json} is fine — order only matters on the way out.
 */
public final class Json {

    private Json() {}

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v) ? "true" : "false");
        } else if (v instanceof Number) {
            sb.append(number((Number) v));
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object item : (List<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else {
            // Unknown type: stringify rather than emit invalid JSON, which would
            // silently produce a wrong signature instead of a loud failure.
            quote(sb, String.valueOf(v));
        }
    }

    /** Formats a number the way JS stringifies it: no trailing ".0", no locale. */
    public static String number(Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return n.toString();
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return "null";
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}

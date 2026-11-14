import java.util.LinkedHashMap;
import java.util.Map;

import com.qinghaipao.autorun.core.Signer;

/**
 * Computes the same six signature cases as tools/gen-sign.mjs so the two can be
 * compared. Signing is all-or-nothing: one wrong byte and every request is
 * rejected, so this is checked before anything touches a real account.
 */
public final class SignDiff {

    public static void main(String[] args) {
        StringBuilder out = new StringBuilder("[\n");

        out.append(row("empty", Signer.genSign(new LinkedHashMap<>(), null))).append(",\n");
        out.append(row("signInTf", Signer.genSign(q("studentId", 3983104L), null))).append(",\n");
        out.append(row("signBody", Signer.genSign(null, signBody()))).append(",\n");
        out.append(row("specialChars", Signer.genSign(null, specialChars()))).append(",\n");
        out.append(row("runRecord", Signer.genSign(null, runRecord()))).append(",\n");
        out.append(row("records", Signer.genSign(q("pageNum", 1, "pageSize", 15), null))).append("\n");
        out.append("]");

        System.out.println(out);
    }

    private static String row(String name, String sign) {
        return "  {\n    \"name\": \"" + name + "\",\n    \"sign\": \"" + sign + "\"\n  }";
    }

    private static Map<String, Object> q(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> signBody() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("activityId", 12345);
        b.put("latitude", "30.5806854");
        b.put("longitude", "103.9857758");
        b.put("signType", "1");
        b.put("studentId", 3983104);
        return b;
    }

    private static Map<String, Object> specialChars() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "成都信息工程大学 a(b)c'd!e~f");
        b.put("note", "has space");
        return b;
    }

    private static Map<String, Object> runRecord() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("againRunStatus", "0");
        b.put("againRunTime", 0);
        b.put("appVersions", "3.6.0");
        b.put("brand", "Apple");
        b.put("mobileType", "iPhone");
        b.put("sysVersions", "18.6");
        b.put("trackPoints", "[\"103.98643281-30.58113441\",\"103.98639557-30.58115000\"]");
        b.put("distanceTimeStatus", "1");
        b.put("innerSchool", "1");
        b.put("runDistance", 3000);
        b.put("runTime", 20);
        b.put("userId", 140842);
        b.put("vocalStatus", "1");
        b.put("yearSemester", "20262");
        b.put("recordDate", "2026-11-14");
        return b;
    }
}

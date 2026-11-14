import com.qinghaipao.autorun.core.Mulberry32;

/**
 * Prints hashSeed and the first draws for a few student/date keys, to confirm the
 * daily plan reproduces the same seed → same schedule as the JS engine.
 */
public final class HashDiff {

    public static void main(String[] args) {
        String[] keys = {
                "3983104:2026-11-14",
                "3983104:2026-11-15",
                "3983103:2026-11-14",
                "admin:2026-01-01",
        };
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < keys.length; i++) {
            long seed = Mulberry32.hashSeed(keys[i]);
            Mulberry32 rng = new Mulberry32(seed);
            sb.append("  {\"key\":\"").append(keys[i]).append("\",\"seed\":").append(seed)
                    .append(",\"draws\":[");
            for (int k = 0; k < 5; k++) {
                if (k > 0) sb.append(',');
                sb.append(rng.nextDouble());
            }
            sb.append("]}");
            if (i < keys.length - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(']');
        System.out.println(sb);
    }
}

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.qinghaipao.autorun.core.Mulberry32;
import com.qinghaipao.autorun.core.TrackGenerator;

/**
 * Desktop diff harness: runs the Java trajectory generator with the same seeded
 * random sequence the JS script used, so both can be compared point for point.
 *
 * <p>Deliberately free of Android imports — run it with the app's compiled
 * classes plus android.jar on the classpath (only needed because
 * TrackGenerator declares a MapRepo field; nothing Android is executed).
 */
public final class TrackDiff {

    public static void main(String[] args) throws Exception {
        String mapPath = args.length > 0 ? args[0] : "app/src/main/assets/maps/cuit_hkg.json";
        double distance = args.length > 1 ? Double.parseDouble(args[1]) : 3000;
        Double duration = (args.length > 2 && !"null".equals(args[2]))
                ? Double.valueOf(args[2]) : null;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 20261114L;

        String json = new String(Files.readAllBytes(Paths.get(mapPath)), StandardCharsets.UTF_8);
        List<double[]> points = parseMapData(json);
        if (points.isEmpty()) {
            System.out.println("{\"error\":\"no points parsed from " + mapPath + "\"}");
            return;
        }

        Mulberry32 rng = new Mulberry32(seed);
        String track = TrackGenerator.generate(distance, points, duration, rng);
        int count = countPoints(track);

        String out = "{\"seed\":" + seed
                + ",\"distance\":" + (long) distance
                + ",\"duration\":" + (duration == null ? "null" : duration.toString())
                + ",\"points\":" + count
                + ",\"track\":" + track + "}";
        Files.write(Paths.get("build/track-java.json"), out.getBytes(StandardCharsets.UTF_8));
        System.out.println("java points=" + count + " -> build/track-java.json");
    }

    /** Reads every "lng,lat" literal; the array itself is not split, its items contain commas. */
    private static List<double[]> parseMapData(String json) {
        List<double[]> out = new ArrayList<>();
        int idx = json.indexOf("\"mapData\"");
        if (idx < 0) return out;
        int open = json.indexOf('[', idx);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) return out;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(-?[0-9.]+),(-?[0-9.]+)\"")
                .matcher(json.substring(open, close));
        while (m.find()) {
            try {
                out.add(new double[]{
                        Double.parseDouble(m.group(1)),
                        Double.parseDouble(m.group(2))});
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static int countPoints(String track) {
        if (track == null || track.length() < 3) return 0;
        int n = 1;
        for (int i = 0; i < track.length(); i++) {
            if (track.charAt(i) == ',') n++;
        }
        return n;
    }
}

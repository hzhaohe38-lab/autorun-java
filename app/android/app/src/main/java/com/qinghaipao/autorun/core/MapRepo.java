package com.qinghaipao.autorun.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads campus route outlines from {@code maps/*.json}.
 *
 * <p>File shape (same as the frontend's): {@code {"mapId","mapName","mapData":["lng,lat",...]}}.
 * Only the two CUIT campuses are bundled — cuit_hkg (main loop), cuit_hkg2 (second
 * track) and cuit_lqy (Longquanyi) — because that is the only school this build
 * is for.
 *
 * <p>Data comes through {@link MapSource} rather than Android's AssetManager so the
 * engine can also run on a desktop JVM: the APK reads assets, the desktop runner
 * reads a directory, and nothing above this line has to care which.
 */
public final class MapRepo {

    /** Where {@code maps/<id>.json} can be opened from. */
    public interface MapSource {
        java.io.InputStream open(String name) throws java.io.IOException;
    }

    public static final class MapInfo {
        public final String id;
        public final String name;
        public final List<double[]> points; // [lng, lat]

        MapInfo(String id, String name, List<double[]> points) {
            this.id = id;
            this.name = name;
            this.points = points;
        }
    }

    private final MapSource source;
    private final Map<String, MapInfo> cache = new HashMap<>();

    public MapRepo(MapSource source) {
        this.source = source;
    }

    /** Returns null when the map is missing — callers fall back to the default. */
    public synchronized MapInfo get(String mapId) {
        String id = mapId == null || mapId.trim().isEmpty() ? Const.DEFAULT_MAP_ID : mapId.trim();
        if (cache.containsKey(id)) return cache.get(id);

        MapInfo info = load(id);
        if (info == null && !id.equals(Const.DEFAULT_MAP_ID)) {
            info = get(Const.DEFAULT_MAP_ID);
        }
        if (info != null) cache.put(id, info);
        return info;
    }

    public synchronized List<String> listIds() {
        List<String> ids = new ArrayList<>();
        for (String id : Const.MAP_IDS) {
            if (load(id) != null) ids.add(id);
        }
        return ids;
    }

    public synchronized List<MapInfo> list() {
        List<MapInfo> out = new ArrayList<>();
        for (String id : Const.MAP_IDS) {
            MapInfo info = get(id);
            if (info != null) out.add(info);
        }
        return out;
    }

    private MapInfo load(String mapId) {
        try (InputStream in = source.open("maps/" + mapId + ".json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) bos.write(buf, 0, n);

            JSONObject obj = new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8));
            JSONArray arr = obj.optJSONArray("mapData");
            if (arr == null) return null;

            List<double[]> pts = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                String raw = arr.optString(i, "");
                int comma = raw.indexOf(',');
                if (comma <= 0) continue;
                try {
                    double lng = Double.parseDouble(raw.substring(0, comma).trim());
                    double lat = Double.parseDouble(raw.substring(comma + 1).trim());
                    if (Double.isNaN(lng) || Double.isNaN(lat)) continue;
                    pts.add(new double[]{lng, lat});
                } catch (NumberFormatException ignored) {
                    // skip malformed point, same as the JS filter
                }
            }
            if (pts.isEmpty()) return null;

            String name = obj.optString("mapName", mapId);
            return new MapInfo(obj.optString("mapId", mapId), name, pts);
        } catch (IOException e) {
            return null;
        } catch (Exception e) {
            L.w("Autorun/Map", "bad map " + mapId + ": " + e.getMessage());
            return null;
        }
    }
}

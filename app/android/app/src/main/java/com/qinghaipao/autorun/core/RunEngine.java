package com.qinghaipao.autorun.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-run engine: one planned run per calendar day, submitted by the platform's
 * own API at a time drawn from a seed.
 *
 * <p>Ported from {@code server/index.js} + {@code server/auto-run.js} with two
 * structural changes that the whole rewrite exists for:
 * <ol>
 *   <li><b>Config and daily state are persisted</b>, so a process kill no longer
 *       loses "already ran today" and risks a second submission.</li>
 *   <li><b>The plan is recomputed from the seed, never stored as a timer.</b> A
 *       missed alarm is therefore harmless: the next process start recomputes
 *       the same target time and can still act inside the grace window.</li>
 * </ol>
 *
 * <p>Behaviour kept identical on purpose: the draw order (time first, then
 * distance), the 10-minute grace, 5 attempts, the "已过今日执行时间" settle, and
 * the record body field order (the signature covers the serialized body).
 */
public final class RunEngine {

    public static final class Config {
        public String studentId = "";
        public String token = "";
        public long userId;
        public long schoolId;
        public String gender = "";
        public JSONObject runStandard = new JSONObject();
        public String mapId = Const.DEFAULT_MAP_ID;
        public String windowStart = Const.DEFAULT_WINDOW_START;
        public String windowEnd = Const.DEFAULT_WINDOW_END;
        public int distanceMin = Const.DEFAULT_DISTANCE_MIN;
        public int distanceMax = Const.DEFAULT_DISTANCE_MAX;
        public boolean enabled = true;
        public long updatedAt;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("studentId", studentId);
                o.put("token", token);
                o.put("userId", userId);
                o.put("schoolId", schoolId);
                o.put("gender", gender);
                o.put("runStandard", runStandard == null ? new JSONObject() : runStandard);
                o.put("mapId", mapId);
                o.put("windowStart", windowStart);
                o.put("windowEnd", windowEnd);
                o.put("distanceMin", distanceMin);
                o.put("distanceMax", distanceMax);
                o.put("enabled", enabled);
                o.put("updatedAt", updatedAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Config from(JSONObject o) {
            Config c = new Config();
            c.studentId = o.optString("studentId", "");
            c.token = o.optString("token", "");
            c.userId = o.optLong("userId", 0);
            c.schoolId = o.optLong("schoolId", 0);
            c.gender = o.optString("gender", "");
            c.runStandard = o.optJSONObject("runStandard") == null ? new JSONObject() : o.optJSONObject("runStandard");
            c.mapId = o.optString("mapId", Const.DEFAULT_MAP_ID);
            c.windowStart = o.optString("windowStart", Const.DEFAULT_WINDOW_START);
            c.windowEnd = o.optString("windowEnd", Const.DEFAULT_WINDOW_END);
            c.distanceMin = o.optInt("distanceMin", Const.DEFAULT_DISTANCE_MIN);
            c.distanceMax = o.optInt("distanceMax", Const.DEFAULT_DISTANCE_MAX);
            c.enabled = o.optBoolean("enabled", true);
            c.updatedAt = o.optLong("updatedAt", 0);
            return c;
        }
    }

    public static final class Plan {
        public int hour;
        public int minute;
        public int distance;
        public long targetTimestamp;
        public String dateKey;
    }

    private static final class DayState {
        String dateKey = "";
        boolean executed;
        boolean settled;
        int attempts;
        JSONObject result;
    }

    private static final String FILE_CONFIGS = "auto-run-configs.json";
    private static final String FILE_RUNS = "auto-run-runs.json";

    private final Store store;
    private final MapRepo maps;
    private final TrackGenerator tracks;

    private final Map<String, Config> configs = new ConcurrentHashMap<>();
    private final Map<String, DayState> runs = new ConcurrentHashMap<>();

    private volatile boolean busy;

    public RunEngine(Store store, MapRepo maps) {
        this.store = store;
        this.maps = maps;
        this.tracks = new TrackGenerator(maps);
        load();
    }

    public TrackGenerator tracks() {
        return tracks;
    }

    public MapRepo maps() {
        return maps;
    }

    // ------------------------------------------------------------ persistence

    private void load() {
        JSONObject cfgRoot = store.read(FILE_CONFIGS);
        if (cfgRoot != null) {
            for (java.util.Iterator<String> it = cfgRoot.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject o = cfgRoot.optJSONObject(key);
                if (o != null) configs.put(key, Config.from(o));
            }
        }
        JSONObject runRoot = store.read(FILE_RUNS);
        if (runRoot != null) {
            for (java.util.Iterator<String> it = runRoot.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject o = runRoot.optJSONObject(key);
                if (o == null) continue;
                DayState st = new DayState();
                st.dateKey = o.optString("dateKey", "");
                st.executed = o.optBoolean("executed", false);
                st.settled = o.optBoolean("settled", false);
                st.attempts = o.optInt("attempts", 0);
                st.result = o.optJSONObject("result");
                runs.put(key, st);
            }
        }
    }

    private synchronized void persistConfigs() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Config> e : configs.entrySet()) {
                root.put(e.getKey(), e.getValue().toJson());
            }
        } catch (Exception ignored) {
        }
        store.write(FILE_CONFIGS, root);
    }

    private synchronized void persistRuns() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, DayState> e : runs.entrySet()) {
                DayState s = e.getValue();
                JSONObject o = new JSONObject();
                o.put("dateKey", s.dateKey);
                o.put("executed", s.executed);
                o.put("settled", s.settled);
                o.put("attempts", s.attempts);
                if (s.result != null) o.put("result", s.result);
                root.put(e.getKey(), o);
            }
        } catch (Exception ignored) {
        }
        store.write(FILE_RUNS, root);
    }

    // ------------------------------------------------------------ public API

    /** {@code POST /api/auto-run/config} — shape mirrors the JS response. */
    public synchronized JSONObject saveConfig(JSONObject body) {
        String sid = body.optString("studentId", "").trim();
        if (sid.isEmpty()) return err("studentId required");

        Config prev = configs.get(sid);
        Config cfg = (prev == null) ? new Config() : prev;
        cfg.studentId = sid;

        if (body.has("token")) cfg.token = body.optString("token", cfg.token);
        if (body.has("userId")) cfg.userId = body.optLong("userId", cfg.userId);
        if (body.has("schoolId")) cfg.schoolId = body.optLong("schoolId", cfg.schoolId);
        if (body.has("gender")) cfg.gender = body.optString("gender", cfg.gender);

        JSONObject rs = body.optJSONObject("runStandard");
        if (rs != null) cfg.runStandard = rs;

        String wantedMap = body.optString("mapId", cfg.mapId).trim();
        Set<String> available = new LinkedHashSet<>();
        for (MapRepo.MapInfo info : maps.list()) available.add(info.id);
        if (available.contains(wantedMap)) {
            cfg.mapId = wantedMap;
        } else if (!available.contains(cfg.mapId)) {
            cfg.mapId = Const.DEFAULT_MAP_ID;
        }

        cfg.windowStart = toHm(body.optString("windowStart", cfg.windowStart), cfg.windowStart);
        cfg.windowEnd = toHm(body.optString("windowEnd", cfg.windowEnd), cfg.windowEnd);

        int[] envelope = RunMath.resolveEnvelope(cfg.runStandard, cfg.gender,
                Const.DEFAULT_DISTANCE_MIN, Const.DEFAULT_DISTANCE_MAX);
        int wantedMin = clampToEnvelope(body.optInt("distanceMin", cfg.distanceMin), envelope[0], envelope);
        int wantedMax = clampToEnvelope(body.optInt("distanceMax", cfg.distanceMax), envelope[1], envelope);
        boolean usable = wantedMin < wantedMax;
        cfg.distanceMin = usable ? wantedMin : envelope[0];
        cfg.distanceMax = usable ? wantedMax : envelope[1];

        if (body.has("enabled")) cfg.enabled = body.optBoolean("enabled", cfg.enabled);
        cfg.updatedAt = System.currentTimeMillis();

        configs.put(sid, cfg);
        persistConfigs();

        // Saving is the point where the user expects a fresh answer about today,
        // so ask the platform and reconcile instead of trusting the local marker.
        JSONObject detect = detectTodayRun(cfg);
        DayState st = runs.get(sid);
        if (detect.optBoolean("ok", false)) {
            String today = RunMath.localDateKey(Calendar.getInstance());
            if (detect.optBoolean("found", false)) {
                DayState locked = new DayState();
                locked.dateKey = today;
                locked.executed = true;
                locked.result = new JSONObject();
                try {
                    locked.result.put("ok", true);
                    locked.result.put("msg", "今日已成功跑步，不再执行");
                    locked.result.put("time", RunMath.utcIsoNow());
                } catch (Exception ignored) {
                }
                runs.put(sid, locked);
            } else if (st != null && today.equals(st.dateKey) && st.settled && !st.executed) {
                // Nothing on the platform: a previous "skipped" verdict is stale.
                runs.remove(sid);
            }
            persistRuns();
        }

        JSONObject out = new JSONObject();
        try {
            out.put("code", 10000);
            out.put("config", cfg.toJson());
            out.put("detect", detect);
            out.put("status", status(sid));
        } catch (Exception ignored) {
        }
        return out;
    }

    public synchronized JSONObject status(String studentId) {
        String sid = studentId == null ? "" : studentId.trim();
        Config cfg = configs.get(sid);
        JSONObject out = new JSONObject();
        try {
            if (cfg == null) {
                out.put("configured", false);
                out.put("maps", mapsJson());
                return out;
            }
            Plan plan = planFor(cfg);
            int[] targets = resolveTargets(cfg);
            int[] envelope = RunMath.resolveEnvelope(cfg.runStandard, cfg.gender,
                    Const.DEFAULT_DISTANCE_MIN, Const.DEFAULT_DISTANCE_MAX);

            DayState st = runs.get(sid);
            DayState today = (st != null && plan.dateKey.equals(st.dateKey)) ? st : null;

            out.put("configured", true);
            out.put("config", cfg.toJson());
            out.put("maps", mapsJson());

            JSONObject effective = new JSONObject();
            effective.put("min", targets[0]);
            effective.put("max", targets[1]);
            out.put("effective", effective);

            JSONObject env = new JSONObject();
            env.put("min", envelope[0]);
            env.put("max", envelope[1]);
            out.put("envelope", env);

            JSONObject t = new JSONObject();
            t.put("hour", plan.hour);
            t.put("minute", plan.minute);
            t.put("distance", plan.distance);
            t.put("targetTimestamp", plan.targetTimestamp);
            t.put("dateKey", plan.dateKey);
            out.put("today", t);

            out.put("executedToday", today != null && today.executed);
            if (today != null && today.result != null) out.put("lastResult", today.result);
            else out.put("lastResult", JSONObject.NULL);
        } catch (Exception e) {
            return err(e.getMessage());
        }
        return out;
    }

    public synchronized JSONObject cancel(String studentId) {
        String sid = studentId == null ? "" : studentId.trim();
        Config cfg = configs.get(sid);
        if (cfg != null) {
            cfg.enabled = false;
            cfg.updatedAt = System.currentTimeMillis();
            persistConfigs();
        }
        return ok();
    }

    public synchronized JSONObject setToken(String studentId, String token) {
        String sid = studentId == null ? "" : studentId.trim();
        Config cfg = configs.get(sid);
        if (cfg == null) return ok();
        cfg.token = token == null ? "" : token;
        cfg.updatedAt = System.currentTimeMillis();
        persistConfigs();
        return ok();
    }

    /** Called by the scheduler; returns how many runs were submitted this pass. */
    public int tick() {
        if (busy) return 0;
        busy = true;
        int submitted = 0;
        try {
            Calendar now = Calendar.getInstance();
            String todayKey = RunMath.localDateKey(now);
            for (Config cfg : new ArrayList<>(configs.values())) {
                if (cfg == null || !cfg.enabled) continue;
                String sid = cfg.studentId;
                DayState prev = runs.get(sid);
                boolean sameDay = prev != null && todayKey.equals(prev.dateKey);
                if (sameDay && (prev.executed || prev.settled)) continue;

                Plan plan = planFor(cfg);
                long lateness = System.currentTimeMillis() - plan.targetTimestamp;
                if (lateness < 0) continue;

                if (lateness > Const.AUTO_RUN_GRACE_MS) {
                    DayState skip = new DayState();
                    skip.dateKey = todayKey;
                    skip.settled = true;
                    skip.result = new JSONObject();
                    try {
                        skip.result.put("ok", false);
                        skip.result.put("msg", "已过今日执行时间（" + RunMath.pad2(plan.hour) + ":"
                                + RunMath.pad2(plan.minute) + "），今日跳过");
                        skip.result.put("time", RunMath.utcIsoNow());
                    } catch (Exception ignored) {
                    }
                    runs.put(sid, skip);
                    persistRuns();
                    Log.i("AutoRun", sid + " 跳过: 今日执行时间已过 " + lateness + "ms");
                    continue;
                }

                JSONObject result;
                try {
                    result = execute(cfg, plan);
                } catch (Exception e) {
                    result = new JSONObject();
                    try {
                        result.put("ok", false);
                        result.put("msg", e.getMessage() == null ? "执行异常" : e.getMessage());
                        result.put("time", RunMath.utcIsoNow());
                    } catch (Exception ignored) {
                    }
                }

                int attempts = (sameDay ? prev.attempts : 0) + 1;
                boolean ok = result.optBoolean("ok", false);
                boolean gaveUp = !ok && attempts >= Const.MAX_AUTO_RUN_ATTEMPTS;
                if (gaveUp) {
                    try {
                        result.put("msg", "连续失败已放弃：" + result.optString("msg", ""));
                    } catch (Exception ignored) {
                    }
                }

                DayState st = new DayState();
                st.dateKey = todayKey;
                st.executed = ok;
                st.settled = gaveUp;
                st.attempts = attempts;
                st.result = result;
                runs.put(sid, st);
                persistRuns();

                if (ok) submitted++;
                Log.i("AutoRun", sid + (ok ? " 完成: " : gaveUp ? " 放弃: " : " 将重试: ")
                        + result.optString("msg", ""));
            }
        } finally {
            busy = false;
        }
        return submitted;
    }

    /** Milliseconds until the next pending target, or -1 when nothing is pending. */
    public long nextWakeInMs() {
        long best = -1;
        Calendar now = Calendar.getInstance();
        String todayKey = RunMath.localDateKey(now);
        for (Config cfg : configs.values()) {
            if (cfg == null || !cfg.enabled) continue;
            DayState st = runs.get(cfg.studentId);
            if (st != null && todayKey.equals(st.dateKey) && (st.executed || st.settled)) continue;
            Plan plan = planFor(cfg);
            long delta = plan.targetTimestamp - System.currentTimeMillis();
            if (delta < 0) delta = 0;
            if (best < 0 || delta < best) best = delta;
        }
        return best;
    }

    public boolean hasEnabled() {
        for (Config c : configs.values()) if (c != null && c.enabled) return true;
        return false;
    }

    // ---------------------------------------------------------------- planning

    public Plan planFor(Config cfg) {
        Calendar date = Calendar.getInstance();
        String dateKey = RunMath.localDateKey(date);

        int ws = parseHm(cfg.windowStart, 8 * 60);
        int we = parseHm(cfg.windowEnd, 22 * 60);
        int lo = Math.min(ws, we);
        int hi = Math.max(ws, we);

        // Same generator, same seed, same draw order as the JS engine: a restart
        // mid-day must reproduce the exact same plan.
        Mulberry32 rng = new Mulberry32(Mulberry32.hashSeed(cfg.studentId + ":" + dateKey));

        int span = Math.max(1, hi - lo);
        int minutesOfDay = Math.min(hi - 1 >= lo ? hi - 1 : hi,
                lo + (int) Math.floor(rng.nextDouble() * span));
        int hour = minutesOfDay / 60;
        int minute = minutesOfDay % 60;

        int[] targets = resolveTargets(cfg);
        int distance = RunMath.randomIntNonThousand(targets[0], targets[1], rng);

        Calendar target = RunMath.startOfDay(date);
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, minute);

        Plan p = new Plan();
        p.hour = hour;
        p.minute = minute;
        p.distance = distance;
        p.targetTimestamp = target.getTimeInMillis();
        p.dateKey = dateKey;
        return p;
    }

    /** Effective distance window: the user's range clamped into the school envelope. */
    private int[] resolveTargets(Config cfg) {
        int[] envelope = RunMath.resolveEnvelope(cfg.runStandard, cfg.gender,
                Const.DEFAULT_DISTANCE_MIN, Const.DEFAULT_DISTANCE_MAX);
        int min = clampToEnvelope(cfg.distanceMin, envelope[0], envelope);
        int max = clampToEnvelope(cfg.distanceMax, envelope[1], envelope);
        if (min >= max) {
            min = envelope[0];
            max = envelope[1];
        }
        return new int[]{min, max};
    }

    // -------------------------------------------------------------- execution

    private JSONObject execute(Config cfg, Plan plan) throws IOException {
        double[] timeBounds = RunMath.calculateTimeBounds(RunMath.normalizeGender(cfg.gender), cfg.runStandard);
        double timeMin = timeBounds[0];
        double timeMax = timeBounds[1];

        if (!cfg.token.isEmpty() && cfg.userId == 0) {
            // userId comes from the login payload; without it the submit body is invalid.
            throw new IOException("缺少 userId，无法提交");
        }
        if (cfg.token.isEmpty()) throw new IOException("缺少 token，无法提交");

        // Duration first, then the track — the track's pace is derived from the
        // rounded duration, exactly as the manual submit path does it.
        double duration = RunMath.computeDurationFromDistance(plan.distance, timeMin, timeMax, Math::random);
        int runTime = RunMath.normalizeRoundedRunTime(duration, plan.distance, timeMin, timeMax, Math::random);
        String trackPoints = tracks.genTrackPoints(plan.distance, cfg.mapId, (double) runTime, Math::random);
        if (trackPoints == null || "[]".equals(trackPoints)) {
            throw new IOException("轨迹生成失败（地图数据为空？）");
        }

        Calendar now = Calendar.getInstance();
        String recordDate = RunMath.localDateKey(now);
        String yearSemester = RunMath.buildYearSemester(now);

        Map<String, Object> body = buildRecordBody(
                trackPoints, Math.round(plan.distance), runTime, cfg.userId, recordDate, yearSemester);

        JSONObject result = new JSONObject();
        long t0 = System.currentTimeMillis();
        ApiClient.Resp resp = ApiClient.post("/unirun/save/run/record/new", body, cfg.token,
                Const.RUN_SUBMIT_TIMEOUT_MS);
        boolean ok = resp.code() == 10000;

        try {
            int pts = 0;
            try {
                pts = new JSONArray(trackPoints).length();
            } catch (Exception ignored) {
            }
            result.put("ok", ok);
            result.put("msg", resp.data == null
                    ? (ok ? "提交成功" : "提交失败（响应非 JSON）")
                    : resp.data.optString("msg", resp.data.optString("message", ok ? "提交成功" : "提交失败")));
            result.put("time", RunMath.utcIsoNow());
            JSONObject summary = new JSONObject();
            summary.put("dateKey", plan.dateKey);
            summary.put("plannedTime", RunMath.pad2(plan.hour) + ":" + RunMath.pad2(plan.minute));
            summary.put("mapId", cfg.mapId);
            summary.put("distance", Math.round(plan.distance));
            summary.put("runTime", runTime);
            summary.put("pace", RunMath.paceOf(plan.distance, runTime));
            summary.put("trackPoints", pts);
            summary.put("recordDate", recordDate);
            summary.put("yearSemester", yearSemester);
            result.put("summary", summary);
        } catch (Exception ignored) {
        }

        Log.i("AutoRun", "submit " + cfg.studentId + ": " + Math.round(plan.distance) + "m / "
                + runTime + "min / " + (System.currentTimeMillis() - t0) + "ms / ok=" + ok);
        return result;
    }

    /**
     * Field order is load-bearing: the sign is an MD5 over the serialized body, so
     * a LinkedHashMap (never a JSONObject) is used here.
     */
    private static Map<String, Object> buildRecordBody(String trackPoints, long distance, int runTime,
                                                       long userId, String recordDate, String yearSemester) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("againRunStatus", "0");
        b.put("againRunTime", 0);
        b.put("appVersions", "3.6.0");
        b.put("brand", "Apple");
        b.put("mobileType", "iPhone");
        b.put("sysVersions", "18.6");
        b.put("trackPoints", trackPoints);
        b.put("distanceTimeStatus", "1");
        b.put("innerSchool", "1");
        b.put("runDistance", distance);
        b.put("runTime", runTime);
        b.put("userId", userId);
        b.put("vocalStatus", "1");
        b.put("yearSemester", yearSemester);
        b.put("recordDate", recordDate);
        return b;
    }

    /** {@code GET /unirun/query/student/all/run/record} — did today's run land? */
    public JSONObject detectTodayRun(Config cfg) {
        JSONObject out = new JSONObject();
        try {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("pageNum", 1);
            q.put("pageSize", 15);
            ApiClient.Resp resp = ApiClient.get("/unirun/query/student/all/run/record", q, cfg.token, 8000);
            if (resp.code() != 10000) {
                out.put("ok", false);
                out.put("msg", resp.data == null ? "" : resp.data.optString("msg", ""));
                return out;
            }
            JSONArray list = resp.data.optJSONArray("response");
            if (list == null) {
                JSONObject r = resp.data.optJSONObject("response");
                if (r != null) list = r.optJSONArray("records");
            }
            String today = RunMath.localDateKey(Calendar.getInstance());
            JSONObject hit = null;
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject rec = list.optJSONObject(i);
                    if (rec == null) continue;
                    String createTime = rec.optString("createTime", "");
                    if (createTime.startsWith(today) && "1".equals(rec.optString("runStatus", ""))) {
                        hit = rec;
                        break;
                    }
                }
            }
            out.put("ok", true);
            out.put("found", hit != null);
            if (hit != null) out.put("record", hit);
        } catch (Exception e) {
            try {
                out.put("ok", false);
                out.put("msg", e.getMessage() == null ? "查询失败" : e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private JSONArray mapsJson() {
        JSONArray arr = new JSONArray();
        for (MapRepo.MapInfo info : maps.list()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", info.id);
                o.put("name", info.name);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    private static int clampToEnvelope(int value, int fallback, int[] envelope) {
        int v = value > 0 ? value : fallback;
        return Math.min(envelope[1], Math.max(envelope[0], v));
    }

    private static int parseHm(String value, int fallback) {
        if (value == null) return fallback;
        String raw = value.trim();
        if (!raw.matches("^\\d{1,2}:\\d{2}$")) return fallback;
        String[] parts = raw.split(":");
        int h = Integer.parseInt(parts[0]);
        int mi = Integer.parseInt(parts[1]);
        if (h < 0 || h > 23 || mi < 0 || mi > 59) return fallback;
        return h * 60 + mi;
    }

    private static String toHm(String value, String fallback) {
        if (value == null) return fallback;
        String raw = value.trim();
        return raw.matches("^\\d{1,2}:\\d{2}$") ? raw : fallback;
    }

    private static JSONObject ok() {
        JSONObject o = new JSONObject();
        try {
            o.put("code", 10000);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject err(String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("code", 1);
            o.put("msg", msg == null ? "error" : msg);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Thin log façade so the engine reads like the JS original. */
    static final class Log {
        static void i(String tag, String msg) {
            L.i("Autorun/" + tag, msg);
        }
    }
}

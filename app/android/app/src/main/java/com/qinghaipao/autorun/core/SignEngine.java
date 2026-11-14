package com.qinghaipao.autorun.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Club sign-in / sign-out engine — the single executor of 签到/签退.
 *
 * <p>Ported from the auto-sign half of {@code server/index.js}. What the frontend
 * still does is unchanged: it schedules tasks, polls {@code /status} and shows a
 * countdown. It never signs by itself, because the moment two code paths each
 * call {@code signInOrSignBack} the same event gets signed twice (that was a real
 * bug, fixed in September by making the engine the only executor).
 *
 * <p>Structural differences from the JS engine, all aimed at the one failure mode
 * that mattered: "kept it in the background and it never signed".
 * <ol>
 *   <li><b>Tasks are persisted.</b> The JS engine kept them in a Map; a process
 *       kill lost every pending sign silently. Here each mutation writes the
 *       whole table atomically.</li>
 *   <li><b>No self-chaining timer.</b> The JS scheduler replaced a setTimeout
 *       after each pass, so the trigger drifted by the pass duration. Here an
 *       exact alarm fires at the target itself and the tick is a plain scan.</li>
 *   <li><b>JIT idempotency stays.</b> Before every real request the authoritative
 *       {@code getSignInTf} is consulted; if the platform already shows the sign
 *       as done, nothing is sent — which is what makes overlapping triggers safe.</li>
 * </ol>
 *
 * <p>Kept identical on purpose: the 15-minute stale window, 8 attempts, the
 * "activity changed" drop, and the 5-minute discovery poll that catches a
 * sign-out time that only appears on the day.
 */
public final class SignEngine {

    private static final String FILE_TASKS = "sign-tasks.json";
    private static final String FILE_WATCHES = "sign-watches.json";
    private static final String FILE_LOGS = "sign-logs.json";

    private static final int MAX_LOGS_TOTAL = 200;
    private static final int MAX_LOGS_PER_STUDENT = 50;
    private static final long DROP_EXECUTED_AFTER_MS = 48L * 60 * 60 * 1000;
    private static final long DROP_FUTURE_AFTER_MS = 7L * 24 * 60 * 60 * 1000;

    // ------------------------------------------------------------------ model

    public static final class Task {
        public String id = "";
        public String studentId = "";
        public String token = "";
        public long activityId;
        public String activityName = "";
        public String signType = "";
        public String signTime = "";
        public long targetTimestamp;
        public String latitude = "";
        public String longitude = "";
        public String date = "";
        public boolean executed;
        public JSONObject result;
        public int attempts;
        public long createdAt;

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("studentId", studentId);
                o.put("token", token);
                o.put("activityId", activityId);
                o.put("activityName", activityName);
                o.put("signType", signType);
                o.put("signTime", signTime);
                o.put("targetTimestamp", targetTimestamp);
                o.put("latitude", latitude);
                o.put("longitude", longitude);
                o.put("date", date);
                o.put("executed", executed);
                if (result != null) o.put("result", result);
                o.put("attempts", attempts);
                o.put("createdAt", createdAt);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Task from(JSONObject o) {
            Task t = new Task();
            t.id = o.optString("id", "");
            t.studentId = o.optString("studentId", "");
            t.token = o.optString("token", "");
            t.activityId = o.optLong("activityId", 0);
            t.activityName = o.optString("activityName", "");
            t.signType = o.optString("signType", "");
            t.signTime = o.optString("signTime", "");
            t.targetTimestamp = o.optLong("targetTimestamp", 0);
            t.latitude = o.optString("latitude", "");
            t.longitude = o.optString("longitude", "");
            t.date = o.optString("date", "");
            t.executed = o.optBoolean("executed", false);
            t.result = o.optJSONObject("result");
            t.attempts = o.optInt("attempts", 0);
            t.createdAt = o.optLong("createdAt", 0);
            return t;
        }

        Task copy() {
            return from(toJson());
        }
    }

    private static final class Watch {
        String studentId = "";
        String token = "";
        boolean enabled = true;
        long updatedAt;
    }

    // ----------------------------------------------------------------- state

    private final Store store;
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();
    private final List<JSONObject> logs = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SignEngine(Store store) {
        this.store = store;
        load();
    }

    private void load() {
        JSONObject taskRoot = store.read(FILE_TASKS);
        if (taskRoot != null) {
            for (java.util.Iterator<String> it = taskRoot.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject o = taskRoot.optJSONObject(key);
                if (o != null) tasks.put(key, Task.from(o));
            }
        }
        JSONObject watchRoot = store.read(FILE_WATCHES);
        if (watchRoot != null) {
            for (java.util.Iterator<String> it = watchRoot.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject o = watchRoot.optJSONObject(key);
                if (o == null) continue;
                Watch w = new Watch();
                w.studentId = o.optString("studentId", key);
                w.token = o.optString("token", "");
                w.enabled = o.optBoolean("enabled", true);
                w.updatedAt = o.optLong("updatedAt", 0);
                watches.put(key, w);
            }
        }
        JSONArray logArr = null;
        JSONObject logRoot = store.read(FILE_LOGS);
        if (logRoot != null) logArr = logRoot.optJSONArray("logs");
        if (logArr != null) {
            for (int i = 0; i < logArr.length(); i++) {
                JSONObject o = logArr.optJSONObject(i);
                if (o != null) logs.add(o);
            }
        }
    }

    private synchronized void persistTasks() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Task> e : tasks.entrySet()) {
                root.put(e.getKey(), e.getValue().toJson());
            }
        } catch (Exception ignored) {
        }
        store.write(FILE_TASKS, root);
    }

    private synchronized void persistWatches() {
        JSONObject root = new JSONObject();
        try {
            for (Map.Entry<String, Watch> e : watches.entrySet()) {
                Watch w = e.getValue();
                JSONObject o = new JSONObject();
                o.put("studentId", w.studentId);
                o.put("token", w.token);
                o.put("enabled", w.enabled);
                o.put("updatedAt", w.updatedAt);
                root.put(e.getKey(), o);
            }
        } catch (Exception ignored) {
        }
        store.write(FILE_WATCHES, root);
    }

    private synchronized void persistLogs() {
        JSONObject root = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : logs) arr.put(o);
            root.put("logs", arr);
        } catch (Exception ignored) {
        }
        store.write(FILE_LOGS, root);
    }

    // ------------------------------------------------------------ scheduling

    /** {@code POST /api/auto-sign/schedule} */
    public JSONObject schedule(JSONObject body) {
        JSONArray arr = body.optJSONArray("tasks");
        String studentId = body.optString("studentId", "");
        String token = body.optString("token", "");
        if (arr == null || arr.length() == 0) return err("no tasks");

        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            String key = taskKeyOf(studentId, t.optLong("activityId", 0), t.optString("signType", ""));
            Task existing = tasks.get(key);
            String newDate = dateStrOf(t.optLong("targetTimestamp", 0));
            boolean sameDay = existing != null && newDate.equals(existing.date);

            Task nt = new Task();
            nt.id = key;
            nt.studentId = studentId;
            nt.token = token;
            nt.activityId = t.optLong("activityId", 0);
            nt.activityName = t.optString("activityName", "");
            nt.signType = t.optString("signType", "");
            nt.signTime = t.optString("signTime", "");
            nt.targetTimestamp = t.optLong("targetTimestamp", 0);
            nt.latitude = t.optString("latitude", "");
            nt.longitude = t.optString("longitude", "");
            nt.date = newDate;
            // Cross-day reuse of the key must not inherit yesterday's completion.
            nt.executed = sameDay && existing.executed;
            nt.result = (sameDay && existing.executed) ? existing.result : null;
            nt.attempts = sameDay ? existing.attempts : 0;
            nt.createdAt = System.currentTimeMillis();

            tasks.put(key, nt);
            count++;
        }
        persistTasks();
        log("AutoSign", "scheduled " + count + " tasks for student " + studentId);

        JSONObject out = ok();
        try {
            out.put("msg", "scheduled");
            out.put("count", count);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code POST /api/auto-sign/cancel} */
    public JSONObject cancel(JSONObject body) {
        String studentId = body.optString("studentId", "");
        long activityId = body.optLong("activityId", 0);
        String signType = body.optString("signType", "");
        int removed = 0;

        if (activityId != 0 && !signType.isEmpty() && !studentId.isEmpty()) {
            if (tasks.remove(taskKeyOf(studentId, activityId, signType)) != null) removed++;
        } else if (!studentId.isEmpty()) {
            for (Map.Entry<String, Task> e : new ArrayList<>(tasks.entrySet())) {
                if (studentId.equals(e.getValue().studentId)) {
                    tasks.remove(e.getKey());
                    removed++;
                }
            }
        }
        if (removed > 0) persistTasks();

        JSONObject out = ok();
        try {
            out.put("msg", "cancelled");
            out.put("removed", removed);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code GET /api/auto-sign/status} */
    public JSONObject status(String studentId) {
        JSONArray arr = new JSONArray();
        for (Task t : tasks.values()) {
            if (!String.valueOf(studentId).equals(t.studentId)) continue;
            JSONObject o = new JSONObject();
            try {
                o.put("activityId", t.activityId);
                o.put("activityName", t.activityName);
                o.put("signType", t.signType);
                o.put("signTime", t.signTime);
                o.put("targetTimestamp", t.targetTimestamp);
                o.put("executed", t.executed);
                o.put("result", t.result == null ? JSONObject.NULL : t.result);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }

        JSONArray logArr = new JSONArray();
        synchronized (this) {
            for (JSONObject o : logs) {
                if (String.valueOf(studentId).equals(o.optString("studentId", ""))) logArr.put(o);
            }
        }

        JSONObject out = ok();
        try {
            out.put("tasks", arr);
            out.put("logs", logArr);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code POST /api/auto-sign/watch} — also refreshes the engine's token. */
    public JSONObject watch(JSONObject body) {
        String sid = body.optString("studentId", "");
        String token = body.optString("token", "");
        boolean enabled = !body.has("enabled") || body.optBoolean("enabled", true);
        if (sid.isEmpty()) return err("studentId required");

        if (!enabled) {
            watches.remove(sid);
            persistWatches();
            JSONObject out = ok();
            try {
                out.put("watching", false);
            } catch (Exception ignored) {
            }
            return out;
        }
        if (token.isEmpty()) return err("token required");

        Watch w = new Watch();
        w.studentId = sid;
        w.token = token;
        w.enabled = true;
        w.updatedAt = System.currentTimeMillis();
        watches.put(sid, w);
        persistWatches();

        // Flip the switch and discovery runs at once instead of waiting 5 minutes.
        new Thread(() -> {
            try {
                Thread.sleep(800);
                discovery();
            } catch (InterruptedException ignored) {
            }
        }, "autorun-watch").start();

        JSONObject out = ok();
        try {
            out.put("watching", true);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code POST /api/auto-sign/token} */
    public JSONObject setToken(JSONObject body) {
        String sid = body.optString("studentId", "");
        String token = body.optString("token", "");
        if (sid.isEmpty() || token.isEmpty()) return err("studentId/token required");

        int updated = 0;
        for (Task t : tasks.values()) {
            if (!sid.equals(t.studentId) || t.executed) continue;
            if (token.equals(t.token)) continue;
            t.token = token;
            updated++;
        }
        if (updated > 0) persistTasks();

        JSONObject out = ok();
        try {
            out.put("updated", updated);
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {@code POST /api/auto-sign/exec-now} — manual "立即检查/执行". */
    public JSONObject execNow(JSONObject body) {
        String sid = body.optString("studentId", "");
        String token = body.optString("token", "");
        long activityId = body.optLong("activityId", 0);
        String st = body.optString("signType", "");
        String key = taskKeyOf(sid, activityId, st);

        Task task = tasks.get(key);
        if (task != null && task.executed) {
            JSONObject out = ok();
            try {
                out.put("ok", true);
                out.put("already", true);
                out.put("msg", "该任务已执行，无需重复执行");
                out.put("label", signTypeLabel(st));
            } catch (Exception ignored) {
            }
            return out;
        }

        if (task == null) {
            task = new Task();
            task.id = key;
            task.studentId = sid;
            task.token = token;
            task.activityId = activityId;
            task.activityName = body.optString("activityName", "");
            task.signType = st;
            task.targetTimestamp = System.currentTimeMillis();
            task.latitude = body.optString("latitude", "");
            task.longitude = body.optString("longitude", "");
        } else {
            task = task.copy();
            if (!token.isEmpty()) task.token = token;
            if (body.has("latitude")) task.latitude = body.optString("latitude", task.latitude);
            if (body.has("longitude")) task.longitude = body.optString("longitude", task.longitude);
        }

        Outcome outcome;
        try {
            outcome = attempt(task, "force", false);
        } catch (Exception e) {
            outcome = Outcome.error(e.getMessage());
        }
        settle(task, outcome);

        boolean ok = "already".equals(outcome.kind) || ("signed".equals(outcome.kind) && outcome.ok);
        JSONObject out = ok();
        try {
            out.put("ok", ok);
            out.put("already", "already".equals(outcome.kind));
            out.put("msg", outcome.msg != null ? outcome.msg
                    : ("already".equals(outcome.kind) ? "已由其他入口完成" : ""));
            out.put("label", signTypeLabel(st));
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------- scheduling

    /**
     * One scheduler pass: fire every due, unexecuted task.
     *
     * @return number of tasks that reached a terminal state
     */
    public int tick() {
        if (!running.compareAndSet(false, true)) return 0;
        int finished = 0;
        try {
            long now = System.currentTimeMillis();
            for (Task snapshot : new ArrayList<>(tasks.values())) {
                if (snapshot.executed) continue;
                long target = snapshot.targetTimestamp;
                if (target == 0 || now < target) continue;

                Task task = tasks.get(snapshot.id);
                if (task == null || task.executed) continue;

                boolean stale = now - target > Const.STALE_TOLERANCE_MS;
                Outcome outcome;
                try {
                    outcome = attempt(task, "scheduled", stale);
                } catch (Exception e) {
                    outcome = Outcome.error(e.getMessage());
                }
                if (settle(task, outcome)) finished++;
            }
            cleanup();
        } finally {
            running.set(false);
        }
        return finished;
    }

    private static final class Outcome {
        String kind;      // already | drop | signed | error
        boolean ok;
        String msg;

        static Outcome error(String msg) {
            Outcome o = new Outcome();
            o.kind = "error";
            o.ok = false;
            o.msg = msg == null ? "请求异常" : msg;
            return o;
        }
    }

    /**
     * JIT-check the authoritative state, then sign if it is genuinely pending.
     * The check is what makes concurrent triggers safe: whoever gets there second
     * sees the platform already marked and sends nothing.
     */
    private Outcome attempt(Task task, String mode, boolean stale) throws IOException {
        String signType = task.signType;
        if (!"1".equals(signType) && !"2".equals(signType)) {
            Outcome o = new Outcome();
            o.kind = "drop";
            o.msg = "未知签到类型";
            return o;
        }

        boolean statusOk = false;
        JSONObject tf = null;
        try {
            ApiClient.Resp r = fetchSignTask(task.studentId, task.token);
            statusOk = r.code() == 10000;
            tf = r.response();
        } catch (Exception e) {
            statusOk = false; // JIT unavailable — best effort, keep going
        }

        if (statusOk) {
            long tfId = tf == null ? 0 : tf.optLong("activityId", 0);
            if (isSignDone(tf, signType)) {
                Outcome o = new Outcome();
                o.kind = "already";
                o.ok = true;
                o.msg = "该" + signTypeLabel(signType) + "已由其他入口完成";
                return o;
            }
            if (stale) {
                Outcome o = new Outcome();
                o.kind = "drop";
                o.msg = "已逾期超过容忍窗口，取消补签";
                return o;
            }
            if ("scheduled".equals(mode)) {
                if (tfId == 0) {
                    Outcome o = new Outcome();
                    o.kind = "drop";
                    o.msg = "当前无待执行的活动";
                    return o;
                }
                if (task.activityId != 0 && tfId != task.activityId) {
                    Outcome o = new Outcome();
                    o.kind = "drop";
                    o.msg = "活动已变更，跳过旧任务";
                    return o;
                }
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("activityId", task.activityId);
        body.put("latitude", task.latitude == null ? "" : task.latitude);
        body.put("longitude", task.longitude == null ? "" : task.longitude);
        body.put("signType", signType);
        body.put("studentId", parseLong(task.studentId));

        ApiClient.Resp resp = ApiClient.post("/clubactivity/signInOrSignBack", body, task.token);

        Outcome o = new Outcome();
        o.kind = "signed";
        o.ok = resp.code() == 10000;
        o.msg = resp.data == null ? "" : resp.data.optString("msg",
                resp.data.optString("message", ""));
        return o;
    }

    /** Persist an outcome. Returns true when the task reached a terminal state. */
    private boolean settle(Task task, Outcome outcome) {
        String key = taskKeyOf(task.studentId, task.activityId, task.signType);
        String label = signTypeLabel(task.signType);
        String time = RunMath.utcIsoNow();

        switch (outcome.kind) {
            case "drop": {
                tasks.remove(key);
                persistTasks();
                pushLog(task, label, true, outcome.msg != null ? outcome.msg : "任务已清理");
                return true;
            }
            case "already": {
                task.executed = true;
                task.attempts = 0;
                task.result = resultJson(true, "already", outcome.msg, time);
                tasks.put(key, task);
                persistTasks();
                pushLog(task, label, true, outcome.msg != null ? outcome.msg : "已由其他入口完成");
                return true;
            }
            case "signed": {
                task.executed = true;
                task.attempts = 0;
                task.result = resultJson(outcome.ok, "signed",
                        outcome.msg != null && !outcome.msg.isEmpty()
                                ? outcome.msg : (outcome.ok ? "执行成功" : "执行失败"), time);
                tasks.put(key, task);
                persistTasks();
                pushLog(task, label, outcome.ok,
                        outcome.msg != null && !outcome.msg.isEmpty()
                                ? outcome.msg : (outcome.ok ? "执行成功" : "执行失败"));
                return true;
            }
            default: {
                task.attempts = task.attempts + 1;
                if (task.attempts >= Const.MAX_SIGN_ATTEMPTS) {
                    task.executed = true;
                    task.result = resultJson(false, "error",
                            outcome.msg != null ? outcome.msg : "执行异常", time);
                    tasks.put(key, task);
                    persistTasks();
                    pushLog(task, label, false, "连续失败已放弃：" + (outcome.msg == null ? "" : outcome.msg));
                    return true;
                }
                tasks.put(key, task);
                persistTasks();
                return false;
            }
        }
    }

    private static JSONObject resultJson(boolean ok, String action, String msg, String time) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", ok);
            o.put("action", action);
            o.put("msg", msg == null ? "" : msg);
            o.put("time", time);
        } catch (Exception ignored) {
        }
        return o;
    }

    // -------------------------------------------------------------- discovery

    /**
     * Ask the platform what is still pending and queue it.
     *
     * <p>This is the piece that catches a sign-out time which only materialises on
     * the day of the activity — the browser never sees it in advance, so without
     * server-side discovery the sign-in happens and the sign-out never does.
     */
    public int discovery() {
        int total = 0;
        for (Watch w : new ArrayList<>(watches.values())) {
            if (w == null || !w.enabled || w.token == null || w.token.isEmpty()) continue;
            try {
                total += discoverFor(w);
            } catch (Exception e) {
                log("AutoSign", "watch " + w.studentId + " discovery failed: " + e.getMessage());
            }
        }
        if (total > 0) persistTasks();
        return total;
    }

    private int discoverFor(Watch watch) throws IOException {
        ApiClient.Resp r = fetchSignTask(watch.studentId, watch.token);
        if (r.code() != 10000) return 0;
        JSONObject tf = r.response();
        if (tf == null) return 0;
        long activityId = tf.optLong("activityId", 0);
        if (activityId == 0) return 0;

        boolean inDone = "1".equals(String.valueOf(tf.opt("signInStatus") == null ? "" : tf.opt("signInStatus")));
        boolean outDone = "1".equals(String.valueOf(tf.opt("signBackStatus") == null ? "" : tf.opt("signBackStatus")));
        if (inDone && outDone) return 0;

        long now = System.currentTimeMillis();
        List<String[]> wanted = new ArrayList<>();  // [signType, signTime, targetTs]

        if (!inDone) addWanted(wanted, "1", optString(tf, "signInTime"), now);
        if (!outDone) {
            String raw = firstNonEmpty(
                    optString(tf, "signBackTime"),
                    optString(tf, "signBackLimitTime"),
                    optString(tf, "endTime"),
                    optString(tf, "activityEndTime"));
            addWanted(wanted, "2", raw, now);
        }

        int added = 0;
        for (String[] w : wanted) {
            String key = taskKeyOf(watch.studentId, activityId, w[0]);
            Task existing = tasks.get(key);
            long target = Long.parseLong(w[2]);
            String date = dateStrOf(target);
            boolean sameDay = existing != null && date.equals(existing.date);

            if (existing != null && !existing.executed && sameDay
                    && existing.targetTimestamp == target) {
                continue;
            }

            Task nt = new Task();
            nt.id = key;
            nt.studentId = watch.studentId;
            nt.token = watch.token;
            nt.activityId = activityId;
            nt.activityName = firstNonEmpty(optString(tf, "activityName"),
                    existing != null ? existing.activityName : "");
            nt.signType = w[0];
            nt.signTime = w[1];
            nt.targetTimestamp = target;
            nt.latitude = optString(tf, "latitude");
            nt.longitude = optString(tf, "longitude");
            nt.date = date;
            nt.executed = sameDay && existing.executed;
            nt.result = (sameDay && existing.executed) ? existing.result : null;
            nt.attempts = sameDay ? existing.attempts : 0;
            nt.createdAt = existing != null && existing.createdAt > 0
                    ? existing.createdAt : System.currentTimeMillis();

            tasks.put(key, nt);
            added++;
        }
        return added;
    }

    private void addWanted(List<String[]> out, String signType, String rawTime, long now) {
        long target = clockToTodayMs(rawTime);
        if (target == 0) return;
        if (target < now - Const.STALE_TOLERANCE_MS) return; // window already gone
        out.add(new String[]{signType, rawTime == null ? "" : rawTime, String.valueOf(target)});
    }

    // ---------------------------------------------------------------- helpers

    private ApiClient.Resp fetchSignTask(String studentId, String token) throws IOException {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("studentId", parseLong(studentId));
        return ApiClient.get("/clubactivity/getSignInTf", q, token);
    }

    private static boolean isSignDone(JSONObject tf, String signType) {
        if (tf == null) return false;
        String v = "1".equals(signType)
                ? String.valueOf(tf.opt("signInStatus") == null ? "" : tf.opt("signInStatus"))
                : String.valueOf(tf.opt("signBackStatus") == null ? "" : tf.opt("signBackStatus"));
        return "1".equals(v);
    }

    private void pushLog(Task task, String action, boolean ok, String msg) {
        JSONObject entry = new JSONObject();
        try {
            entry.put("studentId", task.studentId);
            entry.put("activityId", task.activityId);
            entry.put("activityName", task.activityName == null ? "" : task.activityName);
            entry.put("signType", task.signType == null ? "" : task.signType);
            entry.put("action", action);
            entry.put("ok", ok);
            entry.put("msg", msg == null ? "" : msg);
            entry.put("time", RunMath.utcIsoNow());
        } catch (Exception ignored) {
        }
        synchronized (this) {
            logs.add(entry);
            if (logs.size() > MAX_LOGS_TOTAL) {
                logs.subList(0, logs.size() - MAX_LOGS_TOTAL).clear();
            }
        }
        persistLogs();
    }

    /** Writes a system-level line ("服务已启动，调度器就绪") into every watch's log. */
    public void logStartupForWatches() {
        for (Watch w : watches.values()) {
            JSONObject entry = new JSONObject();
            try {
                entry.put("studentId", w.studentId);
                entry.put("activityId", 0);
                entry.put("activityName", "");
                entry.put("signType", "");
                entry.put("action", "系统");
                entry.put("ok", true);
                entry.put("msg", "服务已启动，调度器就绪");
                entry.put("time", RunMath.utcIsoNow());
            } catch (Exception ignored) {
            }
            synchronized (this) {
                logs.add(entry);
                if (logs.size() > MAX_LOGS_TOTAL) {
                    logs.subList(0, logs.size() - MAX_LOGS_TOTAL).clear();
                }
            }
        }
        if (!watches.isEmpty()) persistLogs();
    }

    /** Drop completed tasks older than 48h and anything beyond a week out. */
    private void cleanup() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, Task> e : new ArrayList<>(tasks.entrySet())) {
            Task t = e.getValue();
            boolean oldExecuted = t.executed && now - t.targetTimestamp > DROP_EXECUTED_AFTER_MS;
            boolean tooFar = t.targetTimestamp - now > DROP_FUTURE_AFTER_MS;
            if (oldExecuted || tooFar) {
                tasks.remove(e.getKey());
                changed = true;
            }
        }
        if (changed) persistTasks();
    }

    public boolean hasPendingSoon(long withinMs) {
        long now = System.currentTimeMillis();
        for (Task t : tasks.values()) {
            if (t.executed) continue;
            long delta = t.targetTimestamp - now;
            if (delta <= withinMs) return true;
        }
        return false;
    }

    public long nextDueInMs() {
        long now = System.currentTimeMillis();
        long best = -1;
        for (Task t : tasks.values()) {
            if (t.executed) continue;
            long delta = Math.max(0, t.targetTimestamp - now);
            if (best < 0 || delta < best) best = delta;
        }
        return best;
    }

    public boolean hasWatches() {
        return !watches.isEmpty();
    }

    private static String taskKeyOf(String studentId, long activityId, String signType) {
        return studentId + "-" + activityId + "-" + signType;
    }

    private static String signTypeLabel(String signType) {
        return "1".equals(signType) ? "签到" : "签退";
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String optString(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return "";
        return o.optString(key, "");
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return "";
    }

    private static String dateStrOf(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts != 0 ? ts : System.currentTimeMillis());
        return RunMath.localDateKey(c);
    }

    private static final Pattern CLOCK = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");

    /** {@code "HH:mm[:ss]"} / {@code "2026-09-11 07:30:01"} → today's epoch ms. */
    static long clockToTodayMs(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        String s = value.trim();
        int sp = s.lastIndexOf(' ');
        if (sp >= 0) s = s.substring(sp + 1);
        int t = s.lastIndexOf('T');
        if (t >= 0) s = s.substring(t + 1);

        Matcher m = CLOCK.matcher(s);
        if (!m.find()) return 0;
        int h = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        int sec = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (h > 23 || mi > 59 || sec > 59) return 0;

        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, mi);
        c.set(Calendar.SECOND, sec);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
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
            o.put("msg", msg);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void log(String tag, String msg) {
        L.i("Autorun/" + tag, msg);
    }
}

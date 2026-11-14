package com.qinghaipao.autorun.core;

/**
 * Shared constants for the campus-run backend.
 *
 * <p>The appKey/appSecret pair and the upstream host are the same values the
 * official client ships with; they are what makes the signature accepted.
 */
public final class Const {

    private Const() {}

    public static final String APP_KEY = "389885588s0648fa";
    public static final String APP_SECRET = "56E39A1658455588885690425C0FD16055A21676";

    /** Upstream API root. Local routes mirror everything after this prefix. */
    public static final String BASE = "https://run-lb.tanmasports.com/v1";

    /** Same UA the JS engine sent; some WAF rules key on it. */
    public static final String UA =
            "Dalvik/2.1.0 (Linux; U; Android 14; SM-S918B Build/UP1A.230905.011)";

    /** Port the WebView talks to. Must stay 3000: the built Vue bundle hardcodes nothing, but every
     *  existing device/bookmark expects it, and the JS engine used it. */
    public static final int PORT = 3000;

    /** Hard deadline for every upstream call. A stalled socket once silenced the whole scheduler. */
    public static final int UPSTREAM_TIMEOUT_MS = 15000;
    public static final int RUN_SUBMIT_TIMEOUT_MS = 45000;

    // ---- sign engine ---- (ported verbatim from server/index.js)
    public static final int MAX_SIGN_ATTEMPTS = 8;
    public static final long STALE_TOLERANCE_MS = 15L * 60 * 1000;
    public static final long FAST_TICK_MS = 5L * 1000;
    public static final long IDLE_TICK_MS = 30L * 1000;
    public static final long FAST_LEAD_MS = 2L * 60 * 1000;
    public static final long DISCOVERY_INTERVAL_MS = 5L * 60 * 1000;

    /**
     * 可排程的最远时间。超过这个跨度的一律当作脏数据拒收。
     *
     * <p>前端曾经把一条上一学期的报名记录（mmdd 格式）滚到了明年，客户端算出
     * "7279 小时后执行签到"。窗口卡在这里，无论上游传什么都不会排进去。
     */
    public static final long MAX_SCHEDULE_AHEAD_MS = 30L * 24 * 60 * 60 * 1000;

    // ---- run engine ----
    public static final long AUTO_RUN_GRACE_MS = 10L * 60 * 1000;
    public static final int MAX_AUTO_RUN_ATTEMPTS = 5;
    public static final int DEFAULT_DISTANCE_MIN = 1000;
    public static final int DEFAULT_DISTANCE_MAX = 5000;
    public static final String DEFAULT_WINDOW_START = "08:00";
    public static final String DEFAULT_WINDOW_END = "22:00";

    /** Only the two CUIT campuses are kept. cuit_hkg is the day-to-day default. */
    public static final String DEFAULT_MAP_ID = "cuit_hkg";
    public static final String[] MAP_IDS = {"cuit_hkg", "cuit_hkg2", "cuit_lqy"};
}

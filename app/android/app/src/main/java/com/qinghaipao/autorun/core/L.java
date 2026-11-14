package com.qinghaipao.autorun.core;

/**
 * Logging seam.
 *
 * <p>Everything under {@code core/} has to compile and run in two places: inside
 * the APK (where logs should go to logcat) and on a desktop JVM (where there is no
 * Android at all and the output should just be stdout). Calling
 * {@code android.util.Log} directly would tie the engine to Android — and on a
 * desktop classpath those are stub methods that throw.
 *
 * <p>So core logs through this, and the Android shell swaps in a logcat sink at
 * startup. The default sink prints to stdout, which is what the desktop runner and
 * the tests use.
 */
public final class L {

    public interface Sink {
        void log(int level, String tag, String msg, Throwable t);
    }

    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;

    private static volatile Sink sink = (level, tag, msg, t) -> {
        String prefix = level >= ERROR ? "E/" : level >= WARN ? "W/" : "I/";
        System.out.println(prefix + tag + ": " + msg);
        if (t != null) t.printStackTrace(System.out);
    };

    private L() {}

    public static void setSink(Sink s) {
        if (s != null) sink = s;
    }

    public static void i(String tag, String msg) {
        sink.log(INFO, tag, msg, null);
    }

    public static void w(String tag, String msg) {
        sink.log(WARN, tag, msg, null);
    }

    public static void e(String tag, String msg, Throwable t) {
        sink.log(ERROR, tag, msg, t);
    }

    public static void e(String tag, String msg) {
        sink.log(ERROR, tag, msg, null);
    }
}

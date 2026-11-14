package com.qinghaipao.autorun.core;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Distance / duration / pace math, ported from {@code server/auto-run.js}
 * (itself a copy of the frontend {@code utils/run.js}).
 *
 * <p>Two families of rules live here and they are deliberately different:
 * <ul>
 *   <li><b>Duration bounds</b> come from the school standard padded as the manual
 *       path does — they only serve to keep pace inside 6-10 min/km.</li>
 *   <li><b>Distance envelope</b> uses the school's stated numbers <i>unpadded</i>
 *       ({@code resolveEnvelope}), because that is what the UI shows and stores.
 *       Mixing the two was a real bug once: the auto-run panel displayed 1001/6001
 *       instead of the school's 1000/5000.</li>
 * </ul>
 *
 * <p>Every "avoid a rounded number" rule exists because a suspiciously round
 * distance or duration is exactly what a statistical anti-cheat looks for:
 * distances avoid multiples of 10, durations avoid multiples of 10 minutes and
 * whole thousands of seconds.
 */
public final class RunMath {

    public static final int MIN_PACE = 6;
    public static final int MAX_PACE = 10;

    private RunMath() {}

    // ------------------------------------------------------------- coercion

    private static double num(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return 0;
        double d = o.optDouble(key, 0);
        return Double.isFinite(d) ? d : 0;
    }

    private static double num(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isFinite(d) ? d : 0;
        }
        try {
            double d = Double.parseDouble(String.valueOf(v).trim());
            return Double.isFinite(d) ? d : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double positive(double v) {
        return v > 0 ? v : 0;
    }

    /** {@code Math.trunc}; Android's java.lang.Math does not ship it. */
    private static double truncd(double v) {
        return v < 0 ? Math.ceil(v) : Math.floor(v);
    }

    private static int toInt(double v, int fallback) {
        if (!Double.isFinite(v)) return fallback;
        return (int) truncd(v);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(v, max));
    }

    private static double getRandom(TrackGenerator.Rng rng) {
        if (rng == null) return Math.random();
        double v = rng.nextDouble();
        return (Double.isFinite(v) && v >= 0 && v < 1) ? v : Math.random();
    }

    private static int randomInt(int min, int max, TrackGenerator.Rng rng) {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        long v = lo + (long) Math.floor(getRandom(rng) * (hi - lo + 1));
        return (int) v;
    }

    // ------------------------------------------------------------- gender

    public static String normalizeGender(Object raw) {
        String g = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        for (String m : new String[]{"1", "male", "man", "m", "boy", "nan"}) {
            if (m.equals(g)) return "male";
        }
        for (String f : new String[]{"2", "female", "woman", "f", "girl", "nv"}) {
            if (f.equals(g)) return "female";
        }
        return "";
    }

    // ------------------------------------------------------------- bounds

    private static double selectOnceMin(String gender, JSONObject rs) {
        double boy = positive(num(rs, "boyOnceDistanceMin"));
        double girl = positive(num(rs, "girlOnceDistanceMin"));
        if ("male".equals(gender)) return boy;
        if ("female".equals(gender)) return girl;
        return Math.max(boy, girl);
    }

    private static double selectOnceMax(String gender, JSONObject rs) {
        double boy = positive(num(rs, "boyOnceDistanceMax"));
        double girl = positive(num(rs, "girlOnceDistanceMax"));
        if ("male".equals(gender)) return boy;
        if ("female".equals(gender)) return girl;
        return Math.max(boy, girl);
    }

    /** Padded distance bounds — manual path only. Prefer {@link #resolveEnvelope} for auto-run. */
    public static int[] calculateDistanceBounds(String gender, JSONObject rs, int defMin, int defMax) {
        int minDistance = Math.max(1, defMin);
        int maxDistance = Math.max(minDistance, defMax);

        double onceMin = selectOnceMin(gender, rs);
        double onceMax = selectOnceMax(gender, rs);

        if (onceMin > 0 || onceMax > 0) {
            if (onceMin > 0) minDistance = Math.max(1, (int) truncd(onceMin) + 1);
            if (onceMax > 0) maxDistance = Math.max(minDistance, (int) truncd(onceMax) + 1001);
        }
        if (maxDistance < minDistance) maxDistance = minDistance;
        return new int[]{minDistance, maxDistance};
    }

    public static double[] calculateTimeBounds(String gender, JSONObject rs) {
        double boyMin = positive(num(rs, "boyOnceTimeMin"));
        double boyMax = positive(num(rs, "boyOnceTimeMax"));
        double girlMin = positive(num(rs, "girlOnceTimeMin"));
        double girlMax = positive(num(rs, "girlOnceTimeMax"));

        double minTime;
        double maxTime;
        if ("male".equals(gender)) {
            minTime = boyMin;
            maxTime = boyMax;
        } else if ("female".equals(gender)) {
            minTime = girlMin;
            maxTime = girlMax;
        } else {
            minTime = minPositive(boyMin, girlMin);
            maxTime = Math.max(boyMax, girlMax);
        }

        if (minTime > 0 && maxTime > 0 && minTime > maxTime) {
            return new double[]{maxTime, minTime};
        }
        return new double[]{minTime, maxTime};
    }

    private static double minPositive(double a, double b) {
        double ap = positive(a);
        double bp = positive(b);
        if (ap > 0 && bp > 0) return Math.min(ap, bp);
        if (ap > 0) return ap;
        if (bp > 0) return bp;
        return 0;
    }

    /**
     * The school's accepted per-run distance window, <b>unpadded</b>.
     * A collapsed standard still needs room to draw from, hence {@code lo+1}.
     */
    public static int[] resolveEnvelope(JSONObject rs, String gender, int defMin, int defMax) {
        String g = normalizeGender(gender);
        int boyMin = (int) positive(num(rs, "boyOnceDistanceMin"));
        int girlMin = (int) positive(num(rs, "girlOnceDistanceMin"));
        int boyMax = (int) positive(num(rs, "boyOnceDistanceMax"));
        int girlMax = (int) positive(num(rs, "girlOnceDistanceMax"));

        int min;
        int max;
        if ("male".equals(g)) {
            min = boyMin;
            max = boyMax;
        } else if ("female".equals(g)) {
            min = girlMin;
            max = girlMax;
        } else {
            List<Integer> mins = new ArrayList<>();
            List<Integer> maxs = new ArrayList<>();
            if (boyMin > 0) mins.add(boyMin);
            if (girlMin > 0) mins.add(girlMin);
            if (boyMax > 0) maxs.add(boyMax);
            if (girlMax > 0) maxs.add(girlMax);
            min = 0;
            for (int v : mins) min = min == 0 ? v : Math.min(min, v);
            max = 0;
            for (int v : maxs) max = Math.max(max, v);
        }

        int lo = min > 0 ? min : defMin;
        int hi = max > 0 ? max : defMax;
        return hi > lo ? new int[]{lo, hi} : new int[]{lo, lo + 1};
    }

    // ---------------------------------------------------------- randomness

    public static int randomIntNonThousand(int min, int max, TrackGenerator.Rng rng) {
        int lo = min;
        int hi = max;
        if (lo > hi) {
            int tmp = lo;
            lo = hi;
            hi = tmp;
        }
        if (lo == hi) return avoidRoundedTenValue(lo, lo, hi, rng);

        int span = hi - lo + 1;
        for (int i = 0; i < 64; i++) {
            int v = (int) (lo + Math.floor(getRandom(rng) * span));
            if (v % 10 != 0) return v;
        }
        return avoidRoundedTenValue((int) (lo + Math.floor(getRandom(rng) * span)), lo, hi, rng);
    }

    public static int avoidRoundedTenValue(int value, int min, int max, TrackGenerator.Rng rng) {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        int current = (int) clamp(truncd(value), lo, hi);

        if (current % 10 != 0) return current;

        int bestDelta = hi - lo + 1;
        List<Integer> candidates = new ArrayList<>();

        for (int v = lo; v <= hi; v++) {
            if (v % 10 == 0) continue;
            int delta = Math.abs(v - current);
            if (delta < bestDelta) {
                bestDelta = delta;
                candidates.clear();
                candidates.add(v);
            } else if (delta == bestDelta) {
                candidates.add(v);
            }
        }
        if (candidates.isEmpty()) return current;
        return candidates.get(randomInt(0, candidates.size() - 1, rng));
    }

    private static long avoidMultipleOf(long value, long modulus, int minOffset, int maxOffset,
                                        TrackGenerator.Rng rng) {
        if (modulus == 0 || value % modulus != 0) return value;
        int lo = Math.min(minOffset, maxOffset);
        int hi = Math.max(minOffset, maxOffset);
        return value + randomInt(lo, hi, rng);
    }

    // ------------------------------------------------------------- duration

    public static double computeDurationFromDistance(double distanceMeters, double minMinutes,
                                                    double maxMinutes, TrackGenerator.Rng rng) {
        if (!Double.isFinite(distanceMeters) || distanceMeters <= 0) return 0;

        double minM = positive(minMinutes);
        double maxM = positive(maxMinutes);
        if (minM > 0 && maxM > 0 && minM > maxM) {
            double tmp = minM;
            minM = maxM;
            maxM = tmp;
        }

        double km = distanceMeters / 1000;
        double minPace = MIN_PACE;
        double maxPace = MAX_PACE;
        if (km > 0 && minM > 0) minPace = Math.max(minPace, minM / km);
        if (km > 0 && maxM > 0) maxPace = Math.min(maxPace, maxM / km);

        long seconds;
        if (minPace <= maxPace) {
            double pace = minPace + (maxPace - minPace) * getRandom(rng);
            seconds = Math.round(km * pace * 60);
        } else {
            double pace = MIN_PACE + (MAX_PACE - MIN_PACE) * getRandom(rng);
            seconds = Math.round(km * pace * 60);
        }

        if (seconds % 1000 == 0) {
            seconds = avoidMultipleOf(seconds, 1000, 5, 59, rng);
        }

        double duration = Math.max(1, seconds / 60.0);
        if (minM > 0 && duration < minM) duration = minM;
        if (maxM > 0 && duration > maxM) duration = maxM;

        // Number(duration.toFixed(1))
        return Math.round(duration * 10) / 10.0;
    }

    public static int normalizeRoundedRunTime(double runTimeMinutes, double distanceMeters,
                                              double minMinutes, double maxMinutes,
                                              TrackGenerator.Rng rng) {
        int rounded = (int) Math.round(runTimeMinutes);
        if (rounded < 1) rounded = 1;

        int[] bounds = deriveRoundedRunTimeBounds(distanceMeters, positive(minMinutes), positive(maxMinutes));
        int minRounded = bounds[0];
        int maxRounded = bounds[1];

        if (rounded < minRounded) rounded = minRounded;
        if (maxRounded > 0 && rounded > maxRounded) rounded = maxRounded;

        rounded = avoidRoundedTenMinute(rounded, minRounded, maxRounded, rng);
        if (rounded < minRounded) rounded = minRounded;
        if (maxRounded > 0 && rounded > maxRounded) rounded = maxRounded;
        if (rounded < 1) rounded = 1;
        return rounded;
    }

    private static int[] deriveRoundedRunTimeBounds(double distanceMeters, double minMinutes, double maxMinutes) {
        double dist = positive(distanceMeters);
        int minRounded = minMinutes > 0 ? (int) Math.ceil(minMinutes) : 1;
        int maxRounded = maxMinutes > 0 ? (int) Math.floor(maxMinutes) : 0;

        if (dist > 0) {
            double km = dist / 1000;
            int paceMinRounded = (int) Math.ceil(km * MIN_PACE);
            if (paceMinRounded > minRounded) minRounded = paceMinRounded;

            int paceMaxRounded = (int) Math.floor(km * MAX_PACE);
            if (paceMaxRounded > 0 && (maxRounded == 0 || paceMaxRounded < maxRounded)) {
                maxRounded = paceMaxRounded;
            }
        }
        if (maxRounded > 0 && maxRounded < minRounded) maxRounded = minRounded;
        if (minRounded < 1) minRounded = 1;
        return new int[]{minRounded, maxRounded};
    }

    private static int avoidRoundedTenMinute(int value, int minBound, int maxBound, TrackGenerator.Rng rng) {
        int current = Math.max(1, value);
        int lowerBound = Math.max(1, minBound);
        int upperBound = maxBound;
        if (upperBound > 0 && upperBound < lowerBound) upperBound = lowerBound;

        if (current % 10 != 0) return current;

        if (upperBound > 0) {
            int adjusted = avoidRoundedTenValue(current, lowerBound, upperBound, rng);
            if (adjusted % 10 != 0) return adjusted;
        }

        for (int step = 1; step <= 9; step++) {
            int up = current + step;
            if (up >= lowerBound && up % 10 != 0) return up;
            int down = current - step;
            if (down >= lowerBound && down % 10 != 0) return down;
        }

        current += 1;
        if (current < lowerBound) current = lowerBound;
        if (current % 10 == 0) current += 1;
        return current;
    }

    public static double paceOf(double distanceMeters, double durationMinutes) {
        double distance = num(distanceMeters);
        double duration = num(durationMinutes);
        if (distance <= 0 || duration <= 0) return 0;
        double pace = duration / (distance / 1000);
        if (!Double.isFinite(pace)) return 0;
        return Math.round(pace * 100) / 100.0;
    }

    // ---------------------------------------------------------------- dates

    public static String pad2(int n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    public static String localDateKey(Calendar c) {
        return c.get(Calendar.YEAR) + "-" + pad2(c.get(Calendar.MONTH) + 1) + "-" + pad2(c.get(Calendar.DAY_OF_MONTH));
    }

    public static String buildYearSemester(Calendar c) {
        int year = c.get(Calendar.YEAR);
        String semester = (c.get(Calendar.MONTH) + 1) < 8 ? "1" : "2";
        return year + semester;
    }

    /**
     * ISO-8601 UTC timestamp, the shape {@code new Date().toISOString()} produced
     * in the JS logs. Hand-rolled because {@code java.time} needs API 26 and
     * minSdk here is 24.
     */
    public static String utcIsoNow() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    public static Calendar startOfDay(Calendar c) {
        Calendar out = (Calendar) c.clone();
        out.set(Calendar.HOUR_OF_DAY, 0);
        out.set(Calendar.MINUTE, 0);
        out.set(Calendar.SECOND, 0);
        out.set(Calendar.MILLISECOND, 0);
        return out;
    }
}

package com.qinghaipao.autorun.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of {@code genTrackPoints} from app/src/utils/track.js.
 *
 * <p>This is the part of the project that cannot be "close enough". The platform
 * scored these tracks as genuine for months, so every constant here — the 6-10
 * min/km pace clamp, the 4-8 m step, the 4000-point ceiling, the ±3e-6° jitter,
 * the 0.65/0.35 speed smoothing — is copied verbatim rather than tidied up.
 * The acceptance test is "same random sequence in, identical points out".
 *
 * <p>Randomness is injected ({@link Rng}) instead of calling {@code Math.random()}
 * directly, purely so a seeded implementation can be diffed against the JS
 * original. Production passes {@code Math::random}.
 */
public final class TrackGenerator {

    public interface Rng {
        double nextDouble();
    }

    private static final double MIN_PACE = 6.0;
    private static final double MAX_PACE = 10.0;
    private static final int MAX_TOTAL_POINTS = 4000;
    private static final double JITTER = 0.000003;   // ~0.3 m
    private static final double BBOX_PAD = 0.00005;
    private static final double EARTH_RADIUS = 6378137.0;

    private final MapRepo maps;

    public TrackGenerator(MapRepo maps) {
        this.maps = maps;
    }

    /**
     * @param distance        target distance in metres
     * @param mapId           route id; null falls back to cuit_hkg
     * @param durationMinutes planned duration; when null a 7.6-8.8 min/km pace is drawn
     * @return JSON array literal of {@code "lng-lat"} strings, or {@code "[]"}
     */
    public String genTrackPoints(double distance, String mapId, Double durationMinutes, Rng rng) {
        MapRepo.MapInfo map = maps.get(mapId);
        if (map == null || map.points.isEmpty()) return "[]";
        return generate(distance, map.points, durationMinutes, rng);
    }

    /**
     * Core generator — a pure function of (distance, points, duration, rng sequence).
     *
     * <p>Kept static and free of Android types so the trajectory can be diffed
     * against the JS original on a plain desktop JVM. That diff is the acceptance
     * test for this whole rewrite: identical random sequence in, identical points out.
     */
    public static String generate(double distance, List<double[]> sourcePoints,
                                  Double durationMinutes, Rng rng) {
        if (!Double.isFinite(distance) || distance <= 0) return "[]";
        if (sourcePoints == null || sourcePoints.isEmpty()) return "[]";

        // 1. drop consecutive duplicates, then a closing point that repeats the start
        List<double[]> sanitized = new ArrayList<>(sourcePoints.size());
        for (int i = 0; i < sourcePoints.size(); i++) {
            double[] pt = sourcePoints.get(i);
            if (i == 0 || !arePointsEqual(pt, sourcePoints.get(i - 1))) {
                sanitized.add(pt);
            }
        }
        if (sanitized.size() > 1 && arePointsEqual(sanitized.get(0), sanitized.get(sanitized.size() - 1))) {
            sanitized.remove(sanitized.size() - 1);
        }
        if (sanitized.size() < 2) return "[]";

        // 2. bounding box
        double minLng = Double.POSITIVE_INFINITY, maxLng = Double.NEGATIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY, maxLat = Double.NEGATIVE_INFINITY;
        for (double[] pt : sanitized) {
            minLng = Math.min(minLng, pt[0]);
            maxLng = Math.max(maxLng, pt[0]);
            minLat = Math.min(minLat, pt[1]);
            maxLat = Math.max(maxLat, pt[1]);
        }

        // 3. closed ring of segments, dropping degenerate ones
        List<Segment> segments = new ArrayList<>(sanitized.size());
        for (int i = 0; i < sanitized.size(); i++) {
            double[] from = sanitized.get(i);
            double[] to = sanitized.get((i + 1) % sanitized.size());
            double len = haversine(from, to);
            if (len >= 0.5) {
                segments.add(new Segment(from, to, len));
            }
        }
        if (segments.isEmpty()) return "[]";

        // 4. pace / duration / step
        double inferredPace = durationMinutes != null && durationMinutes > 0
                ? durationMinutes / (distance / 1000.0)
                : 7.6 + rng.nextDouble() * 1.2;
        double pace = clamp(inferredPace, MIN_PACE, MAX_PACE);
        long durationMs = Math.round((distance / 1000.0) * pace * 60 * 1000);
        double baseSpeed = 1000.0 / (pace * 60);
        double baseSpacing = clamp(distance / 1200.0, 4, 8);

        // 5. random start on a random segment
        int segIndex = (int) Math.floor(rng.nextDouble() * segments.size());
        if (segIndex >= segments.size()) segIndex = segments.size() - 1;
        double segOffset = rng.nextDouble() * Math.max(1, segments.get(segIndex).length * 0.6);
        double[] lastPoint = addJitter(projectOnSegment(segments.get(segIndex), segOffset),
                minLng, maxLng, minLat, maxLat, rng);

        // The JS original computes
        //   startTime = Date.now() - durationMs - floor(rng()*60000 + 20000)
        // and never uses the value — but the draw still advances the generator.
        // Skipping it here would shift every later random number by one and change
        // the whole track, which is exactly what the point-for-point diff caught.
        rng.nextDouble();

        List<String> result = new ArrayList<>(1024);
        result.add(fmt(lastPoint));

        long elapsedMs = 0;
        double generatedDistance = 0;
        double currentSpeed = baseSpeed;

        while (generatedDistance < distance && result.size() < MAX_TOTAL_POINTS) {
            double remainingDistance = distance - generatedDistance;
            double stepTarget = Math.min(remainingDistance, baseSpacing * (0.9 + rng.nextDouble() * 0.35));
            double advance = stepTarget;

            // Advance along the ring, wrapping across segments. The guard exists
            // because a zero-length remaining segment would otherwise spin here
            // forever if the map were ever degenerate.
            int guard = 0;
            while (advance > 0 && guard++ < 10000) {
                Segment seg = segments.get(segIndex);
                double remainingOnSeg = seg.length - segOffset;
                double stepThisSeg = Math.min(advance, remainingOnSeg);
                segOffset += stepThisSeg;
                advance -= stepThisSeg;

                if (segOffset >= seg.length - 1e-6) {
                    segIndex = (segIndex + 1) % segments.size();
                    segOffset = 0;
                }
            }

            double[] rawPoint = projectOnSegment(segments.get(segIndex), segOffset);
            double[] point = addJitter(rawPoint, minLng, maxLng, minLat, maxLat, rng);

            double traveled = haversine(lastPoint, point);
            generatedDistance += traveled;

            double remainingTime = Math.max(2000, durationMs - elapsedMs);
            double neededSpeed = remainingDistance > 0 ? remainingDistance / (remainingTime / 1000.0) : baseSpeed;
            double targetSpeed = clamp(
                    (baseSpeed * 0.6 + neededSpeed * 0.4) * (0.95 + rng.nextDouble() * 0.1),
                    baseSpeed * 0.8,
                    baseSpeed * 1.2);
            currentSpeed = clamp(currentSpeed * 0.65 + targetSpeed * 0.35,
                    baseSpeed * 0.75, baseSpeed * 1.25);

            double stepTime = (traveled / Math.max(0.5, currentSpeed)) * 1000;
            elapsedMs += (long) stepTime;

            result.add(fmt(point));
            lastPoint = point;
        }

        StringBuilder sb = new StringBuilder(result.size() * 24);
        sb.append('[');
        for (int i = 0; i < result.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(result.get(i)).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    // ------------------------------------------------------------- helpers

    private static double[] addJitter(double[] pt, double minLng, double maxLng,
                                      double minLat, double maxLat, Rng rng) {
        double lng = clamp(pt[0] + (rng.nextDouble() - 0.5) * 2 * JITTER,
                minLng - BBOX_PAD, maxLng + BBOX_PAD);
        double lat = clamp(pt[1] + (rng.nextDouble() - 0.5) * 2 * JITTER,
                minLat - BBOX_PAD, maxLat + BBOX_PAD);
        return new double[]{lng, lat};
    }

    private static double[] projectOnSegment(Segment seg, double offsetMeters) {
        double t = clamp(offsetMeters / seg.length, 0, 1);
        double lng = seg.from[0] + (seg.to[0] - seg.from[0]) * t;
        double lat = seg.from[1] + (seg.to[1] - seg.from[1]) * t;
        return new double[]{lng, lat};
    }

    private static boolean arePointsEqual(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) <= 1e-9 && Math.abs(a[1] - b[1]) <= 1e-9;
    }

    static double haversine(double[] a, double[] b) {
        double lat1 = Math.toRadians(a[1]);
        double lat2 = Math.toRadians(b[1]);
        double dLat = Math.toRadians(b[1] - a[1]);
        double dLng = Math.toRadians(b[0] - a[0]);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(s), Math.sqrt(1 - s));
        return EARTH_RADIUS * c;
    }

    private static double clamp(double v, double a, double b) {
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        return Math.max(lo, Math.min(v, hi));
    }

    /** {@code `${lng}-${lat}`} with JS number formatting (no trailing ".0"). */
    private static String fmt(double[] pt) {
        return jsDouble(pt[0]) + "-" + jsDouble(pt[1]);
    }

    private static String jsDouble(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e21) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    private static final class Segment {
        final double[] from;
        final double[] to;
        final double length;

        Segment(double[] from, double[] to, double length) {
            this.from = from;
            this.to = to;
            this.length = length;
        }
    }
}

package com.qinghaipao.autorun.core;

/**
 * mulberry32, bit-for-bit the same generator the JS engine uses.
 *
 * <p>This matters more than it looks: the daily run plan is a pure function of
 * {@code hash(studentId:dateKey)} → this generator → (time of day, distance).
 * If the Java implementation diverged by one bit, a restart would recompute a
 * <i>different</i> plan for the same day, and the user could end up submitting a
 * second run. Hence the exact 32-bit wrap-around arithmetic instead of anything
 * "cleaner": {@code int} overflow in Java is the same modulo-2^32 the JS
 * {@code Math.imul} performs.
 */
public final class Mulberry32 implements TrackGenerator.Rng {

    private int a;

    public Mulberry32(long seed) {
        this.a = (int) seed;
    }

    /** FNV-1a over UTF-16 code units, identical to the JS {@code hashSeed}. */
    public static long hashSeed(String str) {
        int h = (int) 2166136261L; // JS keeps this as unsigned; the bits are the same
        String s = String.valueOf(str);
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 16777619; // Math.imul
        }
        return h & 0xFFFFFFFFL;
    }

    @Override
    public double nextDouble() {
        a = a + 0x6d2b79f5;
        int t = (a ^ (a >>> 15)) * (1 | a);
        t = (t + (t ^ (t >>> 7)) * (61 | t)) ^ t;
        return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
    }
}

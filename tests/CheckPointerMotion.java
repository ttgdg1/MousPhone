package io.pocketpad.remote;

public final class CheckPointerMotion {
    private static void eq(int actual, int expected, String message) {
        if (actual != expected) throw new AssertionError(message + ": " + actual + " != " + expected);
    }
    public static void main(String[] args) {
        PointerMotion motion = new PointerMotion();
        // Fractional movements must eventually add up to whole HID pixels.
        for (int i = 0; i < 20; i++) motion.add(0.35f, 0.15f);
        int dx = 0, dy = 0;
        for (int i = 0; i < 6; i++) {
            int[] d = motion.take(false);
            dx += d[0]; dy += d[1];
        }
        int[] rest = motion.take(true);
        eq(dx + rest[0], 7, "fraction X preserved");
        eq(dy + rest[1], 3, "fraction Y preserved");

        // Large UI batches should be split over several frames rather than jumping.
        motion.add(100f, -80f);
        int[] first = motion.take(false);
        if (first[0] <= 0 || first[0] >= 100 || first[1] >= 0 || first[1] <= -80)
            throw new AssertionError("large batch not smoothed");
        int[] finalFrame = motion.take(true);
        eq(first[0] + finalFrame[0], 100, "smoothing X distance");
        eq(first[1] + finalFrame[1], -80, "smoothing Y distance");

        motion.add(23, 12);
        motion.clear();
        int[] empty = motion.take(true);
        eq(empty[0], 0, "clear X");
        eq(empty[1], 0, "clear Y");
        System.out.println("OK: smooth pointer motion and fractional travel");
    }
}

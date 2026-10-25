package io.pocketpad.remote;

/** Recognizes a second tap near the first without delaying normal single clicks. */
final class TapSequence {
    private static final long DOUBLE_TAP_MAX_DELAY_MS = 320;
    private long lastTapUpTime = -1;
    private float lastX, lastY;

    boolean isSecondTap(long downTime, float x, float y, float maxDistance) {
        boolean result = lastTapUpTime >= 0 && downTime >= lastTapUpTime
                && downTime - lastTapUpTime <= DOUBLE_TAP_MAX_DELAY_MS
                && Math.hypot(x - lastX, y - lastY) <= maxDistance;
        clear(); // a third tap must not automatically start another drag
        return result;
    }

    void recordTap(long upTime, float x, float y) {
        lastTapUpTime = upTime;
        lastX = x;
        lastY = y;
    }

    void clear() { lastTapUpTime = -1; }
}

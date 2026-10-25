package io.pocketpad.remote;

/** Fractional HID mouse travel with gentle frame-spike smoothing (no lost distance). */
final class PointerMotion {
    private double x, y;

    void add(float dx, float dy) {
        x += dx;
        y += dy;
    }

    int[] take(boolean finish) {
        // 78% of the backlog per frame smooths a delayed/batched touch event.
        // 100% on finger release preserves the final position and avoids drift.
        double factor = finish ? 1.0 : 0.78;
        int dx = (int) Math.round(x * factor);
        int dy = (int) Math.round(y * factor);
        x -= dx;
        y -= dy;
        if (finish) clear();
        return new int[]{dx, dy};
    }

    void clear() { x = 0; y = 0; }
}

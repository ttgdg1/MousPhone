package io.pocketpad.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

/** One finger moves the pointer; two fingers scroll; quick taps click. */
final class TouchpadView extends View {
    private final BluetoothHidController controller;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float sensitivity = 1.35f;

    private float lastX, lastY;
    private float fractionScroll;
    private float downX, downY;
    private float twoFingerTravel;
    private long downTime;
    private boolean twoFingers;
    private boolean moved;
    private boolean scrolling;
    private boolean dragByDoubleTap;
    private final TapSequence taps = new TapSequence();

    TouchpadView(Context context, BluetoothHidController controller) {
        super(context);
        this.controller = controller;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(0x00000000);
        setClickable(true);
        setContentDescription(getContext().getString(R.string.touchpad_accessibility));
    }

    void setSensitivity(float factor) { sensitivity = Math.max(0.5f, Math.min(3.0f, factor)); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight(), r = 22 * density;
        paint.setColor(0xFF161E2D);
        canvas.drawRoundRect(new RectF(0, 0, w, h), r, r, paint);
        paint.setStrokeWidth(density);
        paint.setColor(0xFF1F2A3C);
        int gap = (int)(62 * density);
        for (int x = gap; x < w; x += gap) canvas.drawLine(x, 0, x, h, paint);
        for (int y = gap; y < h; y += gap) canvas.drawLine(0, y, w, y, paint);
        paint.setColor(0xFF69A7FF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
        canvas.drawCircle(w/2, h/2 - 14*density, 19*density, paint);
        canvas.drawCircle(w/2, h/2 - 14*density, 5*density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextSize(16*density);
        paint.setColor(0xFFDBE7FA);
        canvas.drawText("MousPhone", w/2, h/2 + 33*density, paint);
        paint.setTextSize(12*density);
        paint.setColor(dragByDoubleTap ? 0xFF75E0AB : 0xFF93A5C0);
        canvas.drawText(dragByDoubleTap ? getContext().getString(R.string.touchpad_drag) :
                getContext().getString(R.string.touchpad_help), w/2, h/2 + 57*density, paint);
    }

    private float centroidY(MotionEvent e) {
        if (e.getPointerCount() < 2) return e.getY();
        return (e.getY(0) + e.getY(1)) * 0.5f;
    }

    private void lockToTouchpad(boolean lock) {
        // The activity contains a ScrollView. Without this call it intercepts
        // vertical swipes and cancels mouse input part-way through a gesture.
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(lock);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lockToTouchpad(true);
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downTime = e.getEventTime();
                dragByDoubleTap = taps.isSecondTap(downTime, downX, downY, 48 * density);
                if (dragByDoubleTap) controller.setGestureDragHeld(true);
                controller.pointerDown();
                fractionScroll = 0;
                twoFingerTravel = 0;
                twoFingers = moved = scrolling = false;
                if (dragByDoubleTap) invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // A second finger cancels drag before switching to two-finger scrolling.
                if (dragByDoubleTap) {
                    controller.pointerUp();
                    controller.setGestureDragHeld(false);
                    dragByDoubleTap = false;
                    invalidate();
                }
                taps.clear();
                if (e.getPointerCount() == 2) {
                    controller.pointerUp(); // flush queued movement before switching modes
                    twoFingers = true; // Do not switch back to pointer mode until all lift.
                    lastY = centroidY(e);
                    twoFingerTravel = 0;
                    fractionScroll = 0;
                    scrolling = false;
                } else {
                    moved = true; // Three-finger gestures are never clicks.
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (twoFingers && e.getPointerCount() >= 2) {
                    float y = centroidY(e);
                    float dy = y - lastY;
                    lastY = y;
                    if (!scrolling) {
                        twoFingerTravel += dy;
                        // A small dead zone avoids accidental scrolling/click rejection.
                        if (Math.abs(twoFingerTravel) < 6 * density) return true;
                        scrolling = true;
                        moved = true;
                        dy = twoFingerTravel;
                        twoFingerTravel = 0;
                    }
                    fractionScroll += -dy / (25 * density);
                    int steps = (int) fractionScroll;
                    if (steps != 0) {
                        controller.scroll(steps);
                        fractionScroll -= steps;
                    }
                } else if (!twoFingers && e.getPointerCount() == 1) {
                    float x = e.getX(), y = e.getY();
                    float dx = x - lastX, dy = y - lastY;
                    if (Math.hypot(x - downX, y - downY) > 9 * density) moved = true;
                    float boost = sensitivity;
                    // Controller keeps fractional deltas and coalesces at ~60 Hz.
                    if (dx != 0 || dy != 0) controller.mouseMove(dx * boost, dy * boost);
                    lastX = x;
                    lastY = y;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // Never reinterpret the remaining finger as a new one-finger swipe.
                return true;

            case MotionEvent.ACTION_UP:
                lockToTouchpad(false);
                controller.pointerUp(); // flush movement with LMB still pressed
                if (dragByDoubleTap) {
                    controller.setGestureDragHeld(false);
                    dragByDoubleTap = false;
                    invalidate();
                } else {
                    long duration = e.getEventTime() - downTime;
                    if (!moved && duration < 300) {
                        controller.click(twoFingers ? 2 : 1);
                        performClick();
                        if (!twoFingers) {
                            taps.recordTap(e.getEventTime(), e.getX(), e.getY());
                        } else taps.clear();
                    } else taps.clear();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                cancelActiveGesture();
                return true;

            default:
                return true;
        }
    }

    void cancelActiveGesture() {
        controller.pointerUp();
        if (dragByDoubleTap) controller.setGestureDragHeld(false);
        dragByDoubleTap = false;
        taps.clear();
        lockToTouchpad(false);
        invalidate();
    }

    @Override protected void onDetachedFromWindow() {
        cancelActiveGesture(); // never leave LMB pressed after rotation/screen switch
        super.onDetachedFromWindow();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}

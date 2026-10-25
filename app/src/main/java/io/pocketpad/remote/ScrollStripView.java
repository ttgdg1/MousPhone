package io.pocketpad.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

/** Dedicated wheel strip, never intercepts touchpad or screen gestures. */
final class ScrollStripView extends View {
    private final BluetoothHidController controller;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private float lastY, residual;
    private boolean dragged;

    ScrollStripView(Context context, BluetoothHidController controller) {
        super(context);
        this.controller = controller;
        density = getResources().getDisplayMetrics().density;
        setClickable(true);
        setContentDescription(getContext().getString(R.string.scroll_hint));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(0xFF263246);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 12*density, 12*density, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0xFFDEE9FB);
        paint.setTextSize(14*density);
        canvas.drawText("↕", getWidth()/2f, getHeight()/2f+5*density, paint);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastY = event.getY(); residual = 0; dragged = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = event.getY()-lastY;
                lastY = event.getY();
                if (Math.abs(dy)>1) dragged = true;
                residual += -dy/(18*density);
                int ticks = (int)residual;
                if (ticks != 0) { controller.scroll(ticks); residual -= ticks; }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragged) controller.scroll(event.getY() < getHeight()/2f ? 2 : -2);
                getParent().requestDisallowInterceptTouchEvent(false);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}

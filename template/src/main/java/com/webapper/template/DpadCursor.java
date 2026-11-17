package com.webapper.template;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.ScreenLength;

/**
 * Remote-control mouse pointer for Android TV: the D-pad moves an on-screen cursor (with
 * acceleration), OK/Enter clicks where it points, and pushing against a screen edge scrolls the
 * page. Websites are made for mice and touch, so this makes any site usable from a TV remote.
 */
final class DpadCursor extends View {
    private static final float BASE_SPEED = 6f;
    private static final float MAX_SPEED = 40f;
    private static final int EDGE = 40;

    private final GeckoView target;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private GeckoSession session;
    private float x = -1, y = -1, speed = BASE_SPEED;
    private int dx, dy;
    private long downTime;
    private boolean enabled = true;

    private final Runnable mover = new Runnable() {
        @Override
        public void run() {
            if (dx == 0 && dy == 0) return;
            speed = Math.min(MAX_SPEED, speed * 1.12f);
            step();
            handler.postDelayed(this, 16);
        }
    };

    DpadCursor(Context ctx, GeckoView target) {
        super(ctx);
        this.target = target;
        float d = getResources().getDisplayMetrics().density;
        fill.setColor(0xFFFFFFFF);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.5f * d);
        float s = 18 * d;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, s);
        arrow.lineTo(s * 0.28f, s * 0.75f);
        arrow.lineTo(s * 0.5f, s * 1.15f);
        arrow.lineTo(s * 0.65f, s * 1.05f);
        arrow.lineTo(s * 0.45f, s * 0.68f);
        arrow.lineTo(s * 0.8f, s * 0.68f);
        arrow.close();
        setFocusable(false);
        setClickable(false);
    }

    void setSession(GeckoSession s) {
        session = s;
    }

    void setCursorEnabled(boolean on) {
        enabled = on;
        invalidate();
    }

    boolean isCursorEnabled() {
        return enabled;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (x < 0) {
            x = w / 2f;
            y = h / 2f;
        }
        x = Math.min(x, w - 1);
        y = Math.min(y, h - 1);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!enabled) return;
        c.save();
        c.translate(x, y);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, stroke);
        c.restore();
    }

    private void step() {
        float nx = x + dx * speed, ny = y + dy * speed;
        int w = getWidth(), h = getHeight();
        float sx = 0, sy = 0;
        if (nx < EDGE) sx = nx - EDGE;
        if (nx > w - EDGE) sx = nx - (w - EDGE);
        if (ny < EDGE) sy = ny - EDGE;
        if (ny > h - EDGE) sy = ny - (h - EDGE);
        x = Math.max(EDGE / 2f, Math.min(w - EDGE / 2f, nx));
        y = Math.max(EDGE / 2f, Math.min(h - EDGE / 2f, ny));
        if ((sx != 0 || sy != 0) && session != null) {
            session.getPanZoomController().scrollBy(ScreenLength.fromPixels(sx * 3), ScreenLength.fromPixels(sy * 3));
        }
        // Hover so menus/tooltips react like they do with a mouse.
        long now = SystemClock.uptimeMillis();
        MotionEvent hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        hover.setSource(android.view.InputDevice.SOURCE_MOUSE);
        target.dispatchGenericMotionEvent(hover);
        hover.recycle();
        invalidate();
    }

    private void touch(int action) {
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN) downTime = now;
        MotionEvent e = MotionEvent.obtain(downTime, now, action, x, y, 0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(e);
        e.recycle();
    }

    /** @return true when the key was consumed by the cursor. */
    boolean handleKey(KeyEvent ev) {
        if (!enabled) return false;
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        int ndx = 0, ndy = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: ndx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: ndx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: ndy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: ndy = 1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (ev.getRepeatCount() == 0) touch(down ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP);
                return true;
            default:
                return false;
        }
        if (down) {
            boolean start = dx == 0 && dy == 0;
            if (ndx != 0) dx = ndx;
            if (ndy != 0) dy = ndy;
            if (start) {
                speed = BASE_SPEED;
                step();
                handler.postDelayed(mover, 120);
            }
        } else {
            if (ndx != 0) dx = 0;
            if (ndy != 0) dy = 0;
            if (dx == 0 && dy == 0) handler.removeCallbacks(mover);
        }
        return true;
    }
}

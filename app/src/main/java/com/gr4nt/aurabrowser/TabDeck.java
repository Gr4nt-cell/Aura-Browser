package com.gr4nt.aurabrowser;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.OverScroller;
import java.util.*;

/** Composited recent-app style deck: overlapping previews, fling/snap, swipe-up dismissal. */
final class TabDeck extends View {
  interface Listener {
    void select(int i);

    void close(int i);
  }

  private final List<BrowserActivity.Tab> tabs;
  private final Listener listener;
  private final Paint p = new Paint(3);
  private final OverScroller scroller;
  private float position, startX, startY, lastX, dragY;
  private boolean vertical, moved;
  private VelocityTracker velocity;
  private final float density;
  private final boolean dark;

  TabDeck(Context c, List<BrowserActivity.Tab> t, int active, boolean d, Listener l) {
    super(c);
    tabs = t;
    position = active;
    dark = d;
    listener = l;
    density = getResources().getDisplayMetrics().density;
    scroller = new OverScroller(c);
    setLayerType(View.LAYER_TYPE_HARDWARE, null);
    setFocusable(true);
    setContentDescription(
        "Tab cards. Swipe left or right to browse, swipe up to close, tap to open.");
  }

  float spacing() {
    return getWidth() * .69f;
  }

  float cardWidth() {
    return Math.min(getWidth() * .80f, 440 * density);
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    if (tabs.isEmpty()) return;
    int center = Math.max(0, Math.min(tabs.size() - 1, Math.round(position)));
    for (int delta = tabs.size(); delta >= 1; delta--) {
      drawCard(c, center - delta);
      drawCard(c, center + delta);
    }
    drawCard(c, center);
    p.setColor(dark ? 0xFFAFA5C4 : 0xFF756C87);
    p.setTextAlign(Paint.Align.CENTER);
    p.setTextSize(12 * density);
    c.drawText("Swipe up to close a tab", getWidth() / 2f, getHeight() - 14 * density, p);
  }

  private void drawCard(Canvas c, int i) {
    if (i < 0 || i >= tabs.size()) return;
    float distance = i - position;
    float cw = cardWidth(),
        ch = Math.min(getHeight() * .83f, cw * 1.5f),
        scale = 1 - Math.min(.13f, Math.abs(distance) * .10f);
    float x = getWidth() / 2f + distance * spacing(),
        y = getHeight() * .46f + (i == Math.round(position) ? dragY : 0);
    if (x + cw < 0 || x - cw > getWidth()) return;
    BrowserActivity.Tab tab = tabs.get(i);
    c.save();
    c.translate(x, y);
    c.scale(scale, scale);
    RectF r = new RectF(-cw / 2, -ch / 2, cw / 2, ch / 2);
    p.setColor(dark ? 0xFF272430 : 0xFFF9F7FC);
    p.setShadowLayer(18 * density, 0, 7 * density, 0x204A335F);
    c.drawRoundRect(r, 25 * density, 25 * density, p);
    p.clearShadowLayer();
    Path clip = new Path();
    clip.addRoundRect(r, 25 * density, 25 * density, Path.Direction.CW);
    c.clipPath(clip);
    float header = 49 * density;
    RectF content = new RectF(r.left, r.top + header, r.right, r.bottom);
    if (tab.preview != null && !tab.preview.isRecycled()) {
      p.setFilterBitmap(true);
      c.drawBitmap(tab.preview, null, content, p);
    } else {
      p.setShader(
          new LinearGradient(
              0,
              content.top,
              cw,
              content.bottom,
              new int[] {dark ? 0xFF24202E : 0xFFF3E1F1, dark ? 0xFF111118 : 0xFFE0E6FA},
              null,
              Shader.TileMode.CLAMP));
      c.drawRect(content, p);
      p.setShader(null);
      p.setColor(dark ? 0xFFD4CDE4 : 0xFF62567E);
      p.setTextSize(31 * density);
      p.setTextAlign(Paint.Align.CENTER);
      c.drawText("Aura", 0, 0, p);
    }
    p.setColor(dark ? 0xFFDDD6EC : 0xFF363043);
    p.setTextAlign(Paint.Align.LEFT);
    p.setTextSize(14 * density);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    String title = tab.home ? "Start Page" : tab.title;
    while (p.measureText(title) > cw - 94 * density && title.length() > 1)
      title = title.substring(0, title.length() - 1);
    c.drawText(title, r.left + 19 * density, r.top + 30 * density, p);
    p.setStrokeWidth(1.6f * density);
    float cx = r.right - 25 * density, cy = r.top + 25 * density;
    c.drawLine(cx - 4 * density, cy - 4 * density, cx + 4 * density, cy + 4 * density, p);
    c.drawLine(cx + 4 * density, cy - 4 * density, cx - 4 * density, cy + 4 * density, p);
    c.restore();
  }

  public boolean onTouchEvent(android.view.MotionEvent e) {
    float x = e.getX(), y = e.getY();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        scroller.forceFinished(true);
        startX = lastX = x;
        startY = y;
        vertical = false;
        moved = false;
        velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
        return true;
      case MotionEvent.ACTION_MOVE:
        velocity.addMovement(e);
        float dx = x - startX, dy = y - startY;
        if (!moved && (Math.abs(dx) > 8 * density || Math.abs(dy) > 8 * density)) {
          moved = true;
          vertical = Math.abs(dy) > Math.abs(dx) * 1.2f;
        }
        if (moved) {
          if (vertical) dragY = Math.min(25 * density, dy);
          else
            position =
                Math.max(-.2f, Math.min(tabs.size() - .8f, position - (x - lastX) / spacing()));
          invalidate();
        }
        lastX = x;
        return true;
      case MotionEvent.ACTION_UP:
        velocity.addMovement(e);
        velocity.computeCurrentVelocity(1000);
        int at = Math.max(0, Math.min(tabs.size() - 1, Math.round(position)));
        if (vertical && (dragY < -80 * density || velocity.getYVelocity() < -1000 * density)) {
          listener.close(at);
          position = Math.max(0, Math.min(tabs.size() - 1, at));
          performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        } else if (!moved) {
          float cw = cardWidth(),
              ch = Math.min(getHeight() * .83f, cw * 1.5f),
              top = getHeight() * .46f - ch / 2;
          if (y < top + 49 * density && x > getWidth() / 2 + cw / 2 - 48 * density && y >= top)
            listener.close(at);
          else
            listener.select(
                Math.max(
                    0,
                    Math.min(
                        tabs.size() - 1,
                        Math.round(position + (x - getWidth() / 2f) / spacing()))));
        } else if (!vertical) {
          float target = position - velocity.getXVelocity() / spacing() * .16f;
          int snap = Math.max(0, Math.min(tabs.size() - 1, Math.round(target)));
          scroller.startScroll(
              (int) (position * 10000), 0, (int) ((snap - position) * 10000), 0, 330);
        }
        dragY = 0;
        velocity.recycle();
        velocity = null;
        postInvalidateOnAnimation();
        return true;
      case MotionEvent.ACTION_CANCEL:
        dragY = 0;
        if (velocity != null) velocity.recycle();
        velocity = null;
        position = Math.max(0, Math.min(tabs.size() - 1, Math.round(position)));
        invalidate();
        return true;
    }
    return true;
  }

  public void computeScroll() {
    if (scroller.computeScrollOffset()) {
      position = scroller.getCurrX() / 10000f;
      postInvalidateOnAnimation();
    }
  }

  public boolean onKeyDown(int key, android.view.KeyEvent event) {
    if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
      position =
          Math.max(
              0,
              Math.min(
                  tabs.size() - 1,
                  Math.round(position) + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1)));
      invalidate();
      return true;
    }
    if (key == KeyEvent.KEYCODE_ENTER) {
      listener.select(Math.round(position));
      return true;
    }
    return super.onKeyDown(key, event);
  }
}

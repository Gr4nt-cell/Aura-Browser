package com.gr4nt.aurabrowser;

import android.content.*;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.ImageView;

final class Glass extends Drawable {
  private final Paint p = new Paint(3);
  private final boolean dark;
  private final float radius;

  Glass(boolean d, float r) {
    dark = d;
    radius = r;
  }

  public void draw(Canvas c) {
    Rect b = getBounds();
    RectF r = new RectF(b.left + .5f, b.top + .5f, b.right - .5f, b.bottom - .5f);
    p.setStyle(Paint.Style.FILL);
    p.setShader(
        new LinearGradient(
            0,
            b.top,
            b.right,
            b.bottom,
            dark
                ? new int[] {0xEB34303F, 0xDD191922, 0xE52A2536}
                : new int[] {0xF2FFFFFF, 0xCFF7F4FC, 0xE5FFFFFF},
            null,
            Shader.TileMode.CLAMP));
    c.drawRoundRect(r, radius, radius, p);
    p.setShader(null);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.4f);
    p.setColor(dark ? 0x448E849F : 0xFFFFFFFF);
    c.drawRoundRect(r, radius, radius, p);
    p.setStyle(Paint.Style.FILL);
  }

  public void setAlpha(int a) {}

  public void setColorFilter(ColorFilter f) {}

  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }

  /** Blurs only the sampled backdrop; text and glyphs remain sharp. */
  static final class Capsule extends FrameLayout {
    private final ImageView backdrop;

    Capsule(Context context, boolean dark, float radius) {
      super(context);
      float d = getResources().getDisplayMetrics().density;
      setBackground(new android.graphics.drawable.ColorDrawable(dark ? 0xFF242428 : 0xFFF5F5F7));
      setOutlineProvider(
          new ViewOutlineProvider() {
            public void getOutline(View v, Outline outline) {
              outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
          });
      setClipToOutline(true);
      setElevation(5 * d);
      backdrop = new ImageView(context);
      backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
      if (Build.VERSION.SDK_INT >= 31)
        backdrop.setRenderEffect(
            RenderEffect.createBlurEffect(14 * d, 14 * d, Shader.TileMode.CLAMP));
      addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
      View coating = new View(context);
      coating.setBackground(new Lens(dark, radius));
      addView(coating, new FrameLayout.LayoutParams(-1, -1));
    }

    void setSample(Bitmap bitmap) {
      backdrop.setImageBitmap(bitmap);
    }
  }

  static final class Lens extends Drawable {
    private final Paint paint = new Paint(3);
    private final boolean dark;
    private final float radius;

    Lens(boolean dark, float radius) {
      this.dark = dark;
      this.radius = radius;
    }

    public void draw(Canvas canvas) {
      Rect b = getBounds();
      RectF box = new RectF(b.left + .75f, b.top + .75f, b.right - .75f, b.bottom - .75f);
      paint.setStyle(Paint.Style.FILL);
      paint.setShader(
          new LinearGradient(
              0,
              b.top,
              0,
              b.bottom,
              dark
                  ? new int[] {0xC244444A, 0xB82B2B30, 0xD0323238}
                  : new int[] {0xDFFFFFFF, 0xA8F5F5F7, 0xD4FFFFFF},
              new float[] {0, .55f, 1},
              Shader.TileMode.CLAMP));
      canvas.drawRoundRect(box, radius, radius, paint);
      paint.setShader(
          new LinearGradient(
              0,
              b.top,
              0,
              b.bottom,
              dark
                  ? new int[] {0x779A9AA6, 0x223B3B44, 0x66595965}
                  : new int[] {0xFFFFFFFF, 0x66FFFFFF, 0xB3D4D4DC},
              null,
              Shader.TileMode.CLAMP));
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.5f);
      canvas.drawRoundRect(box, radius, radius, paint);
      paint.setShader(null);
    }

    public void setAlpha(int a) {}

    public void setColorFilter(ColorFilter f) {}

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  static class Pearl extends View {
    final Paint p = new Paint(3);
    boolean dark;
    private boolean pageMode;
    private Bitmap dockSample;
    private int dockDepth;

    void setPageMode(boolean page) {
      pageMode = page;
      setAlpha(1);
      invalidate();
    }

    void setDockBackdrop(Bitmap bitmap, int depth) {
      dockSample = bitmap;
      dockDepth = depth;
      invalidate();
    }

    Pearl(Context c, boolean d) {
      super(c);
      dark = d;
      setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight();
      c.drawColor(pageMode ? (dark ? 0xFF07070C : Color.WHITE) : (dark ? 0xFF07070C : 0xFFF4F2F8));
      if (!pageMode) {
        glow(c, w * .02f, h * .24f, w * .98f, dark ? 0x603C2462 : 0xB5EDCBE1);
        glow(c, w * .95f, h * .18f, w * .8f, dark ? 0x50344165 : 0x99C8DCF5);
        glow(c, w * .45f, h * .72f, w * .88f, dark ? 0x503B3463 : 0x99D8D1F4);
        glow(c, w * .1f, h * .9f, w * .7f, dark ? 0x40213C40 : 0x80D2EEE7);
      }
      if (pageMode && dockSample != null && !dockSample.isRecycled() && dockDepth > 0) {
        p.setShader(null);
        p.setFilterBitmap(true);
        p.setAlpha(255);
        c.drawBitmap(dockSample, null, new RectF(0, h - dockDepth, w, h), p);
      }
    }

    void glow(Canvas c, float x, float y, float r, int color) {
      p.setShader(
          new RadialGradient(
              x,
              y,
              Math.max(1, r),
              new int[] {color, color & 0x00FFFFFF},
              null,
              Shader.TileMode.CLAMP));
      c.drawRect(0, 0, getWidth(), getHeight(), p);
      p.setShader(null);
    }
  }

  static final class Favorite extends View {
    private final Paint paint = new Paint(3);
    private final String brand;

    Favorite(Context context, String brand, boolean dark) {
      super(context);
      this.brand = brand;
      android.graphics.drawable.GradientDrawable tile =
          new android.graphics.drawable.GradientDrawable(
              android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
              dark ? new int[] {0xFF37373D, 0xFF222227} : new int[] {0xFFFFFFFF, 0xFFF7F7FA});
      tile.setCornerRadius(16 * getResources().getDisplayMetrics().density);
      tile.setStroke(1, dark ? 0x555D5D68 : 0xDDFFFFFF);
      setBackground(tile);
      setElevation(3 * getResources().getDisplayMetrics().density);
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      c.save();
      float side = Math.min(getWidth(), getHeight()) * .62f;
      c.translate((getWidth() - side) / 2, (getHeight() - side) / 2);
      c.scale(side / 24, side / 24);
      paint.setShader(null);
      paint.setStyle(Paint.Style.FILL);
      Path path = new Path();
      if (brand.equals("Google")) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.5f);
        paint.setStrokeCap(Paint.Cap.BUTT);
        int[] colors = {0xFF4285F4, 0xFF34A853, 0xFFFBBC05, 0xFFEA4335};
        float[] starts = {-10, 47, 139, 185}, sweeps = {58, 94, 48, 117};
        for (int i = 0; i < 4; i++) {
          paint.setColor(colors[i]);
          c.drawArc(4, 4, 20, 20, starts[i], sweeps[i], false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF4285F4);
        c.drawRect(12, 10, 22, 14, paint);
        c.drawRect(18, 12, 22, 16, paint);
      } else if (brand.equals("YouTube")) {
        paint.setColor(0xFFFF0033);
        c.drawRoundRect(1, 4, 23, 20, 5, 5, paint);
        paint.setColor(Color.WHITE);
        path.moveTo(10, 8);
        path.lineTo(17, 12);
        path.lineTo(10, 16);
        path.close();
        c.drawPath(path, paint);
      } else if (brand.equals("GitHub")) {
        paint.setColor(0xFF24292F);
        c.drawCircle(12, 12, 11, paint);
        paint.setColor(Color.WHITE);
        c.drawOval(5, 7, 19, 17, paint);
        path.moveTo(6, 9);
        path.lineTo(6, 4);
        path.lineTo(10, 7);
        path.lineTo(15, 7);
        path.lineTo(18, 4);
        path.lineTo(18, 10);
        path.close();
        c.drawPath(path, paint);
        c.drawRoundRect(10, 15, 15, 23, 2, 2, paint);
        paint.setColor(0xFF24292F);
        c.drawCircle(9, 11, 1, paint);
        c.drawCircle(15, 11, 1, paint);
      } else if (brand.equals("Play Console")) {
        paint.setColor(0xFF00C9C0);
        path.moveTo(4, 2);
        path.lineTo(4, 22);
        path.lineTo(14, 12);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        paint.setColor(0xFF00B05B);
        path.moveTo(4, 2);
        path.lineTo(14, 12);
        path.lineTo(20, 8);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        paint.setColor(0xFFEF534F);
        path.moveTo(4, 22);
        path.lineTo(14, 12);
        path.lineTo(20, 16);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        paint.setColor(0xFFFFC640);
        path.moveTo(14, 12);
        path.lineTo(20, 8);
        path.lineTo(24, 12);
        path.lineTo(20, 16);
        path.close();
        c.drawPath(path, paint);
      } else if (brand.equals("Reddit")) {
        paint.setColor(0xFFFF4500);
        c.drawCircle(12, 12, 11, paint);
        paint.setColor(Color.WHITE);
        c.drawOval(5, 9, 19, 18, paint);
        paint.setColor(0xFFFF4500);
        c.drawCircle(9, 13, 1, paint);
        c.drawCircle(15, 13, 1, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        c.drawArc(9, 13, 15, 16, 10, 160, false, paint);
      } else if (brand.equals("BBC")) {
        for (int i = 0; i < 3; i++) {
          paint.setColor(0xFF19191C);
          c.drawRect(i * 8, 7, i * 8 + 7, 17, paint);
          paint.setColor(Color.WHITE);
          paint.setTextAlign(Paint.Align.CENTER);
          paint.setTextSize(7);
          paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
          c.drawText("" + "BBC".charAt(i), i * 8 + 3.5f, 14.5f, paint);
        }
      } else {
        paint.setColor(brand.equals("Add favourite") ? 0xFF007AFF : 0xFF252529);
        paint.setTypeface(
            Typeface.create(brand.equals("Wikipedia") ? "serif" : "sans-serif", Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(brand.equals("Wikipedia") ? 27 : 25);
        c.drawText(
            brand.equals("Wikipedia")
                ? "W"
                : brand.equals("Add favourite") ? "+" : brand.substring(0, 1).toUpperCase(),
            12,
            20,
            paint);
      }
      c.restore();
    }
  }

  static class Icon extends View {
    String name;
    int color;
    Paint p = new Paint(3);
    float stroke = 1.7f;

    Icon(Context c, String n, int col) {
      super(c);
      name = n;
      color = col;
      setContentDescription(n);
      setFocusable(true);
    }

    protected void onDraw(Canvas canvas) {
      canvas.save();
      float size = Math.min(getWidth(), getHeight()) * .48f;
      canvas.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
      canvas.scale(size / 24, size / 24);
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(stroke);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      Path path = new Path();
      switch (name) {
        case "page":
          canvas.drawRoundRect(3, 3, 20, 21, 3, 3, p);
          line(canvas, 7, 8, 16, 8);
          line(canvas, 7, 12, 16, 12);
          line(canvas, 7, 16, 12, 16);
          break;
        case "back":
          line(canvas, 15, 4, 7, 12);
          line(canvas, 7, 12, 15, 20);
          break;
        case "forward":
          line(canvas, 9, 4, 17, 12);
          line(canvas, 17, 12, 9, 20);
          break;
        case "plus":
          line(canvas, 12, 4, 12, 20);
          line(canvas, 4, 12, 20, 12);
          break;
        case "close":
          line(canvas, 5, 5, 19, 19);
          line(canvas, 19, 5, 5, 19);
          break;
        case "search":
          canvas.drawCircle(10, 10, 6, p);
          line(canvas, 15, 15, 21, 21);
          break;
        case "tabs":
          canvas.drawRoundRect(3, 7, 17, 22, 3, 3, p);
          path.moveTo(8, 3);
          path.lineTo(19, 3);
          path.quadTo(22, 3, 22, 6);
          path.lineTo(22, 16);
          canvas.drawPath(path, p);
          break;
        case "shield":
          path.moveTo(12, 2);
          path.lineTo(21, 6);
          path.lineTo(20, 14);
          path.quadTo(18, 20, 12, 23);
          path.quadTo(6, 20, 4, 14);
          path.lineTo(3, 6);
          path.close();
          canvas.drawPath(path, p);
          line(canvas, 8, 12, 11, 15);
          line(canvas, 11, 15, 16, 9);
          break;
        case "download":
          line(canvas, 12, 2, 12, 16);
          line(canvas, 7, 11, 12, 16);
          line(canvas, 17, 11, 12, 16);
          path.moveTo(3, 17);
          path.lineTo(3, 22);
          path.lineTo(21, 22);
          path.lineTo(21, 17);
          canvas.drawPath(path, p);
          break;
        case "share":
          line(canvas, 12, 15, 12, 1);
          line(canvas, 7, 6, 12, 1);
          line(canvas, 17, 6, 12, 1);
          path.moveTo(6, 10);
          path.lineTo(3, 10);
          path.lineTo(3, 23);
          path.lineTo(21, 23);
          path.lineTo(21, 10);
          path.lineTo(18, 10);
          canvas.drawPath(path, p);
          break;
        case "bookmarks":
          path.moveTo(4, 3);
          path.lineTo(10, 3);
          path.quadTo(12, 3, 12, 6);
          path.quadTo(12, 3, 14, 3);
          path.lineTo(21, 3);
          path.lineTo(21, 21);
          path.lineTo(15, 21);
          path.quadTo(12, 21, 12, 23);
          path.quadTo(10, 21, 9, 21);
          path.lineTo(3, 21);
          path.lineTo(3, 3);
          canvas.drawPath(path, p);
          line(canvas, 12, 6, 12, 22);
          break;
        case "reload":
          canvas.drawArc(3, 3, 21, 21, 40, 285, false, p);
          line(canvas, 20, 3, 20, 9);
          line(canvas, 20, 9, 14, 9);
          break;
        case "home":
          path.moveTo(2, 11);
          path.lineTo(12, 2);
          path.lineTo(22, 11);
          path.moveTo(5, 9);
          path.lineTo(5, 22);
          path.lineTo(19, 22);
          path.lineTo(19, 9);
          canvas.drawPath(path, p);
          break;
        case "sidebar":
          canvas.drawRoundRect(2, 3, 22, 21, 3, 3, p);
          line(canvas, 9, 3, 9, 21);
          line(canvas, 4, 8, 7, 8);
          line(canvas, 4, 12, 7, 12);
          break;
        case "lock":
          canvas.drawRoundRect(5, 10, 19, 22, 3, 3, p);
          canvas.drawArc(8, 2, 16, 17, 180, 180, false, p);
          break;
        case "play":
          path.moveTo(6, 3);
          path.lineTo(22, 12);
          path.lineTo(6, 21);
          path.close();
          canvas.drawPath(path, p);
          break;
        default:
          p.setStyle(Paint.Style.FILL);
          for (int i = 0; i < 3; i++) canvas.drawCircle(4 + i * 8, 12, 1.8f, p);
      }
      canvas.restore();
    }

    void line(Canvas c, float x, float y, float xx, float yy) {
      c.drawLine(x, y, xx, yy, p);
    }
  }
}

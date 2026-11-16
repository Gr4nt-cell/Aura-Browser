package com.gr4nt.aurabrowser;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Shared app-owned dialog renderer for phone, tablet and desktop windows. */
final class GlassDialog extends Dialog {
  private final BrowserActivity owner;
  private final Glass.Capsule surface;
  private Bitmap sample;
  private final int ink, accent;

  private GlassDialog(Builder b) {
    super(b.owner);
    owner = b.owner;
    ink = owner.dialogInk();
    accent = owner.dialogAccent();
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    sample = owner.captureGlassBackdrop();
    surface = new Glass.Capsule(owner, owner.dialogDark(), owner.dp(28));
    surface.setSample(sample);
    LinearLayout panel = new LinearLayout(owner);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(owner.dp(14), owner.dp(12), owner.dp(14), owner.dp(14));
    LinearLayout heading = new LinearLayout(owner);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = label(b.title == null ? "Aura Browser" : b.title, 21);
    title.setTypeface(Typeface.create("sans-serif-medium", 0));
    title.setPadding(owner.dp(8), 0, owner.dp(4), 0);
    heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Glass.Icon close = new Glass.Icon(owner, "close", ink);
    close.setContentDescription("Close dialog");
    close.setOnClickListener(v -> cancel());
    heading.addView(close, new LinearLayout.LayoutParams(owner.dp(44), owner.dp(44)));
    panel.addView(heading);
    LinearLayout content = new LinearLayout(owner);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(owner.dp(7), owner.dp(4), owner.dp(7), owner.dp(10));
    if (b.message != null) {
      TextView message = label(b.message, 15);
      message.setPadding(0, owner.dp(7), 0, owner.dp(12));
      message.setLineSpacing(owner.dp(3), 1);
      content.addView(message);
    }
    if (b.items != null)
      for (int i = 0; i < b.items.length; i++) {
        final int index = i;
        TextView item = label(b.items[i], 16);
        item.setMinHeight(owner.dp(50));
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(owner.dp(12), owner.dp(10), owner.dp(12), owner.dp(10));
        item.setBackground(
            new RippleDrawable(
                ColorStateList.valueOf(0x227A7A88),
                new Glass(owner.dialogDark(), owner.dp(15)),
                null));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2);
        ip.setMargins(0, owner.dp(3), 0, owner.dp(3));
        content.addView(item, ip);
        item.setOnClickListener(
            v -> {
              dismiss();
              if (b.itemListener != null) b.itemListener.onClick(this, index);
            });
      }
    if (b.view != null) {
      if (b.view.getParent() != null) ((ViewGroup) b.view.getParent()).removeView(b.view);
      styleControls(b.view);
      content.addView(b.view, new LinearLayout.LayoutParams(-1, -2));
    }
    ScrollView scroll = new ScrollView(owner);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(content);
    int max = owner.dp((int) (owner.getResources().getConfiguration().screenHeightDp * .58f));
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout buttons = new LinearLayout(owner);
    buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    button(buttons, b.neutral, b.neutralListener, DialogInterface.BUTTON_NEUTRAL, false);
    button(buttons, b.negative, b.negativeListener, DialogInterface.BUTTON_NEGATIVE, false);
    button(buttons, b.positive, b.positiveListener, DialogInterface.BUTTON_POSITIVE, true);
    if (buttons.getChildCount() > 0)
      panel.addView(buttons, new LinearLayout.LayoutParams(-1, owner.dp(54)));
    surface.addView(panel, new android.widget.FrameLayout.LayoutParams(-1, -2));
    setContentView(surface);
    setCanceledOnTouchOutside(true);
    if (b.cancelListener != null) setOnCancelListener(b.cancelListener);
    Window win = getWindow();
    win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    win.setDimAmount(.18f);
    win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    scroll.post(
        () -> {
          if (scroll.getHeight() > max)
            scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, max));
        });
  }

  private TextView label(CharSequence s, int size) {
    TextView t = new TextView(owner);
    t.setText(s);
    t.setTextColor(ink);
    t.setTextSize(size);
    return t;
  }

  private void button(
      LinearLayout row,
      CharSequence title,
      DialogInterface.OnClickListener action,
      int which,
      boolean primary) {
    if (title == null) return;
    TextView t = label(title, 16);
    t.setGravity(Gravity.CENTER);
    t.setTypeface(Typeface.create("sans-serif-medium", 0));
    android.graphics.drawable.GradientDrawable shape =
        new android.graphics.drawable.GradientDrawable();
    shape.setCornerRadius(owner.dp(22));
    shape.setColor(primary ? accent : owner.dialogDark() ? 0x554C4C58 : 0x88FFFFFF);
    t.setTextColor(primary ? Color.WHITE : accent);
    t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x225F5F72), shape, null));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, owner.dp(44), 1);
    p.setMargins(owner.dp(3), owner.dp(5), owner.dp(3), 0);
    row.addView(t, p);
    t.setOnClickListener(
        v -> {
          dismiss();
          if (action != null) action.onClick(this, which);
        });
  }

  private void styleControls(View view) {
    if (view instanceof Switch) {
      Switch s = (Switch) view;
      s.setTextColor(ink);
      s.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
      s.setTrackTintList(
          new ColorStateList(
              new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
              new int[] {0xFF34C759, owner.dialogDark() ? 0xFF5B5B65 : 0xFFC6C6CF}));
    } else if (view instanceof EditText) {
      EditText e = (EditText) view;
      e.setTextColor(ink);
      e.setHintTextColor(owner.dialogDark() ? 0xFFAAAAB5 : 0xFF85858F);
      e.setBackground(new Glass(owner.dialogDark(), owner.dp(14)));
      e.setPadding(owner.dp(12), owner.dp(10), owner.dp(12), owner.dp(10));
    }
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        styleControls(((ViewGroup) view).getChildAt(i));
  }

  public void show() {
    super.show();
    int available = owner.getResources().getConfiguration().screenWidthDp;
    boolean wide = available >= 600;
    getWindow().setGravity(wide ? Gravity.CENTER : Gravity.BOTTOM);
    getWindow().setLayout(owner.dp(Math.min(available - 24, 560)), -2);
    WindowManager.LayoutParams p = getWindow().getAttributes();
    p.y = wide ? 0 : owner.dp(12);
    getWindow().setAttributes(p);
    surface.setAlpha(0);
    surface.setTranslationY(owner.dp(18));
    surface.animate().alpha(1).translationY(0).setDuration(180).start();
  }

  public void dismiss() {
    super.dismiss();
    releaseSample();
  }

  private void releaseSample() {
    surface.setSample(null);
    if (sample != null && !sample.isRecycled()) sample.recycle();
    sample = null;
  }

  static final class Builder {
    final BrowserActivity owner;
    CharSequence title, message, positive, negative, neutral;
    CharSequence[] items;
    View view;
    DialogInterface.OnClickListener positiveListener,
        negativeListener,
        neutralListener,
        itemListener;
    DialogInterface.OnCancelListener cancelListener;

    Builder(Context context) {
      owner = (BrowserActivity) context;
    }

    Builder setTitle(CharSequence v) {
      title = v;
      return this;
    }

    Builder setMessage(CharSequence v) {
      message = v;
      return this;
    }

    Builder setView(View v) {
      view = v;
      return this;
    }

    Builder setItems(CharSequence[] v, DialogInterface.OnClickListener l) {
      items = v;
      itemListener = l;
      return this;
    }

    Builder setPositiveButton(CharSequence v, DialogInterface.OnClickListener l) {
      positive = v;
      positiveListener = l;
      return this;
    }

    Builder setNegativeButton(CharSequence v, DialogInterface.OnClickListener l) {
      negative = v;
      negativeListener = l;
      return this;
    }

    Builder setNeutralButton(CharSequence v, DialogInterface.OnClickListener l) {
      neutral = v;
      neutralListener = l;
      return this;
    }

    Builder setOnCancelListener(DialogInterface.OnCancelListener l) {
      cancelListener = l;
      return this;
    }

    GlassDialog create() {
      return new GlassDialog(this);
    }

    GlassDialog show() {
      GlassDialog d = create();
      d.show();
      return d;
    }
  }
}

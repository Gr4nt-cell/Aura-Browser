package com.gr4nt.aurabrowser;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.view.inputmethod.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

public class BrowserActivity extends Activity {
  static final class Tab {
    String id = UUID.randomUUID().toString(), url = "", title = "Start Page";
    boolean home = true, desktop;
    WebView web;
    Bundle state;
    Bitmap preview;
    final LinkedHashSet<String> media = new LinkedHashSet<>();
  }

  private final ArrayList<Tab> tabs = new ArrayList<>();
  private int active;
  private FrameLayout root, viewport, deckOverlay, fullScreen;
  private LinearLayout body, toolbar, tabStrip, workspace, sidebar;
  private EditText address;
  private ProgressBar progress;
  private TextView tabCount;
  private View fullscreenView;
  private WebChromeClient.CustomViewCallback fullscreenCallback;
  private SharedPreferences prefs;
  private Blocker blocker;
  private DownloadCore downloads;
  private Handler handler = new Handler(Looper.getMainLooper());
  private boolean dark, wide, desktopWidth, sidebarShown;
  private int ink, muted, accent;
  private ValueCallback<Uri[]> fileCallback;
  private Uri cameraUri;
  private WebChromeClient.FileChooserParams fileParams;
  private PermissionRequest sitePermission;
  private ArrayList<String> siteResources;
  private Dialog downloadsDialog;
  private Glass.Capsule mobileAddress, mobileBack, mobileMore;
  private View mobilePage, mobileReload, mobileLoadLine;
  private LinearLayout mobileChromeRow;
  private Glass.Pearl pearlBackground;
  private boolean chromeCollapsed, chromeAnimating;
  private android.animation.ValueAnimator chromeMorph;
  private long lastGlassSample;
  private Bitmap glassSample;
  private Dialog chromeSheet;

  private static final int FILES = 40,
      CAMERA = 41,
      SITE_PERMISSION = 42,
      CAMERA_PERMISSION = 43,
      NOTIFICATIONS = 44;
  private final String MEDIA_SCAN =
      "(function(){var a=[];document.querySelectorAll('video,audio,video source,audio"
          + " source,a[href]').forEach(function(e){var"
          + " u=e.currentSrc||e.src||e.href;if(u&&(/\\.(mp4|webm|m4v|mov|m3u8|mp3|m4a|ogg)(\\?|$)/i.test(u)||e.tagName==='VIDEO'||e.tagName==='AUDIO'))a.push(u)});performance.getEntriesByType('resource').forEach(function(e){if(/\\.(mp4|webm|m3u8|m4v|mp3|m4a)(\\?|$)/i.test(e.name))a.push(e.name)});return"
          + " JSON.stringify(Array.from(new Set(a)).filter(function(u){return"
          + " /^https?:/.test(u)}).slice(0,100))})()";

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> onBackPressed());
    prefs = getSharedPreferences("prism", 0);
    blocker = new Blocker(this);
    blocker.enabled = prefs.getBoolean("blocking", true);
    for (String s : prefs.getStringSet("allowed", Collections.emptySet())) blocker.allowed.add(s);
    downloads = DownloadService.engine(this);
    restoreTabs();
    readTheme();
    buildUi();
    if (getIntent().getData() != null) navigate(getIntent().getData().toString());
    if (getIntent().getBooleanExtra("downloads", false))
      handler.postDelayed(() -> showDownloads(), 500);
  }

  void readTheme() {
    String mode = prefs.getString("theme", "System");
    dark =
        mode.equals("Dark")
            || (mode.equals("System")
                && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES);
    setTheme(
        dark
            ? android.R.style.Theme_Material_NoActionBar
            : android.R.style.Theme_Material_Light_NoActionBar);
    ink = dark ? 0xFFF0EBF7 : 0xFF27232F;
    muted = dark ? 0xFFB5ABCA : 0xFF82788F;
    accent = dark ? 0xFF68A9FF : 0xFF007AFF;
  }

  boolean dialogDark() {
    return dark;
  }

  int dialogInk() {
    return ink;
  }

  int dialogAccent() {
    return accent;
  }

  Bitmap captureGlassBackdrop() {
    if (root == null || root.getWidth() == 0 || root.getHeight() == 0) return null;
    try {
      int width = Build.VERSION.SDK_INT >= 31 ? 96 : 20;
      int height = Math.max(24, Math.round(root.getHeight() * width / (float) root.getWidth()));
      Bitmap b = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
      Canvas c = new Canvas(b);
      c.scale(width / (float) root.getWidth(), height / (float) root.getHeight());
      root.draw(c);
      return b;
    } catch (RuntimeException | OutOfMemoryError e) {
      return null;
    }
  }

  int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  Tab current() {
    return tabs.get(Math.max(0, Math.min(active, tabs.size() - 1)));
  }

  LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  TextView text(String t, int size, int color) {
    TextView v = new TextView(this);
    v.setText(t);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setFontFeatureSettings("kern");
    return v;
  }

  TextView label(String t, int size) {
    return text(t, size, ink);
  }

  void bold(TextView v) {
    v.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
  }

  void pad(View v, int n) {
    v.setPadding(dp(n), dp(n), dp(n), dp(n));
  }

  void glass(View v, int radius) {
    v.setBackground(new Glass(dark, dp(radius)));
    v.setElevation(dp(3));
    v.setClipToOutline(true);
  }

  Glass.Icon icon(String n, Runnable action) {
    Glass.Icon i = new Glass.Icon(this, n, ink);
    i.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
    i.setOnClickListener(v -> action.run());
    i.setTooltipText(n.substring(0, 1).toUpperCase() + n.substring(1));
    i.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(dark ? 0x227F73A3 : 0x225D4A91),
            null,
            null));
    return i;
  }

  TextView action(String title, Runnable r) {
    TextView v = text(title, 15, accent);
    bold(v);
    v.setPadding(dp(16), dp(12), dp(16), dp(12));
    v.setMinHeight(dp(46));
    v.setOnClickListener(x -> r.run());
    return v;
  }

  LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
  }

  void buildUi() {
    if (viewport != null)
      for (Tab t : tabs)
        if (t.web != null && t.web.getParent() != null)
          ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
    wide = getResources().getConfiguration().screenWidthDp >= 600;
    desktopWidth = getResources().getConfiguration().screenWidthDp >= 1000;
    root = new FrameLayout(this);
    root.setClipToPadding(false);
    pearlBackground = new Glass.Pearl(this, dark);
    root.addView(pearlBackground, new FrameLayout.LayoutParams(-1, -1));
    body = vertical();
    root.addView(body, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.TRANSPARENT);
    if (Build.VERSION.SDK_INT >= 29) {
      getWindow().setNavigationBarContrastEnforced(false);
      getWindow().setStatusBarContrastEnforced(false);
    }
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      getWindow()
          .getInsetsController()
          .setSystemBarsAppearance(
              dark
                  ? 0
                  : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                      | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
              WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
      root.setOnApplyWindowInsetsListener(
          (v, insets) -> {
            android.graphics.Insets b =
                insets.getInsets(
                    WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout()
                        | WindowInsets.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            FrameLayout.LayoutParams backdrop = new FrameLayout.LayoutParams(-1, -1);
            backdrop.setMargins(-b.left, -b.top, -b.right, -b.bottom);
            pearlBackground.setLayoutParams(backdrop);
            handler.post(() -> updateGlass());
            return insets;
          });
    } else root.setFitsSystemWindows(true);
    chromeCollapsed = false;
    mobileAddress = null;
    mobileBack = null;
    mobileMore = null;
    tabCount = null;
    toolbar = vertical();
    if (wide) body.addView(toolbar, lp(-1, -2));
    createToolbar();
    if (wide) {
      HorizontalScrollView scroll = new HorizontalScrollView(this);
      scroll.setHorizontalScrollBarEnabled(false);
      tabStrip = row();
      scroll.addView(tabStrip, lp(-2, 42));
      body.addView(scroll, lp(-1, 44));
      refreshTabStrip();
    }
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
    progress.setMax(100);
    progress.setVisibility(View.INVISIBLE);
    body.addView(progress, lp(-1, wide ? 2 : 0));
    workspace = row();
    viewport = new FrameLayout(this);
    if (wide && (desktopWidth || sidebarShown)) {
      sidebar = createSidebar();
      workspace.addView(sidebar, lp(desktopWidth ? 232 : 200, -1));
    }
    workspace.addView(viewport, new LinearLayout.LayoutParams(0, -1, 1));
    body.addView(workspace, new LinearLayout.LayoutParams(-1, 0, 1));
    if (!wide) body.addView(toolbar, lp(-1, -2));
    showTab(active);
    root.requestApplyInsets();
  }

  void createToolbar() {
    toolbar.removeAllViews();
    toolbar.setBackgroundColor(Color.TRANSPARENT);
    toolbar.setPadding(dp(wide ? 12 : 14), dp(wide ? 8 : 7), dp(wide ? 12 : 14), dp(wide ? 8 : 2));
    LinearLayout addressRow = row();
    glass(addressRow, wide ? 12 : 18);
    address = new EditText(this);
    address.setSingleLine(true);
    address.setTextSize(15);
    address.setTextColor(ink);
    address.setHintTextColor(muted);
    address.setHint("Search or enter website name");
    address.setBackgroundColor(Color.TRANSPARENT);
    address.setPadding(dp(7), 0, dp(7), 0);
    address.setSelectAllOnFocus(true);
    address.setOnFocusChangeListener(
        (v, focused) -> {
          if (focused) {
            setChromeCollapsed(false);
            address.setText(current().home ? "" : current().url);
            address.setGravity(Gravity.CENTER_VERTICAL);
            address.post(() -> address.selectAll());
          } else updateAddress();
        });
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    address.setImeOptions(EditorInfo.IME_ACTION_GO);
    address.setOnEditorActionListener(
        (v, a, e) -> {
          if (a == EditorInfo.IME_ACTION_GO
              || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
            navigate(address.getText().toString());
            address.clearFocus();
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(address.getWindowToken(), 0);
            return true;
          }
          return false;
        });
    TextView aa = label("aA", 17);
    aa.setGravity(Gravity.CENTER);
    aa.setContentDescription("Page options");
    aa.setLayoutParams(lp(44, 44));
    aa.setOnClickListener(v -> pageOptions());
    addressRow.addView(aa);
    addressRow.addView(address, new LinearLayout.LayoutParams(0, dp(44), 1));
    addressRow.addView(
        icon(
            "reload",
            () -> {
              if (current().web != null && !current().home) current().web.reload();
            }));
    if (wide) {
      LinearLayout nav = row();
      nav.addView(
          icon(
              "sidebar",
              () -> {
                sidebarShown = !sidebarShown;
                buildUi();
              }));
      nav.addView(icon("back", () -> goBack()));
      nav.addView(icon("forward", () -> goForward()));
      nav.addView(icon("home", () -> home()));
      Space spacer = new Space(this);
      nav.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
      nav.addView(addressRow, new LinearLayout.LayoutParams(0, dp(44), desktopWidth ? 3.7f : 5));
      nav.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
      nav.addView(icon("shield", () -> showShield()));
      nav.addView(icon("download", () -> showDownloads()));
      nav.addView(icon("share", () -> share()));
      nav.addView(icon("plus", () -> newTab("")));
      nav.addView(icon("more", () -> menu()));
      toolbar.addView(nav, lp(-1, 48));
    } else {
      addressRow.removeView(aa);
      mobilePage = icon("page", () -> pageOptions());
      mobilePage.setLayoutParams(lp(36, 50));
      addressRow.addView(mobilePage, 0);
      mobileReload = addressRow.getChildAt(addressRow.getChildCount() - 1);
      mobileReload.setLayoutParams(lp(36, 50));
      addressRow.setClipToOutline(false);
      addressRow.setBackground(null);
      addressRow.setElevation(0);
      address.setTextSize(16);
      address.setHint("Search or enter website");
      address.setPadding(0, 0, 0, 0);
      address.setGravity(Gravity.CENTER);
      addressRow.setPadding(dp(4), 0, dp(4), 0);
      toolbar.setPadding(dp(12), dp(8), dp(12), dp(10));
      toolbar.setClipChildren(false);
      toolbar.setClipToPadding(false);
      mobileChromeRow = row();
      mobileChromeRow.setGravity(Gravity.CENTER);
      mobileBack = new Glass.Capsule(this, dark, dp(28));
      Glass.Icon back = icon("back", () -> goBack());
      back.setOnLongClickListener(
          v -> {
            goForward();
            return true;
          });
      mobileBack.addView(back, new FrameLayout.LayoutParams(-1, -1));
      mobileChromeRow.addView(mobileBack, lp(48, 50));
      mobileAddress = new Glass.Capsule(this, dark, dp(28));
      mobileAddress.addView(addressRow, new FrameLayout.LayoutParams(-1, -1));
      mobileLoadLine = new View(this);
      mobileLoadLine.setBackgroundColor(accent);
      mobileLoadLine.setPivotX(0);
      mobileLoadLine.setScaleX(0);
      mobileAddress.addView(
          mobileLoadLine, new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM));
      mobileReload.setOnClickListener(
          v -> {
            if (current().web != null && !current().home) {
              if (progress.getProgress() < 100) current().web.stopLoading();
              else current().web.reload();
            }
          });
      LinearLayout.LayoutParams center = new LinearLayout.LayoutParams(0, dp(50), 1);
      center.setMargins(dp(9), 0, dp(9), 0);
      mobileChromeRow.addView(mobileAddress, center);
      mobileMore = new Glass.Capsule(this, dark, dp(28));
      Glass.Icon more = icon("more", () -> menu());
      more.setContentDescription("More actions. Long press for tabs");
      more.setOnLongClickListener(
          v -> {
            showDeck();
            return true;
          });
      mobileMore.addView(more, new FrameLayout.LayoutParams(-1, -1));
      mobileChromeRow.addView(mobileMore, lp(48, 50));
      toolbar.addView(mobileChromeRow, lp(-1, 52));
      final float[] down = new float[2];
      address.setOnTouchListener(
          (v, event) -> {
            if (address.hasFocus()) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
              down[0] = event.getX();
              down[1] = event.getY();
              return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
              float dx = event.getX() - down[0], dy = event.getY() - down[1];
              if (Math.abs(dx) > dp(50) && Math.abs(dx) > Math.abs(dy)) {
                int next = active + (dx < 0 ? 1 : -1);
                if (next >= 0 && next < tabs.size()) showTab(next);
              } else if (dy < -dp(40)) {
                showDeck();
              } else {
                setChromeCollapsed(false);
                address.requestFocus();
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
              }
              return true;
            }
            return true;
          });
    }
  }

  void addNav(LinearLayout row, View v) {
    v.setLayoutParams(new LinearLayout.LayoutParams(0, dp(49), 1));
    row.addView(v);
  }

  void setChromeCollapsed(boolean collapsed) {
    if (wide || mobileAddress == null || collapsed == chromeCollapsed) return;
    if (collapsed && address.hasFocus()) return;
    if (chromeMorph != null) chromeMorph.cancel();
    chromeCollapsed = collapsed;
    chromeAnimating = true;
    mobileBack.setVisibility(collapsed ? View.GONE : View.VISIBLE);
    mobileMore.setVisibility(collapsed ? View.GONE : View.VISIBLE);
    mobilePage.setVisibility(collapsed ? View.GONE : View.VISIBLE);
    mobileReload.setVisibility(collapsed ? View.GONE : View.VISIBLE);
    LinearLayout.LayoutParams pill =
        new LinearLayout.LayoutParams(
            collapsed ? dp(184) : 0, dp(collapsed ? 30 : 50), collapsed ? 0 : 1);
    pill.setMargins(dp(9), 0, dp(9), 0);
    mobileAddress.setLayoutParams(pill);
    mobileChromeRow.setLayoutParams(lp(-1, collapsed ? 34 : 52));
    address.setTextSize(collapsed ? 12 : 16);
    int from = toolbar.getHeight(), to = dp(collapsed ? 52 : 70);
    android.animation.ValueAnimator morph =
        android.animation.ValueAnimator.ofInt(from > 0 ? from : to, to);
    morph.setDuration(210);
    morph.setInterpolator(new android.view.animation.PathInterpolator(.2f, .75f, .2f, 1));
    morph.addUpdateListener(
        a -> {
          android.view.ViewGroup.LayoutParams p = toolbar.getLayoutParams();
          p.height = (Integer) a.getAnimatedValue();
          toolbar.setLayoutParams(p);
        });
    morph.addListener(
        new android.animation.AnimatorListenerAdapter() {
          public void onAnimationEnd(android.animation.Animator a) {
            chromeAnimating = false;
          }
        });
    chromeMorph = morph;
    morph.start();
  }

  void updateGlass() {
    if (wide || mobileAddress == null || isFinishing()) return;
    lastGlassSample = android.os.SystemClock.uptimeMillis();
    Bitmap next = null;
    Tab t = current();
    if (!t.home && t.web != null && t.web.getWidth() > 0 && t.web.getHeight() > 0) {
      try {
        int sourceHeight = Math.min(t.web.getHeight(), dp(120));
        int width = Build.VERSION.SDK_INT >= 31 ? 64 : 12;
        int height = Math.max(8, Math.round(sourceHeight * width / (float) t.web.getWidth()));
        next = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(next);
        c.scale(width / (float) t.web.getWidth(), height / (float) sourceHeight);
        c.translate(0, -(t.web.getHeight() - sourceHeight));
        t.web.draw(c);
      } catch (RuntimeException | OutOfMemoryError ignored) {
      }
    }
    if (pearlBackground != null)
      pearlBackground.setDockBackdrop(next, toolbar.getHeight() + root.getPaddingBottom());
    if (Build.VERSION.SDK_INT >= 30) {
      boolean light = !dark;
      if (next != null) {
        int pixel = next.getPixel(next.getWidth() / 2, next.getHeight() / 2);
        light = Color.luminance(pixel) > .42f;
      }
      getWindow()
          .getInsetsController()
          .setSystemBarsAppearance(
              light ? WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS : 0,
              WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
    mobileAddress.setSample(next);
    mobileBack.setSample(next);
    mobileMore.setSample(next);
    Bitmap old = glassSample;
    glassSample = next;
    if (old != null && !old.isRecycled()) old.recycle();
  }

  LinearLayout sheetPanel(String title, Dialog dialog) {
    LinearLayout panel = vertical();
    panel.setPadding(dp(10), dp(9), dp(10), dp(12));
    glass(panel, 30);
    View grab = new View(this);
    GradientDrawable grip = new GradientDrawable();
    grip.setColor(dark ? 0xFF62626A : 0xFFC9C9D0);
    grip.setCornerRadius(dp(3));
    grab.setBackground(grip);
    LinearLayout.LayoutParams gp = lp(34, 4);
    gp.gravity = Gravity.CENTER_HORIZONTAL;
    gp.bottomMargin = dp(6);
    panel.addView(grab, gp);
    LinearLayout heading = row();
    heading.setPadding(dp(10), 0, 0, 0);
    TextView name = label(title, 18);
    bold(name);
    name.setSingleLine();
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    heading.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
    heading.addView(icon("close", () -> dialog.dismiss()));
    panel.addView(heading);
    return panel;
  }

  void presentSheet(Dialog dialog, LinearLayout panel) {
    hideKeyboard();
    if (chromeSheet != null) chromeSheet.dismiss();
    chromeSheet = dialog;
    FrameLayout margin = new FrameLayout(this);
    FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2);
    pp.setMargins(dp(10), dp(8), dp(10), dp(12));
    Bitmap sheetSample = captureGlassBackdrop();
    Glass.Capsule frosted = new Glass.Capsule(this, dark, dp(30));
    frosted.setSample(sheetSample);
    panel.setBackground(null);
    panel.setClipToOutline(false);
    panel.setElevation(0);
    frosted.addView(panel, new FrameLayout.LayoutParams(-1, -2));
    margin.addView(frosted, pp);
    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setFillViewport(false);
    scroll.addView(margin);
    dialog.setContentView(scroll);
    Window win = dialog.getWindow();
    win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    win.setDimAmount(.16f);
    win.setGravity(Gravity.BOTTOM);
    dialog.setOnDismissListener(
        d -> {
          if (chromeSheet == dialog) chromeSheet = null;
          frosted.setSample(null);
          if (sheetSample != null && !sheetSample.isRecycled()) sheetSample.recycle();
        });
    dialog.show();
    win.setLayout(-1, -2);
    scroll.post(
        () -> {
          int max = dp((int) (getResources().getConfiguration().screenHeightDp * .78f));
          if (scroll.getHeight() > max) win.setLayout(-1, max);
        });
    panel.setTranslationY(dp(28));
    panel.setAlpha(0);
    panel
        .animate()
        .translationY(0)
        .alpha(1)
        .setDuration(230)
        .setInterpolator(new android.view.animation.DecelerateInterpolator())
        .start();
  }

  void sheetDivider(LinearLayout panel) {
    View line = new View(this);
    line.setBackgroundColor(dark ? 0x335C5C68 : 0x22757582);
    LinearLayout.LayoutParams p = lp(-1, 1);
    p.setMargins(dp(12), dp(5), dp(12), dp(5));
    panel.addView(line, p);
  }

  void sheetAction(LinearLayout panel, Dialog dialog, String glyph, String title, Runnable action) {
    LinearLayout r = row();
    r.setPadding(dp(8), 0, dp(12), 0);
    Glass.Icon symbol = new Glass.Icon(this, glyph, ink);
    r.addView(symbol, lp(38, 48));
    TextView name = label(title, 16);
    r.addView(name, new LinearLayout.LayoutParams(0, dp(48), 1));
    r.setContentDescription(title);
    r.setFocusable(true);
    r.setOnClickListener(
        v -> {
          dialog.dismiss();
          action.run();
        });
    r.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x18757585), null, null));
    panel.addView(r, lp(-1, 48));
  }

  void showMobileMenu() {
    Dialog dialog = new Dialog(this);
    LinearLayout panel =
        sheetPanel(current().home ? "Start Page" : Blocker.host(current().url), dialog);
    LinearLayout quick = row();
    String[] names = {"Share", "Bookmarks", "Tabs"};
    String[] glyphs = {"share", "bookmarks", "tabs"};
    Runnable[] actions = {() -> share(), () -> showLibrary(false), () -> showDeck()};
    for (int i = 0; i < 3; i++) {
      final Runnable action = actions[i];
      LinearLayout cell = vertical();
      cell.setGravity(Gravity.CENTER);
      glass(cell, 20);
      Glass.Icon symbol = new Glass.Icon(this, glyphs[i], ink);
      cell.addView(symbol, lp(38, 38));
      TextView name = text(names[i], 12, ink);
      name.setGravity(Gravity.CENTER);
      cell.addView(name, lp(-1, 22));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(78), 1);
      cp.setMargins(dp(4), dp(7), dp(4), dp(8));
      quick.addView(cell, cp);
      cell.setOnClickListener(
          v -> {
            dialog.dismiss();
            action.run();
          });
    }
    panel.addView(quick);
    sheetDivider(panel);
    sheetAction(panel, dialog, "plus", "New Tab", () -> newTab(""));
    sheetAction(panel, dialog, "tabs", "Show All " + tabs.size() + " Tabs", () -> showDeck());
    sheetAction(panel, dialog, "forward", "Forward", () -> goForward());
    sheetAction(panel, dialog, "download", "Downloads", () -> showDownloads());
    sheetAction(panel, dialog, "play", "Download Detected Video", () -> scanMedia());
    sheetDivider(panel);
    sheetAction(panel, dialog, "bookmarks", "Add Bookmark", () -> addBookmark());
    sheetAction(panel, dialog, "reload", "History", () -> showLibrary(true));
    sheetAction(panel, dialog, "shield", "Privacy Report", () -> showShield());
    sheetAction(panel, dialog, "more", "Settings", () -> settings());
    presentSheet(dialog, panel);
  }

  void showPageSheet() {
    Dialog dialog = new Dialog(this);
    LinearLayout panel =
        sheetPanel(current().home ? "Page Menu" : Blocker.host(current().url), dialog);
    LinearLayout sizes = row();
    sizes.setGravity(Gravity.CENTER);
    TextView value = label(prefs.getInt("zoom", 100) + "%", 18);
    value.setGravity(Gravity.CENTER);
    TextView smaller =
        action(
            "A",
            () -> {
              int z = Math.max(75, prefs.getInt("zoom", 100) - 10);
              prefs.edit().putInt("zoom", z).apply();
              if (current().web != null) current().web.getSettings().setTextZoom(z);
              value.setText(z + "%");
            });
    TextView larger =
        action(
            "A",
            () -> {
              int z = Math.min(150, prefs.getInt("zoom", 100) + 10);
              prefs.edit().putInt("zoom", z).apply();
              if (current().web != null) current().web.getSettings().setTextZoom(z);
              value.setText(z + "%");
            });
    larger.setTextSize(24);
    sizes.addView(smaller, lp(60, 54));
    sizes.addView(value, new LinearLayout.LayoutParams(0, dp(54), 1));
    sizes.addView(larger, lp(60, 54));
    panel.addView(sizes);
    sheetDivider(panel);
    sheetAction(panel, dialog, "search", "Find on Page", () -> findOnPage());
    sheetAction(
        panel,
        dialog,
        "page",
        current().desktop ? "Request Mobile Website" : "Request Desktop Website",
        () -> {
          current().desktop = !current().desktop;
          applyAgent(current());
          if (current().web != null) current().web.reload();
        });
    sheetAction(panel, dialog, "shield", "Privacy Report", () -> showShield());
    sheetAction(
        panel,
        dialog,
        "share",
        "Open in Another App",
        () -> {
          if (!current().home) openExternal(current().url);
        });
    sheetAction(panel, dialog, "play", "Download Detected Video", () -> scanMedia());
    sheetAction(panel, dialog, "more", "More Actions", () -> showMobileMenu());
    presentSheet(dialog, panel);
  }

  LinearLayout createSidebar() {
    LinearLayout s = vertical();
    s.setPadding(dp(20), dp(24), dp(16), dp(12));
    s.setBackgroundColor(dark ? 0x661E1A28 : 0x55FFFFFF);
    TextView title = label("Aura Browser", 24);
    bold(title);
    s.addView(title, lp(-1, 52));
    TextView desc = text("Your window to the web", 12, muted);
    s.addView(desc, lp(-1, 34));
    s.addView(action("Start Page", () -> home()));
    s.addView(action("Bookmarks", () -> showLibrary(false)));
    s.addView(action("History", () -> showLibrary(true)));
    s.addView(action("Downloads", () -> showDownloads()));
    s.addView(action("Detected video", () -> scanMedia()));
    s.addView(action("Privacy & blocking", () -> showShield()));
    s.addView(new Space(this), new LinearLayout.LayoutParams(1, 0, 1));
    s.addView(action("Settings", () -> settings()));
    return s;
  }

  void refreshTabStrip() {
    if (tabStrip == null) return;
    tabStrip.removeAllViews();
    for (int i = 0; i < tabs.size(); i++) {
      final int at = i;
      Tab t = tabs.get(i);
      LinearLayout item = row();
      item.setPadding(dp(12), 0, dp(2), 0);
      if (i == active) glass(item, 10);
      TextView title = label(t.home ? "Start Page" : t.title, 13);
      title.setSingleLine();
      title.setEllipsize(android.text.TextUtils.TruncateAt.END);
      item.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
      Glass.Icon x = icon("close", () -> closeTab(at));
      x.setLayoutParams(lp(34, 34));
      item.addView(x);
      item.setOnClickListener(v -> showTab(at));
      LinearLayout.LayoutParams p = lp(190, 38);
      p.setMargins(dp(3), 0, dp(3), 0);
      tabStrip.addView(item, p);
    }
    tabStrip.addView(icon("plus", () -> newTab("")));
  }

  void showTab(int index) {
    if (index < 0 || index >= tabs.size()) return;
    if (viewport == null) return;
    if (!tabs.isEmpty() && active < tabs.size()) snapshot(current());
    active = index;
    Tab t = current();
    viewport.removeAllViews();
    if (t.home) {
      viewport.addView(startPage(), new FrameLayout.LayoutParams(-1, -1));
    } else {
      ensureWeb(t);
      if (t.web.getParent() != null) ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
      viewport.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
      t.web.onResume();
    }
    updateAddress();
    refreshTabStrip();
    saveTabs();
    setChromeCollapsed(false);
    if (pearlBackground != null) pearlBackground.setPageMode(!t.home);
    handler.postDelayed(() -> updateGlass(), 120);
    suspendBackgroundTabs();
  }

  void updateAddress() {
    if (address == null || address.hasFocus()) return;
    Tab t = current();
    if (pearlBackground != null) pearlBackground.setPageMode(!t.home);
    address.setText(t.home ? "" : Blocker.host(t.url));
    address.setGravity(Gravity.CENTER);
    if (tabCount != null) tabCount.setText("" + tabs.size());
    if (mobileBack != null) mobileBack.setAlpha(t.home ? .4f : 1f);
  }

  void newTab(String url) {
    if (tabs.size() >= 40) {
      toast("Close a tab before adding more");
      return;
    }
    snapshot(current());
    Tab t = new Tab();
    tabs.add(t);
    active = tabs.size() - 1;
    showTab(active);
    if (!url.isEmpty()) navigate(url);
  }

  void closeTab(int at) {
    if (at < 0 || at >= tabs.size()) return;
    Tab t = tabs.remove(at);
    if (t.web != null) {
      if (t.web.getParent() != null) ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
      t.web.destroy();
    }
    if (t.preview != null) t.preview.recycle();
    if (tabs.isEmpty()) tabs.add(new Tab());
    if (at < active) active--;
    active = Math.max(0, Math.min(active, tabs.size() - 1));
    if (deckOverlay == null) showTab(active);
    else {
      saveTabs();
      refreshTabStrip();
      updateAddress();
    }
  }

  void home() {
    Tab t = current();
    t.home = true;
    t.url = "";
    t.title = "Start Page";
    t.media.clear();
    if (t.web != null) t.web.stopLoading();
    showTab(active);
  }

  View startPage() {
    ScrollView sc = new ScrollView(this);
    sc.setFillViewport(true);
    sc.setVerticalScrollBarEnabled(false);
    LinearLayout outer = vertical();
    outer.setGravity(Gravity.CENTER_HORIZONTAL);
    outer.setPadding(dp(wide ? 44 : 24), dp(wide ? 45 : 48), dp(wide ? 44 : 24), dp(20));
    sc.addView(outer);
    LinearLayout content = vertical();
    content.setGravity(Gravity.CENTER_HORIZONTAL);
    outer.addView(
        content, new LinearLayout.LayoutParams(wide ? dp(desktopWidth ? 700 : 520) : -1, -2));
    if (wide) {
      TextView brand = text("AURA BROWSER", 12, accent);
      brand.setLetterSpacing(.3f);
      bold(brand);
      content.addView(brand, lp(-2, 25));
      TextView heading = label("A little more possibility.", wide ? 36 : 29);
      bold(heading);
      heading.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams hp = lp(-1, wide ? 70 : 88);
      content.addView(heading, hp);
      TextView sub = text("A clearer view of your everyday web.", 14, muted);
      sub.setGravity(Gravity.CENTER);
      content.addView(sub, lp(-1, 30));
    }
    LinearLayout favorites = vertical();
    LinearLayout.LayoutParams fp = lp(-1, -2);
    fp.setMargins(0, dp(wide ? 28 : 0), 0, dp(22));
    content.addView(favorites, fp);
    LinearLayout top = row();
    TextView ft = label("Favourites", wide ? 24 : 25);
    bold(ft);
    top.addView(ft, new LinearLayout.LayoutParams(0, dp(36), 1));
    top.addView(action("Edit", () -> showLibrary(false)));
    favorites.addView(top);
    String[][] sites = {
      {"Google", "https://www.google.com", "G", "#8CA2F5"},
      {"YouTube", "https://www.youtube.com", "▶", "#EC8D9A"},
      {"Wikipedia", "https://www.wikipedia.org", "W", "#A7A1BC"},
      {"GitHub", "https://github.com", "⌘", "#8F84BE"},
      {"Play Console", "https://play.google.com/console", "▷", "#87BEB4"},
      {"Reddit", "https://www.reddit.com", "r", "#E5AC87"},
      {"BBC", "https://www.bbc.com", "B", "#9FA4C8"},
      {"Add favourite", "", "+", "#B8A7D8"}
    };
    for (int row = 0; row < 2; row++) {
      LinearLayout grid = row();
      grid.setGravity(Gravity.TOP);
      for (int col = 0; col < 4; col++) {
        final String[] site = sites[row * 4 + col];
        LinearLayout tile = vertical();
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        View mark = new Glass.Favorite(this, site[0], dark);
        tile.addView(mark, lp(wide ? 66 : 60, wide ? 66 : 60));
        TextView name = text(site[0], 12, ink);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        tile.addView(name, lp(-1, 40));
        tile.setOnClickListener(
            v -> {
              if (site[1].isEmpty()) addFavoriteUrl();
              else navigate(site[1]);
            });
        grid.addView(tile, new LinearLayout.LayoutParams(0, dp(wide ? 120 : 110), 1));
      }
      favorites.addView(grid);
    }
    if (!wide) {
      TextView report = label("Privacy Report", 19);
      bold(report);
      content.addView(report, lp(-1, 42));
    }
    LinearLayout privacy = row();
    glass(privacy, 22);
    privacy.setPadding(dp(16), dp(14), dp(16), dp(14));
    Glass.Icon shield = icon("shield", () -> showShield());
    shield.color = accent;
    privacy.addView(shield);
    LinearLayout info = vertical();
    TextView pt =
        label(wide ? "Privacy, built in." : blocker.blocked.get() + " requests blocked", 16);
    bold(pt);
    info.addView(pt);
    TextView pd = text("Ads, trackers and unwanted pop-ups blocked.", 12, muted);
    pd.setMaxLines(2);
    info.addView(pd);
    privacy.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    privacy.setOnClickListener(v -> showShield());
    content.addView(privacy, lp(-1, -2));
    LinearLayout utilities = row();
    utilities.setGravity(Gravity.CENTER);
    if (wide) {
      utilities.addView(action("Downloads", () -> showDownloads()));
      utilities.addView(action("History", () -> showLibrary(true)));
      utilities.addView(action("Settings", () -> settings()));
    } else
      utilities.addView(
          action(
              "Edit Start Page",
              () ->
                  new GlassDialog.Builder(this)
                      .setTitle("Start Page")
                      .setItems(
                          new String[] {"Add favourite", "Bookmarks", "Settings"},
                          (d, i) -> {
                            if (i == 0) addFavoriteUrl();
                            else if (i == 1) showLibrary(false);
                            else settings();
                          })
                      .show()));
    LinearLayout.LayoutParams up = lp(-1, 60);
    up.topMargin = dp(15);
    content.addView(utilities, up);
    return sc;
  }

  void ensureWeb(Tab t) {
    if (t.web != null) return;
    WebView w = new WebView(this);
    t.web = w;
    w.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
    w.setVerticalScrollBarEnabled(false);
    w.setHorizontalScrollBarEnabled(false);
    WebSettings s = w.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setAllowContentAccess(true);
    s.setAllowFileAccess(false);
    s.setSupportZoom(true);
    s.setBuiltInZoomControls(true);
    s.setDisplayZoomControls(false);
    s.setUseWideViewPort(true);
    s.setLoadWithOverviewMode(true);
    s.setMediaPlaybackRequiresUserGesture(true);
    s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    s.setSafeBrowsingEnabled(true);
    s.setSupportMultipleWindows(true);
    s.setJavaScriptCanOpenWindowsAutomatically(false);
    s.setTextZoom(prefs.getInt("zoom", 100));
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance()
        .setAcceptThirdPartyCookies(w, prefs.getBoolean("thirdCookies", false));
    applyAgent(t);
    if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(dark);
    w.setOnScrollChangeListener(
        (view, x, y, oldX, oldY) -> {
          if (t != current() || wide) return;
          if (!address.hasFocus() && !chromeAnimating) {
            if (y > dp(90) && y - oldY > dp(9)) setChromeCollapsed(true);
            else if (oldY - y > dp(7) || y < dp(15)) setChromeCollapsed(false);
          }
          if (android.os.SystemClock.uptimeMillis() - lastGlassSample > 180) updateGlass();
        });
    w.setWebViewClient(
        new WebViewClient() {
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
            String url = r.getUrl().toString();
            String scheme = n(r.getUrl().getScheme());
            if (scheme.equals("http") || scheme.equals("https")) return false;
            if (r.isForMainFrame() && r.hasGesture()) openExternal(url);
            return true;
          }

          public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest r) {
            String u = r.getUrl().toString();
            if (!r.isForMainFrame() && blocker.blocks(u, t.url))
              return new WebResourceResponse(
                  "text/plain",
                  "UTF-8",
                  204,
                  "Blocked",
                  Collections.emptyMap(),
                  new ByteArrayInputStream(new byte[0]));
            if (isMedia(u)) handler.post(() -> recordMedia(t, u));
            return null;
          }

          public void onPageStarted(WebView view, String url, Bitmap favicon) {
            t.home = false;
            t.url = url;
            t.media.clear();
            if (t == current()) {
              progress.setVisibility(View.VISIBLE);
              updateAddress();
            }
            if (sitePermission != null) {
              sitePermission.deny();
              sitePermission = null;
            }
          }

          public void onPageFinished(WebView view, String url) {
            t.url = url;
            if (t == current()) {
              progress.setVisibility(View.INVISIBLE);
              updateAddress();
            }
            if (blocker.enabled && !blocker.allowed.contains(Blocker.host(url)))
              view.evaluateJavascript(Blocker.COSMETIC, null);
            saveHistory(t);
            saveTabs();
            handler.postDelayed(
                () -> {
                  snapshot(t);
                  if (t == current()) updateGlass();
                },
                250);
          }

          public void onReceivedError(WebView view, WebResourceRequest r, WebResourceError e) {
            if (r.isForMainFrame() && t == current()) {
              progress.setVisibility(View.INVISIBLE);
              toast("Page could not load: " + e.getDescription());
            }
          }

          public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (view.getParent() != null)
              ((android.view.ViewGroup) view.getParent()).removeView(view);
            view.destroy();
            t.web = null;
            t.state = null;
            if (t == current()) {
              showTab(active);
              toast("This tab was restarted after the web renderer stopped");
            }
            return true;
          }
        });
    w.setWebChromeClient(
        new WebChromeClient() {
          public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            new GlassDialog.Builder(BrowserActivity.this)
                .setTitle(Blocker.host(url))
                .setMessage(message)
                .setPositiveButton("OK", (d, i) -> result.confirm())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            new GlassDialog.Builder(BrowserActivity.this)
                .setTitle(Blocker.host(url))
                .setMessage(message)
                .setPositiveButton("OK", (d, i) -> result.confirm())
                .setNegativeButton("Cancel", (d, i) -> result.cancel())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          public boolean onJsPrompt(
              WebView view, String url, String message, String value, JsPromptResult result) {
            EditText input = new EditText(BrowserActivity.this);
            input.setText(value);
            new GlassDialog.Builder(BrowserActivity.this)
                .setTitle(Blocker.host(url))
                .setMessage(message)
                .setView(input)
                .setPositiveButton("OK", (d, i) -> result.confirm(input.getText().toString()))
                .setNegativeButton("Cancel", (d, i) -> result.cancel())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          public boolean onJsBeforeUnload(
              WebView view, String url, String message, JsResult result) {
            new GlassDialog.Builder(BrowserActivity.this)
                .setTitle("Leave this page?")
                .setMessage(message)
                .setPositiveButton("Leave", (d, i) -> result.confirm())
                .setNegativeButton("Stay", (d, i) -> result.cancel())
                .setOnCancelListener(d -> result.cancel())
                .show();
            return true;
          }

          public void onProgressChanged(WebView v, int value) {
            if (t == current()) {
              progress.setProgress(value);
              if (!wide && mobileLoadLine != null) {
                mobileLoadLine.setScaleX(value / 100f);
                mobileLoadLine.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE);
                if (mobileReload instanceof Glass.Icon) {
                  ((Glass.Icon) mobileReload).name = value >= 100 ? "reload" : "close";
                  mobileReload.invalidate();
                }
              }
              progress.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
          }

          public void onReceivedTitle(WebView v, String title) {
            t.title = title == null ? Blocker.host(t.url) : title;
            refreshTabStrip();
          }

          public boolean onCreateWindow(
              WebView view, boolean dialog, boolean gesture, Message message) {
            if (!gesture || prefs.getBoolean("strictPopups", true)) {
              blocker.blocked.incrementAndGet();
              if (gesture)
                toast("Pop-up blocked. Allow tapped pop-ups in Privacy settings if needed.");
              return false;
            }
            new GlassDialog.Builder(BrowserActivity.this)
                .setTitle("Open a new tab?")
                .setMessage("This website requested a new window.")
                .setNegativeButton("Block", (d, x) -> {})
                .setPositiveButton(
                    "Open",
                    (d, x) -> {
                      WebView temp = new WebView(BrowserActivity.this);
                      temp.setWebViewClient(
                          new WebViewClient() {
                            public boolean shouldOverrideUrlLoading(
                                WebView v, WebResourceRequest r) {
                              String u = r.getUrl().toString();
                              if (u.startsWith("https://") || u.startsWith("http://")) newTab(u);
                              temp.destroy();
                              return true;
                            }
                          });
                      ((WebView.WebViewTransport) message.obj).setWebView(temp);
                      message.sendToTarget();
                    })
                .show();
            return true;
          }

          public boolean onShowFileChooser(
              WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            cancelFile();
            fileCallback = callback;
            fileParams = params;
            String types = String.join(",", params.getAcceptTypes());
            if (types.contains("image") || params.isCaptureEnabled()) {
              new GlassDialog.Builder(BrowserActivity.this)
                  .setTitle("Upload to " + Blocker.host(t.url))
                  .setItems(
                      new String[] {
                        "Choose files or photos",
                        types.contains("video") && !types.contains("image")
                            ? "Record a video"
                            : "Take a photo"
                      },
                      (d, i) -> {
                        if (i == 0) chooseFiles();
                        else camera();
                      })
                  .setOnCancelListener(d -> cancelFile())
                  .show();
            } else chooseFiles();
            return true;
          }

          public void onPermissionRequest(PermissionRequest r) {
            runOnUiThread(() -> requestSitePermission(r));
          }

          public void onPermissionRequestCanceled(PermissionRequest r) {
            if (sitePermission == r) sitePermission = null;
          }

          public void onShowCustomView(View v, CustomViewCallback callback) {
            if (fullscreenView != null) {
              callback.onCustomViewHidden();
              return;
            }
            fullscreenView = v;
            fullscreenCallback = callback;
            fullScreen = new FrameLayout(BrowserActivity.this);
            fullScreen.setBackgroundColor(Color.BLACK);
            fullScreen.addView(v, new FrameLayout.LayoutParams(-1, -1));
            root.addView(fullScreen, new FrameLayout.LayoutParams(-1, -1));
            if (Build.VERSION.SDK_INT >= 30)
              getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
          }

          public void onHideCustomView() {
            exitFullscreen();
          }
        });
    w.setDownloadListener(
        (url, agent, disposition, mime, length) ->
            confirmDownload(
                url, URLUtil.guessFileName(url, disposition, mime), mime, agent, t.url));
    w.setOnLongClickListener(
        v -> {
          WebView.HitTestResult hit = w.getHitTestResult();
          if (hit == null || hit.getExtra() == null) return false;
          String u = hit.getExtra();
          if (!u.startsWith("http")) return false;
          new GlassDialog.Builder(this)
              .setTitle(u)
              .setItems(
                  new String[] {
                    "Open in new tab", "Open in another app", "Download link", "Copy link"
                  },
                  (d, i) -> {
                    if (i == 0) newTab(u);
                    if (i == 1) openExternal(u);
                    if (i == 2)
                      confirmDownload(
                          u,
                          URLUtil.guessFileName(u, null, null),
                          "",
                          w.getSettings().getUserAgentString(),
                          t.url);
                    if (i == 3) {
                      ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                          .setPrimaryClip(ClipData.newPlainText("Link", u));
                      toast("Link copied");
                    }
                  })
              .show();
          return true;
        });
    if (t.state != null) {
      w.restoreState(t.state);
      t.state = null;
    } else if (!t.url.isEmpty()) w.loadUrl(t.url);
  }

  void applyAgent(Tab t) {
    if (t.web == null) return;
    WebSettings s = t.web.getSettings();
    if (t.desktop || wide)
      s.setUserAgentString(
          "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0"
              + " Safari/537.36");
    else s.setUserAgentString(WebSettings.getDefaultUserAgent(this));
  }

  void navigate(String input) {
    String u = input.trim();
    if (u.isEmpty()) return;
    if (!u.matches("(?i)^https?://.*")) {
      if (u.contains(".") && !u.contains(" ") && !u.contains(":")) u = "https://" + u;
      else u = "https://www.google.com/search?q=" + Uri.encode(u);
    }
    if (!u.startsWith("https://") && !u.startsWith("http://")) {
      toast("Enter a website or search term");
      return;
    }
    Tab t = current();
    t.home = false;
    t.url = u;
    t.media.clear();
    boolean existed = t.web != null;
    ensureWeb(t);
    viewport.removeAllViews();
    if (t.web.getParent() != null) ((android.view.ViewGroup) t.web.getParent()).removeView(t.web);
    viewport.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
    if (existed) t.web.loadUrl(u);
    updateAddress();
    saveTabs();
  }

  void goBack() {
    if (deckOverlay != null) {
      hideDeck();
      return;
    }
    Tab t = current();
    if (t.web != null && !t.home && t.web.canGoBack()) t.web.goBack();
    else home();
  }

  void goForward() {
    Tab t = current();
    if (t.web != null && t.web.canGoForward()) t.web.goForward();
  }

  void snapshot(Tab t) {
    if (t.web == null || t.home || t.web.getWidth() == 0 || t.web.getHeight() == 0) return;
    try {
      int width = 360,
          height =
              Math.max(200, Math.min(640, (int) (360f * t.web.getHeight() / t.web.getWidth())));
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
      Canvas canvas = new Canvas(bitmap);
      canvas.scale(width / (float) t.web.getWidth(), height / (float) t.web.getHeight());
      t.web.draw(canvas);
      Bitmap old = t.preview;
      t.preview = bitmap;
      if (old != null && !old.isRecycled()) old.recycle();
    } catch (OutOfMemoryError ignored) {
    }
  }

  void suspendBackgroundTabs() {
    int live = 0;
    for (Tab t : tabs) if (t.web != null) live++;
    if (live <= 4) return;
    for (Tab t : tabs)
      if (t != current() && t.web != null && live > 4) {
        snapshot(t);
        t.state = new Bundle();
        t.web.saveState(t.state);
        t.web.destroy();
        t.web = null;
        live--;
      }
  }

  void showDeck() {
    snapshot(current());
    if (deckOverlay != null) return;
    hideKeyboard();
    deckOverlay = new FrameLayout(this);
    deckOverlay.addView(new Glass.Pearl(this, dark), new FrameLayout.LayoutParams(-1, -1));
    LinearLayout pane = vertical();
    pane.setPadding(dp(15), dp(8), dp(15), dp(6));
    LinearLayout header = row();
    TextView count = label(tabs.size() + " Tabs", 27);
    bold(count);
    header.addView(count, new LinearLayout.LayoutParams(0, dp(56), 1));
    header.addView(action("Done", () -> hideDeck()));
    pane.addView(header);
    TabDeck deck =
        new TabDeck(
            this,
            tabs,
            active,
            dark,
            new TabDeck.Listener() {
              public void select(int i) {
                hideDeck();
                showTab(i);
              }

              public void close(int i) {
                closeTab(i);
                count.setText(tabs.size() + " Tabs");
              }
            });
    pane.addView(deck, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout bottom = row();
    bottom.setGravity(Gravity.CENTER);
    bottom.addView(
        action(
            "+  New Tab",
            () -> {
              hideDeck();
              newTab("");
            }));
    bottom.addView(
        action(
            "Close All",
            () ->
                new GlassDialog.Builder(BrowserActivity.this)
                    .setTitle("Close all tabs?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(
                        "Close",
                        (d, i) -> {
                          hideDeck();
                          while (tabs.size() > 1) closeTab(tabs.size() - 1);
                          home();
                        })
                    .show()));
    pane.addView(bottom, lp(-1, 55));
    deckOverlay.addView(pane, new FrameLayout.LayoutParams(-1, -1));
    root.addView(deckOverlay, new FrameLayout.LayoutParams(-1, -1));
    deckOverlay.setAlpha(0);
    deckOverlay.animate().alpha(1).setDuration(180).start();
  }

  void hideDeck() {
    if (deckOverlay == null) return;
    root.removeView(deckOverlay);
    deckOverlay = null;
    showTab(active);
  }

  void hideKeyboard() {
    address.clearFocus();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(address.getWindowToken(), 0);
  }

  void pageOptions() {
    if (!wide) {
      showPageSheet();
      return;
    }
    String[] a = {
      "Reload page",
      "Request " + (current().desktop || wide ? "mobile" : "desktop") + " website",
      "Page text size",
      "Find on page",
      "Open in another app",
      "Download detected video",
      "More options"
    };
    new GlassDialog.Builder(this)
        .setTitle(current().home ? "Page controls" : Blocker.host(current().url))
        .setItems(
            a,
            (d, i) -> {
              switch (i) {
                case 0:
                  if (current().web != null) current().web.reload();
                  break;
                case 1:
                  current().desktop = !(current().desktop || wide);
                  if (wide) {
                    toast("Desktop layout uses desktop pages by default");
                  } else {
                    applyAgent(current());
                    if (current().web != null) current().web.reload();
                  }
                  break;
                case 2:
                  zoom();
                  break;
                case 3:
                  findOnPage();
                  break;
                case 4:
                  if (!current().home) openExternal(current().url);
                  break;
                case 5:
                  scanMedia();
                  break;
                default:
                  menu();
              }
            })
        .show();
  }

  void menu() {
    if (!wide) {
      showMobileMenu();
      return;
    }
    String[] a = {
      "New tab",
      "Tabs",
      "Downloads",
      "Download detected video",
      "Add bookmark",
      "Bookmarks",
      "History",
      "Privacy & blocking",
      "Settings",
      "Open in another app",
      "Find on page"
    };
    new GlassDialog.Builder(this)
        .setTitle("Aura Browser")
        .setItems(
            a,
            (d, i) -> {
              switch (i) {
                case 0:
                  newTab("");
                  break;
                case 1:
                  showDeck();
                  break;
                case 2:
                  showDownloads();
                  break;
                case 3:
                  scanMedia();
                  break;
                case 4:
                  addBookmark();
                  break;
                case 5:
                  showLibrary(false);
                  break;
                case 6:
                  showLibrary(true);
                  break;
                case 7:
                  showShield();
                  break;
                case 8:
                  settings();
                  break;
                case 9:
                  openExternal(current().url);
                  break;
                case 10:
                  findOnPage();
              }
            })
        .show();
  }

  void share() {
    if (current().home) {
      menu();
      return;
    }
    Intent i =
        new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, current().url);
    startActivity(Intent.createChooser(i, "Share page"));
  }

  void openExternal(String url) {
    if (url == null || url.isEmpty()) return;
    try {
      Intent i;
      if (url.startsWith("intent:")) {
        i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        String fallback = i.getStringExtra("browser_fallback_url");
        String targetPackage = i.getPackage();
        Uri targetData = i.getData();
        i = new Intent(Intent.ACTION_VIEW, targetData).addCategory(Intent.CATEGORY_BROWSABLE);
        if (targetPackage != null) i.setPackage(targetPackage);
        if (i.getData() == null) {
          if (fallback != null && fallback.startsWith("https://")) navigate(fallback);
          return;
        }
      } else {
        i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addCategory(Intent.CATEGORY_BROWSABLE);
      }
      String scheme = n(i.getData().getScheme()).toLowerCase(Locale.ROOT);
      if (Arrays.asList("file", "content", "javascript", "data", "about").contains(scheme)) {
        toast("This link cannot be opened externally");
        return;
      }
      final Intent target = i;
      new GlassDialog.Builder(this)
          .setTitle("Open in another app?")
          .setMessage("Choose an installed app to handle this link.")
          .setNegativeButton("Cancel", null)
          .setPositiveButton(
              "Choose app",
              (d, x) -> {
                try {
                  Intent chooser = Intent.createChooser(target, "Open with");
                  if (Build.VERSION.SDK_INT >= 24)
                    chooser.putExtra(
                        Intent.EXTRA_EXCLUDE_COMPONENTS,
                        new ComponentName[] {new ComponentName(this, BrowserActivity.class)});
                  startActivity(chooser);
                } catch (Exception e) {
                  toast("No installed app can open this link");
                }
              })
          .show();
    } catch (Exception e) {
      toast("Unable to open this link");
    }
  }

  void chooseFiles() {
    if (fileCallback == null) return;
    try {
      Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      ArrayList<String> types = new ArrayList<>();
      if (fileParams != null)
        for (String type : fileParams.getAcceptTypes()) {
          if (type == null) continue;
          for (String val : type.split(",")) {
            val = val.trim();
            if (val.startsWith(".")) {
              String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(val.substring(1));
              if (m != null) types.add(m);
            } else if (val.contains("/")) types.add(val);
          }
        }
      if (!types.isEmpty()) {
        if (types.size() == 1) i.setType(types.get(0));
        else i.putExtra(Intent.EXTRA_MIME_TYPES, types.toArray(new String[0]));
      }
      i.putExtra(
          Intent.EXTRA_ALLOW_MULTIPLE,
          fileParams != null
              && fileParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
      i.addFlags(
          Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      startActivityForResult(i, FILES);
    } catch (Exception e) {
      toast("No file picker is available");
      cancelFile();
    }
  }

  void camera() {
    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
      return;
    }
    try {
      String types = fileParams == null ? "" : String.join(",", fileParams.getAcceptTypes());
      if (types.contains("video") && !types.contains("image")) {
        cameraUri = null;
        startActivityForResult(new Intent(MediaStore.ACTION_VIDEO_CAPTURE), CAMERA);
        return;
      }
      File capture = new File(getCacheDir(), "capture-" + System.currentTimeMillis() + ".jpg");
      capture.createNewFile();
      cameraUri = ShareProvider.uri(capture, this);
      Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
      i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
      i.setClipData(ClipData.newRawUri("Camera output", cameraUri));
      i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      startActivityForResult(i, CAMERA);
    } catch (Exception e) {
      toast("No camera app is available");
      cancelFile();
    }
  }

  void cancelFile() {
    if (fileCallback != null) {
      fileCallback.onReceiveValue(null);
      fileCallback = null;
    }
    fileParams = null;
    cameraUri = null;
  }

  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != FILES && request != CAMERA) return;
    if (fileCallback == null) return;
    ArrayList<Uri> picked = new ArrayList<>();
    if (result == RESULT_OK) {
      if (request == CAMERA && cameraUri != null) picked.add(cameraUri);
      else if (data != null) {
        if (data.getClipData() != null) {
          for (int i = 0; i < data.getClipData().getItemCount(); i++)
            picked.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) picked.add(data.getData());
      }
    }
    ArrayList<Uri> safe = new ArrayList<>();
    for (Uri u : picked)
      try {
        if (!"content".equals(u.getScheme())) continue;
        if ("com.gr4nt.aurabrowser.files".equals(u.getAuthority()) && !u.equals(cameraUri)) continue;
        try (android.content.res.AssetFileDescriptor fd =
            getContentResolver().openAssetFileDescriptor(u, "r")) {
          if (fd != null) safe.add(u);
        }
        if (request == FILES && data != null)
          try {
            getContentResolver()
                .takePersistableUriPermission(
                    u, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
          } catch (Exception ignored) {
          }
      } catch (Exception ignored) {
      }
    fileCallback.onReceiveValue(safe.isEmpty() ? null : safe.toArray(new Uri[0]));
    fileCallback = null;
    fileParams = null;
    cameraUri = null;
  }

  void requestSitePermission(PermissionRequest r) {
    if (sitePermission != null) sitePermission.deny();
    String origin = r.getOrigin().toString();
    if (!origin.startsWith("https://")
        || !Blocker.host(origin).equals(Blocker.host(current().url))) {
      r.deny();
      return;
    }
    sitePermission = r;
    siteResources = new ArrayList<>();
    for (String s : r.getResources())
      if (s.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
          || s.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) siteResources.add(s);
    if (siteResources.isEmpty()) {
      r.deny();
      sitePermission = null;
      return;
    }
    new GlassDialog.Builder(this)
        .setTitle("Website permission")
        .setMessage(
            Blocker.host(origin)
                + " wants to use your "
                + (siteResources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) ? "camera" : "")
                + (siteResources.size() > 1
                    ? " and microphone"
                    : siteResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                        ? "microphone"
                        : "")
                + " for this session.")
        .setNegativeButton("Block", (d, i) -> denySite())
        .setOnCancelListener(d -> denySite())
        .setPositiveButton(
            "Allow",
            (d, i) -> {
              if (sitePermission != r) return;
              ArrayList<String> permissions = new ArrayList<>();
              if (siteResources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                  && checkSelfPermission(Manifest.permission.CAMERA)
                      != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.CAMERA);
              if (siteResources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                  && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                      != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.RECORD_AUDIO);
              if (permissions.isEmpty()) grantSite();
              else requestPermissions(permissions.toArray(new String[0]), SITE_PERMISSION);
            })
        .show();
  }

  void denySite() {
    if (sitePermission != null) {
      sitePermission.deny();
      sitePermission = null;
    }
  }

  void grantSite() {
    if (sitePermission == null) return;
    if (!Blocker.host(sitePermission.getOrigin().toString()).equals(Blocker.host(current().url))) {
      denySite();
      return;
    }
    ArrayList<String> ok = new ArrayList<>();
    for (String resource : siteResources)
      if ((resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
              && checkSelfPermission(Manifest.permission.CAMERA)
                  == PackageManager.PERMISSION_GRANTED)
          || (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
              && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                  == PackageManager.PERMISSION_GRANTED)) ok.add(resource);
    if (ok.isEmpty()) sitePermission.deny();
    else sitePermission.grant(ok.toArray(new String[0]));
    sitePermission = null;
  }

  public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(request, permissions, results);
    if (request == SITE_PERMISSION) grantSite();
    if (request == CAMERA_PERMISSION) {
      if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) camera();
      else {
        toast("Camera permission was declined");
        cancelFile();
      }
    }
  }

  boolean isMedia(String url) {
    return url.matches("(?i)^https?://.*\\.(mp4|webm|m3u8|m4v|mov|mp3|m4a|ogg)(\\?.*)?$");
  }

  void recordMedia(Tab tab, String url) {
    if (tab.media.size() < 150) tab.media.add(url);
  }

  void scanMedia() {
    Tab t = current();
    if (t.web == null || t.home) {
      toast("Open a page with a video first");
      return;
    }
    t.web.evaluateJavascript(
        MEDIA_SCAN,
        value -> {
          try {
            String decoded = new JSONTokener(value).nextValue().toString();
            JSONArray array = new JSONArray(decoded);
            for (int i = 0; i < array.length(); i++) recordMedia(t, array.getString(i));
          } catch (Exception ignored) {
          }
          if (t.media.isEmpty()) {
            new GlassDialog.Builder(this)
                .setTitle("No downloadable media found")
                .setMessage(
                    "Start the video and try again. Direct media and unencrypted, completed HLS"
                        + " videos are supported. DRM, blob-only players and some separate"
                        + " audio/video streams are unsupported.")
                .setPositiveButton("OK", null)
                .show();
            return;
          }
          String[] urls = t.media.toArray(new String[0]);
          String[] names = new String[urls.length];
          for (int i = 0; i < urls.length; i++)
            names[i] =
                (urls[i].contains(".m3u8") ? "HLS video · " : "Media · ")
                    + URLUtil.guessFileName(urls[i], null, null)
                    + "\n"
                    + Blocker.host(urls[i]);
          new GlassDialog.Builder(this)
              .setTitle("Detected media")
              .setItems(
                  names,
                  (d, i) ->
                      confirmDownload(
                          urls[i],
                          URLUtil.guessFileName(urls[i], null, null),
                          urls[i].contains(".m3u8") ? "application/vnd.apple.mpegurl" : "",
                          t.web.getSettings().getUserAgentString(),
                          t.url))
              .setNegativeButton("Cancel", null)
              .show();
        });
  }

  void confirmDownload(String url, String name, String mime, String agent, String referer) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      toast("This source does not expose a downloadable HTTP media URL");
      return;
    }
    EditText edit = new EditText(this);
    edit.setText(DownloadCore.cleanName(name));
    edit.setSingleLine(true);
    edit.setSelectAllOnFocus(true);
    pad(edit, 16);
    new GlassDialog.Builder(this)
        .setTitle("Download file")
        .setMessage(
            "Up to 6 parallel connections when the server supports ranges. Files save to"
                + " Downloads/Aura Browser.")
        .setView(edit)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Download",
            (d, i) -> {
              try {
                if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
                  requestPermissions(
                      new String[] {Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                DownloadService.start(this);
                downloads.add(
                    url,
                    edit.getText().toString(),
                    mime,
                    agent,
                    CookieManager.getInstance().getCookie(url),
                    referer);
                toast("Download started");
                showDownloads();
              } catch (Exception e) {
                toast("Download could not start: " + e.getMessage());
              }
            })
        .show();
  }

  void showDownloads() {
    if (downloadsDialog != null && downloadsDialog.isShowing()) return;
    LinearLayout panel = vertical();
    pad(panel, 15);
    LinearLayout header = row();
    header.addView(action("+  Add URL", () -> addDownloadUrl()));
    header.addView(action("Pause all", () -> downloads.pauseAll()));
    panel.addView(header);
    ScrollView scroll = new ScrollView(this);
    LinearLayout list = vertical();
    scroll.addView(list);
    panel.addView(
        scroll,
        new LinearLayout.LayoutParams(
            -1,
            dp(Math.min(390, (int) (getResources().getConfiguration().screenHeightDp * .52f)))));
    GlassDialog dialog =
        new GlassDialog.Builder(this)
            .setTitle("Downloads")
            .setView(panel)
            .setPositiveButton("Done", null)
            .create();
    downloadsDialog = dialog;
    Runnable refresh =
        new Runnable() {
          public void run() {
            if (!dialog.isShowing()) return;
            list.removeAllViews();
            if (downloads.jobs().isEmpty()) {
              TextView empty =
                  label(
                      "Your downloads appear here.\nDownload a file, detect a video, or add a URL.",
                      15);
              pad(empty, 14);
              list.addView(empty);
            }
            for (DownloadCore.Job j : downloads.jobs()) {
              LinearLayout item = vertical();
              pad(item, 10);
              TextView title = label(j.name, 15);
              bold(title);
              item.addView(title);
              String status =
                  j.state
                      + " · "
                      + size(j.done.get())
                      + (j.size > 0 ? " / " + size(j.size) : "")
                      + (j.speed > 0 ? " · " + size((long) j.speed) + "/s" : "");
              item.addView(text(status, 12, muted));
              if (!j.error.isEmpty()) {
                TextView err = text(j.error, 12, 0xFFD56B7D);
                item.addView(err);
              }
              if (j.size > 0) {
                ProgressBar p =
                    new ProgressBar(
                        BrowserActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                p.setMax(1000);
                p.setProgress((int) Math.min(1000, j.done.get() * 1000 / j.size));
                item.addView(p, lp(-1, 5));
              }
              LinearLayout controls = row();
              if (j.state.equals("Complete")) {
                controls.addView(action("Open", () -> openDownload(j)));
                controls.addView(action("Share", () -> shareDownload(j)));
              } else if (j.state.equals("Downloading") || j.state.equals("Queued"))
                controls.addView(action("Pause", () -> downloads.pause(j)));
              else
                controls.addView(
                    action(
                        "Resume",
                        () -> {
                          try {
                            DownloadService.start(BrowserActivity.this);
                            downloads.resume(j);
                          } catch (Exception e) {
                            toast(e.getMessage());
                          }
                        }));
              controls.addView(
                  action(
                      "Remove",
                      () ->
                          new GlassDialog.Builder(BrowserActivity.this)
                              .setTitle("Remove this download?")
                              .setMessage(
                                  "The local transfer will be deleted. An exported file in"
                                      + " Downloads stays available.")
                              .setNegativeButton("Cancel", null)
                              .setPositiveButton("Remove", (d, i) -> downloads.remove(j))
                              .show()));
              item.addView(controls);
              list.addView(item);
            }
            handler.postDelayed(this, 900);
          }
        };
    dialog.setOnShowListener(d -> handler.post(refresh));
    dialog.setOnDismissListener(
        d -> {
          handler.removeCallbacks(refresh);
          downloadsDialog = null;
        });
    dialog.show();
  }

  static String size(long n) {
    return n < 1048576
        ? String.format(Locale.US, "%.0f KB", n / 1024d)
        : String.format(Locale.US, "%.1f MB", n / 1048576d);
  }

  void addDownloadUrl() {
    EditText e = new EditText(this);
    e.setHint("https://example.com/video.mp4");
    e.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    pad(e, 16);
    new GlassDialog.Builder(this)
        .setTitle("Download from URL")
        .setView(e)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Continue",
            (d, i) -> {
              String u = e.getText().toString();
              confirmDownload(
                  u,
                  URLUtil.guessFileName(u, null, null),
                  "",
                  WebSettings.getDefaultUserAgent(this),
                  "");
            })
        .show();
  }

  Uri downloadUri(DownloadCore.Job j) {
    return j.savedUri.isEmpty() ? ShareProvider.uri(j.output(), this) : Uri.parse(j.savedUri);
  }

  String downloadMime(DownloadCore.Job j) {
    if (!j.mime.isEmpty()) return j.mime;
    String ext = MimeTypeMap.getFileExtensionFromUrl(j.name);
    String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
    return m == null ? "application/octet-stream" : m;
  }

  void openDownload(DownloadCore.Job j) {
    try {
      Intent i =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(downloadUri(j), downloadMime(j))
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(i, "Open downloaded file"));
    } catch (Exception e) {
      toast("No installed app can open this file");
    }
  }

  void shareDownload(DownloadCore.Job j) {
    Intent i =
        new Intent(Intent.ACTION_SEND)
            .setType(downloadMime(j))
            .putExtra(Intent.EXTRA_STREAM, downloadUri(j))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(Intent.createChooser(i, "Share downloaded file"));
  }

  void showShield() {
    LinearLayout panel = vertical();
    pad(panel, 20);
    TextView count = text(String.format(Locale.US, "%,d", blocker.blocked.get()), 38, accent);
    bold(count);
    panel.addView(count);
    panel.addView(label("requests blocked this session", 14));
    panel.addView(
        text(
            String.format(Locale.US, "%,d offline ad / tracking domains", blocker.count()),
            13,
            muted));
    Switch blocking = new Switch(this);
    blocking.setText("Block ads and trackers");
    blocking.setChecked(blocker.enabled);
    blocking.setOnCheckedChangeListener(
        (v, on) -> {
          blocker.enabled = on;
          prefs.edit().putBoolean("blocking", on).apply();
        });
    panel.addView(blocking, lp(-1, 55));
    Switch pop = new Switch(this);
    pop.setText("Strict pop-up blocking");
    pop.setChecked(prefs.getBoolean("strictPopups", true));
    pop.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("strictPopups", on).apply());
    panel.addView(pop, lp(-1, 55));
    TextView note =
        text(
            "Strict mode blocks every new window. Turn it off to approve windows opened by a tap,"
                + " such as sign-in pop-ups.",
            12,
            muted);
    panel.addView(note);
    if (!current().home) {
      String host = Blocker.host(current().url);
      Switch site = new Switch(this);
      site.setText("Allow ads on " + host);
      site.setChecked(blocker.allowed.contains(host));
      site.setOnCheckedChangeListener(
          (v, on) -> {
            if (on) blocker.allowed.add(host);
            else blocker.allowed.remove(host);
            prefs.edit().putStringSet("allowed", new HashSet<>(blocker.allowed)).apply();
          });
      panel.addView(site, lp(-1, 62));
    }
    new GlassDialog.Builder(this)
        .setTitle("Privacy report")
        .setView(panel)
        .setPositiveButton(
            "Done",
            (d, i) -> {
              if (current().web != null && !current().home) current().web.reload();
            })
        .show();
  }

  void settings() {
    String theme = prefs.getString("theme", "System");
    String[] a = {
      "Appearance · " + theme,
      "Text size · " + prefs.getInt("zoom", 100) + "%",
      "Privacy & blocking",
      "Third-party cookies · " + (prefs.getBoolean("thirdCookies", false) ? "allowed" : "blocked"),
      "Clear browsing data",
      "About Aura Browser"
    };
    new GlassDialog.Builder(this)
        .setTitle("Settings")
        .setItems(
            a,
            (d, i) -> {
              switch (i) {
                case 0:
                  new GlassDialog.Builder(this)
                      .setTitle("Appearance")
                      .setItems(
                          new String[] {"System", "Light", "Dark"},
                          (dd, x) -> {
                            prefs
                                .edit()
                                .putString("theme", new String[] {"System", "Light", "Dark"}[x])
                                .apply();
                            readTheme();
                            buildUi();
                          })
                      .show();
                  break;
                case 1:
                  zoom();
                  break;
                case 2:
                  showShield();
                  break;
                case 3:
                  boolean on = !prefs.getBoolean("thirdCookies", false);
                  prefs.edit().putBoolean("thirdCookies", on).apply();
                  for (Tab t : tabs)
                    if (t.web != null)
                      CookieManager.getInstance().setAcceptThirdPartyCookies(t.web, on);
                  toast("Third-party cookies " + (on ? "allowed" : "blocked"));
                  break;
                case 4:
                  clearData();
                  break;
                case 5:
                  new GlassDialog.Builder(this)
                      .setTitle("Aura Browser 1.2")
                      .setMessage(
                          "Pearl Mist glass browser for phone, tablet and Android desktop mode.\n\n"
                              + "Engine: Android System WebView (Chromium), not WebKit.\n\n"
                              + "Downloads: 6 parallel connections where supported. Speed depends"
                              + " on your connection and the server. Resume may restart if a server"
                              + " lacks ranges or validators.\n\n"
                              + "Video: direct HTTP media and unencrypted, completed HLS with"
                              + " combined audio/video. DRM, live HLS, blob-only sources and"
                              + " separate-stream muxing are unsupported.\n\n"
                              + "Some websites restrict embedded browsers or sign-in; full Play"
                              + " Console compatibility is not guaranteed.\n\n"
                              + "Blocklist: StevenBlack unified hosts; bundled licenses are"
                              + " included in the source. Filtering is best effort.\n\n"
                              + "Glass uses translucent gradients and highlights. Mobile controls"
                              + " reserve their own space.")
                      .setPositiveButton("OK", null)
                      .show();
              }
            })
        .show();
  }

  void zoom() {
    String[] values = {"75%", "85%", "100%", "110%", "125%", "150%"};
    new GlassDialog.Builder(this)
        .setTitle("Page text size")
        .setItems(
            values,
            (d, i) -> {
              int percent = new int[] {75, 85, 100, 110, 125, 150}[i];
              prefs.edit().putInt("zoom", percent).apply();
              for (Tab t : tabs) if (t.web != null) t.web.getSettings().setTextZoom(percent);
            })
        .show();
  }

  void findOnPage() {
    if (current().web == null || current().home) return;
    EditText e = new EditText(this);
    e.setHint("Find text");
    pad(e, 16);
    new GlassDialog.Builder(this)
        .setTitle("Find on page")
        .setView(e)
        .setPositiveButton(
            "Find",
            (d, i) -> {
              WebView w = current().web;
              w.findAllAsync(e.getText().toString());
              LinearLayout buttons = row();
              buttons.addView(action("Previous", () -> w.findNext(false)));
              buttons.addView(action("Next", () -> w.findNext(true)));
              new GlassDialog.Builder(this)
                  .setTitle(e.getText())
                  .setView(buttons)
                  .setPositiveButton("Done", (dd, x) -> w.clearMatches())
                  .show();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  JSONArray stored(String key) {
    try {
      return new JSONArray(prefs.getString(key, "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  void addBookmark() {
    if (current().home) {
      addFavoriteUrl();
      return;
    }
    JSONArray a = stored("bookmarks");
    try {
      JSONObject o = new JSONObject();
      o.put("title", current().title);
      o.put("url", current().url);
      a.put(o);
      prefs.edit().putString("bookmarks", a.toString()).apply();
      toast("Bookmark saved");
    } catch (Exception ignored) {
    }
  }

  void addFavoriteUrl() {
    LinearLayout pane = vertical();
    pad(pane, 16);
    EditText name = new EditText(this);
    name.setHint("Name");
    EditText url = new EditText(this);
    url.setHint("https://website.com");
    pane.addView(name);
    pane.addView(url);
    new GlassDialog.Builder(this)
        .setTitle("Add favourite")
        .setView(pane)
        .setPositiveButton(
            "Save",
            (d, i) -> {
              String u = url.getText().toString().trim();
              if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
              try {
                JSONArray a = stored("bookmarks");
                a.put(new JSONObject().put("title", name.getText().toString()).put("url", u));
                prefs.edit().putString("bookmarks", a.toString()).apply();
                toast("Favourite saved in Bookmarks");
              } catch (Exception ignored) {
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  void saveHistory(Tab t) {
    if (t.home || !t.url.startsWith("http")) return;
    JSONArray old = stored("history"), a = new JSONArray();
    try {
      a.put(new JSONObject().put("title", t.title).put("url", t.url));
      for (int i = 0; i < old.length() && a.length() < 250; i++)
        if (!old.getJSONObject(i).optString("url").equals(t.url)) a.put(old.getJSONObject(i));
      prefs.edit().putString("history", a.toString()).apply();
    } catch (Exception ignored) {
    }
  }

  void showLibrary(boolean history) {
    JSONArray a = stored(history ? "history" : "bookmarks");
    if (a.length() == 0) {
      new GlassDialog.Builder(this)
          .setTitle(history ? "History" : "Bookmarks")
          .setMessage(
              history
                  ? "Pages you visit appear here."
                  : "Save a page by holding the book icon, or choose Add bookmark from the menu.")
          .setPositiveButton("OK", null)
          .show();
      return;
    }
    String[] titles = new String[a.length()];
    for (int i = 0; i < a.length(); i++) {
      JSONObject o = a.optJSONObject(i);
      titles[i] = o.optString("title") + "\n" + Blocker.host(o.optString("url"));
    }
    new GlassDialog.Builder(this)
        .setTitle(history ? "History" : "Bookmarks")
        .setItems(titles, (d, i) -> navigate(a.optJSONObject(i).optString("url")))
        .setNeutralButton(
            "Clear",
            (d, i) ->
                new GlassDialog.Builder(this)
                    .setTitle("Clear " + (history ? "history" : "bookmarks") + "?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(
                        "Clear",
                        (dd, x) -> prefs.edit().remove(history ? "history" : "bookmarks").apply())
                    .show())
        .setNegativeButton("Done", null)
        .show();
  }

  void clearData() {
    new GlassDialog.Builder(this)
        .setTitle("Clear browsing data?")
        .setMessage(
            "Removes history, cookies, website storage and cached pages. You will be signed out of"
                + " websites.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Clear",
            (d, i) -> {
              prefs.edit().remove("history").apply();
              CookieManager.getInstance().removeAllCookies(null);
              CookieManager.getInstance().flush();
              WebStorage.getInstance().deleteAllData();
              for (Tab t : tabs)
                if (t.web != null) {
                  t.web.clearCache(true);
                  t.web.clearHistory();
                }
              toast("Browsing data cleared");
            })
        .show();
  }

  void saveTabs() {
    JSONArray a = new JSONArray();
    try {
      for (Tab t : tabs)
        a.put(
            new JSONObject()
                .put("url", t.home ? "" : t.url)
                .put("title", t.title)
                .put("desktop", t.desktop));
      prefs.edit().putString("tabs", a.toString()).putInt("active", active).apply();
    } catch (Exception ignored) {
    }
  }

  void restoreTabs() {
    JSONArray a = stored("tabs");
    for (int i = 0; i < a.length() && i < 40; i++) {
      JSONObject o = a.optJSONObject(i);
      if (o == null) continue;
      Tab t = new Tab();
      t.url = o.optString("url");
      t.title = o.optString("title", "Start Page");
      t.home = t.url.isEmpty();
      t.desktop = o.optBoolean("desktop");
      tabs.add(t);
    }
    if (tabs.isEmpty()) tabs.add(new Tab());
    active = Math.max(0, Math.min(tabs.size() - 1, prefs.getInt("active", 0)));
  }

  void exitFullscreen() {
    if (fullscreenView == null) return;
    root.removeView(fullScreen);
    fullScreen = null;
    fullscreenView = null;
    if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
    fullscreenCallback = null;
    if (Build.VERSION.SDK_INT >= 30)
      getWindow().getInsetsController().show(WindowInsets.Type.systemBars());
  }

  void toast(String s) {
    Toast.makeText(this, s == null ? "Unable to complete action" : s, Toast.LENGTH_LONG).show();
  }

  static String n(String s) {
    return s == null ? "" : s;
  }

  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    exitFullscreen();
    hideDeck();
    readTheme();
    buildUi();
    for (Tab t : tabs) applyAgent(t);
  }

  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    if (i.getData() != null) navigate(i.getData().toString());
    if (i.getBooleanExtra("downloads", false)) showDownloads();
  }

  public boolean onKeyDown(int code, KeyEvent event) {
    if (event.isCtrlPressed()) {
      if (code == KeyEvent.KEYCODE_L) {
        address.requestFocus();
        address.selectAll();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
            .showSoftInput(address, InputMethodManager.SHOW_IMPLICIT);
        return true;
      }
      if (code == KeyEvent.KEYCODE_T) {
        newTab("");
        return true;
      }
      if (code == KeyEvent.KEYCODE_W) {
        closeTab(active);
        return true;
      }
      if (code == KeyEvent.KEYCODE_R) {
        if (current().web != null) current().web.reload();
        return true;
      }
      if (code == KeyEvent.KEYCODE_F) {
        findOnPage();
        return true;
      }
      if (code == KeyEvent.KEYCODE_TAB) {
        showTab((active + 1) % tabs.size());
        return true;
      }
    }
    return super.onKeyDown(code, event);
  }

  public void onBackPressed() {
    if (fullscreenView != null) {
      exitFullscreen();
      return;
    }
    if (deckOverlay != null) {
      hideDeck();
      return;
    }
    if (current().home) {
      super.onBackPressed();
      return;
    }
    goBack();
  }

  protected void onPause() {
    super.onPause();
    saveTabs();
    CookieManager.getInstance().flush();
    for (Tab t : tabs) if (t.web != null) t.web.onPause();
  }

  protected void onResume() {
    super.onResume();
    if (!tabs.isEmpty() && current().web != null) current().web.onResume();
  }

  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (level >= TRIM_MEMORY_RUNNING_LOW)
      for (Tab t : tabs)
        if (t != current() && t.web != null) {
          t.state = new Bundle();
          t.web.saveState(t.state);
          t.web.destroy();
          t.web = null;
        }
  }

  protected void onDestroy() {
    if (chromeMorph != null) chromeMorph.cancel();
    cancelFile();
    denySite();
    handler.removeCallbacksAndMessages(null);
    for (Tab t : tabs) if (t.web != null) t.web.destroy();
    super.onDestroy();
  }
}

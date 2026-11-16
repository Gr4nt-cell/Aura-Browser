package com.gr4nt.aurabrowser;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;
import java.util.*;

public class DownloadService extends Service {
  private static DownloadCore core;
  private static Context app;
  private Handler handler = new Handler(Looper.getMainLooper());
  private PowerManager.WakeLock wake;
  private final Set<String> exporting = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public static synchronized DownloadCore engine(Context c) {
    if (core == null) {
      app = c.getApplicationContext();
      core = new DownloadCore(new File(app.getFilesDir(), "transfers"));
    }
    return core;
  }

  public static void start(Context c) {
    Intent i = new Intent(c, DownloadService.class);
    c.startForegroundService(i);
  }

  public void onCreate() {
    super.onCreate();
    engine(this);
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(
        new NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW));
    PowerManager pm = getSystemService(PowerManager.class);
    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aura Browser:downloads");
    startForeground(7, notification());
    handler.post(tick);
  }

  public int onStartCommand(Intent i, int flags, int id) {
    if (i != null && "pause".equals(i.getAction())) core.pauseAll();
    return START_NOT_STICKY;
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          boolean active = core.active();
          if (active && !wake.isHeld()) wake.acquire(10 * 60 * 1000L);
          if (!active && wake.isHeld()) wake.release();
          boolean pending = false;
          for (DownloadCore.Job j : core.jobs())
            if (j.state.equals("Complete") && j.savedUri.isEmpty()) {
              pending = true;
              if (exporting.add(j.id))
                new Thread(
                        () -> {
                          export(j);
                          exporting.remove(j.id);
                        },
                        "Aura Browser-save-download")
                    .start();
            }
          getSystemService(NotificationManager.class).notify(7, notification());
          if (!active && !pending && exporting.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
          }
          handler.postDelayed(this, 1000);
        }
      };

  private Notification notification() {
    int active = 0;
    long done = 0;
    for (DownloadCore.Job j : core.jobs())
      if (j.state.equals("Downloading") || j.state.equals("Queued")) {
        active++;
        done += j.done.get();
      }
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, BrowserActivity.class).putExtra("downloads", true),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent pause =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, DownloadService.class).setAction("pause"),
            PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, "downloads")
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(
            active == 0
                ? "Aura Browser · Saving downloads"
                : active + " download" + (active == 1 ? "" : "s") + " in progress")
        .setContentText(String.format(Locale.US, "%.1f MB transferred", done / 1048576d))
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setOngoing(active > 0)
        .addAction(new Notification.Action.Builder(null, "Pause all", pause).build())
        .build();
  }

  private void export(DownloadCore.Job j) {
    Uri uri = null;
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, j.name);
        v.put(
            MediaStore.Downloads.MIME_TYPE, j.mime.isEmpty() ? "application/octet-stream" : j.mime);
        v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Aura Browser");
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Storage unavailable");
        try (InputStream in = new FileInputStream(j.output());
            OutputStream out = getContentResolver().openOutputStream(uri)) {
          byte[] b = new byte[128 * 1024];
          int n;
          while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
        j.savedUri = uri.toString();
      } else j.savedUri = ShareProvider.uri(j.output(), this).toString();
      core.save(j);
    } catch (Exception e) {
      if (uri != null) getContentResolver().delete(uri, null, null);
      j.error = "Saved inside Aura Browser; public export failed: " + e.getMessage();
      j.savedUri = ShareProvider.uri(j.output(), this).toString();
      core.save(j);
    }
  }

  public void onTimeout(int startId, int fgsType) {
    core.pauseAll();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  public void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (wake != null && wake.isHeld()) wake.release();
    super.onDestroy();
  }

  public IBinder onBind(Intent i) {
    return null;
  }
}

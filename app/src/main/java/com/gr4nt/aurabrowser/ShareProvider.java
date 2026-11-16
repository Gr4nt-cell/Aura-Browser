package com.gr4nt.aurabrowser;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.Properties;

/** Grants scoped access only to camera captures and completed downloads. */
public class ShareProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  public static Uri uri(File file, Context c) {
    String p = file.getAbsolutePath();
    String cache = c.getCacheDir().getAbsolutePath(), files = c.getFilesDir().getAbsolutePath();
    String relative =
        p.startsWith(cache + "/")
            ? "cache/" + p.substring(cache.length() + 1)
            : "files/" + p.substring(files.length() + 1);
    return new Uri.Builder()
        .scheme("content")
        .authority("com.gr4nt.aurabrowser.files")
        .path(relative)
        .build();
  }

  private File resolve(Uri u) throws FileNotFoundException {
    try {
      String path = u.getPath();
      File root;
      String relative;
      if (path.startsWith("/cache/")) {
        root = getContext().getCacheDir();
        relative = path.substring(7);
      } else if (path.startsWith("/files/transfers/")) {
        root = getContext().getFilesDir();
        relative = path.substring(7);
        if (!relative.endsWith("/finished")) throw new IOException();
      } else throw new IOException();
      File f = new File(root, relative).getCanonicalFile();
      if (!f.getPath().startsWith(root.getCanonicalPath() + "/")) throw new IOException();
      return f;
    } catch (Exception e) {
      throw new FileNotFoundException("Unavailable file");
    }
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    File f = resolve(uri);
    if (mode.contains("w") && !uri.getPath().startsWith("/cache/capture-"))
      throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(
        f,
        mode.contains("w")
            ? ParcelFileDescriptor.MODE_CREATE
                | ParcelFileDescriptor.MODE_TRUNCATE
                | ParcelFileDescriptor.MODE_READ_WRITE
            : ParcelFileDescriptor.MODE_READ_ONLY);
  }

  private String displayName(File f) {
    if (!f.getName().equals("finished")) return f.getName();
    try {
      Properties p = new Properties();
      try (InputStream in =
          new FileInputStream(new File(f.getParentFile(), "metadata.properties"))) {
        p.load(in);
      }
      return p.getProperty("name", "download");
    } catch (Exception e) {
      return "download";
    }
  }

  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
    try {
      File f = resolve(uri);
      String[] cols =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor m = new MatrixCursor(cols);
      Object[] row = new Object[cols.length];
      for (int i = 0; i < cols.length; i++)
        row[i] =
            cols[i].equals(OpenableColumns.SIZE)
                ? f.length()
                : cols[i].equals(OpenableColumns.DISPLAY_NAME) ? displayName(f) : null;
      m.addRow(row);
      return m;
    } catch (Exception e) {
      return null;
    }
  }

  public String getType(Uri uri) {
    return uri.getPath().startsWith("/cache/capture-") ? "image/jpeg" : "application/octet-stream";
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    return 0;
  }

  public int delete(Uri u, String s, String[] a) {
    return 0;
  }
}

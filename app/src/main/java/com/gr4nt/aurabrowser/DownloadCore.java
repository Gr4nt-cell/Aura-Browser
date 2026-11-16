package com.gr4nt.aurabrowser;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Platform-independent, disk-persistent ranged transfer engine. */
public final class DownloadCore {
  public static final int CONNECTIONS = 6;

  public static final class Job {
    public String id,
        url,
        name,
        mime,
        agent,
        cookie,
        referer,
        validator = "",
        error = "",
        kind = "file",
        savedUri = "";
    public volatile String state = "Paused";
    public long size = -1, created = System.currentTimeMillis();
    public volatile boolean stop;
    public volatile double speed;
    public final AtomicLong done = new AtomicLong();
    public int parts = 1;
    final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    public File dir;

    public File output() {
      return new File(dir, "finished");
    }
  }

  private final File root;
  private final List<Job> jobs = new CopyOnWriteArrayList<>();
  private final ExecutorService queue = Executors.newFixedThreadPool(2);
  private final Set<String> running = ConcurrentHashMap.newKeySet();
  public volatile Runnable onChange = () -> {};

  public DownloadCore(File root) {
    this.root = root;
    root.mkdirs();
    load();
  }

  public List<Job> jobs() {
    return new ArrayList<>(jobs);
  }

  public boolean active() {
    for (Job j : jobs) if (j.state.equals("Downloading") || j.state.equals("Queued")) return true;
    return false;
  }

  public Job add(String url, String name, String mime, String agent, String cookie, String referer)
      throws IOException {
    checkUrl(url);
    Job j = new Job();
    j.id = UUID.randomUUID().toString();
    j.url = url;
    j.name = cleanName(name);
    j.mime = n(mime);
    j.agent = n(agent);
    j.cookie = n(cookie);
    j.referer = n(referer);
    j.dir = new File(root, j.id);
    j.dir.mkdirs();
    jobs.add(0, j);
    save(j);
    resume(j);
    return j;
  }

  public static String cleanName(String s) {
    s = n(s).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
    if (s.isEmpty()) s = "download";
    return s.substring(0, Math.min(150, s.length()));
  }

  static String n(String s) {
    return s == null ? "" : s;
  }

  public synchronized void resume(Job j) {
    if (running.contains(j.id) || j.state.equals("Complete")) return;
    j.stop = false;
    j.error = "";
    j.state = "Queued";
    running.add(j.id);
    save(j);
    onChange.run();
    queue.submit(() -> transfer(j));
  }

  public void pause(Job j) {
    j.stop = true;
    if (!j.state.equals("Complete")) j.state = "Paused";
    for (HttpURLConnection c : j.connections) c.disconnect();
    save(j);
    onChange.run();
  }

  public void pauseAll() {
    for (Job j : jobs) if (!j.state.equals("Complete")) pause(j);
  }

  public void remove(Job j) {
    pause(j);
    if (running.contains(j.id)) {
      j.state = "Removing";
      queue.submit(
          () -> {
            while (running.contains(j.id)) {
              try {
                Thread.sleep(50);
              } catch (Exception ignored) {
              }
            }
            delete(j);
          });
    } else delete(j);
  }

  private void delete(Job j) {
    jobs.remove(j);
    File[] f = j.dir.listFiles();
    if (f != null) for (File x : f) x.delete();
    j.dir.delete();
    onChange.run();
  }

  public void shutdown() {
    pauseAll();
    queue.shutdownNow();
  }

  private void transfer(Job j) {
    try {
      if (j.stop) return;
      j.state = "Downloading";
      onChange.run();
      if (j.url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
        j.kind = "hls";
        hls(j);
      } else file(j);
      if (!j.stop) {
        j.state = "Complete";
        j.cookie = "";
        j.referer = "";
        j.speed = 0;
        save(j);
      }
    } catch (Exception e) {
      if (!j.stop) {
        j.state = "Failed";
        j.error = n(e.getMessage());
        save(j);
      }
    } finally {
      running.remove(j.id);
      for (HttpURLConnection c : j.connections) c.disconnect();
      j.connections.clear();
      j.speed = 0;
      if (j.stop && !j.state.equals("Removing")) j.state = "Paused";
      save(j);
      onChange.run();
    }
  }

  static void checkUrl(String s) throws IOException {
    try {
      URL u = new URL(s);
      if (!u.getProtocol().equals("https") && !u.getProtocol().equals("http"))
        throw new IOException("Only HTTP and HTTPS media can be downloaded");
      if (u.getUserInfo() != null) throw new IOException("Credential URLs are unsupported");
    } catch (MalformedURLException e) {
      throw new IOException("Invalid URL");
    }
  }

  private HttpURLConnection request(Job j, String url, String range) throws IOException {
    checkUrl(url);
    URL current = new URL(url);
    String originalHost = new URL(j.url).getHost();
    for (int redirects = 0; redirects < 10; redirects++) {
      if (j.stop) throw new InterruptedIOException("Paused");
      HttpURLConnection c = (HttpURLConnection) current.openConnection();
      c.setConnectTimeout(20000);
      c.setReadTimeout(20000);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("Accept-Encoding", "identity");
      if (!j.agent.isEmpty()) c.setRequestProperty("User-Agent", j.agent);
      if (current.getHost().equalsIgnoreCase(originalHost) && !j.cookie.isEmpty())
        c.setRequestProperty("Cookie", j.cookie);
      if (!j.referer.isEmpty()) c.setRequestProperty("Referer", j.referer);
      if (range != null) c.setRequestProperty("Range", range);
      if (range != null && !j.validator.isEmpty()) c.setRequestProperty("If-Range", j.validator);
      j.connections.add(c);
      int code;
      try {
        code = c.getResponseCode();
      } catch (IOException e) {
        j.connections.remove(c);
        c.disconnect();
        throw e;
      }
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String loc = c.getHeaderField("Location");
        close(j, c);
        if (loc == null) throw new IOException("Redirect missing a destination");
        URL next = new URL(current, loc);
        checkUrl(next.toString());
        if (current.getProtocol().equals("https") && next.getProtocol().equals("http"))
          throw new IOException("Insecure download redirect refused");
        current = next;
        continue;
      }
      return c;
    }
    throw new IOException("Too many redirects");
  }

  private void close(Job j, HttpURLConnection c) {
    j.connections.remove(c);
    c.disconnect();
  }

  private String validator(HttpURLConnection c) {
    String e = c.getHeaderField("ETag");
    if (e != null && !e.startsWith("W/")) return e;
    return n(c.getHeaderField("Last-Modified"));
  }

  private void file(Job j) throws Exception {
    HttpURLConnection probe = request(j, j.url, "bytes=0-0");
    int code = probe.getResponseCode();
    boolean ranges = code == 206;
    long size = probe.getContentLengthLong();
    if (ranges) {
      String cr = n(probe.getHeaderField("Content-Range"));
      if (!cr.matches("bytes 0-0/[0-9]+")) {
        close(j, probe);
        throw new IOException("Invalid server range response");
      }
      size = Long.parseLong(cr.substring(cr.indexOf('/') + 1));
    }
    if (code != 200 && code != 206) {
      close(j, probe);
      throw new IOException("Server returned HTTP " + code + ". The link may have expired.");
    }
    String v = validator(probe);
    String type = n(probe.getContentType());
    close(j, probe);
    if ((n(j.mime).isEmpty() || j.mime.equals("application/octet-stream")) && !type.isEmpty())
      j.mime = type.split(";")[0].trim();
    if (type.contains("mpegurl")) {
      j.kind = "hls";
      hls(j);
      return;
    }
    boolean changed = j.size != size || !j.validator.equals(v) || v.isEmpty();
    if (changed) {
      clearParts(j);
      j.done.set(0);
    }
    j.size = size;
    j.validator = v;
    j.parts = ranges && size >= 2 * 1024 * 1024 ? CONNECTIONS : 1;
    save(j);
    if (!ranges) {
      File p = new File(j.dir, "part-0");
      p.delete();
      j.done.set(0);
      copyWhole(j, j.url, p);
    } else {
      long total = 0;
      for (int i = 0; i < j.parts; i++) total += new File(j.dir, "part-" + i).length();
      j.done.set(total);
      ExecutorService pool = Executors.newFixedThreadPool(j.parts);
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < j.parts; i++) {
        final int index = i;
        final long start = size * i / j.parts, end = size * (i + 1) / j.parts - 1;
        futures.add(
            pool.submit(
                () -> {
                  try {
                    segment(j, index, start, end);
                  } catch (Exception e) {
                    throw new CompletionException(e);
                  }
                }));
      }
      try {
        for (Future<?> f : futures) f.get();
      } finally {
        pool.shutdownNow();
        for (HttpURLConnection c : j.connections) c.disconnect();
        pool.awaitTermination(25, TimeUnit.SECONDS);
      }
    }
    if (j.stop) return;
    merge(j, j.parts, "part-");
    if (j.size >= 0 && j.output().length() != j.size)
      throw new IOException("Size verification failed; resume to retry");
    clearParts(j);
    j.done.set(j.output().length());
  }

  private void segment(Job j, int index, long start, long end) throws Exception {
    File part = new File(j.dir, "part-" + index);
    long max = end - start + 1;
    if (part.length() > max) part.delete();
    for (int attempt = 0; attempt < 4 && !j.stop; attempt++) {
      long existing = part.length();
      if (existing == max) return;
      HttpURLConnection c = null;
      try {
        c = request(j, j.url, "bytes=" + (start + existing) + "-" + end);
        String expected = "bytes " + (start + existing) + "-" + end + "/" + j.size;
        if (c.getResponseCode() != 206 || !expected.equals(c.getHeaderField("Content-Range")))
          throw new IOException("Server cannot safely resume this range; retry the download");
        String received = validator(c);
        if (!j.validator.isEmpty() && !received.isEmpty() && !j.validator.equals(received))
          throw new IOException("The remote file changed; retry the download");
        try (InputStream in = c.getInputStream();
            OutputStream out = new BufferedOutputStream(new FileOutputStream(part, true))) {
          copy(j, in, out, max - existing);
        }
        if (part.length() != max)
          throw new IOException("Connection ended before the range completed");
        return;
      } catch (IOException e) {
        if (j.stop) return;
        if (attempt == 3) throw e;
        Thread.sleep(300L * (attempt + 1));
      } finally {
        if (c != null) close(j, c);
      }
    }
  }

  private void copyWhole(Job j, String url, File target) throws Exception {
    HttpURLConnection c = request(j, url, null);
    try {
      if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
      try (InputStream in = c.getInputStream();
          OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
        copy(j, in, out, -1);
      }
    } finally {
      close(j, c);
    }
  }

  private void copy(Job j, InputStream in, OutputStream out, long limit) throws IOException {
    byte[] b = new byte[64 * 1024];
    long copied = 0, at = System.nanoTime(), previous = j.done.get();
    int count;
    while (!j.stop
        && (limit < 0 || copied < limit)
        && (count = in.read(b, 0, limit < 0 ? b.length : (int) Math.min(b.length, limit - copied)))
            != -1) {
      out.write(b, 0, count);
      copied += count;
      long d = j.done.addAndGet(count);
      long now = System.nanoTime();
      if (now - at > 500_000_000L) {
        j.speed = (d - previous) * 1e9 / (now - at);
        at = now;
        previous = d;
      }
    }
  }

  private String text(Job j, String url) throws Exception {
    HttpURLConnection c = request(j, url, null);
    try {
      if (c.getResponseCode() != 200) throw new IOException("Playlist HTTP " + c.getResponseCode());
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) != -1) {
          if (j.stop) throw new InterruptedIOException("Paused");
          out.write(b, 0, n);
          if (out.size() > 8 * 1024 * 1024) throw new IOException("Playlist too large");
        }
        return out.toString("UTF-8");
      }
    } finally {
      close(j, c);
    }
  }

  private void hls(Job j) throws Exception {
    String url = j.url, playlist = text(j, url);
    for (int depth = 0; depth < 4 && playlist.contains("#EXT-X-STREAM-INF"); depth++) {
      if (playlist.contains("#EXT-X-MEDIA:"))
        throw new IOException(
            "This HLS video uses separate media tracks; choose a direct MP4 source instead");
      String[] lines = playlist.split("\\r?\\n");
      long best = -1;
      String chosen = null;
      for (int i = 0; i < lines.length - 1; i++)
        if (lines[i].startsWith("#EXT-X-STREAM-INF:")) {
          java.util.regex.Matcher m =
              java.util.regex.Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)")
                  .matcher(lines[i].substring(lines[i].indexOf(':') + 1));
          long rate = m.find() ? Long.parseLong(m.group(1)) : 0;
          int k = i + 1;
          while (k < lines.length && (lines[k].trim().isEmpty() || lines[k].startsWith("#"))) k++;
          if (k < lines.length && rate > best) {
            best = rate;
            chosen = new URL(new URL(url), lines[k].trim()).toString();
          }
        }
      if (chosen == null) throw new IOException("No downloadable HLS variant");
      url = chosen;
      playlist = text(j, url);
    }
    if (!playlist.contains("#EXTM3U") || !playlist.contains("#EXT-X-ENDLIST"))
      throw new IOException(
          "Only completed HLS videos are supported; live streams cannot be saved");
    if (playlist.matches("(?s).*#EXT-X-KEY:(?!METHOD=NONE).*"))
      throw new IOException(
          "Encrypted HLS is unsupported. DRM-protected video cannot be downloaded.");
    if (playlist.contains("#EXT-X-BYTERANGE")
        || playlist.contains("#EXT-X-MAP")
        || playlist.contains("#EXT-X-MEDIA:"))
      throw new IOException(
          "This HLS format needs a separate audio/video muxer; try a direct MP4 source");
    List<String> segments = new ArrayList<>();
    for (String l : playlist.split("\\r?\\n"))
      if (!l.trim().isEmpty() && !l.startsWith("#"))
        segments.add(new URL(new URL(url), l.trim()).toString());
    if (segments.isEmpty() || segments.size() > 20000)
      throw new IOException("Invalid segment count");
    String fingerprint = Integer.toHexString(segments.toString().hashCode());
    File fingerprintFile = new File(j.dir, "playlist-id");
    String old =
        fingerprintFile.exists()
            ? new String(Files.readAllBytes(fingerprintFile.toPath()), "UTF-8")
            : "";
    if (!old.equals(fingerprint)) {
      clearParts(j);
      try (FileOutputStream out = new FileOutputStream(fingerprintFile)) {
        out.write(fingerprint.getBytes("UTF-8"));
      }
    }
    j.size = -1;
    j.parts = segments.size();
    j.done.set(0);
    for (int i = 0; i < segments.size(); i++)
      j.done.addAndGet(new File(j.dir, "seg-" + i).length());
    ExecutorService pool = Executors.newFixedThreadPool(CONNECTIONS);
    List<Future<?>> tasks = new ArrayList<>();
    for (int i = 0; i < segments.size(); i++) {
      final int index = i;
      tasks.add(
          pool.submit(
              () -> {
                try {
                  File finished = new File(j.dir, "seg-" + index);
                  if (finished.exists()) return;
                  File temp = new File(j.dir, "temp-" + index);
                  long before = j.done.get();
                  try {
                    copyWhole(j, segments.get(index), temp);
                  } catch (Exception e) {
                    j.done.addAndGet(-temp.length());
                    throw e;
                  }
                  if (!j.stop) {
                    if (!temp.renameTo(finished)) throw new IOException("Could not save segment");
                  } else j.done.addAndGet(-temp.length());
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              }));
    }
    try {
      for (Future<?> f : tasks) f.get();
    } finally {
      pool.shutdownNow();
      for (HttpURLConnection c : j.connections) c.disconnect();
      pool.awaitTermination(25, TimeUnit.SECONDS);
    }
    if (j.stop) return;
    merge(j, segments.size(), "seg-");
    j.mime = "video/mp2t";
    if (!j.name.endsWith(".ts")) j.name = j.name.replaceAll("(?i)\\.m3u8.*$", "") + ".ts";
    j.size = j.output().length();
    j.done.set(j.size);
    clearParts(j);
  }

  private void merge(Job j, int count, String prefix) throws Exception {
    File temp = new File(j.dir, "merging");
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
      byte[] b = new byte[128 * 1024];
      for (int i = 0; i < count; i++) {
        if (j.stop) {
          temp.delete();
          return;
        }
        try (InputStream in = new FileInputStream(new File(j.dir, prefix + i))) {
          int n;
          while ((n = in.read(b)) != -1) out.write(b, 0, n);
        }
      }
    }
    if (j.stop) {
      temp.delete();
      return;
    }
    if (j.output().exists()) j.output().delete();
    if (!temp.renameTo(j.output())) throw new IOException("Could not finalize file");
  }

  private void clearParts(Job j) {
    File[] fs = j.dir.listFiles();
    if (fs != null)
      for (File f : fs)
        if (f.getName().startsWith("part-")
            || f.getName().startsWith("seg-")
            || f.getName().startsWith("temp-")) f.delete();
  }

  public synchronized void save(Job j) {
    try {
      if (!j.dir.exists()) return;
      Properties p = new Properties();
      String[] names = {
        "id",
        "url",
        "name",
        "mime",
        "agent",
        "cookie",
        "referer",
        "validator",
        "error",
        "kind",
        "state",
        "savedUri"
      };
      String[] vals = {
        j.id,
        j.url,
        j.name,
        j.mime,
        j.agent,
        j.cookie,
        j.referer,
        j.validator,
        j.error,
        j.kind,
        j.state,
        j.savedUri
      };
      for (int i = 0; i < names.length; i++) p.setProperty(names[i], n(vals[i]));
      p.setProperty("size", "" + j.size);
      p.setProperty("parts", "" + j.parts);
      p.setProperty("created", "" + j.created);
      File t = new File(j.dir, "metadata.tmp");
      try (OutputStream out = new FileOutputStream(t)) {
        p.store(out, "Prism transfer");
      }
      Files.move(
          t.toPath(),
          new File(j.dir, "metadata.properties").toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception ignored) {
    }
  }

  private void load() {
    File[] dirs = root.listFiles();
    if (dirs == null) return;
    for (File d : dirs)
      try {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(new File(d, "metadata.properties"))) {
          p.load(in);
        }
        Job j = new Job();
        j.dir = d;
        j.id = p.getProperty("id");
        j.url = p.getProperty("url");
        j.name = p.getProperty("name");
        j.mime = p.getProperty("mime", "");
        j.agent = p.getProperty("agent", "");
        j.cookie = p.getProperty("cookie", "");
        j.referer = p.getProperty("referer", "");
        j.validator = p.getProperty("validator", "");
        j.error = p.getProperty("error", "");
        j.kind = p.getProperty("kind", "file");
        j.savedUri = p.getProperty("savedUri", "");
        j.size = Long.parseLong(p.getProperty("size", "-1"));
        j.parts = Integer.parseInt(p.getProperty("parts", "1"));
        j.created = Long.parseLong(p.getProperty("created", "0"));
        j.state =
            p.getProperty("state", "").equals("Complete") && j.output().exists()
                ? "Complete"
                : "Paused";
        if (j.state.equals("Complete")) j.done.set(j.output().length());
        else {
          for (int i = 0; i < j.parts; i++)
            j.done.addAndGet(new File(d, (j.kind.equals("hls") ? "seg-" : "part-") + i).length());
        }
        jobs.add(j);
      } catch (Exception ignored) {
      }
    jobs.sort((a, b) -> Long.compare(b.created, a.created));
  }
}

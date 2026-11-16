import com.gr4nt.aurabrowser.DownloadCore;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class DownloadCoreTest {
  static byte[] payload = new byte[7 * 1024 * 1024 + 103];
  static byte[] replacement;
  static volatile boolean changed, slow = true;
  static AtomicInteger concurrent = new AtomicInteger(),
      peak = new AtomicInteger(),
      requests = new AtomicInteger();
  static List<String> ranges = new CopyOnWriteArrayList<>();

  static void assertTrue(boolean x, String message) {
    if (!x) throw new AssertionError(message);
  }

  static void waitUntil(java.util.function.BooleanSupplier condition, long millis)
      throws Exception {
    long end = System.currentTimeMillis() + millis;
    while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(25);
    assertTrue(condition.getAsBoolean(), "Timed out waiting for condition");
  }

  static void serve(HttpExchange e, boolean supports) throws IOException {
    byte[] bytes = changed ? replacement : payload;
    String etag = changed ? "\"v2\"" : "\"v1\"";
    String range = e.getRequestHeaders().getFirst("Range"),
        ifRange = e.getRequestHeaders().getFirst("If-Range");
    int from = 0, to = bytes.length - 1;
    boolean ranged = supports && range != null && (ifRange == null || ifRange.equals(etag));
    if (ranged) {
      String[] parts = range.substring(6).split("-");
      from = Integer.parseInt(parts[0]);
      to = Math.min(to, Integer.parseInt(parts[1]));
      e.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + bytes.length);
      ranges.add(range);
    }
    e.getResponseHeaders().set("ETag", etag);
    e.getResponseHeaders().set("Content-Type", "application/octet-stream");
    e.sendResponseHeaders(ranged ? 206 : 200, to - from + 1);
    requests.incrementAndGet();
    int count = concurrent.incrementAndGet();
    peak.accumulateAndGet(count, Math::max);
    try (OutputStream out = e.getResponseBody()) {
      for (int i = from; i <= to; i += 8192) {
        out.write(bytes, i, Math.min(8192, to - i + 1));
        out.flush();
        if (slow)
          try {
            Thread.sleep(3);
          } catch (InterruptedException ignored) {
          }
      }
    } catch (IOException ignored) {
    } finally {
      concurrent.decrementAndGet();
      e.close();
    }
  }

  static DownloadCore.Job add(DownloadCore core, String url, String name) throws Exception {
    return core.add(url, name, "application/octet-stream", "Prism test", "", "");
  }

  static void complete(DownloadCore.Job j) throws Exception {
    waitUntil(() -> j.state.equals("Complete") || j.state.equals("Failed"), 45000);
    assertTrue(j.state.equals("Complete"), j.error);
  }

  static void check(DownloadCore.Job j, byte[] bytes) throws Exception {
    assertTrue(
        Arrays.equals(Files.readAllBytes(j.output().toPath()), bytes), "Downloaded bytes differ");
  }

  public static void main(String[] args) throws Exception {
    new Random(712).nextBytes(payload);
    replacement = payload.clone();
    new Random(381).nextBytes(replacement);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService serverPool = Executors.newCachedThreadPool();
    server.setExecutor(serverPool);
    server.createContext("/range", e -> serve(e, true));
    server.createContext("/single", e -> serve(e, false));
    server.createContext(
        "/redirect",
        e -> {
          e.getResponseHeaders().set("Location", "/range");
          e.sendResponseHeaders(302, -1);
          e.close();
        });
    server.createContext(
        "/missing",
        e -> {
          e.sendResponseHeaders(404, -1);
          e.close();
        });
    byte[] segment1 = "first-transport-stream".getBytes(),
        segment2 = "second-transport-stream".getBytes();
    server.createContext(
        "/playlist.m3u8",
        e -> {
          byte[] b =
              "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n/one.ts\n#EXTINF:6,\n/two.ts\n#EXT-X-ENDLIST\n"
                  .getBytes();
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.createContext(
        "/encrypted.m3u8",
        e -> {
          byte[] b =
              "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXTINF:6,\n/one.ts\n#EXT-X-ENDLIST\n"
                  .getBytes();
          e.sendResponseHeaders(200, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.createContext(
        "/one.ts",
        e -> {
          e.sendResponseHeaders(200, segment1.length);
          e.getResponseBody().write(segment1);
          e.close();
        });
    server.createContext(
        "/two.ts",
        e -> {
          e.sendResponseHeaders(200, segment2.length);
          e.getResponseBody().write(segment2);
          e.close();
        });
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    File dir = Files.createTempDirectory("prism-transfers-").toFile();
    DownloadCore core = new DownloadCore(dir);
    try {
      DownloadCore.Job a = add(core, base + "/range", "parallel.bin");
      waitUntil(() -> a.done.get() > 300000, 10000);
      core.pause(a);
      waitUntil(() -> concurrent.get() == 0, 10000);
      Thread.sleep(150);
      long partial = a.done.get();
      assertTrue(
          partial > 0 && partial < payload.length, "Pause did not preserve a partial transfer");
      core.resume(a);
      complete(a);
      check(a, payload);
      assertTrue(peak.get() >= 3, "Parallel workers were not used");
      assertTrue(ranges.size() > 7, "Resume did not request remaining byte ranges");
      System.out.println(
          "PASS parallel ranges + pause/resume + byte-for-byte integrity (peak "
              + peak.get()
              + " HTTP connections)");
      DownloadCore.Job b = add(core, base + "/range", "changed.bin");
      waitUntil(() -> b.done.get() > 200000, 10000);
      core.pause(b);
      waitUntil(() -> concurrent.get() == 0, 10000);
      Thread.sleep(150);
      changed = true;
      core.resume(b);
      complete(b);
      check(b, replacement);
      System.out.println("PASS changed ETag discards old partial bytes");
      slow = false;
      DownloadCore.Job c = add(core, base + "/single", "nonrange.bin");
      complete(c);
      check(c, replacement);
      System.out.println("PASS non-range server fallback");
      DownloadCore.Job d = add(core, base + "/redirect", "redirect.bin");
      complete(d);
      check(d, replacement);
      System.out.println("PASS HTTP redirects");
      DownloadCore.Job h = add(core, base + "/playlist.m3u8", "clip.m3u8");
      complete(h);
      ByteArrayOutputStream merged = new ByteArrayOutputStream();
      merged.write(segment1);
      merged.write(segment2);
      check(h, merged.toByteArray());
      assertTrue(h.name.endsWith(".ts"), "HLS extension");
      System.out.println("PASS HLS VOD segments merged in playlist order");
      DownloadCore.Job enc = add(core, base + "/encrypted.m3u8", "protected.m3u8");
      waitUntil(() -> enc.state.equals("Failed"), 10000);
      assertTrue(enc.error.contains("Encrypted"), "Encrypted media should be refused");
      System.out.println("PASS encrypted HLS rejection");
      DownloadCore.Job missing = add(core, base + "/missing", "missing.bin");
      waitUntil(() -> missing.state.equals("Failed"), 10000);
      assertTrue(missing.error.contains("404"), "HTTP failure missing");
      System.out.println("PASS HTTP failure report");
      slow = true;
      DownloadCore.Job partialJob = add(core, base + "/range", "process-resume.bin");
      waitUntil(() -> partialJob.done.get() > 200000, 10000);
      core.pause(partialJob);
      waitUntil(() -> concurrent.get() == 0, 10000);
      Thread.sleep(150);
      core.shutdown();
      DownloadCore restored = new DownloadCore(dir);
      assertTrue(restored.jobs().size() == 8, "Persistent job inventory");
      DownloadCore.Job recovered =
          restored.jobs().stream()
              .filter(j -> j.name.equals("process-resume.bin"))
              .findFirst()
              .get();
      assertTrue(
          recovered.state.equals("Paused") && recovered.done.get() > 0,
          "Partial transfer not restored");
      restored.resume(recovered);
      complete(recovered);
      check(recovered, replacement);
      assertTrue(
          restored.jobs().stream().filter(j -> j.state.equals("Complete")).count() == 6,
          "Completed transfers not restored");
      restored.shutdown();
      System.out.println("PASS disk-restored partial transfer resume and completed job inventory");
      assertTrue(
          DownloadCore.cleanName("../../bad:file.mp4").indexOf('/') < 0, "Filename sanitization");
      System.out.println("PASS filename sanitization");
    } finally {
      core.shutdown();
      server.stop(0);
      serverPool.shutdownNow();
    }
  }
}

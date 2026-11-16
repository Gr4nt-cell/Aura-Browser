package com.gr4nt.aurabrowser;

import android.content.Context;
import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class Blocker {
  private final Set<String> hosts = Collections.synchronizedSet(new HashSet<>());
  public final Set<String> allowed = Collections.synchronizedSet(new HashSet<>());
  public final AtomicLong blocked = new AtomicLong();
  public volatile boolean enabled = true, ready;

  public Blocker(Context c) {
    new Thread(
            () -> {
              try (BufferedReader r =
                  new BufferedReader(new InputStreamReader(c.getAssets().open("hosts.txt")))) {
                String line;
                while ((line = r.readLine()) != null) {
                  line = line.trim();
                  if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length > 1 && !parts[1].equals("localhost"))
                      hosts.add(parts[1].toLowerCase(Locale.ROOT));
                  }
                }
              } catch (Exception ignored) {
              } finally {
                ready = true;
              }
            },
            "Prism-filter-list")
        .start();
  }

  public int count() {
    return hosts.size();
  }

  public static String host(String url) {
    try {
      return new URI(url).getHost().toLowerCase(Locale.ROOT);
    } catch (Exception e) {
      return "";
    }
  }

  public boolean blocks(String url, String page) {
    if (!enabled || allowed.contains(host(page))) return false;
    String h = host(url);
    if (h.isEmpty() || h.equals(host(page))) return false;
    String s = h;
    while (s.contains(".")) {
      if (hosts.contains(s)) {
        blocked.incrementAndGet();
        return true;
      }
      s = s.substring(s.indexOf('.') + 1);
    }
    String u = url.toLowerCase(Locale.ROOT);
    if (u.contains("/doubleclick/")
        || u.contains("/adsbygoogle.js")
        || u.contains("/pagead/")
        || u.contains("/popunder")
        || u.contains("/popads.")
        || u.contains("/adsystem/")
        || u.contains("/prebid.")
        || u.contains("googletagservices.com")
        || u.contains("google-analytics.com")) {
      blocked.incrementAndGet();
      return true;
    }
    return false;
  }

  public static final String COSMETIC =
      "(function(){if(document.getElementById('prism-filter'))return;var"
          + " s=document.createElement('style');s.id='prism-filter';s.textContent='ins.adsbygoogle,iframe[src*=\"doubleclick.net\"],iframe[src*=\"googlesyndication.com\"],[id^=\"google_ads_iframe\"],[id^=\"div-gpt-ad\"],[data-ad-slot],[data-ad-client],.ad-banner,.adsbygoogle,.advertisement,.ad-container{display:none!important;height:0!important;min-height:0!important}';(document.head||document.documentElement).appendChild(s)})();";
}

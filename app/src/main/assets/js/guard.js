/* Aura Browser page guard. Injected at document start into every frame.
   Config comes from AuraBridge.config(host) (JSON), cosmetic data from AuraBridge.cosmetic() (top frame only). */
(function () {
  'use strict';
  if (window.__auraGuard) return;
  try { Object.defineProperty(window, '__auraGuard', { value: true }); } catch (e) { window.__auraGuard = true; }

  var B = window.AuraBridge;
  var cfg = { ads: true, popups: true, redirects: true, cookie: true, yt: true, off: false };
  try { cfg = JSON.parse(B.config(location.hostname)); } catch (e) {}
  /* media discovery (reported even when blocking is off for the site) */
  try {
    var seenMedia = Object.create(null);
    var reportMedia = function (el) {
      try {
        var u = el.currentSrc || el.src || '';
        if (u && /^https?:/i.test(u) && !seenMedia[u]) { seenMedia[u] = 1; B.media(u); }
        var kids = el.querySelectorAll ? el.querySelectorAll('source[src]') : [];
        for (var i = 0; i < kids.length; i++) { var k = kids[i].src; if (k && /^https?:/i.test(k) && !seenMedia[k]) { seenMedia[k] = 1; B.media(k); } }
      } catch (e) {}
    };
    ['loadstart', 'loadedmetadata', 'play', 'playing'].forEach(function (n) {
      document.addEventListener(n, function (e) { if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) reportMedia(e.target); }, true);
    });
    setInterval(function () { var v = document.querySelectorAll('video,audio'); for (var i = 0; i < v.length; i++) reportMedia(v[i]); }, 2500);
  } catch (e) {}
  if (window.top === window && cfg.pad) {
    try {
      var padStyle = document.createElement('style');
      padStyle.textContent = 'html::after{content:"";display:block;height:' + cfg.pad + 'px;pointer-events:none}';
      (document.head || document.documentElement).appendChild(padStyle);
    } catch (e) {}
  }
  if (cfg.off) return;
  var isTop = window.top === window;
  function report(kind, url) { try { B.blocked(kind, String(url || '')); } catch (e) {} }

  /* ------------------------------------------------------------------ helpers */
  var TWO = { co: 1, com: 1, org: 1, net: 1, gov: 1, edu: 1, ac: 1, or: 1, ne: 1, go: 1 };
  function reg(h) {
    h = String(h || '').toLowerCase().replace(/\.$/, '');
    var p = h.split('.');
    if (p.length <= 2) return h;
    var l = p[p.length - 1], s = p[p.length - 2];
    return (l.length === 2 && TWO[s]) ? p.slice(-3).join('.') : p.slice(-2).join('.');
  }
  function hostOf(u) { try { return new URL(u, location.href).hostname; } catch (e) { return ''; } }
  function crossSite(u) {
    var h = hostOf(u);
    return h && reg(h) !== reg(location.hostname);
  }
  var TRUSTED = /(^|\.)(accounts\.google\.com|google\.com|appleid\.apple\.com|apple\.com|login\.microsoftonline\.com|login\.live\.com|microsoft\.com|facebook\.com|github\.com|gitlab\.com|paypal\.com|stripe\.com|twitter\.com|x\.com|linkedin\.com|discord\.com|slack\.com|okta\.com|auth0\.com|amazon\.com|cognito-idp\.[a-z0-9-]+\.amazonaws\.com|firebaseapp\.com|dropbox\.com|zoom\.us|whatsapp\.com|telegram\.org|spotify\.com|yahoo\.com|twitch\.tv)$/;
  function trusted(u) { var h = hostOf(u); return !!h && TRUSTED.test(h); }

  var lastInput = { t: -1e9, el: null };
  function mark(e) { lastInput = { t: performance.now(), el: e.target }; }
  ['pointerdown', 'mousedown', 'touchstart', 'touchend', 'click', 'keydown'].forEach(function (n) {
    window.addEventListener(n, mark, true);
  });
  var INTERACTIVE = 'a[href],button,input,select,textarea,summary,label,[role=button],[role=link],[role=menuitem],[role=tab]';
  function userClickedInteractive() {
    if (performance.now() - lastInput.t > 1500) return false;
    var el = lastInput.el;
    try { return !!(el && el.closest && el.closest(INTERACTIVE)); } catch (e) { return false; }
  }
  function userActive() {
    try { return navigator.userActivation ? navigator.userActivation.isActive : true; } catch (e) { return true; }
  }

  /* ------------------------------------------------------------------ pop-ups */
  if (cfg.popups) {
    var realOpen = window.open;
    var stub = function () {
      var d = { write: function () {}, writeln: function () {}, close: function () {}, open: function () {} };
      return { closed: true, opener: null, focus: function () {}, blur: function () {}, close: function () {},
        postMessage: function () {}, document: d, location: { href: '', assign: function () {}, replace: function () {}, reload: function () {} } };
    };
    var lastOpen = -1e9;
    window.open = function (url) {
      var u = url ? String(url) : '';
      var nowT = performance.now();
      if (nowT - lastOpen < 2500) { report('popup', u); return stub(); }
      var abs = u ? hostOf(u) : '';
      var cross = u && u !== 'about:blank' && abs && reg(abs) !== reg(location.hostname);
      if (!userActive()) { report('popup', u); return stub(); }
      if (cross && !trusted(u) && !userClickedInteractive()) { report('popup', u); return stub(); }
      lastOpen = nowT;
      return realOpen.apply(window, arguments);
    };

    var realClick = HTMLElement.prototype.click;
    HTMLElement.prototype.click = function () {
      try {
        if (this instanceof HTMLAnchorElement && this.href && !this.hasAttribute('download') &&
            !/^(blob|data|javascript):/i.test(this.href) &&
            (this.target === '_blank' || !this.isConnected) && crossSite(this.href) && !userClickedInteractive()) {
          report('popup', this.href);
          return;
        }
      } catch (e) {}
      return realClick.apply(this, arguments);
    };

    document.addEventListener('click', function (e) {
      if (e.isTrusted) return;
      var a = e.target && e.target.closest && e.target.closest('a[href]');
      if (a && a.target === '_blank' && crossSite(a.href) && !a.hasAttribute('download')) {
        e.preventDefault(); e.stopImmediatePropagation(); report('popup', a.href);
      }
    }, true);
  }

  /* ------------------------------------------------------------------ click-jack overlays */
  function checkOverlay(el) {
    try {
      if (!el || el.nodeType !== 1 || el === document.body || el === document.documentElement) return;
      var tag = el.tagName;
      var link = tag === 'A' ? el : (tag === 'IFRAME' ? el : (el.children && el.children.length === 1 ? el.children[0] : null));
      if (!link) return;
      var ltag = link.tagName;
      var target = ltag === 'A' ? link.href : (ltag === 'IFRAME' ? link.src : '');
      if (!target || !crossSite(target)) return;
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') return;
      if (cs.pointerEvents === 'none') return;
      var r = el.getBoundingClientRect();
      if (r.width < innerWidth * 0.85 || r.height < innerHeight * 0.6) return;
      var transparent = parseFloat(cs.opacity) < 0.06 || /rgba\(.*,\s*0\)|transparent/.test(cs.backgroundColor);
      if (!transparent) return;
      el.remove();
      report('overlay', target);
    } catch (e) {}
  }

  /* Full-page invisible / ad iframes and click traps, caught when added, when restyled and under every tap. */
  function bigFixed(el) {
    try {
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') return null;
      if (cs.pointerEvents === 'none' || cs.display === 'none' || cs.visibility === 'hidden') return null;
      var r = el.getBoundingClientRect();
      if (r.width < innerWidth * 0.8 || r.height < innerHeight * 0.55) return null;
      return cs;
    } catch (e) { return null; }
  }
  function killTrap(el) {
    try {
      if (!el || el.nodeType !== 1 || el === document.body || el === document.documentElement) return false;
      var cs = bigFixed(el);
      if (!cs) return false;
      var transparent = parseFloat(cs.opacity) < 0.08 || /rgba\(.*,\s*0\)|transparent/.test(cs.backgroundColor);
      var iframe = el.tagName === 'IFRAME' ? el : (el.querySelector ? el.querySelector('iframe') : null);
      var adFrame = iframe && iframe.src && crossSite(iframe.src) && !trusted(iframe.src);
      var link = el.tagName === 'A' ? el : (el.querySelector ? el.querySelector('a[href]') : null);
      var adLink = link && link.href && crossSite(link.href) && !trusted(link.href) && (el.children.length <= 2);
      if ((transparent && (adFrame || adLink || el.children.length === 0)) || (adFrame && el.tagName === 'IFRAME' && transparent)) {
        el.remove(); report('overlay', link ? link.href : (iframe ? iframe.src : ''));
        return true;
      }
    } catch (e) {}
    return false;
  }
  if (cfg.popups) {
    var onTap = function (e) {
      try {
        var x = e.clientX, y = e.clientY;
        if (typeof x !== 'number' || !document.elementsFromPoint) return;
        var stack = document.elementsFromPoint(x, y);
        for (var i = 0; i < stack.length && i < 6; i++) {
          var el = stack[i];
          if (killTrap(el)) { e.preventDefault(); e.stopImmediatePropagation(); return; }
        }
      } catch (err) {}
    };
    ['pointerdown', 'touchstart', 'click'].forEach(function (n) { document.addEventListener(n, onTap, true); });
    setInterval(function () {
      try {
        var all = document.querySelectorAll('body > div, body > iframe, body > a, html > div, html > iframe');
        for (var i = 0; i < all.length; i++) killTrap(all[i]);
      } catch (e) {}
    }, 1200);
  }

  /* ------------------------------------------------------------------ cosmetic filtering (top frame) */
  var styleStatic = null, styleDyn = null;
  function rulesFrom(list) {
    if (!list) return '';
    var out = '', parts = list.split('\n');
    for (var i = 0; i < parts.length; i++) if (parts[i]) out += parts[i] + '{display:none!important}\n';
    return out;
  }
  function mountStyle(css) {
    var s = document.createElement('style');
    s.setAttribute('data-aura', '1');
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
    return s;
  }

  var simpleSet = null;
  var seen = Object.create(null);
  function scan(root) {
    if (!simpleSet) return;
    var add = '';
    var els;
    try { els = (root.querySelectorAll ? root.querySelectorAll('[class],[id]') : []); } catch (e) { return; }
    function test(sel) {
      if (seen[sel]) return;
      seen[sel] = 1;
      if (simpleSet.has(sel)) add += sel + '{display:none!important}\n';
    }
    if (root.nodeType === 1 && (root.id || root.classList.length)) { if (root.id) test('#' + root.id); for (var k = 0; k < root.classList.length; k++) test('.' + root.classList[k]); }
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.id) test('#' + el.id);
      var cl = el.classList;
      for (var j = 0; j < cl.length; j++) test('.' + cl[j]);
    }
    if (add) {
      if (!styleDyn) styleDyn = mountStyle('');
      styleDyn.textContent += add;
    }
  }

  function startCosmetic() {
    var data;
    try { data = JSON.parse(B.cosmetic()); } catch (e) { return; }
    if (!data) return;
    var css = '';
    var list = [];
    if (cfg.ads) {
      css += rulesFrom(data.complexAds);
      list.push(data.simpleAds);
      var parts = location.hostname.replace(/^www\./, '').split('.');
      for (var i = 0; i < parts.length - 1; i++) {
        var key = parts.slice(i).join('.');
        if (data.sites && data.sites[key]) css += rulesFrom(data.sites[key]);
      }
    }
    if (cfg.cookie) {
      css += rulesFrom(data.complexCookie);
      list.push(data.simpleCookie);
    }
    if (cfg.yt && /(^|\.)youtube\.com$/.test(location.hostname)) {
      css += '#masthead-ad,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,.ytp-ad-overlay-container,ytd-banner-promo-renderer,#player-ads,ytd-promoted-sparkles-web-renderer,.video-ads,ytd-display-ad-renderer,ytd-companion-slot-renderer,tp-yt-paper-dialog:has(ytd-enforcement-message-view-model){display:none!important}\n';
    }
    if (css) styleStatic = mountStyle(css);
    if (list.length) {
      simpleSet = new Set(list.join(' ').split(' '));
      var go = function () { scan(document); };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', go, { once: true }); else go();
      var timer = 0, pending = [];
      new MutationObserver(function (muts) {
        for (var m = 0; m < muts.length; m++) {
          var mu = muts[m];
          if (mu.type === 'attributes') pending.push(mu.target);
          for (var n = 0; n < mu.addedNodes.length; n++) if (mu.addedNodes[n].nodeType === 1) pending.push(mu.addedNodes[n]);
        }
        if (!timer) timer = setTimeout(function () {
          timer = 0;
          var batch = pending; pending = [];
          if (batch.length > 400) { scan(document); return; }
          for (var b = 0; b < batch.length; b++) scan(batch[b]);
        }, 120);
      }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
    }
  }

  if (cfg.ads || cfg.cookie) {
    if (isTop) startCosmetic();
  }

  if (cfg.popups) {
    var ov = new MutationObserver(function (muts) {
      for (var m = 0; m < muts.length; m++) {
        var added = muts[m].addedNodes;
        for (var n = 0; n < added.length; n++) {
          var el = added[n];
          if (el.nodeType === 1) requestAnimationFrame(function (x) { return function () { checkOverlay(x); }; }(el));
        }
      }
    });
    var attach = function () { ov.observe(document.documentElement, { childList: true, subtree: true }); };
    if (document.documentElement) attach(); else document.addEventListener('readystatechange', attach, { once: true });
  }

  /* ------------------------------------------------------------------ YouTube */
  if (cfg.yt && /(^|\.)youtube\.com$/.test(location.hostname)) {
    var strip = function (o) {
      try {
        if (o && typeof o === 'object' && (o.adPlacements || o.playerAds || o.adSlots)) {
          delete o.adPlacements; delete o.playerAds; delete o.adSlots;
        }
      } catch (e) {}
      return o;
    };
    var origParse = JSON.parse;
    JSON.parse = function () { return strip(origParse.apply(this, arguments)); };
    try {
      var origJson = Response.prototype.json;
      Response.prototype.json = function () { return origJson.apply(this, arguments).then(strip); };
    } catch (e) {}
    var sped = false, wasMuted = false;
    setInterval(function () {
      var p = document.querySelector('.html5-video-player');
      var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
      if (!p || !v) return;
      if (p.classList.contains('ad-showing')) {
        if (!sped) { sped = true; wasMuted = v.muted; }
        try {
          v.muted = true; v.playbackRate = 16;
          if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration - 0.1;
        } catch (e) {}
        var skip = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');
        if (skip) skip.click();
      } else if (sped) {
        sped = false;
        try { v.playbackRate = 1; v.muted = wasMuted; } catch (e) {}
      }
    }, 250);
  }
})();

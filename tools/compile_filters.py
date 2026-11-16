#!/usr/bin/env python3
"""Compile EasyList / EasyPrivacy / uBlock / StevenBlack data into the compact assets Aura loads.

Usage: compile_filters.py <dir with downloaded lists> <assets/filters output dir>

Outputs
  domains.txt   one registrable-or-sub domain per line; the domain and every subdomain is blocked
  rules.txt     network rules, tab separated:  kind \t flags \t pattern
                  kind  H = host + path prefix   (pattern: host/path-prefix, host part may be empty path)
                        S = plain substring       (pattern: lowercase substring)
                        R = regex                 (pattern: java regex, matched against full url)
                  flags t = only when request is third party
  allow.txt     exception rules, same format as rules.txt
  hide.css      generic cosmetic selectors, one per line
  hide_sites.tsv  domain \t selector  (domain specific cosmetic filters)
  popups.txt    domains / substrings that must never open as popups or navigations from scripts
"""
import re
import sys
from pathlib import Path

src = Path(sys.argv[1])
out = Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)

DOMAIN_RE = re.compile(r'^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$')
OK_OPTS = {'third-party', '3p', 'script', 'image', 'subdocument', 'stylesheet', 'xmlhttprequest', 'xhr',
           'media', 'font', 'other', 'ping', 'important', 'match-case', 'websocket', 'object', '~third-party',
           '1p', '~1p', 'first-party', '~first-party', 'popup', 'all', 'css', 'frame', 'doc', 'document'}

domains = set()
block = {}      # (kind, flags, pattern) -> True
allow = {}
popups = set()
hide = set()
hide_cookie = set()
_cookie_mode = False
hide_sites = {}

def add(table, kind, flags, pat):
    if not pat or len(pat) < 4 and kind == 'S':
        return
    table[(kind, flags, pat)] = True

BAD_SEL = re.compile(r':-abp-|:has-text|:xpath|:matches-|:min-text|:upward|:remove|:style|:watch|\+js\(|:nth-ancestor|:others|:contains|\{|\}|\\u|:-js-')

def cosmetic(line, sites_ok=True):
    if '#@#' in line or '#?#' in line or '#$#' in line or '#%#' in line:
        return
    if '##' not in line:
        return
    dom, sel = line.split('##', 1)
    sel = sel.strip()
    if not sel or BAD_SEL.search(sel) or len(sel) > 400:
        return
    if sel.startswith('^'):
        return
    if dom == '':
        (hide_cookie if _cookie_mode else hide).add(sel)
    elif sites_ok:
        ds = [d for d in dom.split(',') if d and not d.startswith('~') and '*' not in d and '/' not in d]
        for d in ds:
            hide_sites.setdefault(d.lower(), set()).add(sel)

def convert(pat):
    """Convert an ABP pattern into (kind, value) or None."""
    anchored = False
    if pat.startswith('||'):
        anchored = True
        pat = pat[2:]
    elif pat.startswith('|'):
        pat = pat[1:]
    if pat.endswith('|'):
        pat = pat[:-1]
    if pat.startswith('/') and pat.endswith('/') and len(pat) > 2:
        return ('R', pat[1:-1])
    pat = pat.rstrip('^') if pat.endswith('^') and '^' not in pat[:-1] and '*' not in pat else pat
    if anchored:
        m = re.match(r'^([a-z0-9.-]+)(?:[\^/:?](.*))?$', pat, re.I)
        if m and '*' not in m.group(1):
            host = m.group(1).lower()
            rest = pat[len(m.group(1)):]
            if rest in ('', '^', '^|'):
                return ('D', host)
            if rest.startswith('/') and not re.search(r'[*^]', rest):
                return ('H', host + rest.lower())
            if rest.startswith('^/') and not re.search(r'[*^]', rest[1:]):
                return ('H', host + rest[1:].lower())
        # fall through to regex
        rx = re.escape(pat).replace(r'\*', '.*').replace(r'\^', r'[^\w.%-]')
        return ('R', r'^[a-z][a-z0-9+.-]*://([^/?#]*\.)?' + rx)
    if not re.search(r'[*^]', pat):
        p = pat.lower()
        return ('S', p) if len(p) >= 5 else None
    rx = re.escape(pat).replace(r'\*', '.*').replace(r'\^', r'[^\w.%-]')
    if len(rx) < 12:
        return None
    return ('R', rx)

def handle_network(line):
    exc = line.startswith('@@')
    if exc:
        line = line[2:]
    opts = ''
    if '$' in line:
        i = line.rfind('$')
        # a `$` inside the pattern (regex) is rare; assume trailing options
        line, opts = line[:i], line[i + 1:]
    flags = ''
    popup_rule = False
    if opts:
        parts = [o.strip().lower() for o in opts.split(',') if o.strip()]
        for o in parts:
            base = o.split('=')[0]
            if base in ('domain', 'redirect', 'redirect-rule', 'removeparam', 'csp', 'badfilter', 'denyallow',
                        'replace', 'header', 'permissions', 'cname', 'method', 'to', 'from', 'uritransform',
                        'inline-script', 'inline-font', 'generichide', 'specifichide', 'elemhide', 'ehide', 'shide',
                        'empty', 'mp4', 'queryprune', 'urlskip', 'ipaddress'):
                return
            if base.startswith('~') and base not in ('~third-party', '~1p', '~first-party'):
                # negated resource type, e.g. ~script: allow anyway
                continue
            if base not in OK_OPTS:
                return
        if 'popup' in parts:
            popup_rule = True
        if 'third-party' in parts or '3p' in parts:
            flags = 't'
        if '~third-party' in parts or '1p' in parts or 'first-party' in parts:
            return
        only_doc = all(p in ('document', 'doc', 'popup', 'all', 'important') for p in parts)
        if ('document' in parts or 'doc' in parts) and only_doc and not popup_rule:
            if not exc:
                return
    conv = convert(line)
    if not conv:
        return
    kind, val = conv
    if popup_rule:
        if not exc:
            popups.add(val if kind in ('D', 'H', 'S') else '')
        return
    if kind == 'D':
        if exc:
            add(allow, 'H', flags, val + '/')
            allow_domains.add(val)
        else:
            if flags == '':
                domains.add(val)
            else:
                add(block, 'H', 't', val + '/')
        return
    add(allow if exc else block, kind, flags, val)

allow_domains = set()

def process(path, network=True, cosm=True, hosts=False):
    n = 0
    for raw in Path(path).read_text(errors='ignore').splitlines():
        line = raw.strip()
        if not line or line[0] in '![#' and not ('##' in line and line[0] != '#') :
            if not (line.startswith('#') and '##' in line):
                continue
        if hosts:
            if line.startswith('#'):
                continue
            p = line.split()
            if len(p) >= 2 and p[0] in ('0.0.0.0', '127.0.0.1') and DOMAIN_RE.match(p[1]) and p[1] != 'localhost' and '.' in p[1]:
                domains.add(p[1].lower())
            continue
        if line.startswith('!') or line.startswith('['):
            continue
        if '##' in line or '#@#' in line or '#?#' in line or '#$#' in line or '#%#' in line or '#@$#' in line:
            if cosm:
                cosmetic(line)
            continue
        if network:
            handle_network(line)
            n += 1
    return n

files = {p.name: p for p in src.glob('*.txt')}
for name in ['easylist_adservers.txt', 'easylist_general_block.txt', 'easylist_thirdparty.txt',
             'easyprivacy_trackingservers.txt', 'easyprivacy_general.txt', 'adservers.txt', 'filters.txt',
             'badware.txt', 'unbreak.txt', 'adult_adservers.txt', 'adult_thirdparty.txt',
             'adult_specific_block.txt', 'resource-abuse.txt']:
    if name in files:
        process(files[name])
for name in ['easylist_general_hide.txt', 'adult_specific_hide.txt']:
    if name in files:
        process(files[name], network=False)
_cookie_mode = True
for name in ['easylist_cookie_general_hide.txt']:
    if name in files:
        process(files[name], network=False)
_cookie_mode = False
for name in ['easylist_specific_block_popup.txt', 'easylist_thirdparty_popup.txt', 'adult_adservers_popup.txt',
             'adult_specific_block_popup.txt', 'adult_thirdparty_popup.txt']:
    if name in files:
        process(files[name])

hosts = src / 'hosts.txt'
if hosts.exists():
    process(hosts, hosts=True)

# Domains we never block: sign-in, payments, developer consoles, core infra.
NEVER = {
    'google.com', 'gstatic.com', 'googleapis.com', 'googleusercontent.com', 'recaptcha.net', 'gvt1.com', 'gvt2.com',
    'youtube.com', 'ytimg.com', 'withgoogle.com', 'apple.com', 'icloud.com', 'microsoft.com', 'live.com',
    'microsoftonline.com', 'office.com', 'github.com', 'githubusercontent.com', 'githubassets.com', 'cloudflare.com',
    'cloudflareinsights.com', 'paypal.com', 'paypalobjects.com', 'stripe.com', 'stripe.network', 'amazon.com',
    'amazonaws.com', 'cloudfront.net', 'fastly.net', 'akamaized.net', 'jsdelivr.net', 'cdnjs.cloudflare.com',
    'unpkg.com', 'facebook.com', 'fbcdn.net', 'twitter.com', 'x.com', 'twimg.com', 'linkedin.com', 'licdn.com',
    'wikipedia.org', 'wikimedia.org', 'hcaptcha.com', 'play.google.com', 'expo.dev', 'whatsapp.com', 'whatsapp.net',
}
domains = {d for d in domains if d not in NEVER and not any(d.endswith('.' + n) and d.split('.')[-2:] == n.split('.')[-2:] and d.count('.') == n.count('.') + 1 and False for n in NEVER)}
# Drop apex domains of the NEVER list only when they would block the whole site; keep ad subdomains.
domains -= NEVER

# Remove anything covered by a parent domain in the set to keep the file small.
def has_parent(d):
    parts = d.split('.')
    for i in range(1, len(parts) - 1):
        if '.'.join(parts[i:]) in domains:
            return True
    return False
domains = {d for d in domains if not has_parent(d)}

(out / 'domains.txt').write_text('\n'.join(sorted(domains)) + '\n')

def dump(table, name, limit_regex=3500):
    rows, rx = [], 0
    for (k, f, p) in sorted(table.keys()):
        if k == 'R':
            try:
                re.compile(p)
            except re.error:
                continue
            if rx >= limit_regex:
                continue
            rx += 1
        if '\t' in p or '\n' in p:
            continue
        rows.append(f'{k}\t{f}\t{p}')
    (out / name).write_text('\n'.join(rows) + '\n')
    return len(rows)

nb = dump(block, 'rules.txt')
na = dump(allow, 'allow.txt', 1500)

(out / 'hide.css').write_text('\n'.join(sorted(hide)) + '\n')
(out / 'hide_cookie.css').write_text('\n'.join(sorted(hide_cookie - hide)) + '\n')
lines = []
for d, sels in sorted(hide_sites.items()):
    for s in sorted(sels):
        lines.append(f'{d}\t{s}')
(out / 'hide_sites.tsv').write_text('\n'.join(lines) + '\n')
(out / 'popups.txt').write_text('\n'.join(sorted(p for p in popups if p)) + '\n')

print(f'domains={len(domains)} block-rules={nb} allow-rules={na} generic-hide={len(hide)} cookie-hide={len(hide_cookie)} site-hide={len(lines)} popups={len(popups)}')

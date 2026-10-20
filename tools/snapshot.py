"""Builds app/src/main/assets/channels.m3u: iptv-org Tamil + India merged,
Tamil first, de-duplicated by tvg-id (else URL), Tamil channels tagged ';Tamil'.
Same merge as Repo.fetch() in the app, so first launch looks like a refreshed list.
Run from the project root: python tools/snapshot.py app/src/main/assets/channels.m3u"""
import re, sys, urllib.request

SOURCES = [("https://iptv-org.github.io/iptv/languages/tam.m3u", True),
           ("https://iptv-org.github.io/iptv/countries/in.m3u", False)]

def entries(text):
    extinf, opts = None, []
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF"):
            extinf, opts = line, []
        elif line.startswith("#EXTVLCOPT") and extinf:
            opts.append(line)
        elif line and not line.startswith("#") and extinf:
            yield extinf, opts, line
            extinf, opts = None, []

def tag_tamil(extinf):
    m = re.search(r'group-title="([^"]*)"', extinf)
    if not m:
        return extinf.replace(",", ' group-title="Tamil",', 1)
    g = m.group(1)
    new = "Tamil" if not g else (g if "Tamil" in g.split(";") else g + ";Tamil")
    return extinf[:m.start(1)] + new + extinf[m.end(1):]

seen, out = set(), ["#EXTM3U"]
for url, tamil in SOURCES:
    text = urllib.request.urlopen(url, timeout=30).read().decode("utf-8")
    for extinf, opts, stream in entries(text):
        m = re.search(r'tvg-id="([^"]+)"', extinf)
        key = m.group(1) if m else stream
        if key in seen:
            continue
        seen.add(key)
        out += [tag_tamil(extinf) if tamil else extinf, *opts, stream]

open(sys.argv[1], "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print(len(seen), "channels")

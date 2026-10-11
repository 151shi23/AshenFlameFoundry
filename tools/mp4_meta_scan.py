#!/usr/bin/env python3
"""Dump the ISOBMFF (MP4/MOV) box tree of a file and flag editor brand fingerprints.

Usage: python mp4_meta_scan.py <file.mp4> [--strings]
"""
import sys
from pathlib import Path

CONTAINERS = {
    b"moov", b"trak", b"mdia", b"minf", b"stbl", b"dinf", b"edts",
    b"udta", b"ilst", b"meta", b"mvex", b"moof", b"traf", b"mfra",
}
# fullbox containers carry 4 bytes of version+flags before their children
FULLBOX_CONTAINERS = {b"meta", b"moof", b"traf", b"mvex"}

SIGNATURES = [
    "剪映", "jianying", "JianYing", "VideoFusion", "video_fusion", "CapCut", "capcut",
    "bytedance", "ByteDance", "douyin", "Douyin", "TikTok", "ttvideo",
    "快影", "kuaiying", "KuaiYing", "kwai", "Kuaishou", "kuaishou", "gifshow",
    "必剪", "bijian", "BiJian", "bcut", "Bcut", "bilibili", "Bilibili",
    "premiere", "Adobe", "Final Cut", "iMovie", "Lavf", "ffmpeg", "MediaCoder",
    "VivaVideo", "小咖秀", "快剪辑",
]


def u32(b, o):
    return int.from_bytes(b[o:o + 4], "big")


def u64(b, o):
    return int.from_bytes(b[o:o + 8], "big")


def name(typ):
    return typ.decode("latin-1").replace("\xa9", "(c)").strip("\x00") or repr(typ)


def walk(data, start, end, depth, path, out):
    o = start
    while o + 8 <= end:
        size = u32(data, o)
        typ = data[o + 4:o + 8]
        hdr = 8
        if size == 1:
            if o + 16 > end:
                break
            size = u64(data, o + 8)
            hdr = 16
        elif size == 0:
            size = end - o
        p = path + "/" + name(typ)
        if size < hdr or o + size > end:
            out.append((depth, p + "  [TRUNCATED]", o, end - o, ""))
            return
        payload = data[o + hdr:o + size]
        note = ""
        if typ not in CONTAINERS:
            hits = find_hits(payload)
            if hits:
                note = "HIT: " + ", ".join(hits)
        out.append((depth, p, o, size, note))
        if typ in CONTAINERS:
            walk(data, o + hdr + (4 if typ in FULLBOX_CONTAINERS else 0), o + size,
                 depth + 1, p, out)
        o += size


def find_hits(chunk):
    hits = []
    for sig in SIGNATURES:
        low = sig.lower()
        for enc in ("utf-8", "gbk", "utf-16-le"):
            try:
                needle = sig.encode(enc)
            except UnicodeEncodeError:
                continue
            if needle in chunk or needle.lower() in chunk.lower():
                hits.append(f"{sig}[{enc}]")
                break
    return hits


def print_strings(head):
    cur = bytearray()
    seen = []
    for byte in head:
        if 32 <= byte < 127:
            cur.append(byte)
        else:
            if len(cur) >= 5:
                s = cur.decode("latin-1")
                if s not in seen:
                    seen.append(s)
            cur = bytearray()
    for s in seen:
        print(repr(s))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = [a for a in sys.argv[1:] if a.startswith("--")]
    f = Path(args[0])
    data = f.read_bytes()
    print(f"{f}  {len(data):,} bytes")
    out = []
    walk(data, 0, len(data), 0, "", out)
    for depth, p, off, size, note in out:
        print(f"{'  ' * depth}{p:<44} @{off:<11} {size:>11,}  {note}")
    if "--strings" in flags:
        print("\n-- printable strings in first 512KB --")
        print_strings(data[:524288])


if __name__ == "__main__":
    main()

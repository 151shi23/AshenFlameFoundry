#!/usr/bin/env python3
"""Craft a structurally valid MP4 carrying JianYing / KuaiYing / BiJian style metadata.

Used to exercise Mp4Scrubber's precise-vs-full removal granularity, the AIGC
keep-rule and the "no byte moves" guarantee without needing a real export.
"""
import sys
from pathlib import Path


def box(typ, payload):
    assert len(typ) == 4, typ
    return (8 + len(payload)).to_bytes(4, "big") + typ.encode("latin-1") + payload


def fullbox(typ, children):
    return box(typ, b"\x00\x00\x00\x00" + children)


def hdlr(htype, name):
    body = (b"\x00\x00\x00\x00" + b"\x00\x00\x00\x00" + htype.encode("latin-1")
            + b"\x00" * 12 + name.encode("utf-8") + b"\x00")
    return box("hdlr", body)


def data_atom(text):
    # ilst value atom: size + 'data' + version/flags(4) + typecode(4) + locale(4) + text
    body = b"\x00\x00\x00\x01" + b"TEXT" + b"\x00\x00\x00\x00" + text.encode("utf-8")
    return box("data", body)


def entry(key, text):
    return box(key, data_atom(text))


def stco(offsets):
    body = b"\x00\x00\x00\x00" + len(offsets).to_bytes(4, "big")
    for o in offsets:
        body += o.to_bytes(4, "big")
    return box("stco", body)


def avc1(width, height, compressor):
    p = b"\x00" * 6 + b"\x00\x00\x00\x01"          # reserved + data_ref_index
    p += b"\x00\x00\x00\x00" + b"\x00" * 12        # pre_defined / reserved
    p += width.to_bytes(2, "big") + height.to_bytes(2, "big")
    p += (0x00480000).to_bytes(4, "big") + (0x00480000).to_bytes(4, "big")
    p += b"\x00" * 4 + b"\x00\x01"                 # reserved, frame_count
    name = compressor.encode("utf-8")
    p += bytes([len(name)]) + name + b"\x00" * (31 - len(name))
    p += b"\x00\x18\xff\xff"                        # depth, pre_defined
    return box("avc1", p)


def stsd(entries):
    return box("stsd", b"\x00\x00\x00\x00" + len(entries).to_bytes(4, "big") + b"".join(entries))


def build(out):
    udta = box("udta", fullbox("meta",
        hdlr("mdir", "剪映专业版")
        + box("ilst",
              entry("\u00a9too", "剪映专业版 6.2.0")
              + entry("desc", "由 剪映 一键成片")
              + entry("\u00a9day", "2026-10-06")
              + entry("AIGC", "TC260:AIGC 剪映生成 合成内容")
              + entry("cprt", "必检水印 bijian"))
    ))
    uuid_extra = box("uuid", b"\x00" * 16 + b"kuaiying export kwai")
    mdia = box("mdia",
        box("mdhd", b"\x00" * 4 + b"\x00" * 8 + (1000).to_bytes(4, "big")
            + (240).to_bytes(4, "big") + b"\x00\x00\x13\x98" + b"\x00" * 2)
        + hdlr("vide", "VideoFusion")
        + box("minf",
              box("vmhd", b"\x00\x00\x00\x01" + b"\x00" * 8)
              + box("dinf", box("dref", b"\x00\x00\x00\x00" + (1).to_bytes(4, "big")
                                + box("url ", b"\x00\x00\x00\x01")))
              + box("stbl", stsd([avc1(720, 1280, "剪映 H.264")])
                    + box("stts", b"\x00\x00\x00\x00" + (1).to_bytes(4, "big")
                          + (12).to_bytes(4, "big") + (20).to_bytes(4, "big"))
                    + stco([0])))
    )
    trak = box("trak", box("tkhd", b"\x00" * 4 + b"\x00" * 20 + (1).to_bytes(4, "big")
                           + b"\x00" * 32 + (720).to_bytes(4, "big")
                           + (1280).to_bytes(4, "big")) + mdia)
    mdat_payload = b"\xf0\x0d" * 32
    mdat = box("mdat", mdat_payload)
    moov_children = box("mvhd", b"\x00" * 4 + b"\x00" * 8 + (1000).to_bytes(4, "big")
                        + (240).to_bytes(4, "big") + b"\x00\x01\x00\x00" + b"\x00" * 24
                        + b"\x00" * 28 + (2).to_bytes(4, "big")) + trak + udta
    moov = box("moov", moov_children)
    ftyp = box("ftyp", b"isom" + (0x200).to_bytes(4, "big") + b"isomiso2avc1mp41")
    body = ftyp + moov + uuid_extra + mdat
    Path(out).write_bytes(body)
    # 回显 mdat 的真实起点，模拟「faststart 后 mdat 在 moov 之后」的绝对偏移
    print(f"{out}: {len(body):,} bytes, mdat @ {len(ftyp) + len(moov) + len(uuid_extra)}")
    print("mdhd/tkhd/stco 全部指向 mdat 区内；清洗要求这些偏移一位都不动")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "/tmp/fake_jy.mp4")

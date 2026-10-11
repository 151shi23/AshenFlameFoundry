"""核验 APK 里到底进了什么（对应 PROJECT_NOTES 5.5 的手工做法）。

用法：
    python neteasemc\\tools\\verify_apk.py <apk> [要搜的类名 ...]
默认搜的一组是本项目的关键类（Blender 模块 / xz 解码器 / arnis 内核）。
"""
import hashlib
import sys
import zipfile

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEFAULT_NEEDLES = [
    "com/mineways/blender/BlenderActivity",
    "com/mineways/blender/BlenderInstaller",
    "com/mineways/blender/BlenderOps",
    "com/mineways/blender/ProjectOps",
    "com/mineways/blender/ProjectToolsActivity",
    "com/mineways/blender/GpuProbe",
    "com/mineways/blender/RenderFallback",
    "com/mineways/blender/AssetLib",
    "com/mineways/blender/SequenceEncoder",
    "com/mineways/blender/TemplateLib",
    "org/tukaani/xz/XZInputStream",
    "com/mineways/ShizukuWorldImporter",
    # 反射用的字符串字面量也要在（写错包名时这里会挂）
    "org.tukaani.xz.XZInputStream",
]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    apk = sys.argv[1]
    needles = sys.argv[2:] or DEFAULT_NEEDLES

    with open(apk, "rb") as f:
        raw = f.read()
    print("APK：%s\n大小：%.1f MB\nSHA-256：%s\n" % (
        apk, len(raw) / 1048576.0, hashlib.sha256(raw).hexdigest()))

    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        libs = sorted(n for n in names if n.endswith(".so"))
        print("原生库 %d 个：" % len(libs))
        for n in libs:
            print("  %-46s %8.2f MB" % (n, z.getinfo(n).file_size / 1048576.0))
        dex = b"".join(z.read(n) for n in names
                       if n.startswith("classes") and n.endswith(".dex"))
        print("\nclasses*.dex 合计 %.1f MB" % (len(dex) / 1048576.0))
        print("\n关键类是否进包：")
        missing = 0
        for k in needles:
            hit = k.encode() in dex
            if not hit:
                missing += 1
            print("  [%s] %s" % ("OK" if hit else "!!", k))
        assets = sorted(n for n in names if n.startswith("assets/"))
        print("\nassets 条目 %d 个（前 8 个）：%s" % (len(assets), assets[:8]))
    print("\n结论：%s" % ("全部命中" if missing == 0 else "有 %d 个没找到" % missing))
    return 0 if missing == 0 else 1


if __name__ == "__main__":
    sys.exit(main())

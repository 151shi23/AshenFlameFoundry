"""监控一键打包脚本的进度（实时读 C:\\mmbuild\\build.log）。

用法：
    python neteasemc\\tools\\watch_build.py                  # 最多盯 10 分钟
    python neteasemc\\tools\\watch_build.py --timeout 1800    # 最多盯 30 分钟
退出码：0=成功，1=失败，2=超时仍在跑。
"""
import argparse
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def read_text(path):
    try:
        with open(path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read()
    except OSError:
        return ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--log", default=r"C:\mmbuild\build.log")
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--interval", type=int, default=15)
    args = ap.parse_args()

    if not os.path.exists(args.log):
        print("还没找到构建日志：%s（沙箱可能还没建好）" % args.log)
        return 2

    start = time.time()
    state = "RUNNING"
    seen = 0
    while time.time() - start < args.timeout:
        text = read_text(args.log)
        if "BUILD SUCCESSFUL" in text:
            state = "SUCCESS"
            break
        if "BUILD FAILED" in text:
            state = "FAILED"
            break
        tasks = re.findall(r"^> Task :(\S+)", text, re.M)
        if len(tasks) > seen:                 # 只打印新完成的 task，日志不刷屏
            for t in tasks[seen:]:
                print("  [%4ds] %s" % (int(time.time() - start), t))
            seen = len(tasks)
            sys.stdout.flush()
        time.sleep(args.interval)

    text = read_text(args.log)
    print("\n==== %s（用时 %ds）====" % (state, int(time.time() - start)))
    if state != "SUCCESS":
        print("---- 日志尾部 ----")
        for line in text.strip().splitlines()[-40:]:
            print(line)
    apk = r"C:\mmbuild\MinewaysMobile-release.apk"
    if os.path.exists(apk):
        print("成品：%s（%.1f MB，%s）" % (
            apk, os.path.getsize(apk) / 1048576.0,
            time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(os.path.getmtime(apk)))))
    else:
        print("还没出现成品 APK：%s" % apk)
    return {"SUCCESS": 0, "FAILED": 1}.get(state, 2)


if __name__ == "__main__":
    sys.exit(main())

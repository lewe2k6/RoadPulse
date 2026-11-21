#!/usr/bin/env python3
# c:\Users\Acer\Downloads\gsm\roadpulse_lite\tools\verify_all.py
# Python 3.12 / Automated end-to-end verification of RoadPulse Lite spatial engine + release APK
import os
import subprocess
import zipfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TOOLS = os.path.join(ROOT, "tools")
APP_JAVA = os.path.join(ROOT, "app", "src", "main", "java", "vn", "roadpulse", "lite")
RPOI = os.path.join(ROOT, "app", "assets", "roadpulse_vn.rpoi")
APK = os.path.join(ROOT, "dist", "RoadPulseLite-v1.1.apk")

LOCAL_APP = os.environ.get("LOCALAPPDATA", r"C:\Users\Acer\AppData\Local")
SDK_DIR = os.path.join(LOCAL_APP, "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK_DIR, "build-tools", "34.0.0")
ANDROID_JAR = os.path.join(SDK_DIR, "platforms", "android-34", "android.jar")
CORE_LAMBDA_STUBS = os.path.join(BUILD_TOOLS, "core-lambda-stubs.jar")

JDK17_ROOT = os.path.join(LOCAL_APP, "Android", "jdk17")
JDK_BIN = None
for d in os.listdir(JDK17_ROOT):
    cand = os.path.join(JDK17_ROOT, d, "bin")
    if os.path.isfile(os.path.join(cand, "java.exe")):
        JDK_BIN = cand
        break

JAVAC = os.path.join(JDK_BIN, "javac.exe")
JAVA = os.path.join(JDK_BIN, "java.exe")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER_JAR = os.path.join(BUILD_TOOLS, "lib", "apksigner.jar")


def run(cmd, desc):
    print(f"\n=== {desc} ===")
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.stdout.strip():
        print(p.stdout.strip())
    if p.stderr.strip():
        print(p.stderr.strip())
    if p.returncode != 0:
        raise RuntimeError(f"Failed ({p.returncode}): {desc}")


def main():
    test_classes = os.path.join(ROOT, "build", "test_classes")
    os.makedirs(test_classes, exist_ok=True)

    boot_cp = ANDROID_JAR + os.pathsep + CORE_LAMBDA_STUBS
    run([
        JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-Xlint:-options", "-bootclasspath", boot_cp, "-classpath", ANDROID_JAR,
        "-d", test_classes,
        os.path.join(APP_JAVA, "UserPinStore.java"),
        os.path.join(APP_JAVA, "SpatialRadarEngine.java"),
        os.path.join(TOOLS, "RadarReplayHarness.java"),
    ], "Compiling SpatialRadarEngine + RadarReplayHarness")

    run([
        JAVA, "-cp", test_classes + os.pathsep + ANDROID_JAR,
        "vn.roadpulse.lite.RadarReplayHarness", RPOI
    ], "Running HN -> Cau Chui -> CT01 -> Na Sam (Lewe2k6) Replay Assertions")

    # Build APK
    import build_apk
    build_apk.main()

    # Check APK contents & alignment & badging
    run([AAPT2, "dump", "badging", APK], "Checking APK manifest & SDK flags (aapt2 dump badging)")
    run([ZIPALIGN, "-c", "-v", "4", APK], "Checking APK 4-byte alignment (zipalign)")

    with zipfile.ZipFile(APK, "r") as zf:
        info = zf.getinfo("assets/roadpulse_vn.rpoi")
        assert info.compress_type == zipfile.ZIP_STORED, f"Expected ZIP_STORED for .rpoi mmap, got {info.compress_type}"
        print(f"\nVerified assets/roadpulse_vn.rpoi compress_type=STORED ({info.file_size:,} bytes)")
        for zi in zf.infolist():
            print(f"  {zi.filename:38s} stored={zi.compress_type==0!s:5s} raw={zi.file_size:8d} cmp={zi.compress_size:8d}")

    print("\nALL BUILD & REPLAY VERIFICATIONS PASSED 100%!")


if __name__ == "__main__":
    main()

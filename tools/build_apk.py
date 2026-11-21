#!/usr/bin/env python3
# c:\Users\Acer\Downloads\gsm\roadpulse_lite\tools\build_apk.py
# Python 3.12 / Build signed RoadPulse Lite APK directly with JDK 17 + Android SDK 34 build-tools (no Gradle bloat)
import os
import shutil
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
APP_DIR = os.path.join(ROOT, "app")
RES_DIR = os.path.join(APP_DIR, "res")
ASSETS_DIR = os.path.join(APP_DIR, "assets")
MANIFEST = os.path.join(APP_DIR, "AndroidManifest.xml")
SRC_DIR = os.path.join(APP_DIR, "src", "main", "java")

BUILD_DIR = os.path.join(ROOT, "build")
DIST_DIR = os.path.join(ROOT, "dist")
KEYSTORE = os.path.join(ROOT, "tools", "roadpulse.keystore")

LOCAL_APP = os.environ.get("LOCALAPPDATA", r"C:\Users\Acer\AppData\Local")
SDK_DIR = os.path.join(LOCAL_APP, "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK_DIR, "build-tools", "34.0.0")
ANDROID_JAR = os.path.join(SDK_DIR, "platforms", "android-34", "android.jar")

# Find portable JDK 17
JDK17_ROOT = os.path.join(LOCAL_APP, "Android", "jdk17")
JDK_BIN = None
if os.path.isdir(JDK17_ROOT):
    for d in os.listdir(JDK17_ROOT):
        cand = os.path.join(JDK17_ROOT, d, "bin")
        if os.path.isfile(os.path.join(cand, "javac.exe")):
            JDK_BIN = cand
            break
if not JDK_BIN:
    raise RuntimeError("JDK 17 bin directory not found under " + JDK17_ROOT)

AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8_JAR = os.path.join(BUILD_TOOLS, "lib", "d8.jar")
CORE_LAMBDA_STUBS = os.path.join(BUILD_TOOLS, "core-lambda-stubs.jar")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER_JAR = os.path.join(BUILD_TOOLS, "lib", "apksigner.jar")
JAVAC = os.path.join(JDK_BIN, "javac.exe")
JAVA = os.path.join(JDK_BIN, "java.exe")
KEYTOOL = os.path.join(JDK_BIN, "keytool.exe")


def run(cmd, desc):
    print(f"[BUILD] {desc}...")
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print(proc.stdout)
        print(proc.stderr)
        raise RuntimeError(f"Step failed ({proc.returncode}): {desc}")
    if proc.stdout.strip():
        print(proc.stdout.strip())


def ensure_keystore():
    if os.path.exists(KEYSTORE):
        return
    run([
        KEYTOOL, "-genkeypair", "-v",
        "-keystore", KEYSTORE,
        "-alias", "roadpulse",
        "-keyalg", "RSA", "-keysize", "2048", "-validity", "9125",
        "-storepass", "roadpulse123", "-keypass", "roadpulse123",
        "-dname", "CN=RoadPulse Lite, O=RoadPulse, L=Hanoi, C=VN"
    ], "Generating persistent signing keystore")


def main():
    rpoi = os.path.join(ASSETS_DIR, "roadpulse_vn.rpoi")
    if not os.path.exists(rpoi):
        raise FileNotFoundError("Missing POI database: " + rpoi)

    if os.path.exists(BUILD_DIR):
        shutil.rmtree(BUILD_DIR)
    os.makedirs(BUILD_DIR, exist_ok=True)
    os.makedirs(DIST_DIR, exist_ok=True)

    res_flat_zip = os.path.join(BUILD_DIR, "res.zip")
    gen_java_dir = os.path.join(BUILD_DIR, "gen")
    classes_dir = os.path.join(BUILD_DIR, "classes")
    dex_dir = os.path.join(BUILD_DIR, "dex")
    os.makedirs(gen_java_dir, exist_ok=True)
    os.makedirs(classes_dir, exist_ok=True)
    os.makedirs(dex_dir, exist_ok=True)

    unaligned_apk = os.path.join(BUILD_DIR, "app-unaligned.apk")
    aligned_apk = os.path.join(BUILD_DIR, "app-aligned.apk")
    final_apk = os.path.join(DIST_DIR, "RoadPulseLite-v1.1.apk")

    # 1. Compile Android resources with aapt2
    run([AAPT2, "compile", "--dir", RES_DIR, "-o", res_flat_zip], "Compiling resources (aapt2 compile)")

    # 2. Link resources + manifest + assets (-0 .rpoi keeps binary DB uncompressed for zero-copy mmap!)
    run([
        AAPT2, "link",
        "-I", ANDROID_JAR,
        "--manifest", MANIFEST,
        "-A", ASSETS_DIR,
        "-0", ".rpoi",
        "--java", gen_java_dir,
        "--min-sdk-version", "26",
        "--target-sdk-version", "34",
        "-o", unaligned_apk,
        res_flat_zip
    ], "Linking APK base & generating R.java (aapt2 link)")

    # 3. Gather all .java files (src + generated R.java)
    java_files = []
    for base in (SRC_DIR, gen_java_dir):
        for r, _, files in os.walk(base):
            for fn in files:
                if fn.endswith(".java"):
                    java_files.append(os.path.join(r, fn))

    # 4. Compile Java 8 bytecode against android-34 + core-lambda-stubs
    boot_cp = ANDROID_JAR + os.pathsep + CORE_LAMBDA_STUBS
    run([
        JAVAC,
        "-encoding", "UTF-8",
        "-source", "8", "-target", "8",
        "-Xlint:-options", "-Xlint:unchecked",
        "-bootclasspath", boot_cp,
        "-classpath", ANDROID_JAR,
        "-d", classes_dir
    ] + java_files, f"Compiling {len(java_files)} Java sources (javac)")

    # 5. DEX + shrink/optimize bytecode with R8/D8 in release mode
    class_files = []
    for r, _, files in os.walk(classes_dir):
        for fn in files:
            if fn.endswith(".class"):
                class_files.append(os.path.join(r, fn))

    run([
        JAVA, "-cp", D8_JAR, "com.android.tools.r8.D8",
        "--release",
        "--min-api", "26",
        "--lib", ANDROID_JAR,
        "--output", dex_dir
    ] + class_files, f"Dexing {len(class_files)} classes (D8 release)")

    # 6. Inject classes.dex into unaligned APK cleanly (stored or deflated)
    import zipfile
    dex_path = os.path.join(dex_dir, "classes.dex")
    with zipfile.ZipFile(unaligned_apk, "a", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.write(dex_path, "classes.dex")

    # 7. Zipalign (4-byte alignment, also keeps uncompressed assets 4-byte aligned for mmap)
    run([ZIPALIGN, "-f", "-p", "4", unaligned_apk, aligned_apk], "Zipaligning APK (4-byte boundary)")

    # 8. Sign APK (v2 + v3 scheme for Android 8 - 15)
    ensure_keystore()
    run([
        JAVA, "-jar", APKSIGNER_JAR, "sign",
        "--ks", KEYSTORE,
        "--ks-pass", "pass:roadpulse123",
        "--ks-key-alias", "roadpulse",
        "--key-pass", "pass:roadpulse123",
        "--out", final_apk,
        aligned_apk
    ], "Signing release APK (apksigner)")

    # 9. Verify signature
    run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--print-certs", final_apk], "Verifying APK signature")

    # Size breakdown
    apk_size = os.path.getsize(final_apk)
    with zipfile.ZipFile(final_apk, "r") as zf:
        dex_z = zf.getinfo("classes.dex").compress_size
        rpoi_z = zf.getinfo("assets/roadpulse_vn.rpoi").file_size
    code_overhead = apk_size - rpoi_z
    print(f"\n=== BUILD SUCCESS ===")
    print(f"APK Path      : {final_apk}")
    print(f"Total APK Size: {apk_size:,} bytes ({apk_size / 1024:.1f} KB)")
    print(f"Code+Res Only : {code_overhead:,} bytes ({code_overhead / 1024:.1f} KB)")
    print(f"DEX Compressed: {dex_z:,} bytes ({dex_z / 1024:.1f} KB)")
    print(f"Offline .rpoi : {rpoi_z:,} bytes ({rpoi_z / 1024:.1f} KB)")


if __name__ == "__main__":
    main()

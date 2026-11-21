#!/usr/bin/env python3
# c:\Users\Acer\Downloads\gsm\roadpulse_lite\tools\auto_doctor.py
# Python 3.12 / Master 4-Gate Self-Check, Bytecode API 26..34 Auditor, 100k Fuzz Runner & APK Verifier
import os
import re
import struct
import subprocess
import sys
import zipfile
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
TOOLS = os.path.join(ROOT, "tools")
APP_JAVA = os.path.join(ROOT, "app", "src", "main", "java", "vn", "roadpulse", "lite")
RPOI = os.path.join(ROOT, "app", "assets", "roadpulse_vn.rpoi")
APK = os.path.join(ROOT, "dist", "RoadPulseLite-v1.1.apk")

LOCAL_APP = os.environ.get("LOCALAPPDATA", r"C:\Users\Acer\AppData\Local")
SDK_DIR = os.path.join(LOCAL_APP, "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK_DIR, "build-tools", "34.0.0")
ANDROID_26_JAR = os.path.join(SDK_DIR, "platforms", "android-26", "android.jar")
ANDROID_34_JAR = os.path.join(SDK_DIR, "platforms", "android-34", "android.jar")
CORE_LAMBDA_STUBS = os.path.join(BUILD_TOOLS, "core-lambda-stubs.jar")
ADB = os.path.join(SDK_DIR, "platform-tools", "adb.exe")

JDK17_ROOT = os.path.join(LOCAL_APP, "Android", "jdk17")
JDK_BIN = None
for d in os.listdir(JDK17_ROOT):
    cand = os.path.join(JDK17_ROOT, d, "bin")
    if os.path.isfile(os.path.join(cand, "java.exe")):
        JDK_BIN = cand
        break

JAVAC = os.path.join(JDK_BIN, "javac.exe")
JAVA = os.path.join(JDK_BIN, "java.exe")
JAVAP = os.path.join(JDK_BIN, "javap.exe")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER_JAR = os.path.join(BUILD_TOOLS, "lib", "apksigner.jar")

# Explicitly guarded post-API-26 calls (verified to sit inside Build.VERSION.SDK_INT >= Q/TIRAMISU branches)
ALLOWED_GUARDED_REFS = {
    "android/app/Service.startForeground:(ILandroid/app/Notification;I)V": "Build.VERSION_CODES.Q",
}


def run(cmd, desc):
    print(f"\n[DOCTOR] {desc}...")
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.stdout.strip():
        print(p.stdout.strip())
    if p.stderr.strip():
        print(p.stderr.strip())
    if p.returncode != 0:
        raise RuntimeError(f"GATE FAILED ({p.returncode}): {desc}")
    return p.stdout


def gate2_validate_rpoi_binary():
    print("\n=== GATE 2: Validating .rpoi v2 Binary Integrity & CRC32 ===")
    with open(RPOI, "rb") as f:
        raw = f.read()
    assert len(raw) >= 40, "File smaller than 40-byte v2 header"
    magic, ver, csize, mlat, mlon, ccount, rcount, roff, soff, bts, pcrc = struct.unpack_from("<4sHHiiIIIIII", raw, 0)
    assert magic == b"RPOI", f"Bad magic: {magic}"
    assert ver == 2, f"Expected version 2, got {ver}"
    actual_crc = zlib.crc32(raw[40:]) & 0xFFFFFFFF
    assert actual_crc == pcrc, f"CRC32 mismatch: expected {pcrc:#x}, got {actual_crc:#x}"
    assert roff == 40 + ccount * 12, "Bad records_offset"
    assert soff == roff + rcount * 24, "Bad string_pool_offset"
    assert soff < len(raw), "Empty string pool"

    prev_key = -1
    total_in_cells = 0
    for i in range(ccount):
        ckey, st, cnt = struct.unpack_from("<III", raw, 40 + i * 12)
        assert ckey > prev_key, f"Non-monotonic cell_key at {i}: {ckey} <= {prev_key}"
        assert st + cnt <= rcount, f"Cell overflow at {i}"
        prev_key = ckey
        total_in_cells += cnt
    assert total_in_cells == rcount, f"Cell record sum {total_in_cells} != {rcount}"

    # Verify every record's UTF-8 string slice
    for i in range(rcount):
        lat, lon, hdg, tol, cat, spd, flg, slen, off, pid = struct.unpack_from("<iiHBBBBHII", raw, roff + i * 24)
        assert 1 <= cat <= 8, f"Invalid category {cat} at record {i}"
        assert slen > 0, f"Empty label at record {i}"
        assert soff + off + slen <= len(raw), f"String out of bounds at record {i}"
        s = raw[soff + off: soff + off + slen].decode("utf-8")
        assert s.strip() and s.strip().lower() != "yes", f"Noisy/blank label '{s}' at record {i}"

    print(f"[PASS] Gate 2: {rcount} records across {ccount} sorted cells, CRC32={pcrc:#010x} verified 100%!")


def parse_classfile_bytes(data: bytes):
    """Parses JVM .class constant pool, super_class, interfaces, declared methods, and referenced Methodrefs."""
    magic, minor, major, cp_count = struct.unpack_from(">IHHH", data, 0)
    assert magic == 0xCAFEBABE
    pos = 10
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:  # Utf8
            ln = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp[i] = ("utf8", data[pos:pos + ln].decode("utf-8", errors="replace"))
            pos += ln
        elif tag in (7, 8, 16, 19, 20):  # Class, String, MethodType, Module, Package
            idx = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            cp[i] = (tag, idx)
        elif tag in (9, 10, 11, 12, 17, 18):  # Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic, InvokeDynamic
            a, b = struct.unpack_from(">HH", data, pos)
            pos += 4
            cp[i] = (tag, a, b)
        elif tag in (3, 4):  # Integer, Float
            pos += 4
            cp[i] = (tag,)
        elif tag in (5, 6):  # Long, Double (takes 2 slots)
            pos += 8
            cp[i] = (tag,)
            i += 1
        elif tag == 15:  # MethodHandle
            pos += 3
            cp[i] = (tag,)
        else:
            raise ValueError(f"Unknown CP tag {tag}")
        i += 1

    def utf8(idx):
        return cp[idx][1] if idx and cp[idx] else ""

    def cls_name(idx):
        return utf8(cp[idx][1]) if idx and cp[idx] else None

    access, this_idx, super_idx, iface_cnt = struct.unpack_from(">HHHH", data, pos)
    pos += 8
    this_cls = cls_name(this_idx)
    super_cls = cls_name(super_idx)
    ifaces = []
    for _ in range(iface_cnt):
        ifaces.append(cls_name(struct.unpack_from(">H", data, pos)[0]))
        pos += 2

    # Skip fields
    f_cnt = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    for _ in range(f_cnt):
        _, _, _, a_cnt = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        for _ in range(a_cnt):
            _, a_len = struct.unpack_from(">HI", data, pos)
            pos += 6 + a_len

    # Read declared methods
    m_cnt = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    declared_methods = set()
    for _ in range(m_cnt):
        _, n_idx, d_idx, a_cnt = struct.unpack_from(">HHHH", data, pos)
        pos += 8
        declared_methods.add((utf8(n_idx), utf8(d_idx)))
        for _ in range(a_cnt):
            _, a_len = struct.unpack_from(">HI", data, pos)
            pos += 6 + a_len

    # Collect referenced Methodrefs (10) & InterfaceMethodrefs (11)
    method_refs = set()
    for entry in cp:
        if entry and entry[0] in (10, 11):
            target_cls = cls_name(entry[1])
            nat = cp[entry[2]]
            m_name = utf8(nat[1])
            m_desc = utf8(nat[2])
            method_refs.add((target_cls, m_name, m_desc))

    return this_cls, super_cls, ifaces, declared_methods, method_refs


def gate1_scan_api26_bytecode_and_ondraw_allocs():
    print("\n=== GATE 1 & 4a: Exact JVM Constant-Pool + Hierarchy API 26 Scanner & onDraw Audit ===")
    classes_root = os.path.join(ROOT, "build", "classes", "vn", "roadpulse", "lite")
    class_files = [os.path.join(classes_root, f) for f in sorted(os.listdir(classes_root)) if f.endswith(".class")]
    assert class_files, "No compiled .class files found!"

    app_refs = set()
    for cf in class_files:
        with open(cf, "rb") as f:
            _, _, _, _, refs = parse_classfile_bytes(f.read())
        for t_cls, m_name, m_desc in refs:
            if not t_cls or t_cls.startswith("vn/roadpulse/lite") or t_cls.startswith("["):
                continue
            if t_cls.startswith("android/") or t_cls.startswith("java/"):
                app_refs.add((t_cls, m_name, m_desc))

    with zipfile.ZipFile(ANDROID_26_JAR, "r") as zf, zipfile.ZipFile(CORE_LAMBDA_STUBS, "r") as lzf:
        jar_entries = set(zf.namelist())
        stub_entries = set(lzf.namelist())
        parsed_cache = {}

        def get_api26_class(cname):
            if cname in parsed_cache:
                return parsed_cache[cname]
            entry = cname + ".class"
            if entry in jar_entries:
                info = parse_classfile_bytes(zf.read(entry))
            elif entry in stub_entries:
                info = parse_classfile_bytes(lzf.read(entry))
            else:
                parsed_cache[cname] = None
                return None
            parsed_cache[cname] = info
            return info

        def has_method_in_hierarchy(cname, m_name, m_desc):
            info = get_api26_class(cname)
            if info is None:
                return False
            _, sup, ifaces, decls, _ = info
            if (m_name, m_desc) in decls:
                return True
            if m_name == "<init>":
                return False
            if sup and has_method_in_hierarchy(sup, m_name, m_desc):
                return True
            for iface in ifaces:
                if iface and has_method_in_hierarchy(iface, m_name, m_desc):
                    return True
            return False

        missing_classes = []
        unverified_methods = []
        for t_cls, m_name, m_desc in sorted(app_refs):
            if get_api26_class(t_cls) is None:
                missing_classes.append(t_cls)
                continue
            if not has_method_in_hierarchy(t_cls, m_name, m_desc):
                full_sig = f"{t_cls}.{m_name}:{m_desc}"
                if full_sig in ALLOWED_GUARDED_REFS:
                    print(f"  [GUARDED OK] {full_sig} -> guarded by {ALLOWED_GUARDED_REFS[full_sig]}")
                else:
                    unverified_methods.append(full_sig)

    if missing_classes:
        raise RuntimeError(f"Classes missing from Android API 26: {sorted(set(missing_classes))}")
    if unverified_methods:
        raise RuntimeError(f"Unguarded post-API-26 method calls detected: {unverified_methods}")

    print(f"[PASS] Gate 1: Verified {len(app_refs)} unique SDK method calls across full superclass/interface hierarchy against Android 8.0 (API 26)!")

    # 2. Audit HudOverlayView.onDraw for heavy object allocations (Paint/RectF/Path/Formatter)
    hud_javap = subprocess.run(
        [JAVAP, "-c", "-p", os.path.join(classes_root, "HudOverlayView.class")],
        capture_output=True, text=True, encoding="utf-8", errors="replace"
    ).stdout
    in_ondraw = False
    forbidden_news = []
    for line in hud_javap.splitlines():
        if "protected void onDraw(android.graphics.Canvas);" in line:
            in_ondraw = True
            continue
        if in_ondraw and line.startswith("  ") and not line.startswith("    ") and line.strip().endswith(";"):
            break
        if in_ondraw and re.search(r":\s+new\s+#", line):
            if "StringBuilder" not in line:
                forbidden_news.append(line.strip())
    if forbidden_news:
        raise RuntimeError(f"Heavy object allocation found inside HudOverlayView.onDraw: {forbidden_news}")
    print("[PASS] Gate 4a: HudOverlayView.onDraw confirmed zero Paint/RectF/Path/Formatter allocations!")


def gate3_run_fuzz_and_fault_suite():
    print("\n=== GATE 3: Compiling & Running 100,000-Case Fuzz + Fault Injection Suite ===")
    test_classes = os.path.join(ROOT, "build", "fuzz_classes")
    os.makedirs(test_classes, exist_ok=True)
    boot_cp = ANDROID_34_JAR + os.pathsep + CORE_LAMBDA_STUBS
    run([
        JAVAC, "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-Xlint:-options", "-Xlint:unchecked",
        "-bootclasspath", boot_cp, "-classpath", ANDROID_34_JAR,
        "-d", test_classes,
        os.path.join(APP_JAVA, "UserPinStore.java"),
        os.path.join(APP_JAVA, "SpatialRadarEngine.java"),
        os.path.join(APP_JAVA, "VoiceAlertManager.java"),
        os.path.join(APP_JAVA, "HudOverlayView.java"),
        os.path.join(TOOLS, "RadarFuzzAndFaultHarness.java"),
    ], "Compiling Fuzz & Fault Injection Harness")

    run([
        JAVA, "-cp", test_classes + os.pathsep + ANDROID_34_JAR,
        "vn.roadpulse.lite.RadarFuzzAndFaultHarness", RPOI
    ], "Executing 100,000 GPS Fuzz + Binary Fault Injection + TSV Self-Heal + Corridor Replay")


def gate4_verify_apk_and_optional_adb():
    print("\n=== GATE 4b: Verifying APK Packaging, Alignment, Signature & Size Budget ===")
    run([AAPT2, "dump", "badging", APK], "Inspecting APK badging")
    run([ZIPALIGN, "-c", "-v", "4", APK], "Verifying 4-byte zipalign")
    run([JAVA, "-jar", APKSIGNER_JAR, "verify", "--print-certs", APK], "Verifying v2/v3 APK signature")

    apk_sz = os.path.getsize(APK)
    assert apk_sz < 300_000, f"APK exceeded 300 KB budget: {apk_sz}"
    with zipfile.ZipFile(APK, "r") as zf:
        ri = zf.getinfo("assets/roadpulse_vn.rpoi")
        assert ri.compress_type == zipfile.ZIP_STORED, "assets/roadpulse_vn.rpoi must be ZIP_STORED for zero-copy mmap!"
        dex_sz = zf.getinfo("classes.dex").compress_size
    print(f"[PASS] Gate 4b: APK={apk_sz:,} bytes ({apk_sz/1024:.1f} KB), DEX={dex_sz/1024:.1f} KB, .rpoi=ZIP_STORED!")

    # Optional live ADB device test if connected
    if os.path.isfile(ADB):
        devs = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout.strip().splitlines()[1:]
        online = [d.split()[0] for d in devs if "\tdevice" in d]
        if online:
            serial = online[0]
            print(f"[ADB] Connected device detected ({serial}) — running live smoke test...")
            run([ADB, "-s", serial, "logcat", "-c"], "Clearing logcat")
            run([ADB, "-s", serial, "install", "-r", "-g", APK], "Installing APK with runtime permissions")
            run([ADB, "-s", serial, "shell", "am", "start", "-n", "vn.roadpulse.lite/.MainActivity"], "Launching MainActivity")
            err_log = subprocess.run([ADB, "-s", serial, "logcat", "-d", "AndroidRuntime:E", "*:S"], capture_output=True, text=True).stdout
            if "vn.roadpulse.lite" in err_log:
                raise RuntimeError(f"Runtime crash detected on device:\n{err_log}")
            print(f"[PASS] Live ADB smoke test passed on {serial} with 0 crashes!")
        else:
            print("[INFO] No physical ADB device attached right now (host JVM & bytecode gates passed 100%).")


def main():
    import compile_poi_pack
    import build_apk

    compile_poi_pack.main()
    gate2_validate_rpoi_binary()
    build_apk.main()
    gate1_scan_api26_bytecode_and_ondraw_allocs()
    gate3_run_fuzz_and_fault_suite()
    gate4_verify_apk_and_optional_adb()

    print("\n========================================================")
    print("  ROADPULSE LITE v1.1 AUTO-DOCTOR: ALL GATES 100% GREEN ")
    print("========================================================")


if __name__ == "__main__":
    main()

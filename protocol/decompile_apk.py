#!/usr/bin/env python3
"""
Meta View APK Decompilation & Analysis Helper
===============================================
Downloads, decompiles, and searches the Meta View APK for protocol clues.

Targets:
  - BLE characteristic UUIDs (05acbe9f, c53673dd, f9fbf15d)
  - Service UUID 0xFD5F
  - WiFi hotspot activation code
  - Media transfer HTTP endpoints
  - Authentication/bonding logic

Prerequisites:
  - Java 11+ (for jadx)
  - jadx: https://github.com/skylot/jadx/releases
    OR: winget install skylot.jadx

Usage:
  python decompile_apk.py download          # Download Meta View APK via apkmirror
  python decompile_apk.py decompile <apk>   # Decompile with jadx
  python decompile_apk.py search <dir>      # Search decompiled source for protocol clues
  python decompile_apk.py full <apk>        # Decompile + search in one step
"""

import argparse
import os
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

# Patterns to search for in decompiled source
SEARCH_PATTERNS = {
    # BLE UUIDs from GATT map
    "BLE Command Characteristic": r"05acbe9f",
    "BLE Status Characteristic": r"c53673dd",
    "BLE Flags Characteristic": r"f9fbf15d",
    "Meta BLE Service UUID": r"[0-9a-fA-F]{4}fd5f|0xfd5f|0xFD5F",
    "BLE Manufacturer ID": r"0x01[aA][bB]|0x01AB",

    # GATT operations
    "writeCharacteristic": r"writeCharacteristic|writeDescriptor|BluetoothGattCharacteristic",
    "BLE Notification Enable": r"ENABLE_NOTIFICATION|ENABLE_INDICATION|0x2902|CLIENT_CHARACTERISTIC_CONFIG",
    "BLE Bonding": r"createBond|BOND_BONDED|ACTION_BOND_STATE_CHANGED",

    # WiFi hotspot
    "WiFi Connect": r"WifiNetworkSpecifier|WifiManager|addNetwork|enableNetwork",
    "WiFi Config": r"WifiConfiguration|WPA2|preSharedKey|SSID",
    "WiFi Direct": r"WifiP2pManager|WIFI_P2P|createGroup|p2pConnect",
    "Hotspot SSID Pattern": r"RB.?Meta|Ray.?Ban|FB.?Meta|meta.?glasses",

    # Media transfer
    "HTTP Client": r"OkHttpClient|HttpURLConnection|Retrofit|Volley",
    "Media Endpoints": r"/api/media|/api/files|/api/photos|/api/videos|/media/|/transfer/|/sync/",
    "File Download": r"InputStream|FileOutputStream|downloadFile|saveMedia|writeToFile",
    "Content Type Media": r"image/jpeg|video/mp4|application/octet-stream",
    "DCIM Path": r"DCIM|Camera|Pictures|Movies",

    # Protocol/crypto
    "AES/Encryption": r"AES|Cipher|SecretKey|encrypt|decrypt|PBKDF",
    "TLS/SSL": r"SSLSocket|TrustManager|X509|certificate|pinning",
    "Protobuf": r"protobuf|\.proto|GeneratedMessageV3|parseFrom|writeTo",
    "CBOR/MessagePack": r"CBOR|MessagePack|msgpack",

    # Meta-specific
    "Meta SDK": r"com\.facebook\.wearable|com\.meta\.glasses|com\.oculus",
    "DAT SDK": r"DirectAccessTechnology|dat_sdk|DatManager",
    "Firmware Update": r"firmwareUpdate|OTA|updateFirmware|fwVersion",
}

# Files/directories to skip
SKIP_DIRS = {
    "android", "androidx", "kotlin", "kotlinx", "com/google",
    "okhttp3", "retrofit2", "dagger", "javax",
    "org/apache", "org/json", "com/squareup",
}


def find_jadx() -> str | None:
    """Find jadx executable."""
    # Check PATH
    for name in ["jadx", "jadx.bat"]:
        result = subprocess.run(
            ["where" if sys.platform == "win32" else "which", name],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            return result.stdout.strip().splitlines()[0]

    # Check common install locations (Windows)
    common_paths = [
        Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "jadx" / "bin" / "jadx.bat",
        Path(os.environ.get("PROGRAMFILES", "")) / "jadx" / "bin" / "jadx.bat",
        Path.home() / "jadx" / "bin" / "jadx.bat",
    ]
    for p in common_paths:
        if p.exists():
            return str(p)

    return None


def cmd_download():
    """Guide for downloading Meta View APK."""
    print("""
=== Download Meta View APK ===

Option 1: From your phone (recommended - gets your exact version)
  adb shell pm path com.facebook.wearable.view
  # Output: package:/data/app/.../base.apk
  adb pull <path_from_above> metaview.apk

Option 2: From APKMirror (manual download)
  1. Go to: https://www.apkmirror.com/apk/meta-platforms-inc/meta-view/
  2. Download the latest version (arm64-v8a variant for S23 Ultra)
  3. Save as metaview.apk in this directory

Option 3: From phone storage
  adb shell pm list packages | findstr meta
  adb shell pm path com.facebook.wearable.view
  adb pull /data/app/~~xxxxx/com.facebook.wearable.view-xxxxx/base.apk metaview.apk

After downloading, run:
  python decompile_apk.py decompile metaview.apk
""")


def cmd_decompile(apk_path: str, output_dir: str | None = None):
    """Decompile APK with jadx."""
    apk = Path(apk_path)
    if not apk.exists():
        print(f"APK not found: {apk_path}")
        print("Run 'python decompile_apk.py download' for instructions.")
        sys.exit(1)

    jadx = find_jadx()
    if not jadx:
        print("jadx not found!")
        print("Install: winget install skylot.jadx")
        print("Or download from: https://github.com/skylot/jadx/releases")
        sys.exit(1)

    if output_dir is None:
        output_dir = str(apk.stem) + "_decompiled"

    out = Path(output_dir)
    print(f"Decompiling {apk.name} -> {out}/")
    print(f"Using jadx: {jadx}")
    print("This may take several minutes...")

    result = subprocess.run(
        [jadx, "-d", str(out), "--deobf", str(apk)],
        capture_output=False,
        timeout=600,
    )

    if result.returncode == 0:
        print(f"\nDecompilation complete: {out}/")
        # Count files
        java_files = list(out.rglob("*.java"))
        print(f"  Java files: {len(java_files)}")
        return str(out)
    else:
        print(f"\njadx exited with code {result.returncode}")
        return None


def should_skip(filepath: str) -> bool:
    """Check if file is in a directory we should skip."""
    for skip in SKIP_DIRS:
        if skip in filepath:
            return True
    return False


def cmd_search(source_dir: str, output_file: str | None = None):
    """Search decompiled source for protocol-relevant patterns."""
    src = Path(source_dir)
    if not src.exists():
        print(f"Directory not found: {source_dir}")
        sys.exit(1)

    # Find all Java/Kotlin/Smali source files
    extensions = {".java", ".kt", ".smali", ".xml", ".json"}
    files = []
    for ext in extensions:
        files.extend(src.rglob(f"*{ext}"))

    print(f"\nSearching {len(files)} files in {src}/...")
    print(f"Patterns: {len(SEARCH_PATTERNS)}\n")

    results = defaultdict(list)
    file_hits = defaultdict(set)

    for filepath in files:
        rel = str(filepath.relative_to(src))

        # Skip third-party libraries
        if should_skip(rel):
            continue

        try:
            content = filepath.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue

        for pattern_name, regex in SEARCH_PATTERNS.items():
            for match in re.finditer(regex, content, re.IGNORECASE):
                # Get line number and context
                start = match.start()
                line_num = content[:start].count("\n") + 1
                line_start = content.rfind("\n", 0, start) + 1
                line_end = content.find("\n", start)
                if line_end == -1:
                    line_end = len(content)
                line_text = content[line_start:line_end].strip()

                hit = {
                    "file": rel,
                    "line": line_num,
                    "match": match.group(),
                    "context": line_text[:200],
                }
                results[pattern_name].append(hit)
                file_hits[rel].add(pattern_name)

    # Print results
    print("=" * 70)
    print("  SEARCH RESULTS")
    print("=" * 70)

    total_hits = sum(len(v) for v in results.values())
    print(f"\n  Total hits: {total_hits}")
    print(f"  Files with hits: {len(file_hits)}\n")

    # Summary by category
    print("  Hits by pattern:")
    for name in sorted(results.keys(), key=lambda k: len(results[k]), reverse=True):
        hits = results[name]
        print(f"    {name:<40} {len(hits):>4} hits")

    # Most interesting files (files with many different pattern hits)
    print("\n  Most interesting files (by pattern diversity):")
    sorted_files = sorted(file_hits.items(), key=lambda x: len(x[1]), reverse=True)
    for filepath, patterns in sorted_files[:20]:
        print(f"    [{len(patterns):>2} patterns] {filepath}")
        for p in sorted(patterns):
            print(f"                   - {p}")

    # Detail for key patterns
    key_patterns = [
        "BLE Command Characteristic",
        "BLE Status Characteristic",
        "Meta BLE Service UUID",
        "WiFi Connect",
        "Hotspot SSID Pattern",
        "Media Endpoints",
        "Protobuf",
        "Meta SDK",
        "DAT SDK",
    ]
    print("\n" + "=" * 70)
    print("  KEY FINDINGS (detailed)")
    print("=" * 70)

    for pattern_name in key_patterns:
        hits = results.get(pattern_name, [])
        if not hits:
            continue
        print(f"\n  --- {pattern_name} ({len(hits)} hits) ---")
        # Show unique files and first few lines
        shown = set()
        for hit in hits[:10]:
            key = (hit["file"], hit["line"])
            if key in shown:
                continue
            shown.add(key)
            print(f"    {hit['file']}:{hit['line']}")
            print(f"      {hit['context'][:120]}")

    # Save full results
    if output_file:
        import json
        out = Path(output_file)
        export = {
            "source_dir": str(src),
            "total_hits": total_hits,
            "files_with_hits": len(file_hits),
            "results": {k: v for k, v in results.items()},
            "interesting_files": [
                {"file": f, "patterns": list(p)}
                for f, p in sorted_files[:50]
            ],
        }
        out.write_text(json.dumps(export, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\n  Full results saved to: {out}")

    return results


def cmd_full(apk_path: str):
    """Decompile + search in one step."""
    output_dir = cmd_decompile(apk_path)
    if output_dir:
        src_dir = Path(output_dir) / "sources"
        if not src_dir.exists():
            src_dir = Path(output_dir)
        results_file = Path(output_dir).parent / "apk_analysis.json"
        cmd_search(str(src_dir), str(results_file))


def main():
    parser = argparse.ArgumentParser(description="Meta View APK Decompilation Helper")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("download", help="Instructions to download Meta View APK")

    p_dec = sub.add_parser("decompile", help="Decompile APK with jadx")
    p_dec.add_argument("apk", help="Path to Meta View APK file")
    p_dec.add_argument("-o", "--output", help="Output directory")

    p_search = sub.add_parser("search", help="Search decompiled source")
    p_search.add_argument("dir", help="Path to decompiled source directory")
    p_search.add_argument("-o", "--output", help="Export results to JSON")

    p_full = sub.add_parser("full", help="Decompile + search (one step)")
    p_full.add_argument("apk", help="Path to Meta View APK file")

    args = parser.parse_args()

    if args.command == "download":
        cmd_download()
    elif args.command == "decompile":
        cmd_decompile(args.apk, args.output)
    elif args.command == "search":
        cmd_search(args.dir, args.output)
    elif args.command == "full":
        cmd_full(args.apk)


if __name__ == "__main__":
    main()

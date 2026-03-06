#!/usr/bin/env python3
"""
Protocol Capture Guide & Helper
================================
Interactive guide for capturing the BLE + WiFi protocol from Meta View app.
Also provides tools to pull and pre-process capture files.

Usage:
  python capture_guide.py guide       # Step-by-step capture instructions
  python capture_guide.py pull        # Pull btsnoop_hci.log via ADB
  python capture_guide.py analyze     # Quick analysis of captured files
"""

import argparse
import subprocess
import sys
from datetime import datetime
from pathlib import Path

CAPTURE_DIR = Path(__file__).parent / "captures"


def print_step(n: int, title: str, instructions: list[str]):
    print(f"\n{'='*60}")
    print(f"  ETAPE {n}: {title}")
    print(f"{'='*60}")
    for i, inst in enumerate(instructions, 1):
        print(f"  {i}. {inst}")
    input(f"\n  >> Appuyez sur Entree quand c'est fait...")


def guide():
    """Interactive step-by-step capture guide."""
    print("""
╔══════════════════════════════════════════════════════════╗
║  CAPTURE DU PROTOCOLE RAY-BAN META V1                   ║
║  Guide interactif de reverse-engineering                 ║
╚══════════════════════════════════════════════════════════╝

PREREQUIS:
  - Samsung S23 Ultra avec Options developpeur activees
  - PCAPdroid installe (Play Store, gratuit)
  - nRF Connect installe (Play Store, gratuit)
  - Meta View reinstalle (Play Store) MAIS PAS ENCORE OUVERT
  - Lunettes Ray-Ban Meta V1 avec des photos/videos dessus
  - Cable USB-C pour connecter le S23 au PC
  - ADB installe sur le PC (winget install Google.PlatformTools)
""")
    input("  >> Appuyez sur Entree pour commencer...")

    ts_start = datetime.now().strftime("%H:%M:%S")
    print(f"\n  Heure de debut: {ts_start}")

    print_step(1, "PREPARATION DU TELEPHONE", [
        "S23 Ultra: Parametres > Options developpeur",
        "Activer 'Journal snoop HCI Bluetooth'",
        "Desactiver le Bluetooth completement",
        "Attendre 3 secondes",
        "Reactiver le Bluetooth (cela demarre un log frais)",
    ])

    print_step(2, "DEMARRER LA CAPTURE RESEAU", [
        "Ouvrir PCAPdroid sur le S23 Ultra",
        "Configurer: Mode = VPN (pas de root)",
        "Configurer: Capture tous les paquets",
        "Appuyer sur le bouton Start/Demarrer",
        "Verifier que l'icone VPN apparait dans la barre de notification",
    ])

    print_step(3, "OPTIONNEL - nRF Connect (capture BLE secondaire)", [
        "Ouvrir nRF Connect",
        "Scanner > chercher 'RB Meta' ou 'Ray-Ban'",
        "NOTER l'adresse MAC BLE affichee",
        "NE PAS se connecter (juste noter l'adresse)",
        "Fermer nRF Connect",
    ])

    mac_addr = input("  >> Entrez l'adresse MAC BLE (ou Entree pour passer): ").strip()
    if mac_addr:
        print(f"  MAC notee: {mac_addr}")

    print_step(4, "APPAIRER AVEC META VIEW", [
        "Ouvrir Meta View pour la premiere fois",
        "Suivre l'assistant d'appairage",
        "Appairer les lunettes 'RB Meta 00WJ'",
        "ATTENDRE 30 secondes apres l'appairage (baseline)",
        f"NOTER L'HEURE: _______ (heure actuelle: {datetime.now().strftime('%H:%M:%S')})",
    ])

    ts_paired = input("  >> Heure de l'appairage (HH:MM:SS): ").strip()

    print_step(5, "DECLENCHER L'IMPORT MEDIA", [
        "Mettre les lunettes dans le boitier de charge",
        "OU: dans Meta View, chercher un bouton 'Importer' / 'Import'",
        "OBSERVER: le telephone devrait se connecter a un reseau WiFi temporaire",
        "NOTER LE NOM DU RESEAU WIFI (SSID) qui apparait",
        f"NOTER L'HEURE: _______ (heure actuelle: {datetime.now().strftime('%H:%M:%S')})",
        "Attendre que le transfert se termine completement",
    ])

    ts_import = input("  >> Heure du debut d'import (HH:MM:SS): ").strip()
    wifi_ssid = input("  >> SSID du WiFi des lunettes: ").strip()

    print_step(6, "ARRETER LES CAPTURES", [
        "Attendre que l'import soit 100% termine",
        "Arreter PCAPdroid (bouton Stop)",
        "Exporter le PCAP: PCAPdroid > Sauvegarder > fichier .pcap",
        f"NOTER L'HEURE de fin: {datetime.now().strftime('%H:%M:%S')}",
    ])

    print_step(7, "EXTRAIRE LES LOGS BT", [
        "Connecter le S23 Ultra au PC via USB-C",
        "Activer le debogage USB si demande",
        "Sur le PC, ouvrir un terminal et executer:",
        "  adb pull /data/misc/bluetooth/logs/btsnoop_hci.log captures/",
        "OU: Parametres > Options developpeur > Desactiver journal snoop HCI",
        "  Cela sauvegarde le log dans /sdcard/btsnoop_hci.log",
        "  adb pull /sdcard/btsnoop_hci.log captures/",
    ])

    print_step(8, "TRANSFERER LE PCAP", [
        "Copier le fichier .pcap de PCAPdroid vers le PC",
        "Via USB: Stockage interne > PCAPdroid > fichier.pcap",
        "OU: adb pull /sdcard/Download/PCAPdroid/ captures/",
        "Placer les fichiers dans le dossier 'captures/'",
    ])

    # Save capture metadata
    CAPTURE_DIR.mkdir(exist_ok=True)
    meta_file = CAPTURE_DIR / f"capture_meta_{datetime.now():%Y%m%d_%H%M%S}.txt"
    meta_content = f"""Capture Metadata
================
Date: {datetime.now().isoformat()}
Start time: {ts_start}
Pairing time: {ts_paired}
Import start time: {ts_import}
WiFi SSID: {wifi_ssid}
BLE MAC: {mac_addr}
Device: RB Meta 00WJ
Firmware: 61695970104300100
"""
    meta_file.write_text(meta_content, encoding="utf-8")

    print(f"""
╔══════════════════════════════════════════════════════════╗
║  CAPTURE TERMINEE!                                       ║
╠══════════════════════════════════════════════════════════╣
║  Metadata sauvee: {meta_file.name:<39}║
║  WiFi SSID: {wifi_ssid:<46}║
║                                                          ║
║  Prochaine etape:                                        ║
║    python capture_guide.py analyze                       ║
║                                                          ║
║  Fichiers a placer dans captures/:                       ║
║    - btsnoop_hci.log (log BLE)                          ║
║    - *.pcap (capture reseau PCAPdroid)                   ║
╚══════════════════════════════════════════════════════════╝
""")


def pull_btsnoop():
    """Pull btsnoop_hci.log from phone via ADB."""
    CAPTURE_DIR.mkdir(exist_ok=True)
    dest = CAPTURE_DIR / f"btsnoop_hci_{datetime.now():%Y%m%d_%H%M%S}.log"

    paths_to_try = [
        "/data/misc/bluetooth/logs/btsnoop_hci.log",
        "/sdcard/btsnoop_hci.log",
        "/data/log/bt/btsnoop_hci.log",
    ]

    for path in paths_to_try:
        print(f"Trying: adb pull {path}...")
        result = subprocess.run(
            ["adb", "pull", path, str(dest)],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            print(f"  OK! Saved to {dest}")
            print(f"  Size: {dest.stat().st_size:,} bytes")
            return
        else:
            print(f"  Failed: {result.stderr.strip()}")

    print("\nCould not pull btsnoop log. Try manually:")
    print("  1. Disable HCI snoop in Developer Options (saves to /sdcard/)")
    print("  2. adb pull /sdcard/btsnoop_hci.log captures/")


def analyze():
    """Quick analysis of captured files."""
    CAPTURE_DIR.mkdir(exist_ok=True)

    print("\n=== Capture Files ===")
    files = list(CAPTURE_DIR.iterdir())
    if not files:
        print("  No files in captures/ directory.")
        print("  Run the capture guide first: python capture_guide.py guide")
        return

    for f in sorted(files):
        size = f.stat().st_size
        print(f"  {f.name:<50} {size:>10,} bytes")

    # Check for btsnoop
    btsnoop_files = [f for f in files if "btsnoop" in f.name.lower()]
    pcap_files = [f for f in files if f.suffix.lower() in (".pcap", ".pcapng")]

    print(f"\n  BT snoop logs: {len(btsnoop_files)}")
    print(f"  PCAP files:    {len(pcap_files)}")

    if btsnoop_files:
        print("\n  Wireshark analysis commands:")
        for f in btsnoop_files:
            print(f"    wireshark {f}")
            print(f"    Filter: btatt.opcode == 0x12 || btatt.opcode == 0x52")
            print(f"    (0x12 = Write Request, 0x52 = Write Command)")

    if pcap_files:
        print("\n  Network analysis:")
        for f in pcap_files:
            print(f"    wireshark {f}")
            print(f"    Filter: http || tcp.port == 80 || tcp.port == 443 || tcp.port == 8080")

    # Check for metadata
    meta_files = [f for f in files if f.name.startswith("capture_meta")]
    if meta_files:
        latest = max(meta_files, key=lambda f: f.stat().st_mtime)
        print(f"\n  Latest capture metadata:")
        for line in latest.read_text(encoding="utf-8").splitlines():
            if line.strip():
                print(f"    {line}")


def main():
    parser = argparse.ArgumentParser(description="Protocol Capture Guide & Helper")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("guide", help="Interactive step-by-step capture guide")
    sub.add_parser("pull", help="Pull btsnoop_hci.log via ADB")
    sub.add_parser("analyze", help="Quick analysis of captured files")
    args = parser.parse_args()

    if args.command == "guide":
        guide()
    elif args.command == "pull":
        pull_btsnoop()
    elif args.command == "analyze":
        analyze()


if __name__ == "__main__":
    main()

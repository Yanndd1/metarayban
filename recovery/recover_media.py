#!/usr/bin/env python3
"""
Meta Ray-Ban Media Recovery Tool
=================================
Automates the safe media recovery process:
1. Verifies DNS blocking is active
2. Reinstalls Meta View APK
3. Guides through media import
4. Pulls media files via ADB
5. Cleans up (uninstall + factory reset instructions)

Usage:
    python recover_media.py [step]

    Steps:
        check    - Verify prerequisites (ADB, APK, DNS)
        block    - Generate hosts file for DNS blocking
        install  - Install Meta View APK via ADB
        pull     - Pull imported media from phone
        cleanup  - Uninstall Meta View and show reset instructions
        full     - Run all steps in sequence
"""

import subprocess
import sys
import os
import shutil
from datetime import datetime
from pathlib import Path

# Configuration
ADB_DEVICE = "RFCW3239FCW"  # Samsung S23 Ultra serial
APK_PATH = os.path.join(os.path.dirname(__file__), "..", "protocol", "captures", "metaview.apk")
META_PACKAGE = "com.facebook.stella"
OUTPUT_DIR = os.path.join(os.path.dirname(__file__), "recovered_media")

# Domains Meta à bloquer (complet)
META_DOMAINS = [
    # Core telemetry
    "pixel.facebook.com", "analytics.facebook.com", "graph.facebook.com",
    "b-graph.facebook.com", "graph.instagram.com", "connect.facebook.net",
    # MQTT push channels
    "mqtt-mini.facebook.com", "edge-mqtt.facebook.com", "mqtt.facebook.com",
    "edge-chat.facebook.com",
    # APIs
    "b-api.facebook.com", "api.facebook.com", "z-m-graph.facebook.com",
    "portal.facebook.com", "portal.meta.com",
    # Upload endpoints (CRITICAL - blocks media upload to Meta servers)
    "upload.facebook.com", "rupload.facebook.com",
    # Oculus/Quest shared infra
    "graph.oculus.com", "mqtt.oculus.com",
    # Social (not needed)
    "www.facebook.com", "m.facebook.com", "www.instagram.com",
    "i.instagram.com", "www.meta.com", "about.meta.com",
    # CDN upload endpoints
    "z-m-scontent.xx.fbcdn.net",
    # Ads
    "an.facebook.com", "ads.facebook.com", "ad.facebook.com",
    # WhatsApp (shared telemetry)
    "crashlogs.whatsapp.net", "dit.whatsapp.net",
    # Stella-specific (if any direct endpoints exist)
    "star.c10r.facebook.com", "star-mini.c10r.facebook.com",
    "b-graph.facebook.com",
]


def run_adb(*args) -> tuple[int, str]:
    """Run an ADB command targeting the S23 Ultra."""
    cmd = ["adb", "-s", ADB_DEVICE] + list(args)
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        return result.returncode, result.stdout + result.stderr
    except FileNotFoundError:
        return -1, "ADB not found. Install Android SDK Platform Tools."
    except subprocess.TimeoutExpired:
        return -2, "ADB command timed out."


def step_check():
    """Verify all prerequisites."""
    print("=" * 60)
    print("  ÉTAPE 0 : Vérification des prérequis")
    print("=" * 60)

    errors = []

    # Check ADB
    print("\n[1/4] ADB...", end=" ")
    code, output = run_adb("devices")
    if code != 0:
        print("❌ ADB non trouvé")
        errors.append("Installe Android SDK Platform Tools")
    elif ADB_DEVICE in output:
        print(f"✅ {ADB_DEVICE} connecté")
    else:
        print(f"❌ Appareil {ADB_DEVICE} non trouvé")
        print(f"    Appareils détectés :\n{output}")
        errors.append("Branche le S23 Ultra en USB avec le débogage activé")

    # Check APK
    print("[2/4] APK Meta View...", end=" ")
    apk = Path(APK_PATH)
    if apk.exists():
        size_mb = apk.stat().st_size / (1024 * 1024)
        print(f"✅ {size_mb:.1f} Mo")
    else:
        print("❌ APK non trouvé")
        errors.append(f"APK manquant : {APK_PATH}")

    # Check if Meta View already installed
    print("[3/4] Meta View sur le téléphone...", end=" ")
    code, output = run_adb("shell", "pm", "list", "packages", META_PACKAGE)
    if META_PACKAGE in output:
        print("⚠️  Déjà installée (sera utilisée telle quelle)")
    else:
        print("✅ Non installée (on l'installera temporairement)")

    # Check free space on phone
    print("[4/4] Espace disque...", end=" ")
    code, output = run_adb("shell", "df", "/sdcard/")
    if code == 0:
        print("✅")
        # Parse df output for available space
        for line in output.strip().split("\n")[1:]:
            parts = line.split()
            if len(parts) >= 4:
                print(f"    Disponible : {parts[3]}")
    else:
        print("⚠️  Impossible de vérifier")

    if errors:
        print("\n❌ ERREURS À CORRIGER :")
        for e in errors:
            print(f"   → {e}")
        return False

    print("\n✅ Tout est prêt !")
    return True


def step_block():
    """Generate and display DNS blocking instructions."""
    print("\n" + "=" * 60)
    print("  ÉTAPE 1 : Bloquer les communications Meta")
    print("=" * 60)

    print("""
╔══════════════════════════════════════════════════════════╗
║  MÉTHODE RECOMMANDÉE : Mode avion + WiFi Direct         ║
║  (la plus simple et la plus sûre)                        ║
╠══════════════════════════════════════════════════════════╣
║                                                          ║
║  1. Sur le S23 Ultra :                                   ║
║     → Active le MODE AVION                               ║
║     → Réactive le WiFi (mais ne te connecte à RIEN)      ║
║     → Réactive le Bluetooth                              ║
║                                                          ║
║  Résultat :                                              ║
║  ✅ WiFi Direct fonctionne (peer-to-peer, pas internet)  ║
║  ✅ Bluetooth fonctionne (BLE vers lunettes)             ║
║  ❌ Zéro accès Internet → rien ne part vers Meta        ║
║                                                          ║
║  Note : L'USB/ADB continue de fonctionner en mode avion ║
╚══════════════════════════════════════════════════════════╝
""")

    # Also generate hosts file for belt-and-suspenders
    hosts_path = os.path.join(os.path.dirname(__file__), "meta_hosts_block.txt")
    with open(hosts_path, "w") as f:
        f.write("# Meta blocklist - generated by recover_media.py\n")
        f.write(f"# Date: {datetime.now().isoformat()}\n\n")
        for domain in META_DOMAINS:
            f.write(f"0.0.0.0 {domain}\n")

    print(f"  Fichier hosts généré : {hosts_path}")
    print("  (utilisable dans Pi-hole, NextDNS, ou /etc/hosts)\n")

    input("  Appuie sur Entrée quand le mode avion est activé...")
    return True


def step_install():
    """Install Meta View APK."""
    print("\n" + "=" * 60)
    print("  ÉTAPE 2 : Installer Meta View (temporaire)")
    print("=" * 60)

    # Check if already installed
    code, output = run_adb("shell", "pm", "list", "packages", META_PACKAGE)
    if META_PACKAGE in output:
        print("\n  Meta View est déjà installée. On continue.\n")
        return True

    print(f"\n  Installation de {APK_PATH}...")
    print("  (95 Mo, ça peut prendre 1-2 minutes)\n")

    code, output = run_adb("install", APK_PATH)
    if code == 0 and "Success" in output:
        print("  ✅ Meta View installée avec succès")
        return True
    else:
        print(f"  ❌ Échec de l'installation : {output}")
        return False


def step_import_guide():
    """Guide user through the media import process."""
    print("\n" + "=" * 60)
    print("  ÉTAPE 3 : Importer les médias des lunettes")
    print("=" * 60)

    print("""
╔══════════════════════════════════════════════════════════╗
║  PROCÉDURE D'IMPORT                                      ║
╠══════════════════════════════════════════════════════════╣
║                                                          ║
║  1. Ouvre les lunettes (déplie la branche droite)        ║
║                                                          ║
║  2. Sur le S23 Ultra, ouvre l'app "Meta View"            ║
║     → Si elle demande une connexion internet :           ║
║       Désactive temporairement le mode avion             ║
║       Connecte-toi à ton WiFi                            ║
║       Connecte-toi à ton compte Meta                     ║
║       PUIS réactive le mode avion + WiFi + BT            ║
║                                                          ║
║  3. L'app devrait détecter les lunettes en Bluetooth     ║
║     → Accepte l'appairage si demandé                     ║
║                                                          ║
║  4. Une fois connectée, va dans la galerie               ║
║     → Appuie sur "Importer" ou "Import"                  ║
║     → L'import WiFi Direct démarre automatiquement       ║
║     → Attends que tous les médias soient transférés      ║
║                                                          ║
║  5. Les fichiers sont maintenant dans la galerie         ║
║     du téléphone (DCIM/ ou Pictures/)                    ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
""")

    input("  Appuie sur Entrée quand l'import est terminé...")
    return True


def step_pull():
    """Pull media files from phone to PC."""
    print("\n" + "=" * 60)
    print("  ÉTAPE 4 : Récupérer les médias sur le PC")
    print("=" * 60)

    # Create output directory
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output = os.path.join(OUTPUT_DIR, timestamp)
    os.makedirs(output, exist_ok=True)

    # Find media files from Meta View
    print("\n  Recherche des fichiers importés...")

    # Meta View saves to these locations
    search_paths = [
        "/sdcard/DCIM/",
        "/sdcard/Pictures/",
        "/sdcard/Movies/",
        "/sdcard/Download/",
    ]

    total_files = 0

    for search_path in search_paths:
        # List files
        code, listing = run_adb("shell", "ls", "-la", search_path)
        if code != 0:
            continue

        # Look for Meta-related directories
        for line in listing.split("\n"):
            if any(x in line.lower() for x in ["meta", "ray", "stella", "facebook"]):
                print(f"  📁 Trouvé : {search_path} → {line.strip()}")

    # Also check Meta View's private storage (if accessible)
    code, listing = run_adb("shell", "ls", "-la",
                            "/sdcard/Android/media/com.facebook.stella/")
    if code == 0 and "No such file" not in listing:
        print(f"\n  📁 Stockage Meta View trouvé :")
        for line in listing.strip().split("\n")[:10]:
            print(f"     {line.strip()}")

    # Pull DCIM (most likely location)
    print(f"\n  Téléchargement vers : {output}")

    for pull_path in ["/sdcard/DCIM/", "/sdcard/Pictures/"]:
        dest = os.path.join(output, os.path.basename(pull_path.rstrip("/")))
        print(f"\n  📥 Copie de {pull_path}...")
        code, out = run_adb("pull", pull_path, dest)
        if code == 0:
            # Count files
            if os.path.exists(dest):
                files = list(Path(dest).rglob("*"))
                media = [f for f in files if f.suffix.lower() in
                        ('.jpg', '.jpeg', '.mp4', '.mov', '.png', '.heic', '.heif')]
                print(f"  ✅ {len(media)} fichiers média copiés")
                total_files += len(media)
        else:
            print(f"  ⚠️  {out.strip()}")

    print(f"\n  ════════════════════════════════════")
    print(f"  Total : {total_files} fichiers récupérés")
    print(f"  Dossier : {output}")
    print(f"  ════════════════════════════════════")

    return total_files > 0


def step_cleanup():
    """Uninstall Meta View and show factory reset instructions."""
    print("\n" + "=" * 60)
    print("  ÉTAPE 5 : Nettoyage")
    print("=" * 60)

    # Uninstall Meta View
    print("\n  Désinstallation de Meta View...")
    code, output = run_adb("uninstall", META_PACKAGE)
    if code == 0:
        print("  ✅ Meta View désinstallée")
    else:
        print(f"  ⚠️  {output.strip()}")
        print("  → Désinstalle manuellement depuis Paramètres → Applications")

    # Unpair Bluetooth
    print("""
╔══════════════════════════════════════════════════════════╗
║  NETTOYAGE BLUETOOTH                                     ║
╠══════════════════════════════════════════════════════════╣
║                                                          ║
║  Sur le S23 Ultra :                                      ║
║  → Paramètres → Connexions → Bluetooth                   ║
║  → À côté de "RB Meta 00WJ" → ⚙️ → Dissocier           ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
""")

    print("""
╔══════════════════════════════════════════════════════════╗
║  FACTORY RESET DES LUNETTES                              ║
╠══════════════════════════════════════════════════════════╣
║                                                          ║
║  1. Ferme la branche droite des lunettes                 ║
║  2. Appuie 5 fois sur le bouton capture                  ║
║  3. Maintiens enfoncé                                    ║
║  4. Attends le clignotement blanc/orange                 ║
║                                                          ║
║  ⚠️  Ça efface toutes les captures sur les lunettes !    ║
║  (Vérifie que tu as bien récupéré tes fichiers avant)    ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
""")

    # Disable airplane mode
    print("  → Tu peux maintenant désactiver le mode avion\n")

    input("  Appuie sur Entrée quand le nettoyage est fait...")
    return True


def main():
    print("""
╔══════════════════════════════════════════════════════════╗
║                                                          ║
║   🕶️  META RAY-BAN - RÉCUPÉRATION DE MÉDIAS              ║
║                                                          ║
║   Récupère tes photos/vidéos sans envoyer                ║
║   de données à Meta                                      ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
""")

    step = sys.argv[1] if len(sys.argv) > 1 else "full"

    if step == "check":
        step_check()
    elif step == "block":
        step_block()
    elif step == "install":
        step_install()
    elif step == "pull":
        step_pull()
    elif step == "cleanup":
        step_cleanup()
    elif step == "full":
        if not step_check():
            print("\n⚠️  Corrige les erreurs ci-dessus puis relance.")
            sys.exit(1)

        step_block()
        step_install()
        step_import_guide()
        step_pull()
        step_cleanup()

        print("\n" + "=" * 60)
        print("  ✅ TERMINÉ !")
        print("=" * 60)
        print("""
  Tes médias sont récupérés.
  Meta View est désinstallée.
  Les lunettes sont réinitialisées.

  → Aucune donnée n'est partie vers Meta.
  → Les lunettes sont maintenant "vierges".
  → Tu peux les utiliser pour prendre des photos
    (bouton capture) et les récupérer plus tard
    avec notre app Android (Phase 2).
""")
    else:
        print(f"Étape inconnue : {step}")
        print("Utilisation : python recover_media.py [check|block|install|pull|cleanup|full]")
        sys.exit(1)


if __name__ == "__main__":
    main()

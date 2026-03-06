#!/usr/bin/env python3
"""
BLE Explorer for Ray-Ban Meta Glasses
======================================
Scans for BLE devices, identifies Meta glasses, connects and maps
all GATT services/characteristics for reverse-engineering.

Known Meta BLE identifiers:
  - Service UUID:      0000fd5f-0000-1000-8000-00805f9b34fb (0xFD5F)
  - Manufacturer ID:   0x01AB (Meta Platforms)
  - MAC prefixes:      7C:2A:9E, CC:66:0A, F4:03:43, 5C:E9:1E
                        (unreliable - firmware uses MAC randomization)

Usage:
  python scanner.py scan                          # Scan for Meta glasses
  python scanner.py scan --all                    # Show all BLE devices
  python scanner.py scan -d 30                    # Scan for 30 seconds
  python scanner.py explore -a XX:XX:XX:XX:XX:XX  # Map GATT services
  python scanner.py monitor -a XX:XX:XX:XX:XX:XX -c <uuid>  # Watch notifications
  python scanner.py live -a XX:XX:XX:XX:XX:XX     # Subscribe to all notifiable chars
"""

import argparse
import asyncio
import json
import sys
from datetime import datetime
from pathlib import Path

from bleak import BleakClient, BleakScanner
from bleak.backends.characteristic import BleakGATTCharacteristic

# --- Known Meta identifiers ---
META_MANUFACTURER_ID = 0x01AB
META_SERVICE_UUID = "0000fd5f-0000-1000-8000-00805f9b34fb"
KNOWN_MAC_PREFIXES = ["7C:2A:9E", "CC:66:0A", "F4:03:43", "5C:E9:1E"]
META_NAME_KEYWORDS = ["ray-ban", "rayban", "meta", "stories"]

# --- Standard GATT service names ---
KNOWN_SERVICES = {
    "00001800-0000-1000-8000-00805f9b34fb": "Generic Access",
    "00001801-0000-1000-8000-00805f9b34fb": "Generic Attribute",
    "0000180a-0000-1000-8000-00805f9b34fb": "Device Information",
    "0000180f-0000-1000-8000-00805f9b34fb": "Battery Service",
    "0000181c-0000-1000-8000-00805f9b34fb": "User Data",
    "00001812-0000-1000-8000-00805f9b34fb": "HID (Human Interface)",
    META_SERVICE_UUID: "Meta Proprietary Service (0xFD5F)",
}

KNOWN_CHARACTERISTICS = {
    "00002a00-0000-1000-8000-00805f9b34fb": "Device Name",
    "00002a01-0000-1000-8000-00805f9b34fb": "Appearance",
    "00002a04-0000-1000-8000-00805f9b34fb": "Peripheral Preferred Connection Parameters",
    "00002a19-0000-1000-8000-00805f9b34fb": "Battery Level",
    "00002a24-0000-1000-8000-00805f9b34fb": "Model Number",
    "00002a25-0000-1000-8000-00805f9b34fb": "Serial Number",
    "00002a26-0000-1000-8000-00805f9b34fb": "Firmware Revision",
    "00002a27-0000-1000-8000-00805f9b34fb": "Hardware Revision",
    "00002a28-0000-1000-8000-00805f9b34fb": "Software Revision",
    "00002a29-0000-1000-8000-00805f9b34fb": "Manufacturer Name",
}


def is_meta_device(addr: str, name: str, adv_data) -> tuple[bool, list[str]]:
    """Check if a BLE device is a Meta glasses. Returns (is_meta, reasons)."""
    reasons = []

    # Check manufacturer data
    mfr_data = getattr(adv_data, "manufacturer_data", {}) or {}
    if META_MANUFACTURER_ID in mfr_data:
        reasons.append(f"manufacturer_id=0x{META_MANUFACTURER_ID:04X}")

    # Check service UUIDs
    svc_uuids = getattr(adv_data, "service_uuids", []) or []
    if META_SERVICE_UUID in svc_uuids:
        reasons.append(f"service_uuid=0xFD5F")

    # Check MAC prefix
    if addr and len(addr) >= 8:
        mac_prefix = addr[:8].upper()
        if mac_prefix in KNOWN_MAC_PREFIXES:
            reasons.append(f"mac_prefix={mac_prefix}")

    # Check name
    if name:
        name_lower = name.lower()
        for kw in META_NAME_KEYWORDS:
            if kw in name_lower:
                reasons.append(f"name_match='{kw}'")
                break

    return len(reasons) > 0, reasons


# ─── Commands ──────────────────────────────────────────────────────────


async def cmd_scan(duration: int = 10, show_all: bool = False):
    """Scan for BLE devices, highlight Meta glasses."""
    print(f"Scanning BLE devices for {duration}s...")
    print("Tip: Put glasses in pairing mode (hold button on case for 5s)\n")

    devices = await BleakScanner.discover(timeout=duration, return_adv=True)

    meta_found = []
    other_count = 0

    for addr, (device, adv_data) in devices.items():
        name = getattr(adv_data, "local_name", None) or getattr(device, "name", None) or ""
        rssi = getattr(adv_data, "rssi", None)

        is_meta, reasons = is_meta_device(addr, name, adv_data)

        if is_meta:
            meta_found.append({
                "address": addr,
                "name": name,
                "rssi": rssi,
                "reasons": reasons,
                "manufacturer_data": {
                    f"0x{k:04X}": v.hex()
                    for k, v in (getattr(adv_data, "manufacturer_data", {}) or {}).items()
                },
                "service_uuids": getattr(adv_data, "service_uuids", []) or [],
                "service_data": {
                    k: v.hex()
                    for k, v in (getattr(adv_data, "service_data", {}) or {}).items()
                },
            })
            print(f"  [META] {addr} | {name or '(no name)'} | RSSI: {rssi}")
            for r in reasons:
                print(f"         -> {r}")
            mfr = getattr(adv_data, "manufacturer_data", {}) or {}
            for k, v in mfr.items():
                print(f"         -> mfr_data[0x{k:04X}]: {v.hex()}")
        else:
            other_count += 1
            if show_all:
                print(f"  [    ] {addr} | {name or '(no name)'} | RSSI: {rssi}")

    print(f"\n{'='*50}")
    print(f"  Meta devices found: {len(meta_found)}")
    print(f"  Other BLE devices:  {other_count}")

    if meta_found:
        print(f"\n  Next step: python scanner.py explore -a {meta_found[0]['address']}")

    return meta_found


async def cmd_explore(address: str):
    """Connect to a device and enumerate all GATT services."""
    print(f"Connecting to {address}...")

    result = {
        "address": address,
        "timestamp": datetime.now().isoformat(),
        "services": [],
    }

    async with BleakClient(address, timeout=15.0) as client:
        print(f"Connected: {client.is_connected}")
        print(f"MTU: {client.mtu_size}")
        print()

        for service in client.services:
            svc_name = KNOWN_SERVICES.get(service.uuid, service.description or "Unknown")
            svc_data = {
                "uuid": service.uuid,
                "name": svc_name,
                "characteristics": [],
            }

            is_meta_svc = "meta" in svc_name.lower() or "fd5f" in service.uuid.lower()
            marker = " <<<< META" if is_meta_svc else ""
            print(f"  Service: {service.uuid} ({svc_name}){marker}")

            for char in service.characteristics:
                char_name = KNOWN_CHARACTERISTICS.get(
                    char.uuid, char.description or "Unknown"
                )
                props = list(char.properties)
                props_str = ", ".join(props)

                c_data = {
                    "uuid": char.uuid,
                    "name": char_name,
                    "properties": props,
                    "handle": char.handle,
                    "value_hex": None,
                    "value_text": None,
                    "descriptors": [],
                }

                print(f"    Char: {char.uuid} [{props_str}] ({char_name})")

                # Try to read value
                if "read" in char.properties:
                    try:
                        value = await client.read_gatt_char(char.uuid)
                        c_data["value_hex"] = value.hex()
                        try:
                            text = value.decode("utf-8")
                            c_data["value_text"] = text
                            print(f"          Value (text): {text}")
                        except (UnicodeDecodeError, ValueError):
                            print(f"          Value (hex):  {value.hex()}")
                            # Also show as int if short
                            if len(value) <= 4:
                                val_int = int.from_bytes(value, "little")
                                c_data["value_int"] = val_int
                                print(f"          Value (int):  {val_int}")
                    except Exception as e:
                        c_data["read_error"] = str(e)
                        print(f"          Read error: {e}")

                # List descriptors
                for desc in char.descriptors:
                    d_data = {"uuid": desc.uuid, "handle": desc.handle}
                    try:
                        desc_val = await client.read_gatt_descriptor(desc.handle)
                        d_data["value_hex"] = desc_val.hex()
                        print(f"          Desc: {desc.uuid} = {desc_val.hex()}")
                    except Exception as e:
                        d_data["read_error"] = str(e)
                    c_data["descriptors"].append(d_data)

                svc_data["characteristics"].append(c_data)

            result["services"].append(svc_data)
            print()

    # Save results
    safe_addr = address.replace(":", "")
    filename = f"gatt_map_{safe_addr}_{datetime.now():%Y%m%d_%H%M%S}.json"
    output_path = Path(__file__).parent / filename
    output_path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"GATT map saved to: {output_path}")
    return result


async def cmd_monitor(address: str, char_uuid: str, duration: int = 30):
    """Subscribe to notifications on a specific characteristic."""
    print(f"Monitoring {char_uuid} on {address} for {duration}s...")

    notifications = []

    def handler(sender: BleakGATTCharacteristic, data: bytearray):
        ts = datetime.now().isoformat()
        entry = {"timestamp": ts, "handle": sender.handle, "data": data.hex(), "length": len(data)}
        notifications.append(entry)
        # Try to interpret
        try:
            text = data.decode("utf-8")
            print(f"  [{ts}] handle={sender.handle}: {text}")
        except (UnicodeDecodeError, ValueError):
            print(f"  [{ts}] handle={sender.handle}: {data.hex()} ({len(data)} bytes)")

    async with BleakClient(address, timeout=15.0) as client:
        print(f"Connected. Subscribing to {char_uuid}...")
        await client.start_notify(char_uuid, handler)

        try:
            await asyncio.sleep(duration)
        except KeyboardInterrupt:
            pass

        await client.stop_notify(char_uuid)

    print(f"\nReceived {len(notifications)} notifications")

    if notifications:
        filename = f"notify_{char_uuid[:8]}_{datetime.now():%Y%m%d_%H%M%S}.json"
        output_path = Path(__file__).parent / filename
        output_path.write_text(json.dumps(notifications, indent=2), encoding="utf-8")
        print(f"Saved to: {output_path}")

    return notifications


async def cmd_live(address: str, duration: int = 60):
    """Subscribe to ALL notifiable characteristics and monitor."""
    print(f"Live monitoring all notifiable chars on {address} for {duration}s...")

    notifications = []

    def make_handler(char_uuid: str, char_name: str):
        def handler(sender: BleakGATTCharacteristic, data: bytearray):
            ts = datetime.now().isoformat()
            entry = {
                "timestamp": ts,
                "char_uuid": char_uuid,
                "char_name": char_name,
                "data": data.hex(),
                "length": len(data),
            }
            notifications.append(entry)
            print(f"  [{ts}] {char_name} ({char_uuid[:8]}...): {data.hex()}")
        return handler

    async with BleakClient(address, timeout=15.0) as client:
        subscribed = []
        for service in client.services:
            for char in service.characteristics:
                if "notify" in char.properties or "indicate" in char.properties:
                    char_name = KNOWN_CHARACTERISTICS.get(char.uuid, char.description or "?")
                    try:
                        await client.start_notify(char.uuid, make_handler(char.uuid, char_name))
                        subscribed.append(char.uuid)
                        print(f"  Subscribed: {char.uuid} ({char_name})")
                    except Exception as e:
                        print(f"  Failed to subscribe {char.uuid}: {e}")

        print(f"\nListening on {len(subscribed)} characteristics...")
        print("Press Ctrl+C to stop.\n")

        try:
            await asyncio.sleep(duration)
        except KeyboardInterrupt:
            pass

        for uuid in subscribed:
            try:
                await client.stop_notify(uuid)
            except Exception:
                pass

    print(f"\nTotal notifications received: {len(notifications)}")

    if notifications:
        filename = f"live_{datetime.now():%Y%m%d_%H%M%S}.json"
        output_path = Path(__file__).parent / filename
        output_path.write_text(json.dumps(notifications, indent=2), encoding="utf-8")
        print(f"Saved to: {output_path}")

    return notifications


# ─── CLI ───────────────────────────────────────────────────────────────


async def main():
    parser = argparse.ArgumentParser(
        description="BLE Explorer for Ray-Ban Meta Glasses",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  python scanner.py scan                          Scan for Meta glasses
  python scanner.py scan --all -d 20              Scan all devices for 20s
  python scanner.py explore -a AA:BB:CC:DD:EE:FF  Map GATT services
  python scanner.py monitor -a AA:BB:... -c UUID  Watch one characteristic
  python scanner.py live -a AA:BB:CC:DD:EE:FF     Watch all notifications
        """,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # scan
    p_scan = sub.add_parser("scan", help="Scan for BLE devices")
    p_scan.add_argument("-d", "--duration", type=int, default=10, help="Scan duration (seconds)")
    p_scan.add_argument("--all", action="store_true", help="Show all devices, not just Meta")

    # explore
    p_explore = sub.add_parser("explore", help="Connect and map GATT services")
    p_explore.add_argument("-a", "--address", required=True, help="BLE device address")

    # monitor
    p_monitor = sub.add_parser("monitor", help="Monitor notifications on a characteristic")
    p_monitor.add_argument("-a", "--address", required=True, help="BLE device address")
    p_monitor.add_argument("-c", "--char", required=True, help="Characteristic UUID")
    p_monitor.add_argument("-d", "--duration", type=int, default=30, help="Duration (seconds)")

    # live
    p_live = sub.add_parser("live", help="Monitor ALL notifiable characteristics")
    p_live.add_argument("-a", "--address", required=True, help="BLE device address")
    p_live.add_argument("-d", "--duration", type=int, default=60, help="Duration (seconds)")

    args = parser.parse_args()

    if args.command == "scan":
        await cmd_scan(args.duration, args.all)
    elif args.command == "explore":
        await cmd_explore(args.address)
    elif args.command == "monitor":
        await cmd_monitor(args.address, args.char, args.duration)
    elif args.command == "live":
        await cmd_live(args.address, args.duration)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nInterrompu.")

#!/usr/bin/env python3
"""
BT Snoop HCI Log Analyzer for Ray-Ban Meta Protocol
=====================================================
Parses btsnoop_hci.log files to extract GATT ATT operations
relevant to the Meta glasses protocol.

Focuses on:
- Write requests/commands to Meta service (0xFD5F) handles
- Notification data from glasses
- Authentication/bonding events
- WiFi activation command identification

Usage:
  python analyze_btsnoop.py <btsnoop_hci.log>
  python analyze_btsnoop.py <btsnoop_hci.log> --handles 41,42,43,44,45,46
  python analyze_btsnoop.py <btsnoop_hci.log> --export commands.json
"""

import argparse
import json
import struct
import sys
from datetime import datetime, timedelta
from pathlib import Path

# BT Snoop file format constants
BTSNOOP_MAGIC = b"btsnoop\x00"
BTSNOOP_VERSION = 1

# HCI packet types
HCI_COMMAND = 0x01
HCI_ACL_DATA = 0x02
HCI_SCO_DATA = 0x03
HCI_EVENT = 0x04

# ATT opcodes we care about
ATT_OPCODES = {
    0x01: "Error Response",
    0x02: "Exchange MTU Request",
    0x03: "Exchange MTU Response",
    0x04: "Find Information Request",
    0x05: "Find Information Response",
    0x08: "Read By Type Request",
    0x09: "Read By Type Response",
    0x0A: "Read Request",
    0x0B: "Read Response",
    0x0C: "Read Blob Request",
    0x0D: "Read Blob Response",
    0x10: "Read By Group Type Request",
    0x11: "Read By Group Type Response",
    0x12: "Write Request",
    0x13: "Write Response",
    0x1B: "Handle Value Notification",
    0x1D: "Handle Value Indication",
    0x1E: "Handle Value Confirmation",
    0x52: "Write Command",
    0xD2: "Signed Write Command",
}

# Known Meta handles from our GATT map
META_HANDLES = {
    # Service Changed
    2: "Service Changed (indicate)",
    # Generic Access
    27: "Encrypted Data Key Material (indicate, read) [AUTH REQUIRED]",
    # Meta 0xFD5F Service
    41: "Meta Command/Notify (05acbe9f) [AUTH REQUIRED]",
    44: "Meta Status (c53673dd) [read]",
    46: "Meta Flags (f9fbf15d) [read]",
    # Device Information
    48: "Firmware Revision (read)",  # handle may vary
}


def parse_btsnoop_header(f) -> dict:
    """Parse btsnoop file header."""
    magic = f.read(8)
    if magic != BTSNOOP_MAGIC:
        raise ValueError(f"Not a btsnoop file (magic: {magic!r})")

    version, datalink = struct.unpack(">II", f.read(8))
    return {"version": version, "datalink": datalink}


def parse_btsnoop_record(f) -> dict | None:
    """Parse one btsnoop record. Returns None at EOF."""
    header = f.read(24)
    if len(header) < 24:
        return None

    orig_len, inc_len, flags, drops, ts_us = struct.unpack(">IIIIq", header)

    # Timestamp: microseconds since 2000-01-01
    epoch_2000 = datetime(2000, 1, 1)
    timestamp = epoch_2000 + timedelta(microseconds=ts_us)

    data = f.read(inc_len)
    if len(data) < inc_len:
        return None

    # Direction from flags
    direction = "recv" if (flags & 0x01) else "send"
    is_command = bool(flags & 0x02)

    return {
        "timestamp": timestamp,
        "direction": direction,
        "is_command": is_command,
        "data": data,
        "original_length": orig_len,
    }


def parse_l2cap_att(data: bytes) -> dict | None:
    """Extract ATT PDU from HCI ACL -> L2CAP -> ATT."""
    if len(data) < 9:
        return None

    # HCI ACL header (4 bytes): handle(12) + PB(2) + BC(2) + length(16)
    hci_handle = struct.unpack("<H", data[0:2])[0] & 0x0FFF
    hci_len = struct.unpack("<H", data[2:4])[0]

    # L2CAP header (4 bytes): length(16) + CID(16)
    if len(data) < 8:
        return None
    l2cap_len = struct.unpack("<H", data[4:6])[0]
    l2cap_cid = struct.unpack("<H", data[6:8])[0]

    # CID 0x0004 = ATT
    if l2cap_cid != 0x0004:
        return None

    att_data = data[8:]
    if len(att_data) < 1:
        return None

    opcode = att_data[0]
    return {
        "hci_handle": hci_handle,
        "opcode": opcode,
        "opcode_name": ATT_OPCODES.get(opcode, f"Unknown (0x{opcode:02X})"),
        "payload": att_data[1:],
    }


def analyze_file(filepath: str, target_handles: set[int] | None = None) -> list[dict]:
    """Analyze a btsnoop file and extract ATT operations."""
    path = Path(filepath)
    if not path.exists():
        print(f"File not found: {filepath}")
        sys.exit(1)

    events = []

    with open(path, "rb") as f:
        header = parse_btsnoop_header(f)
        print(f"BT Snoop v{header['version']}, datalink type {header['datalink']}")

        record_count = 0
        att_count = 0

        while True:
            record = parse_btsnoop_record(f)
            if record is None:
                break
            record_count += 1

            # Only look at ACL data packets (type 0x02)
            # In btsnoop format, the first byte is the HCI packet type
            if len(record["data"]) < 1:
                continue

            pkt_type = record["data"][0]
            if pkt_type != HCI_ACL_DATA:
                continue

            att = parse_l2cap_att(record["data"][1:])
            if att is None:
                continue

            att_count += 1

            # Extract ATT handle for relevant opcodes
            handle = None
            value = b""

            if att["opcode"] in (0x12, 0x52):  # Write Request, Write Command
                if len(att["payload"]) >= 2:
                    handle = struct.unpack("<H", att["payload"][:2])[0]
                    value = att["payload"][2:]

            elif att["opcode"] == 0x0A:  # Read Request
                if len(att["payload"]) >= 2:
                    handle = struct.unpack("<H", att["payload"][:2])[0]

            elif att["opcode"] in (0x0B, 0x0D):  # Read Response, Read Blob Response
                value = att["payload"]

            elif att["opcode"] == 0x1B:  # Handle Value Notification
                if len(att["payload"]) >= 2:
                    handle = struct.unpack("<H", att["payload"][:2])[0]
                    value = att["payload"][2:]

            elif att["opcode"] == 0x1D:  # Handle Value Indication
                if len(att["payload"]) >= 2:
                    handle = struct.unpack("<H", att["payload"][:2])[0]
                    value = att["payload"][2:]

            # Filter by target handles if specified
            if target_handles and handle is not None and handle not in target_handles:
                continue

            # Only show interesting operations
            if att["opcode"] in (0x12, 0x52, 0x0A, 0x0B, 0x1B, 0x1D):
                handle_name = META_HANDLES.get(handle, "") if handle else ""
                event = {
                    "timestamp": record["timestamp"].isoformat(),
                    "direction": record["direction"],
                    "opcode": f"0x{att['opcode']:02X}",
                    "opcode_name": att["opcode_name"],
                    "handle": handle,
                    "handle_name": handle_name,
                    "value_hex": value.hex() if value else "",
                    "value_len": len(value),
                }
                events.append(event)

                # Print
                ts = record["timestamp"].strftime("%H:%M:%S.%f")[:-3]
                dir_arrow = ">>" if record["direction"] == "send" else "<<"
                handle_str = f"handle={handle}" if handle is not None else ""
                name_str = f" ({handle_name})" if handle_name else ""
                val_str = f" data={value.hex()}" if value else ""
                print(f"  [{ts}] {dir_arrow} {att['opcode_name']:<30} {handle_str}{name_str}{val_str}")

    print(f"\n  Total records: {record_count:,}")
    print(f"  ATT operations: {att_count:,}")
    print(f"  Relevant events: {len(events)}")

    return events


def main():
    parser = argparse.ArgumentParser(description="BT Snoop HCI Log Analyzer for Ray-Ban Meta")
    parser.add_argument("logfile", help="Path to btsnoop_hci.log")
    parser.add_argument(
        "--handles", "-H",
        help="Comma-separated list of ATT handles to filter (e.g., 41,44,46)"
    )
    parser.add_argument(
        "--export", "-e",
        help="Export events to JSON file"
    )
    args = parser.parse_args()

    target_handles = None
    if args.handles:
        target_handles = set(int(h.strip()) for h in args.handles.split(","))
        print(f"Filtering handles: {target_handles}")

    events = analyze_file(args.logfile, target_handles)

    if args.export:
        Path(args.export).write_text(
            json.dumps(events, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        print(f"\nExported {len(events)} events to {args.export}")

    # Summary
    if events:
        print("\n=== Summary ===")
        writes = [e for e in events if "Write" in e["opcode_name"]]
        notifs = [e for e in events if "Notification" in e["opcode_name"]]
        indics = [e for e in events if "Indication" in e["opcode_name"]]
        print(f"  Writes to glasses:      {len(writes)}")
        print(f"  Notifications received: {len(notifs)}")
        print(f"  Indications received:   {len(indics)}")

        if writes:
            print("\n  Write commands (what the app sends to glasses):")
            for w in writes[:20]:  # First 20
                h = w.get("handle", "?")
                print(f"    [{w['timestamp'][-12:]}] handle={h} -> {w['value_hex'][:60]}")


if __name__ == "__main__":
    main()

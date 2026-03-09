#!/usr/bin/env python3
"""
Deep analysis of the Meta Ray-Ban proprietary transfer protocol.
Focuses on the 192.168.49.x WiFi Direct traffic on TCP port 20203.
"""

import sys
from pathlib import Path
from scapy.all import rdpcap, IP, TCP, Raw, conf
conf.verb = 0


def analyze_stream(pcap_path: str):
    print(f"Loading {pcap_path}...")
    packets = rdpcap(pcap_path)

    # Filter: WiFi Direct traffic on the proprietary port
    GLASSES_IP = "192.168.49.66"
    PHONE_IP = "192.168.49.1"
    PROTO_PORT = 20203

    stream_packets = []
    for i, pkt in enumerate(packets):
        if IP not in pkt or TCP not in pkt:
            continue
        src = pkt[IP].src
        dst = pkt[IP].dst
        sport = pkt[TCP].sport
        dport = pkt[TCP].dport

        if (src in (GLASSES_IP, PHONE_IP) and dst in (GLASSES_IP, PHONE_IP)):
            has_data = Raw in pkt and len(pkt[Raw].load) > 0
            stream_packets.append({
                "index": i,
                "src": src, "dst": dst,
                "sport": sport, "dport": dport,
                "flags": str(pkt[TCP].flags),
                "seq": pkt[TCP].seq,
                "ack": pkt[TCP].ack,
                "payload": bytes(pkt[Raw].load) if has_data else b"",
                "time": float(pkt.time),
            })

    print(f"\nTotal packets in WiFi Direct stream: {len(stream_packets)}")

    # ── Connection handshake ─────────────────────────────────────────
    print("\n" + "=" * 70)
    print("  TCP HANDSHAKE & INITIAL EXCHANGE")
    print("=" * 70)

    for pkt in stream_packets[:20]:
        direction = "PHONE->GLASSES" if pkt["src"] == PHONE_IP else "GLASSES->PHONE"
        payload_len = len(pkt["payload"])
        flags = pkt["flags"]
        print(f"  [{pkt['index']:>4}] {direction:<18} {pkt['src']}:{pkt['sport']} -> {pkt['dst']}:{pkt['dport']}")
        print(f"         Flags={flags:<6} Seq={pkt['seq']:<12} Ack={pkt['ack']:<12} Payload={payload_len}")
        if pkt["payload"]:
            print(f"         HEX:  {pkt['payload'][:64].hex()}")
            # Try to decode as text
            try:
                txt = pkt["payload"][:64].decode("utf-8", errors="replace")
                if any(c.isalpha() for c in txt):
                    print(f"         TEXT: {repr(txt)}")
            except:
                pass
        print()

    # ── Full data exchange analysis ──────────────────────────────────
    print("=" * 70)
    print("  FULL DATA EXCHANGE (all payloads)")
    print("=" * 70)

    phone_to_glasses = []
    glasses_to_phone = []

    for pkt in stream_packets:
        if not pkt["payload"]:
            continue
        entry = {
            "index": pkt["index"],
            "size": len(pkt["payload"]),
            "data": pkt["payload"],
            "time": pkt["time"],
        }
        if pkt["src"] == PHONE_IP:
            phone_to_glasses.append(entry)
        else:
            glasses_to_phone.append(entry)

    print(f"\n  Phone -> Glasses: {len(phone_to_glasses)} packets, "
          f"{sum(p['size'] for p in phone_to_glasses):,} bytes total")
    print(f"  Glasses -> Phone: {len(glasses_to_phone)} packets, "
          f"{sum(p['size'] for p in glasses_to_phone):,} bytes total")

    # ── Phone commands (small packets, likely protocol commands) ─────
    print("\n" + "-" * 70)
    print("  PHONE -> GLASSES (commands)")
    print("-" * 70)

    for entry in phone_to_glasses:
        data = entry["data"]
        print(f"\n  [Pkt {entry['index']}] {entry['size']} bytes")
        print(f"    HEX: {data[:128].hex()}")
        if len(data) > 128:
            print(f"    ... ({len(data)} bytes total)")

        # Structural analysis
        if len(data) >= 4:
            # Try various interpretations
            b0, b1, b2, b3 = data[0], data[1], data[2], data[3]
            print(f"    Byte[0:4]: {b0:02x} {b1:02x} {b2:02x} {b3:02x}")
            print(f"    As uint16 BE: {(b0<<8|b1)}, {(b2<<8|b3)}")
            print(f"    As uint32 BE: {(b0<<24|b1<<16|b2<<8|b3)}")
            if len(data) >= 8:
                b4, b5, b6, b7 = data[4], data[5], data[6], data[7]
                print(f"    Byte[4:8]: {b4:02x} {b5:02x} {b6:02x} {b7:02x}")

        # Look for patterns
        try:
            txt = data.decode("utf-8", errors="replace")
            if any(c.isalpha() for c in txt[:50]):
                print(f"    TEXT: {repr(txt[:200])}")
        except:
            pass

    # ── Glasses responses (large packets = media data) ───────────────
    print("\n" + "-" * 70)
    print("  GLASSES -> PHONE (responses/media data)")
    print("-" * 70)

    total_media_bytes = 0
    jpeg_starts = 0

    for i, entry in enumerate(glasses_to_phone):
        data = entry["data"]
        total_media_bytes += entry["size"]

        # Only show first 30 and last 5 for brevity
        if i < 30 or i >= len(glasses_to_phone) - 5:
            print(f"\n  [Pkt {entry['index']}] {entry['size']} bytes (cumul: {total_media_bytes:,})")
            print(f"    HEX: {data[:80].hex()}")

            # Detect file signatures
            if data[:2] == b'\xff\xd8':
                print(f"    >>> JPEG START (FFD8) <<<")
                jpeg_starts += 1
            elif data[:4] == b'\x89PNG':
                print(f"    >>> PNG START <<<")
            elif b'\xff\xd8\xff' in data[:256]:
                offset = data.index(b'\xff\xd8\xff')
                print(f"    >>> JPEG embedded at offset {offset} <<<")
                jpeg_starts += 1
            elif b'\x00\x00\x00\x18ftyp' in data[:32] or b'\x00\x00\x00\x1cftyp' in data[:32]:
                print(f"    >>> MP4 START (ftyp) <<<")

            # First bytes analysis
            if len(data) >= 4:
                b0, b1, b2, b3 = data[0], data[1], data[2], data[3]
                print(f"    Byte[0:4]: {b0:02x} {b1:02x} {b2:02x} {b3:02x}")
                val32 = (b0 << 24 | b1 << 16 | b2 << 8 | b3)
                print(f"    As uint32 BE: {val32} (0x{val32:08x})")

        elif i == 30:
            print(f"\n  ... ({len(glasses_to_phone) - 35} more packets) ...")

    print(f"\n  Total media data: {total_media_bytes:,} bytes ({total_media_bytes/(1024*1024):.2f} MB)")
    print(f"  JPEG starts found: {jpeg_starts}")

    # ── Protocol header analysis ─────────────────────────────────────
    print("\n" + "=" * 70)
    print("  PROTOCOL STRUCTURE ANALYSIS")
    print("=" * 70)

    # Analyze first bytes pattern across all payloads
    print("\n  First byte distribution (glasses->phone):")
    first_bytes = {}
    for entry in glasses_to_phone:
        b = entry["data"][0]
        if b not in first_bytes:
            first_bytes[b] = 0
        first_bytes[b] += 1
    for b, count in sorted(first_bytes.items(), key=lambda x: -x[1]):
        print(f"    0x{b:02x} ({b:>3}): {count} packets")

    print("\n  First byte distribution (phone->glasses):")
    first_bytes = {}
    for entry in phone_to_glasses:
        b = entry["data"][0]
        if b not in first_bytes:
            first_bytes[b] = 0
        first_bytes[b] += 1
    for b, count in sorted(first_bytes.items(), key=lambda x: -x[1]):
        print(f"    0x{b:02x} ({b:>3}): {count} packets")

    # ── Look for message framing ─────────────────────────────────────
    print("\n  Looking for message length framing...")

    # Reassemble the full stream in order
    all_data = b""
    for entry in sorted(glasses_to_phone, key=lambda x: x["index"]):
        all_data += entry["data"]

    print(f"  Total reassembled data: {len(all_data):,} bytes")
    print(f"  First 256 bytes hex:")
    for offset in range(0, min(256, len(all_data)), 16):
        chunk = all_data[offset:offset+16]
        hex_str = " ".join(f"{b:02x}" for b in chunk)
        ascii_str = "".join(chr(b) if 32 <= b < 127 else "." for b in chunk)
        print(f"    {offset:04x}: {hex_str:<48} {ascii_str}")

    # Search for JPEG markers in reassembled stream
    print(f"\n  Searching for JPEG markers (FFD8FF) in stream...")
    pos = 0
    jpeg_positions = []
    while True:
        idx = all_data.find(b'\xff\xd8\xff', pos)
        if idx == -1:
            break
        jpeg_positions.append(idx)
        pos = idx + 1

    print(f"  Found {len(jpeg_positions)} JPEG markers at offsets:")
    for p in jpeg_positions:
        # Check what's before the JPEG (might be a header)
        before = all_data[max(0,p-16):p]
        print(f"    offset {p:>8} (0x{p:06x})  before: {before.hex()}")

    # Search for MP4 markers
    print(f"\n  Searching for MP4 markers (ftyp) in stream...")
    pos = 0
    while True:
        idx = all_data.find(b'ftyp', pos)
        if idx == -1:
            break
        before = all_data[max(0,idx-8):idx]
        after = all_data[idx:idx+16]
        print(f"    offset {idx:>8} (0x{idx:06x})  context: {before.hex()} | {after.hex()}")
        pos = idx + 1

    # ── End of JPEG markers ──────────────────────────────────────────
    print(f"\n  Searching for JPEG end markers (FFD9) in stream...")
    pos = 0
    jpeg_ends = []
    while True:
        idx = all_data.find(b'\xff\xd9', pos)
        if idx == -1:
            break
        jpeg_ends.append(idx)
        pos = idx + 1

    print(f"  Found {len(jpeg_ends)} potential JPEG end markers")
    if jpeg_positions and jpeg_ends:
        for i, (start, end) in enumerate(zip(jpeg_positions, jpeg_ends)):
            size = end - start + 2
            print(f"    Image {i}: offset {start}-{end+1} = {size:,} bytes ({size/1024:.1f} KB)")


if __name__ == "__main__":
    pcap = sys.argv[1] if len(sys.argv) > 1 else "captures/PCAPdroid_09_mars_19_21_08.pcap"
    analyze_stream(pcap)

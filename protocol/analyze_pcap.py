#!/usr/bin/env python3
"""
PCAP Analysis for Meta Ray-Ban WiFi Transfer Protocol
======================================================
Analyzes PCAPdroid captures to find:
- WiFi hotspot connections (glasses SSID)
- HTTP endpoints used for media transfer
- TCP connections and data flows
- Any identifiable protocol patterns
"""

import sys
from collections import Counter, defaultdict
from pathlib import Path

try:
    from scapy.all import rdpcap, IP, TCP, UDP, DNS, DNSQR, Raw, conf
    conf.verb = 0  # Suppress scapy output
except ImportError:
    print("pip install scapy")
    sys.exit(1)


def analyze_pcap(pcap_path: str):
    print(f"\nLoading {pcap_path}...")
    packets = rdpcap(pcap_path)
    print(f"  Total packets: {len(packets)}")
    print(f"  Capture size: {Path(pcap_path).stat().st_size:,} bytes")

    # ── IP Statistics ────────────────────────────────────────────────
    print("\n" + "=" * 70)
    print("  IP TRAFFIC SUMMARY")
    print("=" * 70)

    ip_pairs = Counter()
    ip_src = Counter()
    ip_dst = Counter()
    proto_count = Counter()
    tcp_ports = Counter()
    udp_ports = Counter()

    for pkt in packets:
        if IP in pkt:
            src = pkt[IP].src
            dst = pkt[IP].dst
            ip_pairs[(src, dst)] += 1
            ip_src[src] += 1
            ip_dst[dst] += 1
            proto_count[pkt[IP].proto] += 1

            if TCP in pkt:
                tcp_ports[pkt[TCP].dport] += 1
                tcp_ports[pkt[TCP].sport] += 1
            if UDP in pkt:
                udp_ports[pkt[UDP].dport] += 1
                udp_ports[pkt[UDP].sport] += 1

    print("\n  Top source IPs:")
    for ip, count in ip_src.most_common(15):
        print(f"    {ip:<20} {count:>6} packets")

    print("\n  Top destination IPs:")
    for ip, count in ip_dst.most_common(15):
        print(f"    {ip:<20} {count:>6} packets")

    print("\n  Top IP pairs (src -> dst):")
    for (src, dst), count in ip_pairs.most_common(15):
        print(f"    {src:<20} -> {dst:<20} {count:>6} packets")

    print("\n  Top TCP ports:")
    for port, count in tcp_ports.most_common(20):
        svc = {80: "HTTP", 443: "HTTPS", 8080: "HTTP-alt", 8443: "HTTPS-alt",
               53: "DNS", 5228: "GCM", 5222: "XMPP", 5223: "XMPP-SSL",
               9000: "??", 3478: "STUN", 5060: "SIP"}.get(port, "")
        print(f"    {port:<8} {svc:<12} {count:>6} packets")

    print("\n  Top UDP ports:")
    for port, count in udp_ports.most_common(10):
        svc = {53: "DNS", 443: "QUIC", 5353: "mDNS", 1900: "SSDP",
               3478: "STUN", 5060: "SIP"}.get(port, "")
        print(f"    {port:<8} {svc:<12} {count:>6} packets")

    # ── DNS Queries ──────────────────────────────────────────────────
    print("\n" + "=" * 70)
    print("  DNS QUERIES")
    print("=" * 70)

    dns_queries = Counter()
    for pkt in packets:
        if DNS in pkt and DNSQR in pkt:
            qname = pkt[DNSQR].qname.decode(errors="replace").rstrip(".")
            dns_queries[qname] += 1

    print(f"\n  Total unique domains queried: {len(dns_queries)}")
    for domain, count in dns_queries.most_common(30):
        # Highlight Meta/Facebook domains
        marker = " <<<" if any(x in domain.lower() for x in
                               ["meta", "facebook", "fb", "fbcdn", "oculus"]) else ""
        print(f"    {domain:<55} {count:>4}{marker}")

    # ── HTTP Analysis ────────────────────────────────────────────────
    print("\n" + "=" * 70)
    print("  HTTP / PLAINTEXT ANALYSIS")
    print("=" * 70)

    http_requests = []
    http_responses = []
    raw_payloads = []

    for pkt in packets:
        if TCP in pkt and Raw in pkt:
            payload = bytes(pkt[Raw].load)
            src = pkt[IP].src if IP in pkt else "?"
            dst = pkt[IP].dst if IP in pkt else "?"
            sport = pkt[TCP].sport
            dport = pkt[TCP].dport

            try:
                text = payload[:500].decode("utf-8", errors="replace")
            except Exception:
                text = ""

            # HTTP Request
            if text.startswith(("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ")):
                method_line = text.split("\r\n")[0]
                host = ""
                for line in text.split("\r\n"):
                    if line.lower().startswith("host:"):
                        host = line.split(":", 1)[1].strip()
                        break
                http_requests.append({
                    "src": src, "dst": dst, "sport": sport, "dport": dport,
                    "method_line": method_line, "host": host,
                    "headers": text[:1000],
                })

            # HTTP Response
            elif text.startswith("HTTP/"):
                status_line = text.split("\r\n")[0]
                content_type = ""
                content_length = ""
                for line in text.split("\r\n"):
                    ll = line.lower()
                    if ll.startswith("content-type:"):
                        content_type = line.split(":", 1)[1].strip()
                    if ll.startswith("content-length:"):
                        content_length = line.split(":", 1)[1].strip()
                http_responses.append({
                    "src": src, "dst": dst,
                    "status_line": status_line,
                    "content_type": content_type,
                    "content_length": content_length,
                    "headers": text[:1000],
                })

            # Non-HTTP raw payloads on interesting ports
            elif dport in (80, 8080, 8443, 9000) or sport in (80, 8080, 8443, 9000):
                raw_payloads.append({
                    "src": src, "dst": dst, "sport": sport, "dport": dport,
                    "size": len(payload),
                    "hex_head": payload[:64].hex(),
                    "text_head": text[:100],
                })

    if http_requests:
        print(f"\n  HTTP Requests ({len(http_requests)}):")
        for req in http_requests:
            marker = " <<<" if any(x in req.get("host", "").lower()
                                    for x in ["192.168", "10.", "172."]) else ""
            print(f"    {req['src']}:{req['sport']} -> {req['dst']}:{req['dport']}")
            print(f"      {req['method_line']}")
            if req['host']:
                print(f"      Host: {req['host']}{marker}")
            print()
    else:
        print("\n  No HTTP requests found (traffic may be encrypted)")

    if http_responses:
        print(f"\n  HTTP Responses ({len(http_responses)}):")
        for resp in http_responses[:20]:
            print(f"    {resp['src']} -> {resp['dst']}")
            print(f"      {resp['status_line']}")
            if resp['content_type']:
                print(f"      Content-Type: {resp['content_type']}")
            if resp['content_length']:
                print(f"      Content-Length: {resp['content_length']}")
            print()
    else:
        print("  No HTTP responses found")

    if raw_payloads:
        print(f"\n  Non-HTTP raw payloads on web ports ({len(raw_payloads)}):")
        for rp in raw_payloads[:15]:
            print(f"    {rp['src']}:{rp['sport']} -> {rp['dst']}:{rp['dport']} ({rp['size']} bytes)")
            print(f"      HEX: {rp['hex_head'][:80]}")
            if rp['text_head'].isprintable():
                print(f"      TXT: {rp['text_head'][:80]}")
            print()

    # ── Private IP traffic (glasses hotspot) ─────────────────────────
    print("\n" + "=" * 70)
    print("  PRIVATE / LOCAL NETWORK TRAFFIC (glasses hotspot?)")
    print("=" * 70)

    private_traffic = defaultdict(lambda: {"packets": 0, "bytes": 0})
    for pkt in packets:
        if IP in pkt:
            src = pkt[IP].src
            dst = pkt[IP].dst
            is_private_src = src.startswith(("192.168.", "10.", "172.16.", "172.17.",
                                              "172.18.", "172.19.", "172.2", "172.3"))
            is_private_dst = dst.startswith(("192.168.", "10.", "172.16.", "172.17.",
                                              "172.18.", "172.19.", "172.2", "172.3"))
            # Local-only traffic (both ends are private)
            if is_private_src and is_private_dst:
                key = (src, dst)
                private_traffic[key]["packets"] += 1
                private_traffic[key]["bytes"] += len(pkt)

    if private_traffic:
        print("\n  Local traffic flows:")
        for (src, dst), stats in sorted(private_traffic.items(),
                                          key=lambda x: x[1]["bytes"], reverse=True):
            mb = stats["bytes"] / (1024 * 1024)
            print(f"    {src:<18} -> {dst:<18} {stats['packets']:>6} pkts  {mb:>8.2f} MB")
    else:
        print("\n  No private/local network traffic found")
        print("  (PCAPdroid VPN may only capture internet-bound traffic)")

    # ── Large TCP streams (potential media transfers) ────────────────
    print("\n" + "=" * 70)
    print("  LARGE TCP STREAMS (potential media transfer)")
    print("=" * 70)

    tcp_streams = defaultdict(lambda: {"bytes": 0, "packets": 0, "first_payload": b""})
    for pkt in packets:
        if TCP in pkt and IP in pkt:
            stream_key = tuple(sorted([
                (pkt[IP].src, pkt[TCP].sport),
                (pkt[IP].dst, pkt[TCP].dport),
            ]))
            if Raw in pkt:
                payload = bytes(pkt[Raw].load)
                tcp_streams[stream_key]["bytes"] += len(payload)
                tcp_streams[stream_key]["packets"] += 1
                if not tcp_streams[stream_key]["first_payload"]:
                    tcp_streams[stream_key]["first_payload"] = payload[:128]

    sorted_streams = sorted(tcp_streams.items(), key=lambda x: x[1]["bytes"], reverse=True)

    print(f"\n  Total TCP streams with data: {len(sorted_streams)}")
    print("\n  Top 15 streams by data volume:")
    for (ep1, ep2), stats in sorted_streams[:15]:
        mb = stats["bytes"] / (1024 * 1024)
        first = stats["first_payload"]
        # Detect content type from first bytes
        sig = ""
        if first[:4] == b'\xff\xd8\xff\xe0' or first[:4] == b'\xff\xd8\xff\xe1':
            sig = "JPEG!"
        elif first[:4] == b'\x89PNG':
            sig = "PNG!"
        elif first[:4] == b'\x00\x00\x00\x18' or first[:4] == b'\x00\x00\x00\x1c':
            sig = "MP4?"
        elif first[:3] == b'fty':
            sig = "MP4!"
        elif first[:4] == b'RIFF':
            sig = "AVI/WAV"
        elif first[:2] == b'PK':
            sig = "ZIP/APK"
        elif b'HTTP' in first[:20]:
            sig = "HTTP"
        elif first[:3] == b'\x16\x03\x01' or first[:3] == b'\x16\x03\x03':
            sig = "TLS"

        print(f"    {ep1[0]}:{ep1[1]} <-> {ep2[0]}:{ep2[1]}")
        print(f"      {stats['packets']:>6} pkts  {mb:>8.3f} MB  {sig}")
        print(f"      First bytes: {first[:32].hex()}")
        print()

    # ── Summary ──────────────────────────────────────────────────────
    print("=" * 70)
    print("  SUMMARY & OBSERVATIONS")
    print("=" * 70)

    total_bytes = sum(len(pkt) for pkt in packets)
    print(f"\n  Total captured data: {total_bytes / (1024*1024):.2f} MB")
    print(f"  Total packets: {len(packets)}")
    print(f"  HTTP requests found: {len(http_requests)}")
    print(f"  DNS queries: {len(dns_queries)}")
    print(f"  TCP streams with data: {len(sorted_streams)}")

    # Look for Meta-specific traffic
    meta_domains = [d for d in dns_queries if any(x in d.lower()
                    for x in ["meta", "facebook", "fb", "fbcdn", "oculus", "whatsapp"])]
    if meta_domains:
        print(f"\n  Meta/Facebook domains contacted ({len(meta_domains)}):")
        for d in sorted(meta_domains):
            print(f"    - {d}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        pcap = "captures/PCAPdroid_09_mars_19_21_08.pcap"
    else:
        pcap = sys.argv[1]
    analyze_pcap(pcap)

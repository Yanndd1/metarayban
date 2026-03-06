#!/usr/bin/env python3
"""
WiFi Hotspot Probe for Ray-Ban Meta Glasses
=============================================
When the glasses create their WiFi hotspot, this tool scans for
services, open ports, and HTTP endpoints.

Run this from the Windows PC WHILE CONNECTED to the glasses' WiFi hotspot.

Usage:
  python wifi_probe.py scan              # Full service discovery
  python wifi_probe.py scan --gateway    # Scan the gateway IP only
  python wifi_probe.py http <ip>         # Probe HTTP endpoints on an IP
  python wifi_probe.py discover          # mDNS/SSDP service discovery
"""

import argparse
import json
import socket
import sys
import urllib.request
from datetime import datetime
from pathlib import Path

RESULTS_DIR = Path(__file__).parent / "captures"

# Common ports for IoT devices
COMMON_PORTS = [
    20, 21, 22, 23, 53, 80, 443, 554, 1080, 1883,
    3000, 3478, 4443, 5000, 5353, 5555, 7000, 7070,
    8000, 8008, 8080, 8443, 8554, 8888, 9000, 9090,
    49152, 49153, 49154, 49155,  # UPnP
]

# HTTP paths to probe
HTTP_PATHS = [
    "/", "/index.html", "/api", "/api/v1", "/api/media",
    "/api/files", "/api/photos", "/api/videos", "/api/status",
    "/api/device", "/api/info", "/api/manifest",
    "/media", "/files", "/photos", "/videos",
    "/status", "/device", "/info", "/manifest",
    "/sync", "/transfer", "/import", "/export",
    "/list", "/catalog", "/index",
    "/v1/media", "/v1/sync", "/v1/transfer",
    "/.well-known/", "/favicon.ico",
    "/sdcard", "/DCIM", "/Camera",
]


def get_gateway() -> str | None:
    """Get default gateway IP (likely the glasses on their hotspot)."""
    import subprocess
    try:
        result = subprocess.run(
            ["ipconfig"], capture_output=True, text=True, timeout=5
        )
        lines = result.stdout.splitlines()
        for i, line in enumerate(lines):
            if "Default Gateway" in line or "Passerelle par" in line:
                parts = line.split(":")
                if len(parts) >= 2:
                    ip = parts[-1].strip()
                    if ip and ip != "":
                        return ip
    except Exception:
        pass

    # Fallback: try common IoT gateway IPs
    for ip in ["192.168.1.1", "192.168.0.1", "10.0.0.1", "172.16.0.1"]:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(0.5)
            if sock.connect_ex((ip, 80)) == 0:
                sock.close()
                return ip
            sock.close()
        except Exception:
            pass
    return None


def scan_ports(ip: str, ports: list[int] = COMMON_PORTS, timeout: float = 1.0) -> list[dict]:
    """Scan common ports on an IP address."""
    results = []
    print(f"\nScanning {ip} ({len(ports)} ports)...")

    for port in ports:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(timeout)
            result = sock.connect_ex((ip, port))
            if result == 0:
                # Try to grab banner
                banner = ""
                try:
                    sock.send(b"GET / HTTP/1.0\r\nHost: " + ip.encode() + b"\r\n\r\n")
                    banner = sock.recv(512).decode("utf-8", errors="replace")[:200]
                except Exception:
                    pass
                results.append({"port": port, "status": "open", "banner": banner})
                print(f"  [{port}] OPEN {banner[:80]}")
            sock.close()
        except Exception:
            pass

    if not results:
        print("  No open ports found.")
    return results


def probe_http(ip: str, port: int = 80, paths: list[str] = HTTP_PATHS) -> list[dict]:
    """Probe HTTP endpoints on a server."""
    results = []
    base = f"http://{ip}:{port}" if port != 80 else f"http://{ip}"
    print(f"\nProbing HTTP endpoints on {base}...")

    for path in paths:
        url = f"{base}{path}"
        try:
            req = urllib.request.Request(url, method="GET")
            req.add_header("User-Agent", "MetaRayBan-Probe/1.0")
            resp = urllib.request.urlopen(req, timeout=2)
            body = resp.read(1024)
            content_type = resp.headers.get("Content-Type", "")
            result = {
                "path": path,
                "status": resp.status,
                "content_type": content_type,
                "content_length": resp.headers.get("Content-Length", ""),
                "body_preview": body[:200].decode("utf-8", errors="replace"),
                "headers": dict(resp.headers),
            }
            results.append(result)
            print(f"  [{resp.status}] {path} ({content_type}) {len(body)}B")
        except urllib.error.HTTPError as e:
            if e.code != 404:  # Skip 404s, show everything else
                results.append({"path": path, "status": e.code, "error": str(e)})
                print(f"  [{e.code}] {path}")
        except Exception:
            pass  # Connection refused, timeout, etc.

    # Also try HTTPS
    for port_try in [443, 8443]:
        base_s = f"https://{ip}:{port_try}" if port_try != 443 else f"https://{ip}"
        try:
            import ssl
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            req = urllib.request.Request(f"{base_s}/", method="GET")
            resp = urllib.request.urlopen(req, timeout=2, context=ctx)
            print(f"  [HTTPS:{port_try}] Server responded! Status: {resp.status}")
            results.append({"path": f"https://:{port_try}/", "status": resp.status})
        except Exception:
            pass

    if not results:
        print("  No HTTP endpoints found.")
    return results


def discover_mdns() -> list[dict]:
    """Listen for mDNS/DNS-SD announcements (port 5353)."""
    print("\nListening for mDNS announcements (5s)...")
    results = []

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("", 5353))

        # Join multicast group
        import struct
        mreq = struct.pack("4sl", socket.inet_aton("224.0.0.251"), socket.INADDR_ANY)
        sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
        sock.settimeout(5)

        while True:
            try:
                data, addr = sock.recvfrom(4096)
                result = {"source": addr[0], "port": addr[1], "data_hex": data[:100].hex()}
                results.append(result)
                print(f"  mDNS from {addr[0]}:{addr[1]} ({len(data)} bytes)")
            except socket.timeout:
                break
    except Exception as e:
        print(f"  mDNS listen error: {e}")

    if not results:
        print("  No mDNS announcements detected.")
    return results


def discover_ssdp() -> list[dict]:
    """Send SSDP M-SEARCH and listen for responses."""
    print("\nSending SSDP M-SEARCH (5s)...")
    results = []

    msearch = (
        "M-SEARCH * HTTP/1.1\r\n"
        "HOST: 239.255.255.250:1900\r\n"
        "MAN: \"ssdp:discover\"\r\n"
        "MX: 3\r\n"
        "ST: ssdp:all\r\n"
        "\r\n"
    ).encode()

    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(5)
        sock.sendto(msearch, ("239.255.255.250", 1900))

        while True:
            try:
                data, addr = sock.recvfrom(4096)
                text = data.decode("utf-8", errors="replace")
                result = {"source": addr[0], "response": text[:500]}
                results.append(result)
                print(f"  SSDP from {addr[0]}: {text.splitlines()[0]}")
            except socket.timeout:
                break
    except Exception as e:
        print(f"  SSDP error: {e}")

    if not results:
        print("  No SSDP responses.")
    return results


def cmd_scan(gateway_only: bool = False):
    """Full service scan."""
    gateway = get_gateway()
    print(f"Default gateway: {gateway or 'not found'}")

    if gateway_only and not gateway:
        print("Could not determine gateway IP.")
        return

    targets = [gateway] if gateway_only and gateway else []
    if not gateway_only:
        # Scan the local subnet
        if gateway:
            base = ".".join(gateway.split(".")[:3])
            targets = [f"{base}.{i}" for i in range(1, 10)]  # First 10 IPs
        else:
            targets = ["192.168.1.1"]

    all_results = {"timestamp": datetime.now().isoformat(), "scans": []}

    for ip in targets:
        ports = scan_ports(ip, timeout=0.5)
        http_results = []
        for p in ports:
            if p["port"] in [80, 443, 8080, 8443, 3000, 5000, 8000, 9000]:
                http_results.extend(probe_http(ip, p["port"]))

        all_results["scans"].append({
            "ip": ip,
            "open_ports": ports,
            "http_endpoints": http_results,
        })

    # Save results
    RESULTS_DIR.mkdir(exist_ok=True)
    out = RESULTS_DIR / f"wifi_scan_{datetime.now():%Y%m%d_%H%M%S}.json"
    out.write_text(json.dumps(all_results, indent=2), encoding="utf-8")
    print(f"\nResults saved to: {out}")


def cmd_http(ip: str, port: int = 80):
    """Probe HTTP endpoints."""
    results = probe_http(ip, port)
    RESULTS_DIR.mkdir(exist_ok=True)
    out = RESULTS_DIR / f"http_probe_{ip}_{port}_{datetime.now():%Y%m%d_%H%M%S}.json"
    out.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nResults saved to: {out}")


def cmd_discover():
    """Service discovery (mDNS + SSDP)."""
    mdns = discover_mdns()
    ssdp = discover_ssdp()
    results = {"timestamp": datetime.now().isoformat(), "mdns": mdns, "ssdp": ssdp}
    RESULTS_DIR.mkdir(exist_ok=True)
    out = RESULTS_DIR / f"discovery_{datetime.now():%Y%m%d_%H%M%S}.json"
    out.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nResults saved to: {out}")


def main():
    parser = argparse.ArgumentParser(description="WiFi Hotspot Probe for Ray-Ban Meta")
    sub = parser.add_subparsers(dest="command", required=True)

    p_scan = sub.add_parser("scan", help="Port scan + HTTP probe")
    p_scan.add_argument("--gateway", action="store_true", help="Only scan the gateway")

    p_http = sub.add_parser("http", help="Probe HTTP endpoints")
    p_http.add_argument("ip", help="Target IP address")
    p_http.add_argument("-p", "--port", type=int, default=80, help="HTTP port")

    sub.add_parser("discover", help="mDNS + SSDP service discovery")

    args = parser.parse_args()

    if args.command == "scan":
        cmd_scan(args.gateway)
    elif args.command == "http":
        cmd_http(args.ip, args.port)
    elif args.command == "discover":
        cmd_discover()


if __name__ == "__main__":
    main()

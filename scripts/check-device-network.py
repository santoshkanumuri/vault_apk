"""Check named LAN devices from this PC using ping and optional TCP ports."""
import argparse
import ipaddress
import platform
import socket
import subprocess
import time


def check_ping(address, timeout):
    windows = platform.system() == "Windows"
    command = ["ping", "-n" if windows else "-c", "1"]
    if windows:
        command += ["-w", str(int(timeout * 1000))]
    command.append(address)
    try:
        result = subprocess.run(command, capture_output=True, timeout=timeout + 2)
        return "reply received" if result.returncode == 0 else "no echo reply"
    except subprocess.TimeoutExpired:
        return "no echo reply before timeout"
    except FileNotFoundError:
        return "ping command unavailable"


def check_tcp(address, port, timeout):
    started = time.monotonic()
    try:
        with socket.create_connection((address, port), timeout=timeout):
            elapsed = int((time.monotonic() - started) * 1000)
            return f"OPEN, connection accepted in {elapsed} ms"
    except ConnectionRefusedError:
        return "REFUSED, endpoint actively rejected this port"
    except TimeoutError:
        return "TIMEOUT, no connection response"
    except OSError as error:
        return f"ERROR, {error}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("addresses", nargs="+", help="Explicit device IP addresses")
    parser.add_argument("--port", type=int, action="append", default=[],
                        help="Known listening port; repeat to check several ports")
    parser.add_argument("--timeout", type=float, default=3, help="Seconds per check")
    args = parser.parse_args()
    if args.timeout <= 0 or any(port not in range(1, 65536) for port in args.port):
        parser.error("Use a positive timeout and ports between 1 and 65535")
    try:
        addresses = [str(ipaddress.ip_address(value)) for value in args.addresses]
    except ValueError as error:
        parser.error(str(error))
    for address in addresses:
        print(f"Device {address}")
        print(f"  Ping: {check_ping(address, args.timeout)}")
        for port in args.port:
            print(f"  TCP {port}: {check_tcp(address, port, args.timeout)}")
    print("\nOPEN proves this PC can reach that listening port.")
    print("REFUSED means a device or firewall actively rejected the connection.")
    print("No ping reply or TIMEOUT does not prove Wi-Fi isolation: the device may")
    print("ignore ping, be asleep, have a firewall, or have no listener on that port.")
    print("This checks PC-to-device reachability, not phone-to-phone reachability.")


if __name__ == "__main__":
    main()

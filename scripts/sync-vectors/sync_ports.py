"""Writes the shared test vectors of the Wi-Fi sync ports (`sync-ports-v2`).

    python scripts/sync-vectors/sync_ports.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/sync-ports-v2.json, and with a Windows path also
src-tauri/fixtures/sync-ports-v2.json there (the same bytes). Both apps test against the file.
Never edit the JSON by hand: change this script and run it again. The output is deterministic.

Rule (Android LocalSyncNetwork.kt, Windows lan_sync.rs). L5: the port a group listens on now stays
in 20000-32767, below the ports Android and Linux (32768 and up) and Windows (49152 and up) hand out
for outgoing connections, so another app is far less likely to hold it.
- hash: Java's String.hashCode of the group ID (UTF-16 code units, h = 31 * h + c, 32-bit wrap).
- port: 20000 + floorMod(hash, 12768).
- second: the port taken when another program holds `port`: 20000 + floorMod(port - 20000 + 6384, 12768).
- legacy: the port older versions listen on and connect to: 20000 + floorMod(hash, 30000). A new
  version also listens there while any other active member hasn't announced `sync-ports-v2`.
- A saved address is tried on the remembered port, then port, second and legacy, moving on only
  when the device refuses the connection.
"""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
OUT = ANDROID / "app/src/test/resources/sync-vectors/sync-ports-v2.json"

FIRST, COUNT, LEGACY_COUNT = 20000, 12768, 30000


def java_hash(text):
    value = 0
    data = text.encode("utf-16-le")
    for index in range(0, len(data), 2):
        value = (31 * value + int.from_bytes(data[index:index + 2], "little")) & 0xFFFFFFFF
    return value - (1 << 32) if value >= 1 << 31 else value


def ports(group):
    h = java_hash(group)
    port = FIRST + h % COUNT
    second = FIRST + (port - FIRST + COUNT // 2) % COUNT
    legacy = FIRST + h % LEGACY_COUNT
    return {"group": group, "hash": h, "port": port, "second": second, "legacy": legacy}


GROUPS = ["android-vault", "vault-a", "", "Z", "6f1c2a9e-4b7d-4e15-9a3c-0d2b8e7f5a61",
          "c0ffee00-0000-4000-8000-000000000000", "groupé-ü", "😀-vault", "ffffffff-ffff-4fff-bfff-ffffffffffff"]


def main():
    cases = [ports(group) for group in GROUPS]
    for case in cases:
        assert FIRST <= case["port"] <= 32767 and FIRST <= case["second"] <= 32767 and case["port"] != case["second"]
        assert FIRST <= case["legacy"] <= 49999
    assert cases[0]["legacy"] == 23492, "Windows' existing test value"
    document = {"format": "nuvori-sync-port-vectors", "version": 1, "rule": "sync-ports-v2",
                "first": FIRST, "count": COUNT, "legacyCount": LEGACY_COUNT, "cases": cases}
    text = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    OUT.write_text(text, encoding="utf-8")
    if len(sys.argv) > 1:
        (pathlib.Path(sys.argv[1]) / "src-tauri/fixtures/sync-ports-v2.json").write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()

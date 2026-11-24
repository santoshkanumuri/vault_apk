"""Writes the shared test vectors of history compaction on the device (no wire change).

    python scripts/sync-vectors/compaction.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/compaction-v1.json, and with a Windows path also
src-tauri/fixtures/compaction-v1.json there (the same bytes). Never edit the JSON by hand.

Rule (Android HistoryCompaction.kt, Windows compaction.rs). A device deletes change c of author A when
both hold:
- c.sequence <= floor(A). floor(A) is the minimum, over every ACTIVE member M (this device included),
  of the head of A that M reports as applied (0 when M never reported). With Google Drive sync on, it
  is also at most the drive's gap-free coverage of A that this device knows, so the drive can still
  get every change from someone. Removed members pin nothing.
- c is not in the keep set (scripts/sync-vectors/checkpoint.py), and it is not this device's own
  applied head of A.
The history is the one of checkpoint-v1.json: 40 changes by dev-phone, dev-pc and dev-tablet.

Google Drive (feature history-compaction-v1, Android DriveCompaction.kt, Windows compaction.rs). Only
when every active member announced the feature. Per author A:
- A floor marker "nv1.<group>.c.<author tag>" holds sealed JSON {"author","floor","hash","writer","at"}:
  the author's changes up to `floor` may be gone from the drive. A reader counts it only when it is at
  or below the reader's own floor(A) (the minimum applied head over active members, without the drive).
- Coverage starts after the counted floor: coverage(ranges, floor) = the end of the gap-free run of
  files from floor + 1 (from 1 without a marker).
- Cleanup: the `o.` files of A that end at or below min(floor(A), coverage) go. The marker is written
  (or replaced) first with floor = the highest last of those files, then the files are deleted; a
  stop between the two leaves extra files, never a gap. At most 64 deletes a pass.
"""
import hashlib
import hmac
import json
import pathlib
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import checkpoint as base  # noqa: E402

OUT = base.ANDROID / "app/src/test/resources/sync-vectors/compaction-v1.json"
LOCAL = "dev-phone"


def floors(members, progress, local_applied, drive):
    active = [m["deviceId"] for m in members if m["status"] == "ACTIVE"]
    authors = sorted({a for a in local_applied})
    result = {}
    for author in authors:
        values = []
        for member in active:
            applied = local_applied if member == LOCAL else progress.get(member)
            values.append(0 if applied is None else applied.get(author, 0))
        floor = min(values) if values else 0
        if drive is not None:
            floor = min(floor, drive.get(author, 0))
        result[author] = floor
    return result


def coverage(ranges, floor):
    covered = floor
    for first, last in sorted(ranges):
        if first > covered + 1:
            break
        covered = max(covered, last)
    return covered


def counted_floor(marker, reader_floor):
    return marker if marker is not None and marker <= reader_floor else 0


def cleanup(ranges, marker, floor):
    """The files to delete and the new marker floor, for one author."""
    reach = min(floor, coverage(ranges, marker))
    gone = sorted(r for r in ranges if r[1] <= reach)
    return (max([marker] + [r[1] for r in gone]), gone)


def drive_vectors():
    cases = [
        ("no marker, gap-free from 1", [(1, 10), (11, 20)], None, 30, 20),
        ("no marker, a gap", [(1, 10), (12, 20)], None, 30, 10),
        ("a counted marker starts the coverage after it", [(41, 50), (51, 60)], 40, 45, 60),
        ("a marker above this reader's floor is not counted", [(41, 50)], 40, 39, 0),
        ("a marker and a gap after it", [(42, 50)], 40, 45, 40),
    ]
    coverage_cases = []
    for name, ranges, marker, reader_floor, expected in cases:
        counted = counted_floor(marker, reader_floor)
        assert coverage(ranges, counted) == expected, name
        coverage_cases.append({"name": name, "ranges": ranges, "marker": marker, "readerFloor": reader_floor,
                               "coverage": expected})
    cleanup_cases = []
    for name, ranges, marker, floor in [
        ("files below the floor go; the one across it stays", [(1, 256), (257, 512), (513, 600)], 0, 520),
        ("nothing below the floor", [(1, 256)], 0, 100),
        ("a gap stops the cleanup at the gap", [(1, 10), (20, 30)], 0, 40),
        ("after an earlier cleanup", [(257, 512), (513, 600)], 256, 600),
        ("an offline member pins the floor", [(1, 256), (257, 512)], 0, 0),
    ]:
        new_marker, gone = cleanup(ranges, marker, floor)
        cleanup_cases.append({"name": name, "ranges": ranges, "marker": marker, "floor": floor,
                              "newMarker": new_marker, "deleted": [list(r) for r in gone]})
    key = hmac.new(bytes(range(32)), b"nuvori-cloud-v1:vault-a", hashlib.sha256).digest()
    tag = lambda label: hmac.new(key, label.encode(), hashlib.sha256).digest()[:8].hex()
    name = f"nv1.{tag('tag')}.c.{tag('device:dev-pc')}"
    body = json.dumps({"author": "dev-pc", "floor": 256, "hash": "ab" * 32, "writer": "dev-phone", "at": 1795000000000},
                      separators=(",", ":"))
    nonce = bytes([0x71] * 12)
    sealed = b"NVC1" + nonce + AESGCM(key).encrypt(nonce, body.encode(), name.encode())
    return {"feature": "history-compaction-v1", "coverage": coverage_cases, "cleanup": cleanup_cases,
            "marker": {"transportSecretHex": bytes(range(32)).hex(), "vaultId": "vault-a", "name": name, "bodyJson": body,
                       "nonceHex": nonce.hex(), "sealedHex": sealed.hex()}}


def main():
    changes = base.history()
    checkpoint_vectors = json.loads((base.ANDROID / "app/src/test/resources/sync-vectors/checkpoint-v1.json").read_text())
    data = checkpoint_vectors["keepSetInput"]
    assert [c["hash"] for c in data["operations"]] == [c["hash"] for c in changes], "run checkpoint.py first"
    kept = base.keep_set(changes, data["records"], data["tombstones"], data["conflicts"])
    heads = {}
    for c in changes:
        heads[c["deviceId"]] = max(heads.get(c["deviceId"], 0), c["sequence"])
    members = [{"deviceId": "dev-phone", "status": "ACTIVE"}, {"deviceId": "dev-pc", "status": "ACTIVE"},
               {"deviceId": "dev-laptop", "status": "ACTIVE"}, {"deviceId": "dev-tablet", "status": "REVOKED"}]
    everything = dict(heads)
    scenarios = [
        ("an offline member holds back compaction", {"dev-pc": everything, "dev-laptop": {"dev-phone": 4, "dev-pc": 2, "dev-tablet": 3}}, None),
        ("a member that never reported pins everything", {"dev-pc": everything}, None),
        ("every active member applied everything", {"dev-pc": everything, "dev-laptop": everything}, None),
        ("the drive does not hold every change yet", {"dev-pc": everything, "dev-laptop": everything},
         {"dev-phone": 10, "dev-pc": 16, "dev-tablet": 8}),
        ("a removed member pins nothing", {"dev-pc": everything, "dev-laptop": everything, "dev-tablet": {"dev-phone": 1}}, None),
    ]
    out = []
    for name, progress, drive in scenarios:
        floor = floors(members, progress, heads, drive)
        deleted = sorted(c["hash"] for c in changes if c["sequence"] <= floor[c["deviceId"]] and c["hash"] not in kept
                         and c["sequence"] != heads[c["deviceId"]])
        out.append({"name": name, "applied": progress, "driveCovered": drive, "floors": floor,
                    "deleted": deleted, "keptCount": len(changes) - len(deleted)})
    assert len(out[1]["deleted"]) == 0 and len(out[2]["deleted"]) == 32
    vectors = {
        "format": "nuvori-sync-compaction-vectors",
        "version": 1,
        "localDeviceId": LOCAL,
        "members": members,
        "localApplied": heads,
        "history": "checkpoint-v1.json keepSetInput",
        "scenarios": out,
        "drive": drive_vectors(),
    }
    text = json.dumps(vectors, indent=2, ensure_ascii=True) + "\n"
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(text), "bytes")
    if len(sys.argv) > 1:
        windows = pathlib.Path(sys.argv[1]).resolve() / "src-tauri/fixtures/compaction-v1.json"
        windows.write_text(text, encoding="utf-8", newline="\n")
        print("wrote", windows)


if __name__ == "__main__":
    main()

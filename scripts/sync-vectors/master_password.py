"""Writes the shared test vectors of sync protocol `master-password-v1` (every paired device keeps the
managing device's master password).

    python scripts/sync-vectors/master_password.py [path/to/vault_apk_windows]

Output: app/src/test/resources/sync-vectors/master-password-v1.json, and with a Windows path also
src-tauri/fixtures/master-password-v1.json there (the same bytes). Never edit the JSON by hand: change
this script and run it again. The output is deterministic. Android MasterPasswordRules.kt, Windows
master_password.rs.

Entity type "master_password", always an upsert, never a delete. Two kinds of record:

Password check. Entity id = the vault ID. Written only by the managing device, when its master
password changes while it shares the vault:
    {"epoch": E, "membershipSequence": M, "salt": <16 bytes>, "memoryKib": 65536,
     "iterations": T, "parallelism": 1, "check": <32 bytes>}
    key   = Argon2id v1.3(password as UTF-8, salt, 65536 KiB, T passes with 3 <= T <= 64, 1 lane, 32 bytes)
    check = HMAC-SHA256(key, "nuvori-master-password-check-v1\\n<vaultId>\\n<E>")
E counts the managing device's password changes from 1. M is the sequence of the newest membership
event when the check was made. Base64 is standard with padding and canonical. Numbers are JSON
integers from 1 to 2^53 - 1. No other keys. The check proves a typed password without holding it:
guessing it costs one Argon2id run per guess, as guessing the master password from a backup file does,
and it travels only inside the group's end-to-end encrypted sync changes.

Switched. Entity id = the author's own device ID: {"switchedEpoch": E}. The author's vault now
opens with the password of check E.

Any other entity id, a delete, an unknown key or a value out of range makes the change invalid.

The current check is the one with the highest epoch, then the highest membership sequence, then the
highest change hash (UTF-8 order), among the checks whose M is from 1 to the membership head and
whose author is the managing device now (after the newest membership event). A check from any other
device is ignored, a former managing device's included, whatever M it names.

A managing-role transfer carries the current check over: the device that accepts the role first
signs a copy of the current check (the same payload, M included) under its own device ID, and the
managing device completes the transfer only once it holds that copy. So after the transfer the copy
is current, and nothing changes for any device. A device whose own checks are these (the managing
device's, or its copy) holds their password, as if it had switched to their epoch.

While the managing device has no check of its own yet (the transfer reached a device before the copy
did), a device that still had to switch keeps being asked for the check it was last asked for, by its
change hash, if that check is held and its M is from 1 to the head ("held"). That hash was only ever
recorded while its check was current, so no other device's check can take its place.

A device still has to switch while the current check exists and the device is not its author, has
not switched to that epoch or a later one (its own switches and its own checks both count), and was
not added after M (its newest ADD or GENESIS event has a sequence above M: the pairing proved the then
current password). A device that was asked to switch to a check keeps being asked for that check
while the managing device has no check of its own yet (a transfer that arrived before its copy).
"""
import base64
import hashlib
import hmac
import json
import pathlib
import sys

from cryptography.hazmat.primitives.kdf.argon2 import Argon2id

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
OUT = ANDROID / "app/src/test/resources/sync-vectors/master-password-v1.json"
LABEL = "nuvori-master-password-check-v1"
MEMORY_KIB = 65536
MAX_NUMBER = 2**53 - 1


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def check_value(vault_id: str, password: str, epoch: int, salt: bytes, iterations: int) -> bytes:
    key = Argon2id(salt=salt, length=32, iterations=iterations, lanes=1, memory_cost=MEMORY_KIB).derive(
        password.encode("utf-8"))
    return hmac.new(key, f"{LABEL}\n{vault_id}\n{epoch}".encode("utf-8"), hashlib.sha256).digest()


def payload(epoch: int, sequence: int, salt: bytes, iterations: int, check: bytes) -> dict:
    return {"epoch": epoch, "membershipSequence": sequence, "salt": b64(salt), "memoryKib": MEMORY_KIB,
            "iterations": iterations, "parallelism": 1, "check": b64(check)}


def checks() -> list:
    cases = []
    specs = [
        ("ascii", "vault-master-password-test", "correct horse battery staple", 1, 4, bytes(range(16)), 3,
         ["Correct horse battery staple", "correct horse battery staple "]),
        # Non-ASCII and a character outside the BMP: Kotlin holds the password as UTF-16 chars.
        ("unicode", "vault-master-password-test", "Pässwörd ✓ 🔐 Ωmega", 7, 12, bytes([0xA5] * 16), 4,
         ["Passwörd ✓ 🔐 Ωmega", "Pässwörd ✓ Ωmega"]),
        ("other-vault", "8c1f9a2e-4b7d-4e10-9c3a-2d5f6b7e8a90", "correct horse battery staple", 2, 30,
         bytes(range(100, 116)), 3, ["correct horse battery stapl"]),
    ]
    for name, vault_id, password, epoch, sequence, salt, iterations, wrong in specs:
        check = check_value(vault_id, password, epoch, salt, iterations)
        variants = [{"vaultId": vault_id, "password": password, "epoch": epoch, "matches": True}]
        variants += [{"vaultId": vault_id, "password": other, "epoch": epoch, "matches": False} for other in wrong]
        # The check is bound to its vault and its epoch.
        variants.append({"vaultId": vault_id + "-copy", "password": password, "epoch": epoch, "matches": False})
        variants.append({"vaultId": vault_id, "password": password, "epoch": epoch + 1, "matches": False})
        cases.append({"name": name, "vaultId": vault_id, "password": password, "checkHex": check.hex(),
                      "payload": payload(epoch, sequence, salt, iterations, check), "variants": variants})
    return cases


def payloads() -> dict:
    salt, check = bytes(range(16)), bytes(range(32))
    good = payload(3, 9, salt, check=check, iterations=5)

    def edit(**changes):
        value = dict(good)
        for key, replacement in changes.items():
            if replacement is None:
                value.pop(key)
            else:
                value[key] = replacement
        return value

    valid = [good, edit(iterations=3), edit(iterations=64), edit(epoch=MAX_NUMBER, membershipSequence=MAX_NUMBER)]
    invalid = [
        edit(epoch=0), edit(epoch=-1), edit(epoch=MAX_NUMBER + 1), edit(epoch=1.5), edit(epoch="3"),
        edit(membershipSequence=0), edit(membershipSequence=None),
        edit(memoryKib=65535), edit(memoryKib=131072), edit(iterations=2), edit(iterations=65),
        edit(parallelism=2), edit(parallelism=None),
        edit(salt=b64(bytes(15))), edit(salt=b64(bytes(17))), edit(salt=b64(salt).rstrip("=")),
        edit(salt=base64.urlsafe_b64encode(bytes([0xFB] * 16)).decode()),
        # Same bytes but unused bits set: not canonical.
        edit(salt=b64(salt)[:-3] + "x=="),
        edit(check=b64(bytes(31))), edit(check=b64(bytes(33))), edit(check=None), edit(check=7),
        edit(extra=True),
    ]
    return {
        "check": {"valid": valid, "invalid": invalid},
        "switched": {
            "valid": [{"switchedEpoch": 1}, {"switchedEpoch": 42}, {"switchedEpoch": MAX_NUMBER}],
            "invalid": [{"switchedEpoch": 0}, {"switchedEpoch": -3}, {"switchedEpoch": MAX_NUMBER + 1},
                        {"switchedEpoch": 2.0}, {"switchedEpoch": "2"}, {}, {"switchedEpoch": 2, "epoch": 2},
                        {"epoch": 2}],
        },
    }


def records() -> list:
    vault, phone, pc = "vault-records-test", "dev-phone", "dev-pc"
    check = payload(2, 5, bytes(range(16)), 3, bytes(range(32)))
    return [
        {"name": "check", "vaultId": vault, "author": phone, "entityId": vault, "kind": "upsert",
         "payload": check, "expected": "check"},
        {"name": "switched", "vaultId": vault, "author": pc, "entityId": pc, "kind": "upsert",
         "payload": {"switchedEpoch": 2}, "expected": "switched"},
        {"name": "switched-for-another-device", "vaultId": vault, "author": pc, "entityId": phone, "kind": "upsert",
         "payload": {"switchedEpoch": 2}, "expected": "invalid"},
        {"name": "check-under-device-id", "vaultId": vault, "author": phone, "entityId": phone, "kind": "upsert",
         "payload": check, "expected": "invalid"},
        {"name": "switched-under-vault-id", "vaultId": vault, "author": pc, "entityId": vault, "kind": "upsert",
         "payload": {"switchedEpoch": 2}, "expected": "invalid"},
        {"name": "delete", "vaultId": vault, "author": phone, "entityId": vault, "kind": "delete",
         "payload": check, "expected": "invalid"},
        {"name": "other-entity", "vaultId": vault, "author": phone, "entityId": "something-else", "kind": "upsert",
         "payload": check, "expected": "invalid"},
    ]


def current_cases() -> list:
    # managers[i] is the managing device once membership event i + 1 applied. phone-b receives the
    # role at event 5. The managing device now is managers[head - 1].
    managers = ["phone-a", "phone-a", "phone-a", "phone-a", "phone-b", "phone-b"]

    def candidate(author, hash_, epoch, sequence):
        return {"author": author, "hash": hash_, "epoch": epoch, "membershipSequence": sequence}

    def case(name, head, candidates, expected, roles=managers, held=None):
        return {"name": name, "head": head, "managers": roles, "candidates": candidates, "held": held,
                "expected": expected}

    return [
        case("none", 6, [], None),
        case("highest-epoch", 4, [candidate("phone-a", "h1", 1, 2), candidate("phone-a", "h2", 2, 3)], "h2"),
        case("not-the-manager", 4, [candidate("phone-a", "h1", 1, 2), candidate("dev-pc", "h2", 5, 3)], "h1"),
        case("before-the-transfer-the-old-manager-counts", 4,
             [candidate("phone-a", "h1", 3, 4), candidate("phone-b", "h2", 3, 4)], "h1"),
        case("former-manager-is-refused", 6, [candidate("phone-a", "h1", 3, 4)], None),
        case("former-manager-cannot-name-an-earlier-event", 6,
             [candidate("phone-a", "h9", 99, 3), candidate("phone-b", "h2", 3, 4)], "h2"),
        case("copy-carried-over-by-the-new-manager", 6,
             [candidate("phone-a", "h1", 3, 4), candidate("phone-b", "h2", 3, 4)], "h2"),
        case("new-manager-changes-it", 6, [candidate("phone-b", "h2", 3, 4), candidate("phone-b", "h3", 4, 6)], "h3"),
        case("beyond-head", 4, [candidate("phone-a", "h1", 1, 2), candidate("phone-a", "h2", 2, 5)], "h1"),
        case("same-epoch-higher-sequence", 6, [candidate("phone-b", "h9", 4, 6), candidate("phone-b", "h1", 4, 3)], "h9"),
        case("same-epoch-same-sequence-higher-hash", 4,
             [candidate("phone-a", "ab", 2, 3), candidate("phone-a", "b", 2, 3)], "b"),
        case("sequence-zero", 4, [candidate("phone-a", "h1", 1, 0)], None),
        case("role-came-back", 4, [candidate("phone-a", "h1", 1, 1), candidate("phone-b", "h2", 2, 3),
             candidate("phone-a", "h3", 2, 3)], "h3", ["phone-a", "phone-a", "phone-b", "phone-a"]),
        # "held" is the check the device was last asked for (currentOrHeld).
        case("held-while-the-new-manager-has-none", 6, [candidate("phone-a", "h1", 3, 4)], "h1", held="h1"),
        case("held-gives-way-to-the-new-manager", 6,
             [candidate("phone-a", "h1", 3, 4), candidate("phone-b", "h2", 3, 4)], "h2", held="h1"),
        case("held-must-be-a-check-held-here", 6, [candidate("phone-a", "h1", 3, 4)], None, held="h7"),
        case("held-beyond-head", 4, [candidate("phone-a", "h5", 9, 5)], None, held="h5"),
    ]


def satisfied_cases() -> list:
    current = {"author": "phone-a", "epoch": 3, "membershipSequence": 5}

    def case(name, device, switched, added, expected):
        return {"name": name, "current": current, "device": device, "switchedEpoch": switched,
                "addedAt": added, "expected": expected}

    return [
        case("author", "phone-a", None, 1, True),
        case("never-switched", "dev-pc", None, 3, False),
        case("switched-older", "dev-pc", 2, 3, False),
        case("switched", "dev-pc", 3, 3, True),
        case("switched-newer", "dev-pc", 4, 3, True),
        case("added-at-the-check", "dev-pc", None, 5, False),
        case("added-after", "dev-pc", None, 6, True),
        case("not-a-member", "dev-pc", None, None, False),
    ]


def main():
    vectors = {
        "format": "nuvori-sync-master-password-vectors",
        "version": 1,
        "feature": "master-password-v1",
        "entityType": "master_password",
        "label": LABEL,
        "memoryKib": MEMORY_KIB,
        "minIterations": 3,
        "maxIterations": 64,
        "checks": checks(),
        "payloads": payloads(),
        "records": records(),
        "current": current_cases(),
        "satisfied": satisfied_cases(),
    }
    text = json.dumps(vectors, indent=2, ensure_ascii=True) + "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(text), "bytes")
    if len(sys.argv) > 1:
        windows = pathlib.Path(sys.argv[1]).resolve() / "src-tauri/fixtures/master-password-v1.json"
        windows.write_text(text, encoding="utf-8", newline="\n")
        print("wrote", windows)


if __name__ == "__main__":
    main()

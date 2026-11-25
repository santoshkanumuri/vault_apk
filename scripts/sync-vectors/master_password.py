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
highest change hash (UTF-8 order), among the checks whose M is not beyond the membership head and
whose author was the managing device once membership event M applied. A check from any other device
is ignored. A device still has to switch while the current check exists and the device is not its
author, has not said it switched to that epoch or a later one, and was not added after M (its newest
ADD or GENESIS event has a sequence above M: the pairing proved the then current password).
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
    # managers[i] is the managing device once membership event i + 1 applied.
    managers = ["phone-a", "phone-a", "phone-a", "phone-a", "phone-b", "phone-b"]

    def candidate(author, hash_, epoch, sequence):
        return {"author": author, "hash": hash_, "epoch": epoch, "membershipSequence": sequence}

    return [
        {"name": "none", "head": 6, "managers": managers, "candidates": [], "expected": None},
        {"name": "highest-epoch", "head": 6, "managers": managers,
         "candidates": [candidate("phone-a", "h1", 1, 2), candidate("phone-a", "h2", 2, 3)], "expected": "h2"},
        {"name": "not-the-manager", "head": 6, "managers": managers,
         "candidates": [candidate("phone-a", "h1", 1, 2), candidate("dev-pc", "h2", 5, 3)], "expected": "h1"},
        {"name": "manager-before-transfer-still-counts", "head": 6, "managers": managers,
         "candidates": [candidate("phone-a", "h1", 3, 4)], "expected": "h1"},
        {"name": "former-manager-after-transfer", "head": 6, "managers": managers,
         "candidates": [candidate("phone-a", "h1", 3, 4), candidate("phone-a", "h2", 4, 5)], "expected": "h1"},
        {"name": "new-manager", "head": 6, "managers": managers,
         "candidates": [candidate("phone-a", "h1", 3, 4), candidate("phone-b", "h2", 4, 6)], "expected": "h2"},
        {"name": "beyond-head", "head": 4, "managers": managers,
         "candidates": [candidate("phone-a", "h1", 1, 2), candidate("phone-a", "h2", 2, 5)], "expected": "h1"},
        {"name": "same-epoch-higher-sequence", "head": 6, "managers": managers,
         "candidates": [candidate("phone-b", "h9", 4, 6), candidate("phone-a", "h1", 4, 3)], "expected": "h9"},
        {"name": "same-epoch-same-sequence-higher-hash", "head": 6, "managers": managers,
         "candidates": [candidate("phone-a", "ab", 2, 3), candidate("phone-a", "b", 2, 3)], "expected": "b"},
        {"name": "sequence-zero", "head": 6, "managers": managers,
         "candidates": [candidate("phone-a", "h1", 1, 0)], "expected": None},
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

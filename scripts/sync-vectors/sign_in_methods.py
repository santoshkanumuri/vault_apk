"""Writes the shared test vectors of sync protocol `sign-in-methods-v1` (phase 2; not wired into sync yet).

    python scripts/sync-vectors/sign_in_methods.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/sign-in-methods-v1.json, and with a Windows path also
src-tauri/fixtures/sign-in-methods-v1.json there (the same bytes). Both apps test against the file.
Never edit the JSON by hand: change this script and run it again. The output is deterministic.

Protocol (Windows sign_ins.rs keeps the rows; the sync module and Android rules come in phase 2):
- Entity type "sign_in_method": an upsert, or a delete with the tombstone every entity delete leaves.
- One entity per (site, provider, account key). account key = the account trimmed, then lower-cased with
  Unicode default case mapping (Rust str::to_lowercase, Kotlin lowercase(Locale.ROOT), Python str.lower).
- Entity id: the first 32 lowercase hex characters of
  HMAC-SHA256(content key, "nuvori-sync-sign-in-method-id-v1:" + site + "\\n" + provider + "\\n" + account key).
  Entity ids travel in plain text, so the id must not show the site, the provider or the account.
- Upsert plaintext {"signInMethod":{"site","provider","account","firstUsedAt","lastUsedAt","useCount"}};
  times are Unix milliseconds. Other keys are ignored. The receiver recomputes the id and refuses a mismatch.
- Delete plaintext {"deleted":true}, as for entries; the receiver keeps a tombstone for the id.
- Encrypted like every entity: AES-256-GCM, key HMAC-SHA256(content key, "nuvori-sync-payload-key-v1"),
  AAD "nuvori-sync-sign_in_method-v1:<vaultId>:<mutationId>:<entityId>".
- Concurrent upserts merge field by field (never a conflict to review): first used = min, last used = max,
  use count = max, account text from the later writer (later occurredAtUtc, then the greater device id).
  An upsert concurrent with a delete: the later writer wins, by the same order.
"""
import base64
import hashlib
import hmac
import json
import pathlib
import sys

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
OUT = ANDROID / "app/src/test/resources/sync-vectors/sign-in-methods-v1.json"

CONTENT_KEY = bytes(range(32))
VAULT_ID = "vault-sign-in-methods-test"
MUTATION_ID = "6f1d2a9c-3b47-4e85-a0c2-91d7e5b3f468"
NONCE = bytes(range(1, 13))
ID_PREFIX = "nuvori-sync-sign-in-method-id-v1:"
ENTITY_TYPE = "sign_in_method"
PROVIDERS = ["google", "apple", "microsoft", "github", "facebook", "x", "linkedin", "discord", "amazon", "slack"]
MAX_ACCOUNT_CHARS = 256
MAX_USE_COUNT = 1_000_000_000
FORMAT_RANGES = [(0x00AD, 0x00AD), (0x0600, 0x0605), (0x061C, 0x061C), (0x06DD, 0x06DD), (0x070F, 0x070F), (0x0890, 0x0891),
                 (0x08E2, 0x08E2), (0x180E, 0x180E), (0x200B, 0x200F), (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x206F),
                 (0xFEFF, 0xFEFF), (0xFFF9, 0xFFFB), (0x110BD, 0x110BD), (0x110CD, 0x110CD), (0x13430, 0x1343F),
                 (0x1BCA0, 0x1BCA3), (0x1D173, 0x1D17A), (0xE0001, 0xE0001), (0xE0020, 0xE007F)]


def account_key(account: str) -> str:
    return account.strip().lower()


def entity_id(site: str, provider: str, account: str) -> str:
    message = f"{ID_PREFIX}{site}\n{provider}\n{account_key(account)}".encode()
    return hmac.new(CONTENT_KEY, message, hashlib.sha256).hexdigest()[:32]


def valid_account(account: str) -> bool:
    """At most 256 characters after trimming, no control or format (Cf) characters. Empty means not known."""
    text = account.strip()
    if len(text) > MAX_ACCOUNT_CHARS:
        return False
    for char in text:
        code = ord(char)
        if code < 0x20 or 0x7F <= code < 0xA0 or any(low <= code <= high for low, high in FORMAT_RANGES):
            return False
    return True


def encrypt(plaintext: str, eid: str) -> str:
    payload_key = hmac.new(CONTENT_KEY, b"nuvori-sync-payload-key-v1", hashlib.sha256).digest()
    aad = f"nuvori-sync-{ENTITY_TYPE}-v1:{VAULT_ID}:{MUTATION_ID}:{eid}".encode()
    return base64.urlsafe_b64encode(AESGCM(payload_key).encrypt(NONCE, plaintext.encode(), aad)).decode().rstrip("=")


def body(**fields) -> str:
    return json.dumps({"signInMethod": fields}, separators=(",", ":"), ensure_ascii=False)


def method(site, provider, account, first, last, count, **extra):
    fields = {"site": site, "provider": provider, "account": account, "firstUsedAt": first, "lastUsedAt": last, "useCount": count}
    fields.update(extra)
    return fields


def main():
    sites = {
        # The same host rules as site-icons-v1: a lowercase ASCII registrable domain, at least two labels.
        "valid": ["example.com", "bbc.co.uk", "alice.github.io", "xn--bcher-kva.example", "a-b.io", "my-bank.example.org"],
        "invalid": ["", "Example.com", "example", "https://example.com", "example.com/", "-example.com", "exa_mple.com",
                    "127.0.0.1", "example..com", ".example.com", "example.com.", "example.com:443", "bücher.example"],
    }
    accounts = {
        "valid": ["", "octocat", "@jack", "jamie@example.com", "x7k2m9qp4z@privaterelay.appleid.com", "Jamie Doe", "a" * MAX_ACCOUNT_CHARS,
                  "  padded@example.com  ", "Ünïcode@Exämple.com"],
        "invalid": ["a" * (MAX_ACCOUNT_CHARS + 1), "line\nbreak", "tab\tinside", "nul\u0000", "evil‮moc.elpmaxe", "zero​width", "﻿bom"],
    }
    for value in accounts["valid"]:
        assert valid_account(value), value
    for value in accounts["invalid"]:
        assert not valid_account(value), value

    id_cases = []
    for site, provider, account in [("example.com", "github", "octocat"), ("example.com", "github", "OctoCat"),
                                    ("example.com", "github", "  octocat "), ("example.com", "github", ""),
                                    ("example.com", "google", "octocat"), ("bbc.co.uk", "apple", "x7k2m9qp4z@privaterelay.appleid.com"),
                                    ("example.com", "microsoft", "Ünïcode@Exämple.com"), ("example.com", "x", "ΟΔΥΣΣΕΥΣ"),
                                    ("example.com", "x", "İstanbul")]:
        id_cases.append({"site": site, "provider": provider, "account": account, "accountKey": account_key(account),
                         "entityId": entity_id(site, provider, account)})
    assert id_cases[0]["entityId"] == id_cases[1]["entityId"] == id_cases[2]["entityId"], "case and spaces never change the id"
    assert id_cases[0]["entityId"] != id_cases[3]["entityId"] != id_cases[4]["entityId"]

    def case(name, plaintext, eid, kind="upsert", expected=None):
        entry = {"name": name, "kind": kind, "entityId": eid, "plaintext": plaintext,
                 "payloadCiphertext": encrypt(plaintext, eid), "payloadNonce": base64.urlsafe_b64encode(NONCE).decode()}
        if expected is None:
            entry["valid"] = False
        else:
            entry["valid"] = True
            entry["expected"] = expected
        return entry

    gh = entity_id("example.com", "github", "octocat")
    good = method("example.com", "github", "OctoCat", 1_700_000_000_000, 1_790_000_000_000, 41)
    cases = [
        case("valid upsert", body(**good), gh, expected=good),
        case("unknown account is an empty string", body(**method("example.com", "github", "", 1, 2, 1)), entity_id("example.com", "github", ""),
             expected=method("example.com", "github", "", 1, 2, 1)),
        case("extra keys are ignored", json.dumps({"signInMethod": dict(good, icon="x"), "v": 2}, separators=(",", ":")), gh, expected=good),
        case("entity id of another account", body(**method("example.com", "github", "hubot", 1, 2, 1)), gh),
        case("entity id of another provider", body(**method("example.com", "google", "octocat", 1, 2, 1)), gh),
        case("unknown provider", body(**method("example.com", "gitlab", "octocat", 1, 2, 1)), entity_id("example.com", "gitlab", "octocat")),
        case("provider id in capitals", body(**method("example.com", "GitHub", "octocat", 1, 2, 1)), entity_id("example.com", "GitHub", "octocat")),
        case("invalid site", body(**method("Example.com", "github", "octocat", 1, 2, 1)), entity_id("Example.com", "github", "octocat")),
        case("account too long", body(**method("example.com", "github", "a" * 257, 1, 2, 1)), entity_id("example.com", "github", "a" * 257)),
        case("account with a bidi override", body(**method("example.com", "github", "evil‮x", 1, 2, 1)), entity_id("example.com", "github", "evil‮x")),
        case("first use after last use", body(**method("example.com", "github", "octocat", 5, 4, 1)), gh),
        case("negative time", body(**method("example.com", "github", "octocat", -1, 4, 1)), gh),
        case("use count zero", body(**method("example.com", "github", "octocat", 1, 2, 0)), gh),
        case("use count too large", body(**method("example.com", "github", "octocat", 1, 2, MAX_USE_COUNT + 1)), gh),
        case("time is a string", body(**method("example.com", "github", "octocat", "1", 2, 1)), gh),
        case("missing signInMethod", json.dumps({"signIn": good}, separators=(",", ":")), gh),
        case("valid delete", json.dumps({"deleted": True}, separators=(",", ":")), gh, kind="delete", expected={"deleted": True}),
        case("delete without the flag", json.dumps({"deleted": False}, separators=(",", ":")), gh, kind="delete"),
    ]

    # Concurrent upserts of one entity: min first, max last, max count, the later writer's account text.
    merges = [
        {"name": "later writer's spelling, both histories",
         "current": {"at": "2026-11-20T10:00:00Z", "device": "a", "method": method("example.com", "github", "octocat", 100, 500, 7)},
         "incoming": {"at": "2026-11-20T10:00:05Z", "device": "b", "method": method("example.com", "github", "OctoCat", 50, 400, 9)},
         "merged": method("example.com", "github", "OctoCat", 50, 500, 9)},
        {"name": "same instant: the greater device id writes later",
         "current": {"at": "2026-11-20T10:00:00Z", "device": "b", "method": method("example.com", "github", "Octocat", 10, 20, 2)},
         "incoming": {"at": "2026-11-20T10:00:00Z", "device": "a", "method": method("example.com", "github", "octocat", 15, 30, 1)},
         "merged": method("example.com", "github", "Octocat", 10, 30, 2)},
        {"name": "offsets compare as instants",
         "current": {"at": "2026-11-20T11:00:00+01:00", "device": "b", "method": method("example.com", "google", "me@example.com", 1, 2, 1)},
         "incoming": {"at": "2026-11-20T10:00:00.5Z", "device": "a", "method": method("example.com", "google", "Me@Example.com", 1, 3, 2)},
         "merged": method("example.com", "google", "Me@Example.com", 1, 3, 2)},
    ]
    # An upsert concurrent with a delete of the same entity: the later writer wins (no conflict to review).
    upsert_vs_delete = [
        {"deleteAt": "2026-11-20T10:00:00Z", "deleteDevice": "a", "upsertAt": "2026-11-20T10:00:01Z", "upsertDevice": "b", "result": "upsert"},
        {"deleteAt": "2026-11-20T10:00:01Z", "deleteDevice": "a", "upsertAt": "2026-11-20T10:00:00Z", "upsertDevice": "b", "result": "deleted"},
        {"deleteAt": "2026-11-20T10:00:00Z", "deleteDevice": "b", "upsertAt": "2026-11-20T10:00:00Z", "upsertDevice": "a", "result": "deleted"},
        {"deleteAt": "2026-11-20T10:00:00Z", "deleteDevice": "a", "upsertAt": "2026-11-20T10:00:00Z", "upsertDevice": "b", "result": "upsert"},
    ]

    vectors = {
        "format": "nuvori-sync-sign-in-method-vectors",
        "version": 1,
        "feature": "sign-in-methods-v1",
        "entityType": ENTITY_TYPE,
        "idPrefix": ID_PREFIX,
        "contentKeyHex": CONTENT_KEY.hex(),
        "vaultId": VAULT_ID,
        "mutationId": MUTATION_ID,
        "providers": PROVIDERS,
        "maxAccountChars": MAX_ACCOUNT_CHARS,
        "maxUseCount": MAX_USE_COUNT,
        "entityIds": id_cases,
        "sites": sites,
        "accounts": accounts,
        "cases": cases,
        "concurrentUpserts": merges,
        "upsertVersusDelete": upsert_vs_delete,
    }
    text = json.dumps(vectors, indent=2, ensure_ascii=True) + "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(text), "bytes")
    if len(sys.argv) > 1:
        windows = pathlib.Path(sys.argv[1]).resolve() / "src-tauri/fixtures/sign-in-methods-v1.json"
        windows.write_text(text, encoding="utf-8", newline="\n")
        print("wrote", windows)


if __name__ == "__main__":
    main()

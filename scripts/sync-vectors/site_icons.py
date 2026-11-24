"""Writes the shared test vectors of sync protocol `site-icons-v1`.

    python scripts/sync-vectors/site_icons.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/site-icons-v1.json, and with a Windows path also
src-tauri/fixtures/site-icons-v1.json there (the same bytes). Both apps test against the file.
Never edit the JSON by hand: change this script and run it again. The output is deterministic.

Protocol (Android SiteIconSyncRules.kt, Windows site_icon_sync.rs):
- Entity type "site_icon", always an upsert.
- Entity id: the first 32 lowercase hex characters of HMAC-SHA256(content key, "nuvori-sync-site-icon-id-v1:" + site).
  The id never shows the site, because the entity id travels in plain text.
- Plaintext {"siteIcon":{"site":"<site>","png":"<standard base64, padded>"}}; other keys are ignored.
- Encrypted like every entity: AES-256-GCM, key HMAC-SHA256(content key, "nuvori-sync-payload-key-v1"),
  AAD "nuvori-sync-site_icon-v1:<vaultId>:<mutationId>:<entityId>".
"""
import base64
import hashlib
import hmac
import json
import pathlib
import struct
import sys
import zlib

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
OUT = ANDROID / "app/src/test/resources/sync-vectors/site-icons-v1.json"

CONTENT_KEY = bytes(range(32))
VAULT_ID = "vault-site-icons-test"
MUTATION_ID = "2b7d0c4e-8a61-4f3b-9e2d-5c1a7f60b934"
NONCE = bytes(range(1, 13))
ID_PREFIX = "nuvori-sync-site-icon-id-v1:"
MAX_PNG_BYTES = 32 * 1024


def entity_id(site: str) -> str:
    return hmac.new(CONTENT_KEY, (ID_PREFIX + site).encode(), hashlib.sha256).hexdigest()[:32]


def chunk(kind: bytes, data: bytes) -> bytes:
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


def png(width: int, height: int, extra: bytes = b"", first: bytes = b"IHDR") -> bytes:
    """An RGBA PNG filled with one color. [extra] goes in a tEXt chunk, to make a large file."""
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    rows = b"".join(b"\x00" + bytes([0x24, 0x29, 0x2e, 0xff]) * width for _ in range(height))
    body = chunk(first, header) if first == b"IHDR" else chunk(first, b"x") + chunk(b"IHDR", header)
    if extra:
        body += chunk(b"tEXt", b"Comment\x00" + extra)
    return b"\x89PNG\r\n\x1a\n" + body + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode()


def encrypt(plaintext: str, eid: str) -> str:
    payload_key = hmac.new(CONTENT_KEY, b"nuvori-sync-payload-key-v1", hashlib.sha256).digest()
    aad = f"nuvori-sync-site_icon-v1:{VAULT_ID}:{MUTATION_ID}:{eid}".encode()
    return base64.urlsafe_b64encode(AESGCM(payload_key).encrypt(NONCE, plaintext.encode(), aad)).decode().rstrip("=")


def main():
    small, large, mid = png(16, 16), png(64, 64), png(32, 32)
    pngs = [
        {"name": "16x16", "base64": b64(small), "valid": True},
        {"name": "64x64", "base64": b64(large), "valid": True},
        {"name": "32x32", "base64": b64(mid), "valid": True},
        {"name": "15x16 is too small", "base64": b64(png(15, 16)), "valid": False},
        {"name": "65x64 is too large", "base64": b64(png(65, 64)), "valid": False},
        {"name": "16x80 is too tall", "base64": b64(png(16, 80)), "valid": False},
        {"name": "first chunk is not IHDR", "base64": b64(png(16, 16, first=b"tEXt")), "valid": False},
        {"name": "not a PNG signature", "base64": b64(b"GIF89a" + small[6:]), "valid": False},
        {"name": "truncated header", "base64": b64(small[:20]), "valid": False},
        {"name": "one byte over the limit", "base64": None, "valid": False},
        {"name": "exactly at the limit", "base64": None, "valid": True},
    ]
    # Pad a 32x32 icon with a text chunk to exactly the limit, then one byte more.
    base = len(png(32, 32, extra=b"a"))
    at_limit = png(32, 32, extra=b"a" * (1 + MAX_PNG_BYTES - base))
    over = png(32, 32, extra=b"a" * (2 + MAX_PNG_BYTES - base))
    assert len(at_limit) == MAX_PNG_BYTES and len(over) == MAX_PNG_BYTES + 1
    pngs[-2]["base64"] = b64(over)
    pngs[-1]["base64"] = b64(at_limit)

    sites = {
        "valid": ["github.com", "bbc.co.uk", "xn--bcher-kva.example", "a-b.io", "1password.com", "my-bank.example.org"],
        "invalid": ["", "GitHub.com", "github", "https://github.com", "github.com/", "-github.com", "github-.com",
                    "git_hub.com", "127.0.0.1", "github..com", ".github.com", "github.com.", "a" * 64 + ".com",
                    ("a" * 62 + ".") * 4 + "com", "gïthub.com", "github.com:443"],
    }

    def case(name, plaintext, eid, expected=None):
        entry = {"name": name, "entityId": eid, "plaintext": plaintext,
                 "payloadCiphertext": encrypt(plaintext, eid), "payloadNonce": base64.urlsafe_b64encode(NONCE).decode()}
        if expected is None:
            entry["valid"] = False
        else:
            entry["valid"] = True
            entry["expected"] = expected
        return entry

    def body(site, data, **extra):
        icon = {"site": site, "png": data}
        icon.update(extra)
        return json.dumps({"siteIcon": icon}, separators=(",", ":"))

    gh = entity_id("github.com")
    cases = [
        case("valid icon", body("github.com", b64(small)), gh,
             {"site": "github.com", "pngSha256": hashlib.sha256(small).hexdigest()}),
        case("extra keys are ignored", json.dumps({"siteIcon": {"site": "github.com", "png": b64(large), "title": "x"},
                                                   "v": 2}, separators=(",", ":")), gh,
             {"site": "github.com", "pngSha256": hashlib.sha256(large).hexdigest()}),
        case("entity id of another site", body("gitlab.com", b64(small)), gh),
        case("invalid site", body("GitHub.com", b64(small)), entity_id("GitHub.com")),
        case("invalid PNG", body("github.com", b64(png(8, 8))), gh),
        case("PNG is not base64", body("github.com", "@@@@"), gh),
        case("PNG in URL-safe base64", body("github.com", base64.urlsafe_b64encode(b"\xfb\xff" + small).decode()), gh),
        case("PNG is not a string", json.dumps({"siteIcon": {"site": "github.com", "png": 7}}), gh),
        case("missing siteIcon", json.dumps({"icon": {"site": "github.com", "png": b64(small)}}), gh),
    ]

    # Last writer wins between concurrent changes: the later instant, then the greater device ID.
    lww = [
        {"currentAt": "2026-11-20T10:00:00Z", "currentDevice": "b", "incomingAt": "2026-11-20T10:00:01Z", "incomingDevice": "a", "incomingWins": True},
        {"currentAt": "2026-11-20T10:00:01Z", "currentDevice": "a", "incomingAt": "2026-11-20T10:00:00Z", "incomingDevice": "z", "incomingWins": False},
        {"currentAt": "2026-11-20T10:00:00Z", "currentDevice": "a", "incomingAt": "2026-11-20T10:00:00Z", "incomingDevice": "b", "incomingWins": True},
        {"currentAt": "2026-11-20T10:00:00Z", "currentDevice": "b", "incomingAt": "2026-11-20T10:00:00Z", "incomingDevice": "a", "incomingWins": False},
        {"currentAt": "2026-11-20T11:00:00+01:00", "currentDevice": "b", "incomingAt": "2026-11-20T10:00:00.5Z", "incomingDevice": "a", "incomingWins": True},
    ]

    vectors = {
        "format": "nuvori-sync-site-icon-vectors",
        "version": 1,
        "feature": "site-icons-v1",
        "entityType": "site_icon",
        "idPrefix": ID_PREFIX,
        "contentKeyHex": CONTENT_KEY.hex(),
        "vaultId": VAULT_ID,
        "mutationId": MUTATION_ID,
        "maxPngBytes": MAX_PNG_BYTES,
        "minSide": 16,
        "maxSide": 64,
        "entityIds": [{"site": s, "entityId": entity_id(s)} for s in sites["valid"]],
        "sites": sites,
        "pngs": pngs,
        "cases": cases,
        "lastWriterWins": lww,
    }
    text = json.dumps(vectors, indent=2, ensure_ascii=True) + "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(text), "bytes")
    if len(sys.argv) > 1:
        windows = pathlib.Path(sys.argv[1]).resolve() / "src-tauri/fixtures/site-icons-v1.json"
        windows.write_text(text, encoding="utf-8", newline="\n")
        print("wrote", windows)


if __name__ == "__main__":
    main()

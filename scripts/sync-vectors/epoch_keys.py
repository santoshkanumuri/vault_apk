"""Writes the shared test vectors of sync protocol `epoch-keys-v1` (new keys when a device is removed).

    python scripts/sync-vectors/epoch_keys.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/epoch-keys-v1.json, and with a Windows path also
src-tauri/fixtures/epoch-keys-v1.json there (the same bytes). Never edit the JSON by hand: change
this script and run it again. The output is deterministic. Android EpochKeys.kt, Windows epoch_keys.rs.

HKDF is HKDF-SHA256 (RFC 5869). Text is UTF-8. base64url has no padding.

Device key. x25519 private = HKDF(ikm = the device's Ed25519 seed, salt = none, info =
"nuvori-device-x25519-v1", 32 bytes); the public key is X25519(private, 9).

Key statement. "nuvori-device-key-v1\\n<vaultId>\\n<deviceId>\\n<x25519 public, base64url>", signed by
the device's Ed25519 identity. JSON {"vaultId","deviceId","x25519","signature"}.

Package for epoch N to device D (made by the managing device at a REMOVE):
    e = random X25519 private; E = X25519(e, 9); Z = X25519(e, D's public); Z must not be all zero
    key  = HKDF(ikm = Z, salt = E || D's public, info = "nuvori-epoch-key-v1:<vaultId>:<N>:<D>", 32)
    package = E (32) || nonce (12) || AES-256-GCM(key, S(N) || P(N), AAD = "<vaultId>:<N>:<D>")
S(N) is the epoch's transport secret and P(N) its package key, 32 random bytes each. The content key
is K(N) = HKDF(ikm = P(N), salt = K(1), info = "nuvori-epoch-content-v1:<vaultId>:<N>", 32), with
K(1) the group's epoch-1 content key. So a package alone (an X25519 key that a locked phone can use)
gives the transport secret but never the content key: that also needs K(1), which members keep only
in the vault (SQLCipher on Android, vault.db on Windows) behind the master password.

Package set. "nuvori-epoch-keys-v1\\n<vaultId>\\n<N>\\n<removeEventHash>\\n" and one
"<deviceId>:<sha256 hex of the package>\\n" per package, sorted by device ID, signed by the manager.
A set with no packages is a signed marker: the REMOVE at that epoch made no new keys (the gate was
closed), so a survivor stops waiting for a package and uses the derived secret.
base64url text in statements, packages and signatures must be canonical (no padding, unused bits
zero): both apps decode strictly, so the text can't change while the bytes stay the same.

A change of epoch N >= 2: payloadNonce "e<N>.<base64url 12-byte nonce>" (N in decimal, no leading
zero); AAD "nuvori-sync-<type>-v2:<vaultId>:<N>:<mutationId>:<entityId>"; payload key =
HMAC-SHA256(K(N), "nuvori-sync-payload-key-v1"). Epoch 1 stays as before (a bare 16-character nonce,
AAD v1, the group's content key). A photo of epoch N says "keyEpoch": N; its blob key is
HMAC-SHA256(K(N), "nuvori-photo-blob-v1").

Transport secret of epoch N: the stored random S(N); else HMAC-SHA256(B, "nuvori-transport-epoch-v1:
<vaultId>:<N>") with B the newest random S below N, or the root secret when the group never rotated.

Google Drive. cloud key(N) = HMAC-SHA256(S, "nuvori-cloud-v1:<vaultId>") with S the newest random
transport secret at or below N (the group's root secret when there is none). Packages are files
"nv1.<old group tag>.e.<N>.<recipient tag>" under the previous prefix; statements "k.<device tag>".
Under a rotated prefix (a random S, epoch >= 2) a photo blob file "b.<sha256>" is sealed in the same
envelope as every other file (under the root prefix it stays raw, as older builds read it).

Drive progress "p.<device tag>" is signed by the device's identity:
    "nuvori-drive-progress-v1\n<vaultId>\n<deviceId>\n<prefixEpoch>\n<updatedAt>\n<keyEpoch>\n"
    "<sha256 hex of progressText>\n<features, sorted, joined by ','>"
prefixEpoch is the epoch of the drive secret whose prefix holds the file. The body keeps "progress"
(an object, for older builds) and adds "progressText" (the exact progress JSON that was signed),
"prefixEpoch" and "signature". A file without a valid signature is unknown progress.
"""
import base64
import hashlib
import hmac
import json
import pathlib
import sys

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
OUT = ANDROID / "app/src/test/resources/sync-vectors/epoch-keys-v1.json"
VAULT = "vault-epoch-test"
MANAGER_SEED = bytes([11] * 32)
DEVICES = {"dev-pc": bytes([12] * 32), "dev-tablet": bytes([13] * 32), "dev-removed": bytes([14] * 32)}


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def hkdf(ikm: bytes, salt, info: str) -> bytes:
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=salt, info=info.encode()).derive(ikm)


def x25519_private(seed: bytes) -> bytes:
    return hkdf(seed, None, "nuvori-device-x25519-v1")


def x25519_public(private: bytes) -> bytes:
    return X25519PrivateKey.from_private_bytes(private).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def ed_public(seed: bytes) -> bytes:
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw)


def sign(seed: bytes, data: bytes) -> str:
    return b64(Ed25519PrivateKey.from_private_bytes(seed).sign(data))


def statement_bytes(vault: str, device: str, public: bytes) -> bytes:
    return f"nuvori-device-key-v1\n{vault}\n{device}\n{b64(public)}".encode()


def package(vault: str, epoch: int, device: str, recipient: bytes, ephemeral: bytes, nonce: bytes,
            secret: bytes, key: bytes) -> bytes:
    e = X25519PrivateKey.from_private_bytes(ephemeral)
    epk = x25519_public(ephemeral)
    shared = e.exchange(X25519PublicKey.from_public_bytes(recipient))
    assert shared != bytes(32)
    wrap = hkdf(shared, epk + recipient, f"nuvori-epoch-key-v1:{vault}:{epoch}:{device}")
    return epk + nonce + AESGCM(wrap).encrypt(nonce, secret + key, f"{vault}:{epoch}:{device}".encode())


def open_package(vault: str, epoch: int, device: str, private: bytes, data: bytes):
    epk, nonce, sealed = data[:32], data[32:44], data[44:]
    shared = X25519PrivateKey.from_private_bytes(private).exchange(X25519PublicKey.from_public_bytes(epk))
    if shared == bytes(32):
        return None
    wrap = hkdf(shared, epk + x25519_public(private), f"nuvori-epoch-key-v1:{vault}:{epoch}:{device}")
    try:
        plain = AESGCM(wrap).decrypt(nonce, sealed, f"{vault}:{epoch}:{device}".encode())
    except Exception:
        return None
    return plain[:32], plain[32:]


def set_bytes(vault: str, epoch: int, remove_hash: str, packages: dict) -> bytes:
    lines = [f"nuvori-epoch-keys-v1", vault, str(epoch), remove_hash]
    text = "\n".join(lines) + "\n"
    for device in sorted(packages, key=lambda d: d.encode()):
        text += f"{device}:{hashlib.sha256(packages[device]).hexdigest()}\n"
    return text.encode()


def content_key(k1: bytes, vault: str, epoch: int, package_key: bytes) -> bytes:
    return hkdf(package_key, k1, f"nuvori-epoch-content-v1:{vault}:{epoch}")


def progress_bytes(vault: str, device: str, prefix_epoch: int, updated_at: int, key_epoch: int, progress_text: str,
                   features) -> bytes:
    digest = hashlib.sha256(progress_text.encode()).hexdigest()
    return (f"nuvori-drive-progress-v1\n{vault}\n{device}\n{prefix_epoch}\n{updated_at}\n{key_epoch}\n{digest}\n"
            + ",".join(sorted(features))).encode()


def seal(key: bytes, name: str, nonce: bytes, plain: bytes) -> bytes:
    return b"NVC1" + nonce + AESGCM(key).encrypt(nonce, plain, name.encode())


def payload_key(content_key: bytes) -> bytes:
    return hmac.new(content_key, b"nuvori-sync-payload-key-v1", hashlib.sha256).digest()


def nonce_field(epoch: int, nonce: bytes) -> str:
    return b64(nonce) if epoch == 1 else f"e{epoch}.{b64(nonce)}"


def aad(entity_type: str, vault: str, epoch: int, mutation: str, entity: str) -> str:
    if epoch == 1:
        return f"nuvori-sync-{entity_type}-v1:{vault}:{mutation}:{entity}"
    return f"nuvori-sync-{entity_type}-v2:{vault}:{epoch}:{mutation}:{entity}"


def cloud_key(secret: bytes, vault: str) -> bytes:
    return hmac.new(secret, f"nuvori-cloud-v1:{vault}".encode(), hashlib.sha256).digest()


def short_mac(key: bytes, label: str) -> str:
    return hmac.new(key, label.encode(), hashlib.sha256).digest()[:8].hex()


def transport_epoch(base: bytes, vault: str, epoch: int) -> bytes:
    """The HMAC-derived transport secret of an epoch without its own random secret (MembershipNotices.kt).
    `base` is the newest random secret below the epoch, or the group's root secret when there is none:
    a device removed at a rotation knows the root but never the random secret after it."""
    return hmac.new(base, f"nuvori-transport-epoch-v1:{vault}:{epoch}".encode(), hashlib.sha256).digest()


def main():
    root = bytes(range(32))
    k1 = bytes(range(32, 64))
    s2, p2 = bytes([0xA2] * 32), bytes([0xB2] * 32)
    k2 = content_key(k1, VAULT, 2, p2)
    assert k2 != p2
    devices = {}
    for device, seed in sorted(DEVICES.items()):
        private = x25519_private(seed)
        public = x25519_public(private)
        statement = statement_bytes(VAULT, device, public)
        devices[device] = {"ed25519Seed": seed.hex(), "ed25519Public": b64(ed_public(seed)), "x25519Private": private.hex(),
                           "x25519": b64(public), "statementHex": statement.hex(), "signature": sign(seed, statement)}
    remove_hash = "cd" * 32
    survivors = ["dev-pc", "dev-tablet"]
    packages = {}
    for index, device in enumerate(survivors):
        ephemeral = bytes([0x40 + index] * 32)
        nonce = bytes([0x50 + index] * 12)
        packages[device] = package(VAULT, 2, device, unb64(devices[device]["x25519"]), ephemeral, nonce, s2, p2)
        assert open_package(VAULT, 2, device, bytes.fromhex(devices[device]["x25519Private"]), packages[device]) == (s2, p2)
    # The removed device can't open a package, nor can the wrong survivor; a changed AAD fails too.
    removed_private = bytes.fromhex(devices["dev-removed"]["x25519Private"])
    assert open_package(VAULT, 2, "dev-pc", removed_private, packages["dev-pc"]) is None
    assert open_package(VAULT, 2, "dev-tablet", bytes.fromhex(devices["dev-pc"]["x25519Private"]), packages["dev-pc"]) is None
    assert open_package(VAULT, 3, "dev-pc", bytes.fromhex(devices["dev-pc"]["x25519Private"]), packages["dev-pc"]) is None
    tampered = bytearray(packages["dev-pc"])
    tampered[-1] ^= 1
    set_statement = set_bytes(VAULT, 2, remove_hash, packages)

    # A payload of epoch 2, and the same payload of epoch 1 (today's format).
    plaintext = b'{"entry":{"id":"e1","title":"Mail"},"groupIds":[]}'
    mutation, entity, nonce = "mutation-1", "e1", bytes([0x33] * 12)
    payloads = []
    for epoch, key in ((1, k1), (2, k2)):
        sealed = AESGCM(payload_key(key)).encrypt(nonce, plaintext, aad("entry", VAULT, epoch, mutation, entity).encode())
        payloads.append({"epoch": epoch, "entityType": "entry", "mutationId": mutation, "entityId": entity,
                         "payloadNonce": nonce_field(epoch, nonce), "payloadCiphertext": b64(sealed),
                         "aad": aad("entry", VAULT, epoch, mutation, entity), "plaintext": plaintext.decode()})
    # Epoch 2 under K(1) fails: different key and AAD.
    try:
        AESGCM(payload_key(k1)).decrypt(nonce, unb64(payloads[1]["payloadCiphertext"]),
                                         aad("entry", VAULT, 2, mutation, entity).encode())
        raise AssertionError("epoch 2 opened with K(1)")
    except Exception as failure:
        assert "InvalidTag" in type(failure).__name__

    # Drive: epoch 1 (root), epoch 2 (random S(2)), epoch 3 (no rotation: still S(2)).
    clouds = []
    for epoch, secret in ((1, root), (2, s2), (3, s2)):
        key = cloud_key(secret, VAULT)
        clouds.append({"epoch": epoch, "cloudKeyHex": key.hex(), "groupTag": short_mac(key, "tag"),
                       "prefix": f"nv1.{short_mac(key, 'tag')}."})
    old_key = cloud_key(root, VAULT)
    package_file = f"nv1.{short_mac(old_key, 'tag')}.e.2.{short_mac(old_key, 'device:dev-pc')}"
    package_body = json.dumps({"vaultId": VAULT, "epoch": 2, "removeHash": remove_hash, "packages": {d: b64(p) for d, p in sorted(packages.items())},
                               "signature": sign(MANAGER_SEED, set_statement)}, separators=(",", ":")).encode()
    envelope_nonce = bytes([0x61] * 12)
    package_envelope = b"NVC1" + envelope_nonce + AESGCM(old_key).encrypt(envelope_nonce, package_body, package_file.encode())
    # A marker set: the REMOVE at epoch 3 made no new keys (a survivor had no statement).
    marker_hash = "ef" * 32
    marker_statement = set_bytes(VAULT, 3, marker_hash, {})
    # Canonical base64url only: this text decodes to the same bytes in a lenient decoder.
    canonical = b64(packages["dev-pc"])
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    lenient = canonical[:-1] + alphabet[alphabet.index(canonical[-1]) | 1]
    assert lenient != canonical and unb64(lenient) == unb64(canonical)
    # A photo blob under the epoch-2 prefix is sealed like every other file.
    k2_cloud = cloud_key(s2, VAULT)
    blob = bytes(range(40))
    blob_name = f"nv1.{short_mac(k2_cloud, 'tag')}.b.{hashlib.sha256(blob).hexdigest()}"
    blob_nonce = bytes([0x62] * 12)
    # A signed progress file of dev-pc under the epoch-2 prefix.
    progress_text = json.dumps({"received": {"dev-pc": [3, "aa" * 32], "dev-tablet": [2, "bb" * 32]},
                                "applied": {"dev-pc": [3, "aa" * 32], "dev-tablet": [1, "cc" * 32]}}, separators=(",", ":"))
    features = ["epoch-keys-v1", "removal-cutoff-v1"]
    progress_signed = progress_bytes(VAULT, "dev-pc", 2, 1795000000000, 2, progress_text, features)
    progress_name = f"nv1.{short_mac(k2_cloud, 'tag')}.p.{short_mac(k2_cloud, 'device:dev-pc')}"
    statement_file = f"nv1.{short_mac(cloud_key(s2, VAULT), 'tag')}.k.{short_mac(cloud_key(s2, VAULT), 'device:dev-pc')}"

    nonces = [
        {"field": b64(nonce), "epoch": 1, "valid": True},
        # A bare nonce may start with "e": only a "." marks the epoch form.
        {"field": "e" + b64(bytes([0x7B] + [0x33] * 11))[1:], "epoch": 1, "valid": True},
        {"field": f"e2.{b64(nonce)}", "epoch": 2, "valid": True},
        {"field": f"e17.{b64(nonce)}", "epoch": 17, "valid": True},
        {"field": f"e1.{b64(nonce)}", "epoch": 0, "valid": False},
        {"field": f"e0.{b64(nonce)}", "epoch": 0, "valid": False},
        {"field": f"e02.{b64(nonce)}", "epoch": 0, "valid": False},
        {"field": f"e.{b64(nonce)}", "epoch": 0, "valid": False},
        {"field": f"e2{b64(nonce)}", "epoch": 0, "valid": False},
        {"field": f"e2.{b64(nonce[:11])}", "epoch": 0, "valid": False},
        {"field": f"e99999999999.{b64(nonce)}", "epoch": 0, "valid": False},
    ]
    vectors = {
        "format": "nuvori-sync-epoch-keys-vectors",
        "version": 1,
        "feature": "epoch-keys-v1",
        "vaultId": VAULT,
        "managerSeed": MANAGER_SEED.hex(),
        "managerPublic": b64(ed_public(MANAGER_SEED)),
        "devices": devices,
        "epoch": 2,
        "transportSecretHex": s2.hex(),
        "packageKeyHex": p2.hex(),
        "contentKeyHex": k2.hex(),
        "epoch1ContentKeyHex": k1.hex(),
        "rootTransportSecretHex": root.hex(),
        "epoch2DerivedTransportHex": transport_epoch(root, VAULT, 2).hex(),
        "epoch3AfterRotationTransportHex": transport_epoch(s2, VAULT, 3).hex(),
        "removeEventHash": remove_hash,
        "packages": {device: b64(data) for device, data in sorted(packages.items())},
        "tamperedPackage": {"device": "dev-pc", "package": b64(bytes(tampered))},
        "setStatementHex": set_statement.hex(),
        "setSignature": sign(MANAGER_SEED, set_statement),
        "payloads": payloads,
        "nonces": nonces,
        "cloud": clouds,
        "packageFile": {"name": package_file, "bodyJson": package_body.decode(), "envelopeNonceHex": envelope_nonce.hex(),
                        "sealedHex": package_envelope.hex()},
        "statementFileName": statement_file,
        "markerSet": {"vaultId": VAULT, "epoch": 3, "removeHash": marker_hash, "packages": {},
                      "setStatementHex": marker_statement.hex(), "signature": sign(MANAGER_SEED, marker_statement)},
        "nonCanonicalPackage": {"device": "dev-pc", "canonical": canonical, "lenient": lenient},
        "blobFile": {"name": blob_name, "blobHex": blob.hex(), "envelopeNonceHex": blob_nonce.hex(),
                     "sealedHex": seal(k2_cloud, blob_name, blob_nonce, blob).hex()},
        "progressFile": {"name": progress_name, "deviceId": "dev-pc", "devicePublic": devices["dev-pc"]["ed25519Public"],
                         "prefixEpoch": 2, "updatedAt": 1795000000000, "keyEpoch": 2, "progressText": progress_text,
                         "features": features, "signingHex": progress_signed.hex(),
                         "signature": sign(DEVICES["dev-pc"], progress_signed)},
    }
    text = json.dumps(vectors, indent=2, ensure_ascii=True) + "\n"
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(text), "bytes")
    if len(sys.argv) > 1:
        windows = pathlib.Path(sys.argv[1]).resolve() / "src-tauri/fixtures/epoch-keys-v1.json"
        windows.write_text(text, encoding="utf-8", newline="\n")
        print("wrote", windows)


if __name__ == "__main__":
    main()

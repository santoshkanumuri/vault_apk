"""Writes the shared test vectors of sync protocol `checkpoint-enroll-v1` (enrollment snapshot v4).

    python scripts/sync-vectors/checkpoint.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/checkpoint-v1.json, and with a Windows path also
src-tauri/fixtures/checkpoint-v1.json there (the same bytes). Both apps test against the file.
Never edit the JSON by hand: change this script and run it again. The output is deterministic.

Protocol (Android SyncCheckpoint.kt and SyncSnapshot.kt, Windows checkpoint.rs and snapshot.rs):

Keep set. A change c of author A is kept when any of these hold:
 1. head witness: c is A's newest change;
 2. entity witness: c explains the current record state of its entity:
    - entry, group, passkey, photo, photo_cover: the change whose record version is EQUAL to the
      record state (the greatest (deviceId, sequence) when several are);
    - appearance, site_icon: the last-writer-wins winner among the entity's upserts that no other
      upsert dominates (later occurredAtUtc, then the greater device ID);
    - device_name: the newest change of each author for the entity;
    - sign_in_method (merged, sign-in-methods-v1): every change of the entity that no other change of
      the entity includes, upserts and deletes, so a joiner can merge them again;
 3. tombstone witness: c is the change of a tombstone;
 4. conflict witness: c is the local or remote change of an unresolved conflict;
 5. safety net: every change of an entry/group/passkey/photo/photo_cover entity whose record state
    no change explains, or that has neither a record state nor a tombstone; every change of an
    entity type this build does not know.

Checkpoint signing bytes: UTF-8 lines, each ending with "\\n":
    nuvori-checkpoint-v1, vaultId, issuerDeviceId, membershipCount, membershipHead, createdAt,
    "heads <n>", one "<deviceId>:<sequence>:<hash>" per author (sorted, sequence 0 left out),
    "ops <digest>", "records <digest>", "tombstones <digest>", "conflicts <digest>".
Digests: SHA-256, lowercase hex, of the lines sorted by UTF-8 bytes and joined with "\\n":
    operations  <hash>
    records     <entityType>\\t<entityId>\\t<revision>\\t<dev=n,dev=n sorted by device ID>
    tombstones  <entityType>\\t<entityId>\\t<changeHash>
    conflicts   <conflictId>\\t<localChangeHash>\\t<remoteChangeHash> (unresolved only)
The managing device signs the bytes with Ed25519 (base64url without padding).
"""
import base64
import copy
import hashlib
import hmac
import json
import pathlib
import struct
import sys
from datetime import datetime

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
OUT = ANDROID / "app/src/test/resources/sync-vectors/checkpoint-v1.json"

VAULT_ID = "vault-checkpoint-test"
CONTENT_KEY = bytes(range(32))
TRANSPORT_SECRET = bytes(range(32, 64))
GENESIS = "0" * 64
CREATED_AT = 1795000000000
ORDINARY = {"entry", "group", "passkey", "photo", "photo_cover"}
LAST_WRITER_WINS = {"appearance", "site_icon"}
MERGED = {"sign_in_method"}
DEVICES = {
    "dev-phone": bytes([1] * 32),
    "dev-pc": bytes([2] * 32),
    "dev-tablet": bytes([3] * 32),
    "dev-joiner": bytes([4] * 32),
}


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def key(device):
    return Ed25519PrivateKey.from_private_bytes(DEVICES[device])


def public(device) -> str:
    return b64(key(device).public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))


def sign(device, data: bytes) -> str:
    return b64(key(device).sign(data))


def utf8_sorted(lines):
    return sorted(lines, key=lambda line: line.encode("utf-8"))


def version_json(counters: dict) -> str:
    return json.dumps({"counters": dict(sorted(counters.items()))}, separators=(",", ":"))


def relation(left: dict, right: dict) -> str:
    less = any(left.get(k, 0) < right.get(k, 0) for k in set(left) | set(right))
    greater = any(left.get(k, 0) > right.get(k, 0) for k in set(left) | set(right))
    return "EQUAL" if not less and not greater else "BEFORE" if less and not greater else \
        "AFTER" if greater and not less else "CONCURRENT"


# Membership events: Kotlin DataOutputStream layout (SyncMembershipEvent.signingBytes).
def write_string(value: str) -> bytes:
    data = value.encode("utf-8")
    return struct.pack(">i", len(data)) + data


def membership_event(sequence, previous, action, issuer, subject, subject_key, epoch):
    body = (struct.pack(">i", 1) + write_string(VAULT_ID) + struct.pack(">q", sequence) + write_string(previous) +
            write_string(action) + write_string(issuer) + write_string(subject) + write_string(subject_key) +
            struct.pack(">q", epoch))
    signature = sign(issuer, body)
    digest = hashlib.sha256(body + signature.encode("ascii")).hexdigest()
    return {"vaultId": VAULT_ID, "sequence": sequence, "formatVersion": 1, "previousHash": previous,
            "action": action, "issuerDeviceId": issuer, "subjectDeviceId": subject,
            "subjectPublicKey": subject_key, "keyEpoch": epoch, "signature": signature, "hash": digest}


# Changes: SyncChangeRecord canonical bytes (u64 length before every field).
def field(data: bytes) -> bytes:
    return struct.pack(">Q", len(data)) + data


def canonical(change, with_signature):
    out = field(struct.pack(">H", 1)) + field(VAULT_ID.encode()) + field(change["deviceId"].encode())
    out += field(struct.pack(">Q", change["sequence"])) + field(change["previousHash"].encode())
    out += field(change["mutationId"].encode()) + field(change["entityType"].encode())
    out += field(change["entityId"].encode()) + field(change["kind"].encode())
    out += field(struct.pack(">Q", change["baseRevision"]))
    counters = json.loads(change["recordVersionJson"])["counters"]
    out += field(struct.pack(">Q", len(counters)))
    for device in sorted(counters, key=lambda d: d.encode("utf-16-be")):
        out += field(device.encode()) + field(struct.pack(">Q", counters[device]))
    out += field(change["occurredAtUtc"].encode()) + field(change["payloadCiphertext"].encode())
    out += field(change["payloadNonce"].encode())
    if with_signature:
        out += field(change["deviceSignature"].encode())
    return out


def make_change(author, signer, sequence, previous, entity_type, entity_id, kind, counters, at):
    mutation = f"m-{author}-{sequence:03d}"
    nonce = hashlib.sha256(mutation.encode()).digest()[:12]
    payload_key = hmac.new(CONTENT_KEY, b"nuvori-sync-payload-key-v1", hashlib.sha256).digest()
    aad = f"nuvori-sync-{entity_type}-v1:{VAULT_ID}:{mutation}:{entity_id}".encode()
    ciphertext = AESGCM(payload_key).encrypt(nonce, b'{"vector":true}', aad)
    change = {"mutationId": mutation, "formatVersion": 1, "vaultId": VAULT_ID, "deviceId": author,
              "sequence": sequence, "previousHash": previous, "entityType": entity_type, "entityId": entity_id,
              "kind": kind, "baseRevision": 0, "recordVersionJson": version_json(counters),
              "occurredAtUtc": at, "payloadCiphertext": b64(ciphertext), "payloadNonce": b64(nonce),
              "deviceSignature": "", "hash": ""}
    change["deviceSignature"] = sign(signer, canonical(change, False))
    change["hash"] = hashlib.sha256(canonical(change, True)).hexdigest()
    return change


def history():
    """40 changes by 3 authors over 7 entities, in the order they happened."""
    steps = [  # author, entity type, entity id, kind, counters
        ("dev-phone", "group", "g1", "upsert", {"dev-phone": 1}),
        ("dev-phone", "entry", "e1", "upsert", {"dev-phone": 1}),
        ("dev-pc", "entry", "e1", "upsert", {"dev-phone": 1, "dev-pc": 1}),
        ("dev-tablet", "entry", "e3", "upsert", {"dev-tablet": 1}),
        ("dev-phone", "entry", "e2", "upsert", {"dev-phone": 1}),
        ("dev-pc", "device_name", "dev-pc", "upsert", {"dev-pc": 1}),
        ("dev-tablet", "entry", "e3", "upsert", {"dev-tablet": 2}),
        ("dev-pc", "entry", "e3", "upsert", {"dev-tablet": 2, "dev-pc": 1}),
        ("dev-phone", "entry", "e1", "upsert", {"dev-phone": 2}),  # concurrent with pc's e1 edit
        ("dev-phone", "entry", "e1", "upsert", {"dev-phone": 3, "dev-pc": 1}),  # resolves that conflict
        ("dev-pc", "site_icon", "s1", "upsert", {"dev-pc": 1}),
        ("dev-pc", "site_icon", "s1", "upsert", {"dev-pc": 2}),
        ("dev-phone", "appearance", "vault", "upsert", {"dev-phone": 1}),
        ("dev-pc", "appearance", "vault", "upsert", {"dev-pc": 1}),  # concurrent and later: it wins
        ("dev-tablet", "group", "g1", "upsert", {"dev-phone": 1, "dev-tablet": 1}),
        ("dev-tablet", "group", "g1", "upsert", {"dev-phone": 1, "dev-tablet": 2}),
        ("dev-phone", "entry", "e3", "delete", {"dev-tablet": 2, "dev-pc": 1, "dev-phone": 1}),
        ("dev-pc", "entry", "e2", "upsert", {"dev-phone": 1, "dev-pc": 1}),
        ("dev-phone", "entry", "e2", "upsert", {"dev-phone": 2}),  # concurrent: an unresolved conflict
    ]
    steps += [("dev-tablet", "group", "g1", "upsert", {"dev-phone": 1, "dev-tablet": n}) for n in range(3, 7)]
    steps += [("dev-phone", "entry", "e1", "upsert", {"dev-phone": n, "dev-pc": 1}) for n in range(4, 12)]
    steps += [("dev-pc", "device_name", "dev-pc", "upsert", {"dev-pc": n}) for n in range(2, 8)]
    steps += [("dev-pc", "site_icon", "s1", "upsert", {"dev-pc": n}) for n in range(3, 6)]
    heads, changes = {}, []
    for index, (author, entity_type, entity_id, kind, counters) in enumerate(steps):
        sequence, previous = heads.get(author, (0, GENESIS))
        at = f"2026-11-01T10:{index:02d}:00Z"
        change = make_change(author, author, sequence + 1, previous, entity_type, entity_id, kind, counters, at)
        heads[author] = (sequence + 1, change["hash"])
        changes.append(change)
    assert len(changes) == 40
    return changes


def find(changes, author, sequence):
    return next(c for c in changes if c["deviceId"] == author and c["sequence"] == sequence)


def instant(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def lww_winner(candidates):
    counters = [(c, json.loads(c["recordVersionJson"])["counters"]) for c in candidates if c["kind"] == "upsert"]
    maximal = [c for c, v in counters if not any(relation(v, o) == "BEFORE" for _, o in counters)]
    best = None
    for c in maximal:
        if best is None or (instant(c["occurredAtUtc"]), c["deviceId"]) > (instant(best["occurredAtUtc"]), best["deviceId"]):
            best = c
    return best


def keep_set(changes, records, tombstones, conflicts):
    """The reference implementation of the keep set (see the module text)."""
    kept = set()
    heads = {}
    for c in changes:
        if c["sequence"] > heads.get(c["deviceId"], {"sequence": 0})["sequence"]:
            heads[c["deviceId"]] = c
    kept |= {c["hash"] for c in heads.values()}
    by_entity = {}
    for c in changes:
        by_entity.setdefault((c["entityType"], c["entityId"]), []).append(c)
    states = {(r["entityType"], r["entityId"]): r for r in records}
    deleted = {(t["entityType"], t["entityId"]) for t in tombstones}
    for (entity_type, entity_id), group in by_entity.items():
        if entity_type in LAST_WRITER_WINS:
            winner = lww_winner(group)
            if winner:
                kept.add(winner["hash"])
        elif entity_type in MERGED:
            versions = [(c, json.loads(c["recordVersionJson"])["counters"]) for c in group]
            kept |= {c["hash"] for c, v in versions
                     if not any(o["hash"] != c["hash"] and relation(v, w) == "BEFORE" for o, w in versions)}
        elif entity_type == "device_name":
            newest = {}
            for c in group:
                if c["sequence"] > newest.get(c["deviceId"], {"sequence": 0})["sequence"]:
                    newest[c["deviceId"]] = c
            kept |= {c["hash"] for c in newest.values()}
        elif entity_type in ORDINARY:
            state = states.get((entity_type, entity_id))
            if state is None:
                if (entity_type, entity_id) not in deleted:
                    kept |= {c["hash"] for c in group}
                continue
            target = json.loads(state["recordVersionJson"])["counters"]
            equal = [c for c in group if relation(json.loads(c["recordVersionJson"])["counters"], target) == "EQUAL"]
            if equal:
                kept.add(max(equal, key=lambda c: (c["deviceId"].encode(), c["sequence"]))["hash"])
            else:
                kept |= {c["hash"] for c in group}
        else:
            kept |= {c["hash"] for c in group}
    kept |= {t["changeHash"] for t in tombstones}
    for conflict in conflicts:
        if conflict["resolvedAtUtc"] == "":
            kept |= {conflict["localChangeHash"], conflict["remoteChangeHash"]} - {GENESIS}
    return kept


def canonical_version(json_text):
    counters = json.loads(json_text)["counters"]
    return ",".join(f"{d}={counters[d]}" for d in sorted(counters, key=lambda d: d.encode()))


def digest(lines):
    return hashlib.sha256("\n".join(utf8_sorted(lines)).encode("utf-8")).hexdigest()


def digests(state):
    return {
        "operations": digest([c["hash"] for c in state["operations"]]),
        "records": digest([f'{r["entityType"]}\t{r["entityId"]}\t{r["revision"]}\t{canonical_version(r["recordVersionJson"])}'
                           for r in state["records"]]),
        "tombstones": digest([f'{t["entityType"]}\t{t["entityId"]}\t{t["changeHash"]}' for t in state["tombstones"]]),
        "conflicts": digest([f'{c["conflictId"]}\t{c["localChangeHash"]}\t{c["remoteChangeHash"]}'
                             for c in state["conflicts"] if c["resolvedAtUtc"] == ""]),
    }


def signing_bytes(cp):
    heads = [h for h in cp["heads"] if h["sequence"] > 0]
    lines = ["nuvori-checkpoint-v1", cp["vaultId"], cp["issuerDeviceId"], str(cp["membershipCount"]),
             cp["membershipHead"], str(cp["createdAt"]), f"heads {len(heads)}"]
    lines += [f'{h["deviceId"]}:{h["sequence"]}:{h["hash"]}' for h in sorted(heads, key=lambda h: h["deviceId"].encode())]
    lines += [f'ops {cp["operationsDigest"]}', f'records {cp["recordsDigest"]}',
              f'tombstones {cp["tombstonesDigest"]}', f'conflicts {cp["conflictsDigest"]}']
    return "".join(line + "\n" for line in lines).encode("utf-8")


def seal(state, issuer="dev-phone"):
    """Recomputes the heads, the digests and the checkpoint signature of a v4 snapshot state."""
    found = {}
    for c in state["operations"]:
        if c["sequence"] > found.get(c["deviceId"], (0, ""))[0]:
            found[c["deviceId"]] = (c["sequence"], c["hash"])
    heads = [{"deviceId": d, "sequence": s, "hash": h} for d, (s, h) in sorted(found.items())]
    state["heads"] = [{"vaultId": VAULT_ID, **h} for h in heads]
    d = digests(state)
    events = state["membershipEvents"]
    cp = {"format": 1, "vaultId": VAULT_ID, "issuerDeviceId": issuer, "membershipCount": len(events),
          "membershipHead": events[-1]["hash"], "createdAt": CREATED_AT, "heads": heads,
          "operationsDigest": d["operations"], "recordsDigest": d["records"],
          "tombstonesDigest": d["tombstones"], "conflictsDigest": d["conflicts"], "signature": ""}
    cp["signature"] = sign(issuer, signing_bytes(cp))
    state["checkpoint"] = cp
    return state


def main():
    changes = history()
    tablet_head = find(changes, "dev-tablet", 8)
    events = [membership_event(1, GENESIS, "GENESIS", "dev-phone", "dev-phone", public("dev-phone"), 1)]
    for subject in ("dev-pc", "dev-tablet"):
        events.append(membership_event(len(events) + 1, events[-1]["hash"], "ADD", "dev-phone", subject, public(subject), 1))
    events.append(membership_event(4, events[-1]["hash"], "REMOVE", "dev-phone", "dev-tablet",
                                   f'cutoff1:8:{tablet_head["hash"]}', 2))
    events.append(membership_event(5, events[-1]["hash"], "ADD", "dev-phone", "dev-joiner", public("dev-joiner"), 2))
    members = [
        {"vaultId": VAULT_ID, "deviceId": "dev-phone", "displayName": "Phone", "identityPublicKey": public("dev-phone"),
         "status": "ACTIVE", "addedByDeviceId": "dev-phone", "membershipSequence": 1, "keyEpoch": 1},
        {"vaultId": VAULT_ID, "deviceId": "dev-pc", "displayName": "PC", "identityPublicKey": public("dev-pc"),
         "status": "ACTIVE", "addedByDeviceId": "dev-phone", "membershipSequence": 2, "keyEpoch": 1},
        {"vaultId": VAULT_ID, "deviceId": "dev-tablet", "displayName": "Tablet", "identityPublicKey": public("dev-tablet"),
         "status": "REVOKED", "addedByDeviceId": "dev-phone", "membershipSequence": 4, "keyEpoch": 2},
        {"vaultId": VAULT_ID, "deviceId": "dev-joiner", "displayName": "New PC", "identityPublicKey": public("dev-joiner"),
         "status": "ACTIVE", "addedByDeviceId": "dev-phone", "membershipSequence": 5, "keyEpoch": 2},
    ]
    delete = find(changes, "dev-phone", 7)
    records = [
        {"entityType": "group", "entityId": "g1", "revision": 7, "recordVersionJson": version_json({"dev-phone": 1, "dev-tablet": 6})},
        {"entityType": "entry", "entityId": "e1", "revision": 12, "recordVersionJson": version_json({"dev-phone": 11, "dev-pc": 1})},
        {"entityType": "entry", "entityId": "e2", "revision": 2, "recordVersionJson": version_json({"dev-phone": 2})},
        {"entityType": "entry", "entityId": "e3", "revision": 4, "recordVersionJson": delete["recordVersionJson"]},
        {"entityType": "appearance", "entityId": "vault", "revision": 2, "recordVersionJson": version_json({"dev-phone": 1, "dev-pc": 1})},
        {"entityType": "site_icon", "entityId": "s1", "revision": 5, "recordVersionJson": version_json({"dev-pc": 5})},
        {"entityType": "device_name", "entityId": "dev-pc", "revision": 7, "recordVersionJson": version_json({"dev-pc": 7})},
    ]
    tombstones = [{"tombstoneId": delete["mutationId"], "entityType": "entry", "entityId": "e3",
                   "deletedByDeviceId": "dev-phone", "deleteSequence": 7, "recordVersionJson": delete["recordVersionJson"],
                   "deletedAtUtc": delete["occurredAtUtc"], "changeHash": delete["hash"]}]

    def conflict(cid, local, remote, resolved):
        return {"conflictId": cid, "entityType": "entry", "entityId": local["entityId"],
                "localChangeHash": local["hash"], "remoteChangeHash": remote["hash"],
                "localVersionJson": local["recordVersionJson"], "remoteVersionJson": remote["recordVersionJson"],
                "detectedAtUtc": remote["occurredAtUtc"], "resolvedAtUtc": resolved}
    conflicts = [conflict("c-e1", find(changes, "dev-phone", 4), find(changes, "dev-pc", 1), "2026-11-01T10:09:30Z"),
                 conflict("c-e2", find(changes, "dev-phone", 8), find(changes, "dev-pc", 7), "")]

    kept = keep_set(changes, records, tombstones, conflicts)
    expected = {find(changes, a, s)["hash"] for a, s in [("dev-phone", 16), ("dev-pc", 16), ("dev-tablet", 8),
                                                           ("dev-phone", 8), ("dev-phone", 7), ("dev-pc", 6),
                                                           ("dev-pc", 13), ("dev-pc", 7)]}
    assert kept == expected, sorted((c["deviceId"], c["sequence"]) for c in changes if c["hash"] in kept)

    def v4_state():
        return seal({"version": 4, "vaultId": VAULT_ID, "contentKey": b64(CONTENT_KEY), "transportSecret": b64(TRANSPORT_SECRET),
                     "contentKeys": {}, "transportSecrets": {}, "lineage": None,
                     "members": copy.deepcopy(members), "membershipEvents": copy.deepcopy(events), "heads": [],
                     "records": copy.deepcopy(records),
                     "operations": [copy.deepcopy(c) for c in changes if c["hash"] in kept],
                     "tombstones": copy.deepcopy(tombstones),
                     "conflicts": [copy.deepcopy(c) for c in conflicts if c["resolvedAtUtc"] == ""]})

    valid = v4_state()
    cp = valid["checkpoint"]

    negatives = []

    def negative(name, state):
        negatives.append({"name": name, "snapshot": state})

    state = v4_state()
    state["operations"] = [c for c in state["operations"] if not (c["deviceId"] == "dev-pc" and c["sequence"] == 16)]
    signed = seal(state)
    signed["checkpoint"]["heads"] = cp["heads"]  # still claims pc's head 16
    signed["heads"] = valid["heads"]
    signed["checkpoint"]["signature"] = sign("dev-phone", signing_bytes(signed["checkpoint"]))
    negative("missing head witness", signed)

    state = v4_state()
    pc_head = find(changes, "dev-pc", 16)
    forged = make_change("dev-pc", "dev-phone", 16, pc_head["previousHash"], pc_head["entityType"], pc_head["entityId"],
                         pc_head["kind"], json.loads(pc_head["recordVersionJson"])["counters"], pc_head["occurredAtUtc"])
    state["operations"] = [forged if c["hash"] == pc_head["hash"] else c for c in state["operations"]]
    negative("head witness signed by the wrong author", seal(state))

    state = v4_state()
    state["checkpoint"]["recordsDigest"] = "0" * 64
    state["checkpoint"]["signature"] = sign("dev-phone", signing_bytes(state["checkpoint"]))
    negative("wrong digest", state)

    negative("issuer is not the managing device", seal(v4_state(), issuer="dev-pc"))

    state = v4_state()
    state["checkpoint"]["signature"] = sign("dev-pc", signing_bytes(state["checkpoint"]))
    negative("signature by another device", state)

    state = v4_state()
    for record in state["records"]:
        if record["entityId"] == "e2":
            record["recordVersionJson"] = version_json({"dev-phone": 3})
    negative("present item without a witness", seal(state))

    state = v4_state()
    state["operations"] = [c for c in state["operations"] if c["hash"] != delete["hash"]]
    negative("tombstone without its change", seal(state))

    state = v4_state()
    beyond = make_change("dev-tablet", "dev-tablet", 9, tablet_head["hash"], "group", "g1", "upsert",
                         {"dev-phone": 1, "dev-tablet": 7}, "2026-11-01T11:00:00Z")
    state["operations"].append(beyond)
    negative("removed device beyond its cutoff", seal(state))

    state = v4_state()
    state["checkpoint"]["membershipCount"] = 4
    state["checkpoint"]["membershipHead"] = events[3]["hash"]
    state["checkpoint"]["signature"] = sign("dev-phone", signing_bytes(state["checkpoint"]))
    negative("checkpoint of an older membership", state)

    vectors = {
        "format": "nuvori-sync-checkpoint-vectors",
        "version": 1,
        "feature": "checkpoint-enroll-v1",
        "magic": "NUVSYNC4",
        "vaultId": VAULT_ID,
        "devices": [{"deviceId": d, "seedHex": s.hex(), "publicKey": public(d)} for d, s in DEVICES.items()],
        "manager": "dev-phone",
        "joiner": "dev-joiner",
        "keepSetInput": {"operations": changes, "records": records, "tombstones": tombstones, "conflicts": conflicts},
        "keepSet": sorted(kept),
        "digests": digests(valid),
        "signingBytesHex": signing_bytes(cp).hex(),
        "signingBytesText": signing_bytes(cp).decode("utf-8"),
        "snapshot": valid,
        "negative": negatives,
    }
    text = json.dumps(vectors, indent=2, ensure_ascii=True) + "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(text), "bytes")
    if len(sys.argv) > 1:
        windows = pathlib.Path(sys.argv[1]).resolve() / "src-tauri/fixtures/checkpoint-v1.json"
        windows.write_text(text, encoding="utf-8", newline="\n")
        print("wrote", windows)


if __name__ == "__main__":
    main()

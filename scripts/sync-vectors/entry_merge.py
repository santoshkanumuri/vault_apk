"""Writes the shared test vectors of the field merge of concurrent edits (`entry-merge-v1`).

    python scripts/sync-vectors/entry_merge.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/entry-merge-v1.json, and with a Windows path also
src-tauri/fixtures/entry-merge-v1.json there (the same bytes). Both apps test against the file.
Never edit the JSON by hand: change this script and run it again. The output is deterministic.

Rule (Android EntryFieldMerge.kt, Windows entry_merge.rs). Nothing on the wire changes: the result
is an ordinary signed change, so older versions of both apps apply it like any other.
- Applies to entity types "entry" and "group", when an incoming change is concurrent with this
  device's current change of the same record. Payloads are the decrypted JSON objects; a delete is
  {"deleted": true}.
- Base: the upsert of the same record whose record version equals the pointwise minimum of the two
  versions (zero counters dropped). Without one, the two sides merge only when they are equal
  apart from "updatedAt" and "lastOpenedAt" (two devices that chose the same side of a conflict).
- Two deletes merge into a delete. An edit against a delete stays a conflict.
- An entry whose "type" differs between base, local and remote stays a conflict.
- Object fields ("entry" or "group"), and other top-level keys: for each key of base, local or remote,
  "updatedAt" and "lastOpenedAt" in the object take the larger number. Any other key: local equals
  base -> remote; remote equals base -> local; local equals remote -> local; else a conflict.
  Equal means the same JSON value, with numbers compared by value and a missing key equal to null.
  A null result leaves the key out.
- "groupIds" (entries): an id stays when both sides have it or one side added it (it is not in the
  base): (L and R) or (L - B) or (R - B). Sorted. A missing list is empty.
- The result is symmetric: merge(B, L, R) equals merge(B, R, L), so every device computes the same.
- The device that merges signs the result as a change whose record version is the pointwise maximum
  of the two versions, with no extra count. Two devices merging the same pair write the same version
  and content, so each sees the other's change as equal (obsolete), and no new conflict starts.
- The merged result must still be a valid entry or group for the app, or the conflict stays.
"""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
OUT = ANDROID / "app/src/test/resources/sync-vectors/entry-merge-v1.json"

LATEST = {"updatedAt", "lastOpenedAt"}
OBJECT_KEY = {"entry": "entry", "group": "group"}


def is_number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def same(a, b):
    if a is None or b is None:
        return a is None and b is None
    if is_number(a) or is_number(b):
        return is_number(a) and is_number(b) and a == b
    if isinstance(a, dict) and isinstance(b, dict):
        keys = {k for k, v in a.items() if v is not None} | {k for k, v in b.items() if v is not None}
        return all(same(a.get(k), b.get(k)) for k in keys)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return type(a) is type(b) and a == b


class Conflict(Exception):
    pass


def three_way(key, base, local, remote, latest):
    b, l, r = base.get(key), local.get(key), remote.get(key)
    if latest and key in LATEST and is_number(l) and is_number(r):
        return l if l >= r else r
    if same(l, b):
        return r
    if same(r, b):
        return l
    if same(l, r):
        return l
    raise Conflict(key)


def merge_object(base, local, remote, latest):
    out = {}
    for key in sorted(set(base) | set(local) | set(remote)):
        value = three_way(key, base, local, remote, latest)
        if value is not None:
            out[key] = value
    return out


def group_ids(payload):
    ids = payload.get("groupIds") or []
    return set(ids)


def equivalent(entity_type, local, remote):
    """Equal apart from the latest-wins fields (no base needed)."""
    key = OBJECT_KEY[entity_type]
    lo, ro = local[key], remote[key]
    keys = (set(lo) | set(ro)) - LATEST
    if not all(same(lo.get(k), ro.get(k)) for k in keys):
        return False
    others = (set(local) | set(remote)) - {key, "groupIds"}
    if not all(same(local.get(k), remote.get(k)) for k in others):
        return False
    return entity_type != "entry" or group_ids(local) == group_ids(remote)


def deleted(payload):
    return payload is not None and payload.get("deleted") is True and not any(k in payload for k in OBJECT_KEY.values())


def merge(entity_type, base, local, remote):
    """The merged payload, or None for a conflict the user decides."""
    if entity_type not in OBJECT_KEY:
        return None
    if deleted(local) or deleted(remote):
        return {"deleted": True} if deleted(local) and deleted(remote) else None
    key = OBJECT_KEY[entity_type]
    lo, ro = local.get(key), remote.get(key)
    if not isinstance(lo, dict) or not isinstance(ro, dict):
        return None
    if not same(lo.get("id"), ro.get("id")):
        return None
    if base is not None and (deleted(base) or not isinstance(base.get(key), dict)):
        base = None
    if base is None:
        if not equivalent(entity_type, local, remote):
            return None
        base = local
    bo = base[key]
    if entity_type == "entry" and not (same(bo.get("type"), lo.get("type")) and same(lo.get("type"), ro.get("type"))):
        return None
    try:
        result = {key: merge_object(bo, lo, ro, latest=True)}
        if entity_type == "entry":
            b, l, r = group_ids(base), group_ids(local), group_ids(remote)
            result["groupIds"] = sorted((l & r) | (l - b) | (r - b))
        for other in sorted((set(base) | set(local) | set(remote)) - {key, "groupIds"}):
            value = three_way(other, base, local, remote, latest=False)
            if value is not None:
                result[other] = value
    except Conflict:
        return None
    return result


def meet(a, b):
    return {k: min(a.get(k, 0), b.get(k, 0)) for k in sorted(set(a) | set(b)) if min(a.get(k, 0), b.get(k, 0)) > 0}


def join(a, b):
    return {k: max(a.get(k, 0), b.get(k, 0)) for k in sorted(set(a) | set(b))}


def login(**changes):
    entry = {
        "id": "entry-1", "type": "PASSWORD", "title": "Mail", "primaryValue": "sam@example.com",
        "secondaryValue": "old-password", "tertiaryValue": "https://mail.example.com", "fourthValue": "",
        "cardKind": "CREDIT", "network": "", "totpAlgorithm": "SHA1", "totpDigits": 6, "totpPeriod": 30,
        "linkedApps": "", "autofillSignatures": "", "autofillOrigins": "", "linkedAuthenticatorId": "",
        "notes": "", "color": 4278743114, "tags": "", "favorite": False, "lastOpenedAt": 0,
        "sortOrder": 1000, "createdAt": 1000, "updatedAt": 1000,
    }
    groups = changes.pop("groupIds", ["group-a"])
    entry.update(changes)
    return {"entry": entry, "groupIds": groups}


def folder(**changes):
    group = {"id": "group-1", "name": "Work", "notes": "", "folderType": ""}
    group.update(changes)
    return {"group": group}


CASES = [
    ("different fields of one login merge", "entry",
     login(), login(secondaryValue="new-password", updatedAt=2000),
     login(primaryValue="sam@work.example", updatedAt=3000),
     login(primaryValue="sam@work.example", secondaryValue="new-password", updatedAt=3000)),
    ("the same field changed to different values is a conflict", "entry",
     login(), login(secondaryValue="phone-password", updatedAt=2000),
     login(secondaryValue="pc-password", updatedAt=3000), None),
    ("the same field changed to the same value merges", "entry",
     login(), login(secondaryValue="same", updatedAt=2000),
     login(secondaryValue="same", title="Mail account", updatedAt=1500),
     login(secondaryValue="same", title="Mail account", updatedAt=2000)),
    ("last opened and updated take the later time", "entry",
     login(), login(lastOpenedAt=5000, favorite=True, updatedAt=2000),
     login(lastOpenedAt=4000, notes="Recovery codes in the safe", updatedAt=2500),
     login(lastOpenedAt=5000, favorite=True, notes="Recovery codes in the safe", updatedAt=2500)),
    ("folders added and removed on either side combine", "entry",
     login(groupIds=["group-a", "group-b"]), login(groupIds=["group-a", "group-b", "group-c"], updatedAt=2000),
     login(groupIds=["group-a"], title="Mail (work)", updatedAt=2100),
     login(groupIds=["group-a", "group-c"], title="Mail (work)", updatedAt=2100)),
    ("a changed item type is a conflict", "entry",
     login(), login(type="NOTE", updatedAt=2000), login(title="Mail account", updatedAt=2100), None),
    ("a field a newer app added is kept from the side that changed it", "entry",
     login(), login(passwordHistory="h1", updatedAt=2000), login(title="Mail account", updatedAt=1900),
     login(passwordHistory="h1", title="Mail account", updatedAt=2000)),
    ("a field removed on one side and unchanged on the other is removed", "entry",
     login(passwordHistory="h1"), login(updatedAt=2000), login(passwordHistory="h1", title="Mail account", updatedAt=1900),
     login(title="Mail account", updatedAt=2000)),
    ("without a base, equal sides merge with the later times", "entry",
     None, login(secondaryValue="chosen", updatedAt=2000, lastOpenedAt=10),
     login(secondaryValue="chosen", updatedAt=2500, lastOpenedAt=9),
     login(secondaryValue="chosen", updatedAt=2500, lastOpenedAt=10)),
    ("without a base, different sides are a conflict", "entry",
     None, login(secondaryValue="phone", updatedAt=2000), login(title="Other", updatedAt=2500), None),
    ("without a base, different folders are a conflict", "entry",
     None, login(groupIds=["group-a"]), login(groupIds=["group-b"]), None),
    ("a deleted base counts as no base", "entry",
     {"deleted": True}, login(secondaryValue="phone"), login(title="Other"), None),
    ("two deletes merge into a delete", "entry",
     login(), {"deleted": True}, {"deleted": True}, {"deleted": True}),
    ("an edit against a delete is a conflict", "entry",
     login(), login(title="Edited", updatedAt=2000), {"deleted": True}, None),
    ("different ids never merge", "entry",
     None, login(), login(id="entry-2"), None),
    ("a folder's name and notes merge", "group",
     folder(), folder(name="Work accounts"), folder(notes="Shared with the team"),
     folder(name="Work accounts", notes="Shared with the team")),
    ("a folder renamed differently on both sides is a conflict", "group",
     folder(), folder(name="Office"), folder(name="Job"), None),
    ("other kinds of change never merge", "passkey",
     {"passkey": {"id": "p"}}, {"passkey": {"id": "p", "username": "a"}}, {"passkey": {"id": "p", "rpId": "b"}}, None),
]

VERSION_CASES = [
    ({"phone": 2}, {"phone": 1, "pc": 1}),
    ({"phone": 3, "pc": 2}, {"phone": 2, "pc": 3, "tablet": 1}),
    ({"pc": 1}, {"phone": 1}),
]


def main():
    cases = []
    for name, entity_type, base, local, remote, expected in CASES:
        merged = merge(entity_type, base, local, remote)
        swapped = merge(entity_type, base, remote, local)
        assert same(merged, expected), (name, merged, expected)
        assert same(swapped, expected), (name, "not symmetric", swapped)
        cases.append({"name": name, "entityType": entity_type, "base": base, "local": local,
                      "remote": remote, "expected": expected})
    versions = [{"local": a, "remote": b, "meet": meet(a, b), "join": join(a, b)} for a, b in VERSION_CASES]
    document = {
        "format": "nuvori-sync-entry-merge-vectors",
        "version": 1,
        "rule": "entry-merge-v1",
        "latestWins": sorted(LATEST),
        "cases": cases,
        "versions": versions,
    }
    text = json.dumps(document, indent=2, sort_keys=False) + "\n"
    OUT.write_text(text)
    if len(sys.argv) > 1:
        (pathlib.Path(sys.argv[1]) / "src-tauri/fixtures/entry-merge-v1.json").write_text(text)


if __name__ == "__main__":
    main()

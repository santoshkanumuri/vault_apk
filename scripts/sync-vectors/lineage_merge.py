"""Writes the shared test vectors of the ID-preserving merge join and the lost-manager freeze.

    python scripts/sync-vectors/lineage_merge.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/merge-join-v1.json, and with a Windows path also
src-tauri/fixtures/merge-join-v1.json there (the same bytes). Never edit the JSON by hand.

Merge join (feature merge-join-v1, Android MergeJoinRules.decide, Windows merge_join::decide). A
device that joins a group with items of its own matches each item by ID against the group's copy:

- same content (the compared fields below)              -> "same": nothing to do.
- the two copies share a lineage (the joiner is still a member of the host's old group, or both
  left the same old group), the joiner's old version is BEFORE or EQUAL the host's old version
  (record or delete), and the joiner's copy is still the one it had in the old group: the group
  already saw the joiner's copy and moved on. A joiner that left the old group keeps a digest of each
  item's content with its lineage row; an item it edited after leaving ("changedSinceLineage") was
  never seen by the group, so it is not dropped or replaced silently: it becomes a conflict.
      group copy present                                 -> "keepHost"
      group copy deleted or absent                       -> "drop" (to Recently deleted on the joiner)
- otherwise
      group copy absent                                  -> "add": signed by the joiner with the same ID
      group copy present with other content, or deleted  -> "conflict": the joiner signs its copy with
                                                            a fresh version, so every device shows both
                                                            copies for review (owner decision: never
                                                            newest-wins).

Lost manager freeze ("Mark as lost", local only). With a freeze (deviceId, membershipCount,
headSequence, headHash) a device keeps only the first membershipCount membership events it is
offered, and refuses a change by deviceId unless 1 <= sequence <= headSequence and, at
headSequence, the hash is headHash (the RemovalCutoff predicate). Other authors are not affected.
"""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import checkpoint as base  # noqa: E402

OUT = base.ANDROID / "app/src/test/resources/sync-vectors/merge-join-v1.json"

# The fields compared for "same content": what conflict review shows (Windows conflicts.rs
# ENTRY_LABELS, Android SyncConflictResolver), plus the entry's folders. lastOpenedAt and
# updatedAt are local bookkeeping and never make two copies differ.
ENTRY_FIELDS = ["type", "title", "primaryValue", "secondaryValue", "tertiaryValue", "fourthValue", "notes",
                "tags", "favorite", "sortOrder", "color", "network", "linkedAuthenticatorId", "linkedApps",
                "autofillSignatures", "autofillOrigins", "cardKind", "totpAlgorithm", "totpDigits", "totpPeriod"]
GROUP_FIELDS = ["name", "notes", "folderType"]
PASSKEY_FIELDS = ["id", "rpId", "userHandle", "username", "displayName", "privateKey", "publicKey", "createdAt"]


def normalize(value) -> str:
    """Text form of one field: missing or null is empty, booleans are true/false, numbers are integers."""
    if value is None:
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(int(value))
    return str(value)


def same_content(entity_type, left, right) -> bool:
    if entity_type == "entry":
        a, b = left["entry"], right["entry"]
        return all(normalize(a.get(f)) == normalize(b.get(f)) for f in ENTRY_FIELDS) and \
            sorted(left.get("groupIds", [])) == sorted(right.get("groupIds", []))
    if entity_type == "group":
        return all(normalize(left["group"].get(f)) == normalize(right["group"].get(f)) for f in GROUP_FIELDS)
    if entity_type == "passkey":
        return all(normalize(left["passkey"].get(f)) == normalize(right["passkey"].get(f)) for f in PASSKEY_FIELDS)
    raise ValueError(entity_type)


def decide(case) -> str:
    joiner, host, lineage = case["joiner"], case["host"], case["lineage"]
    if host["state"] == "present" and same_content(case["entityType"], joiner["content"], host["content"]):
        return "same"
    seen = lineage["shared"] and lineage["version"] is not None and joiner["oldVersion"] is not None and \
        not joiner["changedSinceLineage"] and base.relation(joiner["oldVersion"], lineage["version"]) in ("BEFORE", "EQUAL")
    if seen:
        return "keepHost" if host["state"] == "present" else "drop"
    return "add" if host["state"] == "absent" else "conflict"


def entry(title, password="pw", favorite=False, opened=0, groups=()):
    return {"entry": {"id": "e1", "type": "PASSWORD", "title": title, "primaryValue": "me@example.com",
                      "secondaryValue": password, "tertiaryValue": "", "fourthValue": "", "notes": "",
                      "tags": "", "favorite": favorite, "sortOrder": 0, "color": "", "network": "",
                      "linkedAuthenticatorId": "", "linkedApps": "", "autofillSignatures": "",
                      "autofillOrigins": "", "cardKind": "NONE", "totpAlgorithm": "SHA1", "totpDigits": 6,
                      "totpPeriod": 30, "lastOpenedAt": opened, "updatedAt": 1700000000000 + opened},
            "groupIds": list(groups)}


def group(name, notes=""):
    return {"group": {"id": "g1", "name": name, "notes": notes, "folderType": ""}}


def passkey(user):
    return {"passkey": {"id": "pk1", "rpId": "example.com", "userHandle": "dXNlcg", "username": user,
                        "displayName": user, "privateKey": "cHJpdmF0ZQ", "publicKey": "cHVibGlj",
                        "createdAt": 1700000000000}}


def case(name, entity_type, joiner_content, joiner_old, host_state, host_content, shared, lineage_version,
         lineage_deleted=False, changed=False):
    return {"name": name, "entityType": entity_type,
            "joiner": {"content": joiner_content, "oldVersion": joiner_old, "changedSinceLineage": changed},
            "host": {"state": host_state, "content": host_content},
            "lineage": {"shared": shared, "version": lineage_version, "deleted": lineage_deleted}}


def main():
    a, b = entry("Mail"), entry("Mail", password="new")
    old = {"dev-phone": 3, "dev-pc": 1}
    cases = [
        case("same content, shared lineage", "entry", a, old, "present", a, True, {"dev-phone": 4, "dev-pc": 1}),
        case("same content, no lineage", "entry", a, None, "present", a, False, None),
        case("only lastOpenedAt differs", "entry", entry("Mail", opened=99), old, "present", a, False, None),
        case("the group saw the joiner's copy (BEFORE)", "entry", a, old, "present", b, True, {"dev-phone": 4, "dev-pc": 1}),
        case("the group saw the joiner's copy (EQUAL)", "entry", a, old, "present", b, True, dict(old)),
        case("the joiner changed it after (AFTER)", "entry", b, {"dev-phone": 3, "dev-pc": 2}, "present", a, True, dict(old)),
        case("both changed it (CONCURRENT)", "entry", b, {"dev-phone": 3, "dev-pc": 2}, "present", a, True,
             {"dev-phone": 4, "dev-pc": 1}),
        case("other content, no lineage", "entry", b, None, "present", a, False, None),
        case("shared lineage, the joiner's copy was never synced", "entry", b, None, "present", a, True, dict(old)),
        case("only the folders differ", "entry", entry("Mail", groups=["g1"]), None, "present", a, False, None),
        case("only on the joiner, the group deleted it after seeing it", "entry", a, old, "absent", None, True,
             {"dev-phone": 4, "dev-pc": 1}, True),
        case("only on the joiner, deleted concurrently", "entry", b, {"dev-phone": 3, "dev-pc": 2}, "absent", None, True,
             {"dev-phone": 4, "dev-pc": 1}, True),
        case("only on the joiner, no lineage", "entry", a, None, "absent", None, False, None),
        case("only on the joiner, the lineage does not know it", "entry", a, old, "absent", None, True, None),
        case("deleted in the new group after the group saw it", "entry", a, old, "deleted", None, True, dict(old)),
        case("deleted in the new group, no lineage", "entry", a, None, "deleted", None, False, None),
        case("a folder with another name, no lineage", "group", group("Work"), None, "present", group("Office"), False, None),
        case("the same folder", "group", group("Work"), None, "present", group("Work"), False, None),
        case("a passkey with the same ID and other data", "passkey", passkey("ann"), None, "present", passkey("bob"),
             False, None),
        case("the same passkey", "passkey", passkey("ann"), None, "present", passkey("ann"), False, None),
        case("the group saw it, but the joiner edited it after leaving", "entry", b, old, "present", a, True,
             {"dev-phone": 4, "dev-pc": 1}, changed=True),
        case("the group deleted it after seeing it, but the joiner edited it after leaving", "entry", b, old, "absent",
             None, True, {"dev-phone": 4, "dev-pc": 1}, True, changed=True),
        case("deleted in the new group, but the joiner edited it after leaving", "entry", b, old, "deleted", None, True,
             dict(old), changed=True),
    ]
    for item in cases:
        item["expected"] = decide(item)
    expected = [c["expected"] for c in cases]
    for outcome in ("same", "keepHost", "drop", "add", "conflict"):
        assert outcome in expected, outcome

    head = "ab" * 32
    freeze = {"deviceId": "dev-phone", "membershipCount": 3, "headSequence": 7, "headHash": head,
              "changes": [
                  {"deviceId": "dev-phone", "sequence": 1, "hash": "01" * 32, "allowed": True},
                  {"deviceId": "dev-phone", "sequence": 7, "hash": head, "allowed": True},
                  {"deviceId": "dev-phone", "sequence": 7, "hash": "cd" * 32, "allowed": False},
                  {"deviceId": "dev-phone", "sequence": 8, "hash": "ef" * 32, "allowed": False},
                  {"deviceId": "dev-pc", "sequence": 900, "hash": "ef" * 32, "allowed": True},
              ],
              "events": [{"offered": 2, "kept": 2}, {"offered": 3, "kept": 3}, {"offered": 9, "kept": 3}]}
    vectors = {
        "format": "nuvori-sync-merge-join-vectors",
        "version": 1,
        "feature": "merge-join-v1",
        "entryFields": ENTRY_FIELDS,
        "groupFields": GROUP_FIELDS,
        "passkeyFields": PASSKEY_FIELDS,
        "cases": cases,
        "freeze": freeze,
    }
    text = json.dumps(vectors, indent=2, ensure_ascii=True) + "\n"
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print("wrote", OUT, len(text), "bytes")
    if len(sys.argv) > 1:
        windows = pathlib.Path(sys.argv[1]).resolve() / "src-tauri/fixtures/merge-join-v1.json"
        windows.write_text(text, encoding="utf-8", newline="\n")
        print("wrote", windows)


if __name__ == "__main__":
    main()

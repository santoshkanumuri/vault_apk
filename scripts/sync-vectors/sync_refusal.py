"""Writes the shared test vectors of the refusal frame (`sync-refusal-v1`) and photo receipts
(`photo-receipts-v1`).

    python scripts/sync-vectors/sync_refusal.py [path/to/card_app_windows]

Output: app/src/test/resources/sync-vectors/sync-session-v1.json, and with a Windows path also
src-tauri/fixtures/sync-session-v1.json there (the same bytes). Both apps test against the file.
Never edit the JSON by hand: change this script and run it again. The output is deterministic.

Refusal (M12; Android SyncRefusal.kt, Windows refusal.rs). Used only when the peer announced
`sync-refusal-v1`, inside the encrypted channel, after both identity proofs:
- frame: MAGIC + code, MAGIC = b"\\x00nuvori-sync-refused-v1:", code "change" or "protocol".
- A received frame is a refusal when it starts with MAGIC and is 1 to 16 bytes longer; an unknown
  code reads as "protocol". Any other frame is ordinary data.
- Never sent in answer to a refusal, and the receiver shows its own words, never the peer's.

Photo receipts (M11; Android PhotoSyncBlobs.kt, Windows lan_sync.rs). Used only when both
devices announced `photo-receipts-v1`:
- The receiving device counts the chunk frames of one photo. After every 16th chunk it sends a
  receipt: the photo's bytes it holds so far (request offset + bytes received), in ASCII digits.
- The sending device reads a receipt after every 16th chunk it sent and stops the session when it
  differs from offset + bytes sent. The photo's final hash acknowledgement is unchanged.
"""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
ANDROID = HERE.parent.parent
NAME = "sync-session-v1.json"
OUT = ANDROID / "app/src/test/resources/sync-vectors" / NAME

MAGIC = b"\x00nuvori-sync-refused-v1:"
CODES = ["change", "protocol"]
MAX_CODE = 16
EVERY = 16
CHUNK = 64 * 1024


def decode(frame):
    if len(frame) <= len(MAGIC) or len(frame) > len(MAGIC) + MAX_CODE or not frame.startswith(MAGIC):
        return None
    code = frame[len(MAGIC):].decode("ascii", "replace")
    return code if code in CODES else "protocol"


def receipts(offset, chunks):
    out, held = [], offset
    for index, size in enumerate(chunks, start=1):
        held += size
        if index % EVERY == 0:
            out.append({"afterChunk": index, "frame": str(held)})
    return out


def main():
    frames = [{"code": code, "hex": (MAGIC + code.encode("ascii")).hex()} for code in CODES]
    samples = [
        MAGIC + b"change",
        MAGIC + b"protocol",
        MAGIC + b"photo",            # unknown code
        MAGIC + b"x" * 16,           # longest allowed
        MAGIC + b"x" * 17,           # too long: data
        MAGIC,                       # no code: data
        MAGIC[1:] + b"change",       # no NUL: data
        b"\x01" + MAGIC[1:] + b"change",
        b"[]",
        b"12",
        b"0" * 64,
    ]
    decoding = [{"hex": sample.hex(), "code": decode(sample)} for sample in samples]
    photos = [
        {"offset": 0, "chunks": [CHUNK] * 15 + [100]},
        {"offset": 0, "chunks": [CHUNK] * 16},
        {"offset": 0, "chunks": [CHUNK] * 33 + [7]},
        {"offset": 5 * CHUNK + 3, "chunks": [CHUNK] * 20},
        {"offset": 0, "chunks": [1000] * 40},
    ]
    for case in photos:
        case["receipts"] = receipts(case["offset"], case["chunks"])
    document = {
        "rules": ["sync-refusal-v1", "photo-receipts-v1"],
        "refusalMagicHex": MAGIC.hex(),
        "refusalFrames": frames,
        "refusalDecoding": decoding,
        "receiptEvery": EVERY,
        "photoReceipts": photos,
    }
    text = json.dumps(document, indent=2, sort_keys=False) + "\n"
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    if len(sys.argv) > 1:
        target = pathlib.Path(sys.argv[1]) / "src-tauri/fixtures" / NAME
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)


if __name__ == "__main__":
    main()

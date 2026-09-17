"""Check every ELF LOAD segment in an APK or AAB for 16 KB alignment."""
import struct
import sys
import zipfile

failed = False
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in archive.namelist():
        if not name.endswith('.so'):
            continue
        data = archive.read(name)
        if data[:4] != b'\x7fELF':
            raise ValueError(f'Not ELF: {name}')
        endian = '<' if data[5] == 1 else '>'
        wide = data[4] == 2
        offset = struct.unpack_from(endian + ('Q' if wide else 'I'), data, 32 if wide else 28)[0]
        size, count = struct.unpack_from(endian + 'HH', data, 54 if wide else 42)
        alignments = []
        for index in range(count):
            row = struct.unpack_from(endian + ('IIQQQQQQ' if wide else 'IIIIIIII'), data, offset + index * size)
            if row[0] == 1:
                alignments.append(row[-1])
        valid = bool(alignments) and all(value >= 16384 for value in alignments)
        print(f'{"PASS" if valid else "FAIL"} {name}: {alignments}')
        failed |= not valid
sys.exit(1 if failed else 0)

"""Project-authored synthetic experiments, never real-save evidence.

Output: 49 key bytes, 170*716 plaintext bytes, 170*716 ciphertext bytes,
then one 716-byte ambiguity plaintext and its 716-byte ciphertext.
Execute only through the fixture validator's sandbox authority.
"""

import struct
import sys


ZERO_RANGES = (
    (80, 108), (236, 238), (240, 242), (270, 280), (309, 310),
    (354, 355), (356, 358), (407, 408), (415, 416), (454, 474),
    (525, 529), (539, 540), (550, 552), (573, 574), (680, 682), (712, 716),
)


def seal(record: bytearray) -> bytes:
    # Mathematical recurrence over raw signed serialization; no game-range assumptions.
    signed = struct.unpack("716b", record)
    total = 1
    for left, right in ((334, 297), (405, 335), (296, 353), (261, 411), (339, 352)):
        total = ((total + signed[left] + 1) * (signed[right] + 1)) % (2**32)
    total += sum(struct.unpack_from("<19H", record, 416))
    total += sum(record[377:396])
    struct.pack_into("<I", record, 696, total % (2**32))
    return bytes(record)


def encrypt(record: bytes, key: bytes) -> bytes:
    # Independent mathematical forward transform for synthetic test data only.
    output = bytearray()
    feedback = 0
    for offset, value in enumerate(record):
        feedback = (feedback + value + key[offset % 49]) % 256
        output.append(feedback)
    return bytes(output)


def generate() -> bytes:
    key = bytes((73 * index + 19) % 256 for index in range(49))
    records = []
    for number in range(170):
        record = bytearray((31 * number + 17 * offset + 3) % 256 for offset in range(716))
        for start, end in ZERO_RANGES:
            record[start:end] = bytes(end - start)
        records.append(seal(record))

    # Deliberately unusual signed-field witness: 129 valid missing-residue candidates.
    witness = bytearray(716)
    witness[405], witness[335], witness[296], witness[451] = 252, 127, 128, 1
    ambiguity = seal(witness)
    return (
        key + b"".join(records) + b"".join(encrypt(record, key) for record in records)
        + ambiguity + encrypt(ambiguity, key)
    )


if __name__ == "__main__":
    sys.stdout.buffer.write(generate())

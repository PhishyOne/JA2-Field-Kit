"""Emit the project-authored 432-byte header sentinel; no real save input."""

import struct
import sys


def generate() -> bytes:
    # Literal offsets are independent of parser constants so parser drift fails tests.
    header = bytearray(432)
    struct.pack_into('<I', header, 0, 103)
    header[4:18] = b'Build 04.12.02'
    description = 'Synthetic ✓'.encode('utf-16le')
    header[20:20 + len(description)] = description
    header[276:280] = bytes([0xde, 0xad, 0xbe, 0xef])
    struct.pack_into('<I', header, 280, 0x10203040)
    header[284:286] = bytes([0x12, 0x34])
    struct.pack_into('<hh', header, 286, 0x1234, -0x1234)
    header[290:292] = bytes([0xfe, 0x5a])
    struct.pack_into('<iI', header, 292, -123456789, 0x89abcdef)
    header[300:308] = bytes([1, 0, 0xbc, 1, 0, 2, 1, 2])
    header[308:315] = bytes(range(0xa0, 0xa7))
    header[315] = 0x7f
    struct.pack_into('<II', header, 316, 0xfedcba98, 0x76543210)
    header[324:432] = bytes((offset * 37 + 11) & 0xff for offset in range(324, 432))
    return bytes(header)


if __name__ == '__main__':
    sys.stdout.buffer.write(generate())

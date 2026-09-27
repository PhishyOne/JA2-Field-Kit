"""Keep the audited oracle payload digest-only and canonically index-aligned."""

import hashlib
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
ORACLE = ROOT / "core/src/main/kotlin/com/phishtopia/ja2fieldkit/core/format/Build041202RotationDigestOracle.kt"


class RotationDigestSourcePolicyTests(unittest.TestCase):
    def test_payload_contains_only_228_fingerprint_literals(self):
        source = ORACLE.read_text(encoding="utf-8")
        payload = source.split("private val fingerprints = listOf(", 1)[1].split(
            ").map(RotationTableDigest::parse)", 1,
        )[0]
        self.assertRegex(payload, r'\A(?:\s*"[0-9a-f]{64}",){228}\s*\Z')
        digests = re.findall(r'"([0-9a-f]{64})"', payload)
        self.assertEqual(len(set(digests)), 228)
        canonical = "".join(f"{index}:{digest}\n" for index, digest in enumerate(digests))
        self.assertEqual(
            hashlib.sha256(canonical.encode("ascii")).hexdigest(),
            "594442e0803a44774ad919af5addabed5d114d4262695b433a942ee4a5f945fe",
        )
        self.assertNotRegex(source, r"\b(?:byteArrayOf|intArrayOf|ByteArray|IntArray)\s*\(")


if __name__ == "__main__":
    unittest.main()

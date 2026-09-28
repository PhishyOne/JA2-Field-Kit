#!/usr/bin/env python3
"""Opt-in audit of public upstream serializers; no upstream bytes are retained.

Usage: python3 -B tools/check_hired_stat_offsets.py /path/to/JA2-Reborn
Reads pinned git objects, not working-tree files or game saves. Normal CI uses
independent literal offset vectors in SaveEditTransactionTest.
"""
import re
import subprocess
import sys

REVISION = "743f38a6ca86c81893376c2576277db660320170"
EXPECTED = {
    "MercProfile": {
        "sExpLevelGain": 242, "sLifeGain": 244, "sAgilityGain": 246,
        "sDexterityGain": 248, "sWisdomGain": 250, "sMarksmanshipGain": 252,
        "sMedicalGain": 254, "sMechanicGain": 256, "sExplosivesGain": 258,
        "sLeadershipGain": 324, "sStrengthGain": 326,
        "bExpLevelDelta": 298, "bLifeDelta": 299, "bAgilityDelta": 300,
        "bDexterityDelta": 301, "bWisdomDelta": 302, "bMarksmanshipDelta": 303,
        "bMedicalDelta": 304, "bMechanicDelta": 305, "bExplosivesDelta": 306,
        "bStrengthDelta": 307, "bLeadershipDelta": 308,
        "bMedical": 261, "bStrength": 296, "bLifeMax": 297,
        "bLife": 334, "bDexterity": 335, "bExplosive": 339,
        "bLeadership": 341, "bExpLevel": 352, "bMarksmanship": 353,
        "bWisdom": 355, "bAgility": 405, "bMechanical": 411,
    },
    "SoldierType": {
        "bOldLife": 704, "sFractLife": 708, "bBleeding": 710,
        "bAgilityDamage": 820, "bDexterityDamage": 821,
        "bStrengthDamage": 822, "bWisdomDamage": 823,
        "bDexterity": 840, "bWisdom": 841, "bExpLevel": 849,
        "bLife": 868, "bAgility": 880, "bStrength": 886,
        "bLeadership": 895, "dNextBleed": 896,
        "bMechanical": 916, "bLifeMax": 917,
        "bMedical": 1372, "bMarksmanship": 1377, "bExplosive": 1378,
    },
}


def audit(tree, kind):
    path = f"src/game/Tactical/LoadSave{kind}.cc"
    obj = f"{REVISION}:{path}"
    source = subprocess.check_output(["git", "-C", tree, "show", obj], text=True)
    blob = subprocess.check_output(["git", "-C", tree, "rev-parse", obj], text=True).strip()
    body = source.split(f"void Inject{kind}(", 1)[1]
    offset = 0
    found = {}
    widths = {"I8": 1, "U8": 1, "BOOL": 1, "I16": 2, "U16": 2,
              "I32": 4, "U32": 4, "FLOAT": 4, "PTR": 4, "SOLDIER": 1}
    # Extents independently established by the pinned headers / InjectObject.
    strings = {"NAME_LENGTH": 30, "NICKNAME_LENGTH": 10,
               "SOLDIERTYPE_NAME_LENGTH": 10, "PaletteRepID_LENGTH": 30}
    arrays = {"bBuddy": 5, "bHated": 5, "bInvStatus": 19,
              "bInvNumber": 19, "usApproachFactor": 4}
    started = False
    for line in body.splitlines():
        line = line.strip()
        if line.startswith("DataWriter "):
            started = True
            continue
        if not started or not line or line in ("{", "}"):
            continue
        if line.startswith("CFOR_EACH_SOLDIER_INV_SLOT"):
            continue
        if line == "InjectObject(d, i);":
            offset += 19 * 36
            continue
        string = re.fullmatch(r"[Dd]\.writeUTF(8|16)\([^,]+, (\w+)\);", line)
        if string:
            offset += int(string[1]) // 8 * strings[string[2]]
            continue
        macro = re.fullmatch(r"INJ_(\w+)\([Dd](?:, (.*))?\)", line)
        if not macro:
            raise AssertionError(f"Unaccounted serializer statement at {offset}: {line}")
        op, args = macro.groups()
        if op == "SKIP":
            offset += int(args)
            continue
        if op.startswith("SKIP_"):
            offset += widths[op[5:]]
            continue
        field = re.match(r"(?:p\.|s->)(\w+)", args)
        if field and field[1] in EXPECTED[kind]:
            found[field[1]] = offset
        if len(found) == len(EXPECTED[kind]):
            break
        if op.endswith("A"):
            offset += widths[op[:-1]] * arrays[field[1]]
        else:
            offset += widths[op]
    assert found == EXPECTED[kind], (kind, found)
    print(f"{kind}: {len(found)} offsets match; blob {blob}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    for record in EXPECTED:
        audit(sys.argv[1], record)

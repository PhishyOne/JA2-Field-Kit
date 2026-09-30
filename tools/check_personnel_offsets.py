#!/usr/bin/env python3
"""Audit pinned public git objects, never saves. Usage: check_personnel_offsets.py UPSTREAM"""
import re
import subprocess
import sys

REVISION = "743f38a6ca86c81893376c2576277db660320170"
EXPECTED = {
    "usKills": (310, "U16", 1), "usAssists": (312, "U16", 1),
    "usShotsFired": (314, "U16", 1), "usShotsHit": (316, "U16", 1),
    "usBattlesFought": (318, "U16", 1), "usTimesWounded": (320, "U16", 1),
    "usTotalDaysServed": (322, "U16", 1), "bPersonalityTrait": (336, "I8", 1),
    "bSkillTrait": (337, "I8", 1), "bSkillTrait2": (340, "I8", 1),
    "bBuddy": (342, "I8", 5), "bHated": (347, "I8", 5),
    "bAttitude": (549, "I8", 1), "uiTotalCostToDate": (708, "U32", 1),
    # Investigated, not presented:
    "bLearnToHate": (235, "I8", 1), "bLearnToLike": (554, "I8", 1),
    "bMercOpinion": (576, "I8", 75),
}
ENUMS = {
    "SkillTrait": "NO_SKILLTRAIT LOCKPICKING HANDTOHAND ELECTRONICS NIGHTOPS THROWING TEACHING HEAVY_WEAPS AUTO_WEAPS STEALTHY AMBIDEXT THIEF MARTIALARTS KNIFING ONROOF CAMOUFLAGED NUM_SKILLTRAITS",
    "PersonalityTrait": "NO_PERSONALITYTRAIT HEAT_INTOLERANT NERVOUS CLAUSTROPHOBIC NONSWIMMER FEAR_OF_INSECTS FORGETFUL PSYCHO",
    "Attitudes": "ATT_NORMAL ATT_FRIENDLY ATT_LONER ATT_OPTIMIST ATT_PESSIMIST ATT_AGGRESSIVE ATT_ARROGANT ATT_BIG_SHOT ATT_ASSHOLE ATT_COWARD NUM_ATTITUDES",
}


def read(tree, path):
    obj = f"{REVISION}:{path}"
    data = subprocess.check_output(["git", "-C", tree, "show", obj], text=True)
    blob = subprocess.check_output(["git", "-C", tree, "rev-parse", obj], text=True).strip()
    print(f"{blob} {path}")
    return data


def layout(body, prefix, header):
    widths = {"I8": 1, "U8": 1, "BOOL": 1, "I16": 2, "U16": 2, "I32": 4, "U32": 4}
    # String widths verified separately against the pinned headers. Normal non-Linux only.
    offset = 80
    fields = {}
    for line in body.splitlines():
        line = line.strip()
        if not line:
            continue
        if re.fullmatch(r'(?:D.writeUTF8\(p\.\w+, PaletteRepID_LENGTH\);|p\.\w+ = S.readUTF8\(PaletteRepID_LENGTH, ST::substitute_invalid\);)', line):
            offset += 30
            continue
        if line == "UINT32 const checksum = SoldierProfileChecksum(p);":
            continue
        macro = re.fullmatch(prefix + r"_(\w+)\([DS], (.*)\)", line)
        assert macro, f"Unaccounted statement at {offset}: {line}"
        op, args = macro.groups()
        if op == "SKIP":
            offset += int(args)
            continue
        name = re.match(r"\*?p\.(\w+(?:\.\w+)?)", args)
        count = 1
        if op.endswith("A"):
            assert name
            expected_args = ("*p.ubApproachMod, sizeof(p.ubApproachMod) / sizeof(**p.ubApproachMod)"
                             if name[1] == "ubApproachMod" else f"p.{name[1]}, lengthof(p.{name[1]})")
            assert args == expected_args, f"Unaccounted array extent: {args}"
            declaration = re.search(r"(?:INT8|UINT8|UINT16)\s+" + name[1] + r"((?:\[\d+\])+)", header)
            assert declaration, name[1]
            for size in re.findall(r"\d+", declaration[1]):
                count *= int(size)
            op = op[:-1]
        if name:
            fields[name[1]] = (offset, op, count)
        offset += widths[op] * count
    assert offset == 716, offset
    assert {name: fields[name] for name in EXPECTED} == EXPECTED, {name: fields[name] for name in EXPECTED}
    return fields


def audit(tree):
    head = subprocess.check_output(["git", "-C", tree, "rev-parse", "HEAD"], text=True).strip()
    assert head == REVISION, "Upstream checkout must match pinned commit"
    header = read(tree, "src/game/Tactical/Soldier_Profile_Type.h")
    serializer = read(tree, "src/game/Tactical/LoadSaveMercProfile.cc")
    loader = read(tree, "src/game/SaveLoadGame.cc")
    data_header = read(tree, "src/sgp/LoadSaveData.h")
    data_source = read(tree, "src/sgp/LoadSaveData.cc")
    assert "return readUTF16(numChars, true, ST_DEFAULT_VALIDATION);" in data_header
    assert "skip(sizeof(char16_t) * (numChars - n));" in data_source
    assert "(D) = (S).read<INT8>();" in data_header
    assert "return read<uint16_t>();" in data_source and "return read<uint32_t>();" in data_source
    assert "D.writeUTF16(p.zName, NAME_LENGTH);" in serializer
    assert "D.writeUTF16(p.zNickname, NICKNAME_LENGTH);" in serializer
    assert "p.zName = S.readString(NAME_LENGTH, stracLinuxFormat);" in serializer
    assert "p.zNickname = S.readString(NICKNAME_LENGTH, stracLinuxFormat);" in serializer
    palette = read(tree, "src/game/Tactical/Overhead_Types.h")
    profile_size = read(tree, "src/game/Tactical/LoadSaveMercProfile.h")
    helpers = read(tree, "src/game/Tactical/Soldier_Profile.cc")
    read(tree, "src/game/Tactical/Soldier_Profile.h")
    strategic = read(tree, "src/game/Strategic/Strategic_Merc_Handler.cc")
    for name, value in [("NAME_LENGTH", 30), ("NICKNAME_LENGTH", 10), ("NUM_PROFILES", 170)]:
        assert re.search(r"#define\s+" + name + r"\s+" + str(value) + r"\b", header)
    assert re.search(r"#define\s+PaletteRepID_LENGTH\s+30", palette)
    assert re.search(r"#define\s+MERC_PROFILE_SIZE\s+\(716\)", profile_size)
    for name, expected in ENUMS.items():
        body = re.search(r"enum " + name + r"\s*\{(.*?)\}", header, re.S)[1]
        assert body.count("=") == 1 and re.search(r"=\s*0\s*,", body)
        assert re.sub(r"\s*=\s*0", "", body).replace(",", " ").split() == expected.split()
    inject = serializer.split("void InjectMercProfile(", 1)[1].split("INJ_SKIP(D, 28)", 1)[1].split("Assert(D.getConsumed()", 1)[0]
    extract = serializer.split("void ExtractMercProfile(", 1)[1].split("EXTR_SKIP(S, 28)", 1)[1].split("if(stracLinuxFormat)", 1)[0]
    assert layout("INJ_SKIP(D, 28)" + inject, "INJ", header) == layout("EXTR_SKIP(S, 28)" + extract, "EXTR", header)
    # Exactly one version decision: both 102 and 103 take the >=87 encrypted-reader path.
    body = loader.split("static void LoadSavedMercProfiles(HWFILE const f,", 1)[1].split("static void SaveSoldierStructure", 1)[0]
    assert body.count("savegame_version") == 2
    assert "savegame_version < 87 ?" in body
    assert "ExtractMercProfile(data.data(), profile, stracLinuxFormat, &checksum, true);" in body
    assert "stracLinuxFormat ? MERC_PROFILE_SIZE_STRAC_LINUX : MERC_PROFILE_SIZE" in body
    assert "savedGameVersion" not in extract and "version" not in extract
    # The later soldier loader only backfills total salary cost for versions before 83.
    cost_migration = loader.split("if (savegame_version < 83 && s->ubProfile != NO_PROFILE)", 1)[1].split("if(isGermanVersion())", 1)[0]
    assert cost_migration.count("p.uiTotalCostToDate =") == 2
    assert loader.count("p.uiTotalCostToDate =") == 2
    for field in ("bBuddy", "bHated"):
        assert re.search(field + r"\[5\]\{ -1, -1, -1, -1, -1 \}", header)
        assert f"p.{field}[bLoop] == " in helpers
    for enum, slots in [("BuddySlot", "BUDDY_NOT_FOUND = -1, BUDDY_SLOT1, BUDDY_SLOT2, LEARNED_TO_LIKE_SLOT, NUM_BUDDY_SLOTS"), ("HatedSlot", "HATED_NOT_FOUND = -1, HATED_SLOT1, HATED_SLOT2, LEARNED_TO_HATE_SLOT, NUM_HATED_SLOTS")]:
        body = re.search(r"enum " + enum + r"\s*\{(.*?)\}", header, re.S)[1]
        assert re.sub(r"\s+", "", body) == re.sub(r"\s+", "", slots)
    assert "bLoop < NUM_BUDDY_SLOTS" in helpers and "bLoop < NUM_HATED_SLOTS" in helpers
    assert "p.bBuddy[LEARNED_TO_LIKE_SLOT] = p.bLearnToLike;" in strategic
    assert "p.bHated[LEARNED_TO_HATE_SLOT] = p.bLearnToHate;" in strategic
    print("PASS: complete 716-byte extract/inject parity; 17 field offsets/widths; enums; v102/v103 common load path; relationship slots/sentinels")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    audit(sys.argv[1])

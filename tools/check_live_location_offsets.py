#!/usr/bin/env python3
"""Independently audit pinned public git objects; never reads saves or retains source.

Usage: python3 -B tools/check_live_location_offsets.py /path/to/JA2-Reborn
"""
import re
import subprocess
import sys

REVISION = "743f38a6ca86c81893376c2576277db660320170"
BLOBS = {
    "src/game/Tactical/LoadSaveSoldierType.cc": "2dbe7b38c3494b25f7628138bf28732ffa35c907",
    "src/game/Tactical/Soldier_Control.h": "4e117cbccedb15a6408b0cb0490cbb2b671191a4",
    "src/game/Strategic/Assignments.h": "54c244d918c5f4a6440e920c1491c9583c9b892a",
}
EXPECTED = {"fBetweenSectors": (754, "BOOL"), "bAssignment": (1917, "I8"),
            "sSector.x": (1922, "I16"), "sSector.y": (1924, "I16"),
            "sSector.z": (1926, "I8")}
WIDTHS = {"I8": 1, "U8": 1, "BOOL": 1, "I16": 2, "U16": 2,
          "I32": 4, "U32": 4, "FLOAT": 4, "PTR": 4, "SOLDIER": 1}


def read(tree, path):
    obj = f"{REVISION}:{path}"
    blob = subprocess.check_output(["git", "-C", tree, "rev-parse", obj], text=True).strip()
    data = subprocess.check_output(["git", "-C", tree, "show", obj])
    actual = subprocess.check_output(["git", "hash-object", "--stdin"], input=data).decode().strip()
    assert blob == actual and (path not in BLOBS or blob == BLOBS[path]), path
    print(f"{blob} {path}")
    return data.decode()


def layout(body, prefix, header, constants):
    offset, fields = 0, {}
    for raw in body.splitlines():
        line = raw.strip().removesuffix(";")
        macro = re.fullmatch(prefix + r"_(\w+)\(d(?:, (.*))?\)", line)
        if macro:
            op, args = macro.groups()
            if op == "SKIP":
                offset += int(args)
                continue
            if op.startswith("SKIP_"):
                offset += WIDTHS[op[5:]]
                continue
            op = op.removeprefix("ENUM_")
            name = args.split(",")[0].removeprefix("s->")
            count = 1
            if op.endswith("A"):
                assert args == f"{args.split(',')[0]}, lengthof({args.split(',')[0]})"
                extent = ("MAX_PATH_LIST_SIZE" if name == "usPathingData" else
                          re.search(r"\b" + name + r"\[\s*(\w+)\s*\]", header)[1])
                count = int(extent) if extent.isdigit() else constants[extent]
                op = op[:-1]
            fields[name] = (offset, op)
            offset += WIDTHS[op] * count
        elif re.fullmatch(r"(?:Inject|Extract)Object\(d, i\)", line):
            offset += constants["NUM_INV_SLOTS"] * 36
        elif "writeUTF" in line or "readUTF" in line:
            encoding, extent = re.search(r"(?:read|write)UTF(\d+)\((?:s->\w+, )?(\w+)", line).groups()
            offset += int(encoding) // 8 * constants[extent]
        elif line.startswith("ExtractStatDamage("):
            name = re.fullmatch(r"ExtractStatDamage\(s->(\w+)\)", line)[1]
            fields[name] = (offset, "I8")
            offset += 1
        else:
            # Only non-I/O setup, loops, and in-memory path conversion may be skipped.
            assert "d," not in line and "d." not in line and not line.startswith(("if", "INJ_", "EXTR_")), (offset, line)
        if "sSector.z" in fields:
            break
    assert {name: fields[name] for name in EXPECTED} == EXPECTED, {name: fields[name] for name in EXPECTED}
    assert [fields[name] for name in ("bAgilityDamage", "bDexterityDamage", "bStrengthDamage", "bWisdomDamage")] == [(i, "I8") for i in range(820, 824)]
    return fields


def audit(tree):
    source = read(tree, "src/game/Tactical/LoadSaveSoldierType.cc")
    header = read(tree, "src/game/Tactical/Soldier_Control.h")
    assignments = read(tree, "src/game/Strategic/Assignments.h")
    overhead = read(tree, "src/game/Tactical/Overhead_Types.h")
    data = read(tree, "src/sgp/LoadSaveData.h")
    data_source = read(tree, "src/sgp/LoadSaveData.cc")
    assert "return read<uint16_t>();" in data_source and "return read<uint32_t>();" in data_source
    objects = read(tree, "src/game/Tactical/LoadSaveObjectType.cc")
    assert "Assert(d.getConsumed() == start + 36);" in objects
    constants = {}
    for name in ("SOLDIERTYPE_NAME_LENGTH", "MAX_PATH_LIST_SIZE", "MAX_NUM_SOLDIERS", "MAXPATROLGRIDS", "PaletteRepID_LENGTH"):
        constants[name] = int(re.search(r"#define\s+" + name + r"\s+(\d+)", header + overhead)[1])
    slots = re.search(r"enum InvSlotPos\s*\{(.*?)\}", header, re.S)[1]
    slots = re.sub(r"//[^\n]*", "", slots)
    assert slots.count("=") == 1 and "HELMETPOS = 0" in slots
    constants["NUM_INV_SLOTS"] = slots.replace(",", " ").split().index("NUM_INV_SLOTS") - 2
    assert constants["NUM_INV_SLOTS"] == 19
    inject = source.split("DataWriter d{data};", 1)[1]
    extract = source.split("\tEXTR_U8(d, s->ubID)", 1)[1]
    extract = "EXTR_U8(d, s->ubID)" + extract
    # Select only normal non-Linux branches; all other conditionals fail the walker.
    extract = re.sub(r"if\(stracLinuxFormat\)\s*\{[^{}]*\}\s*else\s*\{([^{}]*)\}", r"\1", extract)
    extract = re.sub(r"if\(stracLinuxFormat\)\s*\{[^{}]*\}", "", extract)
    inj_fields = layout(inject, "INJ", header, constants)
    ext_fields = layout(extract, "EXTR", header, constants)
    assert all(inj_fields[name] == ext_fields[name] for name in EXPECTED)
    extraction = source.split("void ExtractSoldierType", 1)[1].split("void InjectSoldierType", 1)[0]
    assert extraction.count("uiSavedGameVersion") == 2  # signature and stat-damage value only
    assert "INT8 val = d.read<INT8>();" in extraction
    assert "whichDamage = uiSavedGameVersion >= 103 ? val : 0;" in extraction
    assert source.count("Assert(d.getConsumed() == 2328);") == 2
    for op, typ in [("BOOL", "BOOLEAN"), ("I8", "INT8"), ("I16", "INT16")]:
        assert re.search(r"#define EXTR_" + op + r"\(.*?read<" + typ + r">", data)
    enum = re.search(r"enum Assignments : int8_t\s*\{(.*?)\}", assignments, re.S)[1]
    enum = re.sub(r"//[^\n]*", "", enum)
    names = [f"SQUAD_{i}" for i in range(1, 21)] + "ON_DUTY DOCTOR PATIENT VEHICLE IN_TRANSIT REPAIR TRAIN_SELF TRAIN_TOWN TRAIN_TEAMMATE TRAIN_BY_OTHER ASSIGNMENT_DEAD ASSIGNMENT_UNCONCIOUS ASSIGNMENT_POW ASSIGNMENT_HOSPITAL ASSIGNMENT_EMPTY".split()
    assert enum.count("=") == 1
    assert re.sub(r"=\s*0", "", enum).replace(",", " ").split() == names
    assert re.search(r"#define\s+NO_ASSIGNMENT\s+127\b", assignments)
    for name, value in {"PC": 0x8, "DEAD": 0x80, "VEHICLE": 0x8000, "OFF_MAP": 0x02000000, "DRIVER": 0x08000000, "PASSENGER": 0x10000000}.items():
        assert int(re.search(r"#define\s+SOLDIER_" + name + r"\s+(0x[0-9a-fA-F]+)", header)[1], 16) == value
    checksum = source.split("static UINT32 MercChecksum", 1)[1].split("void ExtractSoldierType", 1)[0]
    assert set(re.findall(r"s\.(\w+)", checksum)) == set("bLife bLifeMax bAgility bDexterity bStrength bMarksmanship bMedical bMechanical bExplosive bExpLevel ubProfile".split())
    assert set(re.findall(r"i->(\w+)", checksum)) == {"usItem", "ubNumberOfObjects"}
    print("PASS: independently counted extract/inject offsets and widths; assignment values; flags; v102/v103 stability; location/status checksum exclusion")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    audit(sys.argv[1])

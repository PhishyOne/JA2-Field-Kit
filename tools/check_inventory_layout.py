#!/usr/bin/env python3
"""Offline audit of pinned public git objects; never reads saves or retains upstream text.

Usage: python3 -B tools/check_inventory_layout.py /path/to/JA2-Reborn
This checks serialization extents and the CLOSED three-item metadata subset.
Creation/removal semantics are separately reviewed in docs/inventory-edit-evidence.md.
"""
import json
import re
import subprocess
import sys

from check_hired_stat_offsets import REVISION, EXPECTED, audit


def read(tree, path):
    return subprocess.check_output(["git", "-C", tree, "show", f"{REVISION}:{path}"], text=True)


def layout(lines, arrays):
    offset = 0
    fields = {}
    widths = {"U16": 2, "U8": 1, "I8": 1, "U32": 4}
    for line in lines:
        line = line.strip()
        macro = re.fullmatch(r"INJ_(\w+)\(d, (.*?)\)", line)
        if macro:
            op, args = macro.groups()
            if op == "SKIP":
                offset += int(args)
                continue
            field = re.fullmatch(r"o->(\w+)(?:, lengthof\(o->\w+\))?", args)
            assert field, line
            name = field[1]
            fields[name] = offset
            offset += widths[op.removesuffix("A")] * (arrays[name] if op.endswith("A") else 1)
        elif line.startswith("d.writeU8("):
            assert "Weight(*o)" in line and "1, 255" in line
            fields["serializedWeight"] = offset
            offset += 1
        elif line:
            raise AssertionError(f"Unaccounted object statement: {line}")
    return offset, fields


def audit_shared_slots(tree):
    control = read(tree, "src/game/Tactical/Soldier_Control.h")
    body = control.split("enum InvSlotPos", 1)[1].split("};", 1)[0]
    body = re.sub(r"//[^\n]*", "", body)
    slots = re.findall(r"\b[A-Z][A-Z0-9_]+\b", body)
    assert slots == ["HELMETPOS", "VESTPOS", "LEGPOS", "HEAD1POS", "HEAD2POS",
                     "HANDPOS", "SECONDHANDPOS"] + [f"BIGPOCK{i}POS" for i in range(1, 5)] + [
                         f"SMALLPOCK{i}POS" for i in range(1, 9)] + ["NUM_INV_SLOTS"]
    assert re.findall(r"=\s*(\d+)", body) == ["0"]
    assert re.search(r"OBJECTTYPE\s+inv\[\s*NUM_INV_SLOTS\s*\]", control)
    assert "(soldier).inv, * const iter##__end = endof((soldier).inv)" in control
    profile = read(tree, "src/game/Tactical/Soldier_Profile_Type.h")
    for kind, field in [("UINT16", "inv"), ("UINT8", "bInvNumber"), ("UINT8", "bInvStatus")]:
        assert re.search(rf"{kind}\s+{field}\[19\]", profile)
    creation = read(tree, "src/game/Tactical/Soldier_Create.cc").split(
        "static void CopyProfileItems(SOLDIERTYPE& s, SOLDIERCREATE_STRUCT const& c)", 1)[1]
    assert "for (UINT32 i = 0; i != NUM_INV_SLOTS; ++i)" in creation
    for pattern in [r"item\s*= p\.inv\[i\]", r"slot\s*= &s\.inv\[i\]",
                    r"count\s*= p\.bInvNumber\[i\]", r"CreateItems\(item, p\.bInvStatus\[i\], count, slot\)"]:
        assert re.search(pattern, creation)
    print("PASS: shared 19-slot InvSlotPos order, profile U16/U8/U8 arrays, same-index profile-to-live creation")


def main(tree):
    audit_shared_slots(tree)
    # Reuse cumulative counting through the profile inventory start, including padding.
    EXPECTED["MercProfile"] = {"bInvStatus": 358, "bInvNumber": 377,
                               "ubInvUndroppable": 412, "inv": 416}
    audit(tree, "MercProfile")
    profile = read(tree, "src/game/Tactical/LoadSaveMercProfile.cc").split("void InjectMercProfile(", 1)[1]
    # Walk backwards from the independently evidenced checksum at 696.
    money_tail = profile.split("INJ_U32(D, p.uiMoney)", 1)[1].split("UINT32 const checksum", 1)[0]
    operations = re.findall(r"INJ_(U8|I8|U32)\(D, p\.\w+\)", money_tail)
    assert len(operations) == 5 and sum(4 if op == "U32" else 1 for op in operations) == 8
    assert 696 - 8 - 4 == 684
    soldier = read(tree, "src/game/Tactical/LoadSaveSoldierType.cc").split("void InjectSoldierType(", 1)[1]
    prefix = soldier.split("CFOR_EACH_SOLDIER_INV_SLOT", 1)[0]
    widths = {"U8": 1, "I8": 1, "U32": 4}
    size = sum(int(args) if op == "SKIP" else widths[op]
               for op, args in re.findall(r"INJ_(U8|I8|U32|SKIP)\(d, ([^)]+)\)", prefix))
    assert size == 12
    source = read(tree, "src/game/Tactical/LoadSaveObjectType.cc").split("void InjectObject(", 1)[1]
    prefix = source.split("INJ_U16(d, o->usItem)", 1)[1].split("switch", 1)[0]
    generic = source.split("inject_status:", 1)[1].split("break;", 1)[0]
    tail = source.split("INJ_U16A(d, o->usAttachItem", 1)[1].split("Assert", 1)[0]
    lines = ("INJ_U16(d, o->usItem)" + prefix + generic + "INJ_U16A(d, o->usAttachItem" + tail).splitlines()
    extent, fields = layout(lines, {"bStatus": 8, "usAttachItem": 4, "bAttachStatus": 4})
    assert extent == 36
    assert fields == {"usItem": 0, "ubNumberOfObjects": 2, "bStatus": 4, "usAttachItem": 16,
                      "bAttachStatus": 24, "fFlags": 28, "ubMission": 29, "bTrap": 30,
                      "ubImprintID": 31, "serializedWeight": 32, "fUsed": 33}
    assert size + 19 * extent == 696
    items = json.loads(read(tree, "assets/externalized/items.json"))
    for item_id, item_class, weight, pocket in [(201, 4096, 5, 4), (202, 4096, 18, 0), (203, 8192, 50, 0)]:
        matches = [item for item in items if item["itemIndex"] == item_id]
        assert len(matches) == 1
        item = matches[0]
        assert (item["usItemClass"], item["ubWeight"], item["ubPerPocket"]) == (item_class, weight, pocket)
        assert not item.get("bDefaultUndroppable", False) and not item.get("bAttachment", False)
        assert min(255, max(1, weight * 100)) == 255
    print("PASS: 5 profile offsets, live inventory [12,696), 11 object offsets / 36-byte extent, 3 item definitions")
    for path in ("src/game/Tactical/Soldier_Control.h", "src/game/Tactical/Soldier_Profile_Type.h",
                 "src/game/Tactical/Items.cc", "src/game/Tactical/Item_Types.h",
                 "src/game/Tactical/LoadSaveObjectType.cc", "src/game/Tactical/Soldier_Create.cc",
                 "src/externalized/ItemModel.cc", "src/externalized/DefaultContentManager.cc",
                 "src/sgp/LoadSaveData.cc", "assets/externalized/items.json"):
        blob = subprocess.check_output(["git", "-C", tree, "rev-parse", f"{REVISION}:{path}"], text=True).strip()
        print(f"{blob} {path}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    main(sys.argv[1])

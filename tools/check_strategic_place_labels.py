#!/usr/bin/env python3
"""Audit pinned public objects against presentation metadata; retain no upstream bytes.

Usage: python3 -B tools/check_strategic_place_labels.py /path/to/JA2-Reborn
"""
import json
from pathlib import Path
import re
import subprocess
import sys

REVISION = "743f38a6ca86c81893376c2576277db660320170"
BLOBS = {
    "assets/externalized/strategic-map-towns.json": "9bf4d480a82cbb5a2e83f2fcf1604337e53bca35",
    "assets/externalized/strings/strategic-map-town-names-eng.json": "4902bc86de1eaddf094c06e56f0c9ea25f03223e",
    "assets/externalized/strategic-map-sectors-descriptions.json": "49b8ea74d08c3d116d9bdacd78de2296e8a23c7e",
    "assets/externalized/strategic-map-traversibility-ratings.json": "6137db1d470c2069ab890572dbc661449db6333f",
    "assets/externalized/strings/strategic-map-land-types-eng.json": "5f0aa6f7603219eab8a7374fbaa16b4d8a7b9063",
    "assets/externalized/strategic-map-secrets.json": "51e427b59a2f13f6a4b97a9ec53b1625ea48199f",
    "src/game/Strategic/StrategicMap.cc": "e72c19c51c80b2dbd98b21309f6d06ad7f50d4a4",
    "src/externalized/DefaultContentManager.cc": "03d360b8aada60e6f3df372b62e4007e1aab45ff",
    "src/externalized/strategic/TownModel.cc": "42e8df6af7c75331299ec0c080b6f8f02ae9cccf",
    "src/externalized/strategic/SectorLandTypes.cc": "6d2d95d6ee74892af710b72be17d7d5d5ca6e344",
}


def unique(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result, f"Duplicate key: {key}"
        result[key] = value
    return result


def read(tree, path, expected):
    obj = f"{REVISION}:{path}"
    blob = subprocess.check_output(["git", "-C", tree, "rev-parse", obj], text=True).strip()
    data = subprocess.check_output(["git", "-C", tree, "show", obj])
    actual = subprocess.check_output(["git", "hash-object", "--stdin"], input=data).decode().strip()
    assert blob == actual == expected, f"Changed blob: {path}"
    print(f"{blob} {path}")
    if not path.endswith(".json"):
        return None
    # Preserve quoted strings while removing JSON comments.
    clean = re.sub(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*',
                   lambda m: m[0] if m[0].startswith('"') else ' ', data.decode(), flags=re.S)
    return json.loads(clean, object_pairs_hook=unique)


def sector(code):
    assert re.fullmatch(r"[A-P](?:[1-9]|1[0-6])", code), f"Non-surface coordinate: {code}"
    return code


def indexed(strings, index):
    assert type(index) is int and 0 <= index < len(strings), f"Missing index: {index}"
    assert isinstance(strings[index], str) and strings[index], f"Empty index: {index}"
    return strings[index]


def audit(tree):
    resources = {path: read(tree, path, blob) for path, blob in BLOBS.items()}
    def resource(name):
        return resources["assets/externalized/" + name + ".json"]
    names = resource("strings/strategic-map-town-names-eng")
    towns = resource("strategic-map-towns")
    assert sorted(t["townId"] for t in towns) == list(range(1, len(names)))
    labels = unique((sector(code), indexed(names, town["townId"]))
                    for town in towns for code in town["sectors"])
    assert len(labels) == 36
    ratings = resource("strategic-map-traversibility-ratings")
    land_names = resource("strings/strategic-map-land-types-eng")
    for index in ratings.values():
        indexed(land_names, index)
    secrets = resource("strategic-map-secrets")
    excluded = unique((sector(s["sector"]), s) for s in secrets)
    assert set(excluded) == {"D2", "D15", "I8", "J9", "K4", "N4"}
    for s in secrets:
        for field in ("secretLandType", "foundLandType"):
            indexed(land_names, ratings[s[field]])
    labels = {code: name for code, name in labels.items() if code not in excluded}
    descriptions = unique(((sector(s["sector"]), s.get("sectorLevel", 0)),
                           indexed(land_names, ratings[s["landType"]]))
                          for s in resource("strategic-map-sectors-descriptions"))
    assert all(type(z) is int and 0 <= z <= 3 for _, z in descriptions)
    overrides = {code: name for (code, z), name in descriptions.items() if z == 0}
    assert overrides == {"B13": "Drassen Airport", "F8": "Cambria Hospital", "N3": "Meduna Airport"}
    assert overrides.keys() <= labels.keys()
    labels.update(overrides)
    assert len(labels) == 33 and not labels.keys() & excluded.keys()
    for code, name in {"A9": "Omerta", "C13": "Drassen", "D13": "Drassen", "P3": "Meduna", **overrides}.items():
        assert labels[code] == name
    assert "A1" not in labels
    source = (Path(__file__).resolve().parents[1] / "android-app/src/main/kotlin/com/phishtopia/ja2fieldkit/android/presentation/StrategicPlaceLabels.kt").read_text()
    entries = re.findall(r'"([^"\n]+)" to "([^"\n]+)"', source)
    production = unique((sector(code), name) for code, name in entries)
    assert production == labels, "Production mapping differs from independently joined resources"
    for y in "ABCDEFGHIJKLMNOP":
        for x in range(1, 17):
            for z in range(1, 4):
                assert f"{y}{x}-{z}" not in production
    print("PASS: 33 surface labels; 6 secret exclusions; exactly 3 structured overrides; no depth keys; production equals independent join")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    audit(sys.argv[1])

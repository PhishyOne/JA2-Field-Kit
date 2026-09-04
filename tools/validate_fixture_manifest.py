#!/usr/bin/env python3
"""Validate fixture metadata and public-repository admission invariants."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "fixtures" / "provenance-manifest.json"
SCHEMA = ROOT / "fixtures" / "provenance-manifest.schema.json"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
FIXTURE_ID = re.compile(r"^[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?$")
JSON_POINTER = re.compile(r"^/(?:[^~/]|~[01])*(?:/(?:[^~/]|~[01])*)*$")


class ValidationError(Exception):
    pass


def load_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValidationError(f"cannot read valid JSON from {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise ValidationError(f"{path} must contain a JSON object")
    return value


def exact_keys(value: Any, required: set[str], where: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValidationError(f"{where} must be an object")
    actual = set(value)
    missing = sorted(required - actual)
    extra = sorted(actual - required)
    if missing or extra:
        raise ValidationError(f"{where} keys invalid; missing={missing}, extra={extra}")
    return value


def nonempty_string(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValidationError(f"{where} must be a non-empty string")
    return value


def enum(value: Any, allowed: set[str], where: str) -> str:
    if value not in allowed:
        raise ValidationError(f"{where} must be one of {sorted(allowed)}, got {value!r}")
    return value


def relative_path(value: Any, prefix: str, where: str) -> str:
    path = nonempty_string(value, where)
    pure = PurePosixPath(path)
    parts = path.split("/")
    if pure.is_absolute() or any(part in {"", ".", ".."} for part in parts) or "\\" in path:
        raise ValidationError(f"{where} must be a safe project-relative POSIX path")
    if not path.startswith(prefix):
        raise ValidationError(f"{where} must start with {prefix!r}")

    candidate = ROOT
    for part in parts:
        candidate /= part
        if candidate.is_symlink():
            raise ValidationError(f"{where} contains symbolic-link component {part!r}")

    containment_root = ROOT / prefix.rstrip("/") if prefix else ROOT
    try:
        candidate.resolve(strict=False).relative_to(containment_root.resolve(strict=False))
    except ValueError as exc:
        raise ValidationError(f"{where} resolves outside {containment_root}") from exc
    return path


def check_identity(identity_value: Any, where: str) -> tuple[str, int | None, str | None]:
    identity = exact_keys(identity_value, {"status", "size_bytes", "sha256"}, where)
    status = enum(identity["status"], {"pending", "verified"}, f"{where}.status")
    size = identity["size_bytes"]
    digest = identity["sha256"]
    if status == "pending":
        if size is not None or digest is not None:
            raise ValidationError(f"{where} pending identity must use null size and SHA-256")
        return status, None, None
    if isinstance(size, bool) or not isinstance(size, int) or size < 0:
        raise ValidationError(f"{where}.size_bytes must be a non-negative integer")
    if not isinstance(digest, str) or SHA256.fullmatch(digest) is None:
        raise ValidationError(f"{where}.sha256 must be 64 lowercase hexadecimal characters")
    return status, size, digest


def check_file(path_text: str, size: int, digest: str, where: str) -> None:
    path = ROOT / path_text
    if path.is_symlink():
        raise ValidationError(f"{where} fixture path must not be a symbolic link: {path_text}")
    if not path.is_file():
        raise ValidationError(f"{where} file is missing: {path_text}")
    actual_size = path.stat().st_size
    if actual_size != size:
        raise ValidationError(f"{where} size mismatch: manifest={size}, actual={actual_size}")
    hasher = hashlib.sha256()
    with path.open("rb") as fixture_file:
        for chunk in iter(lambda: fixture_file.read(1024 * 1024), b""):
            hasher.update(chunk)
    actual_digest = hasher.hexdigest()
    if actual_digest != digest:
        raise ValidationError(
            f"{where} SHA-256 mismatch: manifest={digest}, actual={actual_digest}"
        )


def check_generator_output(location_value: Any, size: int, digest: str, where: str) -> str:
    location = relative_path(location_value, "", f"{where}.location")
    path = ROOT / location
    if path.suffix != ".py":
        raise ValidationError(f"{where}.location must name a Python file")
    if path.is_symlink():
        raise ValidationError(f"{where}.location must not be a symbolic link: {location}")
    if not path.is_file():
        raise ValidationError(f"{where}.location is missing: {location}")
    try:
        result = subprocess.run(
            [sys.executable, str(path)],
            cwd=ROOT,
            check=False,
            capture_output=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValidationError(f"{where} could not produce fixture bytes: {exc}") from exc
    if result.returncode != 0:
        raise ValidationError(f"{where} exited with status {result.returncode}")
    actual_size = len(result.stdout)
    if actual_size != size:
        raise ValidationError(
            f"{where} output size mismatch: manifest={size}, actual={actual_size}"
        )
    actual_digest = hashlib.sha256(result.stdout).hexdigest()
    if actual_digest != digest:
        raise ValidationError(
            f"{where} output SHA-256 mismatch: manifest={digest}, actual={actual_digest}"
        )
    return location


def check_derivation_cycles(graph: dict[str, dict[str, str]]) -> None:
    visited: set[str] = set()
    visiting: set[str] = set()
    path: list[str] = []

    def visit(fixture_id: str) -> None:
        if fixture_id in visited:
            return
        if fixture_id in visiting:
            cycle_start = path.index(fixture_id)
            cycle = path[cycle_start:] + [fixture_id]
            raise ValidationError(f"derivation cycle detected: {' -> '.join(cycle)}")
        visiting.add(fixture_id)
        path.append(fixture_id)
        for parent_id in graph.get(fixture_id, {}):
            visit(parent_id)
        path.pop()
        visiting.remove(fixture_id)
        visited.add(fixture_id)

    for fixture_id in graph:
        visit(fixture_id)


def check_source(value: Any, category: str, where: str) -> None:
    source = exact_keys(
        value,
        {"family", "save_version", "product", "build", "platform", "origin", "evidence"},
        where,
    )
    enum(
        source["family"],
        {"JA2_REBORN", "STRACCIATELLA", "CLASSIC", "UNKNOWN"},
        f"{where}.family",
    )
    save_version = source["save_version"]
    if save_version is not None and (
        isinstance(save_version, bool) or not isinstance(save_version, int) or save_version < 0
    ):
        raise ValidationError(f"{where}.save_version must be a non-negative integer or null")
    nonempty_string(source["product"], f"{where}.product")
    for optional in ("build", "platform"):
        if source[optional] is not None:
            nonempty_string(source[optional], f"{where}.{optional}")
    required_origin = {
        "synthetic": "project-generated",
        "real-local": "locally-supplied",
        "derived-sanitized": "derived",
    }[category]
    if source["origin"] != required_origin:
        raise ValidationError(f"{where}.origin must be {required_origin!r} for {category}")
    evidence = source["evidence"]
    if not isinstance(evidence, list) or not evidence:
        raise ValidationError(f"{where}.evidence must be a non-empty array")
    for index, item in enumerate(evidence):
        nonempty_string(item, f"{where}.evidence[{index}]")


def check_derivation(value: Any, category: str, where: str) -> dict[str, str]:
    if category != "derived-sanitized":
        if value is not None:
            raise ValidationError(f"{where} must be null unless category is derived-sanitized")
        return {}
    derivation = exact_keys(
        value,
        {"parents", "method", "sanitization"},
        where,
    )
    parents = derivation["parents"]
    if not isinstance(parents, list) or not parents:
        raise ValidationError(f"{where}.parents must be a non-empty array")
    parent_digests: dict[str, str] = {}
    for index, parent_value in enumerate(parents):
        parent_where = f"{where}.parents[{index}]"
        parent = exact_keys(parent_value, {"fixture_id", "sha256"}, parent_where)
        parent_id = nonempty_string(parent["fixture_id"], f"{parent_where}.fixture_id")
        if FIXTURE_ID.fullmatch(parent_id) is None:
            raise ValidationError(f"{parent_where}.fixture_id is not a stable fixture ID")
        if parent_id in parent_digests:
            raise ValidationError(f"{parent_where} duplicates parent fixture ID {parent_id!r}")
        digest = parent["sha256"]
        if not isinstance(digest, str) or SHA256.fullmatch(digest) is None:
            raise ValidationError(f"{parent_where}.sha256 is not a lowercase SHA-256")
        parent_digests[parent_id] = digest
    for key in ("method", "sanitization"):
        nonempty_string(derivation[key], f"{where}.{key}")
    return parent_digests


def check_expected(value: Any, where: str) -> None:
    expected = exact_keys(value, {"outcome", "completeness", "basis", "assertions"}, where)
    enum(expected["outcome"], {"success", "failure"}, f"{where}.outcome")
    enum(expected["completeness"], {"partial", "complete"}, f"{where}.completeness")
    nonempty_string(expected["basis"], f"{where}.basis")
    assertions = expected["assertions"]
    if not isinstance(assertions, list) or not assertions:
        raise ValidationError(f"{where}.assertions must be a non-empty array")
    seen: set[tuple[str, str]] = set()
    for index, assertion_value in enumerate(assertions):
        assertion_where = f"{where}.assertions[{index}]"
        assertion = exact_keys(assertion_value, {"json_pointer", "operator", "value"}, assertion_where)
        pointer = assertion["json_pointer"]
        if not isinstance(pointer, str) or JSON_POINTER.fullmatch(pointer) is None:
            raise ValidationError(f"{assertion_where}.json_pointer is not a valid non-root JSON Pointer")
        operator = enum(assertion["operator"], {"equals", "length-equals"}, f"{assertion_where}.operator")
        if operator == "length-equals":
            length = assertion["value"]
            if isinstance(length, bool) or not isinstance(length, int) or length < 0:
                raise ValidationError(f"{assertion_where}.value must be a non-negative integer")
        key = (pointer, operator)
        if key in seen:
            raise ValidationError(f"{assertion_where} duplicates assertion {key}")
        seen.add(key)


def tracked_paths() -> set[str]:
    result = subprocess.run(
        ["git", "ls-files", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return {item.decode("utf-8") for item in result.stdout.split(b"\0") if item}


def validate(manifest: dict[str, Any], check_local_files: bool) -> tuple[int, int, int]:
    exact_keys(manifest, {"$schema", "schema_version", "fixtures"}, "manifest")
    if manifest["$schema"] != "./provenance-manifest.schema.json":
        raise ValidationError("manifest.$schema must reference ./provenance-manifest.schema.json")
    if manifest["schema_version"] != 1:
        raise ValidationError("manifest.schema_version must be 1")
    fixtures = manifest["fixtures"]
    if not isinstance(fixtures, list):
        raise ValidationError("manifest.fixtures must be an array")

    ids: set[str] = set()
    identities: dict[str, tuple[str, str | None]] = {}
    derivation_parents: dict[str, dict[str, str]] = {}
    repository_paths: set[str] = set()
    generator_locations: set[str] = set()
    counts = {"repository": 0, "local-only": 0, "generated": 0}
    fixture_keys = {
        "id",
        "category",
        "description",
        "source",
        "artifact",
        "derivation",
        "redistribution",
        "expected",
        "ci",
        "input_policy",
    }

    for index, fixture_value in enumerate(fixtures):
        where = f"manifest.fixtures[{index}]"
        fixture = exact_keys(fixture_value, fixture_keys, where)
        fixture_id = nonempty_string(fixture["id"], f"{where}.id")
        if FIXTURE_ID.fullmatch(fixture_id) is None:
            raise ValidationError(f"{where}.id is not a lowercase stable fixture ID")
        if fixture_id in ids:
            raise ValidationError(f"duplicate fixture ID: {fixture_id}")
        ids.add(fixture_id)
        category = enum(
            fixture["category"],
            {"synthetic", "real-local", "derived-sanitized"},
            f"{where}.category",
        )
        nonempty_string(fixture["description"], f"{where}.description")
        check_source(fixture["source"], category, f"{where}.source")
        derivation_parents[fixture_id] = check_derivation(
            fixture["derivation"], category, f"{where}.derivation"
        )
        check_expected(fixture["expected"], f"{where}.expected")
        if fixture["input_policy"] != "read-only":
            raise ValidationError(f"{where}.input_policy must be 'read-only'")

        redistribution = exact_keys(
            fixture["redistribution"],
            {"status", "basis", "review_reference"},
            f"{where}.redistribution",
        )
        redistribution_status = enum(
            redistribution["status"],
            {"approved-for-repository", "not-permitted", "pending-review"},
            f"{where}.redistribution.status",
        )
        nonempty_string(redistribution["basis"], f"{where}.redistribution.basis")
        review_reference = redistribution["review_reference"]
        if review_reference is not None:
            nonempty_string(review_reference, f"{where}.redistribution.review_reference")

        ci = exact_keys(fixture["ci"], {"mode", "reason"}, f"{where}.ci")
        ci_mode = enum(ci["mode"], {"public", "local-only", "excluded"}, f"{where}.ci.mode")
        nonempty_string(ci["reason"], f"{where}.ci.reason")

        artifact = exact_keys(
            fixture["artifact"],
            {"storage", "path", "generator", "identity"},
            f"{where}.artifact",
        )
        storage = enum(
            artifact["storage"],
            {"repository", "local-only", "generated"},
            f"{where}.artifact.storage",
        )
        counts[storage] += 1
        if category == "real-local" and storage != "local-only":
            raise ValidationError(f"{where} real-local fixtures must use local-only storage")
        identity_status, size, digest = check_identity(
            artifact["identity"], f"{where}.artifact.identity"
        )
        identities[fixture_id] = (identity_status, digest)

        if storage == "repository":
            path = relative_path(artifact["path"], "fixtures/public/", f"{where}.artifact.path")
            if artifact["generator"] is not None:
                raise ValidationError(f"{where}.artifact.generator must be null for repository storage")
            if identity_status != "verified" or size is None or digest is None:
                raise ValidationError(f"{where} repository artifact identity must be verified")
            if redistribution_status != "approved-for-repository" or review_reference is None:
                raise ValidationError(f"{where} repository artifact needs recorded inclusion approval")
            if ci_mode not in {"public", "excluded"}:
                raise ValidationError(f"{where} repository artifact CI mode is invalid")
            if path in repository_paths:
                raise ValidationError(f"duplicate repository artifact path: {path}")
            repository_paths.add(path)
            check_file(path, size, digest, where)
        elif storage == "local-only":
            path = relative_path(artifact["path"], "fixtures/private/", f"{where}.artifact.path")
            if artifact["generator"] is not None:
                raise ValidationError(f"{where}.artifact.generator must be null for local-only storage")
            if redistribution_status == "approved-for-repository":
                raise ValidationError(f"{where} local-only artifact cannot be approved-for-repository")
            if ci_mode not in {"local-only", "excluded"}:
                raise ValidationError(f"{where} local-only artifact cannot use public CI")
            if check_local_files:
                if identity_status != "verified" or size is None or digest is None:
                    raise ValidationError(f"{where} cannot be locally checked until identity is verified")
                check_file(path, size, digest, where)
        else:
            if category != "synthetic":
                raise ValidationError(f"{where} generated storage is reserved for synthetic fixtures")
            if artifact["path"] is not None:
                raise ValidationError(f"{where}.artifact.path must be null for generated storage")
            generator = exact_keys(
                artifact["generator"],
                {"kind", "location", "recipe"},
                f"{where}.artifact.generator",
            )
            enum(generator["kind"], {"python-stdout"}, f"{where}.artifact.generator.kind")
            nonempty_string(generator["recipe"], f"{where}.artifact.generator.recipe")
            if identity_status != "verified" or size is None or digest is None:
                raise ValidationError(f"{where} generated bytes must have a verified identity")
            if redistribution_status != "approved-for-repository" or review_reference is None:
                raise ValidationError(f"{where} generated fixture needs recorded inclusion approval")
            if ci_mode not in {"public", "excluded"}:
                raise ValidationError(f"{where} generated fixture CI mode is invalid")
            generator_locations.add(
                check_generator_output(
                    generator["location"],
                    size,
                    digest,
                    f"{where}.artifact.generator",
                )
            )

    for fixture_id, parent_digests in derivation_parents.items():
        if fixture_id in parent_digests:
            raise ValidationError(f"fixture {fixture_id!r} cannot derive from itself")
        missing = sorted(set(parent_digests) - ids)
        if missing:
            raise ValidationError(f"fixture {fixture_id!r} has unknown parent IDs: {missing}")
        for parent_id, recorded_digest in parent_digests.items():
            parent_status, parent_digest = identities[parent_id]
            if parent_status != "verified" or parent_digest != recorded_digest:
                raise ValidationError(
                    f"fixture {fixture_id!r} parent {parent_id!r} must reference its "
                    "verified manifest SHA-256"
                )
    check_derivation_cycles(derivation_parents)

    tracked = tracked_paths()
    forbidden_private = sorted(path for path in tracked if path.startswith("fixtures/private/"))
    if forbidden_private:
        raise ValidationError(f"private fixture paths are tracked: {forbidden_private}")
    undeclared_saves = sorted(
        path for path in tracked if path.lower().endswith(".sav") and path not in repository_paths
    )
    if undeclared_saves:
        raise ValidationError(f"tracked .sav files lack approved manifest entries: {undeclared_saves}")
    untracked_generators = sorted(generator_locations - tracked)
    if untracked_generators:
        raise ValidationError(f"generated fixture scripts are not tracked: {untracked_generators}")

    public_root = ROOT / "fixtures" / "public"
    if public_root.is_symlink():
        raise ValidationError("fixtures/public must not be a symbolic link")
    if public_root.exists():
        public_items = list(public_root.rglob("*"))
        public_symlinks = sorted(
            item.relative_to(ROOT).as_posix() for item in public_items if item.is_symlink()
        )
        if public_symlinks:
            raise ValidationError(f"symbolic links are forbidden under fixtures/public: {public_symlinks}")
        undeclared_public = sorted(
            item.relative_to(ROOT).as_posix()
            for item in public_items
            if item.is_file() and item.relative_to(ROOT).as_posix() not in repository_paths
        )
        if undeclared_public:
            raise ValidationError(f"public fixture artifacts lack manifest entries: {undeclared_public}")

    return len(fixtures), counts["repository"], counts["local-only"]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=DEFAULT_MANIFEST,
        help="manifest to validate (default: fixtures/provenance-manifest.json)",
    )
    parser.add_argument(
        "--check-local-files",
        action="store_true",
        help="require local-only artifacts to be verified, present, and hash-matched",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest_path = args.manifest if args.manifest.is_absolute() else ROOT / args.manifest
    try:
        schema = load_json(SCHEMA)
        if schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
            raise ValidationError("fixture schema must declare JSON Schema draft 2020-12")
        if schema.get("properties", {}).get("schema_version", {}).get("const") != 1:
            raise ValidationError("fixture schema must define manifest schema_version 1")
        manifest = load_json(manifest_path)
        total, repository_count, local_count = validate(manifest, args.check_local_files)
    except (ValidationError, subprocess.CalledProcessError) as exc:
        print(f"fixture manifest validation failed: {exc}", file=sys.stderr)
        return 1
    print(
        f"fixture manifest valid: {total} entries "
        f"({repository_count} repository, {local_count} local-only)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

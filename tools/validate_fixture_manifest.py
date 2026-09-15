#!/usr/bin/env python3
"""Validate fixture metadata, bytes, and complete Git-history admission."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable

try:
    from jsonschema import Draft202012Validator
    from jsonschema.exceptions import SchemaError
    from referencing import Registry, Resource
    from referencing.exceptions import Unresolvable
    from referencing.jsonschema import DRAFT202012
except ModuleNotFoundError as dependency_error:  # pragma: no cover - exercised in CI setup
    Draft202012Validator = None  # type: ignore[assignment,misc]
    SchemaError = Exception  # type: ignore[assignment,misc]
    Registry = Resource = DRAFT202012 = None  # type: ignore[assignment,misc]
    Unresolvable = Exception  # type: ignore[assignment,misc]
    DEPENDENCY_ERROR: ModuleNotFoundError | None = dependency_error
else:
    DEPENDENCY_ERROR = None


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = ROOT / "fixtures" / "provenance-manifest.json"
SCHEMA = ROOT / "fixtures" / "provenance-manifest.schema.json"
MANIFEST_PATH = "fixtures/provenance-manifest.json"
SCHEMA_PATH = "fixtures/provenance-manifest.schema.json"
LOCK_PATH = "requirements/fixture-validation.lock"
SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema"
SHA256 = re.compile(r"^[0-9a-f]{64}$")
FIXTURE_ID = re.compile(r"^[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?$")
JSON_POINTER = re.compile(r"^/(?:[^~/]|~[01])*(?:/(?:[^~/]|~[01])*)*$")
REGULAR_MODES = {"100644", "100755"}


class ValidationError(Exception):
    pass


def _require_dependencies() -> None:
    if DEPENDENCY_ERROR is not None:
        raise ValidationError(
            f"required JSON Schema dependency is unavailable; install {LOCK_PATH} "
            "with CPython 3.12"
        ) from DEPENDENCY_ERROR


def _reject_duplicate_members(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError("JSON contains a duplicate decoded object member name")
        result[key] = value
    return result


def _reject_json_constant(_constant: str) -> None:
    raise ValidationError("JSON contains a non-standard numeric constant")


def load_json_bytes(data: bytes, source: str) -> dict[str, Any]:
    """Strictly decode one JSON object without echoing its contents or values."""
    try:
        text = data.decode("utf-8", errors="strict")
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_members,
            parse_constant=_reject_json_constant,
        )
    except ValidationError:
        raise
    except UnicodeDecodeError as exc:
        raise ValidationError(f"{source} is not strict UTF-8 JSON") from exc
    except json.JSONDecodeError as exc:
        raise ValidationError(
            f"{source} is not valid JSON at line {exc.lineno}, column {exc.colno}"
        ) from exc
    if not isinstance(value, dict):
        raise ValidationError(f"{source} must contain a JSON object")
    return value


def load_json(path: Path) -> dict[str, Any]:
    """Load schema or manifest JSON through the shared strict decoder."""
    try:
        absolute = path.absolute()
        current = Path(absolute.anchor)
        for component in absolute.parts[1:]:
            current /= component
            if current.is_symlink():
                raise ValidationError("JSON document path contains a symbolic-link component")
        data = path.read_bytes()
    except ValidationError:
        raise
    except OSError as exc:
        raise ValidationError(f"cannot read JSON document at {path}") from exc
    return load_json_bytes(data, str(path))


def _json_location(path: Iterable[Any]) -> str:
    parts = list(path)
    return "$" if not parts else "$" + "".join(
        f"[{item}]" if isinstance(item, int) else f".{item}" for item in parts
    )


def _walk_refs(value: Any, path: tuple[Any, ...] = ()) -> Iterable[tuple[tuple[Any, ...], Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = path + (key,)
            if key in {"$ref", "$dynamicRef"}:
                yield child_path, child
            yield from _walk_refs(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from _walk_refs(child, path + (index,))


def _walk_keyword(
    value: Any,
    keyword: str,
    path: tuple[Any, ...] = (),
) -> Iterable[tuple[tuple[Any, ...], Any]]:
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = path + (key,)
            if key == keyword:
                yield child_path, child
            yield from _walk_keyword(child, keyword, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from _walk_keyword(child, keyword, path + (index,))


def validate_schema(schema: dict[str, Any]) -> Any:
    """Validate the whole Draft 2020-12 schema and every declared reference."""
    _require_dependencies()
    if schema.get("$schema") != SCHEMA_DIALECT:
        raise ValidationError("fixture schema must declare JSON Schema draft 2020-12")
    schema_id = schema.get("$id")
    if not isinstance(schema_id, str) or not schema_id:
        raise ValidationError("fixture schema must have a non-empty $id")
    for path, _value in _walk_keyword(schema, "$id"):
        if path != ("$id",):
            raise ValidationError(
                f"fixture schema contains an unsupported nested $id at {_json_location(path)}"
            )
    try:
        Draft202012Validator.check_schema(schema)
    except SchemaError as exc:
        raise ValidationError(
            "fixture schema fails its Draft 2020-12 metaschema at "
            f"{_json_location(exc.absolute_schema_path)}"
        ) from exc

    def reject_retrieval(uri: str) -> Resource[Any]:
        raise Unresolvable(ref=uri)

    try:
        resource = Resource.from_contents(schema, default_specification=DRAFT202012)
        registry = Registry(retrieve=reject_retrieval).with_resource(schema_id, resource)
        resolver = registry.resolver(schema_id)
        for ref_path, ref in _walk_refs(schema):
            if not isinstance(ref, str) or not ref.startswith("#"):
                raise ValidationError(
                    "fixture schema contains an unsupported non-fragment $ref at "
                    f"{_json_location(ref_path)}"
                )
            try:
                resolver.lookup(ref)
            except Unresolvable as exc:
                raise ValidationError(
                    f"fixture schema contains an unresolvable $ref at {_json_location(ref_path)}"
                ) from exc
        return Draft202012Validator(schema, registry=registry)
    except ValidationError:
        raise
    except Exception as exc:
        raise ValidationError("fixture schema reference registry could not be constructed") from exc


def apply_schema(schema: dict[str, Any], manifest: dict[str, Any]) -> None:
    validator = validate_schema(schema)
    error = next(iter(validator.iter_errors(manifest)), None)
    if error is not None:
        raise ValidationError(
            "manifest fails the fixture schema at "
            f"{_json_location(error.absolute_path)} ({error.validator})"
        ) from error


def exact_keys(value: Any, required: set[str], where: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValidationError(f"{where} must be an object")
    if set(value) != required:
        raise ValidationError(f"{where} has missing or unsupported members")
    return value


def nonempty_string(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValidationError(f"{where} must be a non-empty string")
    return value


def enum(value: Any, allowed: set[str], where: str) -> str:
    if value not in allowed:
        raise ValidationError(f"{where} is not an allowed value")
    return value


def relative_path(value: Any, prefix: str, where: str) -> str:
    path = nonempty_string(value, where)
    pure = PurePosixPath(path)
    parts = path.split("/")
    if (
        pure.is_absolute()
        or any(part in {"", ".", ".."} for part in parts)
        or "\\" in path
        or any(ord(character) < 32 or ord(character) == 127 for character in path)
    ):
        raise ValidationError(f"{where} must be a safe project-relative POSIX path")
    if not path.startswith(prefix):
        raise ValidationError(f"{where} is outside its required repository area")
    return path


@dataclass(frozen=True)
class TreeEntry:
    mode: str
    object_type: str
    oid: str
    size: int | None


class RepositoryView:
    def __init__(
        self,
        root: Path,
        entries: dict[str, TreeEntry],
        reader: Callable[[str], bytes],
        *,
        historical: bool,
    ) -> None:
        self.root = root
        self.entries = entries
        self._reader = reader
        self.historical = historical

    def require_regular(self, path: str, where: str) -> TreeEntry:
        entry = self.entries.get(path)
        if entry is None:
            raise ValidationError(f"{where} is not tracked in the inspected tree")
        if entry.object_type != "blob" or entry.mode not in REGULAR_MODES:
            raise ValidationError(f"{where} must be a tracked ordinary file")
        return entry

    def read_bytes(self, path: str, where: str) -> bytes:
        self.require_regular(path, where)
        try:
            return self._reader(path)
        except (OSError, subprocess.CalledProcessError, ValidationError) as exc:
            raise ValidationError(f"{where} bytes cannot be read safely") from exc


def _git_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment["GIT_NO_LAZY_FETCH"] = "1"
    environment["GIT_NO_REPLACE_OBJECTS"] = "1"
    return environment


def _git(root: Path, arguments: list[str]) -> bytes:
    try:
        result = subprocess.run(
            ["git", *arguments],
            cwd=root,
            check=True,
            capture_output=True,
            env=_git_environment(),
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValidationError("required Git object inspection failed") from exc
    return result.stdout


def _decode_git_path(data: bytes) -> str:
    try:
        path = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ValidationError("Git tree contains a non-UTF-8 path") from exc
    relative_path(path, "", "Git tree path")
    return path


def worktree_view(root: Path = ROOT) -> RepositoryView:
    entries: dict[str, TreeEntry] = {}
    output = _git(root, ["ls-files", "-s", "-z"])
    for record in output.split(b"\0"):
        if not record:
            continue
        metadata, separator, raw_path = record.partition(b"\t")
        fields = metadata.split()
        if not separator or len(fields) != 3:
            raise ValidationError("Git index returned an unsupported entry")
        mode, raw_oid, stage = fields
        if stage != b"0":
            raise ValidationError("Git index contains an unresolved entry")
        path = _decode_git_path(raw_path)
        entries[path] = TreeEntry(mode.decode("ascii"), "blob", raw_oid.decode("ascii"), None)

    def read_worktree(path: str) -> bytes:
        candidate = root
        for component in path.split("/"):
            candidate /= component
            if candidate.is_symlink():
                raise ValidationError("worktree path contains a symbolic-link component")
        if not candidate.is_file():
            raise ValidationError("worktree ordinary file is missing")
        return candidate.read_bytes()

    return RepositoryView(root, entries, read_worktree, historical=False)


def _safe_worktree_file(root: Path, path: str, where: str) -> Path:
    candidate = root
    for component in path.split("/"):
        candidate /= component
        if candidate.is_symlink():
            raise ValidationError(f"{where} contains a symbolic-link component")
    if not candidate.is_file():
        raise ValidationError(f"{where} ordinary file is unavailable")
    return candidate


def _tree_view(root: Path, commit_oid: str) -> RepositoryView:
    entries: dict[str, TreeEntry] = {}
    output = _git(root, ["ls-tree", "-r", "-z", "--full-tree", "-l", commit_oid])
    for record in output.split(b"\0"):
        if not record:
            continue
        metadata, separator, raw_path = record.partition(b"\t")
        fields = metadata.split()
        if not separator or len(fields) != 4:
            raise ValidationError("Git tree returned an unsupported entry")
        raw_mode, raw_type, raw_oid, raw_size = fields
        path = _decode_git_path(raw_path)
        try:
            size = None if raw_size == b"-" else int(raw_size)
            entry = TreeEntry(
                raw_mode.decode("ascii"),
                raw_type.decode("ascii"),
                raw_oid.decode("ascii"),
                size,
            )
        except (UnicodeDecodeError, ValueError) as exc:
            raise ValidationError("Git tree contains invalid object metadata") from exc
        entries[path] = entry

    def read_blob(path: str) -> bytes:
        entry = entries[path]
        return _git(root, ["cat-file", "blob", entry.oid])

    return RepositoryView(root, entries, read_blob, historical=True)


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
        raise ValidationError(f"{where}.sha256 must be a lowercase SHA-256")
    return status, size, digest


def check_bytes(data: bytes, size: int, digest: str, where: str) -> None:
    if len(data) != size:
        raise ValidationError(f"{where} size does not match its admitted identity")
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValidationError(f"{where} SHA-256 does not match its admitted identity")


def check_generator_output(
    location_value: Any,
    size: int,
    digest: str,
    where: str,
    repository: RepositoryView,
    execute_generators: bool,
) -> str:
    location = relative_path(location_value, "", f"{where}.location")
    if Path(location).suffix != ".py":
        raise ValidationError(f"{where}.location must name a Python file")
    repository.require_regular(location, f"{where}.location")
    if not execute_generators:
        return location
    repository.read_bytes(location, f"{where}.location")
    path = _safe_worktree_file(repository.root, location, f"{where}.location")
    try:
        result = subprocess.run(
            [sys.executable, str(path)],
            cwd=repository.root,
            check=False,
            capture_output=True,
            timeout=30,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValidationError(f"{where} could not produce fixture bytes") from exc
    if result.returncode != 0:
        raise ValidationError(f"{where} generator failed")
    check_bytes(result.stdout, size, digest, f"{where} output")
    return location


def check_derivation_cycles(graph: dict[str, dict[str, str]]) -> None:
    visited: set[str] = set()
    visiting: set[str] = set()

    def visit(fixture_id: str) -> None:
        if fixture_id in visited:
            return
        if fixture_id in visiting:
            raise ValidationError("fixture derivation graph contains a cycle")
        visiting.add(fixture_id)
        for parent_id in graph.get(fixture_id, {}):
            visit(parent_id)
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
    enum(source["family"], {"JA2_REBORN", "STRACCIATELLA", "CLASSIC", "UNKNOWN"}, f"{where}.family")
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
        raise ValidationError(f"{where}.origin does not match the fixture category")
    evidence = source["evidence"]
    if not isinstance(evidence, list) or not evidence:
        raise ValidationError(f"{where}.evidence must be a non-empty array")
    for index, item in enumerate(evidence):
        nonempty_string(item, f"{where}.evidence[{index}]")


def check_derivation(value: Any, category: str, where: str) -> dict[str, str]:
    if category != "derived-sanitized":
        if value is not None:
            raise ValidationError(f"{where} must be null for this fixture category")
        return {}
    derivation = exact_keys(value, {"parents", "method", "sanitization"}, where)
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
            raise ValidationError(f"{parent_where} duplicates a parent fixture ID")
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
            raise ValidationError(f"{assertion_where}.json_pointer is invalid")
        operator = enum(assertion["operator"], {"equals", "length-equals"}, f"{assertion_where}.operator")
        if operator == "length-equals":
            length = assertion["value"]
            if isinstance(length, bool) or not isinstance(length, int) or length < 0:
                raise ValidationError(f"{assertion_where}.value must be a non-negative integer")
        key = (pointer, operator)
        if key in seen:
            raise ValidationError(f"{assertion_where} duplicates an assertion target")
        seen.add(key)


def _check_repository_tree(repository: RepositoryView, repository_paths: set[str]) -> None:
    for path, entry in repository.entries.items():
        folded = path.casefold()
        if folded == "fixtures/private" or folded.startswith("fixtures/private/"):
            raise ValidationError("an inspected tree contains a forbidden private fixture path")
        is_public = folded == "fixtures/public" or folded.startswith("fixtures/public/")
        is_save = folded.endswith(".sav")
        if is_public:
            if not path.startswith("fixtures/public/"):
                raise ValidationError("an inspected tree contains a non-canonical public fixture path")
            if entry.object_type != "blob" or entry.mode not in REGULAR_MODES:
                raise ValidationError("an inspected tree contains a non-regular public fixture artifact")
            if path not in repository_paths:
                raise ValidationError("a public fixture artifact lacks admission in that tree's manifest")
        if is_save and path not in repository_paths:
            raise ValidationError("a case-insensitive .sav path lacks admission in that tree's manifest")


def _is_fixture_artifact_path(path: str) -> bool:
    folded = path.casefold()
    return (
        folded.endswith(".sav")
        or folded == "fixtures/public"
        or folded.startswith("fixtures/public/")
        or folded == "fixtures/private"
        or folded.startswith("fixtures/private/")
    )


def _check_worktree_public_files(root: Path, repository_paths: set[str]) -> None:
    public_root = root / "fixtures" / "public"
    if public_root.is_symlink():
        raise ValidationError("fixtures/public must not be a symbolic link")
    if not public_root.exists():
        return
    for directory, directory_names, filenames in os.walk(public_root, followlinks=False):
        directory_path = Path(directory)
        for name in list(directory_names):
            child = directory_path / name
            if child.is_symlink():
                raise ValidationError("a symbolic link is present below fixtures/public")
        for name in filenames:
            child = directory_path / name
            if child.is_symlink() or not child.is_file():
                raise ValidationError("a non-regular item is present below fixtures/public")
            path = child.relative_to(root).as_posix()
            if path not in repository_paths:
                raise ValidationError("a worktree public fixture artifact lacks manifest admission")


def validate(
    manifest: dict[str, Any],
    check_local_files: bool,
    *,
    schema: dict[str, Any] | None = None,
    repository: RepositoryView | None = None,
    execute_generators: bool = True,
) -> tuple[int, int, int]:
    """Run schema enforcement followed by repository-specific semantic/byte checks."""
    if schema is None:
        schema = load_json(SCHEMA)
    apply_schema(schema, manifest)
    if repository is None:
        repository = worktree_view(ROOT)

    exact_keys(manifest, {"$schema", "schema_version", "fixtures"}, "manifest")
    if manifest["$schema"] != "./provenance-manifest.schema.json":
        raise ValidationError("manifest.$schema must name the repository fixture schema")
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
        "id", "category", "description", "source", "artifact", "derivation",
        "redistribution", "expected", "ci", "input_policy",
    }

    for index, fixture_value in enumerate(fixtures):
        where = f"manifest.fixtures[{index}]"
        fixture = exact_keys(fixture_value, fixture_keys, where)
        fixture_id = nonempty_string(fixture["id"], f"{where}.id")
        if FIXTURE_ID.fullmatch(fixture_id) is None:
            raise ValidationError(f"{where}.id is not a lowercase stable fixture ID")
        if fixture_id in ids:
            raise ValidationError("manifest contains a duplicate fixture ID")
        ids.add(fixture_id)
        category = enum(fixture["category"], {"synthetic", "real-local", "derived-sanitized"}, f"{where}.category")
        nonempty_string(fixture["description"], f"{where}.description")
        check_source(fixture["source"], category, f"{where}.source")
        derivation_parents[fixture_id] = check_derivation(fixture["derivation"], category, f"{where}.derivation")
        check_expected(fixture["expected"], f"{where}.expected")
        if fixture["input_policy"] != "read-only":
            raise ValidationError(f"{where}.input_policy must be read-only")

        redistribution = exact_keys(
            fixture["redistribution"], {"status", "basis", "review_reference"}, f"{where}.redistribution"
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

        artifact = exact_keys(fixture["artifact"], {"storage", "path", "generator", "identity"}, f"{where}.artifact")
        storage = enum(artifact["storage"], {"repository", "local-only", "generated"}, f"{where}.artifact.storage")
        counts[storage] += 1
        if category == "real-local" and storage != "local-only":
            raise ValidationError(f"{where} real-local fixture must use local-only storage")
        identity_status, size, digest = check_identity(artifact["identity"], f"{where}.artifact.identity")
        identities[fixture_id] = (identity_status, digest)

        if storage == "repository":
            path = relative_path(artifact["path"], "fixtures/public/", f"{where}.artifact.path")
            if artifact["generator"] is not None:
                raise ValidationError(f"{where}.artifact.generator must be null")
            if identity_status != "verified" or size is None or digest is None:
                raise ValidationError(f"{where} repository artifact identity must be verified")
            if redistribution_status != "approved-for-repository" or review_reference is None:
                raise ValidationError(f"{where} repository artifact lacks recorded inclusion approval")
            if ci_mode not in {"public", "excluded"}:
                raise ValidationError(f"{where} repository artifact CI mode is invalid")
            if path in repository_paths:
                raise ValidationError("manifest contains a duplicate repository artifact path")
            repository_paths.add(path)
            data = repository.read_bytes(path, f"{where}.artifact.path")
            check_bytes(data, size, digest, f"{where}.artifact")
        elif storage == "local-only":
            path = relative_path(artifact["path"], "fixtures/private/", f"{where}.artifact.path")
            if artifact["generator"] is not None:
                raise ValidationError(f"{where}.artifact.generator must be null")
            if redistribution_status == "approved-for-repository":
                raise ValidationError(f"{where} local-only artifact cannot be repository-approved")
            if ci_mode not in {"local-only", "excluded"}:
                raise ValidationError(f"{where} local-only artifact cannot use public CI")
            if check_local_files:
                if identity_status != "verified" or size is None or digest is None:
                    raise ValidationError(f"{where} local check requires verified identity")
                candidate = _safe_worktree_file(repository.root, path, f"{where}.artifact.path")
                try:
                    data = candidate.read_bytes()
                except OSError as exc:
                    raise ValidationError(f"{where} local fixture cannot be read safely") from exc
                check_bytes(data, size, digest, f"{where}.artifact")
        else:
            if category != "synthetic":
                raise ValidationError(f"{where} generated storage is reserved for synthetic fixtures")
            if artifact["path"] is not None:
                raise ValidationError(f"{where}.artifact.path must be null")
            generator = exact_keys(artifact["generator"], {"kind", "location", "recipe"}, f"{where}.artifact.generator")
            enum(generator["kind"], {"python-stdout"}, f"{where}.artifact.generator.kind")
            nonempty_string(generator["recipe"], f"{where}.artifact.generator.recipe")
            if identity_status != "verified" or size is None or digest is None:
                raise ValidationError(f"{where} generated bytes must have verified identity")
            if redistribution_status != "approved-for-repository" or review_reference is None:
                raise ValidationError(f"{where} generated fixture lacks recorded inclusion approval")
            if ci_mode not in {"public", "excluded"}:
                raise ValidationError(f"{where} generated fixture CI mode is invalid")
            generator_locations.add(
                check_generator_output(generator["location"], size, digest, f"{where}.artifact.generator", repository, execute_generators)
            )

    for fixture_id, parent_digests in derivation_parents.items():
        if fixture_id in parent_digests:
            raise ValidationError("a fixture cannot derive from itself")
        if set(parent_digests) - ids:
            raise ValidationError("a derived fixture references an unknown parent ID")
        for parent_id, recorded_digest in parent_digests.items():
            parent_status, parent_digest = identities[parent_id]
            if parent_status != "verified" or parent_digest != recorded_digest:
                raise ValidationError("a derived fixture does not reference its parent's verified identity")
    check_derivation_cycles(derivation_parents)

    _check_repository_tree(repository, repository_paths)
    for location in generator_locations:
        repository.require_regular(location, "generated fixture script")
    if not repository.historical:
        _check_worktree_public_files(repository.root, repository_paths)

    return len(fixtures), counts["repository"], counts["local-only"]


def _resolve_heads(root: Path, requested_heads: list[str]) -> list[str]:
    resolved: list[str] = []
    for revision in requested_heads:
        try:
            output = subprocess.run(
                ["git", "rev-parse", "--verify", "--quiet", "--end-of-options", f"{revision}^{{commit}}"],
                cwd=root,
                check=True,
                capture_output=True,
                env=_git_environment(),
            ).stdout
            oid = output.decode("ascii").strip()
        except (OSError, subprocess.CalledProcessError, UnicodeDecodeError) as exc:
            raise ValidationError("a requested history head is not an available commit") from exc
        if not re.fullmatch(r"[0-9a-f]{40,64}", oid):
            raise ValidationError("a requested history head did not resolve to an object ID")
        if oid not in resolved:
            resolved.append(oid)
    return resolved


def _verify_complete_history(root: Path, heads: list[str]) -> list[str]:
    shallow = _git(root, ["rev-parse", "--is-shallow-repository"]).decode("ascii").strip()
    if shallow != "false":
        raise ValidationError("fixture admission requires a complete, non-shallow Git history")
    closure = _git(root, ["rev-list", "--objects", "--missing=print", *heads])
    if any(line.startswith((b"?", b"-")) for line in closure.splitlines()):
        raise ValidationError("reachable Git history has missing required objects")
    commits = _git(root, ["rev-list", "--topo-order", "--reverse", *heads])
    try:
        result = [line.decode("ascii") for line in commits.splitlines() if line]
    except UnicodeDecodeError as exc:
        raise ValidationError("Git returned an invalid commit object ID") from exc
    if not result:
        raise ValidationError("requested history contains no commits")
    return result


def validate_history(requested_heads: list[str], root: Path = ROOT) -> int:
    """Validate every commit tree reachable from every explicit requested head."""
    if not requested_heads:
        raise ValidationError("at least one explicit history head is required")
    heads = _resolve_heads(root, requested_heads)
    commits = _verify_complete_history(root, heads)
    for commit_oid in commits:
        repository = _tree_view(root, commit_oid)
        manifest_entry = repository.entries.get(MANIFEST_PATH)
        schema_entry = repository.entries.get(SCHEMA_PATH)
        has_fixture_artifact = any(_is_fixture_artifact_path(path) for path in repository.entries)
        if schema_entry is not None:
            schema = load_json_bytes(
                repository.read_bytes(SCHEMA_PATH, "historical fixture schema"),
                "historical fixture schema",
            )
            validate_schema(schema)
        else:
            schema = None
        if manifest_entry is None:
            if has_fixture_artifact:
                raise ValidationError("a historical fixture artifact has no manifest in its commit tree")
            continue
        if schema is None:
            raise ValidationError("a historical fixture manifest has no schema in its commit tree")
        manifest = load_json_bytes(
            repository.read_bytes(MANIFEST_PATH, "historical fixture manifest"),
            "historical fixture manifest",
        )
        validate(
            manifest,
            check_local_files=False,
            schema=schema,
            repository=repository,
            execute_generators=False,
        )
    return len(commits)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=ROOT,
        help="Git worktree to inspect (default: this repository)",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(MANIFEST_PATH),
        help="manifest relative to the repository root",
    )
    parser.add_argument(
        "--schema",
        type=Path,
        default=Path(SCHEMA_PATH),
        help="schema relative to the repository root",
    )
    parser.add_argument(
        "--head",
        action="append",
        required=True,
        help="explicit Git commit/revision whose complete reachable history must be scanned; repeatable",
    )
    parser.add_argument(
        "--check-local-files",
        action="store_true",
        help="require local-only artifacts to be verified, present, and hash-matched",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.repository_root.resolve()
    manifest_path = args.manifest if args.manifest.is_absolute() else root / args.manifest
    schema_path = args.schema if args.schema.is_absolute() else root / args.schema
    try:
        schema = load_json(schema_path)
        manifest = load_json(manifest_path)
        total, repository_count, local_count = validate(
            manifest,
            args.check_local_files,
            schema=schema,
            repository=worktree_view(root),
        )
        commit_count = validate_history(args.head, root)
    except ValidationError as exc:
        print(f"fixture manifest validation failed: {exc}", file=sys.stderr)
        return 1
    print(
        f"fixture manifest valid: {total} entries "
        f"({repository_count} repository, {local_count} local-only); "
        f"{commit_count} reachable commit trees inspected"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Validate fixture documents, history, and exact executable admission heads."""

from __future__ import annotations

import argparse
import errno
import hashlib
import json
import os
import platform
import re
import resource
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path, PurePosixPath
from typing import Any, Callable, Iterable, Iterator, Sequence

try:
    from jsonschema import Draft202012Validator
    from jsonschema.exceptions import SchemaError
    from jsonschema.validators import extend
    from referencing import Registry, Resource
    from referencing.exceptions import Unresolvable
    from referencing.jsonschema import DRAFT202012
except ModuleNotFoundError as dependency_error:  # pragma: no cover - CI setup owns this path
    Draft202012Validator = None  # type: ignore[assignment,misc]
    SchemaError = Exception  # type: ignore[assignment,misc]
    Registry = Resource = DRAFT202012 = None  # type: ignore[assignment,misc]
    Unresolvable = Exception  # type: ignore[assignment,misc]
    extend = None  # type: ignore[assignment]
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
SHA256_PATTERN = r"^[0-9a-f]{64}(?![\s\S])"
FIXTURE_ID_PATTERN = r"^[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?(?![\s\S])"
JSON_POINTER_PATTERN = r"^/(?:[^~/]|~[01])*(?:/(?:[^~/]|~[01])*)*(?![\s\S])"
SHA256 = re.compile(SHA256_PATTERN)
FIXTURE_ID = re.compile(FIXTURE_ID_PATTERN)
JSON_POINTER = re.compile(JSON_POINTER_PATTERN)
REGULAR_MODES = {"100644", "100755"}
EXACT_OID = re.compile(r"(?:[0-9a-f]{40}|[0-9a-f]{64})\Z")

# JSON numbers are exact in this profile. These resource bounds are deliberately
# much wider than any manifest field while still rejecting pathological tokens.
MAX_JSON_NUMBER_TOKEN_CHARS = 512
MAX_JSON_SIGNIFICANT_DIGITS = 256
MAX_JSON_ABS_EXPONENT = 100_000
MAX_FIXTURE_SIZE_BYTES = 1_073_741_824

# Draft 2020-12 locations whose values are themselves schemas. Annotation and
# instance-valued keywords (including examples, default, const, and enum) are
# intentionally absent so literal "$ref" data there remains data.
SCHEMA_MAP_KEYWORDS = ("$defs", "dependentSchemas", "patternProperties", "properties")
SCHEMA_SINGLE_KEYWORDS = (
    "additionalProperties", "contains", "contentSchema", "else", "if", "items",
    "not", "propertyNames", "then", "unevaluatedItems", "unevaluatedProperties",
)
SCHEMA_ARRAY_KEYWORDS = ("allOf", "anyOf", "oneOf", "prefixItems")

# Linux x86-64 syscall numbers. Generator subprocesses inherit no descriptors
# except stdio and the seccomp program, so denying socket creation closes their
# network boundary without requiring a network namespace from the host.
NETWORK_SYSCALLS_X86_64 = {
    41, 42, 43, 44, 45, 46, 47, 49, 50, 51, 52, 53, 54, 55, 288, 299, 307,
}


class ValidationError(Exception):
    """A controlled, sanitized fixture-policy failure."""


@dataclass(frozen=True)
class ArtifactIdentity:
    status: str
    size_bytes: int | None
    sha256: str | None


@dataclass(frozen=True)
class RepositoryArtifact:
    fixture_id: str
    path: str
    identity: ArtifactIdentity


@dataclass(frozen=True)
class LocalArtifact:
    fixture_id: str
    path: str
    identity: ArtifactIdentity


@dataclass(frozen=True)
class GeneratorDeclaration:
    fixture_id: str
    location: str
    size_bytes: int
    sha256: str


@dataclass(frozen=True)
class DocumentValidationResult:
    """Pure JSON Schema and repository-semantic validation; no files are read."""

    fixture_count: int
    repository_artifacts: tuple[RepositoryArtifact, ...]
    local_artifacts: tuple[LocalArtifact, ...]
    generators: tuple[GeneratorDeclaration, ...]


@dataclass(frozen=True)
class RepositoryValidationResult:
    """Static file, mode, privacy, and byte checks for one repository view."""

    repository_bytes_verified: int
    generator_files_verified: int
    local_bytes_verified: int


@dataclass(frozen=True)
class WorktreeValidationResult:
    """Non-authoritative worktree validation for imported/local consumers."""

    repository: str
    snapshot_sha256: str
    document: DocumentValidationResult
    repository_checks: RepositoryValidationResult
    generator_measurements: tuple["GeneratorMeasurement", ...]


@dataclass(frozen=True)
class EvaluatorIdentity:
    repository: str
    commit_oid: str | None
    tree_oid: str | None
    validator_sha256: str


@dataclass(frozen=True)
class SubjectIdentity:
    role: str
    repository: str
    commit_oid: str
    tree_oid: str


@dataclass(frozen=True)
class StaticTreeAdmissionResult:
    evaluator: EvaluatorIdentity
    subject: SubjectIdentity
    document: DocumentValidationResult | None
    repository_checks: RepositoryValidationResult | None


@dataclass(frozen=True)
class StaticHistoryAdmissionResult:
    evaluator: EvaluatorIdentity
    requested_subjects: tuple[SubjectIdentity, ...]
    trees: tuple[StaticTreeAdmissionResult, ...]

    @property
    def commit_count(self) -> int:
        return len(self.trees)

    @property
    def manifested_tree_count(self) -> int:
        return sum(tree.document is not None for tree in self.trees)


@dataclass(frozen=True)
class GeneratorMeasurement:
    fixture_id: str
    location: str
    size_bytes: int
    sha256: str


@dataclass(frozen=True)
class HeadExecutionResult:
    evaluator: EvaluatorIdentity
    subject: SubjectIdentity
    document: DocumentValidationResult | None
    repository_checks: RepositoryValidationResult | None
    snapshot_sha256: str | None
    generator_measurements: tuple[GeneratorMeasurement, ...]


@dataclass(frozen=True)
class FixtureAdmissionResult:
    evaluator: EvaluatorIdentity
    base: SubjectIdentity | None
    history: StaticHistoryAdmissionResult
    heads: tuple[HeadExecutionResult, ...]


@dataclass(frozen=True)
class AdmissionRequest:
    role: str
    commit_oid: str


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
        except (KeyError, OSError, subprocess.CalledProcessError, ValidationError) as exc:
            raise ValidationError(f"{where} bytes cannot be read safely") from exc


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


def _check_number_token(token: str) -> None:
    if len(token) > MAX_JSON_NUMBER_TOKEN_CHARS:
        raise ValidationError("JSON number exceeds the supported exact numeric domain")
    mantissa, marker, exponent_text = token.lower().partition("e")
    digits = mantissa.lstrip("-").replace(".", "")
    significant_digits = digits.lstrip("0")
    if len(significant_digits) > MAX_JSON_SIGNIFICANT_DIGITS:
        raise ValidationError("JSON number exceeds the supported exact numeric domain")
    if marker:
        try:
            exponent = int(exponent_text)
        except ValueError as exc:
            raise ValidationError("JSON number is outside the supported exact numeric domain") from exc
        if abs(exponent) > MAX_JSON_ABS_EXPONENT:
            raise ValidationError("JSON number exceeds the supported exact numeric domain")


def _decode_json_integer(token: str) -> int:
    _check_number_token(token)
    return int(token)


def _decode_json_decimal(token: str) -> Decimal:
    _check_number_token(token)
    value = Decimal(token)
    if not value.is_finite():
        raise ValidationError("JSON number is outside the supported exact numeric domain")
    return value


def load_json_bytes(data: bytes, source: str) -> dict[str, Any]:
    """Strictly decode one JSON object without echoing its contents or values."""
    try:
        text = data.decode("utf-8", errors="strict")
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_members,
            parse_constant=_reject_json_constant,
            parse_int=_decode_json_integer,
            parse_float=_decode_json_decimal,
        )
    except ValidationError:
        raise
    except UnicodeDecodeError as exc:
        raise ValidationError(f"{source} is not strict UTF-8 JSON") from exc
    except json.JSONDecodeError as exc:
        raise ValidationError(
            f"{source} is not valid JSON at line {exc.lineno}, column {exc.colno}"
        ) from exc
    except ValueError as exc:
        raise ValidationError(f"{source} has an unsupported JSON number") from exc
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


def _schema_nodes(schema: Any, path: tuple[Any, ...] = ()) -> Iterator[tuple[tuple[Any, ...], Any]]:
    if isinstance(schema, bool):
        yield path, schema
        return
    if not isinstance(schema, dict):
        return
    yield path, schema
    for keyword in SCHEMA_MAP_KEYWORDS:
        children = schema.get(keyword)
        if isinstance(children, dict):
            for name, child in children.items():
                yield from _schema_nodes(child, path + (keyword, name))
    for keyword in SCHEMA_SINGLE_KEYWORDS:
        if keyword in schema:
            yield from _schema_nodes(schema[keyword], path + (keyword,))
    for keyword in SCHEMA_ARRAY_KEYWORDS:
        children = schema.get(keyword)
        if isinstance(children, list):
            for index, child in enumerate(children):
                yield from _schema_nodes(child, path + (keyword, index))


def _local_ref_path(
    ref: str,
    where: tuple[Any, ...],
    document: dict[str, Any],
) -> tuple[Any, ...]:
    if ref == "#":
        return ()
    if not ref.startswith("#/") or "%" in ref:
        raise ValidationError(
            f"fixture schema contains an unsupported non-pointer $ref at {_json_location(where)}"
        )
    result: list[Any] = []
    current: Any = document
    for raw_token in ref[2:].split("/"):
        if re.search(r"~(?:[^01]|$)", raw_token):
            raise ValidationError(
                f"fixture schema contains an invalid JSON Pointer $ref at {_json_location(where)}"
            )
        token = raw_token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, dict) and token in current:
            result.append(token)
            current = current[token]
        elif (
            isinstance(current, list)
            and re.fullmatch(r"0|[1-9][0-9]*", token)
            and int(token) < len(current)
        ):
            index = int(token)
            result.append(index)
            current = current[index]
        else:
            raise ValidationError(
                "fixture schema reference does not target a supported subschema at "
                f"{_json_location(where)}"
            )
    return tuple(result)


def _decimal_is_integer(value: Decimal) -> bool:
    if not value.is_finite():
        return False
    if value.is_zero():
        return True
    digits = value.as_tuple().digits
    exponent = value.as_tuple().exponent
    if not isinstance(exponent, int):
        return False
    if exponent >= 0:
        return True
    fractional_digits = -exponent
    if fractional_digits > len(digits):
        return False
    return all(digit == 0 for digit in digits[-fractional_digits:])


def _is_exact_number(_checker: Any, value: Any) -> bool:
    return not isinstance(value, bool) and isinstance(value, (int, Decimal))


def _is_mathematical_integer(_checker: Any, value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, int):
        return True
    return isinstance(value, Decimal) and _decimal_is_integer(value)


def _strict_validator_class() -> Any:
    _require_dependencies()
    return extend(
        Draft202012Validator,
        type_checker=(
            Draft202012Validator.TYPE_CHECKER
            .redefine("number", _is_exact_number)
            .redefine("integer", _is_mathematical_integer)
        ),
    )


def validate_schema(schema: dict[str, Any]) -> Any:
    """Validate the bounded, local Draft 2020-12 schema profile."""
    validator_class = _strict_validator_class()
    if schema.get("$schema") != SCHEMA_DIALECT:
        raise ValidationError("fixture schema must declare JSON Schema draft 2020-12")
    schema_id = schema.get("$id")
    if not isinstance(schema_id, str) or not schema_id:
        raise ValidationError("fixture schema must have a non-empty $id")
    try:
        validator_class.check_schema(schema)
    except SchemaError as exc:
        raise ValidationError(
            "fixture schema fails its Draft 2020-12 metaschema at "
            f"{_json_location(exc.absolute_schema_path)}"
        ) from exc

    nodes = tuple(_schema_nodes(schema))
    node_paths = {path for path, _node in nodes}
    for path, node in nodes:
        if isinstance(node, bool):
            continue
        if "$id" in node and path:
            raise ValidationError(
                f"fixture schema contains an unsupported nested $id at {_json_location(path + ('$id',))}"
            )
        for keyword in ("$ref", "$dynamicRef"):
            if keyword not in node:
                continue
            ref_path = path + (keyword,)
            ref = node[keyword]
            if not isinstance(ref, str) or not ref.startswith("#"):
                raise ValidationError(
                    "fixture schema contains an unsupported external reference at "
                    f"{_json_location(ref_path)}"
                )
            target_path = _local_ref_path(ref, ref_path, schema)
            if target_path not in node_paths:
                raise ValidationError(
                    "fixture schema reference does not target a supported subschema at "
                    f"{_json_location(ref_path)}"
                )

    def reject_retrieval(uri: str) -> Resource[Any]:
        raise Unresolvable(ref=uri)

    try:
        resource_value = Resource.from_contents(schema, default_specification=DRAFT202012)
        registry = Registry(retrieve=reject_retrieval).with_resource(schema_id, resource_value)
        resolver = registry.resolver(schema_id)
        for _path, node in nodes:
            if isinstance(node, bool):
                continue
            for keyword in ("$ref", "$dynamicRef"):
                if keyword in node:
                    resolver.lookup(node[keyword])
        return validator_class(schema, registry=registry)
    except ValidationError:
        raise
    except Exception as exc:
        raise ValidationError("fixture schema reference registry could not be constructed") from exc


def apply_schema(schema: dict[str, Any], manifest: dict[str, Any]) -> None:
    validator = validate_schema(schema)
    try:
        error = next(iter(validator.iter_errors(manifest)), None)
    except Exception as exc:
        raise ValidationError("manifest schema evaluation failed safely") from exc
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


def mathematical_integer(value: Any, where: str) -> int:
    if isinstance(value, bool):
        raise ValidationError(f"{where} must be a mathematical integer")
    if isinstance(value, int):
        return value
    if isinstance(value, Decimal) and _decimal_is_integer(value):
        return int(value)
    raise ValidationError(f"{where} must be a mathematical integer")


def nonnegative_integer(value: Any, where: str) -> int:
    normalized = mathematical_integer(value, where)
    if normalized < 0:
        raise ValidationError(f"{where} must be non-negative")
    return normalized


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


def check_identity(identity_value: Any, where: str) -> ArtifactIdentity:
    identity = exact_keys(identity_value, {"status", "size_bytes", "sha256"}, where)
    status = enum(identity["status"], {"pending", "verified"}, f"{where}.status")
    size_value = identity["size_bytes"]
    digest = identity["sha256"]
    if status == "pending":
        if size_value is not None or digest is not None:
            raise ValidationError(f"{where} pending identity must use null size and SHA-256")
        return ArtifactIdentity(status, None, None)
    size = nonnegative_integer(size_value, f"{where}.size_bytes")
    if size > MAX_FIXTURE_SIZE_BYTES:
        raise ValidationError(f"{where}.size_bytes exceeds the supported one-GiB fixture limit")
    if not isinstance(digest, str) or SHA256.fullmatch(digest) is None:
        raise ValidationError(f"{where}.sha256 must be a lowercase SHA-256")
    return ArtifactIdentity(status, size, digest)


def check_source(value: Any, category: str, where: str) -> None:
    source = exact_keys(
        value,
        {"family", "save_version", "product", "build", "platform", "origin", "evidence"},
        where,
    )
    enum(source["family"], {"JA2_REBORN", "STRACCIATELLA", "CLASSIC", "UNKNOWN"}, f"{where}.family")
    if source["save_version"] is not None:
        nonnegative_integer(source["save_version"], f"{where}.save_version")
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
            nonnegative_integer(assertion["value"], f"{assertion_where}.value")
        key = (pointer, operator)
        if key in seen:
            raise ValidationError(f"{assertion_where} duplicates an assertion target")
        seen.add(key)


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


def validate_document(
    manifest: dict[str, Any],
    *,
    schema: dict[str, Any],
) -> DocumentValidationResult:
    """Purely validate one decoded manifest and return its checked declarations."""
    apply_schema(schema, manifest)
    exact_keys(manifest, {"$schema", "schema_version", "fixtures"}, "manifest")
    if manifest["$schema"] != "./provenance-manifest.schema.json":
        raise ValidationError("manifest.$schema must name the repository fixture schema")
    if mathematical_integer(manifest["schema_version"], "manifest.schema_version") != 1:
        raise ValidationError("manifest.schema_version must be 1")
    fixtures = manifest["fixtures"]
    if not isinstance(fixtures, list):
        raise ValidationError("manifest.fixtures must be an array")

    ids: set[str] = set()
    identities: dict[str, ArtifactIdentity] = {}
    derivation_parents: dict[str, dict[str, str]] = {}
    repository_paths: set[str] = set()
    generator_locations: set[str] = set()
    repository_artifacts: list[RepositoryArtifact] = []
    local_artifacts: list[LocalArtifact] = []
    generators: list[GeneratorDeclaration] = []
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
        identity = check_identity(artifact["identity"], f"{where}.artifact.identity")
        identities[fixture_id] = identity

        if storage == "repository":
            path = relative_path(artifact["path"], "fixtures/public/", f"{where}.artifact.path")
            if artifact["generator"] is not None:
                raise ValidationError(f"{where}.artifact.generator must be null")
            if identity.status != "verified" or identity.size_bytes is None or identity.sha256 is None:
                raise ValidationError(f"{where} repository artifact identity must be verified")
            if redistribution_status != "approved-for-repository" or review_reference is None:
                raise ValidationError(f"{where} repository artifact lacks recorded inclusion approval")
            if ci_mode not in {"public", "excluded"}:
                raise ValidationError(f"{where} repository artifact CI mode is invalid")
            if path in repository_paths:
                raise ValidationError("manifest contains a duplicate repository artifact path")
            repository_paths.add(path)
            repository_artifacts.append(RepositoryArtifact(fixture_id, path, identity))
        elif storage == "local-only":
            path = relative_path(artifact["path"], "fixtures/private/", f"{where}.artifact.path")
            if artifact["generator"] is not None:
                raise ValidationError(f"{where}.artifact.generator must be null")
            if redistribution_status == "approved-for-repository":
                raise ValidationError(f"{where} local-only artifact cannot be repository-approved")
            if ci_mode not in {"local-only", "excluded"}:
                raise ValidationError(f"{where} local-only artifact cannot use public CI")
            local_artifacts.append(LocalArtifact(fixture_id, path, identity))
        else:
            if category != "synthetic":
                raise ValidationError(f"{where} generated storage is reserved for synthetic fixtures")
            if artifact["path"] is not None:
                raise ValidationError(f"{where}.artifact.path must be null")
            generator = exact_keys(artifact["generator"], {"kind", "location", "recipe"}, f"{where}.artifact.generator")
            enum(generator["kind"], {"python-stdout"}, f"{where}.artifact.generator.kind")
            nonempty_string(generator["recipe"], f"{where}.artifact.generator.recipe")
            if identity.status != "verified" or identity.size_bytes is None or identity.sha256 is None:
                raise ValidationError(f"{where} generated bytes must have verified identity")
            if redistribution_status != "approved-for-repository" or review_reference is None:
                raise ValidationError(f"{where} generated fixture lacks recorded inclusion approval")
            if ci_mode not in {"public", "excluded"}:
                raise ValidationError(f"{where} generated fixture CI mode is invalid")
            location = relative_path(generator["location"], "", f"{where}.artifact.generator.location")
            if Path(location).suffix != ".py":
                raise ValidationError(f"{where}.artifact.generator.location must name a Python file")
            if location in generator_locations:
                raise ValidationError("manifest contains a duplicate generator location")
            generator_locations.add(location)
            generators.append(GeneratorDeclaration(fixture_id, location, identity.size_bytes, identity.sha256))

    for fixture_id, parent_digests in derivation_parents.items():
        if fixture_id in parent_digests:
            raise ValidationError("a fixture cannot derive from itself")
        if set(parent_digests) - ids:
            raise ValidationError("a derived fixture references an unknown parent ID")
        for parent_id, recorded_digest in parent_digests.items():
            parent_identity = identities[parent_id]
            if parent_identity.status != "verified" or parent_identity.sha256 != recorded_digest:
                raise ValidationError("a derived fixture does not reference its parent's verified identity")
    check_derivation_cycles(derivation_parents)

    return DocumentValidationResult(
        fixture_count=len(fixtures),
        repository_artifacts=tuple(repository_artifacts),
        local_artifacts=tuple(local_artifacts),
        generators=tuple(generators),
    )


def _git_environment() -> dict[str, str]:
    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.upper().startswith("GIT_")
    }
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


def _safe_worktree_file(root: Path, path: str, where: str) -> Path:
    candidate = root
    for component in path.split("/"):
        candidate /= component
        if candidate.is_symlink():
            raise ValidationError(f"{where} contains a symbolic-link component")
    if not candidate.is_file():
        raise ValidationError(f"{where} ordinary file is unavailable")
    return candidate


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
        return _safe_worktree_file(root, path, "tracked worktree path").read_bytes()

    return RepositoryView(root, entries, read_worktree, historical=False)


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


def check_bytes(data: bytes, size: int, digest: str, where: str) -> None:
    if len(data) != size:
        raise ValidationError(f"{where} size does not match its admitted identity")
    if hashlib.sha256(data).hexdigest() != digest:
        raise ValidationError(f"{where} SHA-256 does not match its admitted identity")


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
            if (directory_path / name).is_symlink():
                raise ValidationError("a symbolic link is present below fixtures/public")
        for name in filenames:
            child = directory_path / name
            if child.is_symlink() or not child.is_file():
                raise ValidationError("a non-regular item is present below fixtures/public")
            path = child.relative_to(root).as_posix()
            if path not in repository_paths:
                raise ValidationError("a worktree public fixture artifact lacks manifest admission")


def validate_repository_view(
    document: DocumentValidationResult,
    repository: RepositoryView,
    *,
    check_local_files: bool,
) -> RepositoryValidationResult:
    repository_paths = {artifact.path for artifact in document.repository_artifacts}
    for artifact in document.repository_artifacts:
        assert artifact.identity.size_bytes is not None and artifact.identity.sha256 is not None
        data = repository.read_bytes(artifact.path, f"fixture {artifact.fixture_id} repository artifact")
        check_bytes(
            data,
            artifact.identity.size_bytes,
            artifact.identity.sha256,
            f"fixture {artifact.fixture_id} repository artifact",
        )
    for generator in document.generators:
        repository.require_regular(generator.location, f"fixture {generator.fixture_id} generator")

    local_verified = 0
    if check_local_files:
        if repository.historical:
            raise ValidationError("local-only fixtures cannot be checked from a historical tree")
        for artifact in document.local_artifacts:
            if (
                artifact.identity.status != "verified"
                or artifact.identity.size_bytes is None
                or artifact.identity.sha256 is None
            ):
                raise ValidationError(f"fixture {artifact.fixture_id} local check requires verified identity")
            path = _safe_worktree_file(
                repository.root,
                artifact.path,
                f"fixture {artifact.fixture_id} local artifact",
            )
            try:
                data = path.read_bytes()
            except OSError as exc:
                raise ValidationError(f"fixture {artifact.fixture_id} local artifact cannot be read safely") from exc
            check_bytes(
                data,
                artifact.identity.size_bytes,
                artifact.identity.sha256,
                f"fixture {artifact.fixture_id} local artifact",
            )
            local_verified += 1

    _check_repository_tree(repository, repository_paths)
    if not repository.historical:
        _check_worktree_public_files(repository.root, repository_paths)
    return RepositoryValidationResult(
        repository_bytes_verified=len(document.repository_artifacts),
        generator_files_verified=len(document.generators),
        local_bytes_verified=local_verified,
    )


def _repository_identity(root: Path) -> str:
    try:
        remote = _git(root, ["config", "--get", "remote.origin.url"]).decode("utf-8").strip()
    except (UnicodeDecodeError, ValidationError):
        remote = ""
    if remote and not any(ord(character) < 32 or ord(character) == 127 for character in remote):
        if re.match(r"^[a-z][a-z0-9+.-]*://[^/@]+@", remote, flags=re.IGNORECASE):
            raise ValidationError("repository origin URL contains unsupported user information")
        return remote
    root_digest = hashlib.sha256(os.fsencode(root.resolve())).hexdigest()
    return f"local-worktree-sha256:{root_digest}"


def _resolve_commit(root: Path, revision: str, *, require_exact: bool) -> str:
    if require_exact and EXACT_OID.fullmatch(revision) is None:
        raise ValidationError("an admission subject must be a full lowercase commit object ID")
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
    if EXACT_OID.fullmatch(oid) is None:
        raise ValidationError("a requested history head did not resolve to a full object ID")
    if require_exact and oid != revision:
        raise ValidationError("an admission subject did not resolve to its exact requested commit")
    return oid


def _subject_identity(root: Path, role: str, revision: str, *, require_exact: bool) -> SubjectIdentity:
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,31}", role):
        raise ValidationError("an admission subject role is invalid")
    commit_oid = _resolve_commit(root, revision, require_exact=require_exact)
    try:
        tree_oid = _git(root, ["rev-parse", f"{commit_oid}^{{tree}}"]).decode("ascii").strip()
    except UnicodeDecodeError as exc:
        raise ValidationError("a subject tree did not resolve to a valid object ID") from exc
    if EXACT_OID.fullmatch(tree_oid) is None:
        raise ValidationError("a subject tree did not resolve to a full object ID")
    return SubjectIdentity(role, _repository_identity(root), commit_oid, tree_oid)


def evaluator_identity() -> EvaluatorIdentity:
    validator_path = Path(__file__).resolve()
    digest = hashlib.sha256(validator_path.read_bytes()).hexdigest()
    try:
        commit_oid = _resolve_commit(ROOT, "HEAD", require_exact=False)
        tree_oid = _git(ROOT, ["rev-parse", f"{commit_oid}^{{tree}}"]).decode("ascii").strip()
        relative_validator = validator_path.relative_to(ROOT).as_posix()
        committed_blob = _git(ROOT, ["rev-parse", f"{commit_oid}:{relative_validator}"]).decode("ascii").strip()
        worktree_blob = _git(ROOT, ["hash-object", "--no-filters", relative_validator]).decode("ascii").strip()
        if EXACT_OID.fullmatch(tree_oid) is None or committed_blob != worktree_blob:
            commit_oid = None
            tree_oid = None
    except (OSError, UnicodeDecodeError, ValidationError):
        commit_oid = None
        tree_oid = None
    return EvaluatorIdentity(_repository_identity(ROOT), commit_oid, tree_oid, digest)


def _verify_complete_history(root: Path, heads: Sequence[str]) -> list[str]:
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


def validate_static_tree(
    subject: SubjectIdentity,
    *,
    root: Path = ROOT,
    evaluator: EvaluatorIdentity | None = None,
) -> StaticTreeAdmissionResult:
    evaluator_value = evaluator or evaluator_identity()
    resolved_subject = _subject_identity(root, subject.role, subject.commit_oid, require_exact=True)
    if resolved_subject != subject:
        raise ValidationError("static tree subject identity does not match its repository commit and tree")
    repository = _tree_view(root, subject.commit_oid)
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
        return StaticTreeAdmissionResult(evaluator_value, subject, None, None)
    if schema is None:
        raise ValidationError("a historical fixture manifest has no schema in its commit tree")
    manifest = load_json_bytes(
        repository.read_bytes(MANIFEST_PATH, "historical fixture manifest"),
        "historical fixture manifest",
    )
    document = validate_document(manifest, schema=schema)
    repository_checks = validate_repository_view(document, repository, check_local_files=False)
    return StaticTreeAdmissionResult(evaluator_value, subject, document, repository_checks)


def validate_static_history(
    subjects: Sequence[SubjectIdentity],
    *,
    root: Path = ROOT,
    evaluator: EvaluatorIdentity | None = None,
) -> StaticHistoryAdmissionResult:
    if not subjects:
        raise ValidationError("at least one explicit history subject is required")
    resolved_subjects = tuple(
        _subject_identity(root, subject.role, subject.commit_oid, require_exact=True)
        for subject in subjects
    )
    if resolved_subjects != tuple(subjects):
        raise ValidationError("history subject identity does not match its repository commit and tree")
    commits = _verify_complete_history(root, [subject.commit_oid for subject in resolved_subjects])
    repository_name = _repository_identity(root)
    evaluator_value = evaluator or evaluator_identity()
    tree_results: list[StaticTreeAdmissionResult] = []
    for commit_oid in commits:
        subject = _subject_identity(root, "historical-tree", commit_oid, require_exact=True)
        if subject.repository != repository_name:
            raise ValidationError("historical subject repository identity changed during validation")
        tree_results.append(validate_static_tree(subject, root=root, evaluator=evaluator_value))
    return StaticHistoryAdmissionResult(
        evaluator=evaluator_value,
        requested_subjects=resolved_subjects,
        trees=tuple(tree_results),
    )


def validate_history(requested_heads: list[str], root: Path = ROOT) -> StaticHistoryAdmissionResult:
    """Compatibility entrypoint for static history only; it never runs generators."""
    subjects = tuple(
        _subject_identity(root, f"history-head-{index}", revision, require_exact=False)
        for index, revision in enumerate(requested_heads, start=1)
    )
    return validate_static_history(subjects, root=root)


def _snapshot_digest(repository: RepositoryView) -> str:
    digest = hashlib.sha256()
    for path in sorted(repository.entries):
        entry = repository.entries[path]
        if entry.object_type != "blob" or entry.mode not in REGULAR_MODES:
            raise ValidationError("an executable snapshot contains an unsupported non-regular entry")
        data = repository.read_bytes(path, "executable snapshot entry")
        digest.update(entry.mode.encode("ascii") + b"\0")
        digest.update(path.encode("utf-8") + b"\0")
        digest.update(len(data).to_bytes(8, "big") + data)
    return digest.hexdigest()


def _materialize_snapshot(repository: RepositoryView, destination: Path) -> str:
    snapshot_digest = hashlib.sha256()
    for path in sorted(repository.entries):
        entry = repository.entries[path]
        if entry.object_type != "blob" or entry.mode not in REGULAR_MODES:
            raise ValidationError("an executable snapshot contains an unsupported non-regular entry")
        data = repository.read_bytes(path, "executable snapshot entry")
        snapshot_digest.update(entry.mode.encode("ascii") + b"\0")
        snapshot_digest.update(path.encode("utf-8") + b"\0")
        snapshot_digest.update(len(data).to_bytes(8, "big") + data)
        target = destination.joinpath(*path.split("/"))
        try:
            target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(data)
            target.chmod(0o755 if entry.mode == "100755" else 0o644)
        except OSError as exc:
            raise ValidationError("an exact executable snapshot could not be materialized") from exc
    return snapshot_digest.hexdigest()


def _seccomp_network_filter() -> bytes:
    # struct sock_filter { unsigned short code; unsigned char jt, jf; unsigned int k; }
    instructions: list[tuple[int, int, int, int]] = [
        (0x20, 0, 0, 4),                 # load seccomp_data.arch
        (0x15, 1, 0, 0xC000003E),        # require AUDIT_ARCH_X86_64
        (0x06, 0, 0, 0x80000000),        # SECCOMP_RET_KILL_PROCESS
        (0x20, 0, 0, 0),                 # load seccomp_data.nr
        (0x45, 0, 1, 0x40000000),        # reject the x32 ABI syscall bit
        (0x06, 0, 0, 0x00050000 | errno.EPERM),
    ]
    for syscall_number in sorted(NETWORK_SYSCALLS_X86_64):
        instructions.extend(
            [
                (0x15, 0, 1, syscall_number),
                (0x06, 0, 0, 0x00050000 | errno.EPERM),  # SECCOMP_RET_ERRNO
            ]
        )
    instructions.append((0x06, 0, 0, 0x7FFF0000))  # SECCOMP_RET_ALLOW
    return b"".join(struct.pack("=HBBI", *instruction) for instruction in instructions)


def _generator_limits(maximum_output_size: int) -> Callable[[], None]:
    def apply_limits() -> None:
        resource.setrlimit(resource.RLIMIT_CPU, (30, 30))
        resource.setrlimit(resource.RLIMIT_FSIZE, (maximum_output_size, maximum_output_size))
        resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))
        address_space = 512 * 1024 * 1024
        resource.setrlimit(resource.RLIMIT_AS, (address_space, address_space))

    return apply_limits


def _sandbox_command(snapshot: Path, generator_path: str, seccomp_fd: int) -> list[str]:
    if platform.system() != "Linux" or platform.machine().lower() not in {"x86_64", "amd64"}:
        raise ValidationError("generator execution requires the supported Linux x86-64 sandbox")
    bwrap = Path("/usr/bin/bwrap")
    python = Path("/usr/bin/python3")
    if not bwrap.is_file() or not python.is_file():
        raise ValidationError("generator execution requires bubblewrap and system Python")
    try:
        runtime = subprocess.run(
            [
                str(python),
                "-E",
                "-s",
                "-B",
                "-c",
                "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')",
            ],
            check=True,
            capture_output=True,
            env={"LANG": "C.UTF-8", "PATH": "/usr/bin:/bin"},
        ).stdout.decode("ascii").strip()
    except (OSError, subprocess.SubprocessError, UnicodeDecodeError) as exc:
        raise ValidationError("generator sandbox Python identity cannot be verified") from exc
    if runtime != "3.12":
        raise ValidationError("generator execution requires system CPython 3.12")
    command = [
        str(bwrap),
        "--unshare-all",
        "--share-net",
        "--die-with-parent",
        "--new-session",
    ]
    for system_path in ("/usr", "/lib", "/lib64"):
        if Path(system_path).exists():
            command.extend(("--ro-bind", system_path, system_path))
    command.extend(
        (
            "--ro-bind", str(snapshot), "/workspace",
            "--tmpfs", "/tmp",
            "--proc", "/proc",
            "--dev", "/dev",
            "--chdir", "/workspace",
            "--clearenv",
            "--setenv", "HOME", "/tmp",
            "--setenv", "LANG", "C.UTF-8",
            "--setenv", "PATH", "/usr/bin:/bin",
            "--setenv", "PYTHONDONTWRITEBYTECODE", "1",
            "--seccomp", str(seccomp_fd),
            str(python), "-E", "-s", "-B", generator_path,
        )
    )
    return command


def _run_generator(snapshot: Path, declaration: GeneratorDeclaration) -> GeneratorMeasurement:
    maximum_output = declaration.size_bytes + 1
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as seccomp_program:
        seccomp_program.write(_seccomp_network_filter())
        seccomp_program.flush()
        seccomp_program.seek(0)
        try:
            result = subprocess.run(
                _sandbox_command(snapshot, declaration.location, seccomp_program.fileno()),
                cwd=snapshot,
                check=False,
                stdin=subprocess.DEVNULL,
                stdout=output,
                stderr=subprocess.DEVNULL,
                timeout=30,
                env={"LANG": "C.UTF-8", "PATH": "/usr/bin:/bin"},
                pass_fds=(seccomp_program.fileno(),),
                preexec_fn=_generator_limits(maximum_output),
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise ValidationError(
                f"fixture {declaration.fixture_id} generator could not execute in the exact snapshot sandbox"
            ) from exc
        if result.returncode != 0:
            raise ValidationError(f"fixture {declaration.fixture_id} generator failed in its exact snapshot")
        output.seek(0)
        data = output.read(maximum_output + 1)
    check_bytes(
        data,
        declaration.size_bytes,
        declaration.sha256,
        f"fixture {declaration.fixture_id} generator output",
    )
    return GeneratorMeasurement(
        declaration.fixture_id,
        declaration.location,
        len(data),
        hashlib.sha256(data).hexdigest(),
    )


def _measure_generators(
    repository: RepositoryView,
    declarations: Sequence[GeneratorDeclaration],
) -> tuple[str, tuple[GeneratorMeasurement, ...]]:
    with tempfile.TemporaryDirectory(prefix="fixture-admission-") as directory:
        snapshot = Path(directory)
        digest = _materialize_snapshot(repository, snapshot)
        measurements = tuple(_run_generator(snapshot, declaration) for declaration in declarations)
    return digest, measurements


def validate(
    manifest: dict[str, Any],
    check_local_files: bool,
    *,
    schema: dict[str, Any] | None = None,
    repository: RepositoryView | None = None,
) -> WorktreeValidationResult:
    """Validate a worktree for local/imported use without making an admission claim.

    This name is retained for the PR #19 importer. Immutable admission callers
    must use ``admit_explicit_heads`` instead.
    """
    if schema is None:
        schema = load_json(SCHEMA)
    if repository is None:
        repository = worktree_view(ROOT)
    if repository.historical:
        raise ValidationError("validate() is a worktree-only compatibility contract")
    document = validate_document(manifest, schema=schema)
    repository_checks = validate_repository_view(
        document,
        repository,
        check_local_files=check_local_files,
    )
    if document.generators:
        snapshot_digest, measurements = _measure_generators(repository, document.generators)
    else:
        snapshot_digest = _snapshot_digest(repository)
        measurements = ()
    return WorktreeValidationResult(
        repository=_repository_identity(repository.root),
        snapshot_sha256=snapshot_digest,
        document=document,
        repository_checks=repository_checks,
        generator_measurements=measurements,
    )


def _load_and_validate_subject(
    subject: SubjectIdentity,
    *,
    root: Path,
    evaluator: EvaluatorIdentity,
) -> HeadExecutionResult:
    static = validate_static_tree(subject, root=root, evaluator=evaluator)
    if static.document is None:
        return HeadExecutionResult(evaluator, subject, None, None, None, ())
    repository = _tree_view(root, subject.commit_oid)
    snapshot_digest, measurements = _measure_generators(repository, static.document.generators)
    return HeadExecutionResult(
        evaluator,
        subject,
        static.document,
        static.repository_checks,
        snapshot_digest,
        measurements,
    )


def _verify_pr_integration_binding(
    root: Path,
    subjects: Sequence[SubjectIdentity],
    base_oid: str | None,
) -> SubjectIdentity | None:
    by_role = {subject.role: subject for subject in subjects}
    if len(by_role) != len(subjects):
        raise ValidationError("admission subject roles must be unique")
    pr_roles = {"pr-head", "integration-head"}
    if pr_roles & set(by_role):
        if set(by_role) != pr_roles or base_oid is None:
            raise ValidationError("pull-request admission requires exact pr-head, integration-head, and base")
        base = _subject_identity(root, "base", base_oid, require_exact=True)
        line = _git(root, ["rev-list", "--parents", "-n", "1", by_role["integration-head"].commit_oid])
        try:
            fields = line.decode("ascii").split()
        except UnicodeDecodeError as exc:
            raise ValidationError("integration commit parent identity is invalid") from exc
        expected = [
            by_role["integration-head"].commit_oid,
            base.commit_oid,
            by_role["pr-head"].commit_oid,
        ]
        if fields != expected:
            raise ValidationError("integration head is not the exact two-parent merge of base and PR head")
        return base
    if base_oid is not None:
        raise ValidationError("base identity is only valid for pull-request admission")
    if len(subjects) != 1 or subjects[0].role not in {"push-head", "local-head"}:
        raise ValidationError("non-PR admission requires exactly one push-head or local-head")
    return None


def _require_clean_worktree(root: Path) -> None:
    status = _git(root, ["status", "--porcelain=v2", "-z", "--untracked-files=all"])
    if status:
        raise ValidationError("admission evaluator worktree must be clean, including untracked files")


def admit_explicit_heads(
    requests: Sequence[AdmissionRequest],
    *,
    root: Path = ROOT,
    base_oid: str | None = None,
    require_clean_worktree: bool = False,
) -> FixtureAdmissionResult:
    """Perform full static history and independent exact-head generator admission."""
    if not requests:
        raise ValidationError("at least one explicit admission subject is required")
    if require_clean_worktree:
        _require_clean_worktree(root)
    evaluator = evaluator_identity()
    if (
        require_clean_worktree
        and root.resolve() == ROOT.resolve()
        and (evaluator.commit_oid is None or evaluator.tree_oid is None)
    ):
        raise ValidationError("admission evaluator is not the validator from the clean HEAD tree")
    subjects = tuple(
        _subject_identity(root, request.role, request.commit_oid, require_exact=True)
        for request in requests
    )
    base = _verify_pr_integration_binding(root, subjects, base_oid)
    history = validate_static_history(subjects, root=root, evaluator=evaluator)
    heads = tuple(
        _load_and_validate_subject(subject, root=root, evaluator=evaluator)
        for subject in subjects
    )
    return FixtureAdmissionResult(evaluator, base, history, heads)


def _parse_subject(value: str) -> AdmissionRequest:
    role, separator, commit_oid = value.partition("=")
    if not separator:
        raise argparse.ArgumentTypeError("subject must use ROLE=FULL_COMMIT_OID")
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,31}", role):
        raise argparse.ArgumentTypeError("subject role is invalid")
    if EXACT_OID.fullmatch(commit_oid) is None:
        raise argparse.ArgumentTypeError("subject must name a full lowercase commit object ID")
    return AdmissionRequest(role, commit_oid)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repository-root",
        type=Path,
        default=ROOT,
        help="complete Git repository whose immutable subjects are inspected",
    )
    parser.add_argument(
        "--subject",
        action="append",
        required=True,
        type=_parse_subject,
        help="ROLE=FULL_COMMIT_OID; use pr-head plus integration-head, or one push/local head",
    )
    parser.add_argument(
        "--base",
        help="exact base commit required for PR integration-parent binding",
    )
    parser.add_argument(
        "--check-local-files",
        action="store_true",
        help="after immutable admission, opt in to worktree validation of every local-only artifact",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    root = args.repository_root.resolve()
    try:
        result = admit_explicit_heads(
            args.subject,
            root=root,
            base_oid=args.base,
            require_clean_worktree=True,
        )
        if args.check_local_files:
            local_result = validate(
                load_json(root / MANIFEST_PATH),
                check_local_files=True,
                schema=load_json(root / SCHEMA_PATH),
                repository=worktree_view(root),
            )
        else:
            local_result = None
    except ValidationError as exc:
        print(f"fixture admission failed: {exc}", file=sys.stderr)
        return 1
    subjects = ", ".join(
        f"{head.subject.role}={head.subject.commit_oid}/tree={head.subject.tree_oid}"
        f"/snapshot={head.snapshot_sha256 or 'not-required'}"
        for head in result.heads
    )
    generator_count = sum(len(head.generator_measurements) for head in result.heads)
    base = "" if result.base is None else f"; base={result.base.commit_oid}/tree={result.base.tree_oid}"
    local = (
        ""
        if local_result is None
        else f"; {local_result.repository_checks.local_bytes_verified} local-only artifacts verified"
    )
    evaluator_subject = (
        "uncommitted"
        if result.evaluator.commit_oid is None or result.evaluator.tree_oid is None
        else f"{result.evaluator.commit_oid}/tree={result.evaluator.tree_oid}"
    )
    print(
        "fixture admission valid: "
        f"repository={result.heads[0].subject.repository}; {subjects}{base}; "
        f"evaluator={result.evaluator.repository}@{evaluator_subject}; "
        f"evaluator-validator-sha256={result.evaluator.validator_sha256}; "
        f"{result.history.commit_count} reachable commit trees statically inspected; "
        f"{generator_count} exact-head generator outputs independently measured{local}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

from __future__ import annotations

import copy
import errno
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path
from unittest import mock

from tools import setup_bwrap_sandbox as sandbox_setup
from tools import validate_fixture_manifest as policy


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = REPOSITORY_ROOT / "tools" / "validate_fixture_manifest.py"
SCHEMA_TEMPLATE = policy.load_json(policy.SCHEMA)


def run_git(root: Path, *arguments: str, check: bool = True) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", *arguments],
        cwd=root,
        check=check,
        capture_output=True,
        env={**os.environ, "GIT_AUTHOR_DATE": "2024-01-01T00:00:00Z", "GIT_COMMITTER_DATE": "2024-01-01T00:00:00Z"},
    )


class SyntheticRepository:
    def __init__(self) -> None:
        self._temporary = tempfile.TemporaryDirectory()
        self.root = Path(self._temporary.name)
        run_git(self.root, "init", "-b", "main")
        run_git(self.root, "config", "user.name", "Fixture Tests")
        run_git(self.root, "config", "user.email", "fixtures@example.invalid")
        self.write_json(policy.SCHEMA_PATH, SCHEMA_TEMPLATE)
        self.write_json(policy.MANIFEST_PATH, empty_manifest())
        self.commit("initial policy")

    def close(self) -> None:
        self._temporary.cleanup()

    def write_bytes(self, path: str, data: bytes) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)

    def write_text(self, path: str, text: str) -> None:
        self.write_bytes(path, text.encode("utf-8"))

    def write_json(self, path: str, value: object) -> None:
        self.write_text(path, json.dumps(value, indent=2) + "\n")

    def remove(self, path: str) -> None:
        target = self.root / path
        if target.is_dir() and not target.is_symlink():
            shutil.rmtree(target)
        else:
            target.unlink()

    def commit(self, message: str) -> str:
        run_git(self.root, "add", "-A")
        run_git(self.root, "commit", "-m", message)
        return self.head()

    def head(self) -> str:
        return run_git(self.root, "rev-parse", "HEAD").stdout.decode().strip()


def empty_manifest() -> dict[str, object]:
    return {
        "$schema": "./provenance-manifest.schema.json",
        "schema_version": 1,
        "fixtures": [],
    }


def common_fixture(fixture_id: str, category: str, origin: str) -> dict[str, object]:
    return {
        "id": fixture_id,
        "category": category,
        "description": "Clearly synthetic regression-test metadata.",
        "source": {
            "family": "UNKNOWN",
            "save_version": None,
            "product": "Synthetic test product",
            "build": None,
            "platform": None,
            "origin": origin,
            "evidence": ["Synthetic unit-test construction."],
        },
        "artifact": {},
        "derivation": None,
        "redistribution": {
            "status": "not-permitted",
            "basis": "Synthetic regression-test policy basis.",
            "review_reference": None,
        },
        "expected": {
            "outcome": "success",
            "completeness": "partial",
            "basis": "Synthetic regression-test expectation.",
            "assertions": [{"json_pointer": "/format/family", "operator": "equals", "value": "UNKNOWN"}],
        },
        "ci": {"mode": "local-only", "reason": "Synthetic regression-test handling."},
        "input_policy": "read-only",
    }


def repository_fixture(path: str, data: bytes, *, approved: bool = True, digest: str | None = None) -> dict[str, object]:
    fixture = common_fixture("synthetic-repository-artifact", "synthetic", "project-generated")
    fixture["artifact"] = {
        "storage": "repository",
        "path": path,
        "generator": None,
        "identity": {
            "status": "verified",
            "size_bytes": len(data),
            "sha256": digest or hashlib.sha256(data).hexdigest(),
        },
    }
    fixture["redistribution"] = {
        "status": "approved-for-repository" if approved else "pending-review",
        "basis": "Synthetic bytes authored only for a regression test.",
        "review_reference": "synthetic-test-review" if approved else None,
    }
    fixture["ci"] = {"mode": "public", "reason": "Synthetic public test bytes."}
    return fixture


def local_fixture(fixture_id: str, *, pending: bool = False, category: str = "real-local") -> dict[str, object]:
    origin = "locally-supplied" if category == "real-local" else "derived"
    fixture = common_fixture(fixture_id, category, origin)
    fixture["artifact"] = {
        "storage": "local-only",
        "path": f"fixtures/private/{fixture_id}.bin",
        "generator": None,
        "identity": {
            "status": "pending" if pending else "verified",
            "size_bytes": None if pending else 4,
            "sha256": None if pending else hashlib.sha256(b"test").hexdigest(),
        },
    }
    return fixture


def generated_fixture(data: bytes) -> dict[str, object]:
    fixture = common_fixture("synthetic-generated-artifact", "synthetic", "project-generated")
    fixture["artifact"] = {
        "storage": "generated",
        "path": None,
        "generator": {
            "kind": "python-stdout",
            "location": "tools/generate_synthetic.py",
            "recipe": "Write a fixed project-authored ASCII test token.",
        },
        "identity": {
            "status": "verified",
            "size_bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        },
    }
    fixture["redistribution"] = {
        "status": "approved-for-repository",
        "basis": "Synthetic bytes authored only for a regression test.",
        "review_reference": "synthetic-test-review",
    }
    fixture["ci"] = {"mode": "public", "reason": "Synthetic public test bytes."}
    return fixture


GOOD_GENERATOR = "import sys\nsys.stdout.buffer.write(b'synthetic')\n"
BAD_GENERATOR = "import sys\nsys.stdout.buffer.write(b'wrong')\n"


def create_pull_request_case(
    pr_generator: str,
    integration_generator: str,
) -> tuple[SyntheticRepository, str, str, str]:
    repository = SyntheticRepository()
    repository.write_text("base-marker.txt", "base\n")
    base = repository.commit("base subject")
    run_git(repository.root, "checkout", "-b", "candidate")
    repository.write_text("tools/generate_synthetic.py", pr_generator)
    manifest = empty_manifest()
    manifest["fixtures"] = [generated_fixture(b"synthetic")]
    repository.write_json(policy.MANIFEST_PATH, manifest)
    pr_head = repository.commit("candidate generator")
    run_git(repository.root, "checkout", "main")
    run_git(repository.root, "merge", "--no-ff", "--no-commit", "candidate")
    repository.write_text("tools/generate_synthetic.py", integration_generator)
    integration_head = repository.commit("integration subject")
    return repository, base, pr_head, integration_head


def admit_pull_request(
    repository: SyntheticRepository,
    base: str,
    pr_head: str,
    integration_head: str,
) -> policy.FixtureAdmissionResult:
    return policy.admit_explicit_heads(
        [
            policy.AdmissionRequest("pr-head", pr_head),
            policy.AdmissionRequest("integration-head", integration_head),
        ],
        root=repository.root,
        base_oid=base,
    )


def validate_in(
    repository: SyntheticRepository,
    manifest: dict[str, object],
    *,
    local: bool = False,
) -> policy.WorktreeValidationResult:
    repository.write_json(policy.MANIFEST_PATH, manifest)
    run_git(repository.root, "add", policy.MANIFEST_PATH)
    return policy.validate(
        manifest,
        local,
        schema=copy.deepcopy(SCHEMA_TEMPLATE),
        repository=policy.worktree_view(repository.root),
    )


class StrictJsonTests(unittest.TestCase):
    def assert_rejected(self, document: bytes) -> None:
        for source in ("schema", "manifest"):
            with self.subTest(source=source), self.assertRaises(policy.ValidationError):
                policy.load_json_bytes(document, source)

    def test_duplicate_keys_at_root_and_nested_levels(self) -> None:
        self.assert_rejected(b'{"member": 1, "member": 1}')
        self.assert_rejected(b'{"outer": {"member": 1, "member": 1}}')

    def test_escaped_equivalent_duplicate_keys(self) -> None:
        self.assert_rejected(b'{"member": 1, "m\\u0065mber": 2}')
        self.assert_rejected(b'{"outer": {"member": 1, "m\\u0065mber": 2}}')

    def test_non_json_numeric_constants(self) -> None:
        for constant in (b"NaN", b"Infinity", b"-Infinity"):
            self.assert_rejected(b'{"number":' + constant + b"}")

    def test_large_small_and_high_precision_numbers_are_preserved_exactly(self) -> None:
        document = policy.load_json_bytes(
            b'{"overflow":1e400,"underflow":1e-4000,"precision":9007199254740993.0}',
            "numeric probe",
        )
        self.assertEqual(Decimal("1e400"), document["overflow"])
        self.assertEqual(Decimal("1e-4000"), document["underflow"])
        self.assertEqual(Decimal("9007199254740993.0"), document["precision"])

        manifest = empty_manifest()
        fixture = local_fixture("exact-numbers", pending=True)
        fixture["expected"]["assertions"] = [
            {"json_pointer": "/overflow", "operator": "equals", "value": "OVERFLOW"},
            {"json_pointer": "/underflow", "operator": "equals", "value": "UNDERFLOW"},
            {"json_pointer": "/precision", "operator": "equals", "value": "PRECISION"},
        ]
        manifest["fixtures"] = [fixture]
        raw = json.dumps(manifest)
        raw = raw.replace('"OVERFLOW"', "1e400")
        raw = raw.replace('"UNDERFLOW"', "1e-4000")
        raw = raw.replace('"PRECISION"', "9007199254740993.0")
        decoded = policy.load_json_bytes(raw.encode(), "manifest")
        policy.validate_document(decoded, schema=copy.deepcopy(SCHEMA_TEMPLATE))
        values = [item["value"] for item in decoded["fixtures"][0]["expected"]["assertions"]]
        self.assertEqual(
            [Decimal("1e400"), Decimal("1e-4000"), Decimal("9007199254740993.0")],
            values,
        )

    def test_unsupported_numeric_resource_domain_is_rejected(self) -> None:
        self.assert_rejected(b'{"number":1e100001}')
        self.assert_rejected(b'{"number":' + b"1" * 257 + b"}")

    def test_rounded_schema_constant_cannot_be_admitted(self) -> None:
        raw = json.dumps(empty_manifest()).replace(
            '"schema_version": 1',
            '"schema_version": 1.00000000000000001',
        ).encode()
        manifest = policy.load_json_bytes(raw, "manifest")
        self.assertEqual(Decimal("1.00000000000000001"), manifest["schema_version"])
        with self.assertRaisesRegex(policy.ValidationError, r"\(const\)"):
            policy.validate_document(manifest, schema=copy.deepcopy(SCHEMA_TEMPLATE))

    def test_mathematical_integer_spellings_agree_and_bool_does_not(self) -> None:
        manifest = empty_manifest()
        fixture = local_fixture("integer-spellings")
        fixture["source"]["save_version"] = 103
        fixture["expected"]["assertions"] = [
            {"json_pointer": "/roster", "operator": "length-equals", "value": 18}
        ]
        manifest["fixtures"] = [fixture]
        raw = json.dumps(manifest)
        raw = raw.replace('"save_version": 103', '"save_version": 103.0')
        raw = raw.replace('"size_bytes": 4', '"size_bytes": 4.0')
        raw = raw.replace('"value": 18', '"value": 18.0')
        decoded = policy.load_json_bytes(raw.encode(), "manifest")
        result = policy.validate_document(decoded, schema=copy.deepcopy(SCHEMA_TEMPLATE))
        self.assertEqual(4, result.local_artifacts[0].identity.size_bytes)

        fixture["source"]["save_version"] = True
        with self.assertRaises(policy.ValidationError):
            policy.validate_document(manifest, schema=copy.deepcopy(SCHEMA_TEMPLATE))
        with self.assertRaises(policy.ValidationError):
            policy.mathematical_integer(True, "probe")


class SchemaTests(unittest.TestCase):
    def test_invalid_nested_schema_type_fails_metaschema(self) -> None:
        schema = copy.deepcopy(SCHEMA_TEMPLATE)
        schema["$defs"]["source"]["properties"]["save_version"]["type"] = 7
        with self.assertRaises(policy.ValidationError):
            policy.validate_schema(schema)

    def test_dangling_exercised_and_unused_refs_fail(self) -> None:
        exercised = copy.deepcopy(SCHEMA_TEMPLATE)
        exercised["$defs"]["fixture"]["properties"]["source"]["$ref"] = "#/$defs/missing"
        unused = copy.deepcopy(SCHEMA_TEMPLATE)
        unused["$defs"]["never-used"] = {"$ref": "#/$defs/missing"}
        for schema in (exercised, unused):
            with self.subTest(), self.assertRaises(policy.ValidationError):
                policy.validate_schema(schema)

    def test_used_and_unused_non_schema_reference_targets_fail_cleanly(self) -> None:
        unused = copy.deepcopy(SCHEMA_TEMPLATE)
        unused["$defs"]["never-used"] = {"$ref": "#/title"}
        used = copy.deepcopy(SCHEMA_TEMPLATE)
        used["$defs"]["fixture"]["properties"]["source"] = {
            "$ref": "#/$defs/fixture/required"
        }
        for schema in (unused, used):
            with self.subTest(), self.assertRaisesRegex(
                policy.ValidationError,
                "does not target a supported subschema",
            ):
                policy.validate_schema(schema)

    def test_local_refs_pass_and_literal_annotation_refs_are_not_operative(self) -> None:
        schema = copy.deepcopy(SCHEMA_TEMPLATE)
        schema["$defs"]["annotation-holder"] = {
            "type": "object",
            "examples": [
                {"$ref": "https://example.invalid/literal-instance-data"},
                {"nested": {"$ref": "#/$defs/not-present"}},
            ],
        }
        policy.validate_schema(schema)
        policy.apply_schema(schema, empty_manifest())

    def test_external_ref_is_rejected_without_retrieval(self) -> None:
        schema = copy.deepcopy(SCHEMA_TEMPLATE)
        schema["$defs"]["never-used"] = {"$ref": "https://example.invalid/schema"}
        with self.assertRaises(policy.ValidationError):
            policy.validate_schema(schema)

    def test_imported_validate_applies_schema_before_semantics(self) -> None:
        manifest = empty_manifest()
        manifest["fixtures"] = "not-an-array"
        with self.assertRaises(policy.ValidationError):
            policy.validate(manifest, False, schema=copy.deepcopy(SCHEMA_TEMPLATE))

    def test_path_patterns_accept_valid_paths(self) -> None:
        manifest = empty_manifest()
        manifest["fixtures"] = [
            repository_fixture("fixtures/public/nested/vector.bin", b"synthetic"),
            local_fixture("valid-local-path"),
            generated_fixture(b"synthetic"),
        ]
        policy.apply_schema(copy.deepcopy(SCHEMA_TEMPLATE), manifest)

    def test_path_patterns_reject_escaped_control_characters(self) -> None:
        controls = {f"u+{code:04x}": chr(code) for code in (*range(32), 127)}
        for surface in ("repository", "local-only", "generator"):
            for name, character in controls.items():
                with self.subTest(surface=surface, control=name):
                    if surface == "repository":
                        fixture = repository_fixture(
                            f"fixtures/public/vector.bin{character}",
                            b"synthetic",
                        )
                    elif surface == "local-only":
                        fixture = local_fixture("unsafe-local-path")
                        fixture["artifact"]["path"] = (
                            f"fixtures/private/vector.bin{character}"
                        )
                    else:
                        fixture = generated_fixture(b"synthetic")
                        fixture["artifact"]["generator"]["location"] = (
                            f"tools/unsafe{character}/generate.py"
                        )
                    manifest = empty_manifest()
                    manifest["fixtures"] = [fixture]
                    document = json.dumps(manifest).replace(
                        json.dumps(character)[1:-1],
                        f"\\u{ord(character):04x}",
                    ).encode()
                    self.assertIn(f"\\u{ord(character):04x}".encode(), document)
                    decoded = policy.load_json_bytes(document, "manifest")
                    with mock.patch.object(policy, "check_source") as semantic_check:
                        with self.assertRaisesRegex(
                            policy.ValidationError,
                            r"\((?:pattern|oneOf)\)",
                        ):
                            policy.validate_document(
                                decoded,
                                schema=copy.deepcopy(SCHEMA_TEMPLATE),
                            )
                        semantic_check.assert_not_called()

    def test_shared_id_digest_and_path_declarations_have_semantic_parity(self) -> None:
        valid_digest = hashlib.sha256(b"synthetic").hexdigest()
        valid_values = (
            ("fixture-id", "valid.fixture-01", policy.FIXTURE_ID),
            ("sha256", valid_digest, policy.SHA256),
        )
        for definition, value, semantic_pattern in valid_values:
            with self.subTest(definition=definition):
                probe_schema = copy.deepcopy(SCHEMA_TEMPLATE)
                probe_schema["properties"] = {"value": {"$ref": f"#/$defs/{definition}"}}
                probe_schema["required"] = ["value"]
                policy.apply_schema(probe_schema, {"value": value})
                self.assertIsNotNone(semantic_pattern.fullmatch(value))

        for code in (*range(32), 127):
            character = chr(code)
            for surface in ("id", "digest"):
                with self.subTest(surface=surface, code=code):
                    manifest = empty_manifest()
                    fixture = local_fixture("parity-id")
                    if surface == "id":
                        fixture["id"] = f"valid-id{character}"
                    else:
                        fixture["artifact"]["identity"]["sha256"] = valid_digest + character
                    manifest["fixtures"] = [fixture]
                    with mock.patch.object(policy, "check_source") as semantic_check:
                        with self.assertRaises(policy.ValidationError):
                            policy.validate_document(manifest, schema=copy.deepcopy(SCHEMA_TEMPLATE))
                        semantic_check.assert_not_called()
                    pattern = policy.FIXTURE_ID if surface == "id" else policy.SHA256
                    candidate = fixture["id"] if surface == "id" else fixture["artifact"]["identity"]["sha256"]
                    self.assertIsNone(pattern.fullmatch(candidate))

        for value in (
            "fixtures/public/nested/vector.bin",
            "fixtures/private/nested/vector.bin",
            "tools/generate.py",
        ):
            with self.subTest(path=value):
                self.assertEqual(value, policy.relative_path(value, "", "path"))

    def test_shared_integer_schema_and_semantics_have_positive_and_negative_parity(self) -> None:
        schema = copy.deepcopy(SCHEMA_TEMPLATE)
        schema["properties"] = {"value": {"$ref": "#/$defs/nonnegative-integer"}}
        schema["required"] = ["value"]
        for value in (0, 103, Decimal("103.0"), Decimal("1e4")):
            with self.subTest(value=value):
                policy.apply_schema(schema, {"value": value})
                self.assertGreaterEqual(policy.nonnegative_integer(value, "value"), 0)
        for value in (True, False, -1, Decimal("1.5")):
            with self.subTest(value=value):
                with self.assertRaises(policy.ValidationError):
                    policy.apply_schema(schema, {"value": value})
                with self.assertRaises(policy.ValidationError):
                    policy.nonnegative_integer(value, "value")

        manifest = empty_manifest()
        fixture = local_fixture("oversize-identity")
        fixture["artifact"]["identity"]["size_bytes"] = policy.MAX_FIXTURE_SIZE_BYTES + 1
        manifest["fixtures"] = [fixture]
        with mock.patch.object(policy, "check_source") as semantic_check:
            with self.assertRaisesRegex(policy.ValidationError, r"\((?:maximum|oneOf)\)"):
                policy.validate_document(manifest, schema=copy.deepcopy(SCHEMA_TEMPLATE))
            semantic_check.assert_not_called()
        with self.assertRaisesRegex(policy.ValidationError, "one-GiB"):
            policy.check_identity(fixture["artifact"]["identity"], "identity")

    def test_missing_dependency_fails_with_setup_direction(self) -> None:
        with mock.patch.object(policy, "DEPENDENCY_ERROR", ModuleNotFoundError("jsonschema")):
            with self.assertRaisesRegex(policy.ValidationError, "requirements/fixture-validation.lock"):
                policy.validate_schema(copy.deepcopy(SCHEMA_TEMPLATE))


class SemanticGuardrailTests(unittest.TestCase):
    def setUp(self) -> None:
        self.repository = SyntheticRepository()

    def tearDown(self) -> None:
        self.repository.close()

    def test_current_manifest_and_absent_pending_private_fixture_are_valid(self) -> None:
        manifest = empty_manifest()
        manifest["fixtures"] = [local_fixture("pending-private", pending=True)]
        result = validate_in(self.repository, manifest)
        self.assertEqual(1, result.document.fixture_count)
        self.assertEqual(1, len(result.document.local_artifacts))
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest, local=True)

    def test_opt_in_local_missing_and_mismatched_identity_are_hard_failures(self) -> None:
        manifest = empty_manifest()
        fixture = local_fixture("verified-private")
        manifest["fixtures"] = [fixture]
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest, local=True)
        self.repository.write_bytes(fixture["artifact"]["path"], b"nope")
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest, local=True)
        self.repository.write_bytes(fixture["artifact"]["path"], b"test")
        result = validate_in(self.repository, manifest, local=True)
        self.assertEqual(1, result.repository_checks.local_bytes_verified)

    def test_pure_document_contract_does_not_open_repository(self) -> None:
        manifest = empty_manifest()
        manifest["fixtures"] = [local_fixture("pure-document", pending=True)]
        with mock.patch.object(policy, "worktree_view") as repository_probe:
            result = policy.validate_document(manifest, schema=copy.deepcopy(SCHEMA_TEMPLATE))
        repository_probe.assert_not_called()
        self.assertIsInstance(result, policy.DocumentValidationResult)

    def test_pr19_compatible_imported_entrypoint(self) -> None:
        manifest = policy.load_json(policy.DEFAULT_MANIFEST)
        result = policy.validate(manifest, check_local_files=False)
        self.assertIsInstance(result, policy.WorktreeValidationResult)
        self.assertEqual(len(manifest["fixtures"]), result.document.fixture_count)
        self.assertEqual(1, len(result.document.local_artifacts))

    def test_valid_synthetic_generator_and_identity_mismatch(self) -> None:
        data = b"synthetic"
        self.repository.write_text(
            "tools/generate_synthetic.py",
            "import sys\nsys.stdout.buffer.write(b'synthetic')\n",
        )
        run_git(self.repository.root, "add", "tools/generate_synthetic.py")
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(data)]
        result = validate_in(self.repository, manifest)
        self.assertEqual(1, result.document.fixture_count)
        self.assertEqual(1, len(result.generator_measurements))
        manifest["fixtures"][0]["artifact"]["identity"]["sha256"] = "0" * 64
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest)

    def test_public_generated_fixture_materialization_reuses_sandbox_authority(self) -> None:
        data = b"synthetic"
        self.repository.write_text("tools/generate_synthetic.py", GOOD_GENERATOR)
        run_git(self.repository.root, "add", "tools/generate_synthetic.py")
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(data)]
        self.repository.write_json(policy.MANIFEST_PATH, manifest)
        run_git(self.repository.root, "add", policy.MANIFEST_PATH)
        repository = policy.worktree_view(self.repository.root)

        materialized = policy.materialize_public_generated_fixture(
            "synthetic-generated-artifact",
            repository=repository,
        )

        self.assertEqual(data, materialized)
        manifest["fixtures"][0]["ci"]["mode"] = "excluded"
        self.repository.write_json(policy.MANIFEST_PATH, manifest)
        run_git(self.repository.root, "add", policy.MANIFEST_PATH)
        with self.assertRaisesRegex(policy.ValidationError, "public generated fixture"):
            policy.materialize_public_generated_fixture(
                "synthetic-generated-artifact",
                repository=policy.worktree_view(self.repository.root),
            )

    def test_generator_symlink_and_unsafe_fixture_path_are_rejected(self) -> None:
        self.repository.write_text("tools/actual.py", "print('synthetic', end='')\n")
        (self.repository.root / "tools" / "generate_synthetic.py").symlink_to("actual.py")
        run_git(self.repository.root, "add", "tools/actual.py", "tools/generate_synthetic.py")
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(b"synthetic")]
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest)

        unsafe = empty_manifest()
        entry = local_fixture("unsafe-path")
        entry["artifact"]["path"] = "fixtures/private/../outside.bin"
        unsafe["fixtures"] = [entry]
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, unsafe)

    def test_derivation_identity_and_read_only_policy(self) -> None:
        parent = local_fixture("synthetic-parent")
        child = local_fixture("synthetic-child", category="derived-sanitized")
        child["derivation"] = {
            "parents": [{"fixture_id": "synthetic-parent", "sha256": hashlib.sha256(b"test").hexdigest()}],
            "method": "Synthetic unit-test derivation.",
            "sanitization": "No real data is involved.",
        }
        manifest = empty_manifest()
        manifest["fixtures"] = [parent, child]
        result = validate_in(self.repository, manifest)
        self.assertEqual(2, result.document.fixture_count)
        self.assertEqual(2, len(result.document.local_artifacts))
        child["derivation"]["parents"][0]["sha256"] = "0" * 64
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest)
        child["derivation"]["parents"][0]["sha256"] = hashlib.sha256(b"test").hexdigest()
        child["input_policy"] = "read-write"
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest)

    def test_manifest_schema_violation_is_rejected(self) -> None:
        manifest = empty_manifest()
        entry = local_fixture("schema-violation")
        entry["source"]["unexpected"] = "not allowed"
        manifest["fixtures"] = [entry]
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest)


class HistoryAdmissionTests(unittest.TestCase):
    def new_repository(self) -> SyntheticRepository:
        repository = SyntheticRepository()
        self.addCleanup(repository.close)
        return repository

    def test_private_artifact_added_then_deleted_still_fails(self) -> None:
        repository = self.new_repository()
        repository.write_bytes("fixtures/private/secret.bin", b"synthetic-private")
        repository.commit("forbidden private artifact")
        repository.remove("fixtures/private/secret.bin")
        repository.commit("delete forbidden artifact")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_undeclared_save_added_then_renamed_still_fails(self) -> None:
        repository = self.new_repository()
        repository.write_bytes("temporary.SaV", b"synthetic-save")
        repository.commit("undeclared save")
        run_git(repository.root, "mv", "temporary.SaV", "temporary.bin")
        repository.commit("rename undeclared save")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_undeclared_public_artifact_added_then_deleted_still_fails(self) -> None:
        repository = self.new_repository()
        repository.write_bytes("fixtures/public/undeclared.bin", b"synthetic-public")
        repository.commit("undeclared public artifact")
        repository.remove("fixtures/public/undeclared.bin")
        repository.commit("delete undeclared public artifact")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_historical_public_symlink_is_rejected(self) -> None:
        repository = self.new_repository()
        data = b"synthetic-public"
        repository.write_bytes("target.bin", data)
        public = repository.root / "fixtures" / "public"
        public.mkdir(parents=True, exist_ok=True)
        (public / "vector.bin").symlink_to("../../target.bin")
        manifest = empty_manifest()
        manifest["fixtures"] = [repository_fixture("fixtures/public/vector.bin", data)]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        repository.commit("public symlink")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_historical_public_gitlink_mode_is_rejected(self) -> None:
        repository = self.new_repository()
        path = "fixtures/public/vector.bin"
        manifest = empty_manifest()
        manifest["fixtures"] = [repository_fixture(path, b"synthetic")]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        run_git(repository.root, "add", policy.MANIFEST_PATH)
        run_git(
            repository.root,
            "update-index",
            "--add",
            "--cacheinfo",
            f"160000,{repository.head()},{path}",
        )
        run_git(repository.root, "commit", "-m", "public gitlink")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_secondary_merge_parent_is_scanned(self) -> None:
        repository = self.new_repository()
        run_git(repository.root, "checkout", "-b", "side")
        repository.write_bytes("fixtures/private/side.bin", b"synthetic-private")
        repository.commit("private artifact on secondary parent")
        run_git(repository.root, "checkout", "main")
        repository.write_text("main.txt", "clean main\n")
        repository.commit("main work")
        run_git(repository.root, "merge", "--no-ff", "--no-commit", "side")
        repository.remove("fixtures/private/side.bin")
        run_git(repository.root, "add", "-A")
        run_git(repository.root, "commit", "-m", "merge with clean result")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_earlier_unapproved_then_valid_head_fails(self) -> None:
        repository = self.new_repository()
        data = b"synthetic-public"
        path = "fixtures/public/vector.bin"
        repository.write_bytes(path, data)
        manifest = empty_manifest()
        manifest["fixtures"] = [repository_fixture(path, data, approved=False)]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        repository.commit("unapproved public bytes")
        manifest["fixtures"] = [repository_fixture(path, data, approved=True)]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        repository.commit("approve only at head")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_earlier_hash_mismatch_then_valid_head_fails(self) -> None:
        repository = self.new_repository()
        data = b"synthetic-public"
        path = "fixtures/public/vector.bin"
        repository.write_bytes(path, data)
        manifest = empty_manifest()
        manifest["fixtures"] = [repository_fixture(path, data, digest="0" * 64)]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        repository.commit("mismatched public bytes")
        manifest["fixtures"] = [repository_fixture(path, data)]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        repository.commit("correct only at head")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_properly_admitted_historical_fixture_later_deleted_passes(self) -> None:
        repository = self.new_repository()
        data = b"synthetic-public"
        path = "fixtures/public/vector.bin"
        repository.write_bytes(path, data)
        manifest = empty_manifest()
        manifest["fixtures"] = [repository_fixture(path, data)]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        repository.commit("admitted synthetic artifact")
        repository.remove(path)
        repository.write_json(policy.MANIFEST_PATH, empty_manifest())
        repository.commit("remove admitted artifact")
        self.assertGreaterEqual(
            policy.validate_history([repository.head()], repository.root).commit_count,
            3,
        )

    def test_historical_generator_is_never_executed(self) -> None:
        repository = self.new_repository()
        marker = repository.root / "executed.marker"
        repository.write_text(
            "tools/generate_synthetic.py",
            f"from pathlib import Path\nPath({str(marker)!r}).write_text('bad')\nprint('synthetic', end='')\n",
        )
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(b"synthetic")]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        repository.commit("historical generator")
        policy.validate_history([repository.head()], repository.root)
        self.assertFalse(marker.exists())

    def test_static_history_returns_subject_bound_result(self) -> None:
        repository = self.new_repository()
        head = repository.head()
        result = policy.validate_history([head], repository.root)
        self.assertIsInstance(result, policy.StaticHistoryAdmissionResult)
        self.assertEqual(head, result.requested_subjects[0].commit_oid)
        self.assertRegex(result.requested_subjects[0].tree_oid, r"^[0-9a-f]{40}$")
        self.assertEqual(result.commit_count, len(result.trees))
        self.assertTrue(all(tree.evaluator == result.evaluator for tree in result.trees))
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{40}", tree.subject.tree_oid) for tree in result.trees))

    def test_ambient_git_repository_variables_cannot_substitute_subject(self) -> None:
        repository = self.new_repository()
        other = self.new_repository()
        head = repository.head()
        with mock.patch.dict(os.environ, {"GIT_DIR": str(other.root / ".git")}):
            result = policy.validate_history([head], repository.root)
        self.assertEqual(head, result.requested_subjects[0].commit_oid)

    def test_historical_duplicate_manifest_member_fails(self) -> None:
        repository = self.new_repository()
        repository.write_text(
            policy.MANIFEST_PATH,
            '{"$schema":"./provenance-manifest.schema.json","schema_version":1,"fixtures":[],"fixtures":[]}\n',
        )
        repository.commit("duplicate manifest member")
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([repository.head()], repository.root)

    def test_historical_rounded_schema_constant_fails(self) -> None:
        repository = self.new_repository()
        repository.write_text(
            policy.MANIFEST_PATH,
            '{"$schema":"./provenance-manifest.schema.json",'
            '"schema_version":1.00000000000000001,"fixtures":[]}\n',
        )
        repository.commit("rounded schema version")
        with self.assertRaisesRegex(policy.ValidationError, r"\(const\)"):
            policy.validate_history([repository.head()], repository.root)

    def test_shallow_clone_missing_object_and_wrong_revision_fail(self) -> None:
        source = self.new_repository()
        source.write_text("later.txt", "later\n")
        source.commit("later")
        with tempfile.TemporaryDirectory() as clone_directory:
            clone = Path(clone_directory) / "clone"
            run_git(Path(clone_directory), "clone", "--depth", "1", f"file://{source.root}", str(clone))
            with self.assertRaises(policy.ValidationError):
                policy.validate_history(["HEAD"], clone)

        with self.assertRaises(policy.ValidationError):
            policy.validate_history(["definitely-not-a-revision"], source.root)

        missing = self.new_repository()
        missing.write_bytes("reachable.bin", b"synthetic-object-to-remove")
        missing.commit("reachable object")
        oid = run_git(missing.root, "rev-parse", "HEAD:reachable.bin").stdout.decode().strip()
        object_path = missing.root / ".git" / "objects" / oid[:2] / oid[2:]
        object_path.unlink()
        with self.assertRaises(policy.ValidationError):
            policy.validate_history([missing.head()], missing.root)


class HeadAdmissionTests(unittest.TestCase):
    def new_case(
        self,
        pr_generator: str,
        integration_generator: str,
    ) -> tuple[SyntheticRepository, str, str, str]:
        case = create_pull_request_case(pr_generator, integration_generator)
        self.addCleanup(case[0].close)
        return case

    def test_bad_pr_head_good_integration_generator_fails(self) -> None:
        repository, base, pr_head, integration_head = self.new_case(
            BAD_GENERATOR,
            GOOD_GENERATOR,
        )
        with self.assertRaisesRegex(policy.ValidationError, "generator"):
            admit_pull_request(repository, base, pr_head, integration_head)

    def test_good_pr_head_bad_integration_generator_fails(self) -> None:
        repository, base, pr_head, integration_head = self.new_case(
            GOOD_GENERATOR,
            BAD_GENERATOR,
        )
        with self.assertRaisesRegex(policy.ValidationError, "generator"):
            admit_pull_request(repository, base, pr_head, integration_head)

    def test_independent_valid_pr_and_integration_snapshots_pass(self) -> None:
        repository, base, pr_head, integration_head = self.new_case(
            GOOD_GENERATOR,
            GOOD_GENERATOR,
        )
        result = admit_pull_request(repository, base, pr_head, integration_head)
        self.assertIsInstance(result, policy.FixtureAdmissionResult)
        self.assertEqual(["pr-head", "integration-head"], [head.subject.role for head in result.heads])
        self.assertEqual([pr_head, integration_head], [head.subject.commit_oid for head in result.heads])
        self.assertEqual([1, 1], [len(head.generator_measurements) for head in result.heads])
        self.assertEqual(base, result.base.commit_oid)
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{40}", head.subject.tree_oid) for head in result.heads))
        self.assertTrue(
            all(re.fullmatch(r"[0-9a-f]{64}", head.snapshot_sha256 or "") for head in result.heads)
        )
        self.assertTrue(all(head.evaluator == result.evaluator for head in result.heads))
        self.assertNotEqual(result.evaluator.repository, result.heads[0].subject.repository)

    def test_untracked_dirty_and_other_head_content_cannot_substitute(self) -> None:
        conditional_bad = (
            "from pathlib import Path\n"
            "import sys\n"
            "value = b'synthetic' if Path('ambient-override').exists() else b'wrong'\n"
            "sys.stdout.buffer.write(value)\n"
        )
        repository, _base, pr_head, _integration_head = self.new_case(
            conditional_bad,
            GOOD_GENERATOR,
        )
        run_git(repository.root, "checkout", "--detach", pr_head)
        repository.write_text("ambient-override", "untracked substitution\n")
        ambient = subprocess.run(
            [sys.executable, "tools/generate_synthetic.py"],
            cwd=repository.root,
            check=True,
            capture_output=True,
        )
        self.assertEqual(b"synthetic", ambient.stdout)
        with self.assertRaises(policy.ValidationError):
            policy.admit_explicit_heads(
                [policy.AdmissionRequest("local-head", pr_head)],
                root=repository.root,
            )
        with self.assertRaisesRegex(policy.ValidationError, "must be clean"):
            policy.admit_explicit_heads(
                [policy.AdmissionRequest("local-head", pr_head)],
                root=repository.root,
                require_clean_worktree=True,
            )

        repository.write_text("tools/generate_synthetic.py", GOOD_GENERATOR)
        with self.assertRaises(policy.ValidationError):
            policy.admit_explicit_heads(
                [policy.AdmissionRequest("local-head", pr_head)],
                root=repository.root,
            )

    def test_base_change_invalidates_integration_identity(self) -> None:
        repository, base, pr_head, integration_head = self.new_case(
            GOOD_GENERATOR,
            GOOD_GENERATOR,
        )
        wrong_base = run_git(repository.root, "rev-parse", f"{base}^").stdout.decode().strip()
        with self.assertRaisesRegex(policy.ValidationError, "exact two-parent merge"):
            admit_pull_request(repository, wrong_base, pr_head, integration_head)

    def test_full_commit_ids_and_exact_roles_are_required(self) -> None:
        repository, base, pr_head, integration_head = self.new_case(
            GOOD_GENERATOR,
            GOOD_GENERATOR,
        )
        with self.assertRaises(policy.ValidationError):
            policy.admit_explicit_heads(
                [policy.AdmissionRequest("local-head", pr_head[:12])],
                root=repository.root,
            )
        with self.assertRaises(policy.ValidationError):
            policy.admit_explicit_heads(
                [policy.AdmissionRequest("pr-head", pr_head)],
                root=repository.root,
                base_oid=base,
            )
        with self.assertRaises(policy.ValidationError):
            policy.admit_explicit_heads(
                [
                    policy.AdmissionRequest("pr-head", pr_head),
                    policy.AdmissionRequest("integration-head", integration_head),
                ],
                root=repository.root,
                base_oid=integration_head,
            )

    def test_generator_network_access_is_denied(self) -> None:
        network_generator = (
            "import socket, sys\n"
            "try:\n"
            "    socket.socket()\n"
            "except OSError:\n"
            "    sys.stdout.buffer.write(b'wrong')\n"
            "else:\n"
            "    sys.stdout.buffer.write(b'synthetic')\n"
        )
        repository = SyntheticRepository()
        self.addCleanup(repository.close)
        repository.write_text("tools/generate_synthetic.py", network_generator)
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(b"synthetic")]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        head = repository.commit("network generator")
        with self.assertRaises(policy.ValidationError):
            policy.admit_explicit_heads(
                [policy.AdmissionRequest("local-head", head)],
                root=repository.root,
            )

    def test_generator_direct_io_uring_setup_is_denied(self) -> None:
        io_uring_generator = (
            "import ctypes, errno, os, sys\n"
            "parameters = ctypes.create_string_buffer(256)\n"
            "libc = ctypes.CDLL(None, use_errno=True)\n"
            "result = libc.syscall(425, 2, ctypes.byref(parameters))\n"
            "error = ctypes.get_errno()\n"
            "if result >= 0:\n"
            "    os.close(result)\n"
            "blocked = result == -1 and error == errno.EPERM\n"
            "sys.stdout.buffer.write(b'synthetic' if blocked else b'wrong')\n"
        )
        repository = SyntheticRepository()
        self.addCleanup(repository.close)
        repository.write_text("tools/generate_synthetic.py", io_uring_generator)
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(b"synthetic")]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        head = repository.commit("io_uring generator")
        result = policy.admit_explicit_heads(
            [policy.AdmissionRequest("local-head", head)],
            root=repository.root,
        )
        self.assertEqual(1, len(result.heads[0].generator_measurements))

    def test_generator_sandbox_keeps_network_namespace_and_blocks_io_uring(
        self,
    ) -> None:
        command = policy._sandbox_command(Path("/exact-snapshot"), "generator.py", 17)
        self.assertIn("--unshare-all", command)
        self.assertNotIn("--share-net", command)

        instructions = list(
            struct.iter_unpack("=HBBI", policy._seccomp_network_filter())
        )
        denied_syscalls = {
            instruction[3]
            for instruction, following in zip(instructions, instructions[1:])
            if instruction[:3] == (0x15, 0, 1)
            and following == (0x06, 0, 0, 0x00050000 | errno.EPERM)
        }
        self.assertTrue(policy.NETWORK_SYSCALLS_X86_64 <= denied_syscalls)
        self.assertTrue(policy.IO_URING_SYSCALLS_X86_64 <= denied_syscalls)

    def test_generator_does_not_inherit_ambient_credentials(self) -> None:
        environment_generator = (
            "import os, sys\n"
            "value = b'synthetic' if os.environ.get('FIXTURE_TEST_CREDENTIAL') else b'wrong'\n"
            "sys.stdout.buffer.write(value)\n"
        )
        repository = SyntheticRepository()
        self.addCleanup(repository.close)
        repository.write_text("tools/generate_synthetic.py", environment_generator)
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(b"synthetic")]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        head = repository.commit("environment generator")
        with mock.patch.dict(os.environ, {"FIXTURE_TEST_CREDENTIAL": "synthetic-secret"}):
            with self.assertRaises(policy.ValidationError):
                policy.admit_explicit_heads(
                    [policy.AdmissionRequest("local-head", head)],
                    root=repository.root,
                )


class CliAndWorkflowTests(unittest.TestCase):
    def test_cli_validates_worktree_and_explicit_history(self) -> None:
        repository = SyntheticRepository()
        self.addCleanup(repository.close)
        result = subprocess.run(
            [
                sys.executable,
                str(VALIDATOR),
                "--repository-root",
                str(repository.root),
                "--subject",
                f"local-head={repository.head()}",
            ],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("reachable commit trees statically inspected", result.stdout)

    def test_cli_opt_in_pending_local_fixture_fails_closed(self) -> None:
        repository = SyntheticRepository()
        self.addCleanup(repository.close)
        manifest = empty_manifest()
        manifest["fixtures"] = [local_fixture("pending-private", pending=True)]
        repository.write_json(policy.MANIFEST_PATH, manifest)
        head = repository.commit("pending local fixture")
        result = subprocess.run(
            [
                sys.executable,
                str(VALIDATOR),
                "--repository-root",
                str(repository.root),
                "--subject",
                f"local-head={head}",
                "--check-local-files",
            ],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("local check requires verified identity", result.stderr)

    def test_cli_executes_actual_pr_and_integration_subject_roles(self) -> None:
        repository, base, pr_head, integration_head = create_pull_request_case(
            GOOD_GENERATOR,
            GOOD_GENERATOR,
        )
        self.addCleanup(repository.close)
        result = subprocess.run(
            [
                sys.executable,
                str(VALIDATOR),
                "--repository-root",
                str(repository.root),
                "--subject",
                f"pr-head={pr_head}",
                "--subject",
                f"integration-head={integration_head}",
                "--base",
                base,
            ],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"pr-head={pr_head}", result.stdout)
        self.assertIn(f"integration-head={integration_head}", result.stdout)
        self.assertIn(f"base={base}", result.stdout)

    def test_workflow_has_complete_checkout_dependencies_tests_and_all_heads(self) -> None:
        workflow = (REPOSITORY_ROOT / ".github" / "workflows" / "core-ci.yml").read_text()
        core_build = (REPOSITORY_ROOT / "core" / "build.gradle.kts").read_text()
        pull_request_types_match = re.search(
            r"(?m)^  pull_request:\n    types: \[([^\]]+)\]$",
            workflow,
        )
        self.assertIsNotNone(pull_request_types_match)
        pull_request_types = {
            event.strip() for event in pull_request_types_match.group(1).split(",")
        }
        self.assertEqual(
            {"opened", "synchronize", "reopened", "ready_for_review", "edited"},
            pull_request_types,
        )
        self.assertGreaterEqual(workflow.count("fetch-depth: 0"), 2)
        self.assertGreaterEqual(workflow.count("actions/setup-python@v5"), 2)
        self.assertGreaterEqual(workflow.count("--require-hashes"), 2)
        self.assertEqual(2, workflow.count("runs-on: ubuntu-24.04"))
        self.assertEqual(2, workflow.count("python3 -B tools/setup_bwrap_sandbox.py"))
        self.assertNotIn("bwrap --version", workflow)
        self.assertNotIn("sysctl", workflow)
        self.assertNotIn("apparmor_restrict_unprivileged_userns", workflow)
        self.assertIn("python3 -B -m unittest discover", workflow)
        self.assertIn('pr-head=${{ github.event.pull_request.head.sha }}', workflow)
        self.assertIn('integration-head=$GITHUB_SHA', workflow)
        self.assertIn('--base "${{ github.event.pull_request.base.sha }}"', workflow)
        self.assertIn('push-head=$GITHUB_SHA', workflow)
        self.assertIn("github.event.pull_request.base.sha || 'push'", workflow)
        self.assertIn("materialize_public_generated_fixture", core_build)
        self.assertNotIn("subprocess", core_build)
        self.assertNotIn("generate_build041202_header.py", core_build)


class FakeSandboxSetupHost:
    def __init__(
        self,
        *,
        smokes: list[bool],
        active: list[dict[str, str]] | None = None,
        staged: list[bool] | None = None,
        package_installed: bool = False,
        profile_exists: bool = False,
    ) -> None:
        self.smokes = iter(smokes)
        self.active = iter(active or [])
        self.staged = iter(staged or [])
        self.package_installed = package_installed
        self.profile_exists = profile_exists
        self.calls: list[str] = []

    def install_bubblewrap(self) -> None:
        self.calls.append("install-bubblewrap")

    def sandbox_smoke_passes(self) -> bool:
        self.calls.append("smoke")
        return next(self.smokes)

    def active_bwrap_profiles(self) -> dict[str, str]:
        self.calls.append("active")
        return next(self.active)

    def staged_bwrap_policy_exists(self) -> bool:
        self.calls.append("staged")
        return next(self.staged)

    def profile_package_installed(self) -> bool:
        self.calls.append("package-installed")
        return self.package_installed

    def packaged_profile_exists(self) -> bool:
        self.calls.append("profile-exists")
        return self.profile_exists

    def install_profile_package(self) -> None:
        self.calls.append("install-profile-package")
        self.package_installed = True
        self.profile_exists = True

    def verify_packaged_profile(self) -> None:
        self.calls.append("verify-profile")

    def add_packaged_profile(self) -> None:
        self.calls.append("add-profile")


class SandboxSetupTests(unittest.TestCase):
    NOBLE_PROFILE_SHAPE = """\
profile bwrap /usr/bin/bwrap flags=(attach_disconnected) {
  allow capability,
  allow userns,
  allow px /** -> bwrap//&unpriv_bwrap,
}
profile unpriv_bwrap flags=(attach_disconnected) {
  allow userns,
  allow pix /** -> &unpriv_bwrap,
  audit deny capability,
}
"""

    def test_existing_working_bwrap_policy_is_left_untouched(self) -> None:
        host = FakeSandboxSetupHost(smokes=[True])
        result = sandbox_setup.establish_generator_sandbox(host)
        self.assertEqual("existing host policy", result)
        self.assertEqual(["install-bubblewrap", "smoke"], host.calls)

    def test_failed_smoke_with_existing_policy_fails_without_mutation(self) -> None:
        host = FakeSandboxSetupHost(
            smokes=[False],
            active=[{"vendor-bwrap": "enforce"}],
        )
        with self.assertRaises(sandbox_setup.SetupError):
            sandbox_setup.establish_generator_sandbox(host)
        self.assertEqual(["install-bubblewrap", "smoke", "active"], host.calls)

    def test_failed_smoke_with_staged_policy_fails_without_mutation(self) -> None:
        host = FakeSandboxSetupHost(
            smokes=[False],
            active=[{}],
            staged=[True],
        )
        with self.assertRaises(sandbox_setup.SetupError):
            sandbox_setup.establish_generator_sandbox(host)
        self.assertEqual(
            ["install-bubblewrap", "smoke", "active", "staged"],
            host.calls,
        )

    def test_inconsistent_packaged_profile_state_fails_without_repair(self) -> None:
        host = FakeSandboxSetupHost(
            smokes=[False],
            active=[{}],
            staged=[False],
            package_installed=True,
            profile_exists=False,
        )
        with self.assertRaises(sandbox_setup.SetupError):
            sandbox_setup.establish_generator_sandbox(host)
        self.assertNotIn("install-profile-package", host.calls)

    def test_packaged_profile_is_verified_added_and_really_smoked(self) -> None:
        host = FakeSandboxSetupHost(
            smokes=[False, False, True],
            active=[{}, {}, dict(sandbox_setup.EXPECTED_ACTIVE_PROFILES)],
            staged=[False, False],
        )
        result = sandbox_setup.establish_generator_sandbox(host)
        self.assertEqual("Ubuntu packaged bwrap-userns-restrict profile", result)
        self.assertEqual(
            [
                "install-bubblewrap", "smoke", "active", "staged",
                "package-installed", "profile-exists", "install-profile-package",
                "smoke", "active", "staged", "verify-profile", "add-profile",
                "active", "smoke",
            ],
            host.calls,
        )

    def test_packaged_profile_that_does_not_enable_bwrap_fails_closed(self) -> None:
        host = FakeSandboxSetupHost(
            smokes=[False, False, False],
            active=[{}, {}, dict(sandbox_setup.EXPECTED_ACTIVE_PROFILES)],
            staged=[False, False],
            package_installed=True,
            profile_exists=True,
        )
        with self.assertRaises(sandbox_setup.SetupError):
            sandbox_setup.establish_generator_sandbox(host)
        self.assertEqual("smoke", host.calls[-1])

    def test_authentic_noble_profile_transition_shape_is_accepted(self) -> None:
        sandbox_setup.verify_noble_profile_shape(self.NOBLE_PROFILE_SHAPE)

    def test_wrong_noble_profile_transition_shapes_are_rejected(self) -> None:
        wrong_outer_pix = self.NOBLE_PROFILE_SHAPE.replace(
            "allow px /** -> bwrap//&unpriv_bwrap,",
            "allow pix /** -> bwrap//&unpriv_bwrap,",
        )
        wrong_outer = self.NOBLE_PROFILE_SHAPE.replace(
            "allow px /** -> bwrap//&unpriv_bwrap,",
            "allow pix /** -> &bwrap//&unpriv_bwrap,",
        )
        wrong_child = self.NOBLE_PROFILE_SHAPE.replace(
            "allow pix /** -> &unpriv_bwrap,",
            "allow px /** -> unpriv_bwrap,",
        )
        for profile in (wrong_outer_pix, wrong_outer, wrong_child):
            with self.subTest(profile=profile), self.assertRaises(sandbox_setup.SetupError):
                sandbox_setup.verify_noble_profile_shape(profile)

    def test_setup_source_forbids_global_weakening_and_policy_replacement(self) -> None:
        source = (REPOSITORY_ROOT / "tools" / "setup_bwrap_sandbox.py").read_text()
        self.assertNotIn("apparmor_restrict_unprivileged_userns=0", source)
        self.assertNotIn("unprivileged_userns_clone=", source)
        self.assertNotIn("sysctl", source)
        self.assertNotIn("--replace", source)
        self.assertNotIn("apparmor-utils", source)
        self.assertIn('"--add"', source)
        self.assertIn('"apparmor-profiles"', source)
        self.assertIn('"--verify"', source)
        self.assertIn('"--unshare-all"', source)
        self.assertNotIn('"--share-net"', source)
        self.assertIn('"--clearenv"', source)
        self.assertIn('"/usr/bin/true"', source)
        self.assertIn("timeout=15", source)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import copy
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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


def validate_in(repository: SyntheticRepository, manifest: dict[str, object], *, local: bool = False) -> tuple[int, int, int]:
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
        self.assertEqual((1, 0, 1), validate_in(self.repository, manifest))
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest, local=True)

    def test_pr19_compatible_imported_entrypoint(self) -> None:
        manifest = policy.load_json(policy.DEFAULT_MANIFEST)
        result = policy.validate(manifest, check_local_files=False)
        self.assertEqual(len(manifest["fixtures"]), result[0])
        self.assertEqual(1, result[2])

    def test_valid_synthetic_generator_and_identity_mismatch(self) -> None:
        data = b"synthetic"
        self.repository.write_text(
            "tools/generate_synthetic.py",
            "import sys\nsys.stdout.buffer.write(b'synthetic')\n",
        )
        run_git(self.repository.root, "add", "tools/generate_synthetic.py")
        manifest = empty_manifest()
        manifest["fixtures"] = [generated_fixture(data)]
        self.assertEqual((1, 0, 0), validate_in(self.repository, manifest))
        manifest["fixtures"][0]["artifact"]["identity"]["sha256"] = "0" * 64
        with self.assertRaises(policy.ValidationError):
            validate_in(self.repository, manifest)

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
        self.assertEqual((2, 0, 2), validate_in(self.repository, manifest))
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
        self.assertGreaterEqual(policy.validate_history([repository.head()], repository.root), 3)

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

    def test_historical_duplicate_manifest_member_fails(self) -> None:
        repository = self.new_repository()
        repository.write_text(
            policy.MANIFEST_PATH,
            '{"$schema":"./provenance-manifest.schema.json","schema_version":1,"fixtures":[],"fixtures":[]}\n',
        )
        repository.commit("duplicate manifest member")
        with self.assertRaises(policy.ValidationError):
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


class CliAndWorkflowTests(unittest.TestCase):
    def test_cli_validates_worktree_and_explicit_history(self) -> None:
        repository = SyntheticRepository()
        self.addCleanup(repository.close)
        result = subprocess.run(
            [sys.executable, str(VALIDATOR), "--repository-root", str(repository.root), "--head", "HEAD"],
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("reachable commit trees inspected", result.stdout)

    def test_workflow_has_complete_checkout_dependencies_tests_and_all_heads(self) -> None:
        workflow = (REPOSITORY_ROOT / ".github" / "workflows" / "core-ci.yml").read_text()
        self.assertGreaterEqual(workflow.count("fetch-depth: 0"), 2)
        self.assertGreaterEqual(workflow.count("actions/setup-python@v5"), 2)
        self.assertGreaterEqual(workflow.count("--require-hashes"), 2)
        self.assertIn("python3 -m unittest discover", workflow)
        self.assertIn("github.event.pull_request.head.sha", workflow)
        self.assertIn('GITHUB_SHA', workflow)


if __name__ == "__main__":
    unittest.main()

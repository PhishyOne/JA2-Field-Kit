"""Bounded source-policy checks for the exact-main debug artifact."""

from pathlib import Path
import base64
import os
import re
import subprocess
import tempfile
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/core-ci.yml"
MAIN_PUSH = "github.event_name == 'push' && github.ref == 'refs/heads/main'"
APK = "android-app/build/outputs/apk/debug/android-app-debug.apk"
FINGERPRINT = "59f644232f2ae3a17e9ddeede98d0b59a366981368631d6000bf053aa57489fe"


class DebugArtifactWorkflowTests(unittest.TestCase):
    def test_exact_main_test_delivery_policy(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertEqual(
            re.findall(r"(?m)^ *permissions:[^\n]*\n(?: +[^\n]+\n)*", workflow),
            ["permissions:\n  contents: read\n"],
        )
        self.assertNotRegex(workflow, r"(?m)^\s*[\w-]+: write\s*$")
        self.assertEqual(
            re.findall(r"(?m)^\s*persist-credentials: (.*)$", workflow),
            ["false", "false", "false"],
        )
        for forbidden in (
            r"gh\s+release", r"(?i)create[-_ ]release", r"(?i)action-gh-release",
            r"(?i)upload-release", r"/releases",
            r"continue-on-error", r"(?i)staging",
            r"(?i)(?:assemble|bundle|signingConfigs?)[^\n]*release",
            r"(?i)play", r"(?i)packages:", r"actions/download-artifact@",
        ):
            self.assertNotRegex(workflow, forbidden)

        # Match this bounded workflow's job/step layout without a YAML dependency.
        job_parts = re.split(r"(?m)^  ([\w-]+):\n", workflow.split("\njobs:\n", 1)[1])
        jobs = dict(zip(job_parts[1::2], job_parts[2::2]))
        self.assertEqual(list(jobs), ["fixture-policy", "test", "publish-debug-apk"])
        for name in ("fixture-policy", "test"):
            self.assertNotIn("JA2_FIELD_KIT_TEST", jobs[name])
            self.assertNotIn("FIELD_KIT_TEST_KEYSTORE", jobs[name])
            self.assertNotRegex(jobs[name], r"secrets\s*[.\[]")
            self.assertNotRegex(jobs[name], r"(?m)^    (?:needs|if):")
            self.assertNotIn("provenance", jobs[name])
            self.assertNotIn("actions/upload-artifact", jobs[name])

        publish = jobs["publish-debug-apk"]
        self.assertEqual(
            publish.split("    steps:\n", 1)[0],
            "    needs: [fixture-policy, test]\n"
            f"    if: {MAIN_PUSH}\n"
            "    runs-on: ubuntu-24.04\n",
        )
        self.assertEqual(re.findall(r"(?m)^\s+if: (.*)$", publish), [MAIN_PUSH, "always()"])
        self.assertNotRegex(workflow.split("  publish-debug-apk:\n", 1)[0], r"secrets\s*[.\[]")
        clauses = dict(re.findall(r"github\.(\w+) == '([^']+)'", MAIN_PUSH))
        for event, ref, expected in (
            ("push", "refs/heads/main", True),
            ("push", "refs/heads/feat/test", False),
            ("push", "refs/heads/fix/test", False),
            ("pull_request", "refs/heads/main", False),
            ("pull_request", "refs/pull/53/merge", False),
        ):
            self.assertEqual(
                event == clauses["event_name"] and ref == clauses["ref"], expected
            )

        steps = re.split(r"(?m)^      - name: ", publish)[1:]
        names = [step.splitlines()[0] for step in steps]
        setup_names = [
            "Check out repository", "Set up JDK 21", "Set up supported Gradle",
            "Install pinned Android command-line tools", "Install pinned Android SDK packages",
        ]
        self.assertEqual(names, setup_names + [
            "Materialize test signing keystore",
            "Assemble debug APK", "Record debug APK provenance", "Upload test-only debug APK",
            "Remove test signing keystore",
        ])
        test_steps = dict(
            (step.splitlines()[0], step.strip())
            for step in re.split(r"(?m)^      - name: ", jobs["test"])[1:]
        )
        for name, step in zip(setup_names, steps):
            self.assertEqual(step.strip(), test_steps[name])
        checkout = steps[0]
        self.assertEqual(checkout.split(), (
            "Check out repository uses: actions/checkout@v4 with: "
            "fetch-depth: 0 persist-credentials: false"
        ).split())
        for pin in (
            'java-version: "21"', 'gradle-version: "9.5.0"',
            "commandlinetools-linux-15859902_latest.zip",
            "COMMAND_LINE_TOOLS_SHA256: 4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583",
            'echo "$COMMAND_LINE_TOOLS_SHA256  $archive" | sha256sum --check --strict',
            '"platforms;android-37.0" "build-tools;36.0.0"',
        ):
            self.assertIn(pin, publish)
        self.assertEqual(
            test_steps["Run Android shell checks"].split(),
            ("Run Android shell checks run: >- gradle "
             ":android-app:testDebugUnitTest :android-app:lintDebug "
             ":android-app:assembleDebug --no-daemon").split(),
        )
        checks_index = names.index("Assemble debug APK")
        provenance_index = names.index("Record debug APK provenance")
        upload_index = names.index("Upload test-only debug APK")
        self.assertEqual(provenance_index, checks_index + 1)
        self.assertEqual(upload_index, provenance_index + 1)
        self.assertEqual(
            steps[checks_index].split(),
            ("Assemble debug APK env: "
             "FIELD_KIT_TEST_KEYSTORE_PATH: ${{ runner.temp }}/ja2-field-kit-test-signing.p12 "
             "FIELD_KIT_TEST_KEYSTORE_PASSWORD: ${{ secrets.JA2_FIELD_KIT_TEST_SIGNING_PASSWORD }} "
             "run: gradle :android-app:assembleDebug --no-daemon").split(),
        )
        materialize = steps[checks_index - 1]
        self.assertEqual(materialize.split(), (
            "Materialize test signing keystore shell: bash env: "
            "JA2_FIELD_KIT_TEST_KEYSTORE_B64: ${{ secrets.JA2_FIELD_KIT_TEST_KEYSTORE_B64 }} "
            "run: | set -euo pipefail umask 077 "
            'test -n "$JA2_FIELD_KIT_TEST_KEYSTORE_B64" '
            'keystore="$RUNNER_TEMP/ja2-field-kit-test-signing.p12" '
            'printf \'%s\' "$JA2_FIELD_KIT_TEST_KEYSTORE_B64" | base64 --decode > "$keystore" '
            'chmod 0600 "$keystore" test -s "$keystore"'
        ).split())
        self.assertEqual(steps[-1].split(), (
            'Remove test signing keystore if: always() shell: bash '
            'run: rm -f -- "$RUNNER_TEMP/ja2-field-kit-test-signing.p12"'
        ).split())
        for index, step in enumerate(steps):
            if index != checks_index:
                self.assertNotIn("FIELD_KIT_TEST_KEYSTORE_PATH", step)
                self.assertNotIn("FIELD_KIT_TEST_KEYSTORE_PASSWORD", step)
            if index not in (checks_index - 1, checks_index):
                self.assertNotRegex(step, r"secrets\s*[.\[]")
        provenance, upload = steps[provenance_index], steps[upload_index]
        pin = f'[[ "$certificate_sha256" == "{FINGERPRINT}" ]]'
        self.assertIn(pin, provenance)
        self.assertLess(provenance.index('certificate_sha256="${certificate_sha256,,}"'), provenance.index(pin))
        self.assertLess(provenance.index(pin), provenance.index("apk_size=$("))
        self.assertLess(provenance.index(pin), provenance.index("printf 'source_sha="))

        self.assertEqual(workflow.count("actions/upload-artifact@"), 1)
        self.assertIn("uses: actions/upload-artifact@v4\n", upload)
        self.assertEqual(
            upload.split("        with:\n", 1)[1].rstrip() + "\n",
            "          name: ja2-field-kit-debug-${{ github.sha }}\n"
            "          path: |\n"
            f"            {APK}\n"
            f"            {APK}.provenance.txt\n"
            "          if-no-files-found: error\n"
            "          retention-days: 7\n"
            "          compression-level: 0\n",
        )
        for required in (
            "set -euo pipefail", f'apk="{APK}"', 'test -f "$apk"',
            '"$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner" verify --print-certs "$apk"',
            "printf 'source_sha=%s\\n' \"$GITHUB_SHA\"",
            "build_type=debug", "test_only=true",
            'stat -c %s "$apk"', 'sha256sum "$apk"', "apk_size_bytes=",
            "apk_sha256=", "signer_1_certificate_sha256=", "^[0-9a-fA-F]{64}$",
            '> "$apk.provenance.txt"',
        ):
            self.assertIn(required, provenance)

    def test_gradle_signing_is_explicit_debug_only_and_fails_closed(self):
        source = (WORKFLOW.parents[2] / "android-app/build.gradle.kts").read_text()
        guard = '''check((testKeystorePath == null && testKeystorePassword == null) ||
    (!testKeystorePath.isNullOrBlank() && !testKeystorePassword.isNullOrBlank()))'''
        self.assertIn(guard, source)
        self.assertLess(source.index(guard), source.index("android {"))
        for required in (
            'providers.environmentVariable("FIELD_KIT_TEST_KEYSTORE_PATH").orNull',
            'providers.environmentVariable("FIELD_KIT_TEST_KEYSTORE_PASSWORD").orNull',
            'if (testKeystorePath != null && testKeystorePassword != null)',
            'signingConfigs.getByName("debug")', 'storeFile = file(testKeystorePath)',
            'storePassword = testKeystorePassword', 'keyPassword = testKeystorePassword',
            'storeType = "PKCS12"', 'keyAlias = "ja2-field-kit-test"',
        ):
            self.assertIn(required, source)
        self.assertEqual(source.count("signingConfigs"), 1)
        self.assertNotRegex(source, r"(?i)release|play|publishing")

    def test_materialization_rejects_bad_input_and_cleanup_is_unconditional(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        steps = dict(
            (step.splitlines()[0], step)
            for step in re.split(r"(?m)^      - name: ", workflow)[1:]
        )
        script = steps["Materialize test signing keystore"].split("        run: |\n", 1)[1]
        cleanup = steps["Remove test signing keystore"].split("        run: ", 1)[1]
        with tempfile.TemporaryDirectory() as directory:
            env = {"PATH": os.environ["PATH"], "RUNNER_TEMP": directory}
            keystore = Path(directory) / "ja2-field-kit-test-signing.p12"
            valid = base64.b64encode(b"test-only dummy bytes").decode()
            for value in (None, "", "!invalid!", "\n", valid):
                with self.subTest(value=value):
                    if value is None:
                        env.pop("JA2_FIELD_KIT_TEST_KEYSTORE_B64", None)
                    else:
                        env["JA2_FIELD_KIT_TEST_KEYSTORE_B64"] = value
                    result = subprocess.run(["bash", "-c", script], env=env, capture_output=True)
                    if value == valid:
                        self.assertEqual(result.returncode, 0)
                        self.assertEqual(keystore.stat().st_mode & 0o777, 0o600)
                        self.assertEqual(keystore.read_bytes(), b"test-only dummy bytes")
                    else:
                        self.assertNotEqual(result.returncode, 0)
                    subprocess.run(["bash", "-c", cleanup], env={"PATH": env["PATH"], "RUNNER_TEMP": directory}, check=True)
                    self.assertFalse(keystore.exists())

    def test_provenance_requires_the_pinned_verified_signer(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        step = workflow.split("      - name: Record debug APK provenance\n", 1)[1]
        script = step.split("        run: |\n", 1)[1].split("      - name:", 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / APK
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"dummy APK for provenance policy")
            verifier = root / "build-tools/36.0.0/apksigner"
            verifier.parent.mkdir(parents=True)
            verifier.write_text('#!/bin/bash\nprintf "Signer #1 certificate SHA-256 digest: %s\\n" "$TEST_DIGEST"\nexit "$TEST_EXIT"\n')
            verifier.chmod(0o700)
            provenance = Path(str(apk) + ".provenance.txt")
            for digest, exit_code, accepted in (
                (FINGERPRINT, "0", True), (FINGERPRINT.upper(), "0", True),
                ("0" * 64, "0", False), ("", "0", False),
                ("malformed", "0", False), (FINGERPRINT, "1", False),
            ):
                with self.subTest(digest=digest, exit_code=exit_code):
                    provenance.unlink(missing_ok=True)
                    result = subprocess.run(["bash", "-c", script], cwd=root, capture_output=True, env={
                        "PATH": os.environ["PATH"], "ANDROID_SDK_ROOT": directory,
                        "GITHUB_SHA": "a" * 40, "TEST_DIGEST": digest, "TEST_EXIT": exit_code,
                    })
                    self.assertEqual(result.returncode == 0, accepted)
                    self.assertEqual(provenance.exists(), accepted)
                    if accepted:
                        self.assertIn(f"signer_1_certificate_sha256={FINGERPRINT}\n", provenance.read_text())


if __name__ == "__main__":
    unittest.main()

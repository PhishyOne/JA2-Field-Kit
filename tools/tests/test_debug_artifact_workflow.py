"""Bounded source-policy checks for the exact-main debug artifact."""

from pathlib import Path
import re
import unittest


WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/core-ci.yml"
MAIN_PUSH = "github.event_name == 'push' && github.ref == 'refs/heads/main'"
APK = "android-app/build/outputs/apk/debug/android-app-debug.apk"


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
            ["false", "false"],
        )
        for forbidden in (
            r"gh\s+release", r"(?i)create[-_ ]release", r"(?i)action-gh-release",
            r"(?i)upload-release", r"/releases", r"secrets\s*[.\[]",
            r"(?i)keystore", r"(?i)signingConfig", r"(?i)storePassword",
            r"(?i)keyPassword", r"(?i)storeFile",
            r"continue-on-error", r"always\(\)",
        ):
            self.assertNotRegex(workflow, forbidden)

        # Match the current small workflow's step layout, without a YAML dependency.
        steps = re.split(r"(?m)^      - name: ", workflow)[1:]
        names = [step.splitlines()[0] for step in steps]
        checks_index = names.index("Run Android shell checks")
        provenance_index = names.index("Record debug APK provenance")
        upload_index = names.index("Upload test-only debug APK")
        self.assertEqual(provenance_index, checks_index + 1)
        self.assertEqual(upload_index, provenance_index + 1)
        self.assertEqual(
            steps[checks_index].split(),
            ("Run Android shell checks run: >- gradle "
             ":android-app:testDebugUnitTest :android-app:lintDebug "
             ":android-app:assembleDebug --no-daemon").split(),
        )
        provenance, upload = steps[provenance_index], steps[upload_index]
        for step in (provenance, upload):
            self.assertEqual(re.findall(r"(?m)^        if: (.*)$", step), [MAIN_PUSH])
            # Evaluate only the two literal equality clauses asserted above.
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

        self.assertEqual(workflow.count("actions/upload-artifact@"), 1)
        self.assertIn("uses: actions/upload-artifact@v4\n", upload)
        self.assertEqual(
            upload.split("        with:\n", 1)[1],
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
            '"$GITHUB_SHA"', "source_sha=", "build_type=debug", "test_only=true",
            'stat -c %s "$apk"', 'sha256sum "$apk"', "apk_size_bytes=",
            "apk_sha256=", "signer_1_certificate_sha256=", "^[0-9a-fA-F]{64}$",
            '> "$apk.provenance.txt"',
        ):
            self.assertIn(required, provenance)


if __name__ == "__main__":
    unittest.main()

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
            ["false", "false", "false"],
        )
        for forbidden in (
            r"gh\s+release", r"(?i)create[-_ ]release", r"(?i)action-gh-release",
            r"(?i)upload-release", r"/releases", r"secrets\s*[.\[]",
            r"(?i)keystore", r"(?i)signingConfig", r"(?i)storePassword",
            r"(?i)keyPassword", r"(?i)storeFile",
            r"continue-on-error", r"always\s*\(", r"(?i)staging",
            r"(?i)assembleRelease", r"actions/download-artifact@",
        ):
            self.assertNotRegex(workflow, forbidden)

        # Match this bounded workflow's job/step layout without a YAML dependency.
        job_parts = re.split(r"(?m)^  ([\w-]+):\n", workflow.split("\njobs:\n", 1)[1])
        jobs = dict(zip(job_parts[1::2], job_parts[2::2]))
        self.assertEqual(list(jobs), ["fixture-policy", "test", "publish-debug-apk"])
        for name in ("fixture-policy", "test"):
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
        self.assertEqual(re.findall(r"(?m)^\s+if: (.*)$", publish), [MAIN_PUSH])
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
            "Assemble debug APK", "Record debug APK provenance", "Upload test-only debug APK",
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
            "Assemble debug APK run: gradle :android-app:assembleDebug --no-daemon".split(),
        )
        provenance, upload = steps[provenance_index], steps[upload_index]

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
            "printf 'source_sha=%s\\n' \"$GITHUB_SHA\"",
            "build_type=debug", "test_only=true",
            'stat -c %s "$apk"', 'sha256sum "$apk"', "apk_size_bytes=",
            "apk_sha256=", "signer_1_certificate_sha256=", "^[0-9a-fA-F]{64}$",
            '> "$apk.provenance.txt"',
        ):
            self.assertIn(required, provenance)


if __name__ == "__main__":
    unittest.main()

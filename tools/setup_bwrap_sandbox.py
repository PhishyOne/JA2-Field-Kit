#!/usr/bin/env python3
"""Establish the narrow Bubblewrap capability required by hosted fixture CI."""

from __future__ import annotations

import os
import re
import stat
import subprocess
import sys
from pathlib import Path
from typing import Protocol


BWRAP = Path("/usr/bin/bwrap")
PROFILE = Path("/usr/share/apparmor/extra-profiles/bwrap-userns-restrict")
PROFILE_PACKAGE = "apparmor-profiles"
PARSER = Path("/usr/sbin/apparmor_parser")
ACTIVE_PROFILES = Path("/sys/kernel/security/apparmor/profiles")
POLICY_DIRECTORY = Path("/etc/apparmor.d")
EXPECTED_ACTIVE_PROFILES = {"bwrap": "enforce", "unpriv_bwrap": "enforce"}
COMMAND_ENVIRONMENT = {"LANG": "C.UTF-8", "PATH": "/usr/sbin:/usr/bin:/sbin:/bin"}


class SetupError(RuntimeError):
    """A controlled, fail-closed sandbox setup failure."""


class SetupHost(Protocol):
    """Privileged host operations separated from the setup decision contract."""

    def install_bubblewrap(self) -> None: ...

    def sandbox_smoke_passes(self) -> bool: ...

    def active_bwrap_profiles(self) -> dict[str, str]: ...

    def staged_bwrap_policy_exists(self) -> bool: ...

    def profile_package_installed(self) -> bool: ...

    def packaged_profile_exists(self) -> bool: ...

    def install_profile_package(self) -> None: ...

    def verify_packaged_profile(self) -> None: ...

    def add_packaged_profile(self) -> None: ...


def _reject_preexisting_policy(host: SetupHost) -> None:
    if host.active_bwrap_profiles() or host.staged_bwrap_policy_exists():
        raise SetupError(
            "bwrap smoke failed while a pre-existing or ambiguous AppArmor policy exists"
        )


def verify_noble_profile_shape(text: str) -> None:
    """Require the security-relevant transitions in Noble's packaged policy."""

    required_shape = (
        r"profile\s+bwrap\s+/usr/bin/bwrap\b",
        r"profile\s+unpriv_bwrap\b",
        r"^\s*allow\s+px\s+/\*\*\s+->\s+bwrap//&unpriv_bwrap,\s*$",
        r"^\s*allow\s+pix\s+/\*\*\s+->\s+&unpriv_bwrap,\s*$",
        r"^\s*audit\s+deny\s+capability,\s*$",
    )
    if text.count("allow userns,") < 2 or any(
        re.search(pattern, text, flags=re.MULTILINE) is None
        for pattern in required_shape
    ):
        raise SetupError("Ubuntu's packaged bwrap profile has an unsupported policy shape")


def establish_generator_sandbox(host: SetupHost) -> str:
    """Install/probe bwrap and add Ubuntu's packaged profile only when required."""

    host.install_bubblewrap()
    if host.sandbox_smoke_passes():
        return "existing host policy"

    _reject_preexisting_policy(host)
    package_installed = host.profile_package_installed()
    profile_exists = host.packaged_profile_exists()
    if package_installed != profile_exists:
        raise SetupError("the Ubuntu bwrap AppArmor profile package state is inconsistent")
    if not package_installed:
        host.install_profile_package()

    # Package installation should only stage this extra profile. If a future
    # package version activates an adequate policy itself, do not disturb it.
    if host.sandbox_smoke_passes():
        return "policy activated by Ubuntu package setup"

    _reject_preexisting_policy(host)
    host.verify_packaged_profile()
    host.add_packaged_profile()
    if host.active_bwrap_profiles() != EXPECTED_ACTIVE_PROFILES:
        raise SetupError("the packaged bwrap AppArmor profiles were not added in enforce mode")
    if not host.sandbox_smoke_passes():
        raise SetupError("bwrap sandbox capability is unavailable after narrow profile setup")
    return "Ubuntu packaged bwrap-userns-restrict profile"


class UbuntuHost:
    """Ubuntu 24.04 implementation used by the hosted workflow."""

    def __init__(self) -> None:
        self._apt_updated = False

    @staticmethod
    def _run(
        command: list[str],
        *,
        capture: bool = False,
        allowed_returncodes: tuple[int, ...] = (0,),
        timeout: int | None = None,
    ) -> subprocess.CompletedProcess[str]:
        try:
            result = subprocess.run(
                command,
                check=False,
                capture_output=capture,
                text=True,
                stdin=subprocess.DEVNULL,
                env=COMMAND_ENVIRONMENT,
                timeout=timeout,
            )
        except (OSError, subprocess.SubprocessError) as exc:
            raise SetupError("a required sandbox setup command could not execute") from exc
        if result.returncode not in allowed_returncodes:
            raise SetupError("a required sandbox setup command failed")
        return result

    @classmethod
    def _sudo(
        cls,
        command: list[str],
        *,
        capture: bool = False,
        allowed_returncodes: tuple[int, ...] = (0,),
    ) -> subprocess.CompletedProcess[str]:
        return cls._run(
            ["/usr/bin/sudo", "--non-interactive", *command],
            capture=capture,
            allowed_returncodes=allowed_returncodes,
        )

    def _apt_update(self) -> None:
        if not self._apt_updated:
            self._sudo(["/usr/bin/apt-get", "update"])
            self._apt_updated = True

    def _apt_install(self, package: str) -> None:
        self._apt_update()
        self._sudo(
            [
                "/usr/bin/apt-get",
                "install",
                "--yes",
                "--no-install-recommends",
                package,
            ]
        )

    def install_bubblewrap(self) -> None:
        self._apt_install("bubblewrap")
        try:
            mode = BWRAP.lstat().st_mode
        except OSError as exc:
            raise SetupError("Ubuntu bubblewrap was not installed at its expected path") from exc
        if (
            not stat.S_ISREG(mode)
            or not mode & 0o111
            or mode & (stat.S_ISUID | stat.S_ISGID)
        ):
            raise SetupError("Ubuntu bubblewrap must be an ordinary non-setid executable")

    def sandbox_smoke_passes(self) -> bool:
        command = [
            str(BWRAP),
            "--unshare-all",
            "--die-with-parent",
            "--new-session",
        ]
        for system_path in ("/usr", "/lib", "/lib64"):
            if Path(system_path).exists():
                command.extend(("--ro-bind", system_path, system_path))
        command.extend(
            (
                "--tmpfs", "/tmp",
                "--proc", "/proc",
                "--dev", "/dev",
                "--chdir", "/tmp",
                "--clearenv",
                "--setenv", "PATH", "/usr/bin:/bin",
                "--", "/usr/bin/true",
            )
        )
        result = self._run(
            command,
            capture=True,
            allowed_returncodes=tuple(range(256)),
            timeout=15,
        )
        if result.returncode != 0:
            print("bwrap capability smoke failed", file=sys.stderr)
        return result.returncode == 0

    def active_bwrap_profiles(self) -> dict[str, str]:
        result = self._sudo(
            [
                "/usr/bin/grep",
                "--ignore-case",
                "--fixed-strings",
                "bwrap",
                str(ACTIVE_PROFILES),
            ],
            capture=True,
            allowed_returncodes=(0, 1),
        )
        if result.returncode == 1 and result.stderr:
            raise SetupError("the active AppArmor profile set cannot be inspected safely")
        profiles: dict[str, str] = {}
        for line in result.stdout.splitlines():
            match = re.fullmatch(r"(.+) \(([^()]*)\)", line)
            if match is None:
                raise SetupError("an active bwrap AppArmor profile has an ambiguous identity")
            profiles[match.group(1)] = match.group(2)
        return profiles

    def staged_bwrap_policy_exists(self) -> bool:
        named = self._sudo(
            [
                "/usr/bin/find", str(POLICY_DIRECTORY), "-xdev",
                "-iname", "*bwrap*", "-print",
            ],
            capture=True,
        )
        attached = self._sudo(
            [
                "/usr/bin/grep",
                "--recursive",
                "--files-with-matches",
                "--fixed-strings",
                "--exclude-dir=cache",
                "--",
                str(BWRAP),
                str(POLICY_DIRECTORY),
            ],
            capture=True,
            allowed_returncodes=(0, 1),
        )
        if attached.returncode == 1 and attached.stderr:
            raise SetupError("the staged AppArmor policy set cannot be inspected safely")
        return bool(named.stdout.strip() or attached.stdout.strip())

    def profile_package_installed(self) -> bool:
        result = self._run(
            [
                "/usr/bin/dpkg-query",
                "--show",
                "--showformat=${db:Status-Status}",
                PROFILE_PACKAGE,
            ],
            capture=True,
            allowed_returncodes=(0, 1),
        )
        if result.returncode == 1:
            return False
        return result.stdout == "installed"

    def packaged_profile_exists(self) -> bool:
        return os.path.lexists(PROFILE)

    def install_profile_package(self) -> None:
        if os.path.lexists(PROFILE):
            raise SetupError("an unowned bwrap AppArmor profile source already exists")
        self._apt_install(PROFILE_PACKAGE)
        if not os.path.lexists(PROFILE):
            raise SetupError("Ubuntu's AppArmor profile package did not provide the bwrap policy")

    def verify_packaged_profile(self) -> None:
        try:
            metadata = PROFILE.lstat()
            text = PROFILE.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            raise SetupError("the packaged bwrap AppArmor profile cannot be read safely") from exc
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != 0
            or metadata.st_gid != 0
        ):
            raise SetupError("the packaged bwrap AppArmor profile has unsafe metadata")

        owner = self._run(
            ["/usr/bin/dpkg-query", "--search", str(PROFILE)],
            capture=True,
        ).stdout.splitlines()
        if owner != [f"{PROFILE_PACKAGE}: {PROFILE}"]:
            raise SetupError("the bwrap AppArmor profile is not owned by Ubuntu's expected package")
        verification = self._run(
            ["/usr/bin/dpkg", "--verify", "--verify-format=rpm", PROFILE_PACKAGE],
            capture=True,
        )
        if verification.stdout or verification.stderr:
            raise SetupError("Ubuntu's packaged AppArmor profile set failed its integrity check")

        verify_noble_profile_shape(text)

    def add_packaged_profile(self) -> None:
        if not PARSER.is_file():
            raise SetupError("the AppArmor parser supplied by Ubuntu's apparmor package is missing")
        self._sudo(
            [
                str(PARSER),
                "--add",
                "--skip-cache",
                "--abort-on-error",
                str(PROFILE),
            ]
        )


def main() -> int:
    if os.geteuid() == 0:
        print("bwrap sandbox setup must run as the unprivileged workflow user", file=sys.stderr)
        return 1
    try:
        policy = establish_generator_sandbox(UbuntuHost())
    except SetupError as exc:
        print(f"sandbox setup failed: {exc}", file=sys.stderr)
        return 1
    print(f"bwrap capability smoke passed using {policy}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Run repeatable M0 inventory, web, and optional Qt build verification."""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def command_path(*names: str) -> str:
    for name in names:
        path = shutil.which(name)
        if path:
            return path
    raise RuntimeError(f"required command not found: {', '.join(names)}")


def run(command: list[str], cwd: Path) -> None:
    rendered = " ".join(command)
    print(f"\n[M0] {cwd.relative_to(ROOT) if cwd.is_relative_to(ROOT) else cwd}$ {rendered}")
    subprocess.run(command, cwd=cwd, check=True)


def verify_inventory() -> None:
    run([sys.executable, str(ROOT / "tools" / "m0_inventory.py"), "--check"], ROOT)


def verify_web(skip_install: bool) -> None:
    npm = command_path("npm", "npm.cmd")
    web = ROOT / "WebClient"
    if not skip_install:
        run([npm, "ci"], web)
    run([npm, "test"], web)
    run([npm, "run", "build"], web)


def select_make(qmake: str) -> tuple[str, bool]:
    spec = subprocess.run(
        [qmake, "-query", "QMAKE_XSPEC"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip().lower()
    if os.name == "nt" and "msvc" in spec:
        return command_path("nmake"), False
    if os.name == "nt":
        return command_path("mingw32-make", "make"), True
    return command_path("make", "gmake"), True


def select_qmake() -> str:
    return os.environ.get("QMAKE") or command_path("qmake6", "qmake")


def make_command(make: str, supports_jobs: bool, jobs: int) -> list[str]:
    command = [make]
    if supports_jobs:
        command.append(f"-j{jobs}")
    return command


def locate_executable(target_dir: Path, name: str) -> Path:
    executable_name = f"{name}.exe" if os.name == "nt" else name
    for candidate in (
        target_dir / executable_name,
        target_dir / "release" / executable_name,
        target_dir / "debug" / executable_name,
    ):
        if candidate.exists():
            return candidate
    raise RuntimeError(f"executable not found below {target_dir}: {executable_name}")


def verify_database_schema(jobs: int, build_root: Path) -> None:
    qmake = select_qmake()
    make, supports_jobs = select_make(qmake)
    target_dir = build_root / "database-schema"
    target_dir.mkdir(parents=True, exist_ok=True)

    run(
        [qmake, str(ROOT / "Tests" / "DatabaseSchemaTest.pro"), "CONFIG+=release"],
        target_dir,
    )
    run(make_command(make, supports_jobs, jobs), target_dir)

    executable = locate_executable(target_dir, "DatabaseSchemaTest")
    run([str(executable)], target_dir)


def verify_password_hash(jobs: int, build_root: Path) -> None:
    qmake = select_qmake()
    make, supports_jobs = select_make(qmake)
    target_dir = build_root / "password-hash"
    target_dir.mkdir(parents=True, exist_ok=True)

    run(
        [qmake, str(ROOT / "Tests" / "PasswordMigrationTest.pro"), "CONFIG+=release"],
        target_dir,
    )
    run(make_command(make, supports_jobs, jobs), target_dir)
    run([str(locate_executable(target_dir, "PasswordMigrationTest"))], target_dir)


def build_headless_server(jobs: int, build_root: Path, target_name: str) -> Path:
    qmake = select_qmake()
    make, supports_jobs = select_make(qmake)
    target_dir = build_root / target_name
    target_dir.mkdir(parents=True, exist_ok=True)

    run(
        [qmake, str(ROOT / "Tests" / "HeadlessServer.pro"), "CONFIG+=release"],
        target_dir,
    )
    run(make_command(make, supports_jobs, jobs), target_dir)

    return locate_executable(target_dir, "ChatServerHeadless")


def verify_v1_smoke(jobs: int, build_root: Path) -> None:
    executable = build_headless_server(jobs, build_root, "v1-smoke-server")
    for test_script in (
        "v1_smoke_test.py",
        "v1_authorization_test.py",
        "v1_transport_limits_test.py",
    ):
        run(
            [
                sys.executable,
                str(ROOT / "Tests" / test_script),
                "--server",
                str(executable),
            ],
            ROOT,
        )


def verify_performance(
    jobs: int,
    build_root: Path,
    output: Path,
    clients: int,
    messages: int,
    warmup: int,
) -> None:
    qmake = select_qmake()
    qt_version = subprocess.run(
        [qmake, "-query", "QT_VERSION"],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    executable = build_headless_server(jobs, build_root, "v1-performance-server")
    run(
        [
            sys.executable,
            str(ROOT / "Tests" / "v1_performance_baseline.py"),
            "--server",
            str(executable),
            "--output",
            str(output),
            "--clients",
            str(clients),
            "--messages",
            str(messages),
            "--warmup",
            str(warmup),
            "--qt-version",
            qt_version,
        ],
        ROOT,
    )


def verify_qt(jobs: int, build_root: Path) -> None:
    qmake = select_qmake()
    make, supports_jobs = select_make(qmake)

    print(f"[M0] Qt: {subprocess.run([qmake, '-query', 'QT_VERSION'], check=True, capture_output=True, text=True).stdout.strip()}")
    print(f"[M0] qmake: {qmake}")

    for target, project in (
        ("server", ROOT / "Server" / "Server.pro"),
        ("client", ROOT / "Client" / "Client.pro"),
    ):
        target_dir = build_root / target
        target_dir.mkdir(parents=True, exist_ok=True)
        run([qmake, str(project), "CONFIG+=release"], target_dir)
        run(make_command(make, supports_jobs, jobs), target_dir)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check the V1 inventory and optionally build web and Qt targets."
    )
    parser.add_argument("--web", action="store_true", help="run npm ci and the web production build")
    parser.add_argument("--qt", action="store_true", help="generate and compile Qt server/client release builds")
    parser.add_argument(
        "--db-schema",
        action="store_true",
        help="build and run the clean/restart SQLite schema regression test",
    )
    parser.add_argument(
        "--v1-smoke",
        action="store_true",
        help="build a headless server and run critical V1 TCP smoke flows",
    )
    parser.add_argument(
        "--password-hash",
        action="store_true",
        help="verify Argon2id registration and legacy password hash migration",
    )
    parser.add_argument(
        "--performance",
        action="store_true",
        help="build the headless server and record the V1 performance scenario",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help="run inventory, web, database schema, password hash, V1 smoke, performance, and Qt verification",
    )
    parser.add_argument("--skip-npm-ci", action="store_true", help="reuse installed web dependencies")
    parser.add_argument("--jobs", type=int, default=max(1, min(os.cpu_count() or 1, 4)))
    parser.add_argument("--performance-clients", type=int, default=8)
    parser.add_argument("--performance-messages", type=int, default=100)
    parser.add_argument("--performance-warmup", type=int, default=20)
    parser.add_argument(
        "--performance-output",
        type=Path,
        help="JSON result path (default: <build-root>/v1-performance.json)",
    )
    parser.add_argument(
        "--build-root",
        type=Path,
        default=ROOT / "build" / "m0" / platform.system().lower(),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    build_root = args.build_root if args.build_root.is_absolute() else ROOT / args.build_root

    print(f"[M0] platform: {platform.platform()}")
    verify_inventory()
    if args.web or args.all:
        verify_web(args.skip_npm_ci)
    if args.db_schema or args.all:
        verify_database_schema(args.jobs, build_root)
    if args.password_hash or args.all:
        verify_password_hash(args.jobs, build_root)
    if args.v1_smoke or args.all:
        verify_v1_smoke(args.jobs, build_root)
    if args.performance or args.all:
        performance_output = args.performance_output or build_root / "v1-performance.json"
        if not performance_output.is_absolute():
            performance_output = ROOT / performance_output
        verify_performance(
            args.jobs,
            build_root,
            performance_output,
            args.performance_clients,
            args.performance_messages,
            args.performance_warmup,
        )
    if args.qt or args.all:
        verify_qt(args.jobs, build_root)
    if not (
        args.web
        or args.db_schema
        or args.password_hash
        or args.v1_smoke
        or args.performance
        or args.qt
        or args.all
    ):
        print(
            "[M0] inventory-only verification complete; "
            "use --web, --db-schema, --password-hash, --v1-smoke, --performance, "
            "--qt, or --all "
            "for builds/tests"
        )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, subprocess.CalledProcessError) as error:
        print(f"[M0] verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)

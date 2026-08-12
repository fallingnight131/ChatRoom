#!/usr/bin/env python3
"""Run repeatable repository inventory and product build verification."""

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


def verify_java() -> None:
    backend = ROOT / "Backend"
    wrapper = backend / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.exists():
        raise RuntimeError(f"Gradle wrapper not found: {wrapper}")
    run([str(wrapper), "--no-daemon", "check"], backend)


def verify_postgres() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_postgres.py")], ROOT)


def verify_protocol_bindings(skip_install: bool) -> None:
    npm = command_path("npm", "npm.cmd")
    backend = ROOT / "Backend"
    typescript = backend / "protocol-v2" / "typescript"
    wrapper = backend / ("gradlew.bat" if os.name == "nt" else "gradlew")
    web_generated = ROOT / "WebClient" / "src" / "protocol" / "v2" / "generated"
    web_before = {
        path.name: path.read_bytes()
        for path in sorted(web_generated.glob("*_pb.ts"))
    }
    if not skip_install:
        run([npm, "ci"], typescript)
    run([str(wrapper), "--no-daemon", ":protocol-v2:generateClientBindings"], backend)
    generated = typescript / "generated"
    for relative in (
        "chat-v2.desc",
        "cpp/chat/v2/envelope.pb.cc",
        "cpp/chat/v2/envelope.pb.h",
        "cpp/chat/v2/control.pb.cc",
        "cpp/chat/v2/control.pb.h",
        "cpp/chat/v2/authentication.pb.cc",
        "cpp/chat/v2/authentication.pb.h",
        "cpp/chat/v2/attachment.pb.cc",
        "cpp/chat/v2/attachment.pb.h",
        "cpp/chat/v2/conversation.pb.cc",
        "cpp/chat/v2/conversation.pb.h",
        "cpp/chat/v2/messaging.pb.cc",
        "cpp/chat/v2/messaging.pb.h",
        "typescript/chat/v2/envelope_pb.ts",
        "typescript/chat/v2/control_pb.ts",
        "typescript/chat/v2/authentication_pb.ts",
        "typescript/chat/v2/attachment_pb.ts",
        "typescript/chat/v2/conversation_pb.ts",
        "typescript/chat/v2/messaging_pb.ts",
    ):
        artifact = generated / relative
        if not artifact.is_file() or artifact.stat().st_size == 0:
            raise RuntimeError(f"generated V2 binding missing or empty: {artifact}")
    web_after = {
        path.name: path.read_bytes()
        for path in sorted(web_generated.glob("*_pb.ts"))
    }
    if not web_after or web_before != web_after:
        raise RuntimeError(
            "committed Web V2 bindings are stale; regenerate and commit them")
    run([npm, "test"], typescript)
    cmake = command_path("cmake")
    ctest = command_path("ctest")
    cpp_source = backend / "protocol-v2" / "cpp"
    cpp_build = ROOT / "build" / "m3" / "v2-cpp-binding"
    run(
        [cmake, "-S", str(cpp_source), "-B", str(cpp_build),
         "-DCMAKE_BUILD_TYPE=Release"],
        ROOT,
    )
    run(
        [cmake, "--build", str(cpp_build), "--target", "v2_cpp_envelope_test",
         "--parallel"],
        ROOT,
    )
    run([ctest, "--test-dir", str(cpp_build), "--output-on-failure"], ROOT)


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


def build_qt_unit_test(
    jobs: int, build_root: Path, name: str
) -> Path:
    qmake = select_qmake()
    make, supports_jobs = select_make(qmake)
    target_dir = build_root / f"qt-{name.lower()}"
    target_dir.mkdir(parents=True, exist_ok=True)
    run([qmake, str(ROOT / "Tests" / f"{name}.pro"), "CONFIG+=release"], target_dir)
    run(make_command(make, supports_jobs, jobs), target_dir)
    return locate_executable(target_dir, name)


def run_qt_client_unit_tests(jobs: int, build_root: Path) -> None:
    for name in (
        "HttpUploadTransportTest",
        "HttpDownloadTransportTest",
        "MessageModelTest",
        "NetworkReconnectTest",
        "LocalConversationRepositoryTest",
        "AttachmentOutboxServiceTest",
        "OutgoingMessageServiceTest",
        "ConversationSyncServiceTest",
        "V1HistoryPageAdapterTest",
        "UpdateManifestSignatureVerifierTest",
    ):
        run([str(build_qt_unit_test(jobs, build_root, name))], ROOT)


def verify_v1_smoke(jobs: int, build_root: Path) -> None:
    executable = build_headless_server(jobs, build_root, "v1-smoke-server")
    run_qt_client_unit_tests(jobs, build_root)
    for test_script in (
        "v1_smoke_test.py",
        "v1_authorization_test.py",
        "v1_transport_limits_test.py",
        "v1_input_validation_test.py",
        "v1_authentication_abuse_test.py",
        "v1_room_message_reliability_test.py",
        "v1_friend_message_reliability_test.py",
        "v1_recall_replay_test.py",
        "v1_administrative_deletion_reliability_test.py",
        "v1_http_upload_test.py",
        "v1_file_forward_test.py",
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


def build_migration_cli() -> Path:
    backend = ROOT / "Backend"
    wrapper = backend / ("gradlew.bat" if os.name == "nt" else "gradlew")
    run([str(wrapper), "--no-daemon", ":migration-cli:installDist"], backend)
    executable_name = "migration-cli.bat" if os.name == "nt" else "migration-cli"
    executable = (
        backend / "migration-cli" / "build" / "install" / "migration-cli"
        / "bin" / executable_name
    )
    if not executable.is_file():
        raise RuntimeError(f"migration CLI executable not found: {executable}")
    return executable


def verify_v1_identity_restore(jobs: int, build_root: Path) -> None:
    executable = build_headless_server(jobs, build_root, "v1-identity-restore-server")
    migration_cli = build_migration_cli()
    run(
        [
            sys.executable,
            str(ROOT / "Tests" / "v1_identity_restore_rehearsal.py"),
            "--server",
            str(executable),
            "--migration-cli",
            str(migration_cli),
            "--evidence",
            str(build_root / "v1-identity-restore-evidence.json"),
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

    run([sys.executable, str(ROOT / "Tests" / "qt_attachment_source_test.py")], ROOT)
    run_qt_client_unit_tests(jobs, build_root)

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
        description="Check the repository inventory and optionally build product targets."
    )
    parser.add_argument("--web", action="store_true", help="run npm ci and the web production build")
    parser.add_argument("--qt", action="store_true", help="generate and compile Qt server/client release builds")
    parser.add_argument("--java", action="store_true", help="compile and test the Java V2 workspace")
    parser.add_argument(
        "--postgres",
        action="store_true",
        help="run V2 migrations against a disposable local PostgreSQL cluster",
    )
    parser.add_argument(
        "--protocol-bindings",
        action="store_true",
        help="generate and verify V2 C++ and TypeScript client bindings",
    )
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
        "--v1-identity-restore",
        action="store_true",
        help="rehearse a timed V1 identity backup, restore, login, and history check",
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
        help=(
            "run inventory, Web, Java, database schema, password hash, V1 smoke, "
            "V1 identity restore, performance, and Qt verification"
        ),
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
    if args.java or args.all:
        verify_java()
    if args.postgres or args.all:
        verify_postgres()
    if args.protocol_bindings or args.all:
        verify_protocol_bindings(args.skip_npm_ci)
    if args.db_schema or args.all:
        verify_database_schema(args.jobs, build_root)
    if args.password_hash or args.all:
        verify_password_hash(args.jobs, build_root)
    if args.v1_smoke or args.all:
        verify_v1_smoke(args.jobs, build_root)
    if args.v1_identity_restore or args.all:
        verify_v1_identity_restore(args.jobs, build_root)
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
        or args.java
        or args.protocol_bindings
        or args.postgres
        or args.db_schema
        or args.password_hash
        or args.v1_smoke
        or args.v1_identity_restore
        or args.performance
        or args.qt
        or args.all
    ):
        print(
            "[M0] inventory-only verification complete; "
            "use --web, --java, --postgres, --protocol-bindings, --db-schema, --password-hash, "
            "--v1-smoke, --v1-identity-restore, --performance, "
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

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


def verify_redis_tls() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_redis_tls.py")], ROOT)


def verify_redis_outage() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_redis_outage.py")], ROOT)


def verify_gateway_load_balancer_config() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_haproxy_gateway.py")], ROOT)


def verify_gateway_load_balancer_runtime() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_haproxy_runtime.py")], ROOT)


def verify_gateway_crash(output: Path | None) -> None:
    command = [sys.executable, str(ROOT / "tools" / "verify_gateway_crash.py")]
    if output is not None:
        command.extend(["--output", str(output)])
    run(command, ROOT)


def verify_gateway_forced_drain() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_gateway_forced_drain.py")], ROOT)


def verify_gateway_load_balancer_reload() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_haproxy_reload.py")], ROOT)


def verify_gateway_load_balancer_certificate_rotation() -> None:
    run([sys.executable,
         str(ROOT / "tools" / "verify_haproxy_certificate_rotation.py")], ROOT)


def verify_gateway_backend_ca_rotation() -> None:
    run([sys.executable,
         str(ROOT / "tools" / "verify_haproxy_backend_ca_rotation.py")], ROOT)


def verify_gateway_mixed_version() -> None:
    run([sys.executable, str(ROOT / "tools" / "verify_gateway_mixed_version.py")], ROOT)


def verify_gateway_multi_edge(output: Path | None, workload: str) -> None:
    command = [sys.executable, str(ROOT / "tools" / "verify_haproxy_multi_edge.py")]
    if output is not None:
        command.extend(["--output", str(output), "--workload", workload])
    run(command, ROOT)


def verify_gateway_multi_edge_ladder(output: Path) -> None:
    run([
        sys.executable,
        str(ROOT / "tools" / "verify_haproxy_multi_edge_ladder.py"),
        "--output", str(output),
    ], ROOT)


def verify_linux_resident_memory() -> None:
    run([sys.executable,
         str(ROOT / "tools" / "verify_linux_resident_memory.py")], ROOT)


def verify_java_performance(args: argparse.Namespace, output: Path) -> None:
    run([
        sys.executable,
        str(ROOT / "tools" / "verify_java_performance.py"),
        "--output", str(output),
        "--warmup", str(args.java_performance_warmup),
        "--append", str(args.java_performance_append),
        "--retry", str(args.java_performance_retry),
        "--concurrent", str(args.java_performance_concurrent),
        "--concurrency", str(args.java_performance_concurrency),
        "--history", str(args.java_performance_history),
        "--payload-bytes", str(args.java_performance_payload_bytes),
    ], ROOT)


def verify_java_gateway_performance(args: argparse.Namespace, output: Path) -> None:
    command = [
        sys.executable,
        str(ROOT / "tools" / "verify_java_gateway_performance.py"),
        "--output", str(output),
        "--warmup", str(args.java_gateway_performance_warmup),
        "--messages", str(args.java_gateway_performance_messages),
        "--payload-bytes", str(args.java_gateway_performance_payload_bytes),
        "--receivers", str(args.java_gateway_performance_receivers),
        "--active-conversations", str(args.java_gateway_performance_active_conversations),
        "--reconnect-rounds", str(args.java_gateway_performance_reconnect_rounds),
        "--reconnect-batch-size", str(args.java_gateway_performance_reconnect_batch_size),
        "--reconnect-batch-interval-millis",
        str(args.java_gateway_performance_reconnect_batch_interval_millis),
        "--slow-consumer-max-messages",
        str(args.java_gateway_performance_slow_consumer_max_messages),
        "--postgres-saturation-senders",
        str(args.java_gateway_performance_postgres_saturation_senders),
    ]
    if args.java_gateway_performance_postgres_outage:
        command.append("--postgres-outage")
    run(command, ROOT)


def verify_protocol_bindings(skip_install: bool) -> None:
    run([sys.executable, str(
        ROOT / "Tests" / "message_forwarding_activation_policy_test.py")], ROOT)
    run([sys.executable, str(
        ROOT / "Tests" / "message_search_activation_policy_test.py")], ROOT)
    run([sys.executable, str(
        ROOT / "Tests" / "account_blocking_activation_policy_test.py")], ROOT)
    npm = command_path("npm", "npm.cmd")
    backend = ROOT / "Backend"
    typescript = backend / "protocol-v2" / "typescript"
    wrapper = backend / ("gradlew.bat" if os.name == "nt" else "gradlew")
    web_generated = ROOT / "WebClient" / "src" / "protocol" / "v2" / "generated"
    windows_cpp_generated = ROOT / "Client" / "protocol" / "v2" / "generated" / "chat" / "v2"
    web_before = {
        path.name: path.read_bytes()
        for path in sorted(web_generated.glob("*_pb.ts"))
    }
    windows_cpp_before = {
        path.name: path.read_bytes()
        for path in sorted(windows_cpp_generated.glob("*.pb.*"))
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
        "cpp/chat/v2/contact.pb.cc",
        "cpp/chat/v2/contact.pb.h",
        "cpp/chat/v2/authentication.pb.cc",
        "cpp/chat/v2/authentication.pb.h",
        "cpp/chat/v2/attachment.pb.cc",
        "cpp/chat/v2/attachment.pb.h",
        "cpp/chat/v2/conversation.pb.cc",
        "cpp/chat/v2/conversation.pb.h",
        "cpp/chat/v2/device_management.pb.cc",
        "cpp/chat/v2/device_management.pb.h",
        "cpp/chat/v2/messaging.pb.cc",
        "cpp/chat/v2/messaging.pb.h",
        "typescript/chat/v2/envelope_pb.ts",
        "typescript/chat/v2/control_pb.ts",
        "typescript/chat/v2/contact_pb.ts",
        "typescript/chat/v2/authentication_pb.ts",
        "typescript/chat/v2/attachment_pb.ts",
        "typescript/chat/v2/conversation_pb.ts",
        "typescript/chat/v2/device_management_pb.ts",
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
    windows_cpp_after = {
        path.name: path.read_bytes()
        for path in sorted(windows_cpp_generated.glob("*.pb.*"))
    }
    if not windows_cpp_after or windows_cpp_before != windows_cpp_after:
        raise RuntimeError(
            "committed Windows C++ V2 bindings are stale; regenerate and commit them")
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
         "v2_windows_device_management_protocol_test", "v2_windows_session_protocol_test",
         "v2_windows_messaging_protocol_test",
         "v2_windows_attachment_protocol_test",
         "v2_windows_message_search_protocol_test",
         "v2_windows_account_block_protocol_test",
         "v2_windows_account_block_view_model_test",
         "v2_windows_account_block_directory_view_model_test",
         "v2_windows_message_search_view_model_test",
         "v2_windows_conversation_directory_protocol_test",
         "v2_windows_conversation_participant_protocol_test",
         "v2_windows_conversation_participant_view_model_test",
         "v2_windows_mention_composer_test",
         "v2_windows_messaging_application_test",
         "v2_windows_messaging_controller_test",
         "v2_windows_device_management_transport_test",
         "v2_windows_device_management_controller_test", "--parallel"],
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


def verify_cmake_headless(jobs: int, build_root: Path) -> None:
    cmake = command_path("cmake")
    target_dir = build_root / "cmake-headless-server"
    configure = [
        cmake,
        "-S", str(ROOT),
        "-B", str(target_dir),
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCHATROOM_BUILD_HEADLESS_SERVER=ON",
        "-DBUILD_TESTING=ON",
    ]
    sodium_root = os.environ.get("SODIUM_ROOT")
    if sodium_root:
        configure.append(f"-DSODIUM_ROOT={sodium_root}")
    run(configure, ROOT)
    run([
        cmake, "--build", str(target_dir), "--config", "Release",
        "--target", "ChatServerHeadless", "DatabaseSchemaTest", "PasswordMigrationTest",
        "MessageModelTest", "LocalConversationRepositoryTest", "OutgoingMessageServiceTest",
        "V2LocalMessageRepositoryTest",
        "V2WindowsMessagingViewModelTest",
        "WindowsLocalePreferenceRepositoryTest",
        "WindowsBandwidthPreferenceRepositoryTest",
        "V2WindowsMentionComposerTest",
        "ConversationSyncServiceTest", "AttachmentOutboxServiceTest", "V1HistoryPageAdapterTest",
        "HttpUploadTransportTest", "HttpDownloadTransportTest", "NetworkReconnectTest",
        "NetworkTlsPolicyTest",
        "UpdateManifestSignatureVerifierTest", "UpdateManifestDecisionPolicyTest",
        "UpdateStateRepositoryTest", "UpdateManifestApplicationServiceTest",
        "UpdateManifestFetchTransportTest", "UpdateInstallerDownloadTransportTest",
        "UpdateInstallerTrustVerifierTest",
        "UpdatePreparationApplicationServiceTest", "UpdateCheckApplicationServiceTest",
        "UpdateLauncherResultTest", "UpdateLifecycleRepositoryTest",
        "WindowsUpdateStartupServiceTest", "UpdateLauncherCommandTest",
        "WindowsUpdateHandoffApplicationServiceTest", "WindowsUpdateInstallCoordinatorTest",
        "WindowsUpdateProductConfigurationTest",
        "WindowsUpdateProductConfigurationEnabledTest",
        "DeviceManagementViewModelTest",
        "DeviceManagementApplicationServiceTest",
        "WindowsV2ProductConfigurationTest",
        "WindowsV2ProductConfigurationEnabledTest",
        "WindowsDeviceIdentityRepositoryTest",
        "--parallel", str(jobs),
    ], ROOT)
    ctest = command_path("ctest")
    run([
        ctest, "--test-dir", str(target_dir), "--build-config", "Release",
        "--output-on-failure", "-R", "^(v1_|m4_|m6_)",
    ], ROOT)
    executable = locate_executable(target_dir, "ChatServerHeadless")
    run([
        sys.executable,
        str(ROOT / "Tests" / "v1_http_health_test.py"),
        "--server", str(executable),
    ], ROOT)


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
        "V2LocalMessageRepositoryTest",
        "V2WindowsMessagingViewModelTest",
        "WindowsConversationRuntimeLocalizationTest",
        "V2WindowsMentionComposerTest",
        "V2WindowsMessagingPanelTest",
        "V2WindowsForwardTargetDialogTest",
        "V2WindowsAccountBlockDialogTest",
        "V2WindowsConversationDialogTest",
        "WindowsProfileBandwidthTest",
        "WindowsLoginLocalizationTest",
        "WindowsEmojiPickerLocalizationTest",
        "WindowsForwardSelectLocalizationTest",
        "AttachmentOutboxServiceTest",
        "OutgoingMessageServiceTest",
        "ConversationSyncServiceTest",
        "V1HistoryPageAdapterTest",
        "UpdateManifestSignatureVerifierTest",
        "UpdateManifestDecisionPolicyTest",
        "UpdateInstallerTrustVerifierTest",
        "UpdateStateRepositoryTest",
        "UpdateManifestApplicationServiceTest",
        "UpdateInstallerDownloadTransportTest",
        "UpdatePreparationApplicationServiceTest",
        "UpdateManifestFetchTransportTest",
        "UpdateCheckApplicationServiceTest",
        "UpdateLauncherCommandTest",
        "WindowsUpdateHandoffApplicationServiceTest",
        "UpdateLauncherResultTest",
        "UpdateLifecycleRepositoryTest",
        "WindowsUpdateInstallCoordinatorTest",
        "WindowsUpdateStartupServiceTest",
        "WindowsUpdateProductConfigurationTest",
        "WindowsUpdateProductConfigurationEnabledTest",
        "WindowsClientInstanceGuardTest",
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
        "v1_http_health_test.py",
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
        ("update-launcher", ROOT / "UpdaterLauncher" / "UpdaterLauncher.pro"),
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
    parser.add_argument(
        "--cmake-headless",
        action="store_true",
        help="configure, build, and health-test the Qt V1 headless server with CMake",
    )
    parser.add_argument("--java", action="store_true", help="compile and test the Java V2 workspace")
    parser.add_argument(
        "--postgres",
        action="store_true",
        help="run V2 migrations against a disposable local PostgreSQL cluster",
    )
    parser.add_argument(
        "--redis-tls",
        action="store_true",
        help="verify Redis routing against disposable TLS and scoped ACL",
    )
    parser.add_argument(
        "--redis-outage",
        action="store_true",
        help="verify product readiness and durable delivery across Redis restart",
    )
    parser.add_argument(
        "--gateway-load-balancer-config",
        action="store_true",
        help="render and syntax-check the pinned HAProxy gateway edge policy",
    )
    parser.add_argument(
        "--gateway-load-balancer-runtime",
        action="store_true",
        help="verify real HAProxy WSS forwarding and readiness withdrawal",
    )
    parser.add_argument(
        "--gateway-crash",
        action="store_true",
        help="verify HAProxy and client recovery after abrupt gateway process loss",
    )
    parser.add_argument(
        "--gateway-crash-output",
        type=Path,
        help="write bounded HAProxy crash/reconnect performance evidence",
    )
    parser.add_argument(
        "--gateway-forced-drain",
        action="store_true",
        help="verify HAProxy withdrawal and bounded forced WebSocket drain",
    )
    parser.add_argument(
        "--gateway-load-balancer-reload",
        action="store_true",
        help="verify HAProxy master-worker reload with established WSS tunnels",
    )
    parser.add_argument(
        "--gateway-load-balancer-certificate-rotation",
        action="store_true",
        help="verify HAProxy frontend certificate rotation with established WSS",
    )
    parser.add_argument(
        "--gateway-backend-ca-rotation",
        action="store_true",
        help="verify HAProxy backend CA expand-migrate-contract rotation",
    )
    parser.add_argument(
        "--gateway-mixed-version",
        action="store_true",
        help="build two committed gateway revisions and verify rolling compatibility",
    )
    parser.add_argument(
        "--gateway-multi-edge",
        action="store_true",
        help="verify client recovery after one of two HAProxy edges fails",
    )
    parser.add_argument(
        "--gateway-multi-edge-output",
        type=Path,
        help="write bounded dual-edge reconnect evidence (requires --gateway-multi-edge)",
    )
    parser.add_argument(
        "--gateway-multi-edge-workload",
        choices=("step-12", "step-24", "step-48"),
        default="step-12",
        help="fixed dual-edge reconnect workload profile",
    )
    parser.add_argument(
        "--gateway-multi-edge-ladder-output",
        type=Path,
        help="run three repetitions of all fixed reconnect profiles",
    )
    parser.add_argument(
        "--linux-rss",
        action="store_true",
        help="run the pinned Linux /proc resident-memory provider gate",
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
        "--java-performance",
        action="store_true",
        help="record the disposable PostgreSQL Java V2 messaging baseline",
    )
    parser.add_argument(
        "--java-gateway-performance",
        action="store_true",
        help="record the disposable PostgreSQL Java V2 TLS/WSS gateway baseline",
    )
    parser.add_argument(
        "--all",
        action="store_true",
        help=(
            "run inventory, Web, Java, database schema, password hash, CMake headless, "
            "V1 smoke, V1 identity restore, V1/Java adapter/gateway performance, "
            "and Qt verification"
        ),
    )
    parser.add_argument("--skip-npm-ci", action="store_true", help="reuse installed web dependencies")
    parser.add_argument("--jobs", type=int, default=max(1, min(os.cpu_count() or 1, 4)))
    parser.add_argument("--performance-clients", type=int, default=8)
    parser.add_argument("--performance-messages", type=int, default=100)
    parser.add_argument("--performance-warmup", type=int, default=20)
    parser.add_argument("--java-performance-warmup", type=int, default=100)
    parser.add_argument("--java-performance-append", type=int, default=500)
    parser.add_argument("--java-performance-retry", type=int, default=200)
    parser.add_argument("--java-performance-concurrent", type=int, default=500)
    parser.add_argument("--java-performance-concurrency", type=int, default=8)
    parser.add_argument("--java-performance-history", type=int, default=200)
    parser.add_argument("--java-performance-payload-bytes", type=int, default=256)
    parser.add_argument(
        "--java-performance-output",
        type=Path,
        help="JSON result path (default: <build-root>/java-v2-postgres-performance.json)",
    )
    parser.add_argument("--java-gateway-performance-warmup", type=int, default=20)
    parser.add_argument("--java-gateway-performance-messages", type=int, default=200)
    parser.add_argument("--java-gateway-performance-payload-bytes", type=int, default=256)
    parser.add_argument("--java-gateway-performance-receivers", type=int, default=1)
    parser.add_argument("--java-gateway-performance-active-conversations", type=int, default=1)
    parser.add_argument("--java-gateway-performance-reconnect-rounds", type=int, default=0)
    parser.add_argument("--java-gateway-performance-reconnect-batch-size", type=int, default=0)
    parser.add_argument(
        "--java-gateway-performance-reconnect-batch-interval-millis", type=int, default=0)
    parser.add_argument(
        "--java-gateway-performance-slow-consumer-max-messages", type=int, default=0)
    parser.add_argument(
        "--java-gateway-performance-postgres-saturation-senders", type=int, default=0)
    parser.add_argument(
        "--java-gateway-performance-postgres-outage", action="store_true")
    parser.add_argument(
        "--java-gateway-performance-output",
        type=Path,
        help="JSON result path (default: <build-root>/java-v2-gateway-performance.json)",
    )
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
    args = parser.parse_args()
    if args.gateway_crash_output is not None and not args.gateway_crash:
        parser.error("--gateway-crash-output requires --gateway-crash")
    if args.gateway_multi_edge_output is not None and not args.gateway_multi_edge:
        parser.error("--gateway-multi-edge-output requires --gateway-multi-edge")
    if (args.gateway_multi_edge_workload != "step-12"
            and args.gateway_multi_edge_output is None):
        parser.error("non-default --gateway-multi-edge-workload requires output evidence")
    if (args.gateway_multi_edge_ladder_output is not None
            and args.gateway_multi_edge):
        parser.error("ladder output and single multi-edge verification are mutually exclusive")
    return args


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
    if args.redis_tls:
        verify_redis_tls()
    if args.redis_outage:
        verify_redis_outage()
    if args.gateway_load_balancer_config:
        verify_gateway_load_balancer_config()
    if args.gateway_load_balancer_runtime:
        verify_gateway_load_balancer_runtime()
    if args.gateway_crash:
        verify_gateway_crash(args.gateway_crash_output)
    if args.gateway_forced_drain:
        verify_gateway_forced_drain()
    if args.gateway_load_balancer_reload:
        verify_gateway_load_balancer_reload()
    if args.gateway_load_balancer_certificate_rotation:
        verify_gateway_load_balancer_certificate_rotation()
    if args.gateway_backend_ca_rotation:
        verify_gateway_backend_ca_rotation()
    if args.gateway_mixed_version:
        verify_gateway_mixed_version()
    if args.gateway_multi_edge:
        verify_gateway_multi_edge(
            args.gateway_multi_edge_output, args.gateway_multi_edge_workload)
    if args.gateway_multi_edge_ladder_output is not None:
        verify_gateway_multi_edge_ladder(args.gateway_multi_edge_ladder_output)
    if args.linux_rss:
        verify_linux_resident_memory()
    if args.protocol_bindings or args.all:
        verify_protocol_bindings(args.skip_npm_ci)
    if args.db_schema or args.all:
        verify_database_schema(args.jobs, build_root)
    if args.cmake_headless or args.all:
        verify_cmake_headless(args.jobs, build_root)
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
    if args.java_performance or args.all:
        java_performance_output = args.java_performance_output or (
            build_root / "java-v2-postgres-performance.json")
        if not java_performance_output.is_absolute():
            java_performance_output = ROOT / java_performance_output
        verify_java_performance(args, java_performance_output)
    if args.java_gateway_performance or args.all:
        gateway_output = args.java_gateway_performance_output or (
            build_root / "java-v2-gateway-performance.json")
        if not gateway_output.is_absolute():
            gateway_output = ROOT / gateway_output
        verify_java_gateway_performance(args, gateway_output)
    if args.qt or args.all:
        verify_qt(args.jobs, build_root)
    if not (
        args.web
        or args.java
        or args.protocol_bindings
        or args.postgres
        or args.redis_tls
        or args.redis_outage
        or args.gateway_load_balancer_config
        or args.gateway_load_balancer_runtime
        or args.gateway_crash
        or args.gateway_forced_drain
        or args.gateway_load_balancer_reload
        or args.gateway_load_balancer_certificate_rotation
        or args.gateway_backend_ca_rotation
        or args.gateway_mixed_version
        or args.gateway_multi_edge
        or args.gateway_multi_edge_ladder_output is not None
        or args.linux_rss
        or args.db_schema
        or args.cmake_headless
        or args.password_hash
        or args.v1_smoke
        or args.v1_identity_restore
        or args.performance
        or args.java_performance
        or args.java_gateway_performance
        or args.qt
        or args.all
    ):
        print(
            "[M0] inventory-only verification complete; "
            "use --web, --java, --postgres, --redis-tls, --redis-outage, "
            "--gateway-load-balancer-config, "
            "--gateway-load-balancer-runtime, "
            "--gateway-crash, "
            "--gateway-forced-drain, "
            "--gateway-load-balancer-reload, "
            "--gateway-load-balancer-certificate-rotation, "
            "--gateway-backend-ca-rotation, "
            "--gateway-mixed-version, "
            "--gateway-multi-edge, "
            "--gateway-multi-edge-ladder-output, "
            "--linux-rss, "
            "--protocol-bindings, "
            "--db-schema, --password-hash, "
            "--cmake-headless, --v1-smoke, --v1-identity-restore, --performance, "
            "--java-performance, --java-gateway-performance, "
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

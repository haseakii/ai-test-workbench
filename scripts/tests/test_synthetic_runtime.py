"""Offline safety checks for the synthetic service's single operation entry.

No Java application, database, migration, container or managed process is started.
The only bound socket is a temporary local listener owned by a test.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock
import zipfile


SCRIPT = Path(__file__).resolve().parents[1] / "synthetic_runtime.py"
spec = importlib.util.spec_from_file_location("synthetic_runtime_under_test", SCRIPT)
runtime = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = runtime
spec.loader.exec_module(runtime)


class SyntheticRuntimeSafetyTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.secret = "synthetic-do-not-echo-credential-4f37"
        self.config = {
            "SPRING_PROFILES_ACTIVE": "synthetic",
            "WB_DB_URL": "jdbc:mysql://127.0.0.1:13306/workbench_platform?sslMode=VERIFY_CA&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
            "WB_DB_USER": "workbench",
            "WB_DB_PASSWORD": self.secret,
            "WB_BLOB_ROOT": str(self.root / "blobs"),
            "WB_PORT": "18080",
        }

    def write_config(self, value=None, *, raw=None, mode=0o600):
        file = self.root / "runtime-private.json"
        file.write_text(raw if raw is not None else json.dumps(self.config if value is None else value))
        file.chmod(mode)
        return file

    def packaged_fixture(self):
        """Structure-only ZIP: no executable class or production artifact is run."""
        file = self.root / "test-only.jar"
        with zipfile.ZipFile(file, "w") as archive:
            for name in [
                "BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeProbe.class",
                "BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeReadiness.class",
                "BOOT-INF/classes/static/index.html",
                "BOOT-INF/classes/db/runner-migration/V1__runner_journal.sql",
            ]:
                archive.writestr(name, "offline-test-only")
        return file

    def migration_configs(self):
        files = []
        for database in ["workbench_platform", "workbench_runner"]:
            file = self.root / (database + "-migration-private.json")
            file.write_text(json.dumps({
                "WB_DB_URL": self.config["WB_DB_URL"].replace("/workbench_platform?", "/" + database + "?"),
                "WB_MIGRATION_USER": "workbench_migration",
                "WB_MIGRATION_PASSWORD": self.secret,
            }))
            file.chmod(0o600)
            files.append(file)
        return ["--platform-migration-config", str(files[0]), "--runner-migration-config", str(files[1])]

    def invoke(self, action, *, directory=None, extra=()):
        output = io.StringIO()
        arguments = [action, "--runtime-dir", str(directory or self.root), "--config", str(self.write_config()), *extra]
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()):
            result = runtime.main(arguments)
        replies = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertTrue(replies)
        self.assertTrue(all(isinstance(reply, dict) and "ok" in reply for reply in replies))
        reply = replies[-1]
        self.assertNotIn(self.secret, output.getvalue())
        return result, reply

    def assert_private_failure(self, function, *args, **kwargs):
        with self.assertRaises(runtime.RuntimeFailure) as raised:
            function(*args, **kwargs)
        self.assertTrue(raised.exception.code)
        self.assertNotIn(self.secret, str(raised.exception))
        self.assertNotIn(self.config["WB_DB_URL"], str(raised.exception))
        return raised.exception

    def test_private_runtime_and_migration_configs_are_accepted(self):
        file = self.write_config()
        self.assertEqual(runtime.load_config(file), self.config)
        migration = {
            "WB_DB_URL": self.config["WB_DB_URL"],
            "WB_MIGRATION_USER": "workbench_migration",
            "WB_MIGRATION_PASSWORD": self.secret,
        }
        self.assertEqual(runtime.load_config(self.write_config(migration), migration=True), migration)

    def test_group_or_world_readable_config_is_rejected(self):
        for mode in [0o640, 0o604, 0o644, 0o666]:
            with self.subTest(mode=oct(mode)):
                self.assert_private_failure(runtime.load_config, self.write_config(mode=mode))

    def test_duplicate_json_password_key_is_rejected(self):
        raw = json.dumps(self.config)[:-1] + ', "WB_DB_PASSWORD": "different-credential"}'
        self.assert_private_failure(runtime.load_config, self.write_config(raw=raw))

    def test_malformed_json_does_not_echo_input(self):
        self.assert_private_failure(runtime.load_config, self.write_config(raw='{"WB_DB_PASSWORD": "' + self.secret))

    def test_missing_required_configuration_is_rejected(self):
        for missing in ["WB_DB_URL", "WB_DB_USER", "WB_DB_PASSWORD", "WB_BLOB_ROOT", "SPRING_PROFILES_ACTIVE"]:
            with self.subTest(missing=missing):
                changed = {key: value for key, value in self.config.items() if key != missing}
                self.assert_private_failure(runtime.load_config, self.write_config(changed))

    def test_non_string_password_does_not_reach_process_environment(self):
        for password in [None, 42, {"secret": self.secret}, [self.secret]]:
            with self.subTest(kind=type(password).__name__):
                self.assert_private_failure(runtime.load_config, self.write_config(self.config | {"WB_DB_PASSWORD": password}))

    def test_platform_target_accepts_only_its_synthetic_scope(self):
        runtime.validate_target(self.config)
        changes = [
            {"SPRING_PROFILES_ACTIVE": "production"},
            {"SPRING_PROFILES_ACTIVE": "synthetic,production"},
            {"WB_DB_USER": "root"},
            {"WB_DB_USER": "workbench_migration"},
            {"WB_DB_USER": "workbench_runner"},
            {"WB_DB_USER": "unapproved_other_account"},
            {"WB_DB_URL": "jdbc:mysql://localhost:13306/workbench_platform"},
            {"WB_DB_URL": "jdbc:mysql://192.0.2.1:13306/workbench_platform"},
            {"WB_DB_URL": "jdbc:mysql://127.0.0.1:13306/business"},
            {"WB_DB_URL": "jdbc:mysql://127.0.0.1:13306/workbench_runner"},
            {"WB_DB_URL": "jdbc:mysql://intruder:secret@127.0.0.1:13306/workbench_platform"},
            {"WB_DB_URL": "jdbc:mysql://127.0.0.1:13306/workbench_platform#fragment"},
        ]
        for changed in changes:
            with self.subTest(field=list(changed)[0], value=list(changed.values())[0]):
                self.assert_private_failure(runtime.validate_target, self.config | changed)

    def test_parent_environment_cannot_override_service_or_leak_privileged_passwords(self):
        hostile = {
            "PATH": os.environ.get("PATH", ""),
            "HTTP_PROXY": "http://proxy.example.invalid:8080",
            "HTTPS_PROXY": "http://proxy.example.invalid:8080",
            "SPRING_APPLICATION_JSON": '{"server":{"address":"0.0.0.0"}}',
            "SPRING_CONFIG_LOCATION": "/untrusted/config.properties",
            "SPRING_DATASOURCE_PASSWORD": "other-private-password",
            "SERVER_ADDRESS": "0.0.0.0",
            "JAVA_TOOL_OPTIONS": "-javaagent:/untrusted/agent.jar",
            "JDK_JAVA_OPTIONS": "-Dspring.profiles.active=production",
            "_JAVA_OPTIONS": "-Dserver.address=0.0.0.0",
            "MYSQL_PWD": "root-password",
            "WB_MIGRATION_USER": "root",
            "WB_MIGRATION_PASSWORD": "migration-secret",
            "WB_RUNNER_PASSWORD": "runner-secret",
            "WB_RUNNER_DB_URL": "jdbc:mysql://127.0.0.1:13306/workbench_runner",
        }
        with mock.patch.dict(os.environ, hostile, clear=True):
            environment = runtime.runtime_env(self.config)
        for key in set(hostile) - {"PATH", "HTTP_PROXY", "HTTPS_PROXY"}:
            with self.subTest(key=key):
                self.assertNotIn(key, environment)
        for key, value in self.config.items():
            self.assertEqual(environment[key], value)
        self.assertEqual(environment.get("HTTP_PROXY"), hostile["HTTP_PROXY"])
        self.assertEqual(environment.get("HTTPS_PROXY"), hostile["HTTPS_PROXY"])

    def test_migration_process_receives_only_explicit_migration_credentials(self):
        migration = {
            "WB_DB_URL": self.config["WB_DB_URL"],
            "WB_MIGRATION_USER": "workbench_migration",
            "WB_MIGRATION_PASSWORD": self.secret,
        }
        with mock.patch.dict(os.environ, self.config | {"WB_RUNNER_PASSWORD": "runner-secret"}, clear=False):
            environment = runtime.runtime_env(migration, migration=True)
        for key in ["WB_DB_PASSWORD", "WB_RUNNER_PASSWORD"]:
            self.assertNotIn(key, environment)
        for key, value in migration.items():
            self.assertEqual(environment[key], value)

    def test_occupied_port_is_reported_without_stopping_owner(self):
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            with mock.patch.object(runtime.os, "kill") as kill:
                self.assert_private_failure(runtime.check_port, port)
            kill.assert_not_called()
            self.assertEqual(listener.getsockname()[1], port)

    def test_missing_pid_is_not_a_managed_service(self):
        self.assertIsNone(runtime.process_identity(2_147_483_647))
        with mock.patch.object(runtime.os, "kill") as kill:
            self.assertIsNone(runtime.owned_process(self.root, "/not-required-for-stopped-service/java"))
        kill.assert_not_called()

    def test_recently_closed_owned_tcp_connection_does_not_block_restart(self):
        with socket.socket() as server, socket.socket() as client:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(('127.0.0.1', 0)); server.listen()
            port = server.getsockname()[1]
            client.connect(('127.0.0.1', port))
            accepted, _ = server.accept()
            accepted.shutdown(socket.SHUT_WR); accepted.close()
            self.assertEqual(client.recv(1), b'')
            client.close(); server.close()
            runtime.check_port(port)

    def test_listener_with_reuseaddr_still_blocks_a_second_service(self):
        with socket.socket() as server:
            server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind(('127.0.0.1', 0)); server.listen()
            self.assert_private_failure(runtime.check_port, server.getsockname()[1])

    def test_unverified_live_pid_metadata_never_authorizes_a_signal(self):
        (self.root / "platform.pid").write_text(str(os.getpid()))
        (self.root / "platform-process.json").write_text(json.dumps({
            "pid": os.getpid(), "java": "/invalid/java", "artifactJar": "/invalid/platform.jar",
            "sourceJar": "/invalid/platform.jar", "sha256": "0" * 64,
            "startedAt": "2026-01-01T00:00:00Z", "startTicks": "0", "bootId": "incorrect",
        }))
        with mock.patch.object(runtime.os, "kill") as kill:
            self.assert_private_failure(runtime.owned_process, self.root, "/invalid/java")
        kill.assert_not_called()
        self.assertTrue((self.root / "platform.pid").exists())

    def test_failed_doctor_does_not_create_a_runtime_directory(self):
        absent = self.root / "must-remain-absent"
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(io.StringIO()), \
                mock.patch.object(runtime, "java_executable", return_value="/offline/java"):
            result = runtime.main(["doctor", "--runtime-dir", str(absent), "--config", str(self.root / "missing-private.json")])
        self.assertNotEqual(result, 0)
        self.assertFalse(absent.exists())
        reply = json.loads(output.getvalue())
        self.assertFalse(reply["ok"])
        self.assertNotIn(self.secret, output.getvalue())

    def test_legacy_cli_flags_remain_in_help(self):
        for action in ["start", "stop", "restart", "migrate"]:
            output = io.StringIO()
            with self.subTest(action=action), contextlib.redirect_stdout(output):
                with self.assertRaises(SystemExit) as raised:
                    runtime.main([action, "--help"])
                self.assertEqual(raised.exception.code, 0)
                for flag in ["--runtime-dir", "--config", "--java-home", "--jar", "--migration-location"]:
                    self.assertIn(flag, output.getvalue())

    def test_existing_operation_lock_rejects_a_second_operation(self):
        def competing_operation():
            with runtime.operation_lock(self.root):
                self.fail("A concurrent operation must not enter the locked section")

        with runtime.operation_lock(self.root):
            failure = self.assert_private_failure(competing_operation)
        self.assertEqual(failure.code, "OPERATION_BUSY")
        with runtime.operation_lock(self.root):
            pass  # An ordinary completed operation releases its lock.

    def test_database_failure_during_doctor_leaves_runtime_and_blob_directories_absent(self):
        absent = self.root / "doctor-must-not-create"
        jar = self.packaged_fixture()
        with mock.patch.object(runtime, "java_executable", return_value="/offline/java"), \
                mock.patch.object(runtime, "check_port"), \
                mock.patch.object(runtime, "database_probe", side_effect=runtime.RuntimeFailure("DB_UNAVAILABLE", "Database unavailable")), \
                mock.patch.object(runtime.subprocess, "Popen") as spawn:
            code, reply = self.invoke("doctor", directory=absent, extra=["--jar", str(jar)])
        self.assertNotEqual(code, 0)
        self.assertEqual(reply["code"], "DB_UNAVAILABLE")
        self.assertFalse(absent.exists())
        self.assertFalse(Path(self.config["WB_BLOB_ROOT"]).exists())
        spawn.assert_not_called()

    def test_status_does_not_trust_a_listener_owned_by_another_process(self):
        with mock.patch.object(runtime, "java_executable", return_value="/offline/java"), \
                mock.patch.object(runtime, "owned_process", return_value={"pid": 24680, "sha256": "0" * 64}), \
                mock.patch.object(runtime, "listener_owned", return_value=False), \
                mock.patch.object(runtime, "build_opener") as network:
            code, reply = self.invoke("status")
        self.assertNotEqual(code, 0)
        self.assertEqual(reply["code"], "LISTENER_UNCONFIRMED")
        self.assertNotEqual(reply.get("state"), "ready")
        network.assert_not_called()

    def test_java_version_probe_cannot_load_agents_from_inherited_environment(self):
        home = self.root / "test-jdk"
        (home / "bin").mkdir(parents=True)
        (home / "bin" / "java").write_text("not-executed")
        result = SimpleNamespace(returncode=0, stderr=b'openjdk version "21.0.9"', stdout=b"")
        hostile = {"JAVA_TOOL_OPTIONS": "-javaagent:/untrusted/agent.jar", "JDK_JAVA_OPTIONS": "-javaagent:/untrusted/agent.jar", "_JAVA_OPTIONS": "-Dserver.address=0.0.0.0"}
        with mock.patch.dict(os.environ, hostile), \
                mock.patch.object(runtime.subprocess, "run", return_value=result) as command:
            runtime.java_executable(home)
        self.assertIn("env", command.call_args.kwargs, "A version probe must not inherit ambient JVM startup options")
        for key in hostile:
            self.assertNotIn(key, command.call_args.kwargs["env"])

    def test_inactive_synthetic_identity_does_not_count_as_ready(self):
        body = {"identityMode": "synthetic_only", "alias": "admin", "state": "inactive"}
        response = io.BytesIO(json.dumps(body).encode())
        response.status = 200
        response.headers = {"Cache-Control": "no-store"}
        opener = mock.Mock()
        opener.open.return_value = response
        with mock.patch.object(runtime, "listener_owned", return_value=True), \
                mock.patch.object(runtime, "build_opener", return_value=opener):
            failure = self.assert_private_failure(runtime.http_health, 24680, 18080)
        self.assertEqual(failure.code, "HTTP_NOT_READY")

    def test_ready_marker_requires_the_original_nonce_pid_port_and_synthetic_mode(self):
        nonce = "00000000-0000-4000-8000-00000000f00d"
        metadata = {"pid": 24680, "readyNonce": nonce}
        (self.root / "platform-process.json").write_text(json.dumps(metadata))
        file = self.root / ("platform-ready-" + nonce + ".json")
        valid = {"pid": 24680, "nonce": nonce, "port": 18080, "identityMode": "synthetic_only"}
        file.write_text(json.dumps(valid))
        self.assertTrue(runtime.ready_marker_matches(self.root, 24680, 18080))
        for changed in [
            {"nonce": "00000000-0000-4000-8000-00000000badd"},
            {"pid": 24681},
            {"port": 18081},
            {"identityMode": "production"},
        ]:
            with self.subTest(field=list(changed)[0]):
                file.write_text(json.dumps(valid | changed))
                self.assertFalse(runtime.ready_marker_matches(self.root, 24680, 18080))
        file.write_text(json.dumps(valid))
        (self.root / "platform-process.json").write_text(json.dumps(metadata | {"pid": 24681}))
        self.assertFalse(runtime.ready_marker_matches(self.root, 24680, 18080))

    def test_up_refuses_migration_while_a_managed_service_is_running(self):
        jar = self.packaged_fixture()
        extra = ["--jar", str(jar), *self.migration_configs()]
        with mock.patch.object(runtime, "java_executable", return_value="/offline/java"), \
                mock.patch.object(runtime, "owned_process", return_value={"pid": 24680, "sha256": "0" * 64}), \
                mock.patch.object(runtime, "database_probe") as probe, \
                mock.patch.object(runtime, "migrate") as migration, \
                mock.patch.object(runtime, "start") as start:
            code, reply = self.invoke("up", extra=extra)
        self.assertNotEqual(code, 0)
        self.assertEqual(reply["code"], "RUNNING_INITIALIZE_REFUSED")
        probe.assert_not_called()
        migration.assert_not_called()
        start.assert_not_called()

    def test_up_checks_database_migrates_both_scopes_checks_schema_then_starts(self):
        jar = self.packaged_fixture()
        extra = ["--jar", str(jar), *self.migration_configs()]
        operations = []

        def probe(java, artifact, config, stage):
            operations.append(("probe", stage))
            return {"ok": True}

        def migrate(java, artifact, config, directory, location):
            database = "runner" if "/workbench_runner?" in config["WB_DB_URL"] else "platform"
            operations.append(("migrate", database, location))

        def start(java, source, directory, config):
            operations.append(("start",))
            return {"state": "ready", "pid": 24680, "sha256": "0" * 64}

        with mock.patch.object(runtime, "java_executable", return_value="/offline/java"), \
                mock.patch.object(runtime, "owned_process", return_value=None), \
                mock.patch.object(runtime, "check_port"), \
                mock.patch.object(runtime, "database_probe", side_effect=probe), \
                mock.patch.object(runtime, "migrate", side_effect=migrate), \
                mock.patch.object(runtime, "start", side_effect=start):
            code, reply = self.invoke("up", extra=extra)
        self.assertEqual(code, 0)
        self.assertEqual(reply["state"], "ready")
        self.assertEqual(operations, [
            ("probe", "database"),
            ("migrate", "platform", None),
            ("migrate", "runner", "classpath:db/runner-migration"),
            ("probe", "schema"),
            ("start",),
        ])

    def test_failed_migration_preserves_data_and_does_not_start_or_echo_private_log(self):
        jar = self.packaged_fixture()
        extra = ["--jar", str(jar), *self.migration_configs()]
        preserved = self.root / "existing-data-do-not-delete"
        preserved.write_bytes(b"pre-existing synthetic test data")

        def failed_process(*args, **kwargs):
            kwargs["stdout"].write(("private JDBC failure password=" + self.secret + "\n").encode())
            return SimpleNamespace(returncode=1)

        with mock.patch.object(runtime, "java_executable", return_value="/offline/java"), \
                mock.patch.object(runtime, "owned_process", return_value=None), \
                mock.patch.object(runtime, "check_port"), \
                mock.patch.object(runtime, "database_probe", return_value={"ok": True}), \
                mock.patch.object(runtime.subprocess, "run", side_effect=failed_process) as migration_process, \
                mock.patch.object(runtime, "start") as start:
            code, reply = self.invoke("up", extra=extra)
        self.assertNotEqual(code, 0)
        self.assertEqual(reply["code"], "MIGRATION_FAILED")
        self.assertEqual(preserved.read_bytes(), b"pre-existing synthetic test data")
        start.assert_not_called()
        self.assertEqual(migration_process.call_count, 1)
        log = self.root / "migration-platform.log"
        self.assertIn(self.secret, log.read_text())
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)

    def test_stop_with_unverified_metadata_never_opens_or_signals_a_pidfd(self):
        (self.root / "platform.pid").write_text(str(os.getpid()))
        (self.root / "platform-process.json").write_text(json.dumps({"pid": os.getpid() + 1}))
        with mock.patch.object(runtime.os, "pidfd_open", create=True) as descriptor, \
                mock.patch.object(runtime.signal, "pidfd_send_signal", create=True) as signal:
            failure = self.assert_private_failure(runtime.stop, self.root, "/offline/java")
        self.assertEqual(failure.code, "PROCESS_OWNERSHIP_UNCERTAIN")
        descriptor.assert_not_called()
        signal.assert_not_called()
        self.assertTrue((self.root / "platform.pid").exists())

    def test_stop_rechecks_identity_after_pidfd_open_before_signalling(self):
        first = {"pid": 24680, "startTicks": 1, "bootId": "same-boot"}
        replaced = first | {"startTicks": 2}
        with mock.patch.object(runtime, "owned_process", side_effect=[first, replaced]), \
                mock.patch.object(runtime.os, "pidfd_open", return_value=123, create=True), \
                mock.patch.object(runtime.os, "close") as close, \
                mock.patch.object(runtime.signal, "pidfd_send_signal", create=True) as signal:
            failure = self.assert_private_failure(runtime.stop, self.root, "/offline/java")
        self.assertEqual(failure.code, "PROCESS_OWNERSHIP_UNCERTAIN")
        signal.assert_not_called()
        close.assert_called_once_with(123)

    def test_unconfirmed_start_keeps_its_identity_without_restarting_or_signalling(self):
        jar = self.packaged_fixture()
        process = mock.Mock(pid=24680)
        process.poll.return_value = None
        clock = iter(range(0, 200, 20))
        with mock.patch.object(runtime, "check_port"), \
                mock.patch.object(runtime.subprocess, "Popen", return_value=process) as spawn, \
                mock.patch.object(runtime, "process_identity", return_value={"startTicks": 12, "bootId": "test-boot"}), \
                mock.patch.object(runtime, "http_health", side_effect=runtime.RuntimeFailure("HTTP_NOT_READY", "Not ready")), \
                mock.patch.object(runtime.time, "monotonic", side_effect=lambda: next(clock)), \
                mock.patch.object(runtime.time, "sleep"), \
                mock.patch.object(runtime.signal, "pidfd_send_signal", create=True) as signal:
            failure = self.assert_private_failure(runtime.start, "/offline/java", jar, self.root, self.config)
        self.assertEqual(failure.code, "START_NOT_CONFIRMED")
        spawn.assert_called_once()
        signal.assert_not_called()
        self.assertEqual((self.root / "platform.pid").read_text(), "24680")
        metadata = json.loads((self.root / "platform-process.json").read_text())
        self.assertEqual((metadata["pid"], metadata["startTicks"], metadata["bootId"]), (24680, 12, "test-boot"))
        self.assertTrue(Path(metadata["artifactJar"]).is_file())


class DistributionRuntimeLayoutTest(unittest.TestCase):
    """Extract a structure-only review bundle; never launch Java or contact a DB."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle = self.root / "解包 空格目录" / "workbench"
        self.other_cwd = self.root / "其他 工作目录"
        self.other_cwd.mkdir()
        archive = self.root / "offline-review-bundle.zip"
        with zipfile.ZipFile(archive, "w") as output:
            output.write(SCRIPT, "workbench/scripts/synthetic_runtime.py")
            for kind in ["runtime", "migration-platform", "migration-runner"]:
                source = SCRIPT.parents[1] / "backend/config" / (kind + ".example.json")
                output.write(source, "workbench/config/" + source.name)
            output.writestr("workbench/app/platform-server.jar", b"structure-only-not-executable")
        with zipfile.ZipFile(archive) as source:
            source.extractall(self.bundle.parent)
        self.entry = self.bundle / "scripts/synthetic_runtime.py"

    def invoke(self, *arguments):
        environment = os.environ.copy()
        environment["PATH"] = ""
        environment.pop("JAVA_HOME", None)
        return subprocess.run([sys.executable, str(self.entry), *arguments], cwd=self.other_cwd,
                              env=environment, capture_output=True, text=True, timeout=5)

    def test_distribution_templates_resolve_from_chinese_spaced_unpacked_directory(self):
        for kind in ["runtime", "migration-platform", "migration-runner"]:
            with self.subTest(kind=kind):
                result = self.invoke("template", "--template-kind", kind)
                self.assertEqual(result.returncode, 0, result.stdout)
                expected = json.loads((self.bundle / "config" / (kind + ".example.json")).read_text())
                self.assertEqual(json.loads(result.stdout), expected)
                self.assertEqual(result.stderr, "")

    def test_distribution_help_is_available_without_java_or_configuration(self):
        result = self.invoke("--help")
        self.assertEqual(result.returncode, 0)
        for action in ["template", "doctor", "status", "up", "start", "stop", "restart", "migrate"]:
            self.assertIn(action, result.stdout)
        self.assertEqual(result.stderr, "")

    def test_default_artifact_uses_distribution_app_path_without_source_tree(self):
        with mock.patch.object(runtime, "ROOT", self.bundle):
            args = runtime.parser().parse_args(["doctor"])
        self.assertEqual(Path(args.jar), self.bundle / "app/platform-server.jar")
        self.assertFalse((self.bundle / "backend").exists())

    def test_explicit_artifact_override_is_not_rewritten_in_distribution(self):
        alternate = self.root / "维护者 提供的制品.jar"
        with mock.patch.object(runtime, "ROOT", self.bundle):
            args = runtime.parser().parse_args(["doctor", "--jar", str(alternate)])
        self.assertEqual(args.jar, str(alternate))

    def test_repository_layout_default_and_templates_are_preserved(self):
        repository = self.root / "源码 工作目录"
        (repository / "backend/config").mkdir(parents=True)
        template = {"offline_test": "only"}
        (repository / "backend/config/runtime.example.json").write_text(json.dumps(template))
        output = io.StringIO()
        with mock.patch.object(runtime, "ROOT", repository), contextlib.redirect_stdout(output):
            args = runtime.parser().parse_args(["doctor"])
            result = runtime.main(["template"])
        self.assertEqual(Path(args.jar), repository / "backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar")
        self.assertEqual(result, 0)
        self.assertEqual(json.loads(output.getvalue()), template)

    def test_missing_java_in_unpacked_distribution_leaves_runtime_absent(self):
        absent = self.root / "不得 创建运行目录"
        result = self.invoke("doctor", "--runtime-dir", str(absent), "--config", str(self.root / "absent-private.json"),
                             "--java-home", str(self.root / "missing-jdk"))
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)["code"], "JAVA_MISSING")
        self.assertEqual(result.stderr, "")
        self.assertFalse(absent.exists())

    def test_missing_distribution_template_reports_repairable_error(self):
        (self.bundle / "config/runtime.example.json").unlink()
        result = self.invoke("template")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)["code"], "TEMPLATE_UNAVAILABLE")
        self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()

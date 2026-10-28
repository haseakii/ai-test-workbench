"""Real Jar classloading regressions; private harmless canary, no Spring server or DB."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import synthetic_runtime as runtime


class ReleaseJavaOfflineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        home = os.environ.get('JAVA_HOME')
        if not home or not (Path(home) / 'bin/javac').is_file():
            raise unittest.SkipTest('Requires the already configured Java 21 JDK; no tools are installed by tests')
        cls.java = Path(home) / 'bin/java'
        version = subprocess.run([str(cls.java), '-version'], env=runtime.runtime_env({}), capture_output=True)
        if b'version "21.' not in version.stderr + version.stdout:
            raise unittest.SkipTest('Requires the existing Java 21 toolchain')
        cls.jar = ROOT / 'backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar'
        if not cls.jar.is_file():
            raise RuntimeError('Build the complete Jar before running release Java regressions')
        cls.temporary = tempfile.TemporaryDirectory(prefix='loader 回归 with spaces ')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        source = cls.directory / 'source/io/aitest/workbench/bootstrap'
        source.mkdir(parents=True)
        for name in ['RuntimeLauncher', 'RuntimeProbe', 'Migrate']:
            (source / (name + '.java')).write_text('package io.aitest.workbench.bootstrap; public final class ' + name
                + ' { public static void main(String[] args) { System.out.println("HARMLESS_LOADER_CANARY"); } }')
        classes = cls.directory / 'classes'; classes.mkdir()
        result = subprocess.run([str(Path(home) / 'bin/javac'), '-d', str(classes), *map(str, source.glob('*.java'))], capture_output=True)
        if result.returncode:
            raise RuntimeError('Private harmless canary compilation failed')
        archive = cls.directory / 'canary.jar'
        with zipfile.ZipFile(archive, 'w') as output:
            for file in classes.rglob('*.class'):
                output.write(file, file.relative_to(classes).as_posix())
        (cls.directory / 'loader.properties').write_text('loader.path=' + archive.as_uri() + '\n')

    def launch(self, target, argument, protected):
        command = [str(self.java)]
        if protected:
            command += ['-Dloader.config.location=classpath:/workbench-disabled-loader.properties', '-Dloader.home=' + str(self.jar.parent)]
        command += ['-Dloader.main=io.aitest.workbench.bootstrap.' + target, '-cp', str(self.jar),
                    'org.springframework.boot.loader.launch.PropertiesLauncher']
        if argument:
            command.append(argument)
        return subprocess.run(command, cwd=self.directory, env=runtime.runtime_env({}), capture_output=True, text=True, timeout=10)

    def test_unprotected_loader_canary_positive_control_and_protected_windows_equivalent_help(self):
        for target, argument in [('RuntimeLauncher', 'help'), ('RuntimeProbe', 'database'), ('Migrate', None)]:
            with self.subTest(target=target):
                before = self.launch(target, argument, False)
                self.assertIn('HARMLESS_LOADER_CANARY', before.stdout)
                after = self.launch(target, argument, True)
                self.assertNotIn('HARMLESS_LOADER_CANARY', after.stdout + after.stderr)
        actual = self.launch('RuntimeLauncher', 'help', True)
        self.assertEqual(actual.returncode, 0)
        self.assertIn('Windows', actual.stdout)
        # The .cmd itself cannot run on Linux; check it uses the same protected launch.
        self.assertIn('-Dloader.config.location=classpath:/workbench-disabled-loader.properties',
                      (ROOT / 'backend/distribution/Workbench.cmd').read_text())

    def test_actual_python_probe_rejects_cwd_loader_override_before_any_database_connection(self):
        previous = Path.cwd()
        try:
            os.chdir(self.directory)
            with self.assertRaises(runtime.RuntimeFailure) as raised:
                runtime.database_probe(str(self.java), self.jar, {}, 'database')
            self.assertEqual(raised.exception.code, 'CONFIG_MISSING')
        finally:
            os.chdir(previous)

    def test_actual_python_migration_rejects_cwd_loader_override_without_credentials(self):
        previous = Path.cwd()
        try:
            os.chdir(self.directory)
            with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(runtime.RuntimeFailure) as raised:
                runtime.migrate(str(self.java), self.jar, {}, self.directory)
            self.assertEqual(raised.exception.code, 'MIGRATION_FAILED')
            log = (self.directory / 'migration-platform.log').read_text()
            self.assertNotIn('HARMLESS_LOADER_CANARY', log)
            self.assertIn('Explicit migration configuration required', log)
        finally:
            os.chdir(previous)

    def test_real_jar_launcher_missing_configuration_is_redacted_and_does_not_create_runtime(self):
        absent = self.directory / 'absent runtime 中文'
        command = [str(self.java), '-Dloader.config.location=classpath:/workbench-disabled-loader.properties',
                   '-Dloader.home=' + str(self.jar.parent), '-Dloader.main=io.aitest.workbench.bootstrap.RuntimeLauncher',
                   '-cp', str(self.jar), 'org.springframework.boot.loader.launch.PropertiesLauncher', 'doctor',
                   '--bundle-dir', str(self.directory), '--runtime-dir', str(absent)]
        result = subprocess.run(command, cwd=self.directory, env=runtime.runtime_env({}), capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)['code'], 'CONFIG_MISSING')
        self.assertFalse(absent.exists())
        self.assertNotIn('HARMLESS_LOADER_CANARY', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()

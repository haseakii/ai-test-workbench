"""Offline contracts for CMD dependency selection, not a Windows execution test.

The small parser below reads the actual candidate statements. Fake files model only
selection/exclusion; no CMD, Java, MySQL, downloader or initializer is invoked.
"""
import re
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
COMMAND = ROOT / 'backend/distribution/Workbench.cmd'


class CmdSelectionContract:
    def __init__(self, text):
        self.portable = re.search(r'^if exist "(?P<path>%~dp0runtime\\java\\bin\\java\.exe)" goto (?P<label>\w+)$', text, re.M)
        self.home = re.search(r'^if defined JAVA_HOME if exist "(?P<path>%JAVA_HOME%\\bin\\java\.exe)" \(\n'
                              r'(?P<body>.*?)^\)$', text, re.M | re.S)
        self.path = re.search(r'^for %%J in \(java\.exe\) do set "WB_LAUNCH_JAVA=%%~\$PATH:J"$', text, re.M)
        if not self.portable or not self.home or not self.path:
            raise AssertionError('CMD selection contract changed; review the actual Windows entry, not a guessed implementation')
        if not self.portable.start() < self.home.start() < self.path.start():
            raise AssertionError('Portable candidate must be assessed before compatibility fallbacks')
        if 'set "WB_LAUNCH_JAVA=' + self.home['path'] + '"' not in self.home['body'] or 'goto java_selected' not in self.home['body']:
            raise AssertionError('JAVA_HOME branch must select only its existing quoted Java executable')
        if 'if not defined WB_LAUNCH_JAVA goto java_missing' not in text:
            raise AssertionError('Missing PATH Java must return a clear unavailable decision')

    def decide(self, bundle, home, path_entries):
        portable = bundle / self.portable['path'].removeprefix('%~dp0').replace('\\', '/')
        if portable.is_file():
            return self.portable['label'], None
        if home is not None:
            existing = home / self.home['path'].removeprefix('%JAVA_HOME%\\').replace('\\', '/')
            if existing.is_file():
                return 'java_selected', existing
        for entry in path_entries:
            existing = entry / 'java.exe'
            if existing.is_file():
                return 'java_selected', existing
        return 'java_missing', None


class PortableCmdDetectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = COMMAND.read_text(encoding='utf-8')
        cls.model = CmdSelectionContract(cls.text)

    def test_requirements_output_is_reachable_before_jar_or_java_dependencies(self):
        branch = self.text.index('if /I "%~1"=="requirements" goto requirements')
        self.assertLess(branch, self.text.index('if not exist "%~dp0app\\platform-server.jar"'))
        self.assertLess(branch, self.text.index('if exist "%~dp0runtime\\java\\bin\\java.exe"'))
        body = self.text.split(':requirements\n', 1)[1]
        for line in body.splitlines():
            self.assertTrue(line.startswith(('echo ', 'if "%~1"=="" pause', 'exit /b ')), line)
        self.assertIn('exit /b %WB_REQUIREMENTS_EXIT%', body)
        self.assertIn('set "WB_REQUIREMENTS_EXIT=0"', self.text[:branch])

    def test_actual_selection_statements_reject_portable_before_compatibility_fallbacks(self):
        with tempfile.TemporaryDirectory(prefix='便携 CMD with spaces ') as temporary:
            root = Path(temporary)
            bundle = root / '发行 目录'
            home = root / '已有 Java home'
            path_one, path_two = root / 'PATH 第一项', root / 'PATH 第二项'
            portable, home_java, first_java, second_java = (
                bundle / 'runtime/java/bin/java.exe', home / 'bin/java.exe', path_one / 'java.exe', path_two / 'java.exe')
            for path in [portable, home_java, first_java, second_java]:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b'NOT AN EXECUTABLE - offline selection fixture only')
            with self.subTest(case='portable is only detected and refused even when compatibility Java exists'):
                self.assertEqual(self.model.decide(bundle, home, [path_one, path_two]), ('portable_unverified', None))
            portable.unlink()
            with self.subTest(case='valid JAVA_HOME precedes PATH'):
                self.assertEqual(self.model.decide(bundle, home, [path_one, path_two]), ('java_selected', home_java))
            home_java.unlink()
            with self.subTest(case='invalid JAVA_HOME falls back to first PATH executable'):
                self.assertEqual(self.model.decide(bundle, home, [path_one, path_two]), ('java_selected', first_java))
            first_java.unlink()
            with self.subTest(case='remaining PATH entry'):
                self.assertEqual(self.model.decide(bundle, None, [path_one, path_two]), ('java_selected', second_java))
            second_java.unlink()
            with self.subTest(case='no Java is not guessed'):
                self.assertEqual(self.model.decide(bundle, home, [path_one, path_two]), ('java_missing', None))

    def test_unverified_portable_candidate_has_no_execution_or_marker_bypass(self):
        body = self.text.split(':portable_unverified\n', 1)[1].split(':java_missing\n', 1)[0]
        self.assertIn('[DEPENDENCY_UNVERIFIED]', body)
        self.assertIn('set "WB_REQUIREMENTS_EXIT=2"', body)
        self.assertTrue(body.rstrip().endswith('goto requirements'))
        self.assertNotIn('WB_LAUNCH_JAVA=', body)
        self.assertNotRegex(body.lower(), r'(?m)^\s*(?:call|start|"[^\n]*java\.exe")')
        self.assertNotRegex(self.text.lower(), r'(?m)^\s*if (?:not )?exist "[^\n]*(?:approved|consent|allow)[^\n]*"')
        missing = self.text.split(':java_missing\n', 1)[1].split(':requirements\n', 1)[0]
        self.assertIn('[JAVA_MISSING]', missing)
        self.assertIn('set "WB_REQUIREMENTS_EXIT=2"', missing)
        self.assertTrue(missing.rstrip().endswith('goto requirements'))

    def test_jvm_and_loader_hardening_precedes_only_java_invocation(self):
        invocation = self.text.index('"%WB_LAUNCH_JAVA%" "-Dworkbench.bundle=%~dp0."')
        for variable in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH']:
            self.assertLess(self.text.index('set "' + variable + '="'), invocation)
        self.assertLess(self.text.index("('set LOADER_ 2^>nul')"), invocation)
        self.assertIn('setlocal DisableDelayedExpansion', self.text[:invocation])
        self.assertIn('-Dloader.config.location=classpath:/workbench-disabled-loader.properties', self.text)
        self.assertIn('"-Dloader.home=%~dp0app" -cp "%~dp0app\\platform-server.jar"', self.text)
        self.assertEqual(len(re.findall(r'^"%WB_LAUNCH_JAVA%" ', self.text, re.M)), 1)

    def test_entry_does_not_download_install_initialize_or_change_system_settings(self):
        executable_lines = [line.strip().lower() for line in self.text.splitlines()
                            if line.strip() and not line.lstrip().lower().startswith(('echo ', 'rem ', '::', ':'))]
        forbidden = r'^(?:call\s+)?(?:curl(?:\.exe)?|wget(?:\.exe)?|bitsadmin(?:\.exe)?|powershell(?:\.exe)?|pwsh(?:\.exe)?|certutil(?:\.exe)?|setx(?:\.exe)?|msiexec(?:\.exe)?|sc(?:\.exe)?|netsh(?:\.exe)?|mysqld(?:\.exe)?|mysql(?:\.exe)?)(?:\s|$)'
        for line in executable_lines:
            self.assertNotRegex(line, forbidden)
            self.assertNotRegex(line, r'^set\s+"?path=')
            self.assertNotIn('--initialize-insecure', line)
            self.assertNotIn('executionpolicy', line)
        body = self.text.split(':requirements\n', 1)[1]
        for phrase in ['Java 21', 'MySQL 8.4', 'NTFS', 'MSVC 14', '未捆绑', '不自动安装', '不关闭 TLS',
                       'https://adoptium.net/temurin/releases/', 'https://dev.mysql.com/downloads/mysql/8.4.html',
                       'https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist', 'Workbench.cmd setup',
                       'Linux 离线检查不代表 Windows']:
            self.assertIn(phrase, body)
        for line in self.text.splitlines():
            if 'https://' in line:
                self.assertTrue(line.startswith('echo '), 'Official source references must remain plain text, not requests')

    def test_cmd_remains_utf8_crlf_and_preserves_quoted_unicode_spaced_paths(self):
        data = COMMAND.read_bytes()
        self.assertFalse(data.startswith(b'\xef\xbb\xbf'))
        self.assertGreater(data.count(b'\r\n'), 0)
        self.assertEqual(data.count(b'\n'), data.count(b'\r\n'))
        self.assertEqual([byte for byte in data if byte < 32 and byte not in (9, 10, 13)], [])
        data.decode('utf-8')
        for token in ['"%~dp0runtime\\java\\bin\\java.exe"', '"%JAVA_HOME%\\bin\\java.exe"',
                      '"%WB_LAUNCH_JAVA%"', '"%~dp0app\\platform-server.jar"']:
            self.assertIn(token, self.text)


if __name__ == '__main__':
    unittest.main()

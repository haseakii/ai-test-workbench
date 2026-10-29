"""Black-box release bundle checks: temporary files only, no JVM, DB or network."""
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[2]
PACKAGER = ROOT / 'scripts/release_bundle.py'
REAL_JAR = ROOT / 'backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar'
EXPECTED_PAYLOAD = {
    'app/platform-server.jar',
    'config/runtime.example.json',
    'config/migration-platform.example.json',
    'config/migration-runner.example.json',
    'scripts/synthetic_runtime.py',
    'scripts/release_manifest.py',
    'README.md',
    'COMPATIBILITY.md',
    'LICENSE',
    'Workbench.cmd',
    'setup/dependencies.json',
    'setup/bootstrap-actions.json',
    'setup/bootstrap.sql.template',
    'setup/initialize.ini.template',
    'setup/my.ini.template',
}
EXPECTED_FILES = EXPECTED_PAYLOAD | {'manifest.json', 'SHA256SUMS'}


class ReleaseBundleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='bundle 测试 with spaces ')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.root = Path(cls.temporary.name)
        cls.jar = cls.root / 'build input 私有' / 'platform-server.jar'
        cls.jar.parent.mkdir()
        shutil.copyfile(REAL_JAR, cls.jar)
        cls.canary = 'release-test-private-credential-CANARY-59d72'
        (cls.jar.parent / 'runtime-private.json').write_text(json.dumps({'password': cls.canary}))
        (cls.jar.parent / '.env').write_text('PRIVATE=' + cls.canary)
        for name in ['node_modules', '.m2', 'test-data', '.runtime', 'target']:
            folder = cls.jar.parent / name
            folder.mkdir()
            (folder / 'must-not-ship.txt').write_text(cls.canary)
        cls.output = cls.root / 'baseline output'
        cls.invoke_static('build', '--jar', str(cls.jar), '--output-dir', str(cls.output), success=True)
        cls.bundle, cls.archive = cls.distribution(cls.output)

    @classmethod
    def invoke_static(cls, *arguments, success=None, cwd=None, script=None):
        result = subprocess.run([sys.executable, str(script or PACKAGER), *arguments],
                                cwd=cwd or ROOT, capture_output=True, text=True, timeout=90)
        messages = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
        if not messages:
            raise AssertionError('release command did not return structured JSON: ' + result.stderr[-300:])
        reply = messages[-1]
        if success is not None:
            if reply.get('ok') is not success or result.returncode != (0 if success else 2):
                raise AssertionError('unexpected release command outcome: ' + json.dumps(reply))
        if cls.canary in result.stdout + result.stderr:
            raise AssertionError('release command exposed private canary')
        return reply, result

    @staticmethod
    def distribution(output):
        directories = [p for p in output.iterdir() if p.is_dir()]
        archives = list(output.glob('*.zip'))
        if len(directories) != 1 or len(archives) != 1:
            raise AssertionError('build must produce one release directory and one ZIP')
        return directories[0], archives[0]

    def setUp(self):
        self.case = tempfile.TemporaryDirectory(prefix='case-', dir=self.root)
        self.addCleanup(self.case.cleanup)
        self.directory = Path(self.case.name)

    def clone_bundle(self):
        target = self.directory / self.bundle.name
        shutil.copytree(self.bundle, target)
        return target

    def invoke(self, *arguments, **kwargs):
        return self.invoke_static(*arguments, **kwargs)

    def reject_directory(self, bundle):
        reply, result = self.invoke('verify', '--directory', str(bundle), success=False)
        self.assertTrue(reply.get('code'))
        self.assertNotIn(self.canary, result.stdout + result.stderr)

    def test_known_good_directory_and_archive_verify(self):
        self.invoke('verify', '--directory', str(self.bundle), success=True)
        self.invoke('verify', '--archive', str(self.archive), success=True)

    def test_legacy_v1_payload_is_still_verified_without_setup_files(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        import release_manifest
        bundle = self.clone_bundle()
        shutil.rmtree(bundle / 'setup')  # Only this test's copied fixture.
        manifest = json.loads((bundle / 'manifest.json').read_bytes())
        manifest['format'] = release_manifest.LEGACY_FORMAT
        manifest['files'] = [row for row in manifest['files'] if row['path'] in release_manifest.BASE_PAYLOAD]
        raw = release_manifest.canonical(manifest)
        (bundle / 'manifest.json').write_bytes(raw)
        (bundle / 'SHA256SUMS').write_bytes(release_manifest.checksum_text(manifest['files'], raw))
        self.assertEqual(self.invoke('verify', '--directory', str(bundle), success=True)[0]['format'], release_manifest.LEGACY_FORMAT)
        (bundle / 'setup').mkdir()
        self.invoke('verify', '--directory', str(bundle), success=False)

    def test_manifest_independently_matches_exact_allowlisted_bytes(self):
        manifest = json.loads((self.bundle / 'manifest.json').read_text())
        self.assertEqual(manifest['format'], 'ai-test-workbench-distribution-v2')
        actual = {p.relative_to(self.bundle).as_posix() for p in self.bundle.rglob('*') if p.is_file()}
        self.assertEqual(actual, EXPECTED_FILES)
        records = manifest['files']
        self.assertEqual([row['path'] for row in records], sorted(EXPECTED_PAYLOAD))
        for row in records:
            content = (self.bundle / row['path']).read_bytes()
            self.assertEqual(row['bytes'], len(content))
            self.assertEqual(row['sha256'], hashlib.sha256(content).hexdigest())
        text = json.dumps(manifest, ensure_ascii=False)
        self.assertIn('synthetic', text)
        self.assertNotIn(str(self.jar), text)
        self.assertNotIn(str(self.root), text)

    def test_checksums_and_archive_sidecar_match_actual_bytes(self):
        checksum_rows = (self.bundle / 'SHA256SUMS').read_text().splitlines()
        expected = []
        for name in sorted(EXPECTED_PAYLOAD | {'manifest.json'}):
            expected.append(hashlib.sha256((self.bundle / name).read_bytes()).hexdigest() + '  ' + name)
        self.assertEqual(checksum_rows, expected)
        sidecar = self.archive.with_name(self.archive.name + '.sha256')
        self.assertEqual(sidecar.read_text().strip(), hashlib.sha256(self.archive.read_bytes()).hexdigest() + '  ' + self.archive.name)

    def test_rebuilding_same_inputs_produces_identical_zip(self):
        first, second = self.directory / 'first', self.directory / 'second'
        self.invoke('build', '--jar', str(self.jar), '--output-dir', str(first), success=True)
        self.invoke('build', '--jar', str(self.jar), '--output-dir', str(second), success=True)
        bundle_a, archive_a = self.distribution(first)
        bundle_b, archive_b = self.distribution(second)
        self.assertEqual(archive_a.read_bytes(), archive_b.read_bytes())
        self.assertEqual((bundle_a / 'manifest.json').read_bytes(), (bundle_b / 'manifest.json').read_bytes())
        with zipfile.ZipFile(archive_a) as archive:
            self.assertEqual(archive.namelist(), sorted(archive.namelist()))

    def test_output_cannot_overwrite_existing_empty_or_populated_directory(self):
        for populated in [False, True]:
            with self.subTest(populated=populated):
                output = self.directory / ('populated' if populated else 'empty')
                output.mkdir()
                if populated:
                    (output / 'keep.txt').write_text(self.canary)
                before = {p.name: p.read_bytes() for p in output.iterdir()}
                self.invoke('build', '--jar', str(self.jar), '--output-dir', str(output), success=False)
                self.assertEqual(before, {p.name: p.read_bytes() for p in output.iterdir()})

    def test_missing_or_corrupt_jar_does_not_create_output(self):
        corrupt = self.directory / 'invalid.jar'
        corrupt.write_text(self.canary)
        for jar in [self.directory / 'absent.jar', corrupt]:
            with self.subTest(kind=jar.name):
                output = self.directory / (jar.stem + '-out')
                self.invoke('build', '--jar', str(jar), '--output-dir', str(output), success=False)
                self.assertFalse(output.exists())

    def modified_jar(self, omitted_prefix):
        target = self.directory / 'incomplete.jar'
        with zipfile.ZipFile(self.jar) as source, zipfile.ZipFile(target, 'w') as destination:
            omitted = 0
            for info in source.infolist():
                if info.filename.startswith(omitted_prefix):
                    omitted += 1
                    continue
                destination.writestr(info, source.read(info.filename))
        self.assertGreater(omitted, 0, 'negative fixture must remove an actual artifact entry')
        return target

    def test_missing_probe_class_is_rejected_before_bundle_creation(self):
        jar = self.modified_jar('BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeProbe.class')
        output = self.directory / 'out'
        self.invoke('build', '--jar', str(jar), '--output-dir', str(output), success=False)
        self.assertFalse(output.exists())

    def test_missing_new_migration_is_rejected_before_bundle_creation(self):
        for entry in ['BOOT-INF/classes/db/migration/V8__resource_operation_consumption.sql',
                      'BOOT-INF/classes/db/migration/V10__local_accounts_and_login_limits.sql',
                      'BOOT-INF/classes/db/runner-migration/V3__scoped_command_intents.sql']:
            with self.subTest(entry=entry):
                jar = self.modified_jar(entry)
                output = self.directory / ('out-' + entry.rsplit('/', 1)[1])
                self.invoke('build', '--jar', str(jar), '--output-dir', str(output), success=False)
                self.assertFalse(output.exists())

    def test_missing_mysql_dependency_is_rejected_before_bundle_creation(self):
        jar = self.modified_jar('BOOT-INF/lib/mysql-connector-j-')
        output = self.directory / 'out'
        self.invoke('build', '--jar', str(jar), '--output-dir', str(output), success=False)
        self.assertFalse(output.exists())

    def test_private_adjacent_input_data_and_caches_are_not_copied(self):
        with zipfile.ZipFile(self.archive) as archive:
            relative = {name.partition('/')[2] for name in archive.namelist() if not name.endswith('/')}
            self.assertEqual(relative, EXPECTED_FILES)
            for name in archive.namelist():
                self.assertNotIn(self.canary.encode(), archive.read(name))
            self.assertFalse(any(part in name for name in relative for part in ['node_modules', '.runtime', '.m2', 'test-data', 'runtime-private', '/src/']))

    def test_filled_or_extended_template_is_rejected_without_echoing_private_value(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        import release_bundle
        source = self.directory / 'isolated-source'
        source.mkdir()
        for relative in release_bundle.SOURCE_FILES.values():
            destination = source / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / relative, destination)
        file = source / 'backend/config/runtime.example.json'
        original = json.loads(file.read_text())
        for changed in [original | {'WB_DB_PASSWORD': self.canary},
                        original | {'WB_DB_URL': original['WB_DB_URL'].replace('REPLACE_TRUSTSTORE_PASSWORD', self.canary)},
                        original | {'PRIVATE_EXTRA_SECRET': self.canary}]:
            with self.subTest(keys=sorted(changed)):
                file.write_text(json.dumps(changed))
                with mock.patch.object(release_bundle, 'ROOT', source), self.assertRaises(release_bundle.BundleFailure) as raised:
                    release_bundle.template_payload()
                self.assertEqual(raised.exception.code, 'TEMPLATE_NOT_SAFE')
                self.assertNotIn(self.canary, str(raised.exception))

    def test_extracted_bundle_verifies_from_unicode_spaced_directory_and_arbitrary_cwd(self):
        extraction = self.directory / '解包 with spaces'
        extraction.mkdir()
        with zipfile.ZipFile(self.archive) as archive:
            archive.extractall(extraction)  # Already positively verified trusted fixture, never malicious input.
        bundle = extraction / self.bundle.name
        elsewhere = self.directory / 'unrelated cwd'
        elsewhere.mkdir()
        self.invoke('verify', '--directory', str(bundle), cwd=elsewhere, success=True)
        self.invoke('--directory', str(bundle), cwd=elsewhere,
                    script=bundle / 'scripts/release_manifest.py', success=True)

    def test_payload_byte_tamper_is_rejected(self):
        bundle = self.clone_bundle()
        file = bundle / 'README.md'
        file.write_bytes(file.read_bytes() + b'\nchanged\n')
        self.reject_directory(bundle)

    def test_archive_payload_byte_tamper_is_rejected(self):
        target = self.directory / 'tampered-valid-structure.zip'
        changed = 0
        with zipfile.ZipFile(self.archive) as original, zipfile.ZipFile(target, 'w') as tampered:
            for info in original.infolist():
                content = original.read(info.filename)
                if info.filename == self.bundle.name + '/README.md':
                    content += b'\nchanged payload bytes\n'
                    changed += 1
                tampered.writestr(info, content)
        self.assertEqual(changed, 1)
        self.invoke('verify', '--archive', str(target), success=False)

    def test_manifest_or_checksum_tamper_is_rejected(self):
        for filename in ['manifest.json', 'SHA256SUMS']:
            with self.subTest(filename=filename):
                parent = self.directory / filename.replace('.', '-')
                parent.mkdir()
                bundle = parent / self.bundle.name
                shutil.copytree(self.bundle, bundle)
                file = bundle / filename
                file.write_bytes(file.read_bytes() + b'tampered')
                self.reject_directory(bundle)

    def test_missing_and_extra_payloads_are_rejected(self):
        missing = self.clone_bundle()
        (missing / 'config/runtime.example.json').unlink()
        self.reject_directory(missing)
        extra_parent = self.directory / 'extra-bundle'
        extra_parent.mkdir()
        extra = extra_parent / self.bundle.name
        shutil.copytree(self.bundle, extra)
        (extra / 'runtime-private.json').write_text(json.dumps({'password': self.canary}))
        self.reject_directory(extra)

    def test_symlink_payload_is_rejected_even_when_bytes_match(self):
        bundle = self.clone_bundle()
        original = bundle / 'README.md'
        external = self.directory / 'external-readme'
        external.write_bytes(original.read_bytes())
        original.unlink()
        original.symlink_to(external)
        self.reject_directory(bundle)
        self.assertEqual(external.read_bytes(), (self.bundle / 'README.md').read_bytes())

    def append_archive(self, name, value=b'not a payload', *, info=None, compression=None):
        target = self.directory / 'malicious.zip'
        shutil.copyfile(self.archive, target)
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(target, 'a') as archive:
                if compression is None:
                    archive.writestr(info or name, value)
                else:
                    archive.writestr(info or name, value, compress_type=compression)
        return target

    def test_unsafe_archive_paths_are_rejected_without_extracting(self):
        for name in ['../must-not-create', '/must-not-create', 'C:/must-not-create',
                     self.bundle.name + '/README.md:stream', self.bundle.name + '/CON',
                     self.bundle.name + '/../must-not-create', self.bundle.name + '\\must-not-create']:
            with self.subTest(name=name):
                archive = self.append_archive(name)
                self.invoke('verify', '--archive', str(archive), success=False)
                self.assertFalse((self.root / 'must-not-create').exists())
                self.assertFalse((self.directory / 'must-not-create').exists())

    def test_duplicate_case_collision_and_archive_symlink_are_rejected(self):
        paths = [self.bundle.name + '/README.md', self.bundle.name + '/readme.md']
        for name in paths:
            with self.subTest(name=name):
                self.invoke('verify', '--archive', str(self.append_archive(name)), success=False)
        info = zipfile.ZipInfo(self.bundle.name + '/link')
        info.create_system = 3
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        self.invoke('verify', '--archive', str(self.append_archive(info.filename, b'../../outside', info=info)), success=False)

    def test_unlisted_high_compression_entry_is_rejected_without_expansion(self):
        archive = self.append_archive(self.bundle.name + '/bomb.txt', b'0' * (16 * 1024 * 1024),
                                      compression=zipfile.ZIP_DEFLATED)
        before = {p.name for p in self.directory.iterdir()}
        self.invoke('verify', '--archive', str(archive), success=False)
        self.assertEqual(before, {p.name for p in self.directory.iterdir()})

    def verifier_module(self):
        spec = importlib.util.spec_from_file_location('release_manifest_test_subject', ROOT / 'scripts/release_manifest.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_oversized_archive_manifest_is_rejected_before_decompression(self):
        target = self.directory / 'oversized-manifest.zip'
        with zipfile.ZipFile(self.archive) as original, zipfile.ZipFile(target, 'w') as modified:
            for info in original.infolist():
                content = original.read(info.filename)
                if info.filename.endswith('/manifest.json'):
                    content += b' ' * (3 * 1024 * 1024)
                modified.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED)
        verifier = self.verifier_module()
        read_manifest = []
        original_read = zipfile.ZipFile.read

        def observe_read(archive, member, *arguments, **keywords):
            name = getattr(member, 'filename', str(member))
            if name.endswith('/manifest.json'):
                read_manifest.append(name)
            return original_read(archive, member, *arguments, **keywords)

        with mock.patch.object(zipfile.ZipFile, 'read', observe_read):
            with self.assertRaises(verifier.BundleFailure):
                verifier.verify_archive(target)
        self.assertEqual(read_manifest, [], 'oversized metadata must be rejected before expanding its content')

    def test_oversized_directory_manifest_is_rejected_before_reading(self):
        bundle = self.clone_bundle()
        manifest = bundle / 'manifest.json'
        manifest.write_bytes(manifest.read_bytes() + b' ' * (3 * 1024 * 1024))
        verifier = self.verifier_module()
        read_manifest = []
        original_read = Path.read_bytes

        def observe_read(path):
            if path == manifest:
                read_manifest.append(path.name)
            return original_read(path)

        with mock.patch.object(Path, 'read_bytes', observe_read):
            with self.assertRaises(verifier.BundleFailure):
                verifier.verify_directory(bundle)
        self.assertEqual(read_manifest, [], 'oversized metadata must be rejected from stat before loading bytes')


if __name__ == '__main__':
    unittest.main()

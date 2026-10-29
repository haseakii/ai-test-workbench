"""Build an allowlisted deterministic distribution from existing build artifacts. No install."""
import argparse
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile

from release_manifest import FORMAT, PAYLOAD, BundleFailure, canonical, checksum_text, fail, safe_path, verify_archive, verify_directory

ROOT = Path(__file__).resolve().parents[1]
NORMALIZED_EPOCH = 1792886400  # 2026-10-25T00:00:00Z; archive normalization, not a release timestamp.
SOURCE_FILES = {
    'config/runtime.example.json': 'backend/config/runtime.example.json',
    'config/migration-platform.example.json': 'backend/config/migration-platform.example.json',
    'config/migration-runner.example.json': 'backend/config/migration-runner.example.json',
    'scripts/synthetic_runtime.py': 'scripts/synthetic_runtime.py',
    'scripts/release_manifest.py': 'scripts/release_manifest.py',
    'Workbench.cmd': 'backend/distribution/Workbench.cmd',
    'README.md': 'backend/distribution/README.md',
    'COMPATIBILITY.md': 'backend/distribution/COMPATIBILITY.md',
    'LICENSE': 'LICENSE',
    'setup/dependencies.json': 'backend/distribution/setup/dependencies.json',
    'setup/bootstrap-actions.json': 'backend/distribution/setup/bootstrap-actions.json',
    'setup/bootstrap.sql.template': 'backend/distribution/setup/bootstrap.sql.template',
    'setup/initialize.ini.template': 'backend/distribution/setup/initialize.ini.template',
    'setup/my.ini.template': 'backend/distribution/setup/my.ini.template',
}


def artifact(jar):
    try:
        if jar.is_symlink() or not jar.is_file() or jar.stat().st_size > 256 * 1024 * 1024:
            raise ValueError()
        data = jar.read_bytes()
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)) or len(names) > 10000 or sum(i.file_size for i in archive.infolist()) > 512 * 1024 * 1024:
                raise ValueError()
            for name in names:
                safe_path(name.rstrip('/'))
                if any(p in name.split('/') for p in ['test-classes', '.runtime', 'node_modules', '.git', '__pycache__']):
                    raise ValueError()
                if name.endswith(('.blob', '.env', '.csv', '.log', '.pyc')) or 'runtime-private' in name or 'migration-platform-private' in name:
                    raise ValueError()
            main = archive.read('META-INF/MANIFEST.MF').decode()
            if 'Main-Class: org.springframework.boot.loader.launch.JarLauncher' not in main or 'Start-Class: io.aitest.workbench.WorkbenchApplication' not in main:
                raise ValueError()
            classes = 'BOOT-INF/classes/'
            required = {classes + 'io/aitest/workbench/bootstrap/' + name + '.class'
                        for name in ['RuntimeProbe', 'RuntimeReadiness', 'RuntimeLauncher', 'RuntimeLifecycleControl', 'RuntimeFiles', 'SetupInspector', 'WindowsSecretProtection']}
            required |= {classes + 'io/aitest/workbench/WorkbenchApplication.class', classes + 'static/index.html', classes + 'db/runner-migration/V1__runner_journal.sql'}
            if not required.issubset(names) or not any(n.startswith(classes + 'static/assets/') and n.endswith('.js') for n in names):
                raise ValueError()
            versions = sorted(int(re.search(r'/V([0-9]+)__', n)[1]) for n in names if re.fullmatch(r'BOOT-INF/classes/db/migration/V[0-9]+__[^/]+\.sql', n))
            if versions != list(range(1, 11)) or not {'BOOT-INF/classes/db/migration/V5__frozen_quota_accounts.sql', 'BOOT-INF/classes/db/runner-migration/V3__scoped_command_intents.sql', 'BOOT-INF/classes/db/migration/V9__local_auth_sessions.sql', 'BOOT-INF/classes/db/migration/V2__title_source_and_quota_policy.sql', 'BOOT-INF/classes/db/migration/V1__platform.sql', 'BOOT-INF/classes/db/migration/V4__receipt_scope_and_synthetic_marker.sql', 'BOOT-INF/classes/db/migration/V6__context_manifests.sql', 'BOOT-INF/classes/db/migration/V8__resource_operation_consumption.sql', 'BOOT-INF/classes/db/migration/V7__typed_provider_journal.sql', 'BOOT-INF/classes/db/runner-migration/V1__runner_journal.sql', 'BOOT-INF/classes/db/runner-migration/V2__single_start_intent.sql', 'BOOT-INF/classes/db/migration/V10__local_accounts_and_login_limits.sql', 'BOOT-INF/classes/db/migration/V3__scoped_content_review.sql'}.issubset(names):
                raise ValueError()
            libs = sorted(n for n in names if n.startswith('BOOT-INF/lib/') and n.endswith('.jar'))
            required_libraries = ['mysql-connector-j-', 'spring-boot-', 'spring-jdbc-', 'flyway-core-', 'flyway-mysql-', 'provider-contract-']
            if not all(any(Path(n).name.startswith(p) for n in libs) for p in required_libraries):
                raise ValueError()
            if any(any(t in Path(n).name.lower() for t in ['junit', 'mockito', 'assertj', 'testcontainers']) for n in libs):
                raise ValueError()
            properties = archive.read('META-INF/maven/io.aitest/platform-server/pom.properties').decode()
            version = re.search(r'^version=(.+)$', properties, re.M)[1].strip()
            if not re.fullmatch(r'[0-9A-Za-z][0-9A-Za-z._+-]{0,63}', version):
                raise ValueError()
            inventory = [{'file': Path(n).name, 'sha256': hashlib.sha256(archive.read(n)).hexdigest()} for n in libs]
        return data, version, inventory
    except BundleFailure:
        raise
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile):
        fail('ARTIFACT_INVALID', '需要包含前端、Windows入口、两库迁移和运行依赖的完整预构建 Jar；不会自动编译或安装。')


def source_snapshot():
    files = []
    suffixes = {'.java', '.xml', '.properties', '.sql', '.ts', '.tsx', '.json', '.py', '.mjs', '.cmd'}
    for directory in ['backend', 'frontend/src', 'scripts', 'contracts']:
        for path in (ROOT / directory).rglob('*'):
            if (not path.is_file() or path.is_symlink() or path.suffix not in suffixes
                    or any(p in path.parts for p in ['target', 'node_modules', 'dist', '__pycache__'])):
                continue
            files.append((path.relative_to(ROOT).as_posix(), hashlib.sha256(path.read_bytes()).hexdigest()))
    for name in ['frontend/package.json', 'frontend/package-lock.json', 'frontend/vite.config.ts', 'frontend/tsconfig.json']:
        path = ROOT / name; files.append((name, hashlib.sha256(path.read_bytes()).hexdigest()))
    try:
        revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, stderr=subprocess.DEVNULL, text=True).strip()
        dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, stderr=subprocess.DEVNULL))
    except (OSError, subprocess.CalledProcessError):
        revision, dirty = 'unknown', None
    return {'revision': revision, 'workingTreeDirty': dirty, 'observedSourceSha256': hashlib.sha256(canonical(sorted(files))).hexdigest(),
            'observedFiles': len(files), 'provenance': 'source observed at packaging; tests and build origin are not attested by this command'}


def template_payload():
    contents = {}
    for destination, source in SOURCE_FILES.items():
        path = ROOT / source
        if path.is_symlink() or not path.is_file():
            fail('INPUT_MISSING', '发行固定输入缺失；先完成仓库构建与发行文件。')
        data = path.read_bytes()
        if destination.startswith('config/'):
            try:
                value = json.loads(data)
                password = value.get('WB_DB_PASSWORD', value.get('WB_MIGRATION_PASSWORD'))
                runtime = destination == 'config/runtime.example.json'
                keys = {'SPRING_PROFILES_ACTIVE', 'WB_DB_URL', 'WB_DB_USER', 'WB_DB_PASSWORD', 'WB_BLOB_ROOT', 'WB_PORT'} if runtime else {'WB_DB_URL', 'WB_MIGRATION_USER', 'WB_MIGRATION_PASSWORD'}
                if set(value) != keys or not isinstance(password, str) or not re.fullmatch(r'REPLACE_[A-Z0-9_]+', password):
                    raise ValueError()
                from urllib.parse import urlsplit, parse_qs
                target = urlsplit(value['WB_DB_URL'][5:]); query = parse_qs(target.query, strict_parsing=True)
                expected_query = {'sslMode': ['VERIFY_CA'], 'trustCertificateKeyStoreUrl': ['REPLACE_TRUSTSTORE_URI'],
                                  'trustCertificateKeyStorePassword': ['REPLACE_TRUSTSTORE_PASSWORD'], 'trustCertificateKeyStoreType': ['PKCS12'],
                                  'connectionTimeZone': ['UTC'], 'forceConnectionTimeZoneToSession': ['true']}
                database = 'workbench_runner' if 'migration-runner' in destination else 'workbench_platform'
                if (not value['WB_DB_URL'].startswith('jdbc:mysql://') or target.hostname != '127.0.0.1' or target.username
                        or target.path != '/' + database or target.fragment or query != expected_query):
                    raise ValueError()
                if runtime:
                    if (value['WB_DB_USER'] != 'workbench' or value['SPRING_PROFILES_ACTIVE'] != 'synthetic'
                            or not re.fullmatch(r'REPLACE_[A-Z0-9_]+', value['WB_BLOB_ROOT']) or value['WB_PORT'] != '18080'):
                        raise ValueError()
                elif value['WB_MIGRATION_USER'] != 'workbench_migration':
                    raise ValueError()
            except (ValueError, TypeError, KeyError):
                fail('TEMPLATE_NOT_SAFE', '发行配置只能包含占位模板，不能包含已填写的运行凭据。')
        contents[destination] = data
    return contents


def build(jar, output, epoch):
    if output.exists() or output.is_symlink():
        fail('OUTPUT_EXISTS', '输出目录已存在；请选择新的空路径，保留原发行物和数据。')
    if not output.parent.is_dir() or output.parent.is_symlink():
        fail('OUTPUT_PARENT', '输出父目录须已存在且不是符号链接；不会创建或覆盖其他目录。')
    try:
        stamp = datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc)
        if not 1980 <= stamp.year <= 2107:
            raise ValueError()
    except (ValueError, OverflowError, OSError):
        fail('BUILD_EPOCH', '归档规范时间超出 ZIP 支持范围。')
    jar_bytes, version, libraries = artifact(jar)
    contents = template_payload(); contents['app/platform-server.jar'] = jar_bytes
    if set(contents) != PAYLOAD:
        fail('INPUT_MISMATCH', '发行固定载荷列表不一致。')
    records = [{'path': p, 'sha256': hashlib.sha256(contents[p]).hexdigest(), 'bytes': len(contents[p])} for p in sorted(contents)]
    name = 'ai-test-workbench-' + version + '-synthetic'
    manifest = {'format': FORMAT, 'version': version, 'edition': 'synthetic-preview', 'bundleName': name,
                'archiveNormalizationEpoch': epoch, 'source': source_snapshot(), 'files': records, 'libraries': libraries,
                'runtime': {'java': '21', 'mysql': '8.4 LTS (not included; private portable proposal or existing compatibility)', 'platformSchema': 7, 'runnerSchema': 1,
                            'windowsEntry': 'Workbench.cmd', 'extraPythonOrDockerRequiredForWindowsEntry': False,
                            'identity': 'synthetic_only', 'externalTestDataIncluded': False,
                            'newPrivateSecretProtection': 'Windows CurrentUser DPAPI/system PowerShell; prepared, not executed',
                            'embeddedSyntheticBootstrap': True, 'realAdaptersIncluded': False},
                'validation': {'bootStructureChecked': True, 'testsAttestedByPackager': False, 'windowsInstallVerified': False,
                               'coldDatabaseInstallVerified': False, 'upgradeOrRestoreVerified': False,
                               'portableSoftwareIncluded': False, 'initializationExecutorIncluded': False}}
    contents['manifest.json'] = canonical(manifest)
    contents['SHA256SUMS'] = checksum_text(records, contents['manifest.json'])
    temporary = Path(tempfile.mkdtemp(prefix='.workbench-build-', dir=output.parent))
    try:
        directory = temporary / name; directory.mkdir(mode=0o755)
        for relative in sorted(contents):
            target = directory / relative; target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
            with target.open('xb') as file:
                file.write(contents[relative])
            target.chmod(0o644)
        verify_directory(directory)
        archive = temporary / (name + '.zip')
        # STORED avoids zlib-version variance; the principal payload is already a compressed Jar.
        with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_STORED) as bundle:
            for relative in sorted(contents):
                entry = zipfile.ZipInfo(name + '/' + relative, stamp.timetuple()[:6])
                entry.create_system = 3; entry.external_attr = (stat.S_IFREG | 0o644) << 16
                entry.compress_type = zipfile.ZIP_STORED
                bundle.writestr(entry, contents[relative])
        verify_archive(archive)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        (temporary / (archive.name + '.sha256')).write_text(digest + '  ' + archive.name + '\n')
        try:
            output.mkdir(mode=0o755)
        except FileExistsError:
            fail('OUTPUT_EXISTS', '输出目录在打包期间已被创建，未覆盖。')
        for child in temporary.iterdir():
            child.rename(output / child.name)
        return {'ok': True, 'code': 'BUILT', 'bundleName': name, 'archiveSha256': digest,
                'artifactSha256': hashlib.sha256(jar_bytes).hexdigest(), 'files': len(contents), 'windowsInstallVerified': False}
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)  # Only the newly created private packaging scratch directory.


def main(argv=None):
    parser = argparse.ArgumentParser(description='构建/离线校验固定白名单发行目录与ZIP；不安装、不访问数据库。')
    sub = parser.add_subparsers(dest='action', required=True)
    command = sub.add_parser('build'); command.add_argument('--jar', required=True); command.add_argument('--output-dir', required=True)
    command.add_argument('--source-date-epoch', type=int, default=NORMALIZED_EPOCH)
    command = sub.add_parser('verify'); target = command.add_mutually_exclusive_group(required=True)
    target.add_argument('--directory'); target.add_argument('--archive')
    args = parser.parse_args(argv)
    try:
        if args.action == 'build':
            value = build(Path(args.jar), Path(args.output_dir).absolute(), args.source_date_epoch)
        else:
            value = verify_directory(args.directory) if args.directory else verify_archive(args.archive)
        print(json.dumps(value, ensure_ascii=False)); return 0
    except BundleFailure as error:
        print(json.dumps({'ok': False, 'code': error.code, 'message': error.message}, ensure_ascii=False)); return 2
    except Exception:
        print(json.dumps({'ok': False, 'code': 'PACKAGE_FAILED', 'message': '发行准备未完成；检查固定输入、文件权限和完整构建制品。'}, ensure_ascii=False)); return 2


if __name__ == '__main__':
    raise SystemExit(main())

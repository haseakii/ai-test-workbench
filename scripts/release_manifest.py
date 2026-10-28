"""Offline distribution integrity checks. Hashes detect damage, not publisher identity."""
import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath
import stat
import zipfile

FORMAT = 'ai-test-workbench-distribution-v2'
LEGACY_FORMAT = 'ai-test-workbench-distribution-v1'
MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_FILES = 128
BASE_PAYLOAD = {'app/platform-server.jar', 'config/runtime.example.json', 'config/migration-platform.example.json',
           'config/migration-runner.example.json', 'scripts/synthetic_runtime.py', 'scripts/release_manifest.py',
           'Workbench.cmd', 'README.md', 'COMPATIBILITY.md', 'LICENSE'}
PAYLOAD = BASE_PAYLOAD | {'setup/dependencies.json', 'setup/bootstrap-actions.json', 'setup/bootstrap.sql.template',
                          'setup/initialize.ini.template', 'setup/my.ini.template'}
ALL_FILES = PAYLOAD | {'manifest.json', 'SHA256SUMS'}


def check_size(name, size):
    maximum = 256 * 1024 * 1024 if name == 'app/platform-server.jar' else 2 * 1024 * 1024 if name == 'manifest.json' else 512 * 1024
    if size > maximum:
        fail('BUNDLE_TOO_LARGE', '发行文件超过对应类型大小限制。')


class BundleFailure(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code, self.message = code, message


def fail(code, message):
    raise BundleFailure(code, message)


def canonical(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode()


def safe_path(value):
    if not isinstance(value, str) or not value or '\\' in value or ':' in value or '\x00' in value:
        fail('UNSAFE_PATH', '归档含不安全路径。')
    path = PurePosixPath(value)
    if path.is_absolute() or str(path) != value or any(p in ('', '.', '..') for p in value.split('/')):
        fail('UNSAFE_PATH', '归档路径须为规范的相对路径。')
    for part in path.parts:
        if part.endswith((' ', '.')) or part.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}:
            fail('UNSAFE_PATH', '归档含 Windows 保留路径。')
    return value


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail('MANIFEST_INVALID', '清单含重复字段。')
        result[key] = value
    return result


def checksum_text(files, manifest_bytes):
    hashes = {row['path']: row['sha256'] for row in files}
    hashes['manifest.json'] = hashlib.sha256(manifest_bytes).hexdigest()
    return ''.join(hashes[p] + '  ' + p + '\n' for p in sorted(hashes)).encode()


def verify_payload(read, paths):
    if 'manifest.json' not in paths or set(paths) - ALL_FILES:
        fail('CONTENT_MISMATCH', '发行内容缺失或包含清单以外的文件；私有配置与运行数据须在包外。')
    raw = read('manifest.json')
    if len(raw) > 2 * 1024 * 1024:
        fail('MANIFEST_INVALID', '发行清单超限。')
    try:
        value = json.loads(raw, object_pairs_hook=unique)
        if not isinstance(value, dict) or value.get('format') not in (FORMAT, LEGACY_FORMAT) or value.get('edition') != 'synthetic-preview':
            raise ValueError()
        expected = PAYLOAD if value['format'] == FORMAT else BASE_PAYLOAD
        if set(paths) != expected | {'manifest.json', 'SHA256SUMS'}:
            fail('CONTENT_MISMATCH', '发行内容与对应版本的固定白名单不匹配。')
        rows = value['files']
        if not isinstance(rows, list) or len(rows) != len(expected):
            raise ValueError()
        names = []
        for row in rows:
            if set(row) != {'path', 'sha256', 'bytes'}:
                raise ValueError()
            name = safe_path(row['path']); names.append(name)
            if not re.fullmatch(r'[0-9a-f]{64}', row['sha256']) or type(row['bytes']) is not int or row['bytes'] < 0:
                raise ValueError()
        if names != sorted(expected):
            raise ValueError()
    except BundleFailure:
        raise
    except (ValueError, TypeError, KeyError):
        fail('MANIFEST_INVALID', '发行清单格式或载荷列表不匹配。')
    for row in rows:
        data = read(row['path'])
        if len(data) != row['bytes'] or hashlib.sha256(data).hexdigest() != row['sha256']:
            fail('CHECKSUM_MISMATCH', '发行文件摘要或大小不匹配；保留文件并重新取得完整发行包。')
    if read('SHA256SUMS') != checksum_text(rows, raw):
        fail('CHECKSUM_MISMATCH', '校验和列表与实际清单不匹配。')
    return {'ok': True, 'code': 'VERIFIED', 'format': value['format'], 'version': value.get('version'),
            'files': len(paths), 'edition': value['edition'], 'windowsInstallVerified': False}


def verify_directory(directory):
    directory = Path(directory)
    if not directory.is_dir() or directory.is_symlink():
        fail('BUNDLE_MISSING', '找不到普通发行目录。')
    files = {}
    allowed_dirs = {str(p) for name in ALL_FILES for p in PurePosixPath(name).parents if str(p) != '.'}
    for file in directory.rglob('*'):
        relative = file.relative_to(directory).as_posix(); safe_path(relative)
        if file.is_symlink():
            fail('SYMLINK_DENIED', '发行目录不能包含符号链接。')
        if file.is_dir():
            if relative not in allowed_dirs:
                fail('CONTENT_MISMATCH', '发行目录含额外目录，不能混放私有配置或缓存。')
        elif file.is_file():
            check_size(relative, file.stat().st_size)
            files[relative] = file
        else:
            fail('CONTENT_MISMATCH', '发行目录含非普通文件。')
    if sum(p.stat().st_size for p in files.values()) > MAX_ARCHIVE_BYTES:
        fail('BUNDLE_TOO_LARGE', '发行内容超过大小限制。')
    result = verify_payload(lambda name: files[name].read_bytes(), files)
    if result['format'] == LEGACY_FORMAT and (directory / 'setup').exists():
        fail('CONTENT_MISMATCH', '旧版发行目录不能混入首装准备目录。')
    return result


def verify_archive(archive):
    archive = Path(archive)
    if not archive.is_file() or archive.is_symlink() or archive.stat().st_size > MAX_ARCHIVE_BYTES:
        fail('ARCHIVE_INVALID', '归档缺失、类型不支持或大小超限。')
    try:
        with zipfile.ZipFile(archive) as bundle:
            entries = bundle.infolist()
            if len(entries) > MAX_FILES or sum(e.file_size for e in entries) > MAX_ARCHIVE_BYTES:
                fail('BUNDLE_TOO_LARGE', '归档解压内容超限。')
            files = {}; seen = set(); prefix = None
            for entry in entries:
                name = safe_path(entry.filename)
                lower = name.casefold()
                if lower in seen:
                    fail('DUPLICATE_ENTRY', '归档存在重复或大小写冲突的路径。')
                seen.add(lower)
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0, stat.S_IFREG) or entry.is_dir() or entry.flag_bits & 1:
                    fail('ARCHIVE_INVALID', '归档不能含链接、特殊文件、目录项或加密文件。')
                parts = name.split('/', 1)
                if len(parts) != 2:
                    fail('ARCHIVE_INVALID', '归档必须有唯一发行根目录。')
                if prefix is None:
                    prefix = parts[0]
                elif prefix != parts[0]:
                    fail('ARCHIVE_INVALID', '归档包含多个根目录。')
                relative = safe_path(parts[1]); check_size(relative, entry.file_size); files[relative] = entry
            return verify_payload(lambda name: bundle.read(files[name]), files)
    except BundleFailure:
        raise
    except (OSError, ValueError, KeyError, zipfile.BadZipFile, RuntimeError):
        fail('ARCHIVE_INVALID', '归档内容损坏或格式无效。')


def main(argv=None):
    parser = argparse.ArgumentParser(description='离线核对发行清单；不安装、不解包、不访问数据库。')
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--directory'); group.add_argument('--archive')
    args = parser.parse_args(argv)
    try:
        value = verify_directory(args.directory) if args.directory else verify_archive(args.archive)
        print(json.dumps(value, ensure_ascii=False)); return 0
    except BundleFailure as error:
        print(json.dumps({'ok': False, 'code': error.code, 'message': error.message}, ensure_ascii=False)); return 2
    except Exception:
        print(json.dumps({'ok': False, 'code': 'VERIFY_FAILED', 'message': '离线校验未完成；检查文件权限及发行版本。'}, ensure_ascii=False)); return 2


if __name__ == '__main__':
    raise SystemExit(main())

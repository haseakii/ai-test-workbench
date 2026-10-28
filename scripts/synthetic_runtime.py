"""Existing Linux/Java21/Python3.12 synthetic entry, in source or review-bundle layout.

Never provisions resources. This development helper is not a final user installer.
"""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import stat
import subprocess
import time
import uuid
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, ProxyHandler, build_opener
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNTIME_KEYS = {'WB_DB_URL', 'WB_DB_USER', 'WB_DB_PASSWORD', 'WB_BLOB_ROOT', 'WB_PORT', 'SPRING_PROFILES_ACTIVE'}
FAULT_KEYS = {'WB_FAULT_PHASE', 'WB_FAULT_BINDING_ID', 'WB_FAULT_ROOT'}
MIGRATION_KEYS = {'WB_DB_URL', 'WB_MIGRATION_USER', 'WB_MIGRATION_PASSWORD'}


def distribution_layout():
    # Resolve from this script, not cwd. A damaged review bundle must not depend on
    # the developer's source tree or a sibling working copy.
    return (ROOT / 'app').is_dir() or (ROOT / 'config').is_dir()


def default_jar():
    return ROOT / ('app/platform-server.jar' if distribution_layout()
                   else 'backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar')


def template_file(kind):
    directory = ROOT / ('config' if distribution_layout() else 'backend/config')
    return directory / (kind + '.example.json')


class RuntimeFailure(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code, self.message = code, message


def fail(code, message):
    raise RuntimeFailure(code, message)


def emit(**value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail('CONFIG_DUPLICATE_KEY', '配置含重复键，请保留每个配置键唯一。')
        result[key] = value
    return result


def load_config(path, migration=False):
    try:
        file = Path(path)
        info = file.stat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) & 0o077:
            fail('CONFIG_PERMISSIONS', '私有配置须由当前用户拥有且仅当前用户可读写（chmod 600）。')
        value = json.loads(file.read_text(), object_pairs_hook=unique_object)
    except RuntimeFailure:
        raise
    except (OSError, ValueError, TypeError):
        fail('CONFIG_UNREADABLE', '无法读取配置 JSON；检查文件存在、读取权限和语法。')
    expected = MIGRATION_KEYS if migration else RUNTIME_KEYS
    allowed = expected if migration else expected | FAULT_KEYS
    if not isinstance(value, dict) or not expected.issubset(value) or set(value) - allowed:
        fail('CONFIG_KEYS', '配置键缺失或不受支持；使用 template 查看相应模板。')
    if any(not isinstance(v, str) or not v or '\x00' in v or 'REPLACE_' in v for v in value.values()):
        fail('CONFIG_VALUES', '配置值须为非空字符串；先填写模板中的占位值。')
    return value


def validate_target(config, database='workbench_platform'):
    try:
        raw = config['WB_DB_URL']
        if not raw.startswith('jdbc:mysql://'):
            raise ValueError()
        target = urlsplit(raw[5:])
        query = parse_qs(target.query, strict_parsing=True)
        if (target.hostname != '127.0.0.1' or target.username is not None or target.password is not None
                or target.path != '/' + database or target.fragment or target.port is None or not 1 <= target.port <= 65535):
            raise ValueError()
        allowed = {'sslMode', 'trustCertificateKeyStoreUrl', 'trustCertificateKeyStorePassword',
                   'trustCertificateKeyStoreType', 'connectionTimeZone', 'forceConnectionTimeZoneToSession',
                   'connectTimeout', 'socketTimeout'}
        if set(query) - allowed or any(len(values) != 1 for values in query.values()):
            fail('DB_OPTIONS', '连接包含未知或重复参数；请使用固定模板，不允许动态 JDBC 覆盖。')
        if query.get('sslMode') not in (['VERIFY_CA'], ['VERIFY_IDENTITY']):
            fail('DB_TLS_REQUIRED', '连接须保留 VERIFY_CA/VERIFY_IDENTITY 与受信 CA，不能关闭证书校验。')
        for key in ('useSSL', 'verifyServerCertificate', 'allowMultiQueries', 'autoReconnect'):
            if key in query:
                fail('DB_OPTIONS', '移除不受支持的连接覆盖项，使用模板的安全连接配置。')
        if query.get('connectionTimeZone') != ['UTC'] or query.get('forceConnectionTimeZoneToSession') != ['true']:
            fail('DB_TIMEZONE', '数据库连接须显式使用 UTC，并固定会话时区。')
        if 'trustCertificateKeyStoreUrl' in query:
            trust = urlsplit(query['trustCertificateKeyStoreUrl'][0])
            if trust.scheme != 'file' or trust.netloc or not trust.path.startswith('/'):
                fail('DB_TLS_REQUIRED', '受信证书库须为已配置的本地文件，不下载远程信任库。')
    except RuntimeFailure:
        raise
    except (KeyError, ValueError, TypeError):
        fail('TARGET_DENIED', '仅支持已获准的 IPv4 回环合成测试库；检查模板目标，勿改接真实数据库。')
    account = config.get('WB_DB_USER', config.get('WB_MIGRATION_USER', ''))
    if not account or account.casefold() == 'root' or not re.fullmatch(r'[a-zA-Z0-9_-]{1,32}', account):
        fail('DB_ACCOUNT_DENIED', '请使用已获准的独立最小权限账号，不能用 root 运行服务或迁移。')
    expected_account = 'workbench' if 'WB_DB_USER' in config else 'workbench_migration'
    if account != expected_account:
        fail('DB_ACCOUNT_DENIED', '当前合成入口仅使用已获准的 workbench 运行账号和独立 workbench_migration 迁移账号；不能互换身份。')
    if 'WB_DB_USER' in config:
        if config.get('SPRING_PROFILES_ACTIVE') != 'synthetic':
            fail('PROFILE_DENIED', '当前仅支持显式 synthetic；生产身份尚未实现。')
        if not re.fullmatch(r'[0-9]{1,5}', config.get('WB_PORT', '')) or not 1024 <= int(config['WB_PORT']) <= 65535:
            fail('PORT_INVALID', '端口须为 1024–65535，避免要求特权端口。')
        if not Path(config.get('WB_BLOB_ROOT', '')).is_absolute():
            fail('BLOB_DIRECTORY', '文件目录须为私有绝对路径；不要使用仓库或系统目录。')


def runtime_env(config, migration=False):
    # Retain cloud proxy/CA/identity guard variables; strip inherited app overrides.
    forbidden = {'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH', 'MYSQL_PWD'}
    result = {k: v for k, v in os.environ.items()
              if k not in forbidden and not k.startswith(('WB_', 'SPRING_', 'SERVER_', 'LOADER_'))}
    allowed = MIGRATION_KEYS if migration else RUNTIME_KEYS | FAULT_KEYS
    result.update({k: v for k, v in config.items() if k in allowed})
    return result


def check_port(port):
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            # Match the JVM's normal server bind: TIME_WAIT is not a live listener.
            # SO_REUSEPORT is deliberately absent; an active listener still rejects us.
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(('127.0.0.1', int(port)))
    except OSError:
        fail('PORT_IN_USE', '回环端口不可用；先用 status 核对受管进程，或配置空闲端口。')


def directory_preflight(path):
    """Read-only: no mkdir, chmod or temporary probe files."""
    path = Path(path)
    if (not path.is_absolute() or path != Path(os.path.normpath(path))
            or any(parent.is_symlink() for parent in (path, *path.parents))):
        fail('DIRECTORY_INVALID', '运行/文件目录须为绝对路径，末级不能为符号链接。')
    parent = path
    while not parent.exists():
        parent = parent.parent
    info = parent.stat()
    if not parent.is_dir() or not os.access(parent, os.W_OK | os.X_OK) or info.st_mode & 0o022:
        fail('DIRECTORY_PERMISSIONS', '目录或最近父目录须可写且不可被其他用户写入；预检不会自动修改权限。')


def process_identity(pid):
    try:
        base = Path('/proc') / str(pid)
        fields = (base / 'stat').read_text().rsplit(')', 1)[1].split()
        if fields[0] == 'Z':
            return None
        start = int(fields[19])
        boot = Path('/proc/sys/kernel/random/boot_id').read_text().strip()
        boot_seconds = int(next(line.split()[1] for line in Path('/proc/stat').read_text().splitlines() if line.startswith('btime ')))
        return {'startTicks': start, 'bootId': boot, 'startedUnix': boot_seconds + start / os.sysconf('SC_CLK_TCK'),
                'command': (base / 'cmdline').read_bytes().split(b'\0')}
    except (FileNotFoundError, ProcessLookupError):
        return None
    except (OSError, ValueError, IndexError, StopIteration):
        fail('PROCESS_IDENTITY_UNAVAILABLE', '无法核对受管进程身份；未发送信号。当前开发入口需要 Linux /proc。')


def owned_process(directory, java):
    pidfile, metadata = Path(directory) / 'platform.pid', Path(directory) / 'platform-process.json'
    if not pidfile.exists() and not metadata.exists():
        return None
    try:
        recorded = json.loads(metadata.read_text())
        # A crash between metadata and PID publication must not hide a live JVM.
        pid = int(pidfile.read_text()) if pidfile.exists() else recorded['pid']
        if pid <= 1:
            raise ValueError()
        if recorded['pid'] != pid:
            raise ValueError()
        identity = process_identity(pid)
        if identity is None:
            return None
        command, artifact = identity['command'], Path(recorded['artifactJar'])
        if (len(command) < 3 or command[1] != b'-jar' or Path(os.fsdecode(command[0])).resolve() != Path(java).resolve()
                or Path(os.fsdecode(command[2])).resolve() != artifact.resolve()
                or Path(recorded['java']).resolve() != Path(java).resolve()
                or hashlib.sha256(artifact.read_bytes()).hexdigest() != recorded['sha256']):
            raise ValueError()
        if 'startTicks' in recorded:
            if any(recorded.get(key) != identity[key] for key in ('startTicks', 'bootId')):
                raise ValueError()
        elif abs(datetime.datetime.fromisoformat(recorded['startedAt']).timestamp() - identity['startedUnix']) > 5:
            raise ValueError()  # Compatibility with the existing task-owned metadata.
        return {**recorded, **{k: identity[k] for k in ('startTicks', 'bootId')}}
    except RuntimeFailure:
        raise
    except (OSError, ValueError, KeyError, TypeError):
        fail('PROCESS_OWNERSHIP_UNCERTAIN', 'PID 与 Java/制品/启动身份不符；未发送信号，不会另起替代进程。')


def listener_owned(pid, port):
    try:
        inodes = set()
        for file in (Path('/proc') / str(pid) / 'fd').iterdir():
            try:
                link = os.readlink(file)
                if link.startswith('socket:['):
                    inodes.add(link[8:-1])
            except FileNotFoundError:
                continue
        # The JVM may use an IPv4-mapped socket in tcp6 even when bound to 127.0.0.1.
        for table, address in (('/proc/net/tcp', '0100007F'), ('/proc/net/tcp6', '0000000000000000FFFF00000100007F')):
            for line in Path(table).read_text().splitlines()[1:]:
                fields = line.split()
                if fields[1] == address + ':' + format(int(port), '04X') and fields[3] == '0A' and fields[9] in inodes:
                    return True
    except (OSError, IndexError):
        return False
    return False


def ready_marker_matches(directory, pid, port):
    if directory is None:
        return False
    try:
        metadata = json.loads((Path(directory) / 'platform-process.json').read_text())
        nonce = metadata.get('readyNonce')
        if not isinstance(nonce, str) or str(uuid.UUID(nonce)) != nonce:
            return False
        marker = json.loads((Path(directory) / ('platform-ready-' + nonce + '.json')).read_text())
        return (isinstance(metadata.get('readyNonce'), str) and marker.get('nonce') == metadata['readyNonce']
                and marker.get('pid') == pid == metadata.get('pid') and marker.get('port') == int(port)
                and marker.get('identityMode') == 'synthetic_only')
    except (OSError, ValueError, TypeError):
        return False


def http_health(pid, port, directory=None):
    if not listener_owned(pid, port) and not ready_marker_matches(directory, pid, port):
        fail('LISTENER_UNCONFIRMED', '受管进程尚未拥有配置的回环监听；不会把其他服务当作就绪。')
    try:
        request = Request('http://127.0.0.1:' + str(port) + '/api/session', headers={'X-Test-Subject': 'admin'})
        with build_opener(ProxyHandler({})).open(request, timeout=2) as response:
            body = json.load(response)
            if (response.status != 200 or body.get('identityMode') != 'synthetic_only'
                    or body.get('state') != 'active' or response.headers.get('Cache-Control') != 'no-store'):
                raise ValueError()
    except Exception:
        fail('HTTP_NOT_READY', '受管进程存在，但合成会话接口未就绪；检查私有运行日志和数据库状态。')


def java_executable(home):
    candidate = str(Path(home) / 'bin/java') if home else shutil.which('java')
    if not candidate or not Path(candidate).is_file():
        fail('JAVA_MISSING', '找不到 Java；请指定已有 JDK 21 的 --java-home。入口不会安装软件。')
    try:
        result = subprocess.run([candidate, '-version'], env=runtime_env({}), capture_output=True, timeout=8)
        match = re.search(rb'(?:openjdk|java) version "(\d+)', result.stderr + result.stdout)
        if result.returncode or not match or int(match[1]) != 21:
            fail('JAVA_VERSION', '此制品需要 Java 21；请使用已安装的受信运行时。')
    except (OSError, subprocess.TimeoutExpired):
        fail('JAVA_UNAVAILABLE', '无法执行 Java 版本检查；检查运行时权限。')
    return str(Path(candidate).resolve())


def artifact_preflight(jar):
    try:
        with zipfile.ZipFile(jar) as archive:
            required = {'BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeProbe.class', 'BOOT-INF/classes/static/index.html',
                        'BOOT-INF/classes/io/aitest/workbench/bootstrap/RuntimeReadiness.class',
                        'BOOT-INF/classes/db/runner-migration/V1__runner_journal.sql'}
            if not required.issubset(archive.namelist()):
                raise ValueError()
    except (OSError, ValueError, zipfile.BadZipFile):
        fail('ARTIFACT_INCOMPLETE', '制品缺失或不含前端/预检/Runner迁移；由维护者提供完整预构建 Jar，入口不编译源码。')


def immutable_artifact(jar, directory):
    data = jar.read_bytes()
    digest = hashlib.sha256(data).hexdigest()
    destination = directory / 'artifacts' / digest / jar.name
    destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if not destination.exists():
        temporary = destination.with_suffix('.pending')
        with temporary.open('xb') as output:
            output.write(data)
        temporary.chmod(0o400)
        temporary.replace(destination)
    if hashlib.sha256(destination.read_bytes()).hexdigest() != digest:
        fail('ARTIFACT_DIGEST', '私有固定制品摘要不匹配；保留原文件，检查来源。')
    return destination, digest


def database_probe(java, jar, config, stage):
    command = [java, '-Dloader.config.location=classpath:/workbench-disabled-loader.properties',
               '-Dloader.home=' + str(jar.parent), '-Dloader.main=io.aitest.workbench.bootstrap.RuntimeProbe', '-cp', str(jar),
               'org.springframework.boot.loader.launch.PropertiesLauncher', stage]
    try:
        result = subprocess.run(command, env=runtime_env(config), capture_output=True, timeout=20)
        report = json.loads(result.stdout.decode().splitlines()[-1])
        if not isinstance(report, dict) or not report.get('ok') or result.returncode:
            code = report.get('code', 'DB_PROBE_FAILED') if isinstance(report, dict) else 'DB_PROBE_FAILED'
            if not isinstance(code, str) or not re.fullmatch(r'[A-Z_]+', code):
                code = 'DB_PROBE_FAILED'
            fail(code, '只读数据库预检失败；核对已授权实例、账号、TLS 和迁移历史。不会自动建库或 repair。')
        return {k: report[k] for k in ('ok', 'code', 'stage', 'platformVersion') if k in report}
    except RuntimeFailure:
        raise
    except subprocess.TimeoutExpired:
        fail('DB_PROBE_TIMEOUT', '只读数据库预检超时；检查已授权数据库是否运行和 TLS 配置。')
    except (OSError, ValueError, IndexError, TypeError):
        fail('DB_PROBE_FAILED', '制品中的只读预检未正常完成；检查 Java 与制品版本。')


@contextlib.contextmanager
def operation_lock(directory):
    try:
        import fcntl
    except ImportError:
        fail('PLATFORM_UNSUPPORTED', '当前开发入口需要 Linux 文件锁；用户交付的目标主机尚待确认。')
    directory_preflight(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    file = directory / 'operation.lock'
    with file.open('a') as handle:
        file.chmod(0o600)
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            fail('OPERATION_BUSY', '同一运行目录已有操作进行；结束后再用 status 核查。')
        previous_umask = os.umask(0o077)
        try:
            yield
        finally:
            os.umask(previous_umask)


def stop(directory, java):
    owned = owned_process(directory, java)
    if owned is None:
        (directory / 'platform.pid').unlink(missing_ok=True)
        return {'state': 'stopped'}
    if not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal'):
        fail('SAFE_SIGNAL_UNAVAILABLE', '当前平台不支持 pidfd；未发送信号，无法安全确认进程归属。')
    try:
        descriptor = os.pidfd_open(owned['pid'])
    except ProcessLookupError:
        (directory / 'platform.pid').unlink(missing_ok=True)
        return {'state': 'stopped'}
    try:
        confirmed = owned_process(directory, java)
        if confirmed is None:
            (directory / 'platform.pid').unlink(missing_ok=True)
            return {'state': 'stopped'}
        if any(confirmed[k] != owned[k] for k in ('pid', 'startTicks', 'bootId')):
            fail('PROCESS_OWNERSHIP_UNCERTAIN', '进程身份在停止前变化；未发送信号。')
        signal.pidfd_send_signal(descriptor, signal.SIGTERM)
        until = time.monotonic() + 20
        while time.monotonic() < until:
            if process_identity(owned['pid']) is None:
                (directory / 'platform.pid').unlink(missing_ok=True)
                return {'state': 'stopped', 'pid': owned['pid']}
            time.sleep(.2)
        fail('STOP_UNCONFIRMED', '优雅停止尚未确认；记录与数据保留，不强杀、不启动替代进程。')
    finally:
        os.close(descriptor)


def migrate(java, artifact, config, directory, location=None):
    command = [java, '-Dloader.config.location=classpath:/workbench-disabled-loader.properties',
               '-Dloader.home=' + str(artifact.parent), '-Dloader.main=io.aitest.workbench.bootstrap.Migrate', '-cp', str(artifact),
               'org.springframework.boot.loader.launch.PropertiesLauncher']
    if location:
        command.append(location)
    logfile = directory / ('migration-runner.log' if location else 'migration-platform.log')
    emit(ok=True, code='MIGRATION_RUNNING', stage='runner' if location else 'platform',
         message='使用独立账号检查/迁移原历史；不建库、不改账号，不自动重试未知结果。')
    with logfile.open('ab') as log:
        logfile.chmod(0o600)
        result = subprocess.run(command, env=runtime_env(config, migration=True), stdout=log, stderr=subprocess.STDOUT)
    # Do not kill an uncertain DDL/COMMIT or automatically retry it.
    if result.returncode:
        fail('MIGRATION_FAILED', '独立迁移失败；私有日志保留，服务未启动。先核查再继续，勿清库或盲目 repair。')


def start(java, jar, directory, config):
    check_port(config['WB_PORT'])
    artifact, digest = immutable_artifact(jar, directory)
    logfile = directory / 'platform-service.log'
    nonce = str(uuid.uuid4())
    environment = runtime_env(config)
    environment.update({'WB_RUNTIME_READY_FILE': str(directory / ('platform-ready-' + nonce + '.json')), 'WB_RUNTIME_READY_NONCE': nonce})
    with logfile.open('ab') as log:
        logfile.chmod(0o600)
        process = subprocess.Popen([java, '-jar', str(artifact)], env=environment, stdout=log,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    identity = process_identity(process.pid)
    if identity is None:
        fail('START_EXITED', '服务进程在启动时已退出；检查私有运行日志。')
    recorded = {'pid': process.pid, 'java': java, 'sourceJar': str(jar), 'artifactJar': str(artifact), 'sha256': digest,
                'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'startTicks': identity['startTicks'], 'bootId': identity['bootId'], 'readyNonce': nonce}
    metadata = directory / 'platform-process.json'
    metadata.write_text(json.dumps(recorded) + '\n')
    metadata.chmod(0o600)
    pidfile = directory / 'platform.pid'
    pidfile.write_text(str(process.pid))
    pidfile.chmod(0o600)
    until = time.monotonic() + 30
    while time.monotonic() < until:
        if process.poll() is not None:
            fail('START_EXITED', '启动失败，原记录与私有日志保留；先 doctor/status 核查，不自动重试。')
        try:
            http_health(process.pid, config['WB_PORT'], directory)
            return {'state': 'ready', 'pid': process.pid, 'sha256': digest}
        except RuntimeFailure:
            time.sleep(.2)
    fail('START_NOT_CONFIRMED', '启动就绪尚未确认；进程可能仍运行，先 status/doctor 核查，不会自动另起进程。')


def parser():
    result = argparse.ArgumentParser(description='合成环境统一入口：当前开发工具依赖 Linux、Java 21、Python 3.12；只管理已有获准数据库与本任务 JVM，不创建实例/账号，不代表最终用户安装器。')
    result.add_argument('action', choices=['template', 'doctor', 'status', 'up', 'start', 'stop', 'restart', 'migrate'])
    result.add_argument('--runtime-dir')
    result.add_argument('--config')
    result.add_argument('--java-home', default=os.environ.get('JAVA_HOME'))
    result.add_argument('--jar', default=str(default_jar()))
    result.add_argument('--migration-location')
    result.add_argument('--platform-migration-config')
    result.add_argument('--runner-migration-config')
    result.add_argument('--template-kind', choices=['runtime', 'migration-platform', 'migration-runner'], default='runtime')
    return result


def run(args):
    if args.action == 'template':
        try:
            content = template_file(args.template_kind).read_text(encoding='utf-8')
        except (OSError, UnicodeError):
            fail('TEMPLATE_UNAVAILABLE', '配置模板缺失或不可读；核对发行清单与校验和，从原发行包恢复模板，不复制私有运行配置。')
        print(content, end='')
        return
    if not args.runtime_dir or args.action != 'stop' and not args.config:
        fail('ARGUMENT_REQUIRED', '请提供 --runtime-dir 和私有 --config；stop 只需运行目录和原 Java。')
    directory = Path(args.runtime_dir).absolute()
    java = java_executable(args.java_home)
    if args.action == 'stop':
        with operation_lock(directory):
            emit(ok=True, code='STOPPED', **stop(directory, java))
        return
    migration = args.action == 'migrate'
    config = load_config(args.config, migration=migration)
    location = args.migration_location
    if location and not migration:
        fail('ARGUMENT_COMBINATION', '--migration-location 仅用于兼容独立 migrate 操作。')
    runner = location in ('classpath:db/runner-migration', 'filesystem:backend/execution-runner/src/main/resources/db/migration')
    if location and not runner:
        fail('MIGRATION_LOCATION_DENIED', '仅允许制品中的平台/Runner固定迁移，不能指定任意目录。')
    validate_target(config, 'workbench_runner' if runner else 'workbench_platform')
    directory_preflight(directory)
    if not migration:
        directory_preflight(Path(config['WB_BLOB_ROOT']))
    owned = owned_process(directory, java)
    if args.action == 'status':
        if owned is None:
            emit(ok=True, code='STOPPED', state='stopped')
            return
        http_health(owned['pid'], config['WB_PORT'], directory)
        emit(ok=True, code='READY', state='ready', pid=owned['pid'], sha256=owned['sha256'])
        return
    jar = Path(args.jar).resolve()
    artifact_preflight(jar)
    initialization = []
    if args.platform_migration_config or args.runner_migration_config:
        if args.action != 'up' or not args.platform_migration_config or not args.runner_migration_config:
            fail('MIGRATION_CONFIG_PAIR', '自动初始化只用于 up，且须同时提供两个已获准的独立迁移配置。')
        for path, database in ((args.platform_migration_config, 'workbench_platform'), (args.runner_migration_config, 'workbench_runner')):
            values = load_config(path, migration=True)
            validate_target(values, database)
            if values['WB_DB_URL'].replace('/' + database + '?', '/workbench_platform?') != config['WB_DB_URL']:
                fail('MIGRATION_TARGET_MISMATCH', '运行与迁移配置须指向同一获准实例及相同 TLS 配置。')
            if values['WB_MIGRATION_USER'] == config['WB_DB_USER']:
                fail('MIGRATION_ACCOUNT_SEPARATION', '迁移与运行账号必须分开，不能把 DDL 权限交给 Web 服务。')
            initialization.append((values, None if database == 'workbench_platform' else 'classpath:db/runner-migration'))
    if owned and (initialization or migration):
        fail('RUNNING_INITIALIZE_REFUSED', '受管服务仍运行，不能同时迁移；先核查并优雅 stop，保留原数据。')
    if args.action == 'doctor':
        if owned:
            http_health(owned['pid'], config['WB_PORT'], directory)
        else:
            check_port(config['WB_PORT'])
        probe = database_probe(java, jar, config, 'schema')
        emit(ok=True, code='PREFLIGHT_PASSED', state='ready' if owned else 'stopped', database=probe,
             note='只读预检通过；不代表新环境首装、真实身份或外部资源已验收。')
        return
    if not migration:
        database_probe(java, jar, config, 'database' if initialization else 'schema')
    with operation_lock(directory):
        current = owned_process(directory, java)
        if (initialization or migration) and current:
            fail('RUNNING_INITIALIZE_REFUSED', '另一受管服务已启动，未运行迁移。')
        if migration:
            artifact, _ = immutable_artifact(jar, directory)
            migrate(java, artifact, config, directory, 'classpath:db/runner-migration' if runner else None)
            emit(ok=True, code='MIGRATIONS_CHECKED', state='stopped')
            return
        if args.action == 'restart':
            stop(directory, java)
        elif current:
            http_health(current['pid'], config['WB_PORT'], directory)
            emit(ok=True, code='ALREADY_READY', state='ready', pid=current['pid'], sha256=current['sha256'])
            return
        if initialization:
            check_port(config['WB_PORT'])
            artifact, _ = immutable_artifact(jar, directory)
            for values, migration_location in initialization:
                migrate(java, artifact, values, directory, migration_location)
            database_probe(java, artifact, config, 'schema')
        emit(ok=True, code='READY', **start(java, jar, directory, config))


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        run(args)
        return 0
    except RuntimeFailure as failure:
        emit(ok=False, code=failure.code, message=failure.message)
        return 2
    except KeyboardInterrupt:
        emit(ok=False, code='INTERRUPTED', message='操作被中断，结果可能未确认；记录和数据保留，先 status/doctor 核查。')
        return 130
    except Exception:
        emit(ok=False, code='LOCAL_OPERATION_FAILED', message='本地操作未完成；保留原记录，检查私有日志、文件权限及入口版本。')
        return 2


if __name__ == '__main__':
    raise SystemExit(main())

"""Check the unified entry against an existing task-owned synthetic service.

No database/account/container provisioning or privilege changes. This check gracefully
stops/restarts only the process proved by synthetic_runtime, retaining data and logs.
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile

from synthetic_runtime import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-dir', required=True)
    parser.add_argument('--runtime-config', required=True)
    parser.add_argument('--java-home', required=True)
    parser.add_argument('--jar', default=str(ROOT / 'backend/platform-server/target/platform-server-0.1.0-SNAPSHOT.jar'))
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    directory, configfile = Path(args.runtime_dir).resolve(), Path(args.runtime_config).resolve()
    config = json.loads(configfile.read_text())
    expected_digest = hashlib.sha256(Path(args.jar).read_bytes()).hexdigest()
    report = {'scope': 'existing authorized synthetic service and schemas only; no provisioning or real environment install',
              'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'artifactSha256': expected_digest, 'cases': []}
    common = ['--runtime-dir', str(directory), '--config', str(configfile), '--java-home', args.java_home, '--jar', args.jar]
    script = str(ROOT / 'scripts/synthetic_runtime.py')

    def invoke(action, flags=None, code=None):
        result = subprocess.run([sys.executable, script, action, *(common if flags is None else flags)],
                                cwd=ROOT, capture_output=True, text=True, timeout=60)
        messages = [json.loads(line) for line in result.stdout.splitlines()]
        value = messages[-1]
        assert result.returncode == (0 if value['ok'] else 2), 'exit code must match structured outcome'
        if code:
            assert value['code'] == code, 'unexpected structured operation outcome'
        assert config['WB_DB_PASSWORD'] not in result.stdout + result.stderr, 'private credential in CLI output'
        return value

    def case(name, function):
        try:
            function()
            report['cases'].append({'name': name, 'result': 'pass'})
            print(name + ': pass', flush=True)
        except Exception as error:
            report['cases'].append({'name': name, 'result': 'fail', 'errorType': type(error).__name__})
            raise

    def metadata():
        return json.loads((directory / 'platform-process.json').read_text())

    def blobs():
        return {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in Path(config['WB_BLOB_ROOT']).rglob('*') if p.is_file()}

    try:
        initial = invoke('status', code='READY')
        assert initial['sha256'] == expected_digest, 'check requires the final fixed artifact'
        case('template_three_kinds_have_only_placeholder_secrets', lambda: templates())

        def readonly_doctor():
            before = {p.name: (p.stat().st_size, p.stat().st_mtime_ns) for p in directory.iterdir()}
            invoke('doctor', code='PREFLIGHT_PASSED')
            after = {p.name: (p.stat().st_size, p.stat().st_mtime_ns) for p in directory.iterdir()}
            assert before == after, 'doctor wrote runtime state'
        case('doctor_reads_current_schema_without_runtime_writes', readonly_doctor)

        def idempotent():
            before = metadata()
            result = invoke('up', code='ALREADY_READY')
            assert result['pid'] == before['pid'] and metadata() == before, 'up replay spawned or changed process'
        case('up_reuses_original_live_process', idempotent)

        def running_migration():
            flags = common + ['--platform-migration-config', str(configfile.parent / 'migration-platform-private.json'),
                              '--runner-migration-config', str(configfile.parent / 'migration-runner-private.json')]
            invoke('up', flags, 'RUNNING_INITIALIZE_REFUSED')
        case('running_service_rejects_concurrent_migration', running_migration)

        with tempfile.TemporaryDirectory(prefix='workbench-setup-', dir=directory.parent) as temporary:
            base = Path(temporary)
            absent_runtime = base / 'must-stay-absent'
            private = base / 'runtime-private.json'
            flags = ['--runtime-dir', str(absent_runtime), '--config', str(private), '--java-home', args.java_home, '--jar', args.jar]

            def missing():
                invoke('doctor', flags, 'CONFIG_UNREADABLE')
                assert not absent_runtime.exists()
            case('missing_config_fails_without_creating_runtime', missing)

            def permissions():
                private.write_text(json.dumps(config)); private.chmod(0o644)
                invoke('doctor', flags, 'CONFIG_PERMISSIONS')
                assert not absent_runtime.exists()
            case('readable_secret_config_is_rejected_without_permission_change', permissions)

            def occupied():
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0)); listener.listen()
                    values = config | {'WB_PORT': str(listener.getsockname()[1])}
                    private.write_text(json.dumps(values)); private.chmod(0o600)
                    invoke('doctor', flags, 'PORT_IN_USE')
                    assert listener.getsockname()[1] == int(values['WB_PORT'])
                    assert not absent_runtime.exists()
            case('occupied_port_reports_error_without_touching_other_listener', occupied)

            def unavailable_database():
                with socket.socket() as reserved:
                    reserved.bind(('127.0.0.1', 0))  # Bound, intentionally not listening; owned port, not another DB.
                    port = str(reserved.getsockname()[1])
                    with socket.socket() as available:
                        available.bind(('127.0.0.1', 0)); http_port = str(available.getsockname()[1])
                    values = config | {'WB_PORT': http_port, 'WB_DB_URL': re.sub(r'127\.0\.0\.1:[0-9]+/', '127.0.0.1:' + port + '/', config['WB_DB_URL'])}
                    private.write_text(json.dumps(values)); private.chmod(0o600)
                    invoke('doctor', flags, 'DATABASE_UNREACHABLE')
                    assert not absent_runtime.exists()
            case('unavailable_database_has_bounded_redacted_error', unavailable_database)

        original_blobs = blobs()
        migration_logs = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.glob('migration-*.log')}
        before_stop = metadata()

        def graceful_stop():
            invoke('stop', code='STOPPED')
            assert invoke('status', code='STOPPED')['state'] == 'stopped'
            assert blobs() == original_blobs
            assert metadata() == before_stop, 'stop must retain evidence'
        case('graceful_stop_retains_blobs_and_process_evidence', graceful_stop)

        def resume():
            started = invoke('up', code='READY')
            assert started['pid'] != before_stop['pid'] and started['sha256'] == expected_digest
            invoke('status', code='READY')
            assert blobs() == original_blobs
        case('up_restarts_existing_data_without_initialization', resume)

        def restart():
            before = metadata()
            result = invoke('restart', code='READY')
            assert result['pid'] != before['pid'] and result['sha256'] == expected_digest
            assert not (Path('/proc') / str(before['pid'])).exists() or (Path('/proc') / str(before['pid']) / 'stat').read_text().rsplit(')', 1)[1].split()[0] == 'Z'
            assert blobs() == original_blobs
            assert migration_logs == {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.glob('migration-*.log')}
            invoke('status', code='READY')
            invoke('doctor', code='PREFLIGHT_PASSED')
        case('restart_has_new_owned_pid_no_migration_or_blob_change', restart)
        report['finalState'] = invoke('status', code='READY')
    except Exception as error:
        report['failedCheckType'] = type(error).__name__
    finally:
        report['passed'] = sum(row['result'] == 'pass' for row in report['cases'])
        report['failed'] = sum(row['result'] == 'fail' for row in report['cases'])
        file = Path(args.output); file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({k: report.get(k) for k in ['passed', 'failed', 'failedCheckType', 'artifactSha256']}))
    return 1 if report.get('failedCheckType') or report['failed'] else 0


def templates():
    for kind in ['runtime', 'migration-platform', 'migration-runner']:
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/synthetic_runtime.py'), 'template', '--template-kind', kind],
                                capture_output=True, text=True, check=True)
        value = json.loads(result.stdout)
        assert 'REPLACE_' in value.get('WB_DB_PASSWORD', value.get('WB_MIGRATION_PASSWORD', ''))


if __name__ == '__main__':
    raise SystemExit(main())

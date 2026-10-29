"""Generate a review-only empty-database schema candidate. Never opens a database.

This is not a Flyway resource or an installation tool. The input SQL is the
synthetic development baseline, and this transformation is deliberately
fail-closed when any expected source boundary changes.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'backend/platform-server/src/main/resources/db/migration'
OUT = Path(__file__).resolve().parent / 'candidate'
FILES = {
    1: 'V1__platform.sql', 2: 'V2__title_source_and_quota_policy.sql',
    3: 'V3__scoped_content_review.sql', 4: 'V4__receipt_scope_and_synthetic_marker.sql',
    5: 'V5__frozen_quota_accounts.sql', 6: 'V6__context_manifests.sql',
    7: 'V7__typed_provider_journal.sql', 8: 'V8__resource_operation_consumption.sql',
    9: 'V9__local_auth_sessions.sql', 10: 'V10__local_accounts_and_login_limits.sql',
}
EXPECTED_SOURCE_HASHES = {'V10__local_accounts_and_login_limits.sql': '00f764339d5ae7785526670a0a44c637306b7f4d77eefc7e6d623984d49170f6', 'V1__platform.sql': '505b49670972b2de17eb2bcb818d28ea09f911af58fd8e0c016cc55a16e114bf', 'V2__title_source_and_quota_policy.sql': 'db78222168aa7c614ae69a99ae6f8760511913f7fc5ce3fab03fd365ce44b492', 'V3__scoped_content_review.sql': '839fd62b6031430a832c09c1406d6045fa8cedc2fa4666a29970222e5c3efe7d', 'V4__receipt_scope_and_synthetic_marker.sql': '20dcb68df9ef8b8b25bc8cc26bd5b8f4df2e575171f7ad4de75f7daada3153da', 'V5__frozen_quota_accounts.sql': '02e55e3f6462da9248cb9e1809f14bd1ef3b40fc492e055d8d29c4f61be312c3', 'V6__context_manifests.sql': '64cca5af6f8d60ef0ec435e35b96a6ee23e161fe2bcdac8954269ce9f187e61c', 'V7__typed_provider_journal.sql': '7cfdcd1d6db5e20ce5eaa4d89d5000c704e06a74802963833cf0cbb6649f62a7', 'V8__resource_operation_consumption.sql': '4b888a6f6a4a1848e1e7c2feec978e3f22f0acfd8a9723da1006c2b929adf81b', 'V9__local_auth_sessions.sql': '1866613c8f609a96349311b4c079b2d93f4fd568ce24bf35a478aa19a1bff51f'}
HEADER = '-- REVIEW ONLY: empty database candidate; not wired, verified, or authorized for deployment.\n'
MARKER = '''-- Inert sentinel only. No deployment UUID or tenant is allocated by this draft.
-- A future audited bootstrap migration must replace these constraints before initialization.
CREATE TABLE production_boundary (
 id INT NOT NULL PRIMARY KEY CHECK (id = 1),
 mode_name VARCHAR(40) NOT NULL CHECK (mode_name = 'production_uninitialized'),
 deployment_id BINARY(16) NULL CHECK (deployment_id IS NULL),
 tenant_id BINARY(16) NULL CHECK (tenant_id IS NULL)
) ENGINE=InnoDB;
INSERT INTO production_boundary (id, mode_name, deployment_id, tenant_id)
 VALUES (1, 'production_uninitialized', NULL, NULL);
'''

BOOTSTRAP_GUARD = """-- No row, token or deployment is created by this schema candidate.
-- A separate trusted local provisioner and eligibility verification do not yet exist.
-- The consumed guard never changes production_boundary or permits service startup.
CREATE TABLE production_bootstrap_guard (
 id INT NOT NULL PRIMARY KEY CHECK (id = 1),
 deployment_id BINARY(16) NOT NULL,
 token_hash BINARY(32) NULL,
 expires_at DATETIME(6) NOT NULL,
 state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BINARY(16) NULL,
 CONSTRAINT ck_production_bootstrap_state CHECK (
   (state = 'provisioned' AND token_hash IS NOT NULL AND tenant_id IS NULL)
   OR (state = 'consumed' AND token_hash IS NULL AND tenant_id IS NOT NULL
       AND tenant_id = UNHEX('00000000000040008000000000000001'))
 )
) ENGINE=InnoDB;
"""


def remove_once(source, pattern, label):
    result, count = re.subn(pattern, '', source, flags=re.MULTILINE | re.DOTALL)
    if count != 1:
        raise ValueError(f'{label}: expected exactly one recognized source block, got {count}')
    return result


def generate():
    found = {p.name for p in SOURCE.glob('V*.sql')}
    if found != set(FILES.values()):
        raise ValueError('Source migration set changed; review before regeneration')
    out = {}
    hashes = {}
    for version, name in FILES.items():
        raw = (SOURCE / name).read_bytes()
        text = raw.decode('utf-8')
        if '\r' in text:
            raise ValueError(f'{name}: non-LF source requires review')
        hashes[name] = hashlib.sha256(raw).hexdigest()
        if hashes[name] != EXPECTED_SOURCE_HASHES[name]:
            raise ValueError(f'{name}: source changed; explicit review required')
        if version == 1:
            # All trailing INSERTs are the known development tenant/members.
            marker = "INSERT INTO tenants(id) VALUES(UUID_TO_BIN('00000000-0000-4000-8000-000000000001'));"
            index = text.find(marker)
            if index < 0 or text.count(marker) != 1:
                raise ValueError('V1 seed boundary changed')
            seeds = text[index:]
            if not all(alias in seeds for alias in ("'admin'", "'reviewer'", "'alice'", "'bob'", "'pending'")) or not seeds.rstrip().endswith("END FROM subjects;"):
                raise ValueError('V1 seed block changed')
            text = text[:index]
        elif version == 2:
            text = remove_once(text, r"^UPDATE conversations SET title_source='automatic'.*?;\nUPDATE conversations c SET title_source='manual'.*?;\n", 'V2 old titles')
            text = remove_once(text, r'^INSERT INTO quota_policies\(tenant_id,subject_id\) SELECT tenant_id,subject_id FROM memberships;\n', 'V2 old policies')
        elif version == 4:
            text = remove_once(text, r'^CREATE TABLE synthetic_boundary \(.*?\);\nINSERT INTO synthetic_boundary VALUES\(1,\'isolated_synthetic_only\'\);\n', 'V4 test marker')
            text = remove_once(text, r'^UPDATE artifacts a JOIN conversations c .*?;\n', 'V4 old artifacts')
            text += MARKER
        elif version == 5:
            text = remove_once(text, r'^UPDATE quota_reservations r\n.*?\)=2;\n', 'V5 old fixture ledger')
        candidate_name = 'V4__receipt_scope_and_production_marker.sql' if version == 4 else name
        out[candidate_name] = (HEADER + text).encode('utf-8')
    out['V11__production_bootstrap_guard.sql'] = (HEADER + BOOTSTRAP_GUARD).encode('utf-8')
    manifest = {'kind': 'review_only_empty_database_candidate', 'source_sha256': hashes,
                'candidate_sha256': {name: hashlib.sha256(blob).hexdigest() for name, blob in out.items()},
                'draft_specific': ['V11__production_bootstrap_guard.sql'],
                'runtime_wired': False, 'database_tested': False}
    out['manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode('utf-8')
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true', help='write review-only candidate files')
    args = parser.parse_args()
    expected = generate()
    if args.write:
        OUT.mkdir(exist_ok=True)
        existing = {p.name for p in OUT.iterdir() if p.is_file()}
        if existing - set(expected) or any(not p.is_file() or p.is_symlink() for p in OUT.iterdir()):
            raise ValueError('Unexpected file in candidate directory')
        for name, data in expected.items():
            (OUT / name).write_bytes(data)
    else:
        if not OUT.is_dir() or {p.name for p in OUT.iterdir()} != set(expected):
            raise ValueError('Missing or extra candidate file')
        for name, data in expected.items():
            path = OUT / name
            if path.is_symlink() or not path.is_file() or path.read_bytes() != data:
                raise ValueError(f'Candidate mismatch: {name}')
    print(f'Candidate reproducibility OK: {len(FILES) + 1} migrations; no database accessed')


if __name__ == '__main__':
    main()

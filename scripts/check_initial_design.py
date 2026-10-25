"""Static design/material checks; no backend or resource calls."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
sys.dont_write_bytecode = True
from build_initial_prototype import build

ROOT = Path(__file__).resolve().parents[1]
checks = []


def check(name, condition):
    checks.append({'name': name, 'pass': bool(condition)})
    if not condition:
        raise AssertionError(name)


tables = json.loads((ROOT / 'docs/design/contracts/state-transitions.json').read_text(encoding='utf-8'))
check('five state layers', set(tables['layers']) == {'issue', 'run', 'node', 'binding', 'approval'})
ids = []
md = (ROOT / 'docs/design/STATE-MACHINES.md').read_text(encoding='utf-8')
for key, layer in tables['layers'].items():
    check(key + ' initial state', layer['initial'] in layer['states'])
    for row in layer['transitions']:
        ids.append(row['id'])
        check(row['id'] + ' complete transition', row['to'] in layer['states'] and all(x in layer['states'] for x in row['from']) and all(row.get(x) for x in ['event', 'actor', 'guard', 'atomic']))
        expected = '| ' + ' | '.join([row['id'], ' / '.join(row['from']), row['event'], row['to'], row['actor'], row['guard'], row['atomic']]) + ' |'
        check(row['id'] + ' markdown matches canonical table', expected in md)
check('unique transition IDs', len(ids) == len(set(ids)))
check('prototype build matches sources', (ROOT / 'docs/prototype/index.html').read_text(encoding='utf-8') == build())

files = [ROOT / name for name in ['README.md', 'CONTEXT.md', 'docs/README.md', 'docs/architecture/ADR-001.md', 'docs/reviews/DECISIONS-20261025.md']]
files += list((ROOT / 'docs/design').glob('*.md'))
files += [ROOT / 'docs/reviews/INITIAL-DESIGN-CHANGELOG-20261025.md', ROOT / 'docs/reviews/END-TO-END-REVIEW-20261025.md']
for file in files:
    for raw in re.findall(r'\]\(([^)]+)\)', file.read_text(encoding='utf-8')):
        if '://' in raw or raw.startswith('#'):
            continue
        target = raw.split('#', 1)[0]
        check('link ' + str(file.relative_to(ROOT)) + ' -> ' + target, (file.parent / target).exists())

decl = (ROOT / 'docs/design/contracts/execution-provider.d.ts').read_text(encoding='utf-8')
methods = set(re.findall(r'^  (\w+)\(context:', decl, re.M))
catalog = json.loads((ROOT / 'docs/design/contracts/conformance-cases.json').read_text(encoding='utf-8'))
check('twelve formal methods', len(methods) == 12)
check('conformance catalog methods exist', all(row['method'] in methods for row in catalog['cases']))
check('real providers remain untested', all(row['controlProvider'] == row['directProvider'] == 'not_run' for row in catalog['cases']))
examples = json.loads((ROOT / 'docs/design/contracts/examples.json').read_text(encoding='utf-8'))
check('examples use matching identity', examples['startRequest']['spec']['bindingId'] == examples['accepted']['binding']['bindingId'])
check('not_found never proves nonacceptance', examples['notFound']['authoritativeNonAcceptance'] is False)
check('unknown usage is null, not zero', examples['usageUnreported']['amount'] is None)
preparation = examples['startRequest']['spec']['preparation']
check('preparation refs are frozen', all(preparation.get(k) for k in ['inputRevision', 'configurationSnapshotRevision', 'admissionPolicyRevision', 'integrationSnapshotRef', 'runtimeManifestRef', 'contextManifestRef', 'authorizationRevision']))
check('preparation hashes are shaped', all(re.fullmatch(r'[0-9a-f]{64}', preparation[k]) for k in ['integrationSnapshotHash', 'runtimeManifestHash', 'contextManifestHash']))
check('unknown code versions stay nullable', 'codeCommit: string | null;' in decl and 'deployedCommit: string | null;' in decl)

manifest = json.loads((ROOT / 'SOURCE_MANIFEST.json').read_text(encoding='utf-8'))
git = ['git', '-c', 'safe.directory=' + ROOT.as_posix(), '-C', str(ROOT)]
crlf_count = 0
for item in manifest['original_text_files']:
    rel = 'docs/review-baseline-20261025/' + item['path']
    blob = subprocess.check_output(git + ['show', 'HEAD:' + rel])
    check('archive HEAD SHA ' + item['path'], len(blob) == item['bytes'] and hashlib.sha256(blob).hexdigest() == item['sha256'])
    work = (ROOT / rel).read_bytes()
    check('archive working text unchanged ' + item['path'], work == blob or work.replace(b'\r\n', b'\n') == blob)
    crlf_count += work != blob
print(json.dumps({'static_checks_passed': len(checks), 'transition_rows': len(ids), 'conformance_catalog': len(catalog['cases']), 'archive_HEAD_SHA_passed': len(manifest['original_text_files']), 'archive_local_CRLF_differences': crlf_count, 'limits': 'Static consistency only; declaration not compiled, no real Provider contract test. CRLF equivalence is not byte-identical archive verification.'}, ensure_ascii=False, indent=2))

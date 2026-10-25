"""Check declared design evidence and optional current offline browser evidence, never real acceptance."""
from pathlib import Path
import argparse
import hashlib
import json
import sys
sys.stdout.reconfigure(encoding='utf-8')

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--browser', action='store_true')
args = parser.parse_args()
catalog = json.loads((ROOT / 'docs/design/contracts/design-coverage.json').read_text(encoding='utf-8'))
assert [r['id'] for r in catalog['requirements']] == [f'D{i:02d}' for i in range(1, 12)]
browser = None
if args.browser:
    browser = json.loads((ROOT / 'docs/reviews/initial-design-checks/browser-report.json').read_text(encoding='utf-8'))
    assert browser['passed'] == browser['total']
    assert not browser['pageErrors'] and not browser['networkAttempts'] and not browser['overflows']
    for rel, expected in browser['sourceHashes'].items():
        assert hashlib.sha256((ROOT / rel).read_bytes()).hexdigest() == expected, 'Stale browser evidence: ' + rel
    cases = {c['name']: c['result'] for c in browser['cases']}
rows = []
for item in catalog['requirements']:
    evidence = []
    assert item['implementationStatus'] == 'not_started'
    for ref in item['designEvidence']:
        target = ROOT / ref['path']
        assert target.is_file() and ref['section'] in target.read_text(encoding='utf-8'), ref
        evidence.append({**ref, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    if browser:
        for case in item['prototypeCases']:
            assert cases.get(case) == 'pass', 'Missing/failed offline case: ' + case
    rows.append({'id': item['id'], 'designEvidencePresent': True, 'evidence': evidence,
                 'offlineCasesMatched': len(item['prototypeCases']) if browser else None,
                 'realImplementationStatus': 'not_started'})
print(json.dumps({'scope': 'Files/sections and offline cases only; semantic design review remains a human assessment.',
                  'requirements': rows, 'requirementCount': len(rows),
                  'browserSourcesCurrent': True if browser else None}, ensure_ascii=False, indent=2))

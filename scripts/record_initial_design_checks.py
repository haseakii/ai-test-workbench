"""Record current local design checks after browser-check.cjs has completed. Never launches resources."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/reviews/initial-design-checks'
node = os.environ.get('DESIGN_NODE') or shutil.which('node')
if not node:
    raise SystemExit('Set DESIGN_NODE to the existing Node executable; do not install or call a model.')
OUT.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1')
commands = [
    ([sys.executable, 'scripts/check_initial_design.py'], 'static-report.json'),
    ([node, '--test-reporter=tap', 'docs/prototype/tests/model.test.cjs'], 'model-tests.tap'),
    ([sys.executable, 'scripts/audit_design_scope.py', '--browser'], 'scope-audit.json'),
]
for command, output in commands:
    run = subprocess.run(command, cwd=ROOT, env=env, capture_output=True, text=True, encoding='utf-8', check=True)
    (OUT / output).write_text(run.stdout, encoding='utf-8', newline='\n')
    print(output + ': PASS')

sources = [ROOT / rel for rel in ['README.md', 'CONTEXT.md', 'docs/README.md', 'docs/architecture/ADR-001.md',
           'docs/reviews/DECISIONS-20261025.md', 'docs/roadmap/design-to-build.md',
           'docs/reviews/INITIAL-DESIGN-CHANGELOG-20261025.md', 'docs/reviews/END-TO-END-REVIEW-20261025.md', '.github/workflows/design-check.yml',
           'scripts/check_initial_design.py', 'scripts/build_initial_prototype.py',
           'scripts/audit_design_scope.py', 'scripts/record_initial_design_checks.py']]
sources += [p for folder in ['docs/design', 'docs/prototype'] for p in (ROOT / folder).rglob('*')
            if p.is_file() and p.name != 'index.html']

def record(p):
    data = p.read_bytes()
    return {'path': p.relative_to(ROOT).as_posix(), 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}

base = subprocess.check_output(['git', '-c', 'safe.directory=' + ROOT.as_posix(), '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
manifest = {
    'status': 'initial_design_review_only',
    'recordedAt': datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat(),
    'historicalBase': base,
    'scope': 'Complete design and offline synthetic prototype; no business implementation or real-resource acceptance.',
    'sources': [record(p) for p in sorted(set(sources))],
    'outputs': [record(ROOT / 'docs/prototype/index.html')] + [record(p) for p in sorted(OUT.rglob('*'))
               if p.is_file() and p.name != 'delivery-manifest.json'],
}
(OUT / 'delivery-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(f"Manifest: {len(manifest['sources'])} sources, {len(manifest['outputs'])} outputs")

from pathlib import Path
import hashlib, json
root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'SOURCE_MANIFEST.json').read_text(encoding='utf-8'))
for item in manifest['original_text_files']:
    p = root / 'docs/review-baseline-20261025' / item['path']
    data = p.read_bytes()
    if len(data) != item['bytes'] or hashlib.sha256(data).hexdigest() != item['sha256']:
        raise SystemExit('Source mismatch: ' + item['path'])
print('PASS:', len(manifest['original_text_files']), 'original UTF-8 snapshots match SHA-256.')

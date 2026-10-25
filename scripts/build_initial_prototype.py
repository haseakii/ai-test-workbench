"""Build the self-contained offline design prototype. No resources are connected."""
from pathlib import Path
import argparse
import json

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'docs/prototype'


def build():
    html = (BASE / 'shell.html').read_text(encoding='utf-8')
    tables = json.loads((ROOT / 'docs/design/contracts/state-transitions.json').read_text(encoding='utf-8'))
    parts = {
        'STYLES': (BASE / 'style.css').read_text(encoding='utf-8') + '\n' + (BASE / 'chat.css').read_text(encoding='utf-8') + '\n' + (BASE / 'admin.css').read_text(encoding='utf-8') + '\n' + (BASE / 'service-design.css').read_text(encoding='utf-8'),
        'SERVICE_DESIGN': (BASE / 'service-design.js').read_text(encoding='utf-8'),
        'JOURNEY': (BASE / 'journey.js').read_text(encoding='utf-8'),
        'CONFIGURATION': (BASE / 'configuration.js').read_text(encoding='utf-8'),
        'ADMIN': (BASE / 'admin.js').read_text(encoding='utf-8'),
        'CHAT': (BASE / 'chat.js').read_text(encoding='utf-8'),
        'TABLES': 'window.REVIEW_TABLES=' + json.dumps(tables, ensure_ascii=False) + ';',
        'MODEL': (BASE / 'model.js').read_text(encoding='utf-8'),
        'APP': (BASE / 'app.js').read_text(encoding='utf-8'),
    }
    for key, text in parts.items():
        marker = '/*__' + key + '__*/'
        if html.count(marker) != 1:
            raise ValueError('Missing/duplicate marker: ' + key)
        html = html.replace(marker, text)
    return html


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result = build()
    target = BASE / 'index.html'
    if args.check:
        if not target.exists() or target.read_text(encoding='utf-8') != result:
            raise SystemExit('FAIL: prototype is not rebuilt')
        print('PASS: self-contained prototype matches sources')
    else:
        target.write_text(result, encoding='utf-8', newline='\n')
        print('Built docs/prototype/index.html (offline synthetic demonstration)')

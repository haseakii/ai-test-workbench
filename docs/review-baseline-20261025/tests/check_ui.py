import os
import json, re, functools, threading
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from playwright.sync_api import sync_playwright
BASE=Path(__file__).resolve().parent.parent
ROUTES=['home','chat','history','result','knowledge','inbox','overview','tasks','task','approvals','workflows','editor','assistants','agents','skills','mcp','systems','users','quotas','audit','login','guide']
results={'routes':[],'errors':[],'missingActions':[], 'screenshots':[]}
with sync_playwright() as p:
 b=p.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH','/usr/bin/chromium'),headless=True,args=['--no-sandbox','--disable-dev-shm-usage'])
 page=b.new_page(viewport={'width':1440,'height':1000},device_scale_factor=1)
 page.on('pageerror',lambda e:results['errors'].append(str(e)))
 page.set_content((BASE/'index.html').read_text(), wait_until='load')
 page.evaluate('window.__prototype.reset()')
 for route in ROUTES:
  page.evaluate('(r)=>{location.hash="/"+r;render()}',route)
  page.wait_for_timeout(100)
  missing=page.evaluate("[...document.querySelectorAll('[data-action]')].map(x=>x.dataset.action).filter(x=>!actions[x])")
  results['missingActions']+=missing
  overflow=page.evaluate('document.documentElement.scrollWidth > window.innerWidth')
  results['routes'].append({'route':route,'h1':page.locator('h1').first.inner_text(),'bodyHorizontalOverflow':overflow})
  page.screenshot(path=str(BASE/'previews'/f'{route}.png'),full_page=True)
  results['screenshots'].append(route+'.png')
 results['missingActions']=list(set(results['missingActions']))
 for w,h in [(1366,768),(1024,900),(390,844)]:
  page.set_viewport_size({'width':w,'height':h})
  for r in ['chat','editor','approvals','tasks']:
   page.evaluate('(r)=>{location.hash="/"+r;render()}',r);page.wait_for_timeout(100)
   results['routes'].append({'route':r,'viewport':[w,h],'bodyHorizontalOverflow':page.evaluate('document.documentElement.scrollWidth > window.innerWidth')})
   if w==1366: page.screenshot(path=str(BASE/'previews'/f'{r}_{w}.png'),full_page=True)
 b.close()
(BASE/'tests'/'ui_report.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in results.items() if k!='screenshots'},ensure_ascii=False,indent=2))

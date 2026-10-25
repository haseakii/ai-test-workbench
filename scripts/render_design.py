"""Render synthetic prototype and reading copies without touching archived sources."""
from pathlib import Path
import json, os, shutil, subprocess, sys, tempfile, hashlib, datetime
from playwright.sync_api import sync_playwright
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'docs/review-baseline-20261025'
OUT=ROOT/'docs/generated/v0.2';OUT.mkdir(parents=True,exist_ok=True)
ROUTES=['home','chat','history','result','knowledge','inbox','overview','tasks','task','approvals','workflows','editor','assistants','agents','skills','mcp','systems','users','quotas','audit','login','guide']
report={'rendered_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'provenance':'Re-generated from original text sources; not the historical binary originals.','real_model_calls':0,'business_database_calls':0,'page_errors':[],'screenshots':[]}
with tempfile.TemporaryDirectory() as temp:
    work=Path(temp)/'baseline';shutil.copytree(BASE,work)
    (work/'previews').mkdir(exist_ok=True)
    with sync_playwright() as p:
        b=p.chromium.launch(headless=True,args=['--no-sandbox'])
        page=b.new_page(viewport={'width':1440,'height':1000},device_scale_factor=1)
        page.on('pageerror',lambda e:report['page_errors'].append(str(e)))
        page.set_content((work/'index.html').read_text(encoding='utf-8'),wait_until='load')
        page.evaluate('window.__prototype.reset()')
        for route in ROUTES:
            page.evaluate('(r)=>{location.hash="/"+r;render()}',route)
            page.wait_for_timeout(150)
            path=work/'previews'/f'{route}.png'
            page.screenshot(path=str(path),full_page=True)
            report['screenshots'].append(path.name)
        page.set_viewport_size({'width':1366,'height':768})
        for route in ['chat','editor','approvals','tasks']:
            page.evaluate('(r)=>{location.hash="/"+r;render()}',route)
            page.wait_for_timeout(150)
            path=work/'previews'/f'{route}_1366.png'
            page.screenshot(path=str(path),full_page=True)
            report['screenshots'].append(path.name)
        b.close()
    if report['page_errors']:
        raise RuntimeError('Prototype JS errors: '+str(report['page_errors']))
    # Run the historical generator only in the temporary copy.
    subprocess.run([sys.executable,str(work/'docs/make_design.py')],check=True)
    docx=work/'docs/页面交互与系统设计_v0.2.docx'
    subprocess.run(['libreoffice','-env:UserInstallation='+ (Path(temp)/'lo-profile').as_uri(),'--headless','--convert-to','pdf','--outdir',str(work/'docs'),str(docx)],check=True,timeout=180)
    pdf=docx.with_suffix('.pdf')
    if not pdf.is_file() or pdf.stat().st_size<10000:raise RuntimeError('PDF conversion failed')
    shutil.copytree(work/'previews',OUT/'previews',dirs_exist_ok=True)
    shutil.copy2(docx,OUT/'页面交互与系统设计_v0.2_仓库阅读版.docx')
    shutil.copy2(pdf,OUT/'页面交互与系统设计_v0.2_仓库阅读版.pdf')
    text=(BASE/'docs/设计说明与交互规范_v0.2.md').read_text(encoding='utf-8')
    text='> 仓库阅读副本：文字来自 v0.2 原稿；图片为源码再渲染。当前架构方向以 ../../architecture/ADR-001.md 为准。\n\n'+text.replace('../previews/','previews/')
    (OUT/'页面交互与系统设计_v0.2.md').write_text(text,encoding='utf-8')
    # Page images are QA aids, separate from reading documents.
    if shutil.which('pdftoppm'):
        qa=OUT/'qa';qa.mkdir(exist_ok=True)
        subprocess.run(['pdftoppm','-r','75','-png',str(pdf),str(qa/'page')],check=True,timeout=180)
        report['pdf_page_images']=len(list(qa.glob('page-*.png')))
report['outputs']=[{'path':p.relative_to(OUT).as_posix(),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(OUT.rglob('*')) if p.is_file()]
(OUT/'build-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(OUT/'README.md').write_text("""# v0.2 仓库阅读版

这些文件从保留的原型和设计源码重新生成，**不是历史 PDF/Word/截图的逐字节复制**。原件保留在资料库，哈希见 [归档说明](../../ARCHIVE-STATUS.md)。原始设计文字与可执行 HTML 已完整入库。

- [PDF 阅读版](页面交互与系统设计_v0.2_仓库阅读版.pdf)
- [可编辑 Word 阅读版](页面交互与系统设计_v0.2_仓库阅读版.docx)
- [含截图的 Markdown 阅读版](页面交互与系统设计_v0.2.md)
- [26 张页面及断点截图](previews/)
- [文档页面 QA 图](qa/)
- [本次构建记录](build-report.json)

当前仍是设计评审稿。生成成功不代表页面已获用户批准，也不是生产功能验收。历史 32 项检查只见原报告；本次记录截图、JS 运行错误和文档转换，不冒充历史测试重跑。
""",encoding='utf-8')
print('Generated',len(report['screenshots']),'screenshots and design reading copies.')

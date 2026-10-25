from pathlib import Path
root = Path(__file__).resolve().parent.parent
parts = ["start.html", "style.css", "body.html", "app.js"]
content = "".join((root / "source" / name).read_text(encoding="utf-8") for name in parts)
(root / "index.html").write_text(content + "\n</script></body></html>\n", encoding="utf-8")
print(root / "index.html")

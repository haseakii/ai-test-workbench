"""Isolated lifecycle fixture; only the caller's temporary marker is written."""
import json, os, pathlib, sys

mode, marker = sys.argv[1:3]
pathlib.Path(marker).write_text(str(os.getpid()), encoding="ascii")
for line in sys.stdin:
    request = json.loads(line)
    if mode == "timeout":
        continue
    if request.get("method") == "initialize":
        result = {"protocolVersion": 2, "agentCapabilities": {}, "authMethods": []}
    elif request.get("method") == "session/new":
        pathlib.Path(marker + ".session").write_text("called", encoding="ascii")
        result = {"sessionId": "must-not-create"}
    else:
        continue
    print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)

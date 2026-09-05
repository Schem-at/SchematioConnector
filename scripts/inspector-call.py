#!/usr/bin/env python3
"""Call the local MC-Inspector MCP and save image responses beside the test logs."""
import base64
import json
import os
from pathlib import Path
import sys
import urllib.request

payload = {'jsonrpc': '2.0', 'id': 1, 'method': 'tools/call',
           'params': {'name': sys.argv[1], 'arguments': json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}}}
request = urllib.request.Request('http://127.0.0.1:' + os.getenv('SCHEMATIO_INSPECTOR_PORT', '38271') + '/mcp', data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json'})
result = json.load(urllib.request.urlopen(request, timeout=60))
for i, block in enumerate(result.get('result', {}).get('content', [])):
    if block.get('type') == 'image':
        path = Path('build/release-readiness/inspector') / f'{sys.argv[1]}-{i}.png'
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(base64.b64decode(block.pop('data')))
        block['path'] = str(path.resolve())
print(json.dumps(result, indent=2))

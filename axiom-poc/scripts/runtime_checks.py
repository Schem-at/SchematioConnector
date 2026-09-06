"""Exercise the packaged adapter/controller through MCInspector in a disposable world.

The test temporarily swaps the API endpoint for a local delayed fixture server.
It restores the production endpoint and the original Axiom clipboard in finally.
It never invokes ImGui or changes the world's blocks.
"""
import base64
import gzip
import json
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from inspector import EVIDENCE, script


def tag(kind, name, body):
    name = name.encode()
    return bytes([kind]) + struct.pack('>H', len(name)) + name + body


def schematic(version=2, dimension=1, entities=False):
    fields = tag(3, 'Version', struct.pack('>i', version))
    fields += tag(3, 'DataVersion', struct.pack('>i', 4325))
    for axis in ['Width', 'Height', 'Length']:
        fields += tag(2, axis, struct.pack('>h', dimension))
    fields += tag(3, 'PaletteMax', struct.pack('>i', 1))
    fields += tag(10, 'Palette', tag(3, 'minecraft:stone', struct.pack('>i', 0)) + b'\0')
    fields += tag(7, 'BlockData', struct.pack('>i', 1) + b'\0')
    if entities:
        fields += tag(9, 'Entities', bytes([10]) + struct.pack('>i', 1) + b'\0')
    return gzip.compress(tag(10, '', fields + b'\0'))


PREFIX = """
var wrapper=Packages.com.moulberry.axiom.tools.ToolManager.getToolByIndex(32);
var tf=wrapper.getClass().getDeclaredField('customTool');tf.setAccessible(true);var tool=tf.get(wrapper);
function field(name){var f=tool.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
function method(name){var mm=tool.getClass().getDeclaredMethods();for(var i=0;i<mm.length;i++)if(mm[i].getName()==name){mm[i].setAccessible(true);return mm[i];}throw name;}
var clip=Packages.com.moulberry.axiom.clipboard.Clipboard.INSTANCE;
"""


def run(code):
    return script(PREFIX + code)['result']


results = {}
for name, body, error in [('valid', schematic(), None), ('version', schematic(version=7), 'Sponge'),
                           ('volume', schematic(dimension=200), '2 million'),
                           ('entities', schematic(entities=True), 'entities'),
                           ('malformed', b'not nbt', 'GZIP')]:
    encoded = base64.b64encode(body).decode()
    result = run("var c=java.lang.Class.forName('io.schemat.axiom.AxiomClipboardAdapter');"
                 "var methods=c.getDeclaredMethods();var p;for(var i=0;i<methods.length;i++)if(methods[i].getName()=='prepare')p=methods[i];p.setAccessible(true);"
                 f"try{{var result=p.invoke(null,java.util.Base64.getDecoder().decode('{encoded}'),'fixture');"
                 "JSON.stringify({ok:true,cells:result.blockRegion().count()});}catch(e){JSON.stringify({ok:false,error:String(e.javaException)+': '+String(e.javaException.getCause())});}")
    data = json.loads(result)
    assert data['ok'] == (error is None), data
    if error:
        assert error.lower() in data['error'].lower(), data
    results[name] = data

fixture = schematic()
started = threading.Event()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        started.set()
        time.sleep(1.5)
        self.send_response(200)
        self.send_header('Content-Length', str(len(fixture)))
        self.end_headers()
        try:
            self.wfile.write(fixture)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *_):
        pass


server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
run("java.lang.System.getProperties().put('schematio.test.api',field('api').get(tool));var current=clip.getClipboard();if(current!=null)java.lang.System.getProperties().put('schematio.test.clipboard',current);'saved'")


def endpoint(url):
    run("var c=java.lang.Class.forName('io.schemat.axiom.SchematioClient');var ctor=c.getDeclaredConstructors()[0];ctor.setAccessible(true);"
        f"field('api').set(tool,ctor.newInstance(java.net.URI.create('{url}')));'endpoint'")


def begin():
    started.clear()
    run("var b=java.lang.Class.forName('io.schemat.axiom.LibraryClient$Build').getDeclaredConstructors()[0];b.setAccessible(true);"
        "method('load').invoke(tool,b.newInstance('00000000-0000-0000-0000-000000000001','Delayed fixture','Test','fixture'));'started'")
    assert started.wait(3), 'Import did not start'


try:
    endpoint(f'http://127.0.0.1:{server.server_port}/api/v1')
    begin()
    run("method('cancel').invoke(tool);'cancelled'")
    time.sleep(2)
    results['cancel'] = json.loads(run("JSON.stringify({busy:field('busy').get(tool),status:String(field('status').get(tool)),unchanged:clip.getClipboard()===java.lang.System.getProperties().get('schematio.test.clipboard')})"))
    assert results['cancel']['unchanged'] and not results['cancel']['busy']
    run("var c=java.lang.Class.forName('io.schemat.axiom.AxiomClipboardAdapter');var mm=c.getDeclaredMethods();var p;for(var i=0;i<mm.length;i++)if(mm[i].getName()=='prepare')p=mm[i];p.setAccessible(true);"
        f"var initial=p.invoke(null,java.util.Base64.getDecoder().decode('{base64.b64encode(fixture).decode()}'),'original fixture');clip.setClipboard(initial);'fixture clipboard'")
    begin()
    run("clip['setClipboard(com.moulberry.axiom.clipboard.ClipboardObject)'](null);'clipboard changed during request'")
    time.sleep(2)
    results['staleClipboard'] = json.loads(run("JSON.stringify({busy:field('busy').get(tool),status:String(field('status').get(tool)),unchanged:clip.getClipboard()===null})"))
    assert results['staleClipboard']['unchanged'] and 'clipboard changed' in results['staleClipboard']['status']
finally:
    run("method('cancel').invoke(tool);clip['setClipboard(com.moulberry.axiom.clipboard.ClipboardObject)'](java.lang.System.getProperties().remove('schematio.test.clipboard'));'restored'")
    run("field('api').set(tool,java.lang.System.getProperties().remove('schematio.test.api'));'service restored'")
    server.shutdown()

EVIDENCE.mkdir(parents=True, exist_ok=True)
(EVIDENCE / 'runtime-checks.json').write_text(json.dumps(results, indent=2))
print(json.dumps(results, indent=2))

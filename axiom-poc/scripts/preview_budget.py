"""Measure a max-region preview and cancel it through the real panel lifecycle."""
import gzip
import json
import statistics
import struct
import time
from inspector import EVIDENCE, script, capture


def tag(kind, name, body):
    name = name.encode()
    return bytes([kind]) + struct.pack('>H',len(name)) + name + body

n = 96
fields = tag(3,'Version',struct.pack('>i',2)) + tag(3,'DataVersion',struct.pack('>i',4325))
for axis in ['Width','Height','Length']:
    fields += tag(2,axis,struct.pack('>h',n))
fields += tag(3,'PaletteMax',struct.pack('>i',1))
fields += tag(10,'Palette',tag(3,'minecraft:stone',struct.pack('>i',0)) + b'\0')
fields += tag(9,'BlockEntities',bytes([10])+struct.pack('>i',0))
fields += tag(7,'BlockData',struct.pack('>i',n**3)) + bytes(n**3)
path = EVIDENCE / 'large-preview.schem'
path.write_bytes(gzip.compress(tag(10,'',fields+b'\0')))
PREFIX = """
var panels=Packages.io.schemat.connector.fabric.client.ui.framework.PanelManager.INSTANCE;
var c=Packages.io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel.INSTANCE;
function field(o,n){var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
"""


def run(code):
    return script(PREFIX+code)['result']


def begin():
    return json.loads(run("var start=java.lang.System.nanoTime();c.show(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("+json.dumps(str(path))+")),new JavaAdapter(Packages.kotlin.jvm.functions.Function1,{invoke:function(png){return Packages.kotlin.Unit.INSTANCE;}}));JSON.stringify({openMs:(java.lang.System.nanoTime()-start)/1e6})"))


run("if(Packages.com.moulberry.axiom.editor.EditorUI.isActive())Packages.com.moulberry.axiom.editor.EditorUI.toggleEnabled();panels.closeAll();mc.options.pauseOnLostFocus=false;mc.options.inactivityFpsLimit().set(Packages.net.minecraft.client.InactivityFpsLimit.MINIMIZED);Packages.org.lwjgl.glfw.GLFW.glfwFocusWindow(window.handle());'ready'")
first = begin()
time.sleep(.15)
run("panels.close('preview-composer');'cancel'")
time.sleep(1)
closed = json.loads(run("JSON.stringify({source:field(c,'source')==null,prepared:field(c,'prepared')==null,job:field(c,'decodeJob')==null})"))
assert all(closed.values()), closed
second = begin()
rows=[]
start=time.monotonic()
while time.monotonic()-start < 45:
    sample=json.loads(run("var src=field(c,'source');JSON.stringify({frameMs:mc.getFrameTimeNs()/1e6,source:src!=null,ready:src!=null&&Packages.io.schemat.connector.fabric.client.render.OffscreenSchematicRenderer.INSTANCE.isPrepared(src),error:String(field(c,'errorMessage'))})"))
    rows.append(sample)
    assert sample['error']=='null', sample
    if sample['ready']: break
    time.sleep(.05)
assert rows[-1]['ready'], rows[-1]
capture('connector-large-preview')
frames=sorted(row['frameMs'] for row in rows)
result={'cells':n**3,'cancelledOpen':first,'cancelReleased':closed,'fullOpen':second,'readySeconds':time.monotonic()-start,'samples':len(rows),'medianFrameMs':statistics.median(frames),'p95FrameMs':frames[int(len(frames)*.95)-1],'maxSampledFrameMs':max(frames),'rows':rows}
run("panels.close('preview-composer');'closed'")
(EVIDENCE/'preview-budget.json').write_text(json.dumps(result,indent=2))
print(json.dumps({k:v for k,v in result.items() if k!='rows'},indent=2))

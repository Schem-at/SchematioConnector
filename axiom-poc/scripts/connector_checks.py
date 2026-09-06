"""Packaged Connector checks through MCInspector; disposable local world only.

Opens editor panels and captures a local upload preview, but never uploads or places blocks.
"""
import json
import time
from pathlib import Path
from inspector import EVIDENCE, capture, script, text_call

RESULTS = {}
PREFIX = """
var editor=Packages.com.moulberry.axiom.editor.EditorUI;
var panel=Packages.dev.harrison.panellib.framework;
var panels=Packages.io.schemat.connector.fabric.client.ui.framework.PanelManager.INSTANCE;
var services=Packages.io.schemat.connector.fabric.client.SchematioClientMod.Companion.getInstance().getServices();
function field(object,name){var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f;}
function invoke(object,name){var mm=object.getClass().getDeclaredMethods();for(var i=0;i<mm.length;i++)if(String(mm[i].getName()).split('$')[0]==name&&mm[i].getParameterCount()==0){mm[i].setAccessible(true);return mm[i].invoke(object);}throw name;}
function callbacks(){var g=Packages.org.lwjgl.glfw.GLFW;var m=g.glfwSetMonitorCallback(null);g.glfwSetMonitorCallback(m);var c=g.glfwSetCursorEnterCallback(window.handle(),null);g.glfwSetCursorEnterCallback(window.handle(),c);return {monitor:m==null?null:String(m.address()),cursor:c==null?null:String(c.address())};}
"""


def run(code):
    return script(PREFIX + code)['result']


def state():
    return json.loads(run("JSON.stringify({axiom:editor.isActive(),panel:panel.Overlay.isVisible(),width:window.getWidth(),height:window.getHeight(),thumbs:String(services.getPreviewImages().stats()),callbacks:callbacks()})"))


script("mc.options.pauseOnLostFocus=false;mc.gui.setScreen(null);\'closed vanilla menu\'")
run("if(!editor.isActive())editor.toggleEnabled();'Axiom active'")
time.sleep(1)
before = state()
run("editor.toggleEnabled();panels.open(Packages.io.schemat.connector.fabric.client.ui.panels.BrowsePanel.INSTANCE);'browse'")
time.sleep(3)
opened = state()
assert opened['panel'] and not opened['axiom'], opened
assert before['callbacks'] == opened['callbacks'], (before, opened)
RESULTS['callbackOwnership'] = {'before': before, 'afterPanelInit': opened}
RESULTS['handoffs'] = []
for _ in range(4):
    run("editor.toggleEnabled();'switch'")
    time.sleep(.7)
    axiom = state()
    assert axiom['axiom'] and not axiom['panel'], axiom
    run("editor.toggleEnabled();'switch'")
    time.sleep(.7)
    connector = state()
    assert connector['panel'] and not connector['axiom'], connector
    assert connector['width'] == opened['width'] and connector['height'] == opened['height'], connector
    assert connector['callbacks'] == before['callbacks'], connector
    RESULTS['handoffs'].append({'axiom': axiom, 'connector': connector})
capture('connector-handoff-browse')

# Preview and submission both enter captureSource. Changing the original file must
# not change a previously captured build; only Refresh source may invalidate it.
fixture = Path(__file__).resolve().parents[2] / 'fabric/src/test/resources/schematic/single_stone.schem'
local = EVIDENCE / 'upload-source.schem'
EVIDENCE.mkdir(parents=True, exist_ok=True)
original = fixture.read_bytes()
local.write_bytes(original)
run("panels.closeAll();var w=Packages.io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.INSTANCE;"
    "w.open(new Packages.io.schemat.connector.fabric.client.integration.ExportSource(" + json.dumps(str(local)) + ", 'Snapshot fixture', Packages.io.schemat.connector.fabric.client.integration.SourceKind.LOCAL_FILE));"
    "Packages.io.schemat.connector.fabric.client.ui.panels.upload.UploadPreviewKt.generatePreview(w);'preview'")
time.sleep(3)
SNAPSHOT = """
var w=Packages.io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.INSTANCE;
var c=Packages.io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel.INSTANCE;
var bytes=field(w,'frozenBytes').get(w);var src=field(c,'source').get(c);
JSON.stringify({frozen:bytes==null?null:java.util.Base64.getEncoder().encodeToString(bytes),source:src!=null,error:String(field(c,'errorMessage').get(c))})
"""
snapshot = json.loads(run(SNAPSHOT))
assert snapshot['frozen'] and snapshot['source'] and snapshot['error'] == 'null', snapshot
run("var c=Packages.io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel.INSTANCE;invoke(c,'beginCapture');'capture PNG'")
for _ in range(100):
    png_size = int(run("var w=Packages.io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.INSTANCE;var png=field(w,'capturedPreviewPng').get(w);String(png==null?0:png.length)"))
    if png_size: break
    time.sleep(.05)
assert png_size > 0, 'No PNG captured'
local.write_bytes(b'file changed after preview')
run("panels.close('preview-composer');var w=Packages.io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.INSTANCE;Packages.io.schemat.connector.fabric.client.ui.panels.upload.UploadPreviewKt.generatePreview(w);'preview again'")
time.sleep(2)
reused = json.loads(run(SNAPSHOT))
assert reused == snapshot, (snapshot, reused)
RESULTS['frozenUpload'] = {'capturedPngBytes': png_size, 'sameBytesAfterFileChanged': True, 'previewReloadedOriginal': reused['source']}
capture('connector-frozen-preview')
run("panels.close('preview-composer');var w=Packages.io.schemat.connector.fabric.client.ui.panels.UploadWizardPanel.INSTANCE;invoke(w,'invalidateSnapshot');'refresh'")
refreshed = json.loads(run(SNAPSHOT))
assert refreshed['frozen'] is None and not refreshed['source'], refreshed
RESULTS['frozenUpload']['refreshClearsBytesAndPreview'] = True
local.write_bytes(original)
run("panels.closeAll();'closed'")
RESULTS['closedComposer'] = json.loads(run("var c=Packages.io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel.INSTANCE;var e=Packages.io.schemat.connector.fabric.client.render.SchematicRenderEngine.INSTANCE;JSON.stringify({source:field(c,'source').get(c)==null,prepared:field(c,'prepared').get(c)==null,decodeJob:field(c,'decodeJob').get(c)==null,target:field(e,'target').get(e)==null})"))
assert all(RESULTS['closedComposer'].values()), RESULTS['closedComposer']
(EVIDENCE / 'connector-checks.json').write_text(json.dumps(RESULTS, indent=2))
print(json.dumps(RESULTS, indent=2))

"""Verify the packaged Connector with none of the optional editors installed."""
import json
import time
from pathlib import Path
from inspector import script, capture, EVIDENCE

result = script("var b=Packages.io.schemat.connector.fabric.client.integration.Bridges.INSTANCE;var a=Packages.io.schemat.connector.fabric.client.integration.axiom.AxiomIntegration.INSTANCE;var l=Packages.net.fabricmc.loader.api.FabricLoader.getInstance();mc.options.pauseOnLostFocus=false;mc.gui.setScreen(null);Packages.io.schemat.connector.fabric.client.ui.framework.PanelManager.INSTANCE.open(Packages.io.schemat.connector.fabric.client.ui.panels.BrowsePanel.INSTANCE);JSON.stringify({axiomInstalled:l.isModLoaded('axiom'),litematicaInstalled:l.isModLoaded('litematica'),worldeditInstalled:l.isModLoaded('worldedit'),axiomAvailable:a.getAvailable(),litematicaAvailable:b.getLitematica().isAvailable(),worldeditAvailable:b.getWorldEdit().isAvailable()})")
data = json.loads(result['result'])
assert not any(data.values()), data
time.sleep(3)
data['browseVisible'] = json.loads(script("JSON.stringify(Packages.dev.harrison.panellib.framework.Overlay.isVisible())")['result'])
assert data['browseVisible']
fixture = Path(__file__).resolve().parents[2]/'fabric/src/test/resources/schematic/single_stone.schem'
script("Packages.io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel.INSTANCE.show(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("+json.dumps(str(fixture))+")),new JavaAdapter(Packages.kotlin.jvm.functions.Function1,{invoke:function(png){return Packages.kotlin.Unit.INSTANCE;}}));'preview'")
time.sleep(3)
data['previewReady'] = json.loads(script("var c=Packages.io.schemat.connector.fabric.client.ui.panels.PreviewComposerPanel.INSTANCE;var f=c.getClass().getDeclaredField('source');f.setAccessible(true);var src=f.get(c);JSON.stringify(src!=null&&Packages.io.schemat.connector.fabric.client.render.OffscreenSchematicRenderer.INSTANCE.isPrepared(src))")['result'])
assert data['previewReady']
capture('connector-without-editors')
script("Packages.io.schemat.connector.fabric.client.ui.framework.PanelManager.INSTANCE.closeAll();'closed'")
(EVIDENCE/'without-editors.json').write_text(json.dumps(data,indent=2))
print(json.dumps(data,indent=2))

"""Test optional editor commit guards against the installed mods in singleplayer."""
import json
import time
from pathlib import Path
from inspector import EVIDENCE, script

PREFIX = """
var bridges=Packages.io.schemat.connector.fabric.client.integration.Bridges.INSTANCE;
var properties=java.lang.System.getProperties();
var we=Packages.com.sk89q.worldedit.WorldEdit.getInstance();
var server=mc.getSingleplayerServer();
function session(){return we.getSessionManager().get(Packages.com.sk89q.worldedit.fabric.FabricAdapter.get().fromNativePlayer(server.getPlayerList().getPlayer(player.getUUID())));}
function callback(key){return new JavaAdapter(Packages.kotlin.jvm.functions.Function2,{invoke:function(ok,error){properties.put(key,JSON.stringify({ok:String(ok)=='true',error:String(error)}));return Packages.kotlin.Unit.INSTANCE;}});}
"""


def run(code):
    return script(PREFIX+code)['result']


def await_result(key):
    for _ in range(100):
        result = run("String(properties.remove(" + json.dumps(key) + "))")
        if result != 'null': return json.loads(result)
        time.sleep(.05)
    raise AssertionError('No callback for '+key)


fixture = Path(__file__).resolve().parents[2]/'fabric/src/test/resources/schematic/single_stone.schem'
READ = "java.nio.file.Files.readAllBytes(java.nio.file.Path.of(" + json.dumps(str(fixture)) + "))"
CHECK = "new JavaAdapter(Packages.kotlin.jvm.functions.Function0,{invoke:function(){return null;}})"
run("bridges.getWorldEdit().bytesToClipboard("+READ+",'schem',"+CHECK+",callback('test.we.first'));'loading'")
first = await_result('test.we.first')
assert first['ok'], first
run("bridges.getWorldEdit().captureImportCheck(new JavaAdapter(Packages.kotlin.jvm.functions.Function2,{invoke:function(check,error){if(check!=null)properties.put('test.we.check',check);properties.put('test.we.capture',JSON.stringify({ok:check!=null,error:String(error)}));return Packages.kotlin.Unit.INSTANCE;}}));'capture'")
captured = await_result('test.we.capture')
assert captured['ok'], captured
run("server.execute(new JavaAdapter(java.lang.Runnable,{run:function(){session().setClipboard(new Packages.com.sk89q.worldedit.session.ClipboardHolder(new Packages.com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard(new Packages.com.sk89q.worldedit.regions.CuboidRegion(Packages.com.sk89q.worldedit.math.BlockVector3.ZERO,Packages.com.sk89q.worldedit.math.BlockVector3.ZERO))));properties.put('test.we.replacement',session().getClipboard());properties.put('test.we.changed',JSON.stringify({ok:true}));}}));'change clipboard'")
await_result('test.we.changed')
run("bridges.getWorldEdit().bytesToClipboard("+READ+",'schem',properties.remove('test.we.check'),callback('test.we.stale'));'load with old ticket'")
stale=await_result('test.we.stale')
assert not stale['ok'] and 'clipboard changed' in stale['error'], stale
run("server.execute(new JavaAdapter(java.lang.Runnable,{run:function(){properties.put('test.we.preserved',JSON.stringify({ok:session().getClipboard()===properties.remove('test.we.replacement')}));}}));'check'")
preserved=await_result('test.we.preserved')
assert preserved['ok'], preserved
# Load a real Litematica placement, capture it, then change the selection.
local=EVIDENCE/'destination-fixture.litematic'
run("var schematic=Packages.com.github.schemat.nucleation.Schematic.fromBytes("+READ+");try{java.nio.file.Files.write(java.nio.file.Path.of("+json.dumps(str(local))+"),schematic.toLitematic());}finally{schematic.close();}'litematic fixture'")
FILE="new java.io.File("+json.dumps(str(local))+")"
run("bridges.getLitematica().loadSchematic("+FILE+",'Destination fixture',"+CHECK+",callback('test.lit.first'));'load Litematica'")
lit=await_result('test.lit.first')
assert lit['ok'], lit
run("properties.put('test.lit.original',Packages.fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement());properties.put('test.lit.source',bridges.getLitematica().currentSelectionSource());properties.put('test.lit.check',bridges.getLitematica().captureImportCheck());Packages.fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().setSelectedSchematicPlacement(null);'selection changed'")
run("bridges.getLitematica().loadSchematic("+FILE+",'Rejected fixture',properties.remove('test.lit.check'),callback('test.lit.stale'));'stale Litematica load'")
lit_stale=await_result('test.lit.stale')
assert not lit_stale['ok'] and 'placement changed' in lit_stale['error'], lit_stale
# A second placement with the same name must not replace the chosen upload source.
run("bridges.getLitematica().loadSchematic("+FILE+",'Destination fixture',"+CHECK+",callback('test.lit.second'));'second same-name placement'")
assert await_result('test.lit.second')['ok']
run("Packages.fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager().removeSchematicPlacement(properties.remove('test.lit.original'));bridges.getLitematica().exportToBytes(properties.remove('test.lit.source'),new JavaAdapter(Packages.kotlin.jvm.functions.Function2,{invoke:function(bytes,error){properties.put('test.lit.sourceResult',JSON.stringify({exported:bytes!=null,error:String(error)}));return Packages.kotlin.Unit.INSTANCE;}}));'export removed source'")
source_result=await_result('test.lit.sourceResult')
assert not source_result['exported'] and 'no longer loaded' in source_result['error'], source_result
moved=json.loads(run("var manager=Packages.fi.dy.masa.litematica.data.DataManager.getSchematicPlacementManager();var selected=manager.getSelectedSchematicPlacement();var origin=selected.getOrigin();var check=bridges.getLitematica().captureImportCheck();selected.setOrigin(origin.offset(1,0,0),null);var error=String(check.invoke());selected.setOrigin(origin,null);JSON.stringify({samePlacement:manager.getSelectedSchematicPlacement()===selected,error:error})"))
assert moved['samePlacement'] and 'placement changed' in moved['error'], moved
result={'worldedit' :{'loaded':first,'stale':stale,'replacementPreserved':preserved},'litematica':{'loaded':lit,'stale':lit_stale,'sameNameSourceNotSubstituted':source_result,'movedPlacementRejected':moved}}
(EVIDENCE/'destination-checks.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result,indent=2))

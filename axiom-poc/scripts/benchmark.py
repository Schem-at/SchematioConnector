"""Run with the Schematio search populated and an idle Axiom clipboard preview.

Samples Minecraft's frame processing timer, not GPU time or end-to-end latency.
MCInspector adds the same probe overhead to each condition.
"""
import json
import statistics
import time
from inspector import EVIDENCE, capture, move, script

samples = []
script("mc.options.inactivityFpsLimit().set(Packages.net.minecraft.client.InactivityFpsLimit.MINIMIZED); "
       "mc.options.pauseOnLostFocus=false; 'inactivity throttle disabled for this run'")
move(200, 830)
for name, index in [("Axiom tool", 1), ("Schematio", 32), ("Schematio", 32), ("Axiom tool", 1)]:
    script(f"Packages.com.moulberry.axiom.tools.ToolManager.setCurrentToolIndex({index}); "
           "Packages.com.moulberry.axiom.tools.ToolManager.setToolSelected(true); 'selected'")
    time.sleep(2)
    capture('benchmark-' + name.replace(' ', '-'))
    for _ in range(80):
        sample = json.loads(script("JSON.stringify({frameMs:mc.getFrameTimeNs()/1e6,fps:mc.getFps(),"
                                   "tool:Packages.com.moulberry.axiom.tools.ToolManager.getCurrentToolIndex(),"
                                   "active:Packages.com.moulberry.axiom.tools.ToolManager.isToolActive()})")["result"])
        assert sample['active'] and sample['tool'] == index, sample
        sample["condition"] = name
        samples.append(sample)
        time.sleep(.08)

summary = {}
for name in ["Axiom tool", "Schematio"]:
    rows = [row for row in samples if row["condition"] == name]
    frames = sorted(row["frameMs"] for row in rows)
    summary[name] = {"samples": len(rows), "medianMs": statistics.median(frames),
                     "p95Ms": frames[int(len(frames) * .95) - 1],
                     "medianFps": statistics.median(row["fps"] for row in rows),
                     "fpsRange": [min(row["fps"] for row in rows), max(row["fps"] for row in rows)]}
EVIDENCE.mkdir(parents=True, exist_ok=True)
(EVIDENCE / "frame-samples.json").write_text(json.dumps({"summary": summary, "samples": samples}, indent=2))
print(json.dumps(summary, indent=2))

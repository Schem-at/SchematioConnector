"""Test-only MCInspector helpers. Axiom handles GLFW input before Minecraft's handlers.

Its own callback entry points are used for editor interactions, without touching
the installed callback chain or calling ImGui directly. This file is not in the mod.
"""
import base64
import json
import os
from pathlib import Path
import time
import urllib.request

PORT = int(os.getenv("SCHEMATIO_INSPECTOR_PORT", "38272"))
EVIDENCE = Path(__file__).resolve().parents[1] / "build/evidence"


def call(name, arguments=None):
    data = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": name, "arguments": arguments or {}}}).encode()
    request = urllib.request.Request(f"http://127.0.0.1:{PORT}/mcp", data=data,
                                    headers={"Content-Type": "application/json"})
    result = json.load(urllib.request.urlopen(request, timeout=60))["result"]
    if result.get("isError"):
        raise RuntimeError(result)
    return result


def text_call(name, arguments=None):
    result = call(name, arguments)
    texts = [item["text"] for item in result.get("content", []) if item["type"] == "text"]
    return json.loads(texts[0]) if texts else None


def script(code):
    return text_call("exec_script", {"code": code})


def move(x, y):
    script("Packages.org.lwjgl.glfw.GLFW.glfwFocusWindow(window.handle()); "
           "Packages.com.moulberry.axiom.editor.EditorUI.imguiGlfw.windowFocusCallback(window.handle(),true); "
           f"Packages.org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.handle(), {x}, {y}); "
           f"Packages.com.moulberry.axiom.editor.EditorUI.imguiGlfw.cursorPosCallback(window.handle(), {x}, {y}); 'moved'")
    time.sleep(0.15)


def click(x, y):
    move(x, y)
    script("Packages.com.moulberry.axiom.editor.EditorUI.imguiGlfw.mouseButtonCallback(window.handle(),0,1,0); 'down'")
    time.sleep(0.12)
    script("Packages.com.moulberry.axiom.editor.EditorUI.imguiGlfw.mouseButtonCallback(window.handle(),0,0,0); 'up'")
    time.sleep(0.15)


def key(code, modifiers=0):
    script(f"Packages.com.moulberry.axiom.editor.EditorUI.imguiGlfw.keyCallback(window.handle(),{code},0,1,{modifiers}); 'down'")
    time.sleep(0.12)
    script(f"Packages.com.moulberry.axiom.editor.EditorUI.imguiGlfw.keyCallback(window.handle(),{code},0,0,0); 'up'")
    time.sleep(0.15)


def type_text(value):
    for character in value:
        script(f"Packages.com.moulberry.axiom.editor.EditorUI.imguiGlfw.charCallback(window.handle(),{ord(character)}); 'typed'")


def capture(name):
    result = call("screenshot", {"scale": 0.5})
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    for item in result["content"]:
        if item["type"] == "image":
            path = EVIDENCE / (name + ".png")
            path.write_bytes(base64.b64decode(item["data"]))
            return str(path)


if __name__ == "__main__":
    import sys
    print(json.dumps(text_call(sys.argv[1], json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}), indent=2))

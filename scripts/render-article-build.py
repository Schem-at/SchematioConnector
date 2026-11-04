"""Render the real example build for the Kineglyph article figures.

Run with a Nucleation Python environment:
  python scripts/render-article-build.py copperlight-observatory.schem resource-pack.zip
Then run render-article-figures.mjs. No Minecraft client or WebGL embed is needed.
"""
import argparse
from pathlib import Path
from nucleation import Schematic, ResourcePack, RenderConfig, Renderer

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('schematic', type=Path)
parser.add_argument('pack', type=Path)
args = parser.parse_args()
article = Path(__file__).resolve().parents[1] / 'docs/article'
config = RenderConfig.create(1000, 900)
config.set_isometric()
config.set_yaw(35)
config.set_pitch(28)
config.set_zoom(1.38)
config.set_background(0, 0, 0, 0)
config.set_ambient_light(.5)
Renderer.render_to_file_with_pack(
    Schematic.load_from_file(str(args.schematic)),
    ResourcePack.from_bytes(list(args.pack.read_bytes())),
    config, str(article / 'media/copperlight-render.png'),
)

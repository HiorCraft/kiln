"""Generates the assets of the demo extension `kiln_content` (example/server/.../extensions/kiln_content/assets).

Everything is synthesised here (no third-party art or sound), deterministic except for the Vorbis encoder:
- textures/item/ruby.png        16x16 gem
- textures/font/runes.png       8x8 rune glyphs for A-Z (bitmap font kiln_content:runes)
- sounds/ruby_chime.ogg         short bell-like chime (sine partials with decay)
- models, item definition, sounds.json, font json, post effect, lang files

Run:  python tools/gen_content_assets.py      (needs Pillow, numpy and soundfile with Vorbis support)
"""
import json
import math
import random
from pathlib import Path

import numpy as np
import soundfile as sf
from PIL import Image

NS = "kiln_content"
ROOT = Path(__file__).resolve().parent.parent / "example" / "server" / "src" / "main" / "resources" / "extensions" / NS / "assets" / NS


def write_json(path: str, data) -> None:
    file = ROOT / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def ruby_texture() -> None:
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    # diamond-shaped gem with facets: dark rim, mid body, bright highlights
    for y in range(16):
        for x in range(16):
            d = abs(x - 7.5) + abs(y - 8.5) * 1.15
            if d > 7.2:
                continue
            if d > 6.2:
                px[x, y] = (110, 8, 24, 255)
            elif x + y < 13:
                px[x, y] = (238, 56, 82, 255)
            elif x > y:
                px[x, y] = (196, 22, 50, 255)
            else:
                px[x, y] = (160, 14, 40, 255)
    for x, y in [(5, 5), (6, 4), (5, 6), (9, 7)]:
        px[x, y] = (255, 214, 222, 255)
    out = ROOT / "textures/item/ruby.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def rune_font() -> None:
    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    rng = random.Random(1937)
    img = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    px = img.load()
    for index in range(len(letters)):
        gx, gy = (index % 8) * 8, (index // 8) * 8
        # a vertical stave plus two random branches: rune-like, 5 px wide, 7 px high
        stave = rng.choice([1, 2, 3])
        for y in range(7):
            px[gx + stave, gy + y] = (255, 255, 255, 255)
        for _ in range(2):
            y0 = rng.randrange(0, 5)
            direction = rng.choice([-1, 1])
            for step in range(3):
                x = gx + stave + direction * (step + 1)
                y = gy + y0 + step
                if gx <= x < gx + 5 and y < gy + 7:
                    px[x, y] = (255, 255, 255, 255)
    out = ROOT / "textures/font/runes.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)
    rows = [letters[i:i + 8].ljust(8, "\u0000") for i in range(0, 32, 8)]
    write_json("font/runes.json", {
        "providers": [
            {"type": "space", "advances": {" ": 4}},
            {"type": "bitmap", "file": f"{NS}:font/runes.png", "ascent": 7, "height": 8, "chars": rows},
        ],
    })


def chime() -> None:
    rate = 44100
    t = np.arange(int(rate * 0.9)) / rate
    tone = np.zeros_like(t)
    for freq, amp, decay in [(1318.5, 0.55, 5.0), (1975.5, 0.3, 7.0), (2637.0, 0.2, 9.0), (3951.0, 0.08, 14.0)]:
        tone += amp * np.sin(2 * math.pi * freq * t) * np.exp(-decay * t)
    attack = np.minimum(1.0, t / 0.004)
    tone *= attack * 0.6
    out = ROOT / "sounds/ruby_chime.ogg"
    out.parent.mkdir(parents=True, exist_ok=True)
    sf.write(out, tone.astype(np.float32), rate, format="OGG", subtype="VORBIS")
    write_json("sounds.json", {"ruby_chime": {"subtitle": f"{NS}.subtitle.ruby_chime", "sounds": [f"{NS}:ruby_chime"]}})


def models() -> None:
    write_json("items/ruby.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/ruby"}})
    write_json("models/item/ruby.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/ruby"}})


def post_effect() -> None:
    # red-tinted vision: vanilla color_convolve into a swap target, then blit back (only vanilla shader programs)
    write_json("post_effect/ruby_vision.json", {
        "targets": {"swap": {}},
        "passes": [
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": "minecraft:post/color_convolve",
                "inputs": [{"sampler_name": "In", "target": "minecraft:main"}],
                "output": "swap",
                "uniforms": {"ColorConfig": [
                    {"name": "RedMatrix", "type": "vec3", "value": [0.9, 0.3, 0.2]},
                    {"name": "GreenMatrix", "type": "vec3", "value": [0.1, 0.35, 0.05]},
                    {"name": "BlueMatrix", "type": "vec3", "value": [0.1, 0.1, 0.35]},
                ]},
            },
            {
                "vertex_shader": "minecraft:core/screenquad",
                "fragment_shader": "minecraft:post/blit",
                "inputs": [{"sampler_name": "In", "target": "swap"}],
                "output": "minecraft:main",
                # blit declares the uniform block BlitConfig; every declared block needs values
                "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
            },
        ],
    })


def lang() -> None:
    write_json("lang/en_us.json", {
        f"{NS}.enchantment.lifesteal": "Lifesteal",
        f"{NS}.subtitle.ruby_chime": "Ruby chimes",
    })
    write_json("lang/de_de.json", {
        f"{NS}.enchantment.lifesteal": "Lebensraub",
        f"{NS}.subtitle.ruby_chime": "Rubin klingt",
    })


def cube(frm, to, texture="#wood"):
    faces = {f: {"texture": texture} for f in ("north", "south", "east", "west", "up", "down")}
    return {"from": frm, "to": to, "faces": faces}


def furniture_models() -> None:
    # block-sized item models (rendered by an item display at the furniture origin + 0.5); front = south (+z)
    wood = {"wood": "minecraft:block/oak_planks", "particle": "minecraft:block/oak_planks"}
    legs = [cube([x, 0, z], [x + 2, 8, z + 2]) for x, z in [(2, 2), (12, 2), (2, 12), (12, 12)]]
    chair = legs + [cube([1, 8, 1], [15, 10, 15]), cube([1, 10, 1], [15, 22, 3])]
    table_legs = [cube([x, 0, z], [x + 2, 14, z + 2]) for x, z in [(1, 1), (13, 1), (1, 13), (13, 13)]]
    table = table_legs + [cube([0, 14, 0], [16, 16, 16])]
    bench_legs = [cube([x, 0, z], [x + 2, 6, z + 2]) for x, z in [(-7, 3), (21, 3), (-7, 11), (21, 11)]]
    bench = bench_legs + [cube([-8, 6, 2], [24, 8, 14])]
    for name, elements in [("chair", chair), ("table", table), ("bench", bench)]:
        write_json(f"models/item/{name}.json", {"textures": wood, "elements": elements})
        write_json(f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})


if __name__ == "__main__":
    ruby_texture()
    rune_font()
    chime()
    models()
    post_effect()
    furniture_models()
    lang()
    print("wrote", ROOT)

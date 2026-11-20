#!/usr/bin/env python3
"""Generates 30 Lua test extensions for the local test server (docs/lua-extensions.md).

Usage (from the repository root, stdlib only):

    python -I tools/gen_test_extensions.py [--out run/server/plugins/Kiln/extensions]

Writes one folder per extension (extension.json, main.lua, icon.png, optional textures/*.png). Output is
deterministic (seeded noise), so re-running produces identical packages and the client cache keeps working.
Only the folders of the ids listed below are replaced; other packages in the output directory are left alone.
About five packages are large (2-3.9 MiB zipped, incompressible noise textures) to make the client's loading
screen visible; everything stays inside the package limits (zip <= 4 MiB, <= 256 files, <= 16 MiB unpacked,
icon <= 16 KiB / 64x64, path segments [a-z0-9_.-], only .lua/.png/.json).
"""

import argparse
import json
import math
import random
import shutil
import struct
import sys
import zlib
from pathlib import Path

MIB = 1024 * 1024
MAX_ZIP = 4 * MIB
ALL_PERMS = ["widgets", "keybinds", "hud", "channels", "toasts"]

# ---- PNG -------------------------------------------------------------------------------------------------


def png(width, height, rgba_rows, level=9):
    """RGBA PNG from a list of `height` byte strings of `width * 4` bytes each (filter 0)."""
    raw = b"".join(b"\x00" + row for row in rgba_rows)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, level)) + chunk(b"IEND", b"")


def noise_png(width, height, rng):
    """Incompressible RGBA noise (alpha opaque-ish), roughly width * height * 4 bytes."""
    return png(width, height, [rng.randbytes(width * 4) for _ in range(height)], level=0)


def hsv(h, s, v):
    i = int(h * 6) % 6
    f = h * 6 - int(h * 6)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    r, g, b = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i]
    return int(r * 255), int(g * 255), int(b * 255)


def inside(shape, x, y, n):
    """Shape mask in [-1, 1]^2 coordinates."""
    r = math.hypot(x, y)
    a = math.atan2(y, x)
    if shape == "circle":
        return r <= 0.85
    if shape == "ring":
        return 0.5 <= r <= 0.85
    if shape == "square":
        return max(abs(x), abs(y)) <= 0.7
    if shape == "diamond":
        return abs(x) + abs(y) <= 0.9
    if shape == "triangle":
        return y >= -0.7 and abs(x) <= (y + 0.7) / 1.6 * 0.9 and y <= 0.75
    if shape == "cross":
        return (abs(x) <= 0.25 and abs(y) <= 0.85) or (abs(y) <= 0.25 and abs(x) <= 0.85)
    if shape == "star":
        k = 0.45 + 0.4 * (0.5 + 0.5 * math.cos(5 * a + math.pi / 2))
        return r <= k
    if shape == "gear":
        k = 0.7 + 0.15 * (1 if math.cos(8 * a) > 0 else 0)
        return r <= k and r >= 0.25
    if shape == "hexagon":
        x, y = abs(x), abs(y)
        return y <= 0.75 and 0.866 * x + 0.5 * y <= 0.75
    if shape == "stripes":
        return max(abs(x), abs(y)) <= 0.8 and int((x + y + 2) * 3) % 2 == 0
    if shape == "heart":
        y = -y
        return (x * x + y * y - 0.5) ** 3 - x * x * y ** 3 <= 0
    raise ValueError(shape)


def icon_png(size, shape, hue):
    bg = hsv(hue, 0.35, 0.22)
    fg = hsv(hue, 0.75, 0.95)
    hi = hsv((hue + 0.08) % 1, 0.45, 1.0)
    rows = []
    for py in range(size):
        row = bytearray()
        for px in range(size):
            x = (px + 0.5) / size * 2 - 1
            y = (py + 0.5) / size * 2 - 1
            edge = max(abs(x), abs(y)) > 0.94
            if inside(shape, x, -y, size):
                c = hi if (x + y) < -0.6 else fg
                row += bytes(c) + b"\xff"
            elif edge:
                row += bytes(fg) + b"\xff"
            else:
                row += bytes(bg) + b"\xff"
        rows.append(bytes(row))
    return png(size, size, rows)


# ---- Extensions ------------------------------------------------------------------------------------------
# (id, name, version, permissions, icon shape, icon size, large texture spec [(name, w, h)], options)

W, K, H, C, T = "widgets", "keybinds", "hud", "channels", "toasts"

EXTENSIONS = [
    ("foerderband_anzeigen", "Förderband-Anzeigen", "1.2.0", [W, H, C], "stripes", 32, [], {}),
    ("quest_tracker", "Quest-Tracker", "0.9.3", [K, H, C, T], "star", 64, [], {}),
    ("minimap_overlay", "Minimap-Overlay", "2.0.0-beta.1", [K, H], "circle", 64,
     [("karte_a", 512, 512), ("karte_b", 512, 512), ("karte_c", 512, 288)], {"key": "key.keyboard.keypad.7"}),
    ("lager_monitor", "Lager-Monitor", "1.0.4", [W], "square", 32, [], {}),
    ("kistenradar", "Kistenradar", "0.3.1", [H], "ring", 32, [], {"subtle": True}),
    ("partikel_dekor", "Partikel-Dekor", "1.1.0", [], "diamond", 32, [], {}),
    ("bedwars_teamanzeige", "BedWars-Teamanzeige", "3.2.1", [H, C], "heart", 64, [], {}),
    ("skywars_kit_auswahl", "SkyWars-Kit-Auswahl", "1.4.0", [W, C], "triangle", 64,
     [("kit_krieger", 512, 512), ("kit_bogner", 512, 512)], {}),
    ("parkour_stoppuhr", "Parkour-Stoppuhr", "0.8.0", [K, H, T], "hexagon", 32, [], {}),
    ("energienetz_graph", "Energienetz-Graph", "1.0.0", [W], "gear", 64, [], {}),
    ("auktionshaus_ticker", "Auktionshaus-Ticker", "2.3.0", [C, T], "diamond", 64, [], {}),
    ("werkbank_rezepte", "Werkbank-Rezeptbuch", "1.5.2", [W, K], "square", 64,
     [("rezepte_1", 512, 512), ("rezepte_2", 512, 512), ("rezepte_3", 384, 384)], {}),
    ("schmelzofen_status", "Schmelzofen-Status", "0.2.0", [T], "cross", 32, [], {}),
    ("erzsucher", "Erzsucher-Hinweise", "1.0.1", [T], "star", 32, [], {}),
    ("tagesbelohnung", "Tagesbelohnung", "1.0.0", [T], "heart", 32, [], {}),
    ("spawnschutz", "Spawnschutz-Anzeige", "0.4.2", [H, C], "ring", 64, [], {}),
    ("murmelbahn", "Murmelbahn-Minispiel", "1.0.0-rc2", ALL_PERMS, "circle", 64,
     [("bahn_1", 512, 512), ("bahn_2", 512, 512), ("bahn_3", 512, 512), ("murmeln", 512, 400)], {}),
    ("fabrik_dashboard", "Fabrik-Dashboard", "4.0.0", ALL_PERMS, "gear", 32, [], {}),
    ("clan_chat_overlay", "Clan-Chat-Overlay", "1.3.3", ALL_PERMS, "hexagon", 64, [], {}),
    ("wirtschaftskurse", "Wirtschaftskurse", "0.6.0", [W, C], "triangle", 32, [], {}),
    ("ressourcenzaehler", "Ressourcenzähler", "1.0.2", [W, H], "cross", 64, [], {}),
    ("server_zeit", "Server-Zeitanzeige", "1.1.0", [H], "circle", 32, [], {"subtle": True}),
    ("koordinaten_hud", "Koordinaten-HUD", "2.1.0", [K, H], "cross", 32, [], {}),
    ("arena_countdown", "Arena-Countdown", "1.0.0", [H, C, T], "stripes", 64, [], {}),
    ("drohnen_steuerung", "Drohnen-Steuerung", "0.1.0", [K, C], "triangle", 32, [], {"key": "key.keyboard.keypad.8"}),
    ("pinnwand", "Pinnwand", "1.2.0", [W, C, T], "square", 32, [], {}),
    ("lichtorgel", "Lichtorgel", "0.9.9", [W], "star", 64, [], {}),
    ("textur_galerie", "Textur-Galerie", "1.0.0", [W], "diamond", 32,
     [("galerie_1", 512, 512), ("galerie_2", 512, 512), ("galerie_3", 512, 512), ("galerie_4", 256, 256),
      ("galerie_5", 256, 256)], {}),
    ("wegpunkte", "Wegpunkte", "1.7.0", [K, H, T], "ring", 64, [], {"key": "key.keyboard.keypad.5"}),
    ("abstimmung", "Abstimmungs-Widget", "1.0.3", [W, C], "heart", 32, [], {}),
]

ANCHORS = ["top_left", "top", "top_right", "left", "right", "bottom_left", "bottom_right"]


def lua_str(s):
    return json.dumps(s, ensure_ascii=False)


def color(hue, s=0.7, v=0.9, a=0xFF):
    r, g, b = hsv(hue, s, v)
    return "0x%02X%02X%02X%02X" % (a, r, g, b)


def main_lua(index, ext_id, name, perms, textures, opts):
    hue = (index * 0.137) % 1
    accent = color(hue)
    out = [
        f"-- {name} ({ext_id}): generated test extension (tools/gen_test_extensions.py), Lua-API v1.",
        f"-- Permissions: {', '.join(perms) if perms else 'none'}. Harmless: nothing is shown or sent while loading.",
        "",
        "local state = { visible = false, count = 0, data = nil }",
        f"local ACCENT = {accent}",
        "local BG = 0xA0101418",
        "local TEXT = 0xFFE0E0E0",
        "",
    ]
    tex_ids = [f"textures/{t[0]}.png" for t in textures]

    if T in perms:
        out += [
            "local function notify(text)",
            f"  kiln.ui.toast({lua_str(name)}, text)",
            "end",
            "",
        ]
    else:
        out += ["local function notify(text) end", ""]

    if not perms:
        out += [
            "-- no permissions: only always-available helpers (lerpColor, time)",
            "local palette = {}",
            "for i = 0, 7 do",
            f"  palette[#palette + 1] = kiln.ui.lerpColor(0xFF203040, ACCENT, i / 7)",
            "end",
            "state.data = { palette = palette, started = kiln.time() }",
        ]

    if W in perms:
        wname = "anzeige" if not tex_ids else "bild"
        out += [
            f'kiln.ui.widget("{ext_id}:{wname}", {{',
            "  init = function(self) self.t = 0 end,",
            "  render = function(self, g, w)",
            "    g:rect(0, 0, w.width, w.height, BG)",
        ]
        if tex_ids:
            out += [
                f"    local textures = {{ {', '.join(lua_str(t) for t in tex_ids)} }}",
                "    local n = math.floor(tonumber(w.props.page) or 1)",
                "    if n < 1 or n > #textures then n = 1 end",
                "    g:texture(textures[n], 1, 1, math.max(1, w.width - 2), math.max(1, w.height - 2))",
            ]
        else:
            out += [
                "    local value = tonumber(w.props.value) or 0.5",
                "    if value ~= value or value < 0 then value = 0 elseif value > 1 then value = 1 end",
                "    g:rect(2, w.height - 6, math.floor((w.width - 4) * value), 4, ACCENT)",
                f"    g:text(w.props.label or {lua_str(name)}, 3, 3, {{ color = TEXT, shadow = true }})",
            ]
        out += [
            "    if w.hovered then",
            "      g:outline(0, 0, w.width, w.height, 0x80FFFFFF)",
            f"      kiln.ui.tooltip({lua_str(name)})",
            "    end",
            "  end,",
            "  click = function(self, w, button) return button == 0 end,",
            "})",
            "",
        ]

    if C in perms:
        chan = ext_id.split("_")[0][:32]
        out += [
            f'local channel = kiln.net.channel("{chan}")',
            "channel:on(function(msg)",
            '  if type(msg) ~= "table" then return end',
            "  state.data = msg",
            "  state.count = state.count + 1",
        ]
        if H in perms:
            out += ["  state.visible = msg.visible ~= false"]
        if T in perms:
            out += ['  if type(msg.toast) == "string" then notify(string.sub(msg.toast, 1, 120)) end']
        out += ["end)", ""]

    if K in perms:
        key = opts.get("key")
        default_line = f'  default = "{key}",' if key else "  -- no default key: the player binds it in the controls menu"
        out += [
            'kiln.keys.bind("umschalten", {',
            f"  name = {lua_str(name + ' umschalten')},",
            default_line,
            "  press = function()",
            "    state.visible = not state.visible",
        ]
        if T in perms:
            out += [f'    notify(state.visible and "eingeblendet" or "ausgeblendet")']
        out += ["  end,", "})", ""]

    if H in perms:
        anchor = ANCHORS[index % len(ANCHORS)]
        if opts.get("subtle"):
            out += [
                f'kiln.hud.layer("hinweis", {{ anchor = "{anchor}", width = 6, height = 6 }}, function(g, area)',
                "  -- subtle: a small, slowly pulsing dot",
                "  local pulse = 0.5 + 0.5 * math.sin(kiln.time() * 2)",
                "  g:rect(1, 1, 4, 4, kiln.ui.lerpColor(0x30FFFFFF, 0x60FFFFFF, pulse))",
                "end)",
                "",
            ]
        else:
            size = (96, 96) if tex_ids else (110, 24)
            out += [
                f'kiln.hud.layer("overlay", {{ anchor = "{anchor}", width = {size[0]}, height = {size[1]} }}, function(g, area)',
                "  if not state.visible then return end -- hidden by default",
                "  g:rect(0, 0, area.width, area.height, BG)",
            ]
            if tex_ids:
                out += [f'  g:texture("{tex_ids[0]}", 2, 2, area.width - 4, area.height - 4)']
            else:
                out += [
                    f"  g:text({lua_str(name)}, 4, 4, {{ color = ACCENT, shadow = true }})",
                    '  local me = kiln.player()',
                    '  local info = me and string.format("%.0f / %.0f / %.0f", me.x, me.y, me.z) or ("#" .. state.count)',
                    "  g:text(info, 4, 14, { color = TEXT })",
                ]
            out += ["end)", ""]

    out += [f'print("{ext_id} geladen")', ""]
    return "\n".join(out)


def build(out_dir: Path):
    out_dir.mkdir(parents=True, exist_ok=True)
    report = []
    for index, (ext_id, name, version, perms, shape, icon_size, textures, opts) in enumerate(EXTENSIONS):
        target = out_dir / ext_id
        if target.exists():
            shutil.rmtree(target)
        target.mkdir()
        hue = (index * 0.137) % 1
        icon = icon_png(icon_size, shape, hue)
        assert len(icon) <= 16 * 1024, (ext_id, len(icon))
        manifest = {"id": ext_id, "version": version, "name": name, "main": "main.lua", "icon": "icon.png",
                    "permissions": perms}
        files = {
            "extension.json": json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8") + b"\n",
            "main.lua": main_lua(index, ext_id, name, perms, textures, opts).encode("utf-8"),
            "icon.png": icon,
        }
        rng = random.Random(f"kiln-test-{ext_id}")
        for tex_name, w, h in textures:
            files[f"textures/{tex_name}.png"] = noise_png(w, h, rng)
        total = sum(len(b) for b in files.values())
        assert total < MAX_ZIP - 64 * 1024, (ext_id, total)  # deflate cannot shrink noise: zip ~= raw size
        for path, data in files.items():
            p = target / path
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(data)
        report.append((ext_id, name, version, total, perms))
    width = max(len(r[1]) for r in report)
    for ext_id, name, version, total, perms in report:
        size = f"{total / MIB:.2f} MiB" if total >= MIB else f"{total / 1024:.1f} KiB"
        print(f"{name:<{width}}  {version:<13} {size:>9}  {','.join(perms) or '-'}")
    print(f"{len(report)} extensions, {sum(r[3] for r in report) / MIB:.2f} MiB, written to {out_dir}")


def main(argv):
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default="run/server/plugins/Kiln/extensions", type=Path)
    args = parser.parse_args(argv)
    build(args.out)


if __name__ == "__main__":
    main(sys.argv[1:])

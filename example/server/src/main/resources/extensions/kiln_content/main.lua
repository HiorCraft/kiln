-- Demo of server-delivered resources in Lua: the bitmap font kiln_content:runes (assets/kiln_content/font/runes.json)
-- drawn in a small HUD label. Items, sounds, the enchantment and the post effect are used by the server plugin.
local hud = kiln.hud

hud.layer("runes", { anchor = "top_right", width = 90, height = 12 }, function(g, area)
  g:text("KILN RUNEN", area.width, 2, { align = "right", font = "kiln_content:runes", color = 0xFFE03050 })
end)

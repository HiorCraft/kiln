-- kiln example extension (Lua-API v1, see docs/lua-extensions.md)
-- Widget "kiln_example:gauge", hello keybind (J) with channel "hello", factory HUD overlay.

local ui, keys, net = kiln.ui, kiln.keys, kiln.net

-- ---- Gauge widget ---------------------------------------------------------------------------------------
-- Half circle for property "value" (0..1, string), "label" below. The arc fills from left to right, its color
-- goes from red to green; hovering brightens it, outlines the widget and shows a tooltip.

local LABEL_SPACE = 20                  -- room below the arc: percent line and label line
local TRACK       = 0xFF3C3C3C
local TRACK_HOVER = 0xFF555555
local LOW         = 0xFFDC3C32
local HIGH        = 0xFF46D250
local WHITE       = 0xFFFFFFFF
local LABEL       = 0xFFCCCCCC

local function clamp01(v)
  if v ~= v then return 0 end            -- NaN
  if v < 0 then return 0 elseif v > 1 then return 1 end
  return v
end

ui.widget("kiln_example:gauge", {
  init = function(self)
    self.shown = nil                       -- animated value, starts at the first server value
  end,

  render = function(self, g, w)
    local value = clamp01(tonumber(w.props.value) or 0)
    local label = w.props.label or ""
    -- ease towards the server value (the server updates once per second)
    if self.shown == nil then self.shown = value end
    self.shown = self.shown + (value - self.shown) * 0.2
    if math.abs(value - self.shown) < 0.002 then self.shown = value end

    local radius = math.min(math.floor(w.width / 2), w.height - LABEL_SPACE) - 1
    local cx = math.floor(w.width / 2)
    local cy = radius + 1                  -- baseline of the half circle
    if radius >= 4 then
      local thickness = math.max(3, math.floor(radius / 3))
      local fill = ui.lerpColor(LOW, HIGH, self.shown)
      if w.hovered then fill = ui.lerpColor(fill, WHITE, 0.35) end
      g:arc(cx, cy, radius, thickness, 0, 1, w.hovered and TRACK_HOVER or TRACK)
      g:arc(cx, cy, radius, thickness, 0, self.shown, fill)
      -- below the baseline: inside the arc the inner radius is too small for the text
      g:text(string.format("%d%%", math.floor(value * 100)), cx, cy + 1, { align = "center", color = WHITE, shadow = true })
    end

    if label ~= "" then
      g:text(label, cx, w.height - 9, { align = "center", color = w.hovered and WHITE or LABEL, shadow = true })
    end

    if w.hovered then
      g:outline(0, 0, w.width, w.height, 0x80FFFFFF)
      ui.tooltip(string.format("%s: %.0f %%", label ~= "" and label or "Wert", value * 100))
    end
  end,

  -- report clicks to the server (ScreenClick)
  click = function(self, w, button)
    return true
  end,
})

-- ---- Hello keybind + typed channel ----------------------------------------------------------------------
-- Server side: ExamplePlugin, ext.channel<HelloMessage>("hello") { player, msg -> send(player, ...) }

local hello = net.channel("hello")

keys.bind("hello", {
  name = "Hallo an Server",
  default = "J",
  press = function()
    local me = kiln.player()
    hello:send({ text = "Hallo vom Client, ich bin " .. (me and me.name or "?") .. "!" })
  end,
})

hello:on(function(msg)
  if type(msg) == "table" and type(msg.text) == "string" then
    ui.toast("Server", string.sub(msg.text, 1, 256))
  end
end)

-- ---- Factory HUD overlay ----------------------------------------------------------------------------------
-- The server sends { running = bool, energy = 0..1, output = n } on channel "machine" whenever it changes.

local machine = { running = false, energy = 0, output = 0 }

net.channel("machine"):on(function(msg)
  if type(msg) ~= "table" then return end
  machine.running = msg.running == true
  machine.energy = clamp01(tonumber(msg.energy) or 0)
  machine.output = math.floor(tonumber(msg.output) or 0)
end)

kiln.hud.layer("machine", { anchor = "top_right", width = 90, height = 22 }, function(g, area)
  if not machine.running then return end
  local pulse = 0.5 + 0.5 * math.sin(kiln.time() * 4)
  g:rect(0, 0, area.width, area.height, 0xA0101410)
  g:item("minecraft:blast_furnace", 3, 3)
  g:rect(22, 5, 64, 6, 0xFF2A2F2C)
  g:rect(22, 5, math.floor(64 * machine.energy), 6, ui.lerpColor(0xFFB89A50, 0xFFE3C26B, pulse))
  g:text("Output: " .. machine.output, 22, 13, { color = 0xFFCCCCCC, shadow = true })
end)

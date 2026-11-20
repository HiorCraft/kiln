-- ui_kit: immediate-mode UI components for kiln Lua screens (API extension, needs no permissions).
-- Full documentation: docs/ui_kit.md. Short version:
--
--   local kit = kiln.import("ui_kit")
--   init   = function(self, s) self.ui = kit.new() end
--   render = function(self, g, s)
--     local ui = self.ui
--     ui:frame(g, s)                                   -- start of every frame
--     if ui:button("ok", 10, 10, 80, 20, "OK", { style = "primary" }) then ... end
--     ui:finish()                                      -- overlays (dropdowns), toasts, tooltip, cursor
--   end,
--   click   = function(self, s, mx, my, b, mods) return self.ui:click(mx, my, b, mods) end,
--   release = function(self, s, mx, my, b) self.ui:release(mx, my, b) end,
--   scroll  = function(self, s, mx, my, dy) return self.ui:scroll(mx, my, dy) end,
--   key     = function(self, s, key, mods) return self.ui:key(key, mods) end,
--
-- Why immediate mode: Lua screens are already redrawn every frame from the script's own state, the runtime has no
-- retained widget tree, and declaring the UI per frame keeps app state in one place (no sync between a widget tree
-- and the data). Input arrives between frames, so ui_kit hit-tests events against the rectangles the widgets
-- registered in the previous frame (topmost = registered last) and reports results ("clicked", new values) in the
-- next frame. Widget state that must survive frames (scroll offsets, open dropdown, animations) lives in the
-- context keyed by widget id.

local K = { version = "1.1.0" }

local floor, max, min, abs = math.floor, math.max, math.min, math.abs
local clock = kiln.clock

-- region kinds
local CLICK, DRAG, BLOCK, SCROLL = 1, 2, 3, 4

local THEME = {
  text = 0xFFE8E8E8, muted = 0xFF9AA0A6, dim = 0xFF6B7078, accent = 0xFFFFC94D,
  danger = 0xFFE86060, success = 0xFF62D46F, info = 0xFF66AAFF,
  panel = 0xE81C1F24, panelBorder = 0xFF3A3F47, card = 0xF0262A30, cardHover = 0xF0323740, cardSelected = 0xF03C4452,
  cardBorder = 0xFF40464F, cardBorderHover = 0xFF6A717C, cardBorderSelected = 0xFFFFC94D,
  field = 0xFF0E0F12, fieldBorder = 0xFF5A5F68, fieldBorderHover = 0xFFA0A6B0,
  overlay = 0xFF181A1F, overlayHover = 0xFF2E5A8A, backdrop = 0xA0000000,
  modal = 0xFF1A1C21, modalBorder = 0xFF5C6370,
  primaryTint = 0x5530A050, dangerTint = 0x60C03030,
}
K.theme = THEME

-- ---- pure helpers (also exported) -------------------------------------------------------------------------------

local function clamp(v, lo, hi) if v < lo then return lo elseif v > hi then return hi end return v end
K.clamp = clamp

--- Snaps v to the grid min + k * step and clamps it to [min, max].
function K.snap(v, lo, hi, step)
  step = step or 1
  if step <= 0 then step = 1 end
  local k = floor((v - lo) / step + 0.5)
  return clamp(lo + k * step, lo, hi)
end

--- Slider value for a mouse x over a slider at x with width w (handle 8 px wide).
function K.sliderValue(mx, x, w, lo, hi, step)
  if hi <= lo then return lo end
  local t = clamp((mx - x - 4) / max(1, w - 8), 0, 1)
  return K.snap(lo + t * (hi - lo), lo, hi, step)
end

--- Clamps a scroll offset for content of height contentH in a view of height viewH.
function K.clampScroll(y, contentH, viewH)
  return clamp(y, 0, max(0, contentH - viewH))
end

local function trimZero(s) return (s:gsub("%.0([kMB])$", "%1")) end

--- Compact count like slot numbers: 999, 1.2k, 12k, 1.5M, 2.1B.
function K.formatCount(n)
  n = floor(n)
  local a = abs(n)
  if a < 1000 then return tostring(n) end
  local sign = n < 0 and "-" or ""
  if a < 10000 then return sign .. trimZero(string.format("%.1fk", floor(a / 100) / 10)) end
  if a < 1000000 then return sign .. floor(a / 1000) .. "k" end
  if a < 10000000 then return sign .. trimZero(string.format("%.1fM", floor(a / 100000) / 10)) end
  if a < 1000000000 then return sign .. floor(a / 1000000) .. "M" end
  return sign .. trimZero(string.format("%.1fB", floor(a / 100000000) / 10))
end

--- 1234567 -> "1.234.567" (German grouping).
function K.formatNumber(n)
  local s = tostring(floor(abs(n)))
  local out = s:reverse():gsub("(%d%d%d)", "%1."):reverse()
  if out:sub(1, 1) == "." then out = out:sub(2) end
  return (n < 0 and "-" or "") .. out
end

local UPPER = { ["\195\132"] = "\195\164", ["\195\150"] = "\195\182", ["\195\156"] = "\195\188" }
--- string.lower plus Ä/Ö/Ü (UTF-8).
function K.lower(s)
  return (s:lower():gsub("\195[\132\150\156]", UPPER))
end

--- Byte positions where UTF-8 characters start.
local function charStarts(s)
  local starts, n = {}, 0
  for i = 1, #s do
    local b = s:byte(i)
    if b < 128 or b >= 192 then n = n + 1; starts[n] = i end
  end
  return starts, n
end

--- First n UTF-8 characters of s.
function K.utf8sub(s, n)
  local starts, count = charStarts(s)
  if n >= count then return s end
  if n <= 0 then return "" end
  return s:sub(1, starts[n + 1] - 1)
end

-- ---- layout helpers --------------------------------------------------------------------------------------------

local Flow = {}
Flow.__index = Flow
Flow.__metatable = "ui_kit.layout"

--- Row: r:next(w [, h]) -> x, y, w, h of the next cell, left to right with gap.
function K.row(x, y, opts)
  opts = opts or {}
  local p = opts.padding or 0
  return setmetatable({ x = x + p, y = y + p, gap = opts.gap or 4, h = opts.h or 20, horizontal = true }, Flow)
end

--- Column: c:next(h [, w]) -> x, y, w, h of the next cell, top to bottom with gap.
function K.column(x, y, opts)
  opts = opts or {}
  local p = opts.padding or 0
  return setmetatable({ x = x + p, y = y + p, gap = opts.gap or 4, w = opts.w or 100, horizontal = false }, Flow)
end

function Flow:next(size, other)
  if self.horizontal then
    local x = self.x
    self.x = x + size + self.gap
    return x, self.y, size, other or self.h
  end
  local y = self.y
  self.y = y + size + self.gap
  return self.x, y, other or self.w, size
end

local Grid = {}
Grid.__index = Grid
Grid.__metatable = "ui_kit.grid"

--- Grid filling width w: as many columns of at least opts.minWidth as fit (opts.maxColumns), cells opts.h high.
function K.grid(x, y, w, opts)
  opts = opts or {}
  local gap, p = opts.gap or 4, opts.padding or 0
  local inner = w - 2 * p
  local cols = max(1, floor((inner + gap) / ((opts.minWidth or 100) + gap)))
  if opts.maxColumns then cols = min(cols, opts.maxColumns) end
  local cellW = max(1, floor((inner - gap * (cols - 1)) / cols))
  return setmetatable({ x = x + p, y = y + p, cols = cols, cellW = cellW, cellH = opts.h or 40, gap = gap, padding = p }, Grid)
end

--- Rectangle of cell i (1-based): x, y, w, h.
function Grid:cell(i)
  local c = (i - 1) % self.cols
  local r = floor((i - 1) / self.cols)
  return self.x + c * (self.cellW + self.gap), self.y + r * (self.cellH + self.gap), self.cellW, self.cellH
end

--- Total height of n cells (including padding).
function Grid:height(n)
  if n <= 0 then return 2 * self.padding end
  local rows = floor((n - 1) / self.cols) + 1
  return rows * self.cellH + (rows - 1) * self.gap + 2 * self.padding
end

-- ---- context ---------------------------------------------------------------------------------------------------

local Ctx = {}
Ctx.__index = Ctx
Ctx.__metatable = "ui_kit.context"

local EMPTY = kiln.readonly({}) -- returned to callers
local NOOPTS = {}                      -- internal default for opts

--- New UI context (one per open screen, keep it in the screen's self). theme: optional color overrides.
function K.new(theme)
  local t = {}
  for k, v in pairs(THEME) do t[k] = v end
  if theme then for k, v in pairs(theme) do t[k] = v end end
  local self = setmetatable({
    theme = t,
    -- regions of the frame being built (after finish: of the last frame, used by input events)
    n = 0, rx = {}, ry = {}, rw = {}, rh = {}, rid = {}, rdata = {}, rkind = {},
    -- regions of the previous frame (during a frame)
    ln = 0, lx = {}, ly = {}, lw = {}, lh = {}, lid = {}, ldata = {}, lkind = {},
    blockers = {}, nblockers = 0,
    clips = {}, nclips = 0,
    state = {},
    overlays = {}, noverlays = 0,
    toasts = {},
    ellipsis = {}, nellipsis = 0,
    mx = -1, my = -1, frameNo = 0, modalDepth = 0,
  }, Ctx)
  return self
end

--- Per-id state table of a widget (created on first use).
function Ctx:stateOf(id)
  local st = self.state[id]
  if not st then st = {}; self.state[id] = st end
  return st
end

--- Starts a frame: call first in render.
function Ctx:frame(g, s)
  self.g, self.s = g, s
  -- the regions built last frame become the "last" set; reuse the older arrays for this frame
  self.lx, self.rx = self.rx, self.lx
  self.ly, self.ry = self.ry, self.ly
  self.lw, self.rw = self.rw, self.lw
  self.lh, self.rh = self.rh, self.lh
  self.lid, self.rid = self.rid, self.lid
  self.ldata, self.rdata = self.rdata, self.ldata
  self.lkind, self.rkind = self.rkind, self.lkind
  self.ln, self.n = self.n, 0
  local mx, my = s.mouseX, s.mouseY
  self.mx, self.my = mx, my
  -- blockers of the last frame (modal backdrops, overlays) for hover / text-field blocking
  local nb, lkind, blockers = 0, self.lkind, self.blockers
  for i = 1, self.ln do
    if lkind[i] == BLOCK then nb = nb + 1; blockers[nb] = i end
  end
  self.nblockers = nb
  -- topmost region under the mouse
  self.hot, self.hotData = nil, nil
  local lx, ly, lw, lh = self.lx, self.ly, self.lw, self.lh
  for i = self.ln, 1, -1 do
    local k = lkind[i]
    if k ~= SCROLL and mx >= lx[i] and my >= ly[i] and mx < lx[i] + lw[i] and my < ly[i] + lh[i] then
      self.hot, self.hotData = self.lid[i], self.ldata[i]
      break
    end
  end
  if self.active and not s:isMouseDown(0) then self.active, self.activeData = nil, nil end
  self.nclips, self.noverlays, self.modalDepth = 0, 0, 0
  self.tip, self.itemTip, self.cursorName = nil, nil, nil
  self.frameNo = self.frameNo + 1
end

--- Ends a frame: dropdown overlays, toasts, tooltip, cursor. Call last in render.
function Ctx:finish()
  local g, s = self.g, self.s
  for i = 1, self.noverlays do self:_drawDropdown(self.overlays[i]) end
  self:_drawToasts()
  if self.itemTip then
    g:itemTooltip(self.itemTip, self.mx, self.my, self.itemTipLines)
  elseif self.tip then
    g:tooltip(self.tip)
  end
  if self.cursorName then s:cursor(self.cursorName) end
  -- clicks no widget claimed this frame are dropped
  self.clicked, self.clickedData, self.dismissed, self.picked = nil, nil, nil, nil
end

-- ---- regions / hit testing -------------------------------------------------------------------------------------

--- Registers an input region (clipped to the current clip). Returns false if fully clipped away.
function Ctx:_region(id, data, x, y, w, h, kind)
  local nc = self.nclips
  if nc > 0 then
    local c = self.clips[nc]
    local x0, y0 = max(x, c[1]), max(y, c[2])
    local x1, y1 = min(x + w, c[3]), min(y + h, c[4])
    if x1 <= x0 or y1 <= y0 then return false end
    x, y, w, h = x0, y0, x1 - x0, y1 - y0
  end
  local n = self.n + 1
  self.n = n
  self.rx[n], self.ry[n], self.rw[n], self.rh[n] = x, y, w, h
  self.rid[n], self.rdata[n], self.rkind[n] = id, data, kind
  return true
end

--- Registers a widget region and returns whether it is the topmost one under the mouse (hovered).
function Ctx:_widget(id, data, x, y, w, h, kind)
  if not self:_region(id, data, x, y, w, h, kind or CLICK) then return false end
  return self.hot == id and self.hotData == data
end

--- Consumes a click on (id, data) reported by the last click event.
function Ctx:_clicked(id, data)
  if self.clicked == id and self.clickedData == data then
    self.clicked, self.clickedData = nil, nil
    return true
  end
  return false
end

--- True if the rectangle is covered by something drawn later in the last frame (modal, dropdown list).
function Ctx:blocked(x, y, w, h)
  local n, b = self.n, self.blockers
  local lx, ly, lw, lh = self.lx, self.ly, self.lw, self.lh
  for j = self.nblockers, 1, -1 do
    local i = b[j]
    if i <= n then return false end
    if x < lx[i] + lw[i] and y < ly[i] + lh[i] and x + w > lx[i] and y + h > ly[i] then return true end
  end
  return false
end

--- True if (id, data) is the topmost widget under the mouse (as of the last frame).
function Ctx:isHot(id, data) return self.hot == id and self.hotData == data end

--- Mouse inside the rectangle and inside the current clip (ignores what is on top).
function Ctx:mouseIn(x, y, w, h)
  local mx, my = self.mx, self.my
  if mx < x or my < y or mx >= x + w or my >= y + h then return false end
  local nc = self.nclips
  if nc > 0 then
    local c = self.clips[nc]
    if mx < c[1] or my < c[2] or mx >= c[3] or my >= c[4] then return false end
  end
  return true
end

--- Mouse over the rectangle and nothing drawn above it (for hover effects of non-interactive things).
function Ctx:hovered(x, y, w, h)
  return self:mouseIn(x, y, w, h) and not self:_blockedPoint(self.mx, self.my)
end

function Ctx:_blockedPoint(px, py)
  local n, b = self.n, self.blockers
  local lx, ly, lw, lh = self.lx, self.ly, self.lw, self.lh
  for j = self.nblockers, 1, -1 do
    local i = b[j]
    if i <= n then return false end
    if px >= lx[i] and py >= ly[i] and px < lx[i] + lw[i] and py < ly[i] + lh[i] then return true end
  end
  return false
end

--- Runs fn() clipped to the rectangle (drawing, input regions and native text fields).
function Ctx:clip(x, y, w, h, fn)
  local nc = self.nclips
  local x0, y0, x1, y1 = x, y, x + max(0, w), y + max(0, h)
  if nc > 0 then
    local c = self.clips[nc]
    x0, y0 = max(x0, c[1]), max(y0, c[2])
    x1, y1 = max(x0, min(x1, c[3])), max(y0, min(y1, c[4]))
  end
  local c = self.clips[nc + 1]
  if not c then c = {}; self.clips[nc + 1] = c end
  c[1], c[2], c[3], c[4] = x0, y0, x1, y1
  self.nclips = nc + 1
  self.g:clip(x, y, w, h, fn)
  self.nclips = nc
end

--- Sets the mouse cursor for this frame (default | hand | text | not_allowed | ...).
function Ctx:cursor(name) self.cursorName = name end

--- Tooltip (text or list of lines) at the mouse after this frame.
function Ctx:tooltip(textOrLines) self.tip = textOrLines end

--- Vanilla item tooltip of an item handle (plus extra lines) after this frame.
function Ctx:itemTooltip(handle, lines) self.itemTip, self.itemTipLines = handle, lines end

-- ---- input events (call from the screen callbacks) ------------------------------------------------------------

local function inside(self, i, x, y)
  return x >= self.rx[i] and y >= self.ry[i] and x < self.rx[i] + self.rw[i] and y < self.ry[i] + self.rh[i]
end

--- Mouse press: hit test against the last frame. Returns true if ui_kit used it.
function Ctx:click(mx, my, button, mods)
  for i = self.n, 1, -1 do
    local k = self.rkind[i]
    if k ~= SCROLL and inside(self, i, mx, my) then
      local id, data = self.rid[i], self.rdata[i]
      if k == BLOCK then
        if data == "dismiss" then
          if id == self.openDropdown then self.openDropdown = nil else self.dismissed = id end
        end
        return true
      end
      if button ~= 0 then return true end
      if self.openDropdown and id == self.openDropdown and type(data) == "number" then
        self.picked, self.pickedIndex = id, data
        self.openDropdown = nil
        return true
      end
      self.clicked, self.clickedData = id, data
      self.clickMods = mods or EMPTY
      if k == DRAG then
        self.active, self.activeData = id, data
        self.grabX, self.grabY = mx, my
      end
      return true
    end
  end
  if self.openDropdown then self.openDropdown = nil return true end
  return false
end

--- Mouse release.
function Ctx:release(mx, my, button)
  if button == 0 then self.active, self.activeData = nil, nil end
end

--- Mouse wheel: scrolls the topmost scroll panel / dropdown list under the mouse. Returns true if used.
function Ctx:scroll(mx, my, dy)
  for i = self.n, 1, -1 do
    local k = self.rkind[i]
    if (k == SCROLL or k == BLOCK) and inside(self, i, mx, my) then
      if k == BLOCK then return true end
      local st = self:stateOf(self.rid[i])
      local step = st.step or 24
      st.y = clamp((st.y or 0) - dy * step, 0, st.max or 0)
      return true
    end
  end
  return false
end

--- Keyboard: an open dropdown takes up/down/enter/escape. Returns true if used.
function Ctx:key(key, mods)
  local id = self.openDropdown
  if not id then return false end
  local dd = self:stateOf(id)
  local count = dd.count or 0
  if key == "escape" or key == "tab" then
    self.openDropdown = nil
  elseif key == "up" then
    dd.hl = clamp((dd.hl or 1) - 1, 1, max(1, count))
    dd.ensureVisible = true
  elseif key == "down" then
    dd.hl = clamp((dd.hl or 0) + 1, 1, max(1, count))
    dd.ensureVisible = true
  elseif key == "home" then
    dd.hl, dd.ensureVisible = 1, true
  elseif key == "end" then
    dd.hl, dd.ensureVisible = max(1, count), true
  elseif key == "enter" or key == "keypad.enter" or key == "space" then
    if count > 0 then self.picked, self.pickedIndex = id, clamp(dd.hl or 1, 1, count) end
    self.openDropdown = nil
  else
    return true
  end
  return true
end

--- True while a dropdown list is open (e.g. to let Escape close it first).
function Ctx:dropdownOpen() return self.openDropdown ~= nil end

--- Closes an open dropdown.
function Ctx:closeDropdown() self.openDropdown = nil end

-- ---- text ------------------------------------------------------------------------------------------------------

--- Cuts text with "…" to fit maxWidth (uses g:textWidth; results cached).
function Ctx:ellipsize(text, maxWidth)
  text = tostring(text)
  if maxWidth <= 0 then return "" end
  local key = text .. "\1" .. maxWidth
  local cached = self.ellipsis[key]
  if cached then return cached end
  local g = self.g
  local result = text
  if g:textWidth(text) > maxWidth then
    local starts, count = charStarts(text)
    local lo, hi = 0, count - 1
    while lo < hi do
      local mid = floor((lo + hi + 1) / 2)
      if g:textWidth(text:sub(1, starts[mid + 1] - 1) .. "…") <= maxWidth then lo = mid else hi = mid - 1 end
    end
    result = lo > 0 and (text:sub(1, starts[lo + 1] - 1):gsub("%s+$", "") .. "…") or ""
  end
  if self.nellipsis >= 1024 then self.ellipsis, self.nellipsis = {}, 0 end
  self.ellipsis[key] = result
  self.nellipsis = self.nellipsis + 1
  return result
end

--- Text; opts: color, align (left|center|right), shadow, scale, maxWidth (ellipsized). Returns the width.
function Ctx:label(text, x, y, opts)
  opts = opts or NOOPTS
  if opts.maxWidth then text = self:ellipsize(text, opts.maxWidth) end
  return self.g:text(text, x, y, { color = opts.color or self.theme.text, align = opts.align, shadow = opts.shadow, scale = opts.scale })
end

--- Heading in the accent color (opts like label; opts.scale e.g. 2 for a big title).
function Ctx:heading(text, x, y, opts)
  opts = opts or NOOPTS
  if opts.maxWidth then text = self:ellipsize(text, opts.maxWidth / (opts.scale or 1)) end
  return self.g:text(text, x, y, { color = opts.color or self.theme.accent, align = opts.align, scale = opts.scale })
end

--- Word-wrapped text; returns the height used (lines * 10).
function Ctx:paragraph(text, x, y, w, opts)
  opts = opts or NOOPTS
  local lines = self.g:wrap(text, w)
  local color = opts.color or self.theme.muted
  for i = 1, #lines do
    self.g:text(lines[i], x, y + (i - 1) * 10, { color = color, align = opts.align })
  end
  return #lines * 10
end

-- ---- basic widgets ---------------------------------------------------------------------------------------------

--- Plain panel. opts: bg, border (false = none).
function Ctx:panel(x, y, w, h, opts)
  opts = opts or NOOPTS
  local t = self.theme
  self.g:rect(x, y, w, h, opts.bg or t.panel)
  if opts.border ~= false then self.g:outline(x, y, w, h, opts.border or t.panelBorder) end
end

--- Card with hover / selected look. opts: selected, clickable (default true), disabled, tooltip, data.
--- Returns clicked, hovered.
function Ctx:card(id, x, y, w, h, opts)
  opts = opts or NOOPTS
  local t, g = self.theme, self.g
  local hot
  if opts.clickable == false then
    hot = self:hovered(x, y, w, h)
  else
    hot = self:_widget(id, opts.data, x, y, w, h, CLICK)
  end
  local selected = opts.selected
  g:rect(x, y, w, h, selected and t.cardSelected or (hot and t.cardHover or t.card))
  g:outline(x, y, w, h, selected and t.cardBorderSelected or (hot and t.cardBorderHover or t.cardBorder))
  local clicked = opts.clickable ~= false and self:_clicked(id, opts.data)
  if hot then
    if opts.tooltip then self.tip = opts.tooltip end
    if opts.clickable ~= false then self.cursorName = opts.disabled and "not_allowed" or "hand" end
  end
  if clicked and opts.disabled then clicked = false end
  if clicked and opts.sound ~= false then self.s:sound("click") end
  return clicked, hot
end

--- Vanilla button. opts: style ("normal" | "primary" | "danger"), disabled, tooltip, data, sound (default true).
--- Returns true in the frame after it was clicked.
function Ctx:button(id, x, y, w, h, label, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local disabled = opts.disabled
  local hot = self:_widget(id, opts.data, x, y, w, h, CLICK)
  if disabled then
    g:sprite("widget/button_disabled", x, y, w, h)
  else
    g:sprite(hot and "widget/button_highlighted" or "widget/button", x, y, w, h)
    if opts.style == "primary" then g:rect(x + 1, y + 1, w - 2, h - 2, t.primaryTint)
    elseif opts.style == "danger" then g:rect(x + 1, y + 1, w - 2, h - 2, t.dangerTint) end
  end
  local color = disabled and 0xFFA0A0A0 or (hot and 0xFFFFFFA0 or 0xFFFFFFFF)
  g:text(self:ellipsize(label, w - 8), x + floor(w / 2), y + floor((h - 8) / 2), { align = "center", color = color })
  if hot then
    self.cursorName = disabled and "not_allowed" or "hand"
    local tip = disabled and (opts.disabledTooltip or opts.tooltip) or opts.tooltip
    if tip then self.tip = tip end
  end
  if self:_clicked(id, opts.data) and not disabled then
    if opts.sound ~= false then self.s:sound("click") end
    return true
  end
  return false
end

--- Tabs in a row. tabs = { { label =, tooltip = }, ... } or { "A", "B" }; selected = index.
--- opts: h (20), padding (16 px around labels), minWidth. Returns selected index, changed, total width.
function Ctx:tabs(id, x, y, tabs, selected, opts)
  opts = opts or NOOPTS
  local g = self.g
  local h = opts.h or 20
  local cx, changed = x, false
  for i = 1, #tabs do
    local tab = tabs[i]
    local label = type(tab) == "table" and tab.label or tab
    local w = max(opts.minWidth or 0, g:textWidth(label) + (opts.padding or 16))
    local hot = self:_widget(id, i, cx, y, w, h, CLICK)
    local sel = i == selected
    local sprite = sel and (hot and "widget/tab_selected_highlighted" or "widget/tab_selected") or (hot and "widget/tab_highlighted" or "widget/tab")
    g:sprite(sprite, cx, y, w, h)
    g:text(label, cx + floor(w / 2), y + floor((h - 8) / 2) + (sel and 0 or 1), { align = "center", color = sel and 0xFFFFFFFF or 0xFFB8B8B8 })
    if hot then
      self.cursorName = "hand"
      if type(tab) == "table" and tab.tooltip then self.tip = tab.tooltip end
    end
    if self:_clicked(id, i) and i ~= selected then
      selected, changed = i, true
      self.s:sound("page")
    end
    cx = cx + w + (opts.gap or 2)
  end
  return selected, changed, cx - x
end

--- Checkbox with label. Returns checked, changed. opts: size (17), disabled, tooltip, color.
function Ctx:checkbox(id, x, y, label, checked, opts)
  opts = opts or NOOPTS
  local g = self.g
  local size = opts.size or 17
  local w = size + (label and (4 + g:textWidth(label)) or 0)
  local hot = self:_widget(id, opts.data, x, y, w, size, CLICK)
  local sprite = checked and (hot and "widget/checkbox_selected_highlighted" or "widget/checkbox_selected")
    or (hot and "widget/checkbox_highlighted" or "widget/checkbox")
  g:sprite(sprite, x, y, size, size)
  if label then
    g:text(label, x + size + 4, y + floor((size - 8) / 2), { color = opts.disabled and self.theme.dim or (opts.color or self.theme.text) })
  end
  if hot then
    self.cursorName = opts.disabled and "not_allowed" or "hand"
    if opts.tooltip then self.tip = opts.tooltip end
  end
  if self:_clicked(id, opts.data) and not opts.disabled then
    self.s:sound("click")
    return not checked, true
  end
  return checked, false
end

--- Switch (animated). Returns on, changed. opts: label (right of the switch), disabled, tooltip.
function Ctx:toggle(id, x, y, on, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local w, h = 26, 14
  local total = w + (opts.label and (6 + g:textWidth(opts.label)) or 0)
  local hot = self:_widget(id, opts.data, x, y, total, h, CLICK)
  local st = self:stateOf(id)
  local target = on and 1 or 0
  local p = st.p or target
  local step = (self.s.delta or 0) * 8
  if p < target then p = min(target, p + step) elseif p > target then p = max(target, p - step) end
  st.p = p
  local track = opts.disabled and 0xFF3A3D42 or kiln.ui.lerpColor(0xFF4A4F57, 0xFF2F8F45, p)
  g:rect(x, y, w, h, track)
  g:outline(x, y, w, h, hot and t.fieldBorderHover or 0xFF1A1C20)
  local kx = x + 2 + floor(p * (w - 14) + 0.5)
  g:rect(kx, y + 2, 10, h - 4, opts.disabled and 0xFF808080 or 0xFFF0F0F0)
  if opts.label then
    g:text(opts.label, x + w + 6, y + 3, { color = opts.disabled and t.dim or t.text })
  end
  if hot then
    self.cursorName = opts.disabled and "not_allowed" or "hand"
    if opts.tooltip then self.tip = opts.tooltip end
  end
  if self:_clicked(id, opts.data) and not opts.disabled then
    self.s:sound("click")
    return not on, true
  end
  return on, false
end

--- Vanilla slider. Returns value, changed, committed. opts: step (1), h (20), text (label shown on the slider,
--- default the value), disabled, tooltip. committed = true in the frame the player let go (end of a drag or a plain
--- click): send values to a server then instead of on every change.
function Ctx:slider(id, x, y, w, value, lo, hi, opts)
  opts = opts or NOOPTS
  local g = self.g
  local h = opts.h or 20
  local step = opts.step or 1
  local hot = self:_widget(id, nil, x, y, w, h, DRAG)
  local changed = false
  local clicked = self:_clicked(id, nil)
  local dragging = self.active == id and self.activeData == nil
  if not opts.disabled and hi > lo and (clicked or dragging) then
    local v = K.sliderValue(self.mx, x, w, lo, hi, step)
    if v ~= value then value, changed = v, true end
  end
  local committed = false
  if not opts.disabled then
    local st = self:stateOf(id)
    if dragging then
      st.dragging = true
    elseif st.dragging or clicked then
      st.dragging, committed = nil, true
    end
  end
  g:sprite(hot and "widget/slider_highlighted" or "widget/slider", x, y, w, h)
  local t = hi > lo and (clamp(value, lo, hi) - lo) / (hi - lo) or 0
  local hx = x + floor(t * (w - 8) + 0.5)
  g:sprite((hot or self.active == id) and "widget/slider_handle_highlighted" or "widget/slider_handle", hx, y, 8, h)
  g:text(opts.text or tostring(value), x + floor(w / 2), y + floor((h - 8) / 2), { align = "center", color = opts.disabled and 0xFFA0A0A0 or 0xFFFFFFFF })
  if hot then
    self.cursorName = opts.disabled and "not_allowed" or "resize_ew"
    if opts.tooltip then self.tip = opts.tooltip end
  end
  return value, changed, committed
end

--- True while the widget id is being dragged (slider, scrollbar thumb).
function Ctx:isActive(id) return self.active == id end

--- Item in a slot (18x18 with the vanilla slot sprite). handle: item handle or item id; count shown as 1.2k etc.
--- opts: selected, tooltip (extra lines; false = none), clickable (default true), data, size (18), dim.
--- Returns clicked, hovered, mods of the click.
function Ctx:itemSlot(id, x, y, handle, count, opts)
  opts = opts or NOOPTS
  local g = self.g
  local size = opts.size or 18
  local hot
  if opts.clickable == false then hot = self:hovered(x, y, size, size) else hot = self:_widget(id, opts.data, x, y, size, size, CLICK) end
  if opts.background ~= false then g:sprite("container/slot", x, y, size, size) end
  if handle then
    local s = size - 2
    g:item(handle, x + 1, y + 1, (s == 16) and { count = count or 1 } or { size = s, count = count or 1 })
  end
  if opts.dim then g:rect(x + 1, y + 1, size - 2, size - 2, 0xA0101010) end
  if hot then
    g:rect(x + 1, y + 1, size - 2, size - 2, 0x80FFFFFF)
    if opts.clickable ~= false then self.cursorName = "hand" end
    if handle and opts.tooltip ~= false then
      if type(handle) == "userdata" then self.itemTip, self.itemTipLines = handle, opts.tooltip
      elseif opts.tooltip then self.tip = opts.tooltip end
    end
  end
  if opts.selected then g:outline(x, y, size, size, self.theme.accent) end
  if opts.clickable ~= false and self:_clicked(id, opts.data) then
    return true, hot, self.clickMods or EMPTY
  end
  return false, hot, EMPTY
end

--- Card with an item on the left and up to a few text lines. t = { item, count, title, lines = { {text, color}, … },
--- tooltip (extra item tooltip lines), selected, disabled, dim }. Returns clicked, hovered.
function Ctx:itemTile(id, data, x, y, w, h, t)
  local clicked, hot = self:card(id, x, y, w, h, { data = data, selected = t.selected, disabled = t.disabled, sound = t.sound })
  local g = self.g
  local iy = y + floor((h - 18) / 2)
  if t.item then
    g:sprite("container/slot", x + 4, iy, 18, 18)
    g:item(t.item, x + 5, iy + 1, { count = t.count or 1 })
    if t.dim then g:rect(x + 5, iy + 1, 16, 16, 0x90181818) end
  end
  local tx = x + 27
  local tw = w - 31
  local ty = y + 4
  if t.title then
    g:text(self:ellipsize(t.title, tw), tx, ty, { color = t.titleColor or self.theme.text })
    ty = ty + 11
  end
  local lines = t.lines
  if lines then
    for i = 1, #lines do
      local line = lines[i]
      if ty + 8 > y + h then break end
      g:text(self:ellipsize(line[1], tw), tx, ty, { color = line[2] or self.theme.muted })
      ty = ty + 10
    end
  end
  if hot and t.item and type(t.item) == "userdata" then self.itemTip, self.itemTipLines = t.item, t.tooltip end
  return clicked, hot
end

-- ---- text input ------------------------------------------------------------------------------------------------

local STUB = {}
STUB.__index = STUB
STUB.__metatable = "ui_kit.field"
function STUB:focus() end
function STUB:blur() end
function STUB:setValue(v) self.value = v end

--- Native text field (s:textInput) that hides itself behind modals and dropdown lists drawn on top of it (the
--- runtime knows no Lua layers): then a look-alike is drawn and no native field is declared. Same opts as
--- s:textInput (x, y, w, h, value, placeholder, maxLength, numeric, enabled, focus, onChange).
--- Returns the field (or a stub with value/valid/focused).
function Ctx:textInput(id, opts)
  local x, y, w, h = opts.x, opts.y, opts.w, opts.h or 20
  if self:blocked(x, y, w, h) then
    local g = self.g
    g:sprite("widget/text_field", x, y, w, h)
    local v = opts.value or ""
    if v ~= "" then
      g:text(self:ellipsize(v, w - 8), x + 4, y + floor((h - 8) / 2), { color = 0xFFE0E0E0 })
    elseif opts.placeholder then
      g:text(self:ellipsize(opts.placeholder, w - 8), x + 4, y + floor((h - 8) / 2), { color = 0xFF707070 })
    end
    return setmetatable({ id = id, value = v, valid = true, focused = false, stub = true }, STUB)
  end
  return self.s:textInput(id, opts)
end

-- ---- scroll panel ----------------------------------------------------------------------------------------------

--- Scrollable area. fn(cx, cy, cw) draws the content with its top at cy (already offset by the scroll position) and
--- width cw (without scrollbar); everything is clipped. contentH = full content height. opts: step (24 px per wheel
--- notch), bar (6). Returns the scroll offset. The offset is kept per id (ui:scrollOffset / ui:setScroll).
function Ctx:scrollPanel(id, x, y, w, h, contentH, fn, opts)
  opts = opts or NOOPTS
  local st = self:stateOf(id)
  local maxY = max(0, contentH - h)
  st.max, st.step = maxY, opts.step or 24
  local sy = clamp(st.y or 0, 0, maxY)
  self:_region(id, nil, x, y, w, h, SCROLL)
  local bar = opts.bar or 6
  local innerW = maxY > 0 and (w - bar - 2) or w
  local g = self.g
  if maxY > 0 then
    local bx = x + w - bar
    local thumbH = max(16, floor(h * h / contentH))
    local range = h - thumbH
    -- dragging the thumb
    if self.active == id and self.activeData == "thumb" then
      if not st.grab then st.grab = sy end
      sy = clamp(st.grab + (self.my - self.grabY) * maxY / max(1, range), 0, maxY)
    else
      st.grab = nil
    end
    local thumbY = y + floor(range * sy / maxY + 0.5)
    self:_region(id, "track", bx, y, bar, h, CLICK)
    local thumbHot = self:_widget(id, "thumb", bx, thumbY, bar, thumbH, DRAG)
    if self:_clicked(id, "track") then
      sy = clamp(sy + (self.my < thumbY and -h or h) * 0.9, 0, maxY)
    end
    self:_clicked(id, "thumb")
    g:sprite("widget/scroller_background", bx, y, bar, h)
    g:sprite("widget/scroller", bx, y + floor(range * sy / maxY + 0.5), bar, thumbH)
    if thumbHot or (self.active == id and self.activeData == "thumb") then self.cursorName = "resize_ns" end
  end
  st.y = sy
  self:clip(x, y, innerW, h, function() fn(x, y - sy, innerW) end)
  return sy
end

function Ctx:scrollOffset(id) return (self.state[id] and self.state[id].y) or 0 end
function Ctx:setScroll(id, y) self:stateOf(id).y = max(0, y) end

-- ---- dropdown --------------------------------------------------------------------------------------------------

--- Dropdown. options = { { value =, label = }, … } or { "a", "b" } (value = label). Returns value, changed.
--- opts: h (20), placeholder, disabled, tooltip, rows (8 visible rows before scrolling).
--- The list is drawn over everything (ui:finish), keyboard: up/down/home/end/enter/escape, wheel scrolls.
function Ctx:dropdown(id, x, y, w, options, value, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local h = opts.h or 20
  local count = #options
  local selectedIndex = nil
  for i = 1, count do
    local o = options[i]
    if (type(o) == "table" and o.value or o) == value then selectedIndex = i break end
  end
  local changed = false
  if self.picked == id then
    local o = options[self.pickedIndex]
    if o ~= nil then
      local v = type(o) == "table" and o.value or o
      changed = v ~= value
      value, selectedIndex = v, self.pickedIndex
      self.s:sound("click")
    end
    self.picked = nil
  end
  local hot = self:_widget(id, nil, x, y, w, h, CLICK)
  local open = self.openDropdown == id
  if self:_clicked(id, nil) and not opts.disabled then
    if open then
      self.openDropdown, open = nil, false
    else
      self.openDropdown, open = id, true
      local dd = self:stateOf(id)
      dd.hl, dd.depth, dd.ensureVisible, dd.top = selectedIndex or 1, self.modalDepth, true, nil
      self.s:sound("click")
    end
  end
  g:rect(x, y, w, h, opts.disabled and 0xFF1A1B1E or t.field)
  g:outline(x, y, w, h, open and t.accent or (hot and t.fieldBorderHover or t.fieldBorder))
  local o = selectedIndex and options[selectedIndex]
  local label = o and (type(o) == "table" and o.label or o) or (opts.placeholder or "")
  g:text(self:ellipsize(label, w - 18), x + 5, y + floor((h - 8) / 2), { color = (o and not opts.disabled) and t.text or t.dim })
  -- chevron
  local cx, cy = x + w - 11, y + floor(h / 2) - 1
  local col = opts.disabled and t.dim or t.muted
  if open then
    g:rect(cx + 2, cy, 1, 1, col); g:rect(cx + 1, cy + 1, 3, 1, col); g:rect(cx, cy + 2, 5, 1, col)
  else
    g:rect(cx, cy, 5, 1, col); g:rect(cx + 1, cy + 1, 3, 1, col); g:rect(cx + 2, cy + 2, 1, 1, col)
  end
  if hot then
    self.cursorName = opts.disabled and "not_allowed" or "hand"
    if opts.tooltip and not open then self.tip = opts.tooltip end
  end
  if open then
    local dd = self:stateOf(id)
    if (dd.depth or 0) < self.modalDepth then
      self.openDropdown = nil
    else
      dd.id, dd.bx, dd.by, dd.bw, dd.bh, dd.options, dd.count, dd.selected = id, x, y, w, h, options, count, selectedIndex
      dd.rows = opts.rows or 8
      local n = self.noverlays + 1
      self.noverlays = n
      self.overlays[n] = dd
    end
  end
  return value, changed
end

local ROW = 14

function Ctx:_drawDropdown(dd)
  local g, s, t = self.g, self.s, self.theme
  local count = dd.count
  local rows = min(dd.rows, max(1, count))
  local listH = rows * ROW + 2
  local ly = dd.by + dd.bh
  if ly + listH > s.height and dd.by - listH >= 0 then ly = dd.by - listH end
  local id = dd.id
  -- whole screen: clicking outside closes the list
  self:_region(id, "dismiss", -10000, -10000, 20000, 20000, BLOCK)
  self:_region(id, "list", dd.bx, ly, dd.bw, listH, BLOCK)
  local maxTop = max(0, count - rows)
  local st = self:stateOf(id)
  -- scrolling (wheel via region SCROLL of this id: offset in pixels in st.y)
  st.step = ROW
  st.max = maxTop * ROW
  if dd.ensureVisible and dd.hl then
    local top = floor((st.y or 0) / ROW)
    if dd.hl <= top then st.y = (dd.hl - 1) * ROW
    elseif dd.hl > top + rows then st.y = (dd.hl - rows) * ROW end
    dd.ensureVisible = false
  end
  st.y = clamp(st.y or 0, 0, st.max)
  local top = floor(st.y / ROW + 0.5)
  if maxTop > 0 then self:_region(id, nil, dd.bx, ly, dd.bw, listH, SCROLL) end
  g:rect(dd.bx, ly, dd.bw, listH, t.overlay)
  g:outline(dd.bx, ly, dd.bw, listH, t.accent)
  local barW = maxTop > 0 and 4 or 0
  for r = 1, rows do
    local i = top + r
    if i > count then break end
    local o = dd.options[i]
    local ry = ly + 1 + (r - 1) * ROW
    self:_region(id, i, dd.bx + 1, ry, dd.bw - 2 - barW, ROW, CLICK)
    local hover = self.mx >= dd.bx and self.mx < dd.bx + dd.bw - barW and self.my >= ry and self.my < ry + ROW
    if hover and (self.lastHoverMx ~= self.mx or self.lastHoverMy ~= self.my) then dd.hl = i end
    if i == dd.hl then g:rect(dd.bx + 1, ry, dd.bw - 2 - barW, ROW, t.overlayHover) end
    local label = type(o) == "table" and o.label or o
    g:text(self:ellipsize(label, dd.bw - 12 - barW), dd.bx + 5, ry + 3, { color = i == dd.selected and t.accent or t.text })
    if hover then self.cursorName = "hand" end
  end
  self.lastHoverMx, self.lastHoverMy = self.mx, self.my
  if maxTop > 0 then
    local trackH = listH - 2
    local thumbH = max(8, floor(trackH * rows / count))
    local thumbY = ly + 1 + floor((trackH - thumbH) * top / maxTop + 0.5)
    g:rect(dd.bx + dd.bw - 1 - barW, ly + 1, barW, trackH, 0xFF101114)
    g:rect(dd.bx + dd.bw - 1 - barW, thumbY, barW, thumbH, 0xFF8A909A)
  end
end

-- ---- modal -----------------------------------------------------------------------------------------------------

--- Modal dialog centered on the screen: dims everything drawn before, blocks input below it and hides native
--- text fields below it (when they are declared via ui:textInput). Draw it after the content it covers; widgets
--- drawn after it are inside the modal. opts: title, dismiss (click on the backdrop reports dismissed).
--- Returns x, y, w, h of the content area (below the title) and dismissed.
function Ctx:modal(id, w, h, opts)
  opts = opts or NOOPTS
  local g, s, t = self.g, self.s, self.theme
  self.modalDepth = self.modalDepth + 1
  if self.openDropdown then
    local dd = self.state[self.openDropdown]
    if not dd or (dd.depth or 0) < self.modalDepth then self.openDropdown = nil end
  end
  w, h = min(w, s.width - 8), min(h, s.height - 8)
  g:rect(0, 0, s.width, s.height, t.backdrop)
  self:_region(id, opts.dismiss and "dismiss" or "backdrop", -10000, -10000, 20000, 20000, BLOCK)
  local x, y = floor((s.width - w) / 2), floor((s.height - h) / 2)
  self:_region(id, "panel", x, y, w, h, BLOCK)
  g:rect(x, y, w, h, t.modal)
  g:outline(x, y, w, h, opts.border or t.modalBorder)
  local top = 0
  if opts.title then
    g:text(self:ellipsize(opts.title, w - 16), x + 8, y + 7, { color = opts.titleColor or t.accent })
    g:rect(x + 1, y + 20, w - 2, 1, t.modalBorder)
    top = 21
  end
  local dismissed = self.dismissed == id
  if dismissed then self.dismissed = nil end
  return x + 8, y + top + 6, w - 16, h - top - 12, dismissed
end

-- ---- toasts ----------------------------------------------------------------------------------------------------

--- Small notification at the bottom of the screen for `seconds` (3). kind: "info" | "success" | "error".
--- May be called outside render (e.g. from a channel handler).
function Ctx:toast(text, kind, seconds)
  local list = self.toasts
  if #list >= 4 then table.remove(list, 1) end
  list[#list + 1] = { text = tostring(text), kind = kind or "info", t0 = clock(), d = seconds or 3 }
end

local function withAlpha(color, a)
  local rgb = color % 0x1000000
  local alpha = floor((floor(color / 0x1000000) % 256) * a)
  return alpha * 0x1000000 + rgb
end

function Ctx:_drawToasts()
  local list = self.toasts
  if #list == 0 then return end
  local g, s, t = self.g, self.s, self.theme
  local now = clock()
  local y = s.height - 8
  for i = #list, 1, -1 do
    local toast = list[i]
    local age = now - toast.t0
    if age > toast.d then
      table.remove(list, i)
    else
      local a = min(1, age / 0.15, (toast.d - age) / 0.4)
      local text = self:ellipsize(toast.text, s.width - 40)
      local w = g:textWidth(text) + 18
      local x = floor((s.width - w) / 2)
      y = y - 20
      local stripe = toast.kind == "error" and t.danger or (toast.kind == "success" and t.success or t.info)
      g:rect(x, y, w, 18, withAlpha(0xF0101216, a))
      g:rect(x, y, 3, 18, withAlpha(stripe, a))
      g:outline(x, y, w, 18, withAlpha(0xFF3A3F47, a))
      if a > 0.1 then g:text(text, x + 10, y + 5, { color = withAlpha(0xFFF0F0F0, a) }) end
      y = y - 2
    end
  end
end

-- ---- display helpers (1.1) ------------------------------------------------------------------------------------

--- Horizontal bar for a value 0..1 (energy, progress, quest objectives). opts: color (accent), bg, border (false =
--- none), text (centered label), textColor, tooltip (when hovered).
function Ctx:progressBar(x, y, w, h, value, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local v = clamp(tonumber(value) or 0, 0, 1)
  g:rect(x, y, w, h, opts.bg or t.field)
  local fill = floor((w - 2) * v + 0.5)
  if fill > 0 then g:rect(x + 1, y + 1, fill, h - 2, opts.color or t.accent) end
  if opts.border ~= false then g:outline(x, y, w, h, opts.border or t.panelBorder) end
  if opts.text and h >= 9 then
    g:text(self:ellipsize(opts.text, w - 4), x + floor(w / 2), y + floor((h - 8) / 2), { align = "center", color = opts.textColor or t.text })
  end
  if opts.tooltip and self:hovered(x, y, w, h) then self.tip = opts.tooltip end
end

--- Small colored label ("Läuft", "Neu", "3/5"). opts: color (frame and tinted background), textColor, align
--- ("left" | "right": x is the right edge), h (11). Returns the width.
function Ctx:badge(text, x, y, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local color = opts.color or t.muted
  local h = opts.h or 11
  local w = g:textWidth(text) + 6
  if opts.align == "right" then x = x - w end
  g:rect(x, y, w, h, (color % 0x1000000) + 0x40000000)
  g:outline(x, y, w, h, color)
  g:text(text, x + 3, y + floor((h - 8) / 2) + 1, { color = opts.textColor or color, shadow = false })
  return w
end

--- Section title with a rule to the right (forms, detail panes). opts: color, line. Returns the height (14).
function Ctx:section(title, x, y, w, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local tw = g:text(title, x, y, { color = opts.color or t.accent })
  if w - tw - 6 > 0 then g:rect(x + tw + 6, y + 4, w - tw - 6, 1, opts.line or t.panelBorder) end
  return 14
end

-- ---- search field (1.1) ---------------------------------------------------------------------------------------

--- Native text field with a search icon on the left and a clear button (×) while it has text. Same opts as
--- ui:textInput (x, y, w, h = 20, value, placeholder, maxLength, onChange, ...), plus clear (default true) and
--- clearTooltip. The clear button calls onChange(field, "") and empties the field.
--- Returns the field (or stub, see ui:textInput) and cleared (true in the frame the button was clicked).
function Ctx:searchField(id, opts)
  local g, t = self.g, self.theme
  local x, y, w, h = opts.x, opts.y, opts.w, opts.h or 20
  local value = opts.value or ""
  local clearW = (value ~= "" and opts.clear ~= false) and h or 0
  g:rect(x, y, h, h, t.field)
  g:outline(x, y, h, h, t.fieldBorder)
  g:sprite("icon/search", x + floor((h - 12) / 2), y + floor((h - 12) / 2), 12, 12)
  local fo = {}
  for k, v in pairs(opts) do fo[k] = v end
  fo.x, fo.w, fo.h = x + h - 1, max(8, w - h + 1 - clearW), h
  fo.clear, fo.clearTooltip = nil, nil
  local field = self:textInput(id, fo)
  local cleared = false
  if clearW > 0 and self:button(id, x + w - clearW, y, clearW, h, "×", { data = "clear", tooltip = opts.clearTooltip or "Suche leeren" }) then
    if opts.onChange then opts.onChange(field, "") end
    field:setValue("")
    cleared = true
  end
  return field, cleared
end

-- ---- selectable list (1.1) ------------------------------------------------------------------------------------

--- Scrollable list with one selected entry. items = array of tables (or strings); selected = key of the selected
--- entry (item[opts.key or "id"], the string itself, or the index when an item has no key). Only visible rows are
--- drawn. opts: rowH (18), gap (2), key, empty (text when there are no items), render = function(item, x, y, w, h,
--- selected, hovered, index) to draw a row's content on the card (default: item.label or the string, plus
--- item.sub on the right). Returns selected, changed.
function Ctx:list(id, x, y, w, h, items, selected, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local rowH, gap = opts.rowH or 18, opts.gap or 2
  local keyField = opts.key or "id"
  local n = #items
  local changed = false
  if n == 0 then
    if opts.empty then self:paragraph(opts.empty, x + 4, y + 4, w - 8, { color = t.dim }) end
    return selected, false
  end
  local pitch = rowH + gap
  local render = opts.render
  self:scrollPanel(id, x, y, w, h, n * pitch - gap, function(cx, cy, cw)
    local first = max(1, floor((y - cy) / pitch) + 1)
    for i = first, n do
      local ry = cy + (i - 1) * pitch
      if ry >= y + h then break end
      local item = items[i]
      local key = item
      if type(item) == "table" then key = item[keyField] end
      if key == nil then key = i end
      local sel = key == selected
      local clicked, hot = self:card(id, cx, ry, cw, rowH, { data = i, selected = sel, sound = opts.sound })
      if clicked and not sel then selected, changed = key, true end
      if render then
        render(item, cx, ry, cw, rowH, sel, hot, i)
      else
        local label = type(item) == "table" and (item.label or tostring(key)) or tostring(item)
        local sub = type(item) == "table" and item.sub
        local subW = sub and (g:textWidth(sub) + 8) or 0
        g:text(self:ellipsize(label, cw - 10 - subW), cx + 5, ry + floor((rowH - 8) / 2), { color = sel and 0xFFFFFFFF or t.text })
        if sub then g:text(sub, cx + cw - 5, ry + floor((rowH - 8) / 2), { align = "right", color = t.dim }) end
      end
    end
  end, { step = opts.step or pitch * 2 })
  return selected, changed
end

-- ---- table (1.1) ----------------------------------------------------------------------------------------------

local function sortValue(col, row)
  local v
  if col.sortValue then v = col.sortValue(row) else v = row[col.key] end
  if type(v) == "string" then return K.lower(v) end
  return v
end

--- Sorted copy of rows (stable), cached per id until rows, sort key/direction or opts.rev change.
function Ctx:_sortedRows(st, rows, columns, sortKey, desc, rev)
  if st.src == rows and st.sk == sortKey and st.sd == desc and st.rev == rev and st.sorted then return st.sorted end
  local col
  for i = 1, #columns do if columns[i].key == sortKey then col = columns[i] break end end
  local list = {}
  for i = 1, #rows do list[i] = rows[i] end
  if col then
    local keys, order = {}, {}
    for i = 1, #list do keys[list[i]] = sortValue(col, list[i]); order[list[i]] = i end
    table.sort(list, function(a, b)
      local va, vb = keys[a], keys[b]
      if va ~= vb then
        if va == nil then return false end
        if vb == nil then return true end
        if type(va) ~= type(vb) then va, vb = tostring(va), tostring(vb) end
        if desc then return va > vb end
        return va < vb
      end
      return order[a] < order[b]
    end)
  end
  st.src, st.sk, st.sd, st.rev, st.sorted = rows, sortKey, desc, rev, list
  return list
end

local function arrow(g, x, y, up, color)
  if up then
    g:rect(x + 2, y, 1, 1, color); g:rect(x + 1, y + 1, 3, 1, color); g:rect(x, y + 2, 5, 1, color)
  else
    g:rect(x, y, 5, 1, color); g:rect(x + 1, y + 1, 3, 1, color); g:rect(x + 2, y + 2, 1, 1, color)
  end
end

--- Table with a header row; a click on a sortable header sorts by that column (again: reverse). Only visible rows
--- are drawn; sorting is cached (pass a new rows table or bump opts.rev when the data changes in place).
--- columns = { { key, label, w (px) or flex (share of the rest, default 1), align ("left" | "right" | "center"),
---   sortable (default true), desc (first click sorts descending), tooltip, format = function(value, row) -> text,
---   color = function(row) -> color, sortValue = function(row) -> value }, … }
--- opts: sortKey, sortDesc (current sort, keep them in the app), rowH (14), headerH (16), highlight = field name or
--- function(row) -> bool (e.g. the player's own row), selected (row table), empty (text), rev, clickable (true).
--- Returns sortKey, sortDesc, clickedRow (left click on a row, in the frame after the click).
function Ctx:table(id, x, y, w, h, columns, rows, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local rowH, headH = opts.rowH or 14, opts.headerH or 16
  local st = self:stateOf(id)
  local sortKey, desc = opts.sortKey, opts.sortDesc and true or false
  -- column widths
  local ncol = #columns
  local fixed, flex = 0, 0
  for i = 1, ncol do
    local c = columns[i]
    if c.w then fixed = fixed + c.w else flex = flex + (c.flex or 1) end
  end
  local rest = max(0, w - 8 - fixed) -- 8 px for the scrollbar
  local xs, ws = st.xs or {}, st.ws or {}
  st.xs, st.ws = xs, ws
  local cx = x
  for i = 1, ncol do
    local c = columns[i]
    local cw = c.w or floor(rest * (c.flex or 1) / max(1, flex))
    xs[i], ws[i] = cx, cw
    cx = cx + cw
  end
  -- header
  g:rect(x, y, w, headH, t.card)
  g:rect(x, y + headH - 1, w, 1, t.panelBorder)
  for i = 1, ncol do
    local c = columns[i]
    local hx, hw = xs[i], ws[i]
    local active = sortKey ~= nil and c.key == sortKey
    local hot = false
    if c.sortable ~= false then
      hot = self:_widget(id, -i, hx, y, hw, headH, CLICK)
      if hot then
        g:rect(hx, y, hw, headH - 1, t.cardHover)
        self.cursorName = "hand"
        self.tip = c.tooltip or "Sortieren"
      end
      if self:_clicked(id, -i) then
        if active then desc = not desc else sortKey, desc = c.key, c.desc and true or false end
        active = true
        self.s:sound("click")
      end
    end
    local color = active and t.accent or (hot and t.text or t.muted)
    local label = self:ellipsize(c.label or "", hw - (active and 14 or 8))
    local ty = y + floor((headH - 8) / 2)
    if c.align == "right" then
      g:text(label, hx + hw - 4 - (active and 8 or 0), ty, { align = "right", color = color })
      if active then arrow(g, hx + hw - 9, ty + 2, not desc, color) end
    elseif c.align == "center" then
      g:text(label, hx + floor(hw / 2), ty, { align = "center", color = color })
      if active then arrow(g, hx + floor(hw / 2) + floor(g:textWidth(label) / 2) + 3, ty + 2, not desc, color) end
    else
      local tw = g:text(label, hx + 4, ty, { color = color })
      if active then arrow(g, hx + 4 + tw + 3, ty + 2, not desc, color) end
    end
  end
  -- rows
  local list = self:_sortedRows(st, rows, columns, sortKey, desc, opts.rev)
  local n = #list
  local bodyY, bodyH = y + headH, h - headH
  local clickedRow = nil
  if n == 0 then
    if opts.empty then g:text(self:ellipsize(opts.empty, w - 8), x + floor(w / 2), bodyY + 8, { align = "center", color = t.dim }) end
    return sortKey, desc, nil
  end
  local highlight = opts.highlight
  local hlFn = type(highlight) == "function"
  local clickable = opts.clickable ~= false
  st.rowsId = st.rowsId or (id .. "#rows")
  self:scrollPanel(st.rowsId, x, bodyY, w, bodyH, n * rowH, function(px, py, pw)
    local first = max(1, floor((bodyY - py) / rowH) + 1)
    for r = first, n do
      local ry = py + (r - 1) * rowH
      if ry >= bodyY + bodyH then break end
      local row = list[r]
      local hot = clickable and self:_widget(id, r, px, ry, pw, rowH, CLICK) or (not clickable and self:hovered(px, ry, pw, rowH))
      local hl = false
      if highlight then
        if hlFn then hl = highlight(row) and true or false else hl = row[highlight] and true or false end
      end
      local bg
      if row == opts.selected then bg = t.cardSelected
      elseif hl then bg = t.primaryTint
      elseif hot then bg = t.cardHover
      elseif r % 2 == 0 then bg = 0x40000000 end
      if bg then g:rect(px, ry, pw, rowH, bg) end
      if hl then g:rect(px, ry, 2, rowH, t.accent) end
      local ty = ry + floor((rowH - 8) / 2)
      for i = 1, ncol do
        local c = columns[i]
        local cw = min(ws[i], px + pw - xs[i])
        if cw > 6 then
          local v = row[c.key]
          local text
          if c.format then text = c.format(v, row) elseif v == nil then text = "" else text = tostring(v) end
          local color = (c.color and c.color(row)) or (hl and 0xFFFFFFFF or t.text)
          text = self:ellipsize(text, cw - 8)
          if c.align == "right" then g:text(text, xs[i] + cw - 4, ty, { align = "right", color = color })
          elseif c.align == "center" then g:text(text, xs[i] + floor(cw / 2), ty, { align = "center", color = color })
          else g:text(text, xs[i] + 4, ty, { color = color }) end
        end
      end
      if clickable then
        if hot then self.cursorName = "hand" end
        if self:_clicked(id, r) then clickedRow = row end
      end
    end
  end, { step = rowH * 3 })
  return sortKey, desc, clickedRow
end

-- ---- confirmation dialog (1.1) --------------------------------------------------------------------------------

--- Modal yes/no question. opts: title, text (wrapped, centered), yes ("Ja"), no ("Abbrechen"), danger (red yes
--- button), pending (buttons disabled, yes shows "…"), w (280). A click on the backdrop counts as "no" (not while
--- pending). Returns "yes", "no" or nil. Handle Escape in the screen's key callback (e.g. same as "no").
function Ctx:confirm(id, opts)
  opts = opts or NOOPTS
  local g, t = self.g, self.theme
  local w = min(opts.w or 280, self.s.width - 8)
  local lines = g:wrap(opts.text or "", w - 16)
  local h = (opts.title and 21 or 0) + 12 + #lines * 10 + 10 + 20
  local mx, my, mw, mh, dismissed = self:modal(id, w, h, { title = opts.title, dismiss = not opts.pending, titleColor = opts.titleColor })
  for i = 1, #lines do
    g:text(lines[i], mx + floor(mw / 2), my + (i - 1) * 10, { align = "center", color = opts.textColor or t.text })
  end
  local bw = min(100, floor((mw - 8) / 2))
  local by = my + mh - 20
  local answer = nil
  if self:button(id, mx + floor(mw / 2) - bw - 4, by, bw, 20, opts.pending and "…" or (opts.yes or "Ja"),
      { data = "yes", style = opts.danger and "danger" or "primary", disabled = opts.pending }) then
    answer = "yes"
  end
  if self:button(id, mx + floor(mw / 2) + 4, by, bw, 20, opts.no or "Abbrechen", { data = "no", disabled = opts.pending }) then
    answer = "no"
  end
  if dismissed and not answer then answer = "no" end
  return answer
end

-- ---- screen wiring (1.1) --------------------------------------------------------------------------------------

--- Screen definition for kiln.ui.screen with the ui_kit wiring done: init creates self.ui = kit.new(def.theme)
--- before def.init runs; render is def.render(self, g, s, ui) between ui:frame and ui:finish; click / release / scroll
--- / key go to the context first and only reach def.click / def.release / def.scroll / def.key if ui_kit did not use
--- them. Other fields (fullscreen, width, height, close, char, drag, resize) are copied. Returns a new table.
function K.screen(def)
  local out = {}
  for k, v in pairs(def) do out[k] = v end
  local init, render, click, release, scroll, key = def.init, def.render, def.click, def.release, def.scroll, def.key
  local theme = def.theme
  out.theme = nil
  out.init = function(self, s, args)
    self.ui = K.new(theme)
    if init then return init(self, s, args) end
  end
  out.render = function(self, g, s)
    local ui = self.ui
    ui:frame(g, s)
    if render then render(self, g, s, ui) end
    ui:finish()
  end
  out.click = function(self, s, mx, my, b, mods)
    if self.ui:click(mx, my, b, mods) then return true end
    if click then return click(self, s, mx, my, b, mods) end
    return false
  end
  out.release = function(self, s, mx, my, b)
    self.ui:release(mx, my, b)
    if release then release(self, s, mx, my, b) end
  end
  out.scroll = function(self, s, mx, my, dy, dx)
    if self.ui:scroll(mx, my, dy) then return true end
    if scroll then return scroll(self, s, mx, my, dy, dx) end
    return false
  end
  out.key = function(self, s, k, mods)
    if self.ui:key(k, mods) then return true end
    if key then return key(self, s, k, mods) end
    return false
  end
  return out
end

kiln.export(K)

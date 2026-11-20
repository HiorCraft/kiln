-- bestenliste: Statistik-/Bestenliste on top of ui_kit. The server ranks the players (category + period chosen with
-- `query`) and refreshes every 5 s; this script shows a sortable table or a bar chart drawn with g:rect.
-- Channels (bestenliste:<name>): open, state, closed, query {req, category, period}, result (S->C).
local kit = kiln.import("ui_kit")

local floor, max, min = math.floor, math.max, math.min
local clock = kiln.clock

local ch = {
  open = kiln.net.channel("open"),
  state = kiln.net.channel("state"),
  closed = kiln.net.channel("closed"),
  query = kiln.net.channel("query"),
  result = kiln.net.channel("result"),
}

local GRAY, DIM, WHITE, GOLD, GREEN = 0xFF9AA0A6, 0xFF6B7078, 0xFFFFFFFF, 0xFFFFC94D, 0xFF62D46F
local HEADER_H, PAD = 30, 6
local MEDAL = { 0xFFFFC94D, 0xFFC8CCD4, 0xFFCD7F32 }

-- ---- model ---------------------------------------------------------------------------------------------------

local M = {
  categories = {}, catOptions = {}, units = {}, periods = {}, category = "BLOCKS", period = "ALL",
  rows = {}, me = nil, players = 0, received = 0, rev = 0,
}
local V = { view = 1, sortKey = "rank", sortDesc = false, selName = nil, loading = false }
local screenOpen, current = false, nil
local nextReq, pending = 0, {}

local function applyState(m)
  if m.categories then
    M.categories, M.catOptions, M.units = m.categories, {}, {}
    for i, c in ipairs(m.categories) do
      M.catOptions[i] = { value = c.id, label = c.label }
      M.units[c.id] = c.unit
    end
  end
  M.periods = m.periods or M.periods
  M.category, M.period = m.category or M.category, m.period or M.period
  M.rows, M.me = m.rows or {}, nil
  for _, r in ipairs(M.rows) do if r.self then M.me = r end end
  M.players = m.players or #M.rows
  M.received = clock()
  M.rev = M.rev + 1
  V.loading = false
end

local function query(category, period)
  nextReq = nextReq + 1
  if not ch.query:send({ req = nextReq, category = category, period = period }) then return end
  pending[nextReq] = true
  V.loading = true
end

local function unit() return M.units[M.category] or "" end

-- ---- table ---------------------------------------------------------------------------------------------------

local COLUMNS = {
  { key = "rank", label = "#", w = 34, align = "right", tooltip = "Nach Rang sortieren",
    format = function(v) return v .. "." end,
    color = function(row) return MEDAL[row.rank] or nil end },
  { key = "name", label = "Spieler", flex = 1, tooltip = "Nach Name sortieren" },
  { key = "value", label = "Wert", w = 96, align = "right", desc = true, tooltip = "Nach Wert sortieren",
    format = function(v) return kit.formatNumber(v) .. " " .. unit() end },
  { key = "gain", label = "Trend", w = 64, align = "right", desc = true, tooltip = "Zuwachs in der letzten Minute",
    format = function(v) return v > 0 and ("+" .. kit.formatNumber(v)) or "–" end,
    color = function(row) return row.gain > 0 and GREEN or DIM end },
}

local function selectedRow()
  if not V.selName then return nil end
  for _, r in ipairs(M.rows) do if r.name == V.selName then return r end end
  return nil
end

local function tableView(ui, g, x, y, w, h)
  local key, desc, clicked = ui:table("board", x, y, w, h, COLUMNS, M.rows, {
    sortKey = V.sortKey, sortDesc = V.sortDesc, highlight = "self", selected = selectedRow(), rowH = 14,
    empty = "Keine Einträge.",
  })
  V.sortKey, V.sortDesc = key, desc
  if clicked then V.selName = clicked.name ~= V.selName and clicked.name or nil end
end

-- ---- chart (g:rect) ------------------------------------------------------------------------------------------

local function chartView(ui, g, x, y, w, h)
  ui:panel(x, y, w, h)
  local top = {}
  for _, r in ipairs(M.rows) do if r.rank <= 10 and #top < 10 then top[#top + 1] = r end end
  if #top == 0 then return end
  local maxV = 1
  for _, r in ipairs(top) do maxV = max(maxV, r.value) end
  local labelW, valueW = 76, 70
  local bx = x + 8 + labelW
  local bw = w - 16 - labelW - valueW
  local rowH = max(10, min(20, floor((h - 24) / #top)))
  local grow = min(1, (clock() - M.received) / 0.35)
  grow = 1 - (1 - grow) * (1 - grow) -- ease out
  -- grid lines at 25 % steps
  for i = 1, 4 do
    local gx = bx + floor(bw * i / 4)
    g:rect(gx, y + 8, 1, #top * rowH, 0x30FFFFFF)
  end
  for i, r in ipairs(top) do
    local ry = y + 8 + (i - 1) * rowH
    local barH = rowH - 4
    local len = max(1, floor(bw * r.value / maxV * grow))
    local color = r.self and GOLD or (MEDAL[r.rank] and 0xFF66AAFF or 0xFF3F6E9E)
    g:text(ui:ellipsize(r.rank .. ". " .. r.name, labelW - 4), x + 8, ry + floor((rowH - 8) / 2), { color = r.self and GOLD or WHITE })
    g:rect(bx, ry + 2, len, barH, color)
    g:rect(bx, ry + 2, len, 1, 0x40FFFFFF)
    g:text(kit.formatCount(r.value), bx + len + 4, ry + floor((rowH - 8) / 2), { color = GRAY })
    if ui:hovered(bx, ry, bw + valueW, rowH) then
      g:rect(bx, ry + 2, len, barH, 0x30FFFFFF)
      ui:tooltip({ r.name, kit.formatNumber(r.value) .. " " .. unit(), "Platz " .. r.rank })
    end
  end
  g:text("Top " .. #top .. " · Skala bis " .. kit.formatNumber(maxV) .. " " .. unit(), x + 8, y + h - 12, { color = DIM, shadow = false })
end

-- ---- screen --------------------------------------------------------------------------------------------------

kiln.ui.screen("main", kit.screen({
  fullscreen = true,
  init = function(self)
    current = self
    screenOpen = true
  end,
  render = function(self, g, s, ui)
    g:gradient(0, 0, s.width, s.height, 0xE00C0E11, 0xF0101216)
    g:gradient(0, 0, s.width, HEADER_H, 0xF0262A31, 0xF01A1D22)
    g:rect(0, HEADER_H - 1, s.width, 1, 0xFF3A3F47)
    local x = PAD
    if s.width >= 500 then x = PAD + ui:heading("Bestenliste", PAD, 11) + 10 end
    local labels, selected = {}, 1
    for i, p in ipairs(M.periods) do
      labels[i] = p.label
      if p.id == M.period then selected = i end
    end
    local tab, changed = ui:tabs("period", x, 6, labels, selected)
    if changed then query(M.category, M.periods[tab].id) end
    local ddW = min(150, max(90, s.width - x - 3 * 70 - 2 * PAD))
    local cat, catChanged = ui:dropdown("category", s.width - PAD - ddW, 5, ddW, M.catOptions, M.category, { tooltip = "Kategorie" })
    if catChanged then query(cat, M.period) end

    local top = HEADER_H + PAD
    local view = ui:tabs("view", PAD, top, { "Tabelle", "Diagramm" }, V.view, { h = 18 })
    V.view = view
    local info = M.players .. " Spieler"
    if M.me then info = "Dein Platz: " .. M.me.rank .. ". (" .. kit.formatNumber(M.me.value) .. " " .. unit() .. ") · " .. info end
    if V.loading then info = "Lädt … · " .. info end
    g:text(ui:ellipsize(info, s.width - 160), s.width - PAD, top + 5, { align = "right", color = GRAY })
    local by = top + 22
    local bh = s.height - by - PAD
    if V.view == 1 then tableView(ui, g, PAD, by, s.width - 2 * PAD, bh) else chartView(ui, g, PAD, by, s.width - 2 * PAD, bh) end
  end,
  close = function()
    screenOpen, current = false, nil
    ch.closed:send({})
  end,
}))

-- ---- channels ------------------------------------------------------------------------------------------------

ch.open:on(function(m)
  applyState(m)
  if not screenOpen then kiln.ui.open("main") end
end)

ch.state:on(applyState)

ch.result:on(function(m)
  if not pending[m.req] then return end
  pending[m.req] = nil
  if not m.ok then
    V.loading = false
    if current then current.ui:toast(m.error or "Fehler", "error") end
  end
end)

kiln.keys.bind("open", { name = "Bestenliste öffnen", default = "N", press = function() ch.open:send({}) end })

-- for tests and debugging
bestenliste_debug = { M = M, V = V }

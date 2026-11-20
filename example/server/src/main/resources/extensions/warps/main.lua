-- warps: Warp-Menü on top of ui_kit. The server owns the warps, favorites and the teleport cooldown and does the
-- teleport; this script filters and shows the grid, and validates the "new warp" form before sending it.
-- Channels (warps:<name>): open, state, closed, teleport {req, warp}, favorite {req, warp, on},
-- create {req, name, icon}, delete {req, warp}, result (S->C).
local kit = kiln.import("ui_kit")

local floor, max, min = math.floor, math.max, math.min
local clock = kiln.clock

local ch = {
  open = kiln.net.channel("open"),
  state = kiln.net.channel("state"),
  closed = kiln.net.channel("closed"),
  teleport = kiln.net.channel("teleport"),
  favorite = kiln.net.channel("favorite"),
  create = kiln.net.channel("create"),
  delete = kiln.net.channel("delete"),
  result = kiln.net.channel("result"),
}

local GRAY, DIM, WHITE, GOLD, RED = 0xFF9AA0A6, 0xFF6B7078, 0xFFFFFFFF, 0xFFFFC94D, 0xFFE86060
local HEADER_H, PAD = 30, 6

-- ---- model ---------------------------------------------------------------------------------------------------

local M = {
  warps = {}, byId = {}, icons = {}, cooldownMillis = 10000, cooldownUntil = 0, maxOwn = 8, own = 0,
  nameMin = 3, nameMax = 24, rev = 0,
}
local V = { search = "", favOnly = false, cache = { rev = -1 }, create = nil, delete = nil, teleporting = nil }
local screenOpen, current = false, nil
local nextReq, pending = 0, {}

local function applyState(m)
  M.cooldownMillis = m.cooldownMillis or M.cooldownMillis
  M.cooldownUntil = clock() + (m.cooldownLeft or 0) / 1000
  M.maxOwn, M.nameMin, M.nameMax = m.maxOwn or 8, m.nameMin or 3, m.nameMax or 24
  M.icons = m.icons or M.icons
  local byId, own = {}, 0
  for _, w in ipairs(m.warps or {}) do
    w.hay = kit.lower(w.name .. " " .. w.description)
    w.coords = w.world .. " · " .. w.x .. ", " .. w.y .. ", " .. w.z
    byId[w.id] = w
    if w.own then own = own + 1 end
  end
  M.warps, M.byId, M.own = m.warps or {}, byId, own
  M.rev = M.rev + 1
end

local function request(action, msg)
  nextReq = nextReq + 1
  msg.req = nextReq
  if not ch[action]:send(msg) then return nil end
  pending[nextReq] = action
  return nextReq
end

local function cooldownLeft() return max(0, M.cooldownUntil - clock()) end

--- Client-side copy of the server's name rules (the server checks again).
local function nameError(name)
  name = name:gsub("^%s+", ""):gsub("%s+$", "")
  local len = #name:gsub("[\128-\191]", "")
  if len < M.nameMin then return "Mindestens " .. M.nameMin .. " Zeichen." end
  if len > M.nameMax then return "Höchstens " .. M.nameMax .. " Zeichen." end
  local plain = name:gsub("\195[\132\150\156\164\182\188\159]", "a")
  if plain:find("[^%w _%-]") then return "Nur Buchstaben, Ziffern, Leerzeichen, _ und -." end
  if name:find("  ", 1, true) then return "Keine doppelten Leerzeichen." end
  local lower = kit.lower(name)
  for _, w in ipairs(M.warps) do
    if kit.lower(w.name) == lower then return "Diesen Namen gibt es schon." end
  end
  return nil
end

--- Warps matching search and favorite filter: favorites first, then by name (cached per state / filter).
local function visible()
  local c = V.cache
  if c.rev ~= M.rev or c.search ~= V.search or c.favOnly ~= V.favOnly then
    c.rev, c.search, c.favOnly = M.rev, V.search, V.favOnly
    local words = {}
    for word in kit.lower(V.search):gmatch("%S+") do words[#words + 1] = word end
    local list = {}
    for _, w in ipairs(M.warps) do
      local ok = not V.favOnly or w.favorite
      for i = 1, #words do if ok and not w.hay:find(words[i], 1, true) then ok = false end end
      if ok then list[#list + 1] = w end
    end
    table.sort(list, function(a, b)
      if a.favorite ~= b.favorite then return a.favorite end
      if a.own ~= b.own then return not a.own end
      return kit.lower(a.name) < kit.lower(b.name)
    end)
    c.list = list
  end
  return c.list
end

-- ---- drawing -------------------------------------------------------------------------------------------------

local function header(ui, g, s)
  g:gradient(0, 0, s.width, HEADER_H, 0xF0262A31, 0xF01A1D22)
  g:rect(0, HEADER_H - 1, s.width, 1, 0xFF3A3F47)
  local x = PAD
  if s.width >= 460 then x = PAD + ui:heading("Warps", PAD, 11) + 10 end
  local newW = 92
  local favW = 20 + g:textWidth("Favoriten")
  local searchW = max(60, s.width - x - newW - favW - 3 * PAD - PAD)
  ui:searchField("search", {
    x = x, y = 5, w = searchW, h = 20, value = V.search, maxLength = 48, placeholder = "Warp suchen …",
    onChange = function(f, v) V.search = v end,
  })
  local fx = x + searchW + PAD
  V.favOnly = ui:checkbox("fav_only", fx, 6, "Favoriten", V.favOnly, { tooltip = "Nur Favoriten zeigen" })
  local full = M.own >= M.maxOwn
  if ui:button("new", s.width - PAD - newW, 5, newW, 20, "+ Neuer Warp", {
    style = "primary", disabled = full, tooltip = "Warp an deiner Position anlegen (" .. M.own .. "/" .. M.maxOwn .. ")",
    disabledTooltip = "Höchstens " .. M.maxOwn .. " eigene Warps – lösche zuerst einen.",
  }) then
    V.create = { name = "", icon = M.icons[1] and M.icons[1].id, touched = false }
  end
end

local STAR = { { 3, 1 }, { 3, 1 }, { 0, 7 }, { 1, 5 }, { 2, 3 }, { 1, 2, 4, 2 }, { 0, 2, 5, 2 } }

--- 7x7 star from rects (the font has no star glyph).
local function star(g, x, y, color)
  for row, spans in ipairs(STAR) do
    for i = 1, #spans, 2 do g:rect(x + spans[i], y + row - 1, spans[i + 1], 1, color) end
  end
end

local function tile(ui, g, w, x, y, cw, chh, cooling)
  local busy = V.teleporting == w.id
  local clicked, hot = ui:card("warp", x, y, cw, chh, {
    data = w.id, disabled = cooling or busy, selected = busy,
    tooltip = cooling and ("Abklingzeit: noch " .. math.ceil(cooldownLeft()) .. " s") or { w.name, w.description, "Klicken zum Teleportieren" },
  })
  g:sprite("container/slot", x + 4, y + floor((chh - 18) / 2), 18, 18)
  g:item(w.icon, x + 5, y + floor((chh - 18) / 2) + 1)
  local tw = cw - 27 - (w.own and 36 or 20)
  g:text(ui:ellipsize(w.name, tw), x + 27, y + 5, { color = w.favorite and GOLD or WHITE })
  g:text(ui:ellipsize(w.coords, cw - 31), x + 27, y + 16, { color = GRAY })
  g:text(ui:ellipsize(w.own and "Eigener Warp" or w.description, cw - 31), x + 27, y + 27, { color = DIM, shadow = false })
  if cooling then g:rect(x + 1, y + 1, cw - 2, chh - 2, 0x80101010) end
  if busy then g:text("…", x + cw - 6, y + chh - 12, { align = "right", color = GOLD }) end
  -- star and delete lie on the card (drawn later = on top, they get the click)
  local starClicked = ui:button("star", x + cw - 18, y + 3, 15, 14, "", {
    data = w.id, tooltip = w.favorite and "Aus den Favoriten entfernen" or "Zu den Favoriten",
  })
  star(g, x + cw - 14, y + 6, w.favorite and GOLD or 0xFF4A4F57)
  if starClicked then
    w.favorite = not w.favorite
    V.cache.rev = -1
    request("favorite", { warp = w.id, on = w.favorite })
  end
  if w.own and ui:button("del", x + cw - 34, y + 3, 15, 14, "×", { data = w.id, style = "danger", tooltip = "Warp löschen" }) then
    V.delete = { id = w.id }
  end
  if clicked and not cooling and not busy then
    if request("teleport", { warp = w.id }) then V.teleporting = w.id end
  end
end

local function grid(ui, g, s, top, height)
  local list = visible()
  local x, w = PAD, s.width - 2 * PAD
  local left = cooldownLeft()
  local cooling = left > 0
  if cooling then
    ui:progressBar(x, top, w, 10, left * 1000 / M.cooldownMillis, {
      color = 0xFF66AAFF, text = "Teleport wieder möglich in " .. math.ceil(left) .. " s",
    })
    top, height = top + 14, height - 14
  end
  if #list == 0 then
    ui:panel(x, top, w, height)
    g:text(V.search ~= "" and "Kein Warp passt zur Suche." or (V.favOnly and "Noch keine Favoriten – markiere Warps mit dem Stern." or "Keine Warps."),
      x + floor(w / 2), top + 20, { align = "center", color = GRAY })
    return
  end
  local layout = kit.grid(0, 0, w - 8, { minWidth = 150, h = 40, gap = 4 })
  ui:scrollPanel("grid", x, top, w, height, layout:height(#list), function(cx, cy, cw)
    local first = max(1, floor((top - cy) / (layout.cellH + layout.gap)) * layout.cols + 1)
    for i = first, #list do
      local tx, ty, tw, th = layout:cell(i)
      ty = ty + cy
      if ty >= top + height then break end
      tile(ui, g, list[i], cx + tx, ty, tw, th, cooling)
    end
  end, { step = 44 })
end

local function createModal(ui, g, s)
  local c = V.create
  if not c then return end
  local mx, my, mw, mh, dismissed = ui:modal("create", 300, 168, { title = "Neuer Warp", dismiss = not c.pending })
  if dismissed then V.create = nil return end
  local err = (c.fields and c.fields.name) or nameError(c.name)
  g:text("Name", mx, my + 6, { color = GRAY })
  ui:textInput("create_name", {
    x = mx + 40, y = my, w = mw - 40, h = 20, value = c.name, maxLength = 32, focus = true, enabled = not c.pending,
    placeholder = M.nameMin .. "–" .. M.nameMax .. " Zeichen",
    onChange = function(f, v) c.name, c.touched, c.fields = v, true, nil end,
  })
  if err and c.touched then g:text(ui:ellipsize(err, mw - 40), mx + 40, my + 23, { color = RED }) end
  g:text("Symbol", mx, my + 42, { color = GRAY })
  for i, icon in ipairs(M.icons) do
    local ix = mx + 40 + (i - 1) * 20
    if ix + 18 > mx + mw then break end
    if ui:itemSlot("create_icon", ix, my + 36, icon.id, 1, { data = i, selected = c.icon == icon.id, tooltip = false }) then c.icon = icon.id end
    if ui:isHot("create_icon", i) then ui:tooltip(icon.label) end
  end
  local p = kiln.player()
  g:text("Position: " .. floor(p.x) .. ", " .. floor(p.y) .. ", " .. floor(p.z) .. " (dein Standort)", mx, my + 64, { color = DIM })
  local valid = err == nil and c.icon ~= nil
  local bw = floor((mw - 8) / 2)
  if ui:button("create_ok", mx, my + mh - 20, bw, 20, c.pending and "…" or "Anlegen", {
    style = "primary", disabled = c.pending or not valid, disabledTooltip = err,
  }) then
    c.pending = request("create", { name = c.name, icon = c.icon }) ~= nil
  end
  if ui:button("create_cancel", mx + bw + 8, my + mh - 20, bw, 20, "Abbrechen", { disabled = c.pending }) then V.create = nil end
end

local function deleteDialog(ui)
  local d = V.delete
  if not d then return end
  local w = M.byId[d.id]
  if not w then V.delete = nil return end
  local answer = ui:confirm("delete", {
    title = "Warp löschen", text = "„" .. w.name .. "“ wirklich löschen? Das kann nicht rückgängig gemacht werden.",
    yes = "Löschen", danger = true, pending = d.pending,
  })
  if answer == "yes" then d.pending = request("delete", { warp = d.id }) ~= nil
  elseif answer == "no" then V.delete = nil end
end

-- ---- screen --------------------------------------------------------------------------------------------------

kiln.ui.screen("main", kit.screen({
  fullscreen = true,
  init = function(self)
    current = self
    screenOpen = true
    V.create, V.delete, V.teleporting = nil, nil, nil
  end,
  render = function(self, g, s, ui)
    g:gradient(0, 0, s.width, s.height, 0xE00C0E11, 0xF0101216)
    header(ui, g, s)
    local top = HEADER_H + PAD
    grid(ui, g, s, top, s.height - top - PAD)
    createModal(ui, g, s)
    deleteDialog(ui)
  end,
  key = function(self, s, key)
    if key == "escape" then
      if V.create then if not V.create.pending then V.create = nil end return true end
      if V.delete then if not V.delete.pending then V.delete = nil end return true end
      return false
    end
    if (key == "enter" or key == "keypad.enter") and V.create and not V.create.pending then
      local c = V.create
      c.touched = true
      if nameError(c.name) == nil and c.icon then c.pending = request("create", { name = c.name, icon = c.icon }) ~= nil end
      return true
    end
    return false
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
  local action = pending[m.req]
  pending[m.req] = nil
  if not action then return end
  if action == "teleport" then
    V.teleporting = nil
    if m.ok then M.cooldownUntil = clock() + M.cooldownMillis / 1000 end
  elseif action == "create" and V.create then
    if m.ok then V.create = nil else V.create.pending, V.create.fields, V.create.touched = false, m.fields, true end
  elseif action == "delete" then
    V.delete = nil
  elseif action == "favorite" and not m.ok then
    V.cache.rev = -1
  end
  local text = m.ok and m.message or m.error
  if current and text then
    current.ui:toast(text, m.ok and "success" or "error")
    if action ~= "favorite" then kiln.ui.sound(m.ok and "success" or "error") end
  end
end)

kiln.keys.bind("open", { name = "Warps öffnen", default = "V", press = function() ch.open:send({}) end })

-- for tests and debugging
warps_debug = { M = M, V = V, nameError = nameError, visible = visible }

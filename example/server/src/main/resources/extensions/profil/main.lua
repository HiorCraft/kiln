-- profil: Einstellungen/Profil on top of ui_kit. The form edits a local copy of the saved settings; "Speichern"
-- sends everything, the server validates every field and answers with field errors. Leaving with unsaved changes
-- asks first (modal).
-- Channels (profil:<name>): open, state, closed, save {req, settings}, result (S->C).
local kit = kiln.import("ui_kit")

local floor, max, min = math.floor, math.max, math.min

local ch = {
  open = kiln.net.channel("open"),
  state = kiln.net.channel("state"),
  closed = kiln.net.channel("closed"),
  save = kiln.net.channel("save"),
  result = kiln.net.channel("result"),
}

local GRAY, DIM, WHITE, RED, GOLD = 0xFF9AA0A6, 0xFF6B7078, 0xFFFFFFFF, 0xFFE86060, 0xFFFFC94D
local HEADER_H, PAD = 30, 6
local FIELDS = { "nickname", "nickColor", "status", "chatFilter", "volume", "notifications", "privateMessages", "compactChat" }

-- ---- model ---------------------------------------------------------------------------------------------------

local M = {
  name = "", saved = nil, defaults = nil, colors = {}, colorOptions = {}, colorById = {}, filters = {},
  nickMin = 3, nickMax = 16, statusMax = 40, volumeStep = 5,
}
local V = { edit = nil, pending = false, fields = nil, ask = false, closeAfterSave = false }
local screenOpen, current = false, nil
local nextReq, pending = 0, {}

local function copy(t)
  local c = {}
  for _, k in ipairs(FIELDS) do c[k] = t[k] end
  return c
end

local function applyState(m)
  M.name = m.name or M.name
  M.saved, M.defaults = m.settings, m.defaults
  M.colors, M.colorOptions, M.colorById = m.colors or {}, {}, {}
  for i, c in ipairs(M.colors) do
    M.colorOptions[i] = { value = c.id, label = c.label }
    M.colorById[c.id] = c
  end
  M.filters = {}
  for i, f in ipairs(m.filters or {}) do M.filters[i] = { value = f.id, label = f.label } end
  M.nickMin, M.nickMax, M.statusMax, M.volumeStep = m.nickMin or 3, m.nickMax or 16, m.statusMax or 40, m.volumeStep or 5
end

local function dirty()
  local e, s = V.edit, M.saved
  if not (e and s) then return false end
  for _, k in ipairs(FIELDS) do if e[k] ~= s[k] then return true end end
  return false
end

local function trim(s) return (s:gsub("^%s+", ""):gsub("%s+$", "")) end

local function utf8len(s) return #s:gsub("[\128-\191]", "") end

--- Client-side copy of the server rules (the server checks again). Returns field -> message.
local function validate(e)
  local errors = {}
  local nick = trim(e.nickname)
  local n = utf8len(nick)
  if n > 0 and n < M.nickMin then errors.nickname = "Mindestens " .. M.nickMin .. " Zeichen (oder leer lassen)."
  elseif n > M.nickMax then errors.nickname = "Höchstens " .. M.nickMax .. " Zeichen."
  elseif nick:find("[^%w_]") then errors.nickname = "Nur A–Z, a–z, 0–9 und _." end
  local status = trim(e.status)
  if utf8len(status) > M.statusMax then errors.status = "Höchstens " .. M.statusMax .. " Zeichen."
  elseif status:find("\194\167", 1, true) then errors.status = "Keine Formatzeichen (§)." end
  return errors
end

local function save()
  if V.pending or not V.edit then return end
  if next(validate(V.edit)) then return end
  nextReq = nextReq + 1
  if not ch.save:send({ req = nextReq, settings = copy(V.edit) }) then return end
  pending[nextReq] = true
  V.pending, V.fields = true, nil
end

-- ---- form ----------------------------------------------------------------------------------------------------

local LABEL_W = 92

local function errorText(ui, g, field, errors, x, y, w)
  local msg = (V.fields and V.fields[field]) or errors[field]
  if msg then g:text(ui:ellipsize(msg, w), x, y, { color = RED, shadow = false }) return 10 end
  return 0
end

--- Draws the form at (x, y) with width w; returns its height (also used as scroll content height).
local function form(ui, g, x, y, w, errors)
  local e = V.edit
  local fx, fw = x + LABEL_W, w - LABEL_W
  local cy = y
  cy = cy + ui:section("Profil", x, cy, w)
  g:text("Nickname", x, cy + 6, { color = GRAY })
  ui:textInput("nickname", {
    x = fx, y = cy, w = fw, h = 18, value = e.nickname, maxLength = 24, placeholder = M.name .. " (Spielername)",
    onChange = function(f, v) e.nickname = v; if V.fields then V.fields.nickname = nil end end,
  })
  cy = cy + 20
  cy = cy + errorText(ui, g, "nickname", errors, fx, cy, fw) + 4
  g:text("Farbe", x, cy + 6, { color = GRAY })
  local c = M.colorById[e.nickColor]
  g:rect(fx, cy + 2, 14, 14, c and c.color or 0xFFFFFFFF)
  g:outline(fx, cy + 2, 14, 14, 0xFF1A1C20)
  local color = ui:dropdown("nickColor", fx + 18, cy, fw - 18, M.colorOptions, e.nickColor, { h = 18, tooltip = "Farbe deines Namens in Chat und Tabliste" })
  e.nickColor = color
  cy = cy + 22
  g:text("Status", x, cy + 6, { color = GRAY })
  ui:textInput("status", {
    x = fx, y = cy, w = fw, h = 18, value = e.status, maxLength = 64, placeholder = "z. B. „Baue gerade eine Burg“",
    onChange = function(f, v) e.status = v; if V.fields then V.fields.status = nil end end,
  })
  cy = cy + 20
  local len = utf8len(trim(e.status))
  g:text(len .. "/" .. M.statusMax, fx + fw, cy, { align = "right", color = len > M.statusMax and RED or DIM, shadow = false })
  cy = cy + max(10, errorText(ui, g, "status", errors, fx, cy, fw - 40)) + 6

  cy = cy + ui:section("Chat", x, cy, w)
  g:text("Chat-Filter", x, cy + 6, { color = GRAY })
  e.chatFilter = ui:dropdown("chatFilter", fx, cy, fw, M.filters, e.chatFilter, { h = 18 })
  cy = cy + 24
  e.compactChat = ui:toggle("compactChat", fx, cy, e.compactChat, { label = "Kompakter Chat" })
  cy = cy + 22

  cy = cy + ui:section("Benachrichtigungen", x, cy, w)
  e.notifications = ui:toggle("notifications", fx, cy, e.notifications, { label = "Join-/Quit-Meldungen" })
  cy = cy + 20
  e.privateMessages = ui:toggle("privateMessages", fx, cy, e.privateMessages, { label = "Privatnachrichten erlauben" })
  cy = cy + 20
  g:text("Lautstärke", x, cy + 6, { color = GRAY })
  e.volume = ui:slider("volume", fx, cy, fw, e.volume, 0, 100, { step = M.volumeStep, h = 18, text = e.volume .. " %" })
  cy = cy + 22
  return cy - y
end

local SAMPLE = { OFF = "So ein Mist, schau mal auf www.beispiel.de", MILD = "So ein ****, schau mal auf www.beispiel.de", STRICT = "So ein ****, schau mal auf [Link entfernt]" }

local function preview(ui, g, x, y, w, h)
  ui:panel(x, y, w, h)
  local e = V.edit
  local cx, cw = x + 8, w - 16
  local cy = y + 8
  cy = cy + ui:section("Vorschau", cx, cy, cw)
  local c = M.colorById[e.nickColor]
  local nick = trim(e.nickname)
  if nick == "" then nick = M.name end
  local spacing = e.compactChat and 9 or 12
  g:rect(cx, cy, cw, spacing * 3 + 8, 0x80000000)
  local ly = cy + 4
  local nw = g:text("<" .. nick .. "> ", cx + 4, ly, { color = c and c.color or WHITE })
  g:text(ui:ellipsize("Hallo zusammen!", cw - nw - 8), cx + 4 + nw, ly, { color = WHITE })
  ly = ly + spacing
  g:text(ui:ellipsize("<Greta> " .. SAMPLE[e.chatFilter or "OFF"], cw - 8), cx + 4, ly, { color = 0xFFDDDDDD })
  ly = ly + spacing
  if e.notifications then g:text(ui:ellipsize("Hans hat den Server betreten", cw - 8), cx + 4, ly, { color = 0xFFFFFF55 }) end
  cy = cy + spacing * 3 + 14
  -- tab list entry
  g:text("Tabliste", cx, cy, { color = GRAY })
  cy = cy + 11
  g:rect(cx, cy, cw, 12, 0x60000000)
  local tw = g:text(nick, cx + 3, cy + 2, { color = c and c.color or WHITE })
  local status = trim(e.status)
  if status ~= "" then g:text(ui:ellipsize("– " .. status, cw - tw - 10), cx + tw + 7, cy + 2, { color = GRAY }) end
  cy = cy + 18
  g:text("Privatnachrichten: " .. (e.privateMessages and "erlaubt" or "blockiert"), cx, cy, { color = e.privateMessages and 0xFF62D46F or RED })
  cy = cy + 12
  ui:progressBar(cx, cy, cw, 8, e.volume / 100, { color = 0xFF66AAFF, tooltip = "Lautstärke " .. e.volume .. " %" })
end

local function askDialog(ui, g)
  if not V.ask then return end
  local mx, my, mw, mh, dismissed = ui:modal("ask", 300, 104, { title = "Ungespeicherte Änderungen", dismiss = not V.pending })
  if dismissed then V.ask = false return end
  g:text("Deine Änderungen sind noch nicht gespeichert.", mx + floor(mw / 2), my + 2, { align = "center" })
  g:text("Was möchtest du tun?", mx + floor(mw / 2), my + 14, { align = "center", color = GRAY })
  local bw = floor((mw - 8) / 3)
  local by = my + mh - 20
  local invalid = next(validate(V.edit)) ~= nil
  if ui:button("ask_save", mx, by, bw, 20, V.pending and "…" or "Speichern", { style = "primary", disabled = V.pending or invalid, disabledTooltip = "Bitte zuerst die Fehler beheben." }) then
    V.closeAfterSave = true
    save()
  end
  if ui:button("ask_discard", mx + bw + 4, by, bw, 20, "Verwerfen", { style = "danger", disabled = V.pending }) then
    V.edit = copy(M.saved)
    V.ask = false
    kiln.ui.close()
  end
  if ui:button("ask_back", mx + 2 * (bw + 4), by, bw, 20, "Weiter", { disabled = V.pending, tooltip = "Weiter bearbeiten" }) then V.ask = false end
end

-- ---- screen --------------------------------------------------------------------------------------------------

kiln.ui.screen("main", kit.screen({
  fullscreen = true,
  init = function(self)
    current = self
    screenOpen = true
    V.edit, V.pending, V.fields, V.ask, V.closeAfterSave = M.saved and copy(M.saved), false, nil, false, false
  end,
  render = function(self, g, s, ui)
    g:gradient(0, 0, s.width, s.height, 0xE00C0E11, 0xF0101216)
    g:gradient(0, 0, s.width, HEADER_H, 0xF0262A31, 0xF01A1D22)
    g:rect(0, HEADER_H - 1, s.width, 1, 0xFF3A3F47)
    ui:heading("Einstellungen & Profil", PAD, 11, { maxWidth = s.width - 2 * PAD - 120 })
    if not V.edit then return end
    local isDirty = dirty()
    if isDirty then ui:badge("Ungespeichert", s.width - PAD, 10, { color = GOLD, align = "right" }) end
    local errors = validate(V.edit)

    local top, bottom = HEADER_H + PAD, s.height - PAD - 24
    local wide = s.width >= 520
    local formW = wide and min(330, s.width - 3 * PAD - 180) or s.width - 2 * PAD
    local previewW = s.width - 3 * PAD - formW
    local viewH = bottom - top - 4
    local st = ui:stateOf("form")
    ui:panel(PAD, top, formW, viewH)
    ui:scrollPanel("form", PAD + 1, top + 1, formW - 2, viewH - 2, st.contentH or viewH, function(cx, cy, cw)
      local h = form(ui, g, cx + 6, cy + 6, cw - 12, errors) + 12
      if not wide then
        preview(ui, g, cx + 6, cy + h, cw - 12, 116)
        h = h + 122
      end
      st.contentH = h
    end)
    if wide then preview(ui, g, PAD * 2 + formW, top, previewW, min(viewH, 140)) end

    -- footer
    local by = s.height - PAD - 20
    local invalid = next(errors) ~= nil
    if ui:button("save", PAD, by, 100, 20, V.pending and "…" or "Speichern", {
      style = "primary", disabled = V.pending or not isDirty or invalid,
      disabledTooltip = invalid and "Bitte zuerst die markierten Felder korrigieren." or (not isDirty and "Keine Änderungen.") or nil,
    }) then save() end
    if ui:button("revert", PAD + 104, by, 100, 20, "Zurücksetzen", { disabled = V.pending or not isDirty, tooltip = "Letzten gespeicherten Stand laden" }) then
      V.edit, V.fields = copy(M.saved), nil
    end
    if ui:button("defaults", PAD + 208, by, 100, 20, "Standard", { disabled = V.pending, tooltip = "Standardwerte eintragen (noch nicht gespeichert)" }) then
      V.edit, V.fields = copy(M.defaults), nil
    end
    askDialog(ui, g)
  end,
  key = function(self, s, key)
    if key == "escape" then
      if V.ask then if not V.pending then V.ask = false end return true end
      if dirty() then V.ask = true return true end
      return false
    end
    if key == "s" and s.ctrl then save() return true end
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

ch.state:on(function(m)
  local wasDirty = dirty()
  applyState(m)
  -- saved elsewhere: an unchanged form follows the new saved state
  if V.edit and not wasDirty then V.edit = copy(M.saved) end
end)

ch.result:on(function(m)
  if not pending[m.req] then return end
  pending[m.req] = nil
  V.pending = false
  if m.ok then
    if V.edit then M.saved = copy(V.edit) end
    V.fields = nil
    if V.closeAfterSave then
      V.closeAfterSave, V.ask = false, false
      kiln.ui.close()
      return
    end
  else
    V.fields = m.fields
    V.closeAfterSave = false
  end
  if current then
    current.ui:toast(m.ok and (m.message or "Gespeichert.") or (m.error or "Fehler"), m.ok and "success" or "error")
    kiln.ui.sound(m.ok and "success" or "error")
  end
end)

kiln.keys.bind("open", { name = "Einstellungen öffnen", default = "M", press = function() ch.open:send({}) end })

-- for tests and debugging
profil_debug = { M = M, V = V, validate = validate, dirty = dirty }

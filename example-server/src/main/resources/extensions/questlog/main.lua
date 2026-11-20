-- questlog: Quest-Log on top of ui_kit. The server owns the quests and the progress (real block/kill events) and
-- gives the rewards; this script shows tabs, list, details and asks before accepting or abandoning.
-- Channels (questlog:<name>): open, state, closed, accept / abandon (C->S {req, quest}), result (S->C).
local kit = kiln.import("ui_kit")

local floor, max, min = math.floor, math.max, math.min

local ch = {
  open = kiln.net.channel("open"),
  state = kiln.net.channel("state"),
  closed = kiln.net.channel("closed"),
  accept = kiln.net.channel("accept"),
  abandon = kiln.net.channel("abandon"),
  result = kiln.net.channel("result"),
}

local GRAY, DIM, WHITE, GREEN, GOLD = 0xFF9AA0A6, 0xFF6B7078, 0xFFFFFFFF, 0xFF62D46F, 0xFFFFC94D
local HEADER_H, PAD = 30, 6
local TABS = { "ACTIVE", "COMPLETED", "AVAILABLE" }
local TAB_LABEL = { ACTIVE = "Aktiv", COMPLETED = "Abgeschlossen", AVAILABLE = "Verfügbar" }
local STATUS_COLOR = { ACTIVE = 0xFF66AAFF, COMPLETED = GREEN, AVAILABLE = GOLD }
local EMPTY = {
  ACTIVE = "Keine aktiven Quests. Unter „Verfügbar“ findest du neue Aufgaben.",
  COMPLETED = "Noch keine Quest abgeschlossen.",
  AVAILABLE = "Keine weiteren Quests verfügbar – gut gemacht!",
}

-- ---- model ---------------------------------------------------------------------------------------------------

local M = { maxActive = 3, quests = {}, byId = {}, lists = { ACTIVE = {}, COMPLETED = {}, AVAILABLE = {} }, rev = 0 }
local V = { tab = 1, sel = {}, confirm = nil } -- sel per tab; confirm = { action, id, pending }
local screenOpen, current = false, nil
local nextReq, pending = 0, {}

local function applyState(m)
  M.maxActive = m.maxActive or 3
  local byId, lists = {}, { ACTIVE = {}, COMPLETED = {}, AVAILABLE = {} }
  for _, q in ipairs(m.quests or {}) do
    local have, need = 0, 0
    for _, o in ipairs(q.objectives) do have, need = have + o.have, need + o.need end
    q.done = need > 0 and have / need or 0
    q.sub = have .. "/" .. need
    for _, r in ipairs(q.rewards) do
      r.handle = r.item and kiln.items.decode(r.item) or nil
      r.item = nil
    end
    byId[q.id] = q
    local list = lists[q.status]
    if list then list[#list + 1] = q end
  end
  M.quests, M.byId, M.lists = m.quests or {}, byId, lists
  M.rev = M.rev + 1
end

local function request(action, questId)
  nextReq = nextReq + 1
  if not ch[action]:send({ req = nextReq, quest = questId }) then return nil end
  pending[nextReq] = action
  return nextReq
end

-- ---- drawing -------------------------------------------------------------------------------------------------

local function questRow(q, x, y, w, h, selected)
  local g, ui = current.g, current.ui
  g:text(ui:ellipsize(q.title, w - 50), x + 5, y + 4, { color = selected and WHITE or 0xFFE0E0E0 })
  g:text(q.category, x + w - 5, y + 4, { align = "right", color = DIM, shadow = false })
  if q.status == "ACTIVE" then
    ui:progressBar(x + 5, y + h - 9, w - 10, 5, q.done, { color = STATUS_COLOR.ACTIVE, border = false })
  elseif q.status == "COMPLETED" then
    g:text("Abgeschlossen", x + 5, y + h - 12, { color = GREEN, shadow = false })
  else
    g:text(#q.objectives .. (#q.objectives == 1 and " Ziel · " or " Ziele · ") .. q.xp .. " XP", x + 5, y + h - 12, { color = GRAY, shadow = false })
  end
end

local function activeCount() return #M.lists.ACTIVE end

local function detail(ui, g, q, x, y, w, h)
  ui:panel(x, y, w, h)
  V.detailX = x
  local cx, cw = x + 8, w - 16
  local cy = y + 8
  ui:heading(q.title, cx, cy, { maxWidth = cw - 90 })
  ui:badge(TAB_LABEL[q.status], cx + cw, cy - 1, { color = STATUS_COLOR[q.status], align = "right" })
  cy = cy + 12
  g:text(q.category, cx, cy, { color = DIM })
  cy = cy + 14
  cy = cy + ui:paragraph(q.description, cx, cy, cw) + 6
  cy = cy + ui:section("Ziele", cx, cy, cw)
  for _, o in ipairs(q.objectives) do
    local done = o.have >= o.need
    g:text(ui:ellipsize(o.label, cw - 70), cx, cy + 1, { color = done and GREEN or 0xFFE0E0E0 })
    ui:progressBar(cx + cw - 64, cy, 64, 10, o.need > 0 and o.have / o.need or 0, {
      color = done and GREEN or STATUS_COLOR.ACTIVE, text = o.have .. " / " .. o.need,
    })
    cy = cy + 14
  end
  cy = cy + 4
  cy = cy + ui:section("Belohnungen", cx, cy, cw)
  local sx = cx
  V.rewardsAt = cy -- for tests: y of the reward slots (x = detail x + 8)
  for i, r in ipairs(q.rewards) do
    ui:itemSlot("reward", sx, cy, r.handle or r.key, r.count, {
      data = i, clickable = false, tooltip = { "Belohnung" .. (r.count > 1 and (" ×" .. r.count) or ""), "Gibt es beim Abschluss der Quest." },
    })
    sx = sx + 20
  end
  g:text("+" .. q.xp .. " XP", sx + 4, cy + 5, { color = GREEN })
  -- actions
  local by = y + h - 28
  if q.status == "AVAILABLE" then
    local full = activeCount() >= M.maxActive
    if ui:button("accept", cx, by, 110, 20, "Annehmen", {
      style = "primary", disabled = full,
      disabledTooltip = "Höchstens " .. M.maxActive .. " aktive Quests – brich zuerst eine ab.",
    }) then
      V.confirm = { action = "accept", id = q.id }
    end
  elseif q.status == "ACTIVE" then
    if ui:button("abandon", cx, by, 110, 20, "Abbrechen", { style = "danger", tooltip = "Der Fortschritt geht verloren" }) then
      V.confirm = { action = "abandon", id = q.id }
    end
    g:text(floor(q.done * 100) .. " % erledigt", cx + 118, by + 6, { color = GRAY })
  else
    g:text("Belohnung erhalten.", cx, by + 6, { color = GREEN })
  end
end

local function confirmDialog(ui)
  local c = V.confirm
  if not c then return end
  local q = M.byId[c.id]
  if not q then V.confirm = nil return end
  local accept = c.action == "accept"
  local answer = ui:confirm("confirm", {
    title = accept and "Quest annehmen" or "Quest abbrechen",
    text = accept and ("„" .. q.title .. "“ annehmen? Du hast dann " .. (activeCount() + 1) .. " von " .. M.maxActive .. " aktiven Quests.")
      or ("„" .. q.title .. "“ wirklich abbrechen? Der bisherige Fortschritt (" .. q.sub .. ") geht verloren."),
    yes = accept and "Annehmen" or "Quest abbrechen", no = "Zurück", danger = not accept, pending = c.pending,
  })
  if answer == "yes" then
    c.pending = request(c.action, c.id) ~= nil
  elseif answer == "no" then
    V.confirm = nil
  end
end

-- ---- screen --------------------------------------------------------------------------------------------------

kiln.ui.screen("main", kit.screen({
  fullscreen = true,
  init = function(self)
    current = self
    screenOpen = true
    V.confirm = nil
  end,
  render = function(self, g, s, ui)
    self.g = g
    g:gradient(0, 0, s.width, s.height, 0xE00C0E11, 0xF0101216)
    g:gradient(0, 0, s.width, HEADER_H, 0xF0262A31, 0xF01A1D22)
    g:rect(0, HEADER_H - 1, s.width, 1, 0xFF3A3F47)
    local x = PAD
    if s.width >= 420 then x = PAD + ui:heading("Quest-Log", PAD, 11) + 12 end
    local tabs = {}
    for i, key in ipairs(TABS) do tabs[i] = TAB_LABEL[key] .. " (" .. #M.lists[key] .. ")" end
    local tab, changed = ui:tabs("tabs", x, 6, tabs, V.tab)
    if changed then V.tab = tab end
    g:text(activeCount() .. "/" .. M.maxActive .. " aktiv", s.width - PAD, 11, { align = "right", color = GRAY })

    local key = TABS[V.tab]
    local list = M.lists[key]
    local top = HEADER_H + PAD
    local height = s.height - top - PAD
    local listW = kit.clamp(floor(s.width * 0.36), 140, 220)
    ui:panel(PAD, top, listW, height)
    local sel = V.sel[key]
    if not (sel and M.byId[sel] and M.byId[sel].status == key) then sel = list[1] and list[1].id end
    local picked = ui:list("quests", PAD + 2, top + 2, listW - 4, height - 4, list, sel, { rowH = 26, render = questRow, empty = EMPTY[key] })
    V.sel[key] = picked
    local q = picked and M.byId[picked]
    local dx = PAD + listW + PAD
    if q then
      detail(ui, g, q, dx, top, s.width - dx - PAD, height)
    else
      ui:panel(dx, top, s.width - dx - PAD, height)
    end
    confirmDialog(ui)
  end,
  key = function(self, s, key)
    if key == "escape" and V.confirm then
      if not V.confirm.pending then V.confirm = nil end
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
  local c = V.confirm
  if c and c.action == action then V.confirm = nil end
  if m.ok then
    -- follow the quest to its new tab
    local target = action == "accept" and 1 or 3
    if c then V.sel[TABS[target]] = c.id end
    V.tab = target
  end
  if current then
    current.ui:toast(m.ok and (m.message or "OK") or (m.error or "Fehler"), m.ok and "success" or "error")
    kiln.ui.sound(m.ok and "success" or "error")
  end
end)

kiln.keys.bind("open", { name = "Quest-Log öffnen", default = "U", press = function() ch.open:send({}) end })

-- for tests and debugging
questlog_debug = { M = M, V = V }

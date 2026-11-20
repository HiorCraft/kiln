-- fabrik: Fabrik-Steuerzentrale on top of ui_kit. The server simulates the machines (power by priority, progress)
-- and sends the state once per second while the screen is open; this script shows it and sends changes.
-- Channels (fabrik:<name>): open (C->S request, S->C state + open), state (S->C live), closed (C->S),
-- set (C->S {req, machine, running? | priority? | recipe?}), result (S->C answer).
local kit = kiln.import("ui_kit")

local floor, max, min = math.floor, math.max, math.min

local ch = {
  open = kiln.net.channel("open"),
  state = kiln.net.channel("state"),
  closed = kiln.net.channel("closed"),
  set = kiln.net.channel("set"),
  result = kiln.net.channel("result"),
}

local GRAY, DIM, WHITE = 0xFF9AA0A6, 0xFF6B7078, 0xFFFFFFFF
local HEADER_H, PAD = 30, 6

local STATUS_COLOR = {
  RUNNING = 0xFF62D46F, LOW_POWER = 0xFFFFC94D, NO_POWER = 0xFFE86060, OFF = 0xFF8A9099,
}

-- ---- model ---------------------------------------------------------------------------------------------------

local M = { supply = 0, used = 0, maxPriority = 10, types = {}, machines = {}, byId = {}, rev = 0 }
local V = { sel = nil, prio = nil } -- prio = { id, value } while the slider is dragged
local screenOpen, current = false, nil
local nextReq, pending = 0, {}

local function applyState(m)
  M.supply, M.used, M.maxPriority = m.supply or 0, m.used or 0, m.maxPriority or 10
  if m.types then
    local types = {}
    for _, t in ipairs(m.types) do
      local options = {}
      for i, r in ipairs(t.recipes) do
        options[i] = { value = r.id, label = r.label .. " (" .. r.seconds .. " s, " .. r.power .. " E)" }
        t.recipes[r.id] = r
      end
      t.options = options
      types[t.id] = t
    end
    M.types = types
  end
  local byId = {}
  for _, machine in ipairs(m.machines or {}) do byId[machine.id] = machine end
  M.machines, M.byId = m.machines or {}, byId
  if not (V.sel and byId[V.sel]) then V.sel = M.machines[1] and M.machines[1].id end
  M.rev = M.rev + 1
end

local function request(fields)
  nextReq = nextReq + 1
  fields.req = nextReq
  if not ch.set:send(fields) then return nil end
  pending[nextReq] = fields
  return nextReq
end

local function toast(text, kind)
  if current then current.ui:toast(text, kind) end
end

-- ---- drawing -------------------------------------------------------------------------------------------------

local function machineRow(machine, x, y, w, h, selected, hovered)
  local g = current.g
  local t = M.types[machine.type]
  if t then g:item(t.icon, x + 4, y + floor((h - 16) / 2)) end
  g:text(current.ui:ellipsize(machine.name, w - 60), x + 24, y + 5, { color = selected and WHITE or 0xFFE0E0E0 })
  local color = STATUS_COLOR[machine.status] or GRAY
  g:rect(x + w - 10, y + 6, 5, 5, color)
  local bw = w - 30
  g:rect(x + 24, y + h - 9, bw, 3, 0xFF15171A)
  g:rect(x + 24, y + h - 9, floor(bw * (machine.progress or 0)), 3, color)
  g:text(machine.statusLabel or "", x + w - 14, y + 5, { align = "right", color = DIM, shadow = false })
end

local function header(ui, g, s)
  g:gradient(0, 0, s.width, HEADER_H, 0xF0262A31, 0xF01A1D22)
  g:rect(0, HEADER_H - 1, s.width, 1, 0xFF3A3F47)
  ui:heading("Fabrik-Steuerzentrale", PAD, 11)
  local bw = min(160, max(80, s.width - 200))
  local bx = s.width - PAD - bw
  local load = M.supply > 0 and M.used / M.supply or 0
  local color = load >= 0.999 and 0xFFE86060 or (load > 0.8 and 0xFFFFC94D or 0xFF62D46F)
  ui:progressBar(bx, 9, bw, 12, load, {
    color = color, text = "Netz " .. M.used .. " / " .. M.supply .. " E",
    tooltip = { "Stromnetz der Fabrik", "Maschinen mit höherer Priorität", "bekommen zuerst Strom." },
  })
end

local function detail(ui, g, s, machine, x, y, w, h)
  ui:panel(x, y, w, h)
  local t = M.types[machine.type]
  local cx, cw = x + 8, w - 16
  local cy = y + 8
  g:sprite("container/slot", cx, cy, 26, 26)
  if t then g:item(t.icon, cx + 1, cy + 1, 24) end
  g:text(ui:ellipsize(machine.name, cw - 110), cx + 32, cy + 3, { color = WHITE, scale = 1 })
  g:text(t and t.label or machine.type, cx + 32, cy + 15, { color = GRAY })
  ui:badge(machine.statusLabel or machine.status, cx + cw, cy + 3, { color = STATUS_COLOR[machine.status], align = "right" })
  cy = cy + 34

  -- operation
  cy = cy + ui:section("Betrieb", cx, cy, cw)
  local on, changed = ui:toggle("power", cx, cy, machine.running, { label = machine.running and "Eingeschaltet" or "Ausgeschaltet" })
  if changed then
    machine.running = on
    request({ machine = machine.id, running = on })
  end
  cy = cy + 20
  local power = machine.power or 0
  ui:progressBar(cx, cy, cw, 12, power, {
    color = STATUS_COLOR[machine.status], text = "Strom " .. floor(power * 100 + 0.5) .. " % von " .. machine.demand .. " E",
  })
  cy = cy + 16
  local recipe = t and t.recipes[machine.recipe]
  local label = recipe and recipe.label or machine.recipe
  ui:progressBar(cx, cy, cw, 12, machine.progress or 0, {
    color = 0xFF66AAFF, text = label .. " " .. floor((machine.progress or 0) * 100) .. " %",
  })
  cy = cy + 18
  if recipe then
    g:item(recipe.input, cx, cy)
    g:text("→", cx + 22, cy + 4, { color = GRAY })
    g:item(recipe.output, cx + 34, cy)
    g:text("Hergestellt: " .. kit.formatNumber(machine.produced or 0), cx + 56, cy + 4, { color = GRAY })
    if ui:hovered(cx, cy, 52, 16) then ui:tooltip({ recipe.label, recipe.seconds .. " s pro Stück", recipe.power .. " E Strombedarf" }) end
  end
  cy = cy + 22

  -- settings
  cy = cy + ui:section("Einstellungen", cx, cy, cw)
  local editing = V.prio and V.prio.id == machine.id
  local prio = editing and V.prio.value or machine.priority
  g:text("Priorität", cx, cy + 6, { color = GRAY })
  local value, prioChanged, committed = ui:slider("priority", cx + 56, cy, cw - 56, prio, 1, M.maxPriority, {
    text = "Priorität " .. prio, tooltip = "Höhere Priorität bekommt zuerst Strom",
  })
  if prioChanged then V.prio = { id = machine.id, value = value } end
  if committed then
    local v = V.prio and V.prio.id == machine.id and V.prio.value or value
    V.prio = nil
    if v ~= machine.priority then
      machine.priority = v
      request({ machine = machine.id, priority = v })
    end
  end
  cy = cy + 24
  g:text("Rezept", cx, cy + 6, { color = GRAY })
  if t then
    local picked, recipeChanged = ui:dropdown("recipe", cx + 56, cy, cw - 56, t.options, machine.recipe, { tooltip = "Was die Maschine herstellt" })
    if recipeChanged then
      machine.recipe = picked
      machine.progress = 0
      request({ machine = machine.id, recipe = picked })
    end
  end
end

-- ---- screen --------------------------------------------------------------------------------------------------

kiln.ui.screen("main", kit.screen({
  fullscreen = true,
  init = function(self, s)
    current = self
    screenOpen = true
  end,
  render = function(self, g, s, ui)
    self.g = g
    g:gradient(0, 0, s.width, s.height, 0xE00C0E11, 0xF0101216)
    header(ui, g, s)
    local top = HEADER_H + PAD
    local height = s.height - top - PAD
    local listW = kit.clamp(floor(s.width * 0.38), 140, 220)
    ui:panel(PAD, top, listW, height)
    g:text("Maschinen (" .. #M.machines .. ")", PAD + 5, top + 5, { color = GRAY })
    local sel, changed = ui:list("machines", PAD + 2, top + 17, listW - 4, height - 19, M.machines, V.sel, {
      rowH = 28, render = machineRow, empty = "Keine Maschinen.",
    })
    if changed then V.sel, V.prio = sel, nil end
    local machine = M.byId[V.sel]
    local dx = PAD + listW + PAD
    if machine then
      detail(ui, g, s, machine, dx, top, s.width - dx - PAD, height)
    else
      ui:panel(dx, top, s.width - dx - PAD, height)
    end
  end,
  key = function(self, s, key)
    if key == "up" or key == "down" then
      for i, m in ipairs(M.machines) do
        if m.id == V.sel then
          local n = M.machines[i + (key == "up" and -1 or 1)]
          if n then V.sel, V.prio = n.id, nil end
          return true
        end
      end
    end
    return false
  end,
  close = function(self)
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
  local req = pending[m.req]
  pending[m.req] = nil
  if not req then return end
  if not m.ok then
    toast(m.error or "Fehler", "error")
    if screenOpen then kiln.ui.sound("error") end
  end
end)

kiln.keys.bind("open", { name = "Fabrik-Steuerzentrale öffnen", default = "B", press = function() ch.open:send({}) end })

-- for tests and debugging
fabrik_debug = { M = M, V = V, applyState = applyState }

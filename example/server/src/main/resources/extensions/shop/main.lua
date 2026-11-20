-- shop: fullscreen player market as a Lua screen on top of ui_kit. The server owns money, items and offers and
-- validates every request; this script only shows the state it receives and sends requests.
-- Channels (shop:<name>): open (S->C open with state / C->S request to open), state (S->C live updates),
-- result (S->C answer to a request), buy / sell / withdraw / reprice (C->S requests), closed (C->S screen closed).
local kit = kiln.import("ui_kit")
local F = kiln.require("filter")

local floor, max, min = math.floor, math.max, math.min
local clock = kiln.clock

local ch = {
  open = kiln.net.channel("open"),
  state = kiln.net.channel("state"),
  result = kiln.net.channel("result"),
  buy = kiln.net.channel("buy"),
  sell = kiln.net.channel("sell"),
  withdraw = kiln.net.channel("withdraw"),
  reprice = kiln.net.channel("reprice"),
  closed = kiln.net.channel("closed"),
}

local GOLD, GRAY, DARK, RED, YELLOW, GREEN, WHITE = 0xFFFFC94D, 0xFF9AA0A6, 0xFF6B7078, 0xFFE86060, 0xFFFFE070, 0xFF62D46F, 0xFFFFFFFF
local HEADER_H, PAD, SIDEBAR_W = 30, 6, 112

-- ---- model (server state) ------------------------------------------------------------------------------------

local M = {
  balance = 0, startBalance = 0, maxPrice = 1000000,
  offers = {}, byId = {}, mine = {}, mineById = {}, suggest = {},
  categories = {}, listable = {}, durations = {}, catLabel = {},
  received = 0, rev = 0,
}

local screenOpen = false
local pendingToasts = {}

local function decode(text)
  if type(text) ~= "string" then return nil end
  local h = kiln.items.decode(text)
  return h
end

--- Applies a state message from the server (open or state channel).
local function applyState(m)
  M.balance = m.balance or 0
  M.startBalance = m.startBalance or M.startBalance
  M.maxPrice = m.maxPrice or M.maxPrice
  if m.categories then
    M.categories, M.listable, M.catLabel = m.categories, {}, {}
    for _, c in ipairs(m.categories) do
      M.catLabel[c.id] = c.label
      if c.id ~= "ALL" then M.listable[#M.listable + 1] = { value = c.id, label = c.label } end
    end
  end
  if m.durations then
    M.durations = {}
    for _, d in ipairs(m.durations) do M.durations[#M.durations + 1] = { value = d.id, label = d.label } end
  end
  M.suggest = m.suggest or {}
  M.received = clock()
  local offers, byId = m.offers or {}, {}
  for _, o in ipairs(offers) do
    o.handle = decode(o.item)
    o.item = nil
    o.hay = F.haystack(o)
    o.lname = F.sortKey(o.name)
    o.priceText = F.money(o.price) .. "/Stk."
    o.availText = "Verfügbar: " .. kit.formatNumber(o.amount) .. " · " .. (o.own and "Dein Angebot" or o.seller)
    byId[o.id] = o
  end
  M.offers, M.byId = offers, byId
  local mine, mineById = m.mine or {}, {}
  for _, o in ipairs(mine) do
    o.handle = decode(o.item)
    o.item = nil
    mineById[o.id] = o
  end
  M.mine, M.mineById = mine, mineById
  M.rev = M.rev + 1
end

--- Seconds until an offer expires (nil = never), counted from when the state arrived.
local function secondsLeft(o)
  if o.expiresIn == nil then return nil end
  return o.expiresIn / 1000 - (clock() - M.received)
end

-- ---- view state (kept while the client is connected, modals reset on open) -------------------------------------

local V = {
  tab = 1, search = "", category = "ALL", sort = "NEWEST",
  buy = nil,            -- { id, amount, text, priceSeen, confirm, error, pending, shulker, gone, priceChanged }
  sell = { slot = nil, id = nil, name = nil, amount = "", price = "", duration = "H24", category = nil, notify = true,
           submitted = false, confirm = false, error = nil, fields = nil, pending = false },
  priceEdit = nil,      -- { id, text, error, pending }
  withdraw = nil,       -- { id, pending }
  cache = { rev = -1 },
  inv = {}, invBySlot = {}, invAt = -1,
}

local nextReq = 0
local pending = {}     -- req -> action

local function request(channel, action, msg)
  nextReq = nextReq + 1
  msg.req = nextReq
  if not ch[channel]:send(msg) then return nil end
  pending[nextReq] = action
  return nextReq
end

local function resetModals()
  V.buy, V.priceEdit, V.withdraw = nil, nil, nil
  V.sell.confirm, V.sell.pending = false, false
end

local function toast(ui, text, kind)
  if ui then ui:toast(text, kind) elseif kiln.ui.toast then kiln.ui.toast("Markt", text) end
end

-- ---- filtering (client-side, cached per state revision / query) ----------------------------------------------

local function marketView()
  local c = V.cache
  if c.rev ~= M.rev or c.search ~= V.search or c.category ~= V.category or c.sort ~= V.sort then
    c.rev, c.search, c.category, c.sort = M.rev, V.search, V.category, V.sort
    c.list = F.query(M.offers, V.category, V.search, V.sort)
    c.counts = F.counts(M.offers, M.categories, V.search)
  end
  return c.list, c.counts
end

-- ---- header ----------------------------------------------------------------------------------------------------

local TAB_KEYS = { "market", "sell", "mine" }

local function header(ui, g, s)
  g:gradient(0, 0, s.width, HEADER_H, 0xF0262A31, 0xF01A1D22)
  g:rect(0, HEADER_H - 1, s.width, 1, 0xFF3A3F47)
  local x = PAD
  if s.width >= 470 then
    ui:heading("Marktplatz", PAD, 11)
    x = 86
  end
  local tabs = {
    { label = "Markt", tooltip = "Angebote aller Spieler durchsuchen und kaufen" },
    { label = "Verkaufen", tooltip = "Items aus deinem Inventar anbieten" },
    { label = "Meine Angebote" .. (#M.mine > 0 and (" (" .. #M.mine .. ")") or ""), tooltip = "Eigene Angebote verwalten" },
  }
  local tab, changed, used = ui:tabs("tabs", x, 6, tabs, V.tab, { h = 20 })
  if changed then V.tab = tab end
  local bx = x + used + 6
  local text = ui:ellipsize(F.money(M.balance), s.width - bx - PAD)
  local w = g:textWidth(text)
  g:text(text, s.width - PAD, 11, { align = "right", color = GOLD })
  if ui:hovered(s.width - PAD - w, 8, w, 14) then
    ui:tooltip("Dein Kontostand (Demo-Geld, Start " .. F.money(M.startBalance) .. ")")
  end
end

-- ---- market ----------------------------------------------------------------------------------------------------

local function openBuy(o)
  V.buy = { id = o.id, amount = 1, text = "1", priceSeen = o.price, confirm = false }
end

local function marketTab(ui, g, s, top, height)
  local list, counts = marketView()

  -- categories
  ui:panel(PAD, top, SIDEBAR_W, height)
  g:text("Kategorien", PAD + 5, top + 5, { color = GRAY })
  local y = top + 17
  for i, c in ipairs(M.categories) do
    local selected = V.category == c.id
    local clicked, hot = ui:card("category", PAD + 2, y, SIDEBAR_W - 4, 16, {
      data = i, selected = selected, tooltip = (counts[c.id] or 0) .. " passende Angebote",
    })
    if clicked then V.category = c.id end
    g:text(ui:ellipsize(c.label, SIDEBAR_W - 34), PAD + 7, y + 4, { color = selected and WHITE or (hot and 0xFFDDDDDD or GRAY) })
    g:text(tostring(counts[c.id] or 0), PAD + SIDEBAR_W - 8, y + 4, { align = "right", color = DARK })
    y = y + 18
  end

  -- search + sort
  local x = PAD + SIDEBAR_W + PAD
  local width = s.width - x - PAD
  local sortW = min(110, max(70, floor(width * 0.3)))
  local clearW = V.search ~= "" and 20 or 0
  local searchW = max(40, width - sortW - 4 - clearW)
  ui:textInput("search", {
    x = x, y = top, w = searchW, h = 20, value = V.search, maxLength = 64,
    placeholder = "Suchen (Name, Item, Verkäufer) …",
    onChange = function(f, v) V.search = v end,
  })
  if clearW > 0 and ui:button("search_clear", x + searchW + 2, top, 18, 20, "×", { tooltip = "Suche leeren" }) then
    V.search = ""
  end
  local sort = ui:dropdown("sort", x + width - sortW, top, sortW, F.sorts, V.sort, { tooltip = "Sortierung" })
  V.sort = sort

  g:text(#list .. (#list == 1 and " Angebot" or " Angebote") .. " · " .. (M.catLabel[V.category] or V.category), x, top + 26, { color = GRAY })

  -- tiles
  local gy = top + 38
  local gh = top + height - gy
  local grid = kit.grid(0, 0, width - 8, { minWidth = 150, h = 44, gap = 4 })
  if #list == 0 then
    ui:panel(x, gy, width, gh)
    local cx = x + floor(width / 2)
    g:text(#M.offers == 0 and "Noch keine Angebote im Markt" or "Keine Angebote gefunden", cx, gy + 24, { align = "center", color = GRAY })
    g:text("Andere Suche oder Kategorie probieren.", cx, gy + 36, { align = "center", color = DARK })
    if (V.search ~= "" or V.category ~= "ALL") and ui:button("filter_reset", cx - 60, gy + 52, 120, 20, "Filter zurücksetzen") then
      V.search, V.category = "", "ALL"
    end
    return
  end
  ui:scrollPanel("offers", x, gy, width, gh, grid:height(#list), function(cx, cy, cw)
    local first = max(1, floor((gy - cy) / (grid.cellH + grid.gap)) * grid.cols + 1)
    local last = min(#list, (floor((gy + gh - cy) / (grid.cellH + grid.gap)) + 1) * grid.cols)
    for i = first, last do
      local o = list[i]
      local tx, ty, tw, th = grid:cell(i)
      tx, ty = cx + tx, cy + ty
      local tip = nil
      if ui:isHot("offer", o.id) then
        local left = secondsLeft(o)
        tip = { o.priceText, "Verkäufer: " .. o.seller, "Verfügbar: " .. kit.formatNumber(o.amount),
                "Läuft ab: " .. F.remaining(left), o.own and "Dein eigenes Angebot" or "Klicken zum Kaufen" }
      end
      local clicked = ui:itemTile("offer", o.id, tx, ty, tw, th, {
        item = o.handle, count = o.amount, title = o.name, dim = o.own, tooltip = tip,
        lines = { { o.priceText, GOLD }, { o.availText, o.own and YELLOW or GRAY } },
      })
      if clicked then
        if o.own then ui:toast("Eigene Angebote verwaltest du unter „Meine Angebote“.", "info") else openBuy(o) end
      end
    end
  end, { step = 3 * (grid.cellH + grid.gap) / 2 })
end

local function buyModals(ui, g, s)
  local b = V.buy
  if not b then return end
  local o = M.byId[b.id]
  if not o then
    local mx, my, mw, mh, dismissed = ui:modal("buy", 260, 90, { title = "Kaufen", dismiss = true })
    g:text("Dieses Angebot ist nicht mehr verfügbar.", mx + floor(mw / 2), my + 8, { align = "center", color = RED })
    if ui:button("buy_close", mx + floor((mw - 80) / 2), my + mh - 20, 80, 20, "Schließen") or dismissed then V.buy = nil end
    return
  end
  if o.price ~= b.priceSeen and not b.priceChanged then b.priceChanged = true end
  local price = o.price
  local available = o.amount
  local amount = b.amount
  local valid = amount >= 1 and amount <= available and amount == floor(amount)
  local total = valid and amount * price or 0
  local affordable = total <= M.balance

  local mx, my, mw, mh, dismissed = ui:modal("buy", 300, 196, { title = "Kaufen", dismiss = not b.confirm and not b.pending })
  if dismissed then V.buy = nil return end
  ui:itemSlot("buy_item", mx, my, o.handle, 1, { clickable = false })
  g:text(ui:ellipsize(o.name, mw - 26), mx + 24, my, { color = WHITE })
  g:text(o.priceText .. " · " .. o.seller, mx + 24, my + 10, { color = GOLD })
  g:text("Verfügbar: " .. kit.formatNumber(available), mx, my + 24, { color = GRAY })

  local y = my + 38
  g:text("Menge", mx, y + 6, { color = GRAY })
  local fieldW = 54
  local sliderX, sliderW = mx + 40, mw - 40 - fieldW - 4
  local v, changed = ui:slider("buy_slider", sliderX, y, sliderW, valid and amount or 1, 1, max(1, available), {
    disabled = available <= 1 or b.pending, text = kit.formatNumber(valid and amount or 1) .. " Stück",
    tooltip = "Ziehen, um die Menge zu wählen (1 bis " .. kit.formatNumber(available) .. ")",
  })
  if changed then b.amount, b.text = v, tostring(v) end
  local field = ui:textInput("buy_amount", {
    x = mx + mw - fieldW, y = y, w = fieldW, h = 20, value = b.text, maxLength = 7,
    numeric = { min = 1, max = max(1, available), integer = true }, enabled = not b.pending,
    onChange = function(f, value)
      b.text = value
      b.amount = tonumber(value) or 0
    end,
  })
  local textInvalid = not field.valid or b.text == ""
  y = y + 26
  if textInvalid then
    g:text("Bitte eine Menge von 1 bis " .. kit.formatNumber(available) .. " eingeben.", mx, y, { color = RED })
  end
  y = y + 12
  g:text("Gesamtpreis: " .. F.money(total), mx, y, { color = GOLD })
  y = y + 11
  if affordable then
    g:text("Kontostand danach: " .. F.money(M.balance - total), mx, y, { color = GRAY })
  else
    g:text("Nicht genug Geld: es fehlen " .. F.money(total - M.balance), mx, y, { color = RED })
  end
  y = y + 13
  b.shulker = ui:checkbox("buy_shulker", mx, y, "In Shulker-Box liefern", b.shulker, {
    tooltip = "Demo: Die Option wird angezeigt, die Lieferung erfolgt aber lose ins Inventar.",
  })
  y = y + 20
  if b.priceChanged then
    g:text(ui:ellipsize("Achtung: Der Preis wurde geändert (jetzt " .. F.money(price) .. "/Stk.).", mw), mx, y, { color = YELLOW })
  elseif b.error then
    g:text(ui:ellipsize(b.error, mw), mx, y, { color = RED })
  end

  local canBuy = valid and not textInvalid and affordable and not b.pending
  if ui:button("buy_cancel", mx + mw - 164, my + mh - 20, 80, 20, "Abbrechen") then V.buy = nil return end
  if ui:button("buy_ok", mx + mw - 80, my + mh - 20, 80, 20, "Kaufen", {
    style = "primary", disabled = not canBuy,
    disabledTooltip = not affordable and "Zu wenig Geld" or (b.pending and "Wird gesendet …" or "Ungültige Menge"),
  }) then
    b.priceSeen, b.priceChanged, b.error = price, false, nil
    b.confirm = true
  end

  if b.confirm then
    local cx, cy, cw, chh = ui:modal("buy_confirm", 280, 92, { title = "Kauf bestätigen" })
    local lines = g:wrap("Wirklich " .. kit.formatNumber(amount) .. "× " .. o.name .. " für " .. F.money(total) .. " kaufen?", cw)
    for i = 1, min(2, #lines) do g:text(lines[i], cx + floor(cw / 2), cy + (i - 1) * 10, { align = "center" }) end
    if ui:button("buy_yes", cx + floor(cw / 2) - 92, cy + chh - 20, 88, 20, b.pending and "…" or "Ja, kaufen", { style = "primary", disabled = b.pending }) then
      b.pending = request("buy", "buy", { offer = o.id, amount = amount, price = b.priceSeen }) ~= nil
      if not b.pending then b.confirm, b.error = false, "Zu viele Anfragen, bitte kurz warten." end
    end
    if ui:button("buy_no", cx + floor(cw / 2) + 4, cy + chh - 20, 88, 20, "Zurück", { disabled = b.pending }) then b.confirm = false end
  end
end

-- ---- sell ------------------------------------------------------------------------------------------------------

local INV_ROWS = {
  { 9, 17 }, { 18, 26 }, { 27, 35 }, { 0, 8 },
}

local function refreshInventory(s)
  if s.time - V.invAt < 0.25 and V.invAt >= 0 then return end
  V.invAt = s.time
  local inv = kiln.player().inventory and kiln.player().inventory() or {}
  local bySlot = {}
  for _, e in ipairs(inv) do bySlot[e.slot] = e end
  V.inv, V.invBySlot = inv, bySlot
end

--- How many items like the selected one (same id and name) are in the inventory (the server checks exactly).
local function availableOf(sell)
  if not sell.id then return 0 end
  local n = 0
  for slot = 0, 35 do
    local e = V.invBySlot[slot]
    if e and e.id == sell.id and e.name == sell.name then n = n + e.count end
  end
  return n
end

local function selectSlot(e, shift)
  local sell = V.sell
  local same = sell.id == e.id and sell.name == e.name
  sell.slot, sell.id, sell.name, sell.handle = e.slot, e.id, e.name, e.handle
  sell.confirm, sell.error, sell.fields = false, nil, nil
  if not same then
    sell.category = M.suggest[e.id]
    sell.submitted = false
    sell.amount = "1"
  end
  if shift then sell.amount = tostring(e.count) end
end

local function clearSell()
  local sell = V.sell
  sell.slot, sell.id, sell.name, sell.handle = nil, nil, nil, nil
  sell.amount, sell.submitted, sell.confirm, sell.error, sell.fields, sell.category = "", false, false, nil, nil, nil
end

local function sellTab(ui, g, s, top, height)
  refreshInventory(s)
  local sell = V.sell
  -- inventory
  local invW = 9 * 18 + 12
  ui:panel(PAD, top, invW, height)
  g:text("Dein Inventar", PAD + 6, top + 6, { color = GRAY })
  local y = top + 18
  for r, range in ipairs(INV_ROWS) do
    local ry = y + (r - 1) * 18 + (r == 4 and 4 or 0)
    for slot = range[1], range[2] do
      local e = V.invBySlot[slot]
      local sx = PAD + 6 + (slot - range[1]) * 18
      local clicked, hot, mods = ui:itemSlot("inv", sx, ry, e and e.handle, e and e.count, {
        data = slot, selected = e ~= nil and sell.slot == slot and sell.id == e.id,
        tooltip = e and { "Klick: auswählen · Shift-Klick: ganzer Stack" } or false,
      })
      if clicked and e then selectSlot(e, mods.shift) end
    end
  end
  ui:paragraph("Klick wählt ein Item, Shift-Klick den ganzen Stack.", PAD + 6, y + 4 * 18 + 10, invW - 12, { color = DARK })
  if #V.inv == 0 then
    g:text("Dein Inventar ist leer.", PAD + 6, y + 4 * 18 + 34, { color = GRAY })
  end

  -- form
  local fx = PAD + invW + PAD
  local fw = min(340, s.width - fx - PAD)
  ui:panel(fx, top, fw, height)
  local x, inner = fx + 8, fw - 16
  y = top + 8
  ui:heading("Neues Angebot", x, y)
  y = y + 14
  local available = availableOf(sell)
  local hasItem = sell.id ~= nil
  if hasItem and available == 0 then
    -- the item left the inventory meanwhile
  end
  local deselect = ui:itemSlot("sell_item", x, y, hasItem and sell.handle or nil, 1, {
    tooltip = hasItem and { "Klicken zum Abwählen" } or false,
  })
  if deselect and hasItem then clearSell() end
  g:text(ui:ellipsize(hasItem and sell.name or "Kein Item gewählt", inner - 24), x + 24, y, { color = hasItem and WHITE or GRAY })
  g:text(hasItem and ("Im Inventar: " .. kit.formatNumber(available)) or "Links im Inventar anklicken", x + 24, y + 10, { color = hasItem and GRAY or DARK })
  y = y + 24

  local errors, amount, price = F.validateListing(hasItem, available, sell.amount, sell.price, sell.category, M.maxPrice)
  local shown = sell.submitted and errors or {}
  if sell.fields then for k, v in pairs(sell.fields) do shown[k] = v end end
  local labelW = 58
  local inputX, inputW = x + labelW, inner - labelW
  local function errorLine(key)
    if shown[key] then
      g:text(ui:ellipsize(shown[key], inputW), inputX, y, { color = RED })
      y = y + 11
    end
  end

  g:text("Menge", x, y + 6, { color = GRAY })
  ui:textInput("sell_amount", {
    x = inputX, y = y, w = inputW, h = 20, value = sell.amount, maxLength = 6, placeholder = "Anzahl",
    numeric = { min = 1, max = max(1, available), integer = true }, enabled = hasItem and not sell.pending,
    onChange = function(f, v) sell.amount = v; sell.fields = nil end,
  })
  y = y + 23
  errorLine("amount")
  g:text("Preis/Stk.", x, y + 6, { color = GRAY })
  ui:textInput("sell_price", {
    x = inputX, y = y, w = inputW, h = 20, value = sell.price, maxLength = 7, placeholder = "Taler",
    numeric = { min = 1, max = M.maxPrice, integer = true }, enabled = not sell.pending,
    onChange = function(f, v) sell.price = v; sell.fields = nil end,
  })
  y = y + 23
  errorLine("price")
  g:text("Gesamtwert: " .. ((amount and price) and F.money(amount * price) or "-"), inputX, y, { color = GOLD })
  y = y + 13
  g:text("Laufzeit", x, y + 6, { color = GRAY })
  local durationY = y
  y = y + 23
  g:text("Kategorie", x, y + 6, { color = GRAY })
  local categoryY = y
  y = y + 23
  errorLine("category")
  sell.notify = ui:toggle("sell_notify", x, y + 1, sell.notify, {
    label = "Benachrichtigen bei Verkauf",
    tooltip = "Toast und Chat-Nachricht, wenn jemand kauft (nur wenn du online bist)",
  })
  y = y + 20
  if shown.item then g:text(ui:ellipsize(shown.item, inner), x, y, { color = RED }) y = y + 11 end
  if sell.error then g:text(ui:ellipsize(sell.error, inner), x, y, { color = RED }) y = y + 11 end
  if ui:button("sell_submit", x, y + 2, inner, 20, sell.pending and "Wird eingestellt …" or "Angebot einstellen", {
    style = "primary", disabled = not hasItem or sell.pending,
    tooltip = "Prüfen und bestätigen", disabledTooltip = hasItem and "Wird gesendet …" or "Erst ein Item wählen",
  }) then
    sell.submitted = true
    sell.error = nil
    if next(errors) == nil then sell.confirm = true end
  end
  -- dropdowns (their lists are drawn over everything by ui:finish)
  sell.category = ui:dropdown("sell_category", inputX, categoryY, inputW, M.listable, sell.category, { placeholder = "Kategorie wählen",
    tooltip = "Wird beim Auswählen eines Items vorgeschlagen" })
  sell.duration = ui:dropdown("sell_duration", inputX, durationY, inputW, M.durations, sell.duration, { tooltip = "Wie lange das Angebot im Markt steht" })

  if sell.confirm and amount and price then
    local cx, cy, cw, chh = ui:modal("sell_confirm", 300, 112, { title = "Angebot bestätigen" })
    local durationLabel = sell.duration
    for _, d in ipairs(M.durations) do if d.value == sell.duration then durationLabel = d.label end end
    local lines = g:wrap(kit.formatNumber(amount) .. "× " .. sell.name .. " für " .. F.money(price) .. "/Stk. (gesamt "
      .. F.money(amount * price) .. ") für " .. durationLabel .. " anbieten?", cw)
    for i = 1, min(3, #lines) do g:text(lines[i], cx + floor(cw / 2), cy + (i - 1) * 10, { align = "center" }) end
    g:text("Die Items werden aus deinem Inventar genommen.", cx + floor(cw / 2), cy + 34, { align = "center", color = GRAY })
    if ui:button("sell_yes", cx + floor(cw / 2) - 92, cy + chh - 20, 88, 20, sell.pending and "…" or "Einstellen", { style = "primary", disabled = sell.pending }) then
      sell.pending = request("sell", "sell", {
        slot = sell.slot, item = sell.id, amount = amount, price = price, duration = sell.duration,
        category = sell.category, notify = sell.notify,
      }) ~= nil
      if not sell.pending then sell.confirm, sell.error = false, "Zu viele Anfragen, bitte kurz warten." end
    end
    if ui:button("sell_no", cx + floor(cw / 2) + 4, cy + chh - 20, 88, 20, "Zurück", { disabled = sell.pending }) then sell.confirm = false end
  elseif sell.confirm then
    sell.confirm = false
  end
end

-- ---- my offers -------------------------------------------------------------------------------------------------

local function myOffersTab(ui, g, s, top, height)
  local x, width = PAD, s.width - 2 * PAD
  local stock = 0
  for _, o in ipairs(M.mine) do if not o.expired then stock = stock + o.amount * o.price end end
  g:text(#M.mine .. (#M.mine == 1 and " Angebot" or " Angebote") .. " · Warenwert im Markt: " .. F.money(stock), x, top + 4, { color = GRAY })
  local ly = top + 16
  local lh = height - 16
  if #M.mine == 0 then
    ui:panel(x, ly, width, lh)
    g:text("Du hast keine Angebote.", x + floor(width / 2), ly + 24, { align = "center", color = GRAY })
    if ui:button("go_sell", x + floor((width - 140) / 2), ly + 40, 140, 20, "Jetzt etwas verkaufen", { style = "primary" }) then V.tab = 2 end
    return
  end
  local rowH, gap = 30, 3
  ui:scrollPanel("mine", x, ly, width, lh, #M.mine * (rowH + gap) - gap, function(cx, cy, cw)
    for i, o in ipairs(M.mine) do
      local ry = cy + (i - 1) * (rowH + gap)
      if ry + rowH >= ly and ry < ly + lh then
        local left = secondsLeft(o)
        local expired = o.expired or (left ~= nil and left <= 0)
        ui:card("mine_row", cx, ry, cw, rowH, { data = o.id, clickable = false })
        ui:itemSlot("mine_item", cx + 5, ry + 6, o.handle, o.amount, { data = o.id, clickable = false, dim = expired })
        local textW = cw - 28 - 172
        g:text(ui:ellipsize(o.name, textW), cx + 28, ry + 5, { color = expired and GRAY or WHITE })
        local info = F.money(o.price) .. "/Stk. · " .. kit.formatNumber(o.amount) .. " Stück · "
        g:text(ui:ellipsize(info .. (expired and "abgelaufen" or ("noch " .. F.remaining(left))), textW), cx + 28, ry + 16, { color = expired and RED or GOLD })
        if ui:button("mine_price", cx + cw - 168, ry + 5, 80, 20, "Preis ändern", {
          data = o.id, disabled = expired,
          tooltip = "Aktuell " .. F.money(o.price) .. "/Stk.", disabledTooltip = "Abgelaufene Angebote nur zurückziehen",
        }) then
          V.priceEdit = { id = o.id, text = tostring(o.price) }
        end
        if ui:button("mine_withdraw", cx + cw - 84, ry + 5, 80, 20, "Zurückziehen", { data = o.id, style = "danger", tooltip = "Items zurück ins Inventar" }) then
          V.withdraw = { id = o.id }
        end
      end
    end
  end)
end

local function savePrice(pe)
  local n = tonumber(pe.text)
  if pe.text == "" or not n or n < 1 or n > M.maxPrice or n ~= floor(n) then
    pe.error = "Preis muss zwischen 1 und " .. kit.formatNumber(M.maxPrice) .. " liegen."
    return
  end
  pe.error = nil
  pe.pending = request("reprice", "reprice", { offer = pe.id, price = n }) ~= nil
  if not pe.pending then pe.error = "Zu viele Anfragen, bitte kurz warten." end
end

local function myOfferModals(ui, g, s)
  local pe = V.priceEdit
  if pe then
    local o = M.mineById[pe.id]
    if not o then V.priceEdit = nil return end
    local mx, my, mw, mh, dismissed = ui:modal("price_edit", 280, 124, { title = "Preis ändern", dismiss = not pe.pending })
    if dismissed then V.priceEdit = nil return end
    ui:itemSlot("pe_item", mx, my, o.handle, 1, { clickable = false })
    g:text(ui:ellipsize(o.name, mw - 24), mx + 24, my, { color = WHITE })
    g:text("Aktuell: " .. F.money(o.price) .. "/Stk.", mx + 24, my + 10, { color = GRAY })
    g:text("Neuer Preis", mx, my + 30, { color = GRAY })
    ui:textInput("pe_price", {
      x = mx + 64, y = my + 24, w = mw - 64, h = 20, value = pe.text, maxLength = 7, focus = true,
      numeric = { min = 1, max = M.maxPrice, integer = true }, enabled = not pe.pending,
      onChange = function(f, v) pe.text = v; pe.error = nil end,
    })
    if pe.error then g:text(ui:ellipsize(pe.error, mw), mx, my + 48, { color = RED }) end
    if ui:button("pe_save", mx + floor(mw / 2) - 92, my + mh - 20, 88, 20, pe.pending and "…" or "Speichern", { style = "primary", disabled = pe.pending }) then
      savePrice(pe)
    end
    if ui:button("pe_cancel", mx + floor(mw / 2) + 4, my + mh - 20, 88, 20, "Abbrechen", { disabled = pe.pending }) then V.priceEdit = nil end
  end
  local wd = V.withdraw
  if wd then
    local o = M.mineById[wd.id]
    if not o then V.withdraw = nil return end
    local mx, my, mw, mh, dismissed = ui:modal("withdraw", 280, 96, { title = "Angebot zurückziehen", dismiss = not wd.pending })
    if dismissed then V.withdraw = nil return end
    g:text(ui:ellipsize(kit.formatNumber(o.amount) .. "× " .. o.name .. " zurückziehen?", mw), mx + floor(mw / 2), my, { align = "center" })
    g:text("Die Items kommen zurück in dein Inventar,", mx + floor(mw / 2), my + 14, { align = "center", color = GRAY })
    g:text("was nicht passt, wird gedroppt.", mx + floor(mw / 2), my + 24, { align = "center", color = GRAY })
    if ui:button("wd_yes", mx + floor(mw / 2) - 92, my + mh - 20, 88, 20, wd.pending and "…" or "Zurückziehen", { style = "danger", disabled = wd.pending }) then
      wd.pending = request("withdraw", "withdraw", { offer = o.id }) ~= nil
    end
    if ui:button("wd_no", mx + floor(mw / 2) + 4, my + mh - 20, 88, 20, "Abbrechen", { disabled = wd.pending }) then V.withdraw = nil end
  end
end

-- ---- screen ----------------------------------------------------------------------------------------------------

local current = nil -- self of the open screen (for results / state arriving while it is open)

kiln.ui.screen("market", {
  fullscreen = true,
  init = function(self, s, args)
    self.ui = kit.new()
    current = self
    screenOpen = true
    resetModals()
    for i, key in ipairs(TAB_KEYS) do if args.tab == key then V.tab = i end end
    for _, t in ipairs(pendingToasts) do self.ui:toast(t[1], t[2]) end
    pendingToasts = {}
  end,
  render = function(self, g, s)
    local ui = self.ui
    ui:frame(g, s)
    g:gradient(0, 0, s.width, s.height, 0xE00C0E11, 0xF0101216)
    header(ui, g, s)
    local top = HEADER_H + PAD
    local height = s.height - top - PAD
    if V.tab == 1 then
      marketTab(ui, g, s, top, height)
      buyModals(ui, g, s)
    elseif V.tab == 2 then
      sellTab(ui, g, s, top, height)
    else
      myOffersTab(ui, g, s, top, height)
      myOfferModals(ui, g, s)
    end
    ui:finish()
  end,
  click = function(self, s, mx, my, button, mods) return self.ui:click(mx, my, button, mods) end,
  release = function(self, s, mx, my, button) self.ui:release(mx, my, button) end,
  scroll = function(self, s, mx, my, dy) return self.ui:scroll(mx, my, dy) end,
  key = function(self, s, key, mods)
    local ui = self.ui
    if ui:key(key, mods) then return true end
    local b, sell, pe, wd = V.buy, V.sell, V.priceEdit, V.withdraw
    if key == "escape" then
      if b and b.confirm then if not b.pending then b.confirm = false end return true end
      if b then if not b.pending then V.buy = nil end return true end
      if sell.confirm then if not sell.pending then sell.confirm = false end return true end
      if pe then if not pe.pending then V.priceEdit = nil end return true end
      if wd then if not wd.pending then V.withdraw = nil end return true end
      return false
    end
    if (key == "enter" or key == "keypad.enter") and pe and V.tab == 3 and not pe.pending then
      savePrice(pe)
      return true
    end
    return false
  end,
  close = function(self, s, reason)
    screenOpen = false
    current = nil
    ch.closed:send({})
  end,
})

-- ---- channels --------------------------------------------------------------------------------------------------

ch.open:on(function(m)
  applyState(m)
  if m.tab then for i, key in ipairs(TAB_KEYS) do if m.tab == key then V.tab = i end end end
  if not screenOpen then kiln.ui.open("market", { tab = m.tab }) end
end)

ch.state:on(function(m)
  applyState(m)
  -- live update: an offer the player is looking at may be gone or have a new price; buy modal shows it
  if V.priceEdit and not M.mineById[V.priceEdit.id] then
    V.priceEdit = nil
    if current then current.ui:toast("Das Angebot existiert nicht mehr.", "error") end
  end
  if V.withdraw and not V.withdraw.pending and not M.mineById[V.withdraw.id] then V.withdraw = nil end
end)

ch.result:on(function(m)
  local action = pending[m.req]
  pending[m.req] = nil
  local ui = current and current.ui
  local kind = m.ok and "success" or "error"
  if action == "buy" and V.buy then
    local b = V.buy
    b.pending, b.confirm = false, false
    if m.ok then V.buy = nil else
      b.error = m.error
      if m.newPrice then b.priceSeen, b.priceChanged = m.newPrice, true end
    end
  elseif action == "sell" then
    local sell = V.sell
    sell.pending, sell.confirm = false, false
    if m.ok then clearSell() else
      sell.error = m.error
      sell.fields = m.fields
      sell.submitted = true
    end
  elseif action == "reprice" and V.priceEdit then
    V.priceEdit.pending = false
    if m.ok then V.priceEdit = nil else V.priceEdit.error = m.error end
  elseif action == "withdraw" then
    V.withdraw = nil
  end
  local text = m.ok and m.message or m.error
  if text and (m.ok or action ~= "sell" or not m.fields) then
    if ui then ui:toast(text, kind) else pendingToasts[#pendingToasts + 1] = { text, kind } end
  end
  if m.ok and kiln.ui.sound and screenOpen then kiln.ui.sound("success") end
  if not m.ok and kiln.ui.sound and screenOpen then kiln.ui.sound("error") end
end)

if kiln.keys then
  kiln.keys.bind("open_shop", { name = "Markt öffnen", default = "K", press = function() ch.open:send({}) end })
end

-- for tests and debugging
shop_debug = { M = M, V = V, F = F, applyState = applyState }

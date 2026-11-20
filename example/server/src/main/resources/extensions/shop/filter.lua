-- Pure helpers of the shop: client-side search / category filter / sorting and formatting.
-- Offers are tables from the server state (see docs/ui_kit.md, "Shop-Kanäle") plus `hay` (lowercase search text).
local kit = kiln.import("ui_kit")

local F = {}

--- Search terms: lowercase words of the query (umlauts included).
function F.terms(search)
  local terms = {}
  for word in kit.lower(search or ""):gmatch("%S+") do terms[#terms + 1] = word end
  return terms
end

local FOLD = { ["\195\164"] = "a", ["\195\182"] = "o", ["\195\188"] = "u", ["\195\159"] = "ss" }
--- Sort key for names: lowercase with umlauts folded (Äpfel sorts with A like in a German dictionary).
function F.sortKey(name)
  return (kit.lower(name or ""):gsub("\195[\164\182\188\159]", FOLD))
end

--- Lowercase haystack of an offer: name, item key (without namespace, "_" -> " ") and seller.
function F.haystack(o)
  local key = (o.key or ""):gsub("^minecraft:", ""):gsub("_", " ")
  return kit.lower((o.name or "") .. " " .. key .. " " .. (o.seller or ""))
end

local function matches(o, category, terms)
  if category ~= "ALL" and o.cat ~= category then return false end
  local hay = o.hay
  for i = 1, #terms do
    if not hay:find(terms[i], 1, true) then return false end
  end
  return true
end
F.matches = matches

local function newest(a, b)
  if a.created ~= b.created then return a.created > b.created end
  return a.id > b.id
end

F.comparators = {
  PRICE_ASC = function(a, b) if a.price ~= b.price then return a.price < b.price end return newest(a, b) end,
  PRICE_DESC = function(a, b) if a.price ~= b.price then return a.price > b.price end return newest(a, b) end,
  NEWEST = newest,
  NAME = function(a, b)
    if a.lname ~= b.lname then return a.lname < b.lname end
    if a.price ~= b.price then return a.price < b.price end
    return newest(a, b)
  end,
}

F.sorts = {
  { value = "NEWEST", label = "Neueste" },
  { value = "PRICE_ASC", label = "Preis aufsteigend" },
  { value = "PRICE_DESC", label = "Preis absteigend" },
  { value = "NAME", label = "Name" },
}

--- Offers matching category ("ALL" = any) and search, sorted by sort (key of F.comparators).
function F.query(offers, category, search, sort)
  local terms = F.terms(search)
  local result = {}
  for i = 1, #offers do
    local o = offers[i]
    if matches(o, category, terms) then result[#result + 1] = o end
  end
  table.sort(result, F.comparators[sort] or newest)
  return result
end

--- Number of offers per category id (plus "ALL") matching the search.
function F.counts(offers, categories, search)
  local terms = F.terms(search)
  local counts = { ALL = 0 }
  for i = 1, #categories do counts[categories[i].id] = 0 end
  for i = 1, #offers do
    local o = offers[i]
    if matches(o, "ALL", terms) then
      counts.ALL = counts.ALL + 1
      counts[o.cat] = (counts[o.cat] or 0) + 1
    end
  end
  return counts
end

--- "1.234 Taler"
function F.money(n) return kit.formatNumber(n) .. " Taler" end

--- Remaining time like the server: "6 Std. 12 Min.", "3 T. 4 Std.", "12 Min.", "abgelaufen", "unbegrenzt".
function F.remaining(seconds)
  if seconds == nil then return "unbegrenzt" end
  if seconds <= 0 then return "abgelaufen" end
  local minutes = math.floor((seconds + 59) / 60)
  local days = math.floor(minutes / 1440)
  local hours = math.floor(minutes / 60) % 24
  local mins = minutes % 60
  if days > 0 then return days .. " T. " .. hours .. " Std." end
  if hours > 0 then return hours .. " Std. " .. mins .. " Min." end
  return mins .. " Min."
end

--- Validation of the sell form (mirrors the server's ShopValidation.listing; the server decides).
--- Returns errors (field -> text), amount, price.
function F.validateListing(hasItem, available, amountText, priceText, category, maxPrice)
  local errors = {}
  if not hasItem then errors.item = "Wähle links ein Item aus deinem Inventar."
  elseif available <= 0 then errors.item = "Das Item ist nicht mehr in deinem Inventar." end
  local amount = tonumber(amountText)
  if amountText == nil or amountText:match("^%s*$") then errors.amount = "Menge fehlt."
  elseif not amount or amount < 1 or amount ~= math.floor(amount) then errors.amount = "Menge muss eine ganze Zahl ab 1 sein."
  elseif hasItem and available > 0 and amount > available then errors.amount = "Du hast nur " .. kit.formatNumber(available) .. " Stück." end
  local price = tonumber(priceText)
  if priceText == nil or priceText:match("^%s*$") then errors.price = "Preis fehlt."
  elseif not price or price < 1 or price > maxPrice or price ~= math.floor(price) then
    errors.price = "Preis muss zwischen 1 und " .. kit.formatNumber(maxPrice) .. " liegen."
  end
  if category == nil or category == "ALL" then errors.category = "Kategorie wählen." end
  return errors, (not errors.amount) and amount or nil, (not errors.price) and price or nil
end

return F

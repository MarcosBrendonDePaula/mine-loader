-- Minimap Demo
--
-- Protótipo de minimap usando apenas o HUD declarativo actual do MineLoader.
-- O mapa é uma fotografia pequena do terreno abaixo do jogador. Use:
--   /mod minimap_demo on
--   /mod minimap_demo refresh
--   /mod minimap_demo off
--
-- Esta versão é deliberadamente limitada: sem API client-side de câmara, ela não acompanha o
-- jogador a cada tick nem desenha uma textura de mapa. Serve para testar a camada de HUD e o
-- contrato de leitura de block_state antes de existir client.map.

local RADIUS = 4
local TILE = 10
local MAP_X = 2
local MAP_Y = 2
local MAP_SIZE = (RADIUS * 2 + 1) * TILE

local ENABLED = {}

local function terrain_color(block_id)
    if block_id == "minecraft:air"
            or block_id == "minecraft:cave_air"
            or block_id == "minecraft:void_air" then
        return "#00000000"
    end

    if string.find(block_id, "water", 1, true) then
        return "#2868B8FF"
    elseif string.find(block_id, "lava", 1, true) then
        return "#D65A20FF"
    elseif string.find(block_id, "grass", 1, true)
            or string.find(block_id, "moss", 1, true)
            or string.find(block_id, "leaves", 1, true) then
        return "#4E9B4EFF"
    elseif string.find(block_id, "sand", 1, true)
            or string.find(block_id, "terracotta", 1, true) then
        return "#C9A45AFF"
    elseif string.find(block_id, "snow", 1, true)
            or string.find(block_id, "ice", 1, true) then
        return "#D9F2FFFF"
    elseif string.find(block_id, "log", 1, true)
            or string.find(block_id, "wood", 1, true)
            or string.find(block_id, "planks", 1, true) then
        return "#8B6A43FF"
    elseif string.find(block_id, "stone", 1, true)
            or string.find(block_id, "deepslate", 1, true)
            or string.find(block_id, "ore", 1, true) then
        return "#777777FF"
    end

    return "#A8A8A8FF"
end

local function safe_block(ctx, x, y, z)
    local state = ctx.server.block_state(x, y, z)
    if state == nil or state.id == nil then
        return "#00000000"
    end
    return terrain_color(state.id)
end

local function map_hud(ctx)
    local position = ctx.player.position()
    local x0 = math.floor(position.x)
    local y0 = math.floor(position.y) - 1
    local z0 = math.floor(position.z)
    local cells = {}

    for dz = -RADIUS, RADIUS do
        for dx = -RADIUS, RADIUS do
            cells[#cells + 1] = {
                type = "panel",
                x = MAP_X + (dx + RADIUS) * TILE,
                y = MAP_Y + (dz + RADIUS) * TILE,
                w = TILE,
                h = TILE,
                color = safe_block(ctx, x0 + dx, y0, z0 + dz)
            }
        end
    end

    local center = MAP_X + RADIUS * TILE
    local elements = {
        { type = "panel", x = MAP_X - 2, y = MAP_Y - 2,
          w = MAP_SIZE + 4, h = MAP_SIZE + 4, color = "#101018E8" },
        { type = "label", x = MAP_X + 2, y = MAP_Y - 1,
          text = "N", color = "#FFFFFFFF" }
    }

    for _, cell in ipairs(cells) do
        elements[#elements + 1] = cell
    end

    -- O marcador é desenhado depois das células para ficar sempre no centro do mapa.
    elements[#elements + 1] = {
        type = "panel", x = center + 2, y = center + 2,
        w = TILE - 4, h = TILE - 4, color = "#F5D547FF"
    }

    elements[#elements + 1] = {
        type = "label", x = MAP_X, y = MAP_Y + MAP_SIZE + 4,
        text = "X " .. x0 .. "  Z " .. z0,
        color = "#FFFFFFFF"
    }
    elements[#elements + 1] = {
        type = "label", x = MAP_X, y = MAP_Y + MAP_SIZE + 16,
        text = "Y " .. math.floor(position.y) .. "  " .. ctx.player.dimension(),
        color = "#D0D0D0FF"
    }

    return elements
end

local function refresh(ctx)
    if not ctx.player.supports_screens() then
        return false
    end

    ctx.player.set_hud(map_hud(ctx))
    return true
end

local function on_player_joined(ctx)
    if ctx.player == nil then
        return
    end

    ENABLED[ctx.player.uuid] = true
    if refresh(ctx) then
        ctx.player.send_message("Minimap Demo ligado. Use /mod minimap_demo refresh depois de se mover.")
    else
        ctx.player.send_message("Minimap Demo: este cliente não suporta o HUD do loader.")
    end
end

mod.command("minimap_demo", function(ctx)
    if ctx.player == nil then
        return
    end

    local action = ctx.argv[2] or "refresh"
    local uuid = ctx.player.uuid

    if action == "off" then
        ENABLED[uuid] = false
        ctx.player.set_hud({})
        ctx.player.send_message("Minimap Demo desligado.")
        return
    end

    if action == "on" or action == "refresh" then
        ENABLED[uuid] = true
        if refresh(ctx) then
            ctx.player.send_message("Minimap Demo actualizado.")
        else
            ctx.player.send_message("Este cliente não suporta o HUD do loader.")
        end
        return
    end

    ctx.player.send_message("Uso: /mod minimap_demo on|refresh|off")
end)

return {
    on_player_joined = on_player_joined
}

local function on_loader_ready(ctx)
    ctx.log.info("Hello Lua foi carregado pelo runtime.")
end

local function on_server_started(ctx)
    ctx.log.info("Servidor Minecraft iniciado.")
end

local function on_player_joined(ctx)
    if ctx.player ~= nil then
        ctx.player.send_message("Ola, " .. ctx.player.name .. "! Bata ou clique num Bloco de Rubi para trocar a textura.")
    end
end

-- Avanca a variante visual do bloco para a proxima textura declarada no manifesto.
local function cycle(ctx, acao)
    if ctx.block == nil then
        return
    end

    local proxima = (ctx.block.variant + 1) % ctx.block.variant_count
    ctx.server.set_block_variant(ctx.block.id, ctx.block.x, ctx.block.y, ctx.block.z, proxima)
    -- A dureza acompanha a variante para deixar a mudanca perceptivel ao minerar.
    ctx.server.set_block_property(ctx.block.id, "hardness", 5 + proxima)

    ctx.log.info(acao .. " em " .. ctx.block.id .. " (" .. ctx.block.x .. "," .. ctx.block.y .. "," .. ctx.block.z .. "): variante " .. ctx.block.variant .. " -> " .. proxima .. ".")

    if ctx.player ~= nil then
        ctx.player.send_message("Textura trocada para a variante " .. proxima .. ".")
    end
end

local function on_block_used(ctx)
    cycle(ctx, "Clique")
end

local function on_block_attacked(ctx)
    cycle(ctx, "Batida")
end

return {
    on_loader_ready = on_loader_ready,
    on_server_started = on_server_started,
    on_player_joined = on_player_joined,
    on_block_used = on_block_used,
    on_block_attacked = on_block_attacked
}

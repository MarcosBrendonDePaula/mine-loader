local ticks = 0

local function on_loader_ready(ctx)
    ctx.log.info("Hello Lua foi carregado pelo runtime.")
end

local function on_server_started(ctx)
    ctx.log.info("Servidor Minecraft iniciado.")
end

local function on_player_joined(ctx)
    if ctx.player ~= nil then
        ctx.player.send_message("Olá, " .. ctx.player.name .. "! Este servidor usa Lua Loader.")
    end
end

local function on_tick(ctx)
    ticks = ticks + 1
    if ticks % 40 == 0 then
        local variant = math.floor(ticks / 40) % 2
        ctx.server.set_block_variant("hello_lua:ruby_block", 0, 100, 0, variant)
        ctx.server.set_block_property("hello_lua:ruby_block", "hardness", 5 + variant)
        ctx.server.set_block_luminance("hello_lua:ruby_block", 0, 100, 0, variant * 15)
        ctx.log.info("Variante visual e dureza alteradas: variante=" .. variant .. ", dureza=" .. (5 + variant) .. ".")
    end
end

return {
    on_loader_ready = on_loader_ready,
    on_server_started = on_server_started,
    on_player_joined = on_player_joined,
    on_tick = on_tick
}

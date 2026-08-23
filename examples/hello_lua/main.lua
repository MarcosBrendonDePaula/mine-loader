-- Ciclo de vida do mod. A logica de cada bloco vive no arquivo que o manifesto aponta.
local function on_loader_ready(ctx)
    ctx.log.info("Hello Lua foi carregado pelo runtime.")
end

local function on_server_started(ctx)
    ctx.log.info("Servidor Minecraft iniciado.")
end

local function on_player_joined(ctx)
    if ctx.player ~= nil then
        ctx.player.send_message("Ola, " .. ctx.player.name .. "! Clique num Bloco de Rubi com a mao vazia.")
    end
end

return {
    on_loader_ready = on_loader_ready,
    on_server_started = on_server_started,
    on_player_joined = on_player_joined
}

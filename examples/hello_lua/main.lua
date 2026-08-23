-- Ciclo de vida do mod. A logica de cada bloco vive no arquivo que o manifesto aponta.
local function on_loader_ready(ctx)
    ctx.log.info("Hello Lua foi carregado pelo runtime.")
end

local function on_server_started(ctx)
    ctx.log.info("Servidor Minecraft iniciado.")
end

local function on_player_joined(ctx)
    if ctx.player ~= nil then
        ctx.player.send_message("Ola, " .. ctx.player.name .. "! Tente /mod rubi")
    end
end

-- Comando proprio do mod: troca 3 diamantes por um rubi, com contagem persistida.
mod.command("rubi", function(ctx)
    if ctx.player == nil then
        return
    end

    local preco = 3
    local tem = ctx.player.count_item("minecraft:diamond")

    if tem < preco then
        ctx.player.send_action_bar("Faltam " .. (preco - tem) .. " diamante(s).")
        return
    end

    ctx.player.take_item("minecraft:diamond", preco)
    ctx.player.give_item("hello_lua:ruby", 1)

    ctx.state.trocas = (ctx.state.trocas or 0) + 1

    local onde = ctx.player.position()
    ctx.server.play_sound("minecraft:block.note_block.bell", onde.x, onde.y, onde.z, 1.0, 1.5)
    ctx.server.spawn_particles("minecraft:happy_villager", onde.x, onde.y + 1, onde.z, 16, 0.6)

    ctx.player.send_message("Troca numero " .. ctx.state.trocas .. " concluida.")

    -- O agendador dispensa contar ticks a mao dentro do evento tick.
    mod.after(40, function(depois)
        depois.log.info("Duas segundos apos a troca numero " .. depois.state.trocas .. ".")
    end)
end)

return {
    on_loader_ready = on_loader_ready,
    on_server_started = on_server_started,
    on_player_joined = on_player_joined
}

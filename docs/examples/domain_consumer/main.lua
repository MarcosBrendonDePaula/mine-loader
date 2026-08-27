local function on_player_joined(ctx)
    local dificuldade = ctx.server.difficulty()
    local hora = ctx.server.time_of_day()
    ctx.player.send_message(
        "Contrato world/player activo | dificuldade: " .. dificuldade .. " | hora: " .. hora)
end

mod.on("player_joined", on_player_joined)

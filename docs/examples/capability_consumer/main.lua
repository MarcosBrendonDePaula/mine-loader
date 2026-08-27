local function on_player_joined(ctx)
    local alvo = ctx.player.looking_at(5)
    if alvo == nil then
        ctx.player.send_message("Nenhum bloco na sua mira.")
        return
    end

    local estado = ctx.server.block_state(alvo.x, alvo.y, alvo.z)
    local sinal = ctx.server.redstone_signal(alvo.x, alvo.y, alvo.z)
    ctx.player.send_message(
        "Alvo: " .. estado.id .. " | redstone: " .. sinal)
end

mod.on("player_joined", on_player_joined)

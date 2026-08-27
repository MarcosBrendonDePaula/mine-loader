local biblioteca = mod.require("library_provider")

local function on_player_joined(ctx)
    local estado = ctx.server.block_state(0, 64, 0)
    ctx.player.send_message(biblioteca.formatar("bloco em 0,64,0: " .. estado.id))
end

mod.on("player_joined", on_player_joined)

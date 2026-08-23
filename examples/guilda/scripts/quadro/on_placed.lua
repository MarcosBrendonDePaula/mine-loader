-- Chamado quando um Quadro de Missoes e colocado no mundo.
-- Cada quadro guarda os proprios dados na posicao em que esta, entao dois quadros nao se misturam.
return function(ctx)
    ctx.server.set_block_data(ctx.block.x, ctx.block.y, ctx.block.z, {
        entregas = 0,
        dono = ctx.player ~= nil and ctx.player.name or "desconhecido"
    })

    if ctx.player ~= nil then
        ctx.player.send_action_bar("Quadro registrado em seu nome.")
    end
end

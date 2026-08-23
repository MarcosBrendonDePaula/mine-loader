-- Clique com o fragmento sobre um bloco: transforma o alvo em cristal.
return function(ctx)
    if ctx.item.target_block == "crystal_world:crystal_block" then
        if ctx.player ~= nil then
            ctx.player.send_message("Esse bloco ja e cristal.")
        end
        -- Cancela a acao padrao para o fragmento nao fazer mais nada aqui.
        return false
    end

    ctx.server.set_block("crystal_world:crystal_block", ctx.item.x, ctx.item.y, ctx.item.z)
    ctx.log.info("Fragmento converteu " .. ctx.item.target_block .. " em cristal.")

    if ctx.player ~= nil then
        ctx.player.send_message("O bloco virou cristal!")
    end
end

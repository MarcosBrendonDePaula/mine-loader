-- Chamado apenas para hello_lua:ruby_block, porque o manifesto associou este arquivo a ele.
-- Nao e preciso checar qual bloco recebeu o clique.
return function(ctx)
    local proxima = (ctx.block.variant + 1) % ctx.block.variant_count
    ctx.server.set_block_variant(ctx.block.id, ctx.block.x, ctx.block.y, ctx.block.z, proxima)
    ctx.server.set_block_property(ctx.block.id, "hardness", 5 + proxima)

    ctx.log.info("Variante " .. ctx.block.variant .. " -> " .. proxima ..
        " em (" .. ctx.block.x .. "," .. ctx.block.y .. "," .. ctx.block.z .. ").")

    if ctx.player ~= nil then
        ctx.player.send_message("Textura trocada para a variante " .. proxima .. ".")
    end
end

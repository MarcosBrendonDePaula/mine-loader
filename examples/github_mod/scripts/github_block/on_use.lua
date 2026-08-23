-- Alterna entre o tema escuro e o claro do bloco.
return function(ctx)
    local proxima = (ctx.block.variant + 1) % ctx.block.variant_count
    ctx.server.set_block_variant(ctx.block.id, ctx.block.x, ctx.block.y, ctx.block.z, proxima)

    local tema = proxima == 0 and "escuro" or "claro"
    ctx.log.info("Tema do bloco: " .. tema .. ".")

    if ctx.player ~= nil then
        ctx.player.send_message("Tema " .. tema .. " aplicado.")
    end
end

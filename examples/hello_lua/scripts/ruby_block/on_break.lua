-- Batida no bloco: acende a luz por um instante, sem trocar a textura.
return function(ctx)
    local aceso = ctx.block.variant == 0 and 15 or 0
    ctx.server.set_block_luminance(ctx.block.id, ctx.block.x, ctx.block.y, ctx.block.z, aceso)

    ctx.log.info("Luminosidade ajustada para " .. aceso .. ".")

    if ctx.player ~= nil then
        ctx.player.send_message("O rubi " .. (aceso > 0 and "brilhou" or "apagou") .. ".")
    end
end

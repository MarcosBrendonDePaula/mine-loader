local function on_loader_ready(ctx)
    ctx.log.info("Crystal World carregado ao lado dos outros mods.")
end

local function on_block_used(ctx)
    if ctx.block == nil then
        return
    end

    local acima = ctx.server.get_block(ctx.block.x, ctx.block.y + 1, ctx.block.z)
    if acima ~= "minecraft:air" then
        ctx.log.info("Espaco ocupado por " .. acima .. "; torre nao construida.")
        if ctx.player ~= nil then
            ctx.player.send_message("Precisa de espaco livre acima do cristal.")
        end
        return
    end

    -- O desenho da torre vive no manifesto; aqui so escolhemos onde coloca-la.
    local colocados = ctx.server.place_structure("crystal_tower", ctx.block.x, ctx.block.y + 1, ctx.block.z)

    ctx.log.info("Torre declarada posicionada em (" .. ctx.block.x .. "," ..
        (ctx.block.y + 1) .. "," .. ctx.block.z .. "): " .. colocados .. " blocos.")

    if ctx.player ~= nil then
        ctx.player.send_message("Torre de cristal erguida com " .. colocados .. " blocos!")
    end
end

return {
    on_loader_ready = on_loader_ready,
    on_block_used = on_block_used
}

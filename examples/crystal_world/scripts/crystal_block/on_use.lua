-- Logica exclusiva de crystal_world:crystal_block, associada pelo manifesto.
-- O desenho da torre vive em parts/structures/crystal_tower.json.
return function(ctx)
    local acima = ctx.server.get_block(ctx.block.x, ctx.block.y + 1, ctx.block.z)
    if acima ~= "minecraft:air" then
        ctx.log.info("Espaco ocupado por " .. acima .. "; torre nao construida.")
        if ctx.player ~= nil then
            ctx.player.send_message("Precisa de espaco livre acima do cristal.")
        end
        return
    end

    local colocados = ctx.server.place_structure("crystal_tower", ctx.block.x, ctx.block.y + 1, ctx.block.z)
    ctx.log.info("Torre posicionada em (" .. ctx.block.x .. "," .. (ctx.block.y + 1) .. "," .. ctx.block.z ..
        "): " .. colocados .. " blocos.")

    if ctx.player ~= nil then
        ctx.player.send_message("Torre de cristal erguida com " .. colocados .. " blocos!")
    end
end

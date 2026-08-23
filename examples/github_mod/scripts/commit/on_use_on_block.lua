-- Usar um Commit sobre um bloco ergue um repositorio no lugar.
return function(ctx)
    local acima = ctx.server.get_block(ctx.item.x, ctx.item.y + 1, ctx.item.z)
    if acima ~= "minecraft:air" then
        if ctx.player ~= nil then
            ctx.player.send_message("Precisa de espaco livre para o repositorio.")
        end
        return false
    end

    local blocos = ctx.server.place_structure("repo", ctx.item.x, ctx.item.y + 1, ctx.item.z)
    ctx.state.repos = (ctx.state.repos or 0) + 1

    ctx.log.info("Repositorio numero " .. ctx.state.repos .. " com " .. blocos .. " blocos.")

    if ctx.player ~= nil then
        ctx.player.send_message("Commit aplicado: repositorio numero " .. ctx.state.repos .. ".")
    end
end

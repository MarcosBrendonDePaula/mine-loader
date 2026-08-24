-- Altar: guarda dados na propria posicao, mostra um menu e invoca um guardiao.
return function(ctx)
    local dados = ctx.server.get_block_data(ctx.block.x, ctx.block.y, ctx.block.z)
    dados.oferendas = (dados.oferendas or 0) + 1
    ctx.server.set_block_data(ctx.block.x, ctx.block.y, ctx.block.z, dados)

    ctx.server.play_sound("minecraft:block.beacon.activate", ctx.block.x, ctx.block.y, ctx.block.z, 0.6, 1.4)
    ctx.server.spawn_particles("minecraft:end_rod", ctx.block.x, ctx.block.y + 1, ctx.block.z, 20, 0.4)

    -- Cada altar tem a propria contagem, porque o dado vive no bloco.
    ctx.log.info("Altar em (" .. ctx.block.x .. "," .. ctx.block.y .. "," .. ctx.block.z ..
        ") recebeu a oferenda numero " .. dados.oferendas .. ".")

    if ctx.player == nil then
        return
    end

    ctx.player.open_menu("altar", "Altar: " .. dados.oferendas .. " oferendas", 3, {
        { item = "crystal_world:crystal_shard", count = math.min(64, dados.oferendas) },
        { item = "crystal_world:crystal_block", count = 1 },
        "minecraft:amethyst_shard"
    })

    -- A cada tres oferendas, um guardiao aparece ao lado.
    if dados.oferendas % 3 == 0 then
        ctx.server.spawn_entity("minecraft:allay", ctx.block.x + 1, ctx.block.y + 1, ctx.block.z)
        ctx.player.send_message("Um guardiao respondeu ao altar!")
    end
end

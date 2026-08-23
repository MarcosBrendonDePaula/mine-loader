-- Entrega da missao. Chamado apenas para guilda:quadro, porque o manifesto associou este
-- arquivo a esse bloco: nao e preciso checar qual bloco recebeu o clique.
local META = 8

return function(ctx)
    if ctx.player == nil then
        return
    end

    local nome = ctx.player.name
    ctx.state.jogadores = ctx.state.jogadores or {}
    local minha = ctx.state.jogadores[nome]

    if minha == nil or minha.extraidos < META then
        local tem = minha ~= nil and minha.extraidos or 0
        ctx.player.send_message("Ainda faltam " .. (META - tem) .. " minerios de ferro.")
        ctx.server.play_sound("minecraft:block.note_block.bass", ctx.block.x, ctx.block.y, ctx.block.z, 0.7, 0.7)
        -- Devolver false impede a acao padrao do jogo para este clique.
        return false
    end

    -- Paga a recompensa e zera o progresso da missao.
    minha.extraidos = 0
    local sobrou = ctx.player.give_item("guilda:emblema", 1)
    if sobrou > 0 then
        ctx.player.send_message("Seu inventario estava cheio; o emblema caiu no chao.")
    end

    -- Os dados ficam no bloco, entao cada quadro tem a propria contagem de entregas.
    local dados = ctx.server.get_block_data(ctx.block.x, ctx.block.y, ctx.block.z)
    dados.entregas = (dados.entregas or 0) + 1
    ctx.server.set_block_data(ctx.block.x, ctx.block.y, ctx.block.z, dados)

    -- A variante 1 usa a textura de quadro concluido.
    ctx.server.set_block_variant(ctx.block.id, ctx.block.x, ctx.block.y, ctx.block.z, 1)
    ctx.server.play_sound("minecraft:block.beacon.activate", ctx.block.x, ctx.block.y, ctx.block.z, 0.8, 1.2)
    ctx.server.spawn_particles("minecraft:happy_villager", ctx.block.x, ctx.block.y + 1, ctx.block.z, 16, 0.5)

    ctx.player.send_message("Emblema entregue! Este quadro ja pagou " .. dados.entregas .. " missao(oes).")

    -- A cada tres entregas o quadro chama um ajudante.
    if dados.entregas % 3 == 0 then
        ctx.server.spawn_entity("minecraft:allay", ctx.block.x, ctx.block.y + 1, ctx.block.z)
        ctx.player.send_message("Um ajudante da guilda apareceu.")
    end

    -- O quadro volta ao aspecto normal depois de cinco segundos.
    local x, y, z, id = ctx.block.x, ctx.block.y, ctx.block.z, ctx.block.id
    mod.after(100, function(depois)
        depois.server.set_block_variant(id, x, y, z, 0)
    end)
end

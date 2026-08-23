-- Clique com o emblema na mao: abre o quadro de status da guilda.
-- O menu usa a tela de container do proprio jogo, entao funciona em cliente vanilla.
return function(ctx)
    if ctx.player == nil then
        return
    end

    ctx.state.jogadores = ctx.state.jogadores or {}
    local minha = ctx.state.jogadores[ctx.player.name] or { extraidos = 0, concluidas = 0 }

    local itens = {}

    -- Uma barra de progresso feita de itens: ferro para o que ja foi, carvao para o que falta.
    for indice = 1, 8 do
        if indice <= minha.extraidos then
            itens[indice] = { item = "minecraft:iron_ingot", count = indice }
        else
            itens[indice] = "minecraft:coal"
        end
    end

    -- Slot 9: quantas missoes o jogador ja concluiu.
    itens[9] = { item = "guilda:emblema", count = math.max(1, math.min(64, minha.concluidas)) }

    ctx.player.open_menu("Guilda: " .. minha.concluidas .. " missao(oes)", 1, itens)
    ctx.player.send_action_bar("Progresso atual: " .. minha.extraidos .. "/8")
end

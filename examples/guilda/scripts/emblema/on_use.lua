-- Clique com o emblema na mao: abre o painel da guilda.
-- O painel e interativo: cada slot e um botao, e o clique volta para o mod.
local META = 8

--- Desenha o painel a partir do estado do jogador.
local function desenhar(ctx, nome)
    local minha = ctx.state.jogadores[nome] or { extraidos = 0, concluidas = 0 }
    local itens = {}

    -- Barra de progresso: ferro para o que ja foi, carvao para o que falta.
    for indice = 1, META do
        if indice <= minha.extraidos then
            itens[indice] = { item = "minecraft:iron_ingot", count = indice, label = "Progresso " .. indice }
        else
            itens[indice] = { item = "minecraft:coal", label = "Faltando" }
        end
    end

    -- Ultimo slot: botao que fecha o painel.
    itens[9] = { item = "minecraft:barrier", label = "Fechar" }
    return itens
end

-- Registra a logica da janela uma vez; o id vale para qualquer jogador que a abrir.
mod.menu("painel", function(ctx)
    if ctx.player == nil then
        return
    end

    if ctx.menu.slot == 8 then
        ctx.player.close_menu()
        return
    end

    -- Clicar na barra mostra o detalhe sem fechar a janela.
    local minha = ctx.state.jogadores[ctx.player.name] or { extraidos = 0, concluidas = 0 }
    ctx.player.send_action_bar("Missao " .. minha.extraidos .. "/" .. META
        .. " | concluidas: " .. minha.concluidas)

    -- Redesenhar mantem a janela aberta e atualiza o conteudo.
    ctx.player.update_menu(desenhar(ctx, ctx.player.name))
end)

return function(ctx)
    if ctx.player == nil then
        return
    end

    ctx.state.jogadores = ctx.state.jogadores or {}
    local minha = ctx.state.jogadores[ctx.player.name] or { extraidos = 0, concluidas = 0 }

    ctx.player.open_menu("painel", "Guilda: " .. minha.concluidas .. " missao(oes)", 1,
        desenhar(ctx, ctx.player.name))
end

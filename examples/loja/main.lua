-- Loja de Exemplo
--
-- Mostra como se cria uma interface: uma grade de itens onde cada slot e um botao.
-- Abra com /mod loja

-- O catalogo e so uma tabela Lua. Cada entrada vira um botao na janela.
local CATALOGO = {
    { item = "minecraft:iron_sword",  preco = 2, moeda = "minecraft:iron_ingot" },
    { item = "minecraft:golden_apple", preco = 4, moeda = "minecraft:gold_ingot" },
    { item = "minecraft:ender_pearl",  preco = 1, moeda = "minecraft:diamond" }
}

--- Monta a lista de itens que a janela exibe.
-- Desenhar e apenas devolver uma tabela: o loader cuida de mandar para a tela.
local function desenhar(ctx)
    local itens = {}

    for indice, oferta in ipairs(CATALOGO) do
        local tem = ctx.player.count_item(oferta.moeda)
        local pode = tem >= oferta.preco

        itens[indice] = {
            item = oferta.item,
            count = 1,
            -- O rotulo e como um botao ganha texto sem uma tela desenhada.
            label = (pode and "Comprar por " or "Faltam moedas: ") .. oferta.preco
        }
    end

    -- Slot 5: quanto o jogador tem de cada moeda, como indicador.
    itens[5] = {
        item = "minecraft:gold_ingot",
        count = math.max(1, math.min(64, ctx.player.count_item("minecraft:gold_ingot"))),
        label = "Seu ouro"
    }

    -- Slot 9: botao de fechar.
    itens[9] = { item = "minecraft:barrier", label = "Fechar" }
    return itens
end

-- A logica da janela e registrada uma vez e vale para qualquer jogador que a abrir.
mod.menu("balcao", function(ctx)
    if ctx.player == nil then
        return
    end

    -- Os slots contam a partir de zero; o catalogo, a partir de um.
    local slot = ctx.menu.slot

    if slot == 8 then
        ctx.player.close_menu()
        return
    end

    local oferta = CATALOGO[slot + 1]
    if oferta == nil then
        -- Clique em um slot sem oferta: apenas redesenha, sem fazer nada.
        ctx.player.update_menu(desenhar(ctx))
        return
    end

    local tem = ctx.player.count_item(oferta.moeda)
    if tem < oferta.preco then
        ctx.player.send_action_bar("Faltam " .. (oferta.preco - tem) .. " para comprar.")
        return
    end

    ctx.player.take_item(oferta.moeda, oferta.preco)
    local sobrou = ctx.player.give_item(oferta.item, 1)

    if sobrou > 0 then
        ctx.player.send_message("Inventario cheio: a compra caiu no chao.")
    else
        ctx.player.send_message("Comprado: " .. oferta.item)
    end

    ctx.state.vendas = (ctx.state.vendas or 0) + 1

    -- Redesenhar mantem a janela aberta e atualiza o saldo mostrado.
    ctx.player.update_menu(desenhar(ctx))
end)

mod.command("loja", function(ctx)
    if ctx.player == nil then
        ctx.log.info("A loja precisa de um jogador.")
        return
    end

    if ctx.subcommand == "vendas" then
        ctx.player.send_message("Vendas desta loja: " .. (ctx.state.vendas or 0))
        return
    end

    ctx.player.open_menu("balcao", "Loja", 1, desenhar(ctx))
end)

return {}

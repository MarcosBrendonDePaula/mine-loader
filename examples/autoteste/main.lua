-- Exercita as APIs dentro do jogo e diz o que passou.
--
-- Os testes do repositorio rodam contra um dublê, e por isso nao alcancam o que so aparece com o
-- jogo de verdade: um registro com mil e trezentos itens, uma tabela de loot carregada de um
-- datapack, o inventario de um bloco de outro mod. Cinco defeitos desta sessao apareceram assim, e
-- nenhum deles era alcancavel pela suite.
--
-- Este mod fecha essa distancia. Um comando executa a bateria e escreve OK ou FALHOU por
-- verificacao, com o motivo -- entao quem le o log sabe o que quebrou sem estar no jogo.
--
--   /mod autoteste          roda tudo
--   /mod autoteste <nome>   roda so uma parte

local resultados = {}

local function verificar(nome, funcao)
    local ok, erro = pcall(funcao)
    resultados[#resultados + 1] = { nome = nome, ok = ok, erro = erro }
end

-- Uma verificacao falha com mensagem, e nao com silencio: o motivo e o que faz o log servir.
local function exigir(condicao, motivo)
    if not condicao then error(motivo, 2) end
end

local TESTES = {}

TESTES.itens = function(ctx)
    local todos = ctx.server.items({ limit = 4096 })
    exigir(#todos > 500, "o registro deveria ter mais de 500 itens, tem " .. #todos)

    local ferro = ctx.server.items({ namespace = "minecraft", contains = "iron_ingot" })
    exigir(#ferro == 1, "iron_ingot deveria dar um resultado, deu " .. #ferro)
    exigir(ferro[1] == "minecraft:iron_ingot", "veio " .. tostring(ferro[1]))
end

TESTES.receitas = function(ctx)
    local produz = ctx.server.recipes_for("minecraft:iron_ingot", 16)
    exigir(#produz > 0, "iron_ingot deveria ter receita")

    local tem_fornalha = false
    for _, receita in ipairs(produz) do
        exigir(receita.output.item == "minecraft:iron_ingot",
               "uma receita de iron_ingot produziu " .. receita.output.item)
        if receita.type == "minecraft:smelting" then tem_fornalha = true end
    end
    exigir(tem_fornalha, "iron_ingot deveria ter receita de fornalha")

    local usa = ctx.server.recipes_using("minecraft:iron_ingot", 16)
    exigir(#usa > 0, "iron_ingot deveria ser usado em alguma receita")
end

TESTES.drops = function(ctx)
    -- Diz o que veio, e nao so que veio errado: sem isso a falha nao aponta a causa.
    ctx.log.info("AUTOTESTE drops_of(iron_ore) = "
                 .. table.concat(ctx.server.drops_of("minecraft:iron_ore", 8), ","))
    ctx.log.info("AUTOTESTE dropped_by(raw_iron) = "
                 .. table.concat(ctx.server.dropped_by("minecraft:raw_iron", 8), ","))
    ctx.log.info("AUTOTESTE dropped_by(white_wool) = "
                 .. table.concat(ctx.server.dropped_by("minecraft:white_wool", 8), ","))

    local do_minerio = ctx.server.drops_of("minecraft:iron_ore", 8)
    exigir(#do_minerio > 0, "iron_ore deveria derrubar algo")

    -- A la vem da ovelha por tabela de loot, e foi o caso que expos o indice errado.
    local de_onde = ctx.server.dropped_by("minecraft:white_wool", 16)
    local tem_ovelha = false
    for _, fonte in ipairs(de_onde) do
        if fonte == "minecraft:sheep" then tem_ovelha = true end
    end
    exigir(tem_ovelha, "la branca deveria vir da ovelha, veio de " .. table.concat(de_onde, ","))
end

TESTES.processos = function(ctx)
    -- Depende do exemplo processos_vanilla estar instalado: sem ele, nao ha o que listar.
    local tosquia = ctx.server.processes({ produces = "minecraft:white_wool" })
    exigir(#tosquia > 0, "nenhum processo produz la branca (processos_vanilla instalado?)")
    exigir(tosquia[1].by == "minecraft:sheep", "o processo deveria ser executado pela ovelha")
end

TESTES.container = function(ctx)
    -- Coordenadas fixas, e nao a posicao do jogador: este comando tambem roda pelo console do
    -- servidor, onde nao ha jogador nenhum. Um teste que so funciona com alguem no jogo nao serve
    -- para verificar sozinho, que e a razao de ele existir.
    local x, y, z = 0, 100, 0

    -- Poe um bau debaixo do jogador, mexe nele e desfaz. Sem escrever no mundo nao da para
    -- verificar a camada de capacidades de verdade.
    local antes = ctx.server.get_block(x, y, z)
    ctx.server.set_block("minecraft:chest", x, y, z)

    local capacidades = ctx.server.capabilities_at(x, y, z)
    exigir(#capacidades > 0, "um bau deveria oferecer alguma capacidade")
    exigir(capacidades[1] == "items", "a capacidade deveria ser items, veio " .. capacidades[1])

    local sobrou = ctx.server.insert_into(x, y, z, "minecraft:coal", 8)
    exigir(sobrou == 0, "o bau vazio deveria aceitar 8 carvoes, sobraram " .. sobrou)

    local conteudo = ctx.server.container_at(x, y, z)
    exigir(#conteudo == 1, "o bau deveria ter um slot ocupado, tem " .. #conteudo)
    exigir(conteudo[1].item == "minecraft:coal", "veio " .. conteudo[1].item)
    exigir(conteudo[1].count == 8, "deveria haver 8, ha " .. conteudo[1].count)

    local pegou = ctx.server.extract_from(x, y, z, "minecraft:coal", 3)
    exigir(pegou == 3, "deveria tirar 3, tirou " .. pegou)

    ctx.server.set_block(antes, x, y, z)
end

-- Ferramenta declarada precisa virar ferramenta de verdade, e nao um item com numeros.
--
-- O que separa as duas coisas nao aparece no manifesto: e o item ser da classe que o jogo usa para
-- minerar. Aqui a verificacao possivel pelo servidor e que o item existe registrado; o resto --
-- velocidade, nivel de colheita -- so o cliente com o bloco na frente responde.
TESTES.ferramentas = function(ctx)
    local achados = ctx.server.items({ namespace = "ferraria", limit = 32 })
    exigir(#achados == 5, "a ferraria deveria registrar 5 pecas, registrou " .. #achados)

    local esperados = {
        "ferraria:picareta_de_rubi", "ferraria:espada_de_rubi", "ferraria:machado_de_rubi",
        "ferraria:elmo_de_rubi", "ferraria:peitoral_de_rubi"
    }
    for _, id in ipairs(esperados) do
        local existe = false
        for _, achado in ipairs(achados) do
            if achado == id then existe = true end
        end
        exigir(existe, id .. " nao foi registrado")
    end
end

TESTES.mundo = function(ctx)
    local nome = ctx.server.world_name()
    exigir(nome ~= "", "world_name nao deveria ser vazio")

    local hora = ctx.server.time_of_day()
    exigir(hora >= 0 and hora < 24000, "hora fora da faixa: " .. hora)
end

mod.command("autoteste", function(ctx)
    resultados = {}
    local so = ctx.subcommand

    local nomes = {}
    for nome in pairs(TESTES) do nomes[#nomes + 1] = nome end
    table.sort(nomes)

    for _, nome in ipairs(nomes) do
        if so == "" or so == nil or so == nome then
            verificar(nome, function() TESTES[nome](ctx) end)
        end
    end

    local passaram = 0
    for _, resultado in ipairs(resultados) do
        if resultado.ok then
            passaram = passaram + 1
            ctx.log.info("AUTOTESTE OK      " .. resultado.nome)
        else
            ctx.log.warn("AUTOTESTE FALHOU  " .. resultado.nome .. ": " .. tostring(resultado.erro))
        end
    end

    local resumo = "AUTOTESTE " .. passaram .. "/" .. #resultados .. " passaram"
    ctx.log.info(resumo)

    -- Pelo console nao ha jogador para avisar; o log ja disse tudo.
    if ctx.player then ctx.player.send_message(resumo) end
end)

return {}

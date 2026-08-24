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

-- Um bloco que o proprio manifesto declarou com inventario, e nao um bau do jogo.
--
-- E o caso que o TESTES.container nao alcanca: la o inventario e do Minecraft, aqui e o que o
-- loader constroi a partir de "inventory" no manifesto. As duas plataformas chegam nele por
-- caminhos diferentes -- SidedInventory no Fabric, capability mais Container no NeoForge -- e este
-- teste e o que garante que os dois caminhos terminam no mesmo lugar.
TESTES.inventario_declarado = function(ctx)
    local x, y, z = 0, 100, 0
    local antes = ctx.server.get_block(x, y, z)

    ctx.server.set_block("crystal_world:cofre", x, y, z)

    local capacidades = ctx.server.capabilities_at(x, y, z)
    exigir(#capacidades > 0, "um bloco com inventario declarado deveria oferecer capacidade")

    local sobrou = ctx.server.insert_into(x, y, z, "minecraft:emerald", 7)
    exigir(sobrou == 0, "o cofre vazio deveria aceitar 7 esmeraldas, sobraram " .. sobrou)

    local conteudo = ctx.server.container_at(x, y, z)
    exigir(#conteudo == 1, "o cofre deveria ter um slot ocupado, tem " .. #conteudo)
    exigir(conteudo[1].count == 7, "deveria haver 7, ha " .. conteudo[1].count)

    -- O cofre do exemplo declara allow_extract falso, e ainda assim isto tira: a permissao vale
    -- para funil e tubo, que acessam por um lado, e nao para o mod. Uma fornalha que recusa saida
    -- automatica precisa tirar o proprio minerio para processar, e este e o unico caminho que ela
    -- tem -- bloquear aqui transformaria a declaracao numa armadilha para quem a escreveu.
    local pegou = ctx.server.extract_from(x, y, z, "minecraft:emerald", 3)
    exigir(pegou == 3, "o mod deveria alcancar o proprio inventario, mas saiu " .. pegou)

    -- Sobram 4 depois da retirada, e e isso que deve cair adiante.
    local restante = ctx.server.container_at(x, y, z)
    exigir(#restante == 1 and restante[1].count == 4,
        "deveriam sobrar 4 esmeraldas apos a retirada")

    -- Remover o bloco derrama o conteudo. Sem isto, quebrar uma maquina cheia apaga o que estava
    -- dentro em silencio, que e o pior desfecho possivel.
    local chao_antes = 0
    for _, entidade in ipairs(ctx.server.entities_near(x, y, z, 6)) do
        if entidade.type == "minecraft:item" then chao_antes = chao_antes + 1 end
    end

    ctx.server.set_block("minecraft:air", x, y, z)

    local chao_depois = 0
    for _, entidade in ipairs(ctx.server.entities_near(x, y, z, 6)) do
        if entidade.type == "minecraft:item" then chao_depois = chao_depois + 1 end
    end
    exigir(chao_depois > chao_antes,
        "o conteudo deveria cair ao remover o bloco; havia " .. chao_antes
        .. " item(ns) no chao e ficou " .. chao_depois)

    ctx.server.set_block(antes, x, y, z)
end

-- Entidade e item nascem com o que o mod declarou, e nao genericos.
--
-- Esta e a diferenca entre invocar "um cavalo" e invocar o cavalo do chefe. O que se verifica aqui
-- e que a declaracao atravessa o nucleo e chega ao jogo: se um adaptador ignorasse a tabela, a
-- entidade nasceria sem nome e este teste diria.
TESTES.dados_declarados = function(ctx)
    local x, y, z = 0, 100, 0

    local uuid = ctx.server.spawn_entity("minecraft:zombie", x, y + 1, z, {
        name = "Chefe de Teste",
        name_visible = true,
        persistent = true,
        glowing = true,
        health = 40,
        yaw = 90,
        attributes = { ["minecraft:generic.movement_speed"] = 0.35 },
        effects = {{ id = "minecraft:strength", duration = 200, amplifier = 1 }},
        equipment = {
            main_hand = { item = "minecraft:diamond_sword",
                          name = "Lamina de Teste",
                          enchantments = { ["minecraft:sharpness"] = 2 },
                          drop_chance = 1.0 },
            head = "minecraft:diamond_helmet"
        }
    })
    exigir(uuid ~= nil and uuid ~= "", "spawn_entity deveria devolver o uuid")

    -- entities_near le o mundo de verdade: se o bicho nao nasceu, nao esta aqui.
    local achou = false
    for _, entidade in ipairs(ctx.server.entities_near(x, y + 1, z, 6)) do
        if entidade.type == "minecraft:zombie" then achou = true end
    end
    exigir(achou, "o mob declarado deveria estar no mundo")

    ctx.server.remove_entity(uuid)

    -- O item so pode ser conferido pelo que o inventario responde, porque o contrato de leitura
    -- devolve identificador e quantidade -- nao o nome nem os encantamentos.
    local sobrou = ctx.player and ctx.player.give_item("minecraft:diamond_sword", 1, {
        name = "Espada de Teste",
        lore = {"Item de verificacao"},
        unbreakable = true,
        enchantments = { ["minecraft:sharpness"] = 3 }
    })
    if ctx.player then
        exigir(sobrou == 0, "a espada deveria caber no inventario, sobrou " .. tostring(sobrou))
    end
end

-- Os pares que faltavam: ler sem escrever, ferir sem curar, item sem bloco.
--
-- Cada verificacao aqui existe porque a operacao tinha metade. O que se confere e o par completo,
-- e nao a operacao nova sozinha -- ler o tempo depois de escrever prova as duas de uma vez.
TESTES.pares_da_api = function(ctx)
    -- Tempo: lia e nao escrevia.
    local antes = ctx.server.time_of_day()
    ctx.server.set_time_of_day(6000)
    local depois = ctx.server.time_of_day()
    exigir(depois >= 5990 and depois <= 6010,
        "o tempo deveria ter virado 6000, veio " .. depois)
    ctx.server.set_time_of_day(antes)

    -- Clima: nao existia em nenhuma direcao.
    ctx.server.set_weather("clear", 6000)
    exigir(ctx.server.weather() == "clear",
        "o clima deveria estar limpo, veio " .. ctx.server.weather())

    -- Altura do terreno: sem isso, um mod que constroi precisa adivinhar onde e o chao.
    local topo = ctx.server.top_y(0, 0)
    exigir(type(topo) == "number", "top_y deveria devolver um numero")

    -- Listar blocos e tipos de entidade: so itens eram listaveis.
    local blocos = ctx.server.blocks({ namespace = "minecraft", contains = "stone", limit = 64 })
    exigir(#blocos > 0, "deveria haver blocos de pedra no registro")

    local tipos = ctx.server.entity_types({ namespace = "minecraft", contains = "zombie" })
    exigir(#tipos > 0, "deveria haver tipos de zumbi no registro")

    -- Curar e aplicar dados a uma entidade que ja existe.
    local uuid = ctx.server.spawn_entity("minecraft:zombie", 0, 100, 0, { health = 20 })
    ctx.server.damage_entity(uuid, 5)

    local info = ctx.server.entity_info(uuid)
    exigir(info ~= nil, "entity_info deveria responder pelo uuid recem-criado")
    exigir(info.type == "minecraft:zombie", "veio " .. tostring(info.type))
    exigir(info.health < info.max_health, "o zumbi deveria estar ferido")

    ctx.server.heal_entity(uuid, 10)
    local curado = ctx.server.entity_info(uuid)
    exigir(curado.health > info.health,
        "a cura deveria ter subido a vida: " .. info.health .. " -> " .. curado.health)

    -- Aplicar dados depois do nascimento: antes so valia no instante em que a entidade nascia.
    exigir(ctx.server.apply_to_entity(uuid, { name = "Renomeado", glowing = true }),
        "apply_to_entity deveria achar a entidade")
    exigir(ctx.server.entity_info(uuid).name == "Renomeado",
        "o nome aplicado depois deveria valer")

    ctx.server.remove_entity(uuid)

    -- Quebrar bloco com drop, em vez de escrever ar por cima.
    ctx.server.set_block("minecraft:stone", 0, 100, 0)
    exigir(ctx.server.break_block(0, 100, 0, false), "deveria haver bloco para quebrar")
    exigir(ctx.server.get_block(0, 100, 0) == "minecraft:air", "o bloco deveria ter sumido")
    exigir(not ctx.server.break_block(0, 100, 0, false),
        "quebrar o ar deveria responder que nao havia nada")
end

-- O jogador: lia vida e nao escrevia, e nao recebia efeito nenhum.
--
-- Roda so quando ha alguem no jogo. Pelo console nao ha jogador, e uma verificacao que finge ter um
-- nao verifica coisa alguma.
TESTES.jogador = function(ctx)
    if ctx.player == nil then
        ctx.log.info("[AUTOTESTE] jogador: pulado, nao ha jogador nesta execucao")
        return
    end

    local vida = ctx.player.health()
    exigir(vida.current > 0, "o jogador deveria estar vivo")

    local comida = ctx.player.food()
    exigir(comida.level >= 0 and comida.level <= 20, "fome fora da faixa: " .. comida.level)

    local xp = ctx.player.experience()
    exigir(xp.level >= 0, "nivel de experiencia negativo")

    local modo = ctx.player.game_mode()
    exigir(modo == "survival" or modo == "creative" or modo == "adventure" or modo == "spectator",
        "modo de jogo desconhecido: " .. modo)

    exigir(ctx.player.dimension() ~= nil and ctx.player.dimension() ~= "",
        "o jogador deveria estar em alguma dimensao")

    -- Efeito no jogador era a assimetria mais estranha: valia para entidade criada por mod e nao
    -- para quem esta jogando, que e o alvo mais provavel de um efeito.
    ctx.player.apply_effect("minecraft:speed", 40, 0)
    ctx.player.clear_effects()

    ctx.player.show_title("Autoteste", "verificando a API", 5, 20, 5)
    ctx.player.play_sound_to("minecraft:block.note_block.bell", 0.4, 1.5)

    local carregado = ctx.player.inventory()
    exigir(type(carregado) == "table", "inventory deveria devolver uma tabela")
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


-- Eventos globais e agendador.
--
-- Estes dois nao apareciam em teste nenhum, e por isso passaram muito tempo mortos numa das
-- plataformas: o adaptador NeoForge nao chamava triggerAll nem advanceScheduler, entao
-- loader_ready, player_joined, tick e mod.after simplesmente nao aconteciam la -- sem erro, sem
-- aviso, e com a matriz de compatibilidade afirmando o contrario. Um mod que reagia a entrada de
-- jogador nao reagia, e nada acusava.
--
-- A verificacao mora aqui, e nao na suite do nucleo, porque o dublê dispara os eventos por
-- construcao: quem esquece de liga-los e a plataforma, e so o jogo de verdade percebe.

local function on_loader_ready(ctx)
    ctx.state.loader_ready = true
    -- Uma tarefa agendada na carga prova o relogio interno: se o agendador nao avanca, ela nunca
    -- vence, e a marca abaixo nunca aparece.
    mod.after(20, function(depois)
        depois.state.agendador_rodou = true
    end)
end

local function on_player_joined(ctx)
    ctx.state.player_joined = true
end

local function on_tick(ctx)
    ctx.state.ticks = (ctx.state.ticks or 0) + 1
end

TESTES.eventos_globais = function(ctx)
    exigir(ctx.state.loader_ready, "loader_ready nao disparou nesta plataforma")
    exigir((ctx.state.ticks or 0) > 0,
           "o evento tick nao disparou; ticks contados: " .. tostring(ctx.state.ticks))
    -- player_joined so vale quando ha jogador: pelo console dirigivel ninguem entrou.
    if ctx.player then
        exigir(ctx.state.player_joined, "player_joined nao disparou nesta plataforma")
    end
end

TESTES.agendador = function(ctx)
    exigir(ctx.state.agendador_rodou,
           "mod.after agendado na carga nunca executou; o agendador nao avanca nesta plataforma")
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

return {
    on_loader_ready = on_loader_ready,
    on_player_joined = on_player_joined,
    on_tick = on_tick,
}

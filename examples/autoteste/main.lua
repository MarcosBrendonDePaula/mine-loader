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

-- A especie declarada por um mod, contra o jogo de verdade.
--
-- Os GameTests ja perguntam se o tipo entrou no registro. O que so aqui aparece e o bicho nascendo
-- num mundo carregado: se o modelo, os atributos ou a heranca estiverem tortos, o tipo continua
-- registrado e a criatura e que nao funciona.
TESTES.especie_declarada = function(ctx)
    local x, y, z = 0, 100, 0

    -- O bestiario do loader, e nao o registro do jogo: a diferenca e o que permite um mod saber o
    -- que outro declarou sem varrer milhares de tipos.
    local declaradas = ctx.server.declared_entities()
    exigir(#declaradas >= 2, "deveria haver ao menos duas especies declaradas, ha " .. #declaradas)

    local tem_guardiao, tem_elite = false, false
    for _, id in ipairs(declaradas) do
        if id == "crystal_world:crystal_guardian" then tem_guardiao = true end
        if id == "bestiario:elite_guardian" then tem_elite = true end
    end
    exigir(tem_guardiao, "crystal_world:crystal_guardian deveria estar no bestiario")
    exigir(tem_elite, "bestiario:elite_guardian deveria estar no bestiario")

    -- Uma especie do jogo nao e declarada por mod nenhum. "Nao existe" e "existe e nao e daqui"
    -- levam a decisoes diferentes em quem monta um bestiario sobre o de outro.
    exigir(ctx.server.entity_definition("minecraft:zombie") == nil,
           "uma especie do jogo nao deveria aparecer como declarada")

    local guardiao = ctx.server.entity_definition("crystal_world:crystal_guardian")
    exigir(guardiao ~= nil, "a definicao do guardiao deveria ser legivel")
    exigir(guardiao.base == "minecraft:iron_golem",
           "a base do guardiao deveria ser o golem, veio " .. tostring(guardiao.base))
    exigir(guardiao.health == 60, "o guardiao deveria declarar 60 de vida, veio "
           .. tostring(guardiao.health))

    -- A heranca entre mods: o elite nao conhece o golem, so o guardiao, e mesmo assim registra com
    -- a base do ancestral -- que e de onde vem modelo e comportamento.
    local elite = ctx.server.entity_definition("bestiario:elite_guardian")
    exigir(elite ~= nil, "a definicao do elite deveria ser legivel")
    exigir(elite.base == "minecraft:iron_golem",
           "a base efetiva do elite deveria ser a do ancestral, veio " .. tostring(elite.base))
    exigir(elite.health == 120, "o elite deveria declarar 120 de vida, veio "
           .. tostring(elite.health))
    exigir(guardiao.fire_immune == true, "o guardiao declara imunidade a fogo")

    -- E agora o que nenhum dublê alcanca: fazer nascer.
    local uuid = ctx.server.spawn_entity("crystal_world:crystal_guardian", x, y + 1, z)
    exigir(uuid ~= nil and uuid ~= "", "a especie declarada deveria nascer")

    local info = ctx.server.entity_info(uuid)
    exigir(info ~= nil, "entity_info deveria responder sobre a especie declarada")
    exigir(info.type == "crystal_world:crystal_guardian",
           "a criatura deveria se declarar como a especie do mod, veio " .. tostring(info.type))
    -- O numero sai da declaracao, e nao da base: o golem tem cem.
    exigir(info.max_health == 60,
           "deveria nascer com 60 de vida maxima, veio " .. tostring(info.max_health))

    local uuid_elite = ctx.server.spawn_entity("bestiario:elite_guardian", x + 2, y + 1, z)
    local info_elite = ctx.server.entity_info(uuid_elite)
    exigir(info_elite.max_health == 120,
           "o elite deveria nascer com 120 de vida, veio " .. tostring(info_elite.max_health))

    -- entities_near le o mundo de verdade: se o bicho nao nasceu, nao esta aqui.
    local achou = false
    for _, entidade in ipairs(ctx.server.entities_near(x, y + 1, z, 8)) do
        if entidade.type == "crystal_world:crystal_guardian" then achou = true end
    end
    exigir(achou, "a especie declarada deveria estar no mundo")

    ctx.server.remove_entity(uuid)
    ctx.server.remove_entity(uuid_elite)

    -- O ovo de criacao e um item como outro qualquer, e precisa existir no registro.
    --
    -- Confere o ovo do guardiao pelo nome, e nao a contagem: contar quantos ovos o mod tem faz o
    -- caso quebrar toda vez que alguem acrescenta uma especie ao exemplo -- que foi exatamente o
    -- que aconteceu, e a falha apontava para o loader em vez de para o teste.
    local ovos = ctx.server.items({ namespace = "crystal_world", contains = "spawn_egg" })
    local tem_do_guardiao = false
    for _, id in ipairs(ovos) do
        if id == "crystal_world:crystal_guardian_spawn_egg" then tem_do_guardiao = true end
    end
    exigir(tem_do_guardiao,
           "o ovo do guardiao deveria existir; achei " .. table.concat(ovos, ", "))
end

-- Bioma e luz: as duas leituras que o nascimento natural precisa.
--
-- Elas existiam como lacuna ha tempo: um mod que gera algo condicionalmente tinha que adivinhar
-- onde estava pela altura ou pelo bloco de baixo, e as duas coisas mentem -- areia tambem existe
-- em praia, e altura nao diz bioma.
TESTES.bioma_e_luz = function(ctx)
    local x, y, z = 0, 100, 0

    local bioma = ctx.server.biome_at(x, y, z)
    exigir(type(bioma) == "string" and bioma:find(":"),
           "biome_at deveria devolver um id com namespace, veio " .. tostring(bioma))

    local luz = ctx.server.light_at(x, y, z)
    exigir(type(luz) == "table", "light_at deveria devolver uma tabela")
    exigir(luz.block >= 0 and luz.block <= 15, "luz de bloco fora da faixa: " .. tostring(luz.block))
    exigir(luz.sky >= 0 and luz.sky <= 15, "luz do ceu fora da faixa: " .. tostring(luz.sky))
    exigir(luz.total == math.max(luz.block, luz.sky), "total deveria ser o maior dos dois")

    -- A distincao entre as duas e o que decide se um monstro nasce ali: o jogo olha a luz de
    -- bloco. Um lugar iluminado so pelo sol tem quinze de total ao meio-dia e continua escuro a
    -- noite -- um mod que olhasse o total erraria todo dia.
    exigir(type(luz.dark_enough_for_monster) == "boolean",
           "a resposta pronta sobre escuridao deveria ser booleana")
    exigir(luz.dark_enough_for_monster == (luz.block == 0),
           "escuro para monstro deveria seguir a luz de bloco, e nao o total")

    -- Uma posicao no fundo do mundo nao recebe luz do ceu.
    local fundo = ctx.server.light_at(x, -60, z)
    exigir(fundo.sky == 0, "no fundo do mundo nao deveria haver luz do ceu, veio " .. fundo.sky)
end

-- A regra de nascimento natural, lida de volta pela API.
TESTES.regra_de_nascimento = function(ctx)
    local colossal = ctx.server.entity_definition("bestiario:guardiao_colossal")
    exigir(colossal ~= nil, "o guardiao colossal deveria estar declarado")
    exigir(colossal.spawn ~= nil, "ele deveria declarar nascimento natural")

    -- A regra volta inteira, e nao so um "nasce sozinho": um mod que monta um guia do bestiario
    -- precisa dizer onde procurar.
    exigir(#colossal.spawn.biomes > 0, "deveria declarar ao menos um bioma")
    exigir(colossal.spawn.weight == 8, "o peso deveria ser 8, veio " .. colossal.spawn.weight)
    exigir(colossal.spawn.max_light == 7,
           "a faixa de luz deveria vir junto, veio " .. tostring(colossal.spawn.max_light))
    exigir(colossal.spawn.min_y == 60, "a altura minima deveria vir junto")

    -- Uma especie sem regra nao inventa uma: sem isso um mod nao teria como distinguir "nasce em
    -- todo lugar" de "nao nasce sozinho".
    local guardiao = ctx.server.entity_definition("crystal_world:crystal_guardian")
    exigir(guardiao.spawn == nil, "o guardiao de cristal nao declara nascimento natural")
end

-- Eventos de criatura e movimentacao.
--
-- Eram dezessete eventos e nenhum de entidade: um mod de combate nao tinha onde se prender, e a
-- unica saida era varrer o mundo a cada tique perguntando a vida de todo mundo -- caro, e ainda
-- assim cego para o que acontece entre dois tiques.
--
-- O caso e o mesmo nas duas plataformas de proposito. Cada uma nomeia os eventos do seu jeito, e o
-- que nao pode divergir e quando eles disparam e com que dados.
TESTES.eventos_de_entidade = function(ctx)
    local x, y, z = 0, 100, 0
    ctx.state.vistos = { nasceu = 0, apanhou = 0, morreu = 0 }

    local uuid = ctx.server.spawn_entity("minecraft:zombie", x, y + 1, z, { health = 20 })
    exigir(uuid ~= nil and uuid ~= "", "o zumbi deveria nascer")

    -- Nascer dispara ao entrar no mundo, e o contador so sobe se o evento chegou de verdade.
    exigir(ctx.state.vistos.nasceu > 0,
           "entity_spawned deveria ter disparado, veio " .. ctx.state.vistos.nasceu)

    ctx.server.damage_entity(uuid, 3.0)
    exigir(ctx.state.vistos.apanhou > 0,
           "entity_damaged deveria ter disparado, veio " .. ctx.state.vistos.apanhou)

    -- A foto e do instante do evento: no momento da morte a vida ja e zero, e um script que
    -- perguntasse ao mundo depois chegaria tarde demais.
    exigir(ctx.state.ultimo_dano ~= nil, "o evento deveria ter trazido os dados do bicho")
    exigir(ctx.state.ultimo_dano.id == "minecraft:zombie",
           "o tipo deveria vir no evento, veio " .. tostring(ctx.state.ultimo_dano.id))
    exigir(ctx.state.ultimo_dano.amount > 0,
           "o dano deveria vir no evento, veio " .. tostring(ctx.state.ultimo_dano.amount))

    ctx.server.damage_entity(uuid, 100.0)
    exigir(ctx.state.vistos.morreu > 0,
           "entity_died deveria ter disparado, veio " .. ctx.state.vistos.morreu)
end

TESTES.mover_entidade = function(ctx)
    local x, y, z = 0, 100, 0

    local uuid = ctx.server.spawn_entity("minecraft:pig", x, y + 1, z, { no_ai = true })
    exigir(uuid ~= nil and uuid ~= "", "o porco deveria nascer")

    -- Teleportar: a posicao muda de imediato, e da para conferir lendo de volta.
    exigir(ctx.server.teleport_entity(uuid, x + 8, y + 1, z + 8), "teleport_entity deveria achar")
    local info = ctx.server.entity_info(uuid)
    exigir(math.abs(info.x - (x + 8)) < 0.5,
           "o bicho deveria ter ido para x+8, esta em " .. tostring(info.x))

    -- Empurrar nao teleporta: o jogo continua resolvendo colisao e queda, entao o que se confere e
    -- que a chamada encontrou a entidade, e nao uma posicao exata.
    exigir(ctx.server.push_entity(uuid, 0.4, 0.2, 0), "push_entity deveria achar a entidade")

    -- E um uuid que nao existe responde falso, em vez de estourar.
    exigir(ctx.server.teleport_entity("00000000-0000-0000-0000-000000000000", 0, 100, 0) == false,
           "teleportar o que nao existe deveria devolver falso")

    ctx.server.remove_entity(uuid)
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
-- A camada de interface, que so existe quando ha um cliente do outro lado.
--
-- Os GameTests sobem um servidor headless e o servidor dirigivel tambem: os dois exercitam o
-- registro, o mundo e os scripts, e nenhum dos dois tem tela. Tudo que sai daqui para o cliente --
-- tela desenhada, HUD, sobreposicao, tamanho da janela -- e invisivel para eles.
--
-- Estes casos so dizem algo rodando dentro do jogo, com alguem logado. Pelo console eles se pulam
-- em vez de falhar, porque "nao ha cliente" nao e defeito -- e a situacao.

--- Um jogador com cliente do loader, ou nil quando nao da para verificar interface.
local function jogador_com_tela(ctx)
    if ctx.player == nil then return nil end
    if not ctx.player.supports_screens() then return nil end
    return ctx.player
end

TESTES.cliente_presente = function(ctx)
    if ctx.player == nil then
        ctx.log.info("[AUTOTESTE] cliente_presente: pulado, execucao sem jogador")
        return
    end

    -- supports_screens e a pergunta que separa "tem cliente do loader" de "nao tem". Um mod que
    -- nao pergunta antes promete uma janela que nunca aparece.
    local tem = ctx.player.supports_screens()
    exigir(type(tem) == "boolean", "supports_screens deveria devolver booleano, veio " .. type(tem))

    if not tem then
        ctx.log.warn("[AUTOTESTE] cliente_presente: o cliente nao anunciou suporte a telas")
        return
    end

    -- O tamanho vem do cliente pelo canal de rede: se ele chegou, o canal funciona nos dois
    -- sentidos, que e o que nenhum teste de servidor consegue afirmar.
    local tela = ctx.player.screen_size()
    exigir(tela.width > 0 and tela.height > 0,
        "screen_size deveria vir do cliente, veio " .. tela.width .. "x" .. tela.height)
    exigir(tela.width <= 1024 and tela.height <= 1024,
        "screen_size fora da faixa: " .. tela.width .. "x" .. tela.height)

    ctx.log.info("[AUTOTESTE] janela do cliente: " .. tela.width .. "x" .. tela.height)
end

TESTES.tela_desenhada = function(ctx)
    local jogador = jogador_com_tela(ctx)
    if jogador == nil then
        ctx.log.info("[AUTOTESTE] tela_desenhada: pulado, sem cliente")
        return
    end

    local descricao = {
        title = "Autoteste",
        width = 200,
        height = 100,
        elements = {
            { type = "panel", x = 0, y = 0, w = 200, h = 100, color = "#101018E0" },
            { type = "label", x = 10, y = 10, text = "Autoteste", color = "#FFD966" },
            { type = "item", x = 10, y = 30, item = "minecraft:diamond", count = 3 },
            { type = "progress", x = 10, y = 52, w = 180, h = 8, progress = 0.5 },
            { type = "button", id = "fechar", x = 10, y = 68, w = 180, h = 20, text = "Fechar" }
        }
    }

    exigir(jogador.open_screen("prova", descricao), "open_screen deveria abrir com cliente presente")
    exigir(jogador.update_screen(descricao), "update_screen deveria valer com a tela aberta")
    jogador.close_screen()

    -- Depois de fechada, redesenhar precisa recusar: aceitar em silencio faria um mod acreditar
    -- que esta desenhando numa tela que ninguem ve.
    exigir(jogador.update_screen(descricao) == false,
        "update_screen deveria recusar com a tela fechada")
end

TESTES.hud_e_sobreposicao = function(ctx)
    local jogador = jogador_com_tela(ctx)
    if jogador == nil then
        ctx.log.info("[AUTOTESTE] hud_e_sobreposicao: pulado, sem cliente")
        return
    end

    -- set_hud passou a responder se o HUD chegou, como open_screen e set_overlay ja faziam.
    -- Era a unica das tres a nao devolver nada, e este teste foi quem mostrou isso.
    exigir(jogador.set_hud({
        { type = "panel", x = 2, y = 2, w = 90, h = 14, color = "#00000080" },
        { type = "label", x = 6, y = 5, text = "Autoteste OK", color = "#FFD966" }
    }) == true, "set_hud deveria devolver true com cliente presente")

    -- A sobreposicao entra sobre uma tela do jogo, e o alvo vem do vocabulario fechado do nucleo.
    jogador.set_overlay("marca", {
        target = "inventory",
        elements = {
            { type = "label", x = 4, y = 4, text = "autoteste", color = "#9090A0" }
        }
    })
    jogador.clear_overlay("marca")

    -- Um alvo fora do vocabulario precisa ser recusado no servidor, e nao ignorado no cliente: o
    -- erro tem que chegar a quem escreveu o mod.
    local ok = pcall(function()
        jogador.set_overlay("invalida", { target = "nao_existe", elements = {} })
    end)
    exigir(not ok, "um alvo de sobreposicao desconhecido deveria ser recusado")

    jogador.set_hud({})
end

-- O que esta sessao acrescentou. Cada caso existe porque a capacidade era nova, e nova sem
-- verificacao e a mesma coisa que ausente com aparencia de presente.

TESTES.inventario_por_slot = function(ctx)
    local x, y, z = 12, 70, 12
    -- Limpa antes de colocar. Um teste que supoe o mundo limpo falha na segunda execucao, e a
    -- mensagem acusa o proprio teste em vez do defeito -- foi o que aconteceu quando uma execucao
    -- anterior foi interrompida no meio e deixou o bau com itens dentro.
    ctx.server.set_block("minecraft:air", x, y, z)
    ctx.server.set_block("minecraft:chest", x, y, z)

    -- container_at sempre numerou os slots; enderecar o slot que ele nomeia e o que faltava.
    local vazio = ctx.server.container_at(x, y, z)
    exigir(#vazio == 0, "o bau novo deveria estar vazio, veio " .. #vazio .. " linha(s)")

    local sobrou = ctx.server.insert_into(x, y, z, "minecraft:diamond", 5, 0)
    exigir(sobrou == 0, "os 5 diamantes deveriam caber no slot 0, sobraram " .. sobrou)

    local conteudo = ctx.server.container_at(x, y, z)
    exigir(#conteudo == 1, "o bau deveria ter uma linha, veio " .. #conteudo)
    exigir(conteudo[1].slot == 0, "o item deveria estar no slot 0, veio " .. conteudo[1].slot)
    exigir(conteudo[1].count == 5, "deveria haver 5, ha " .. conteudo[1].count)

    -- Pedir do slot certo o item errado nao pode tirar nada: sem essa conferencia, errar o indice
    -- esvaziaria o slot errado em silencio.
    local errado = ctx.server.extract_from(x, y, z, "minecraft:emerald", 5, 0)
    exigir(errado == 0, "pedir esmeralda do slot do diamante deveria tirar 0, tirou " .. errado)

    local tirou = ctx.server.extract_from(x, y, z, "minecraft:diamond", 2, 0)
    exigir(tirou == 2, "deveria tirar 2, tirou " .. tirou)

    local resto = ctx.server.container_at(x, y, z)
    exigir(resto[1].count == 3, "deveriam sobrar 3, sobraram " .. resto[1].count)

    ctx.server.set_block("minecraft:air", x, y, z)
end

TESTES.estrutura_girada = function(ctx)
    -- A area limpa e exatamente a que o desenho ocupa, e nao uma folga generosa.
    --
    -- A bateria inteira roda dentro de um callback so, e o orcamento e de 20 ms para tudo. A
    -- primeira versao limpava 11 por 11 quatro vezes -- quase quinhentos set_block -- e derrubou o
    -- caso seguinte por tempo, nao por defeito. Um teste que gasta o orcamento dos outros e um
    -- teste que quebra os outros.
    local y = 72
    -- O desenho e um L: girado, ocupa posicoes diferentes. Um desenho simetrico passaria mesmo
    -- sem a rotacao implementada, que e o pior tipo de teste.
    ctx.server.fill("minecraft:air", 20, y, 20, 21, y, 21)

    ctx.server.place_structure("ele", 20, y, 20)
    local direto = {}
    for dx = 0, 1 do
        for dz = 0, 1 do
            direto[dx .. "," .. dz] = ctx.server.get_block(20 + dx, y, 20 + dz)
        end
    end

    ctx.server.fill("minecraft:air", 20, y, 20, 21, y, 21)
    ctx.server.place_structure("ele", 20, y, 20, 1)
    local girado = {}
    for dx = 0, 1 do
        for dz = 0, 1 do
            girado[dx .. "," .. dz] = ctx.server.get_block(20 + dx, y, 20 + dz)
        end
    end

    local diferente = false
    for chave, bloco in pairs(direto) do
        if girado[chave] ~= bloco then diferente = true end
    end
    exigir(diferente, "um quarto de volta deveria mudar as posicoes de um desenho assimetrico")

    -- Quatro quartos voltam ao original: e o que pega um erro de sinal, que passaria num giro so.
    ctx.server.fill("minecraft:air", 20, y, 20, 21, y, 21)
    ctx.server.place_structure("ele", 20, y, 20, 4)
    for chave, bloco in pairs(direto) do
        local dx, dz = string.match(chave, "(%d+),(%d+)")
        exigir(ctx.server.get_block(20 + tonumber(dx), y, 20 + tonumber(dz)) == bloco,
            "uma volta inteira deveria devolver o desenho original em " .. chave)
    end

    ctx.server.fill("minecraft:air", 20, y, 20, 21, y, 21)
end

TESTES.som_e_particula = function(ctx)
    -- Categoria e o que permite ao jogador baixar o volume do mod sem baixar o do jogo.
    ctx.server.play_sound("minecraft:block.note_block.pling", 0, 70, 0, 0.2, 1.0, "blocks")
    ctx.server.play_sound("minecraft:block.note_block.pling", 0, 70, 0, 0.2, 1.0, "ambient")

    local ok = pcall(function()
        ctx.server.play_sound("minecraft:block.note_block.pling", 0, 70, 0, 0.2, 1.0, "inventada")
    end)
    exigir(not ok, "uma categoria de som desconhecida deveria ser recusada")

    -- Velocidade era zero fixo: dava para fazer fumaca aparecer, nao subir.
    ctx.server.spawn_particles("minecraft:smoke", 0, 71, 0, 4, 0.2, 0.1)

    local faixa = pcall(function()
        ctx.server.spawn_particles("minecraft:smoke", 0, 71, 0, 4, 0.2, 99)
    end)
    exigir(not faixa, "uma velocidade de particula fora da faixa deveria ser recusada")
end

TESTES.lista_de_mods = function(ctx)
    -- O gerenciador da plataforma enxerga um mod so; esta lista e o que torna os mods Lua
    -- visiveis para quem joga.
    local mods = ctx.server.mods()
    exigir(#mods > 0, "a lista de mods nao deveria estar vazia")

    local eu
    for _, m in ipairs(mods) do
        if m.id == "autoteste" then eu = m end
    end
    exigir(eu ~= nil, "o proprio autoteste deveria aparecer na lista")
    exigir(eu.version ~= "", "a versao deveria vir do manifesto")
    exigir(type(eu.permissions) == "table", "as permissoes deveriam vir como lista")
    exigir(#eu.permissions > 0, "o autoteste declara permissoes e elas deveriam aparecer")
    exigir(eu.enabled, "o autoteste esta carregado, entao deveria constar como ligado")

    -- O autoteste nao registra bloco nem item, entao quem entra num servidor com ele nao precisa
    -- te-lo instalado: o Lua roda no servidor e a tela vai como dados.
    exigir(eu.side == "server", "o autoteste deveria ser so de servidor, veio " .. tostring(eu.side))
    exigir(eu.requires_client == false, "o autoteste nao deveria exigir instalacao no cliente")

    -- Ja um mod com conteudo declarado exige os dois lados.
    local com_bloco
    for _, m in ipairs(mods) do
        if m.blocks > 0 then com_bloco = m end
    end
    if com_bloco then
        exigir(com_bloco.requires_client,
            com_bloco.id .. " registra bloco, entao deveria exigir o cliente")
    end
end

TESTES.instalacao_fechada = function(ctx)
    if ctx.player == nil then
        ctx.log.info("[AUTOTESTE] instalacao_fechada: pulado, execucao sem jogador")
        return
    end

    -- A chave nasce desligada, e este teste existe para ela continuar assim: instalar codigo e a
    -- operacao mais forte do loader, e um padrao que vira "ligado" sem ninguem notar e um risco.
    exigir(ctx.server.install_api_enabled() == false,
        "a instalacao pela API deveria nascer desligada")
    exigir(ctx.server.install_allowed() == false,
        "sem a chave ligada, install_allowed deveria ser falso mesmo para operador")

    -- Com a chave desligada, a API precisa recusar com motivo em vez de instalar.
    local ok = pcall(function()
        ctx.server.install_preview("https://exemplo.invalido/mod.json")
    end)
    exigir(not ok, "install_preview deveria recusar com a chave desligada")

    ctx.log.info("[AUTOTESTE] operador: " .. tostring(ctx.server.is_operator()))
end

TESTES.eventos_de_cliente = function(ctx)
    -- O lado que faltava. Ate acoplar o cliente, todo evento nascia no servidor: ciclo de vida,
    -- tique, bloco, item. O jogador abrir o inventario era invisivel para o mod -- mesmo o loader
    -- ja desenhando sobreposicoes justamente sobre aquela tela.
    exigir(ctx.state.telas_vistas ~= nil,
        "o mod deveria ter registrado o mapa de telas na carga")

    if ctx.player == nil then
        ctx.log.info("[AUTOTESTE] eventos_de_cliente: sem jogador; so o registro foi conferido")
        return
    end

    -- Nao da para forcar o jogador a abrir uma tela daqui, entao o que se confere e o caminho:
    -- o evento esta no vocabulario e o callback esta ligado. Se alguem abriu alguma tela nesta
    -- sessao, o mapa mostra qual.
    local vistas = {}
    for nome in pairs(ctx.state.telas_vistas) do vistas[#vistas + 1] = nome end
    table.sort(vistas)

    ctx.log.info("[AUTOTESTE] telas do jogo vistas ate agora: "
                 .. (#vistas == 0 and "nenhuma" or table.concat(vistas, ", ")))
end

TESTES.bloco_declarativo = function(ctx)
    -- O bloco vem de outro mod de exemplo: e o caso realista, porque um mod raramente e o dono de
    -- tudo que toca.
    local x, y, z = 14, 70, 14
    ctx.server.set_block("minecraft:air", x, y, z)
    ctx.server.set_block("hello_lua:ruby_block", x, y, z)
    exigir(ctx.server.get_block(x, y, z) == "hello_lua:ruby_block",
        "o bloco declarado deveria estar ali, veio " .. ctx.server.get_block(x, y, z))

    ctx.server.set_block_variant("hello_lua:ruby_block", x, y, z, 1)
    ctx.server.set_block_luminance("hello_lua:ruby_block", x, y, z, 12)

    -- A luminancia mora no estado da posicao, e nao no bloco: acender um altar nao pode acender
    -- todos os outros do mesmo tipo no mundo.
    local outro_x = x + 3
    ctx.server.set_block("hello_lua:ruby_block", outro_x, y, z)
    exigir(ctx.server.get_block(outro_x, y, z) == "hello_lua:ruby_block",
        "o segundo exemplar deveria existir")

    -- Ja a propriedade fisica vale para o bloco todo: e caracteristica do material.
    ctx.server.set_block_property("hello_lua:ruby_block", "hardness", 3.0)
    ctx.server.set_block_property("hello_lua:ruby_block", "hardness", 5.0)

    local ok = pcall(function()
        ctx.server.set_block_property("hello_lua:ruby_block", "inventada", 1.0)
    end)
    exigir(not ok, "uma propriedade fisica desconhecida deveria ser recusada")

    ctx.server.set_block("minecraft:air", x, y, z)
    ctx.server.set_block("minecraft:air", outro_x, y, z)
end

TESTES.dados_por_bloco = function(ctx)
    local x, y, z = 16, 70, 16
    ctx.server.set_block("minecraft:air", x, y, z)
    ctx.server.set_block("crystal_world:cofre", x, y, z)

    -- Uma posicao sem dados responde vazio, e nao erro: o mod le antes de escrever pela primeira
    -- vez, e um erro ali obrigaria todo mod a envolver a primeira leitura em pcall.
    local inicial = ctx.server.get_block_data(x, y, z)
    exigir(inicial == nil or inicial == "" or type(inicial) == "table",
        "dados de uma posicao nova deveriam vir vazios, veio " .. type(inicial))

    ctx.server.set_block_data(x, y, z, { dono = "autoteste", cargas = 3 })
    local lido = ctx.server.get_block_data(x, y, z)
    exigir(type(lido) == "table", "os dados deveriam voltar como tabela, veio " .. type(lido))
    exigir(lido.dono == "autoteste", "o dono deveria ter sido guardado, veio " .. tostring(lido.dono))
    exigir(lido.cargas == 3, "as cargas deveriam ser 3, vieram " .. tostring(lido.cargas))

    ctx.server.set_block("minecraft:air", x, y, z)
end

TESTES.limites = function(ctx)
    -- Os tetos existem para um mod nao travar a thread do servidor, e um teto que nao recusa e
    -- decoracao. Cada um destes ja teve motivo concreto para existir.
    local grande = pcall(function()
        ctx.server.fill("minecraft:stone", 0, 60, 0, 200, 100, 200)
    end)
    exigir(not grande, "um fill acima do teto de volume deveria ser recusado")

    local longe = pcall(function()
        ctx.server.set_block("minecraft:stone", 999999999, 70, 0)
    end)
    exigir(not longe, "uma coordenada absurda deveria ser recusada")

    local desconhecido = pcall(function()
        ctx.server.set_block("minecraft:bloco_que_nao_existe", 0, 70, 0)
    end)
    exigir(not desconhecido, "um bloco desconhecido deveria ser recusado")

    local muitos = pcall(function()
        ctx.server.items({ limit = 99999 })
    end)
    exigir(not muitos, "um limite de consulta fora da faixa deveria ser recusado")
end

TESTES.sandbox = function(ctx)
    -- O sandbox e a fronteira do projeto inteiro: se uma destas voltar, um mod passa a alcancar o
    -- disco e a rede da maquina de quem hospeda.
    for _, proibido in ipairs({ "io", "os", "package", "debug", "luajava" }) do
        exigir(_G[proibido] == nil, "a biblioteca " .. proibido .. " deveria estar fora do sandbox")
    end
    for _, proibido in ipairs({ "require", "dofile", "loadfile", "load", "loadstring" }) do
        exigir(_G[proibido] == nil, "a funcao " .. proibido .. " deveria estar fora do sandbox")
    end

    -- O que fica disponivel tambem precisa continuar disponivel: cortar demais quebra os mods.
    exigir(type(string) == "table", "string deveria estar disponivel")
    exigir(type(table) == "table", "table deveria estar disponivel")
    exigir(type(math) == "table", "math deveria estar disponivel")
    exigir(type(pcall) == "function", "pcall deveria estar disponivel")
end

TESTES.estado_do_mod = function(ctx)
    -- ctx.state e por mod, e nao por jogador: e a armadilha mais facil de cair, porque com um
    -- jogador so no servidor os dois se parecem.
    ctx.state.contador = (ctx.state.contador or 0) + 1
    exigir(ctx.state.contador >= 1, "o contador deveria ter subido")

    ctx.state.aninhado = { a = 1, b = { c = 2 } }
    exigir(ctx.state.aninhado.b.c == 2, "o estado deveria aceitar tabela aninhada")

    ctx.state.temporario = "some"
    ctx.state.temporario = nil
    exigir(ctx.state.temporario == nil, "apagar uma chave do estado deveria valer")
end

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
    -- O mapa nasce na carga, e nao na primeira tela: assim o teste distingue "ninguem abriu nada"
    -- de "o evento nao esta ligado", que sao coisas diferentes.
    ctx.state.telas_vistas = ctx.state.telas_vistas or {}
    -- Uma tarefa agendada na carga prova o relogio interno: se o agendador nao avanca, ela nunca
    -- vence, e a marca abaixo nunca aparece.
    mod.after(20, function(depois)
        depois.state.agendador_rodou = true
    end)
end

local function on_player_joined(ctx)
    ctx.state.player_joined = true
end

--- O jogador abriu uma tela do jogo. O nome vem do vocabulario fechado do nucleo.
local function on_client_screen_opened(ctx)
    ctx.state.telas_vistas = ctx.state.telas_vistas or {}
    local tela = ctx.client.screen
    ctx.state.telas_vistas[tela] = (ctx.state.telas_vistas[tela] or 0) + 1
    ctx.log.info("Tela do jogo aberta: " .. tela)
end

local function on_client_screen_closed(ctx)
    ctx.log.info("Tela do jogo fechada: " .. ctx.client.screen)
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

-- ---------------------------------------------------------------------------------------------
-- Painel do autoteste
--
-- A bateria sempre escreveu no log, e ler log e o unico jeito de saber o que passou. Uma tela
-- responde a mesma pergunta de relance, e -- mais util -- serve de carga pesada para a propria
-- camada de interface: dezenas de elementos, rolagem, cor por estado e redesenho a cada passo.
--
-- Ela roda em pedacos, e nao de uma vez. O orcamento e de 20 ms por callback, e a bateria inteira
-- chega perto disso; um clique que estourasse o limite abortaria o callback e a tela nao mudaria,
-- que e o pior resultado -- parece travada sem dizer por que.
--
-- Um agendamento resolveria isso melhor, mas uma tarefa de mod.after recebe contexto sem jogador:
-- ela nao tem a quem redesenhar. Fica registrado como lacuna em vez de contornado por dentro.

--- Corta o texto para caber na linha, com reticencias.
-- Um texto que estoura a largura e desenhado por cima do que estiver ao lado, e um motivo de falha
-- ilegivel nao ajuda mais que motivo nenhum.
local function encurtar(texto, limite)
    if texto == nil or texto == "" then return "" end
    if #texto <= limite then return texto end
    return string.sub(texto, 1, limite - 1) .. "…"
end

local LARGURA_P = 300
local ALTURA_P = 232
local POR_CLIQUE = 8
local ALTURA_ITEM = 20

local COR_P = {
    fundo = "#101018E0",
    titulo = "#FFD966",
    texto = "#E0E0E0",
    fraco = "#8A8A98",
    linha = "#FFFFFF14",
    ok = "#7BC96F",
    falhou = "#E06C6C",
    pulado = "#7A7A88",
}

--- Os nomes dos casos, sempre na mesma ordem.
local function nomes_dos_testes()
    local nomes = {}
    for nome in pairs(TESTES) do nomes[#nomes + 1] = nome end
    table.sort(nomes)
    return nomes
end

--- O quadro do painel para este jogador.
local function painel(ctx)
    local uuid = ctx.player and ctx.player.uuid or "console"
    ctx.state.paineis = ctx.state.paineis or {}
    ctx.state.paineis[uuid] = ctx.state.paineis[uuid] or { status = {}, proximo = 1 }
    return ctx.state.paineis[uuid]
end

--- Roda um caso e guarda o que aconteceu, sem deixar o erro subir.
local function rodar_um(ctx, nome, quadro)
    local ok, erro = pcall(function() TESTES[nome](ctx) end)
    quadro.status[nome] = {
        estado = ok and "ok" or "falhou",
        motivo = ok and "" or tostring(erro)
    }
end

local function marca_de(estado)
    if estado == "ok" then return "[x]", COR_P.ok end
    if estado == "falhou" then return "[!]", COR_P.falhou end
    if estado == "pulado" then return "[-]", COR_P.pulado end
    return "[ ]", COR_P.fraco
end

local function desenhar_painel(ctx)
    local quadro = painel(ctx)
    local nomes = nomes_dos_testes()

    local passaram, falharam, rodados = 0, 0, 0
    for _, nome in ipairs(nomes) do
        local r = quadro.status[nome]
        if r then
            rodados = rodados + 1
            if r.estado == "ok" then passaram = passaram + 1 end
            if r.estado == "falhou" then falharam = falharam + 1 end
        end
    end

    local topo_lista = 62
    local rodape = ALTURA_P - 28
    local altura_lista = rodape - 8 - topo_lista

    local elementos = {
        { type = "panel", x = 0, y = 0, w = LARGURA_P, h = ALTURA_P, color = COR_P.fundo },
        { type = "label", x = 12, y = 10, text = "Autoteste do Lua Loader", color = COR_P.titulo },
        { type = "label", x = 12, y = 24, color = COR_P.fraco,
          text = rodados .. "/" .. #nomes .. " rodados  ·  " .. passaram .. " passaram  ·  "
                 .. falharam .. " falharam" },

        -- A barra e o resumo que se le sem contar: cheia e verde quer dizer bateria limpa.
        { type = "progress", x = 12, y = 40, w = LARGURA_P - 24, h = 6,
          progress = #nomes > 0 and (rodados / #nomes) or 0,
          color = falharam > 0 and COR_P.falhou or COR_P.ok },

        { type = "panel", x = 10, y = 54, w = LARGURA_P - 20, h = 1, color = COR_P.linha },

        -- O viewport recorta e rola; tudo que aponta para ele pelo group anda junto.
        { type = "viewport", id = "area", x = 10, y = topo_lista,
          w = LARGURA_P - 20, h = altura_lista,
          content = #nomes * ALTURA_ITEM },
    }

    local y = 0
    for _, nome in ipairs(nomes) do
        local r = quadro.status[nome]
        local marca, cor = marca_de(r and r.estado or nil)

        -- Sem botao aqui dentro, e nao por gosto: botao vira widget de verdade do jogo, e widget
        -- nao passa pelo recorte nem pela rolagem do viewport. Uma linha clicavel por caso
        -- mostraria todos os botoes de uma vez, parados, por cima do resto -- que foi exatamente o
        -- que aconteceu na primeira versao desta tela. O nucleo passou a recusar isso.
        --
        -- O clique por caso continua existindo pelo comando: /mod autoteste <nome>.
        elementos[#elementos + 1] = { type = "panel", group = "area", style = "slot",
                                      x = 0, y = y, w = LARGURA_P - 26, h = ALTURA_ITEM - 2 }
        elementos[#elementos + 1] = { type = "label", group = "area", x = 6, y = y + 2,
                                      text = marca, color = cor }
        elementos[#elementos + 1] = { type = "label", group = "area", x = 30, y = y + 2,
                                      text = nome, color = COR_P.texto }

        if r and r.estado == "falhou" and r.motivo ~= "" then
            elementos[#elementos + 1] = { type = "label", group = "area", x = 30, y = y + 11,
                                          text = encurtar(r.motivo, 40), color = COR_P.falhou }
        end
        y = y + ALTURA_ITEM
    end

    local restam = #nomes - (quadro.proximo - 1)
    local texto_rodar = restam <= 0 and "Rodar tudo"
                        or (quadro.proximo > 1 and ("Continuar (" .. restam .. ")") or "Rodar tudo")

    elementos[#elementos + 1] = { type = "panel", x = 10, y = rodape - 6,
                                  w = LARGURA_P - 20, h = 1, color = COR_P.linha }
    elementos[#elementos + 1] = { type = "button", id = "rodar_tudo", x = 10, y = rodape,
                                  w = 110, h = 20, text = texto_rodar }
    elementos[#elementos + 1] = { type = "button", id = "limpar", x = 126, y = rodape,
                                  w = 60, h = 20, text = "Limpar" }
    elementos[#elementos + 1] = { type = "button", id = "dump", x = 192, y = rodape,
                                  w = 44, h = 20, text = "Dump" }
    elementos[#elementos + 1] = { type = "button", id = "fechar", x = LARGURA_P - 66, y = rodape,
                                  w = 56, h = 20, text = "Fechar" }

    return {
        title = "Autoteste",
        width = LARGURA_P,
        height = ALTURA_P,
        blur = true,
        dim = true,
        elements = elementos
    }
end

mod.screen("painel", function(ctx)
    if ctx.player == nil then return end

    local quadro = painel(ctx)
    local acao = ctx.ui.action
    local elemento = ctx.ui.element or ""

    if acao == "close" then return end
    if acao ~= "click" then return end

    if elemento == "fechar" then
        ctx.player.close_screen()
        return
    elseif elemento == "limpar" then
        quadro.status = {}
        quadro.proximo = 1
    elseif elemento == "rodar_tudo" then
        local nomes = nomes_dos_testes()
        if quadro.proximo > #nomes then
            quadro.status = {}
            quadro.proximo = 1
        end

        -- Um pedaco por clique: e o que mantem cada callback dentro do orcamento.
        local fim = math.min(quadro.proximo + POR_CLIQUE - 1, #nomes)
        for i = quadro.proximo, fim do
            rodar_um(ctx, nomes[i], quadro)
        end
        quadro.proximo = fim + 1
    elseif elemento == "dump" then
        -- Escreve no log onde cada elemento desta tela vai parar, e o que esta errado com isso.
        -- E o caminho para investigar um desenho torto sem precisar de captura de tela.
        ctx.player.dump_screen(desenhar_painel(ctx))
        ctx.player.send_message("Dump da tela escrito no log.")
    end

    ctx.player.update_screen(desenhar_painel(ctx))
end)

--- Escreve no log o que a bateria acumulou, e devolve quantos passaram.
local function relatar(ctx)
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
    if ctx.player then ctx.player.send_message(resumo) end
    return passaram
end

--- Roda um pedaco da bateria e agenda o proximo, se sobrar.
-- Cada tique traz um callback novo, e com ele um orcamento novo.
local function agendar_pedaco(ctx, nomes, inicio)
    local fim = math.min(inicio + POR_CLIQUE - 1, #nomes)

    for i = inicio, fim do
        local nome = nomes[i]
        verificar(nome, function() TESTES[nome](ctx) end)
    end

    if fim < #nomes then
        mod.after(1, function(depois)
            agendar_pedaco(depois, nomes, fim + 1)
        end)
    else
        relatar(ctx)
    end
end

mod.command("autoteste", function(ctx)
    -- Com jogador e sem argumento, o painel: ele responde de relance o que o log responde lendo.
    -- Com um nome depois do comando, a bateria antiga -- e o caminho que o servidor dirigivel usa,
    -- onde nao ha tela nenhuma.
    if ctx.player ~= nil and (ctx.subcommand == "" or ctx.subcommand == nil)
            and ctx.player.supports_screens() then
        ctx.player.open_screen("painel", desenhar_painel(ctx))
        return
    end

    local so = ctx.subcommand
    local nomes = nomes_dos_testes()

    -- Um nome depois do comando roda so aquele caso, e cabe folgado num callback.
    if so ~= nil and so ~= "" then
        resultados = {}
        verificar(so, function()
            if TESTES[so] == nil then error("caso desconhecido: " .. so, 0) end
            TESTES[so](ctx)
        end)
        relatar(ctx)
        return
    end

    -- A bateria inteira nao cabe.
    --
    -- O orcamento e de 20 ms por callback, e ele conta para o callback, nao para o teste: com a
    -- lista crescendo, um caso qualquer passou a ser interrompido no meio -- e o que falhava era o
    -- da vez, nao o culpado. Um relatorio que acusa o inocente e pior que um teste a menos.
    --
    -- Entao a bateria e fatiada, um pedaco por tique. Cada callback agendado ganha o proprio
    -- orcamento, e a lista pode crescer a vontade.
    resultados = {}
    agendar_pedaco(ctx, nomes, 1)
end)

--- Os eventos de criatura, contados para o caso poder conferir que dispararam.
local function on_entity_spawned(ctx)
    if ctx.state.vistos then ctx.state.vistos.nasceu = ctx.state.vistos.nasceu + 1 end
end

local function on_entity_damaged(ctx)
    if ctx.state.vistos then ctx.state.vistos.apanhou = ctx.state.vistos.apanhou + 1 end
    ctx.state.ultimo_dano = { id = ctx.entity.id, amount = ctx.entity.amount,
                              health = ctx.entity.health }
end

local function on_entity_died(ctx)
    if ctx.state.vistos then ctx.state.vistos.morreu = ctx.state.vistos.morreu + 1 end
end

return {
    on_loader_ready = on_loader_ready,
    on_player_joined = on_player_joined,
    on_tick = on_tick,
    on_client_screen_opened = on_client_screen_opened,
    on_client_screen_closed = on_client_screen_closed,
    on_entity_spawned = on_entity_spawned,
    on_entity_damaged = on_entity_damaged,
    on_entity_died = on_entity_died,
}

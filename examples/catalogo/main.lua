-- Catalogo de itens e receitas: o "JEI" possivel com o loader.
--
-- Junta as pecas que existem hoje. No inventario, uma sobreposicao a direita lista todos os itens
-- do jogo em uma grade rolavel. Clicar em um item abre uma tela propria com quem o produz e para
-- que ele serve, e um campo de busca filtra a lista.
--
-- O que ainda nao da para fazer, e por que:
--   * buscar dentro da sobreposicao -- campo de texto nao funciona sobre uma tela do jogo, que
--     disputaria o foco do teclado; por isso a busca vive na tela propria.
--   * pegar o item da grade -- o loader desenha sobre a tela do jogo, mas nao mexe nos slots dela.

local CELULA = 18

-- Largura da janela de container do jogo, que o painel acompanha.
local JANELA_DO_JOGO = 176

-- Nem toda receita e uma grade. Uma fornalha tem entrada, combustivel e chama; um cortador tem uma
-- entrada so. Desenhar todas como 3x3 mentiria sobre o que sao -- um minerio com oito buracos em
-- volta parece uma receita de bancada que alguem esqueceu de preencher.
local COZIMENTO = {
    ["minecraft:smelting"] = "Fornalha",
    ["minecraft:blasting"] = "Alto-forno",
    ["minecraft:smoking"] = "Defumador",
    ["minecraft:campfire_cooking"] = "Fogueira"
}

local UMA_ENTRADA = {
    ["minecraft:stonecutting"] = "Cortador"
}

local function estacao_de(receita)
    return COZIMENTO[receita.type] or UMA_ENTRADA[receita.type]
end

-- O bloco que executa cada tipo de receita. Uma aba com o icone da fornalha diz o que faz sem
-- depender de ler o nome, que e o que um icone serve para fazer.
local ICONE = {
    ["minecraft:smelting"] = "minecraft:furnace",
    ["minecraft:blasting"] = "minecraft:blast_furnace",
    ["minecraft:smoking"] = "minecraft:smoker",
    ["minecraft:campfire_cooking"] = "minecraft:campfire",
    ["minecraft:stonecutting"] = "minecraft:stonecutter",
    ["minecraft:crafting_shaped"] = "minecraft:crafting_table",
    ["minecraft:crafting_shapeless"] = "minecraft:crafting_table",
    ["minecraft:smithing_transform"] = "minecraft:smithing_table",
    ["minecraft:smithing_trim"] = "minecraft:smithing_table"
}

-- Nomes das receitas que nao sao cozimento nem corte.
local BANCADA = {
    ["minecraft:crafting_shaped"] = "Bancada",
    ["minecraft:crafting_shapeless"] = "Bancada (livre)",
    ["minecraft:smithing_transform"] = "Ferraria",
    ["minecraft:smithing_trim"] = "Ferraria (enfeite)"
}

-- O rotulo de cada fonte, dizendo o que a produz.
--
-- Sem ele a forma tinha de contar a historia sozinha, e ela nao conta: uma grade 3x3 e uma bancada,
-- mas duas caixas com uma seta tanto podem ser um cortador quanto um mob que derruba o item ao
-- morrer. Quem olha precisa saber o que fazer, e nao so o que entra e o que sai.
local function acao_de(fonte)
    if fonte.tipo == "receita" then
        return estacao_de(fonte.dados) or BANCADA[fonte.dados.type] or "Receita"
    elseif fonte.tipo == "processo" then
        return fonte.dados.title
    end
    return "Derruba"
end

-- O icone que representa a origem de uma fonte.
--
-- Um processo aponta para quem o executa, quando declarado: a aba de ordenha mostra a vaca. Um drop
-- mostra a picareta, que e o que o jogador faz para consegui-lo.
local function icone_de(fonte)
    if fonte.tipo == "receita" then
        return ICONE[fonte.dados.type] or "minecraft:crafting_table"
    elseif fonte.tipo == "processo" then
        return fonte.dados.by or "minecraft:chest"
    end
    return "minecraft:iron_pickaxe"
end

-- Quantas colunas cabem à direita do inventário naquele cliente.
--
-- Isto não é detalhe de gosto: a escala da interface divide a resolução, então a mesma janela que
-- sobra espaço em escala 2 transborda em escala 3. O mod monta a tela no servidor e por isso não
-- enxerga o cliente -- ele pergunta.
local function colunas_para(ctx)
    local tela = ctx.player.screen_size()
    if not tela then return 4 end

    -- O espaço livre de um lado é metade do que sobra depois da janela do jogo.
    local livre = math.floor((tela.width - JANELA_DO_JOGO) / 2) - 16
    local cabem = math.floor(livre / CELULA)

    -- Nunca menos de duas, nunca mais de nove: abaixo disso não é lista, acima vira parede.
    return math.max(2, math.min(9, cabem))
end

-- Uma pagina, e nao a lista inteira. O vanilla tem mais de mil itens e um modpack passa de vinte
-- mil; como a rolagem acontece no cliente, todas as celulas precisam ser enviadas, entao uma lista
-- unica esbarraria no teto de 512 celulas por grade em qualquer instalacao real. A pagina rola
-- dentro do viewport, e os botoes trocam de pagina -- as duas coisas se completam.
local LINHAS_VISIVEIS = 6
local PAGINAS_DE_LINHAS = 32

-- Estado por jogador: dois jogadores navegam o catalogo de forma independente, e ctx.state e
-- compartilhado pelo mod inteiro.
local function meu(ctx)
    local uuid = ctx.player.uuid
    ctx.state.jogadores = ctx.state.jogadores or {}
    ctx.state.jogadores[uuid] = ctx.state.jogadores[uuid]
            or { busca = "", modo = "produz", pagina = 1, pagina_busca = 1, pagina_receita = 1 }
    return ctx.state.jogadores[uuid]
end

-- A lista completa e lida uma vez: o registro do jogo nao muda enquanto o servidor roda, e
-- relistar a cada tecla digitada custaria uma varredura inteira por letra.
--
-- Fica em uma variavel local, e nao em ctx.state, de proposito: mod.state vai para disco, e gravar
-- mil e trezentos itens a cada salvamento seria desperdicio de um dado que o proprio jogo ja tem.
-- Cache e estado sao coisas diferentes -- este se reconstroi sozinho na proxima carga.
local cache_de_itens

local function todos(ctx)
    cache_de_itens = cache_de_itens or ctx.server.items({ limit = 4096 })
    return cache_de_itens
end

local function filtrar(ctx, busca)
    if busca == nil or busca == "" then return todos(ctx) end

    local encontrados = {}
    for _, id in ipairs(todos(ctx)) do
        if string.find(id, busca, 1, true) then
            encontrados[#encontrados + 1] = id
        end
    end
    return encontrados
end

-- Uma grade e um elemento so, com a lista de itens dentro. Sem ela, cada celula exigiria um
-- elemento com x e y calculados a mao, e o teto de 256 elementos por tela seria atingido rapido.
local function grade(id, grupo, itens, colunas)
    -- Sem tooltip declarado, o cliente responde com o nome traduzido do item e o identificador
    -- abaixo. O servidor nao teria como traduzir: o idioma e escolha de cada cliente.
    local celulas = {}
    for indice, item in ipairs(itens) do
        celulas[indice] = { item = item }
    end
    return { type = "grid", id = id, group = grupo, x = 0, y = 0,
             columns = colunas, cell = CELULA, items = celulas }
end

-- Um slot afundado atras de cada celula, como no inventario. Sao paineis com estilo "slot": o
-- bisel invertido faz o quadrado parecer cavado, e nao ha textura nenhuma envolvida.
local function slots(x, y, colunas, linhas, grupo)
    local caixas = {}
    for linha = 0, linhas - 1 do
        for coluna = 0, colunas - 1 do
            caixas[#caixas + 1] = {
                type = "panel", style = "slot", group = grupo, border = 1,
                x = x + coluna * CELULA, y = y + linha * CELULA, w = 16, h = 16
            }
        end
    end
    return caixas
end

local function por_pagina(colunas)
    return colunas * PAGINAS_DE_LINHAS
end

local function altura_da_grade(quantidade, colunas)
    return math.ceil(quantidade / colunas) * CELULA
end

local function paginas_de(total, colunas)
    return math.max(1, math.ceil(total / por_pagina(colunas)))
end

-- A fatia visivel agora, e o deslocamento dela na lista completa: o clique devolve o indice da
-- celula dentro da pagina, e sem o deslocamento ele apontaria para o item errado.
local function fatia(itens, pagina, colunas)
    local primeiro = (pagina - 1) * por_pagina(colunas)
    local recorte = {}
    for indice = 1, por_pagina(colunas) do
        local item = itens[primeiro + indice]
        if not item then break end
        recorte[indice] = item
    end
    return recorte, primeiro
end

-- Um processo declarado por este mod, so para o catalogo ter o que mostrar alem do vanilla.
-- Qualquer mod declara os seus, e todos aparecem aqui: o registro e global.
mod.process("ordenha", {
    title = "Alimentar a vaca",
    inputs = { "minecraft:wheat" },
    output = { item = "minecraft:milk_bucket", count = 1, chance = 0.5 },
    by = "minecraft:cow"
})

---------------------------------------------------------------------------- sobreposicao

local function sobreposicao(ctx)
    local estado = meu(ctx)
    local itens = todos(ctx)
    local colunas = colunas_para(ctx)
    local pagina = math.min(estado.pagina or 1, paginas_de(#itens, colunas))
    local recorte = fatia(itens, pagina, colunas)

    local largura = colunas * CELULA
    local altura_lista = LINHAS_VISIVEIS * CELULA

    return {
        target = "inventory",
        elements = {
            -- Estilo vanilla: o mesmo cinza e o mesmo bisel da janela do inventario ao lado.
            { type = "panel", style = "vanilla", anchor = "gui_top_right", x = 4, y = 0,
              w = largura + 12, h = 166 },
            { type = "label", anchor = "gui_top_right", x = 10, y = 6,
              text = #itens .. " itens", color = "#404040", shadow = false },

            { type = "viewport", id = "area", anchor = "gui_top_right",
              x = 10, y = 20, w = largura, h = altura_lista,
              content = altura_da_grade(#recorte, colunas) },
            grade("itens", "area", recorte, colunas),

            { type = "button", id = "anterior", anchor = "gui_top_right",
              x = 10, y = 132, w = 24, h = 18, text = "<" },
            { type = "label", anchor = "gui_top_right", x = 40, y = 137,
              text = pagina .. "/" .. paginas_de(#itens, colunas),
              color = "#404040", shadow = false },
            { type = "button", anchor = "gui_top_right", id = "proxima",
              x = 10 + largura - 24, y = 132, w = 24, h = 18, text = ">" },

            { type = "button", id = "abrir_livro", anchor = "gui_top_right",
              x = 10, y = 152, w = largura, h = 14, text = "Buscar" }
        }
    }
end

---------------------------------------------------------------------------- tela de receitas

-- Desenha uma receita como o jogo a apresenta: a grade de entrada a esquerda e o resultado a
-- direita. Uma receita sem forma -- width zero -- vira uma fila unica, porque nao ha posicao.
-- Fornalha, alto-forno, defumador, fogueira e cortador: uma entrada, e o combustivel embaixo
-- quando o processo queima algo. O rotulo diz qual estacao e, porque a forma sozinha nao distingue
-- uma fornalha de um alto-forno.
local function desenhar_estacao(receita, x, y, estacao, com_rotulo)
    local elementos = {}
    local alternativas = receita.ingredients[1] or {}

    local mostrados = {}
    for i = 1, math.min(6, #alternativas) do mostrados[i] = alternativas[i] end
    if #alternativas > 6 then
        mostrados[#mostrados + 1] = "e mais " .. (#alternativas - 6) .. "..."
    end

    if com_rotulo then
        elementos[#elementos + 1] = { type = "label", x = x, y = y, text = estacao,
                                      color = "#404040", shadow = false }
    end

    local topo = y + 12
    elementos[#elementos + 1] = { type = "panel", style = "slot", border = 1,
                                  x = x, y = topo, w = 16, h = 16 }
    elementos[#elementos + 1] = { type = "item", x = x, y = topo,
                                  item = alternativas[1] or "minecraft:air",
                                  tooltip = table.concat(mostrados, "\n") }

    -- Combustivel so existe onde algo queima; um cortador nao consome nada alem da entrada.
    if COZIMENTO[receita.type] then
        elementos[#elementos + 1] = { type = "panel", style = "slot", border = 1,
                                      x = x, y = topo + CELULA, w = 16, h = 16 }
        elementos[#elementos + 1] = { type = "item", x = x, y = topo + CELULA,
                                      item = "minecraft:coal",
                                      tooltip = "Qualquer combustivel" }
    end

    local direita = x + CELULA
    elementos[#elementos + 1] = { type = "label", x = direita + 6, y = topo + 4, text = "->",
                                  color = "#404040", shadow = false }
    elementos[#elementos + 1] = { type = "panel", style = "slot", border = 1,
                                  x = direita + 22, y = topo, w = 16, h = 16 }
    elementos[#elementos + 1] = { type = "item", x = direita + 22, y = topo,
                                  item = receita.output.item, count = receita.output.count }

    return elementos, 2 * CELULA + 12
end

local function desenhar_receita(receita, x, y, com_rotulo)
    local estacao = estacao_de(receita)
    if estacao then return desenhar_estacao(receita, x, y, estacao, com_rotulo) end

    local elementos = {}
    if com_rotulo then
        elementos[#elementos + 1] = { type = "label", x = x, y = y,
                                      text = BANCADA[receita.type] or "Receita",
                                      color = "#404040", shadow = false }
    end
    y = y + 12

    -- A grade e sempre 3x3, como a mesa de trabalho. Desenhar so as posicoes ocupadas encolheria a
    -- grade e esconderia a forma: um machado e uma picareta usam as mesmas tres tabuas e dois
    -- gravetos, e o que os distingue e onde as pecas ficam. Com a grade inteira a vista, a forma se
    -- le de relance.
    local colunas_da_receita = receita.width > 0 and receita.width or 3
    local celulas = {}

    for posicao, alternativas in ipairs(receita.ingredients) do
        if posicao > 9 then break end

        -- Uma receita 2x2 tem quatro ingredientes em sequencia, mas eles ocupam as posicoes 1, 2,
        -- 4 e 5 de uma grade de tres colunas. Sem esta conversao, uma receita estreita apareceria
        -- esticada pela linha.
        local indice = posicao - 1
        local linha = math.floor(indice / colunas_da_receita)
        local coluna = indice % colunas_da_receita
        local destino = linha * 3 + coluna + 1

        -- Uma posicao pode aceitar varios itens, quando a receita usa uma tag. Mostrar o primeiro
        -- e a leitura honesta enquanto nao houver como alternar entre eles na tela.
        local mostrados = {}
        for i = 1, math.min(6, #alternativas) do mostrados[i] = alternativas[i] end
        if #alternativas > 6 then
            mostrados[#mostrados + 1] = "e mais " .. (#alternativas - 6) .. "..."
        end

        if destino <= 9 then
            celulas[destino] = { item = alternativas[1] or "minecraft:air",
                                 tooltip = table.concat(mostrados, "\n") }
        end
    end

    -- As posicoes vazias precisam existir na lista: uma grade e uma sequencia, e um buraco no meio
    -- faria as celulas seguintes andarem para tras.
    for posicao = 1, 9 do
        celulas[posicao] = celulas[posicao] or { item = "" }
    end

    for _, caixa in ipairs(slots(x, y, 3, 3)) do
        elementos[#elementos + 1] = caixa
    end
    elementos[#elementos + 1] = { type = "grid", id = "entrada", x = x, y = y,
                                  columns = 3, cell = CELULA, items = celulas }

    local direita = x + 3 * CELULA
    elementos[#elementos + 1] = { type = "label", x = direita + 6, y = y + 22, text = "->",
                                  color = "#404040", shadow = false }
    elementos[#elementos + 1] = { type = "panel", style = "slot", border = 1,
                                  x = direita + 22, y = y + 18, w = 16, h = 16 }
    elementos[#elementos + 1] = { type = "item", x = direita + 22, y = y + 18,
                                  item = receita.output.item, count = receita.output.count }

    -- A altura e constante agora: toda receita ocupa a mesma grade, mais o rotulo acima.
    return elementos, 3 * CELULA + 12
end

-- Um drop desenha como uma linha: o bloco, a seta e o item. Para a maior parte do jogo esta e a
-- resposta verdadeira sobre de onde um item vem.
local function desenhar_drop(bloco, item, x, y, com_rotulo)
    local topo = y + 12
    local elementos = {
        { type = "panel", style = "slot", border = 1, x = x, y = topo, w = 16, h = 16 },
        -- O identificador tanto pode ser um bloco quanto um mob; o cliente desenha o que for.
        { type = "item", x = x, y = topo, item = bloco, tooltip = bloco },
        { type = "label", x = x + 22, y = topo + 4, text = "->",
          color = "#404040", shadow = false },
        { type = "panel", style = "slot", border = 1, x = x + 38, y = topo, w = 16, h = 16 },
        { type = "item", x = x + 38, y = topo, item = item }
    }

    if com_rotulo then
        table.insert(elementos, 1, { type = "label", x = x, y = y, text = "Derruba",
                                     color = "#404040", shadow = false })
    end
    return elementos, CELULA + 12
end

-- Reune as tres fontes numa lista unica, para poderem ser paginadas juntas.
--
-- Sem isto o painel mostrava as tres primeiras receitas e escondia o resto sem avisar, que e o pior
-- jeito de truncar: quem le conclui que aquilo e tudo. Um item com dez receitas agora diz que tem
-- dez.
-- A pagina se enche por altura, e nao por contagem.
--
-- Uma receita 3x3 ocupa 62 px e uma 1x1 ocupa 26: um numero fixo por pagina ou desperdicava metade
-- da janela ou empurrava a terceira receita para fora dela. Quem decide quantas cabem e a soma das
-- alturas.
-- Espaco de cada fonte, em largura e altura.
--
-- Uma receita e a grade 3x3 mais a seta e o resultado; um processo tem tantas entradas quantas
-- forem declaradas; um drop e uma linha de dois itens. Medir antes de desenhar e o que permite
-- empacotar sem tentativa e erro.
local ESPACO = 10


local function medir(fonte)
    if fonte.tipo == "receita" then
        if estacao_de(fonte.dados) then
            -- Uma coluna de entrada em vez de tres, e o rotulo da estacao acima.
            return CELULA + 38, 2 * CELULA + 12
        end
        return 3 * CELULA + 38, 3 * CELULA + 12
    elseif fonte.tipo == "processo" then
        local colunas = math.max(1, #fonte.dados.inputs)
        -- Mais alta que a grade porque o rotulo do processo fica acima dela.
        return colunas * CELULA + 38, CELULA + 12
    end
    return 54, CELULA + 12
end

-- Distribui as fontes em paginas, enchendo cada linha antes de descer.
--
-- E o comportamento de um flex com quebra: cabendo duas receitas lado a lado, elas ficam lado a
-- lado. Empilhar uma por linha desperdicava a largura inteira da janela, que agora acompanha a
-- tela e por isso costuma sobrar.
local function paginar(lista, disponivel_w, disponivel_h)
    local paginas = {}
    local atual = {}
    local x, y, altura_da_linha = 0, 0, 0

    local function quebrar_linha()
        x = 0
        y = y + altura_da_linha + ESPACO
        altura_da_linha = 0
    end

    for _, fonte in ipairs(lista) do
        local w, h = medir(fonte)

        if x > 0 and x + w > disponivel_w then quebrar_linha() end

        -- A linha nova nao cabe: a pagina fecha. Uma fonte sozinha maior que a area ainda ocupa
        -- uma pagina inteira, porque mostra-la transbordando e melhor que nunca mostra-la.
        if #atual > 0 and y + h > disponivel_h then
            paginas[#paginas + 1] = atual
            atual = {}
            x, y, altura_da_linha = 0, 0, 0
        end

        atual[#atual + 1] = { fonte = fonte, x = x, y = y }
        x = x + w + ESPACO
        altura_da_linha = math.max(altura_da_linha, h)
    end

    if #atual > 0 then paginas[#paginas + 1] = atual end
    if #paginas == 0 then paginas[1] = {} end
    return paginas
end

-- Tamanho da janela do livro, em funcao da tela de quem joga.
--
-- Uma janela fixa de 300 por 200 sobra numa tela grande e aperta numa pequena. Com o tamanho
-- informado pelo cliente, ela usa quase tudo -- deixando uma margem para nao encostar na borda --
-- e o teto existe porque uma janela de mil pixels de largura teria a lista de um lado e a receita
-- do outro longe demais para olhar as duas.
local function janela_do_livro(ctx)
    local tela = ctx.player.screen_size()
    if not tela then return 300, 200 end

    return math.max(260, math.min(tela.width - 40, 560)),
           math.max(180, math.min(tela.height - 40, 320))
end

local function fontes(ctx, item, modo)
    local lista = {}

    if modo == "produz" then
        for _, receita in ipairs(ctx.server.recipes_for(item, 32)) do
            lista[#lista + 1] = { tipo = "receita", dados = receita }
        end
        for _, processo in ipairs(ctx.server.processes({ produces = item, limit = 32 })) do
            lista[#lista + 1] = { tipo = "processo", dados = processo }
        end
        for _, fonte in ipairs(ctx.server.dropped_by(item, 32)) do
            lista[#lista + 1] = { tipo = "drop", de = fonte, para = item }
        end
    else
        for _, receita in ipairs(ctx.server.recipes_using(item, 32)) do
            lista[#lista + 1] = { tipo = "receita", dados = receita }
        end
        for _, processo in ipairs(ctx.server.processes({ uses = item, limit = 32 })) do
            lista[#lista + 1] = { tipo = "processo", dados = processo }
        end
        -- drops_of recusa um item que nao seja bloco nem entidade; pcall evita quebrar a tela.
        local ok, drops = pcall(function() return ctx.server.drops_of(item, 32) end)
        if ok then
            for _, saida in ipairs(drops) do
                lista[#lista + 1] = { tipo = "drop", de = item, para = saida }
            end
        end
    end

    return lista
end

-- Desenha uma fonte na posicao dada, qualquer que seja o tipo.
-- O rotulo so aparece sem filtro. Com uma aba escolhida, toda fonte na tela e da mesma categoria,
-- e repetir o nome em cada uma seria dizer o que a aba ja disse.
local function desenhar_fonte(fonte, x, y, com_rotulo)
    if fonte.tipo == "receita" then
        return (desenhar_receita(fonte.dados, x, y, com_rotulo))
    elseif fonte.tipo == "processo" then
        -- O rotulo fica acima da grade, entao o desenho comeca abaixo da posicao pedida.
        return (desenhar_processo(fonte.dados, x, y + 12, com_rotulo))
    end
    return (desenhar_drop(fonte.de, fonte.para, x, y, com_rotulo))
end

-- As categorias presentes na lista, na ordem em que aparecem, com quantas fontes cada uma tem.
--
-- Nao ha lista fixa de categorias: elas saem do que o item realmente tem. Um item so fabricavel
-- nao mostra aba de fornalha, e um mod que declara um processo novo ganha aba sem ninguem
-- registrar nada.
local function categorias(lista)
    local ordem = {}
    local por_nome = {}

    local icone_por_nome = {}

    for _, fonte in ipairs(lista) do
        local nome = acao_de(fonte)
        if not por_nome[nome] then
            por_nome[nome] = 0
            icone_por_nome[nome] = icone_de(fonte)
            ordem[#ordem + 1] = nome
        end
        por_nome[nome] = por_nome[nome] + 1
    end

    local resultado = {}
    for indice, nome in ipairs(ordem) do
        resultado[indice] = { nome = nome, total = por_nome[nome], icone = icone_por_nome[nome] }
    end
    return resultado
end

local function somente_da_aba(lista, aba)
    if not aba then return lista end

    local filtrada = {}
    for _, fonte in ipairs(lista) do
        if acao_de(fonte) == aba then filtrada[#filtrada + 1] = fonte end
    end
    return filtrada
end

-- Largura de um botao de aba. A fonte do jogo tem seis pixels por caractere na maioria das letras,
-- e o resto e folga: uma aba curta demais corta o nome, e o nome e o que a aba serve para dizer.
local function largura_da_aba(texto)
    return 14 + #texto * 6
end

-- Uma aba com icone e quadrada: o icone ocupa 16 px e a moldura o resto. O nome vai para o texto
-- de ajuda, que aparece ao parar o cursor -- assim a fileira de abas nao cresce com o nome de cada
-- estacao, e continua cabendo mesmo num item com muitas origens.
local LADO_DA_ABA = 22

local function livro(ctx)
    local estado = meu(ctx)
    local itens = filtrar(ctx, estado.busca)
    local janela_w, janela_h = janela_do_livro(ctx)

    -- A lista ocupa um terco da janela, e o resto e o painel de receitas. As colunas saem dessa
    -- largura, e nao do espaco ao lado do inventario: aqui a janela e propria.
    local colunas = math.max(3, math.min(9, math.floor((janela_w / 3) / CELULA)))
    local largura = colunas * CELULA
    local pagina = math.min(estado.pagina_busca or 1, paginas_de(#itens, colunas))
    local recorte = fatia(itens, pagina, colunas)

    local rodape = janela_h - 26
    local altura_lista = rodape - 60

    local elementos = {
        { type = "panel", style = "vanilla", x = 0, y = 0, w = janela_w, h = janela_h },
        { type = "label", x = 8, y = 8, text = "Catalogo", color = "#404040", shadow = false },

        { type = "input", id = "busca", x = 8, y = 22, w = largura, h = 18,
          value = estado.busca },
        { type = "label", x = 8, y = 44,
          text = #itens .. " resultado(s) - pag " .. pagina .. "/" .. paginas_de(#itens, colunas),
          color = "#404040", shadow = false },

        { type = "viewport", id = "area", x = 8, y = 56, w = largura, h = altura_lista,
          content = altura_da_grade(#recorte, colunas) },
        grade("itens", "area", recorte, colunas),

        { type = "button", id = "anterior", x = 8, y = rodape, w = 30, h = 18, text = "<" },
        { type = "button", id = "proxima", x = largura - 22, y = rodape, w = 30, h = 18, text = ">" },

        -- Separa a lista do painel de receitas. Sem ela as duas metades encostam e a tela parece
        -- uma coisa so, quando sao duas com funcoes diferentes.
        { type = "panel", style = "divider", x = largura + 14, y = 22, w = 2, h = janela_h - 44 }
    }

    if estado.item then
        elementos[#elementos + 1] = { type = "label", x = largura + 24, y = 22,
                                      text = estado.item, color = "#404040", shadow = false }
        elementos[#elementos + 1] = { type = "button", id = "alternar",
                                      x = largura + 24, y = 36, w = 80, h = 18,
                                      text = estado.modo == "produz" and "Ver usos" or "Ver receita" }

        local todas = fontes(ctx, estado.item, estado.modo)
        local abas = categorias(todas)
        local lista = somente_da_aba(todas, estado.aba)

        local painel_x = largura + 24
        local disponivel_w = janela_w - painel_x - 8

        -- Uma aba so nao e escolha: mostrar "Bancada" sozinha ocuparia uma linha para nao dizer
        -- nada que o rotulo da propria receita ja diz.
        local topo_das_fontes = 66
        if #abas > 1 then
            local aba_x = 0
            elementos[#elementos + 1] = { type = "button", id = "aba_0",
                                          x = painel_x, y = 62,
                                          w = largura_da_aba("Tudo"), h = LADO_DA_ABA,
                                          text = "Tudo" }
            aba_x = largura_da_aba("Tudo") + 4

            for indice, aba in ipairs(abas) do
                -- Quebra de linha quando as abas nao cabem, pelo mesmo motivo das receitas.
                if aba_x + LADO_DA_ABA > disponivel_w then
                    aba_x = 0
                    topo_das_fontes = topo_das_fontes + LADO_DA_ABA + 2
                end

                elementos[#elementos + 1] = { type = "button", id = "aba_" .. indice,
                                              x = painel_x + aba_x, y = topo_das_fontes - 4,
                                              w = LADO_DA_ABA, h = LADO_DA_ABA,
                                              item = aba.icone, text = "" }
                -- O item por cima do botao responde pelo cursor, e diz o nome da categoria.
                elementos[#elementos + 1] = { type = "item", x = painel_x + aba_x + 3,
                                              y = topo_das_fontes - 1, item = aba.icone,
                                              tooltip = aba.nome .. " (" .. aba.total .. ")" }
                aba_x = aba_x + LADO_DA_ABA + 2
            end
            -- Fecha a fileira de abas, separando-a do conteudo que elas filtram.
            elementos[#elementos + 1] = { type = "panel", style = "divider",
                                          x = painel_x, y = topo_das_fontes + LADO_DA_ABA - 2,
                                          w = disponivel_w, h = 2 }
            topo_das_fontes = topo_das_fontes + LADO_DA_ABA + 6
        end

        local disponivel_h = rodape - topo_das_fontes - 4
        local paginas = paginar(lista, disponivel_w, disponivel_h)
        local pagina_atual = math.min(estado.pagina_receita or 1, #paginas)
        local mostrou = false

        for _, posicionado in ipairs(paginas[pagina_atual]) do
            for _, elemento in ipairs(desenhar_fonte(posicionado.fonte,
                                                     painel_x + posicionado.x,
                                                     topo_das_fontes + posicionado.y,
                                                     estado.aba == nil)) do
                elementos[#elementos + 1] = elemento
            end
            mostrou = true
        end

        -- A navegacao so aparece quando ha mais de uma pagina; dizer "1/1" seria ruido.
        if #paginas > 1 then
            elementos[#elementos + 1] = { type = "button", id = "receita_anterior",
                                          x = painel_x, y = rodape, w = 24, h = 18, text = "<" }
            elementos[#elementos + 1] = { type = "label", x = painel_x + 30, y = rodape + 5,
                                          text = pagina_atual .. "/" .. #paginas
                                                 .. "  (" .. #lista .. ")",
                                          color = "#404040", shadow = false }
            elementos[#elementos + 1] = { type = "button", id = "receita_proxima",
                                          x = painel_x + 96, y = rodape, w = 24, h = 18, text = ">" }
        end

        if not mostrou then
            elementos[#elementos + 1] = { type = "label", x = largura + 24, y = 66,
                                          text = "Nada encontrado.", color = "#707070",
                                          shadow = false }
        end
    else
        elementos[#elementos + 1] = { type = "label", x = largura + 24, y = 22,
                                      text = "Clique em um item", color = "#707070",
                                      shadow = false }
    end

    return { title = "Catalogo", width = janela_w, height = janela_h,
             dim = true, elements = elementos }
end

---------------------------------------------------------------------------- eventos

-- Sobreposicao e tela usam o mesmo mecanismo de callback: para o mod, o clique chega igual.
mod.screen("hud", function(ctx)
    local estado = meu(ctx)
    local itens = todos(ctx)
    local colunas = colunas_para(ctx)

    if ctx.ui.element == "abrir_livro" then
        estado.pagina_busca = 1
        ctx.player.open_screen("livro", livro(ctx))
        return
    elseif ctx.ui.element == "itens" then
        -- O valor de um clique em grade e o indice da celula dentro da pagina; o deslocamento da
        -- pagina precisa ser somado, senao a primeira celula da pagina 3 abriria o primeiro item.
        local _, primeiro = fatia(itens, estado.pagina or 1, colunas)
        estado.item = itens[primeiro + tonumber(ctx.ui.value)]
        estado.modo = "produz"
        estado.pagina_busca = 1
        estado.pagina_receita = 1
        estado.aba = nil
        ctx.player.open_screen("livro", livro(ctx))
        return
    elseif ctx.ui.element == "proxima" then
        estado.pagina = math.min(paginas_de(#itens, colunas), (estado.pagina or 1) + 1)
    elseif ctx.ui.element == "anterior" then
        estado.pagina = math.max(1, (estado.pagina or 1) - 1)
    else
        return
    end

    -- Reenviar substitui o registro: os itens da pagina nova aparecem no proximo quadro, porque o
    -- desenho le o registro a cada quadro. Os botoes, que sao widgets criados quando a tela abriu,
    -- ficam onde estao -- por isso a posicao e o texto deles nao mudam entre paginas.
    ctx.player.set_overlay("hud", sobreposicao(ctx))
end)

mod.screen("livro", function(ctx)
    local estado = meu(ctx)
    local itens = filtrar(ctx, estado.busca)

    -- A mesma conta de livro(): a grade da janela propria nao depende do espaco do inventario.
    local janela_w = janela_do_livro(ctx)
    local colunas = math.max(3, math.min(9, math.floor((janela_w / 3) / CELULA)))

    if ctx.ui.element == "busca" and ctx.ui.action == "change" then
        estado.busca = ctx.ui.value
        estado.pagina_busca = 1
    elseif ctx.ui.element == "itens" then
        local _, primeiro = fatia(itens, estado.pagina_busca or 1, colunas)
        estado.item = itens[primeiro + tonumber(ctx.ui.value)]
        estado.modo = "produz"
        estado.pagina_receita = 1
        estado.aba = nil
    elseif ctx.ui.element == "alternar" then
        estado.modo = estado.modo == "produz" and "usa" or "produz"
        estado.pagina_receita = 1
        estado.aba = nil
    elseif ctx.ui.element:match("^aba_%d+$") then
        local indice = tonumber(ctx.ui.element:match("%d+"))
        local abas = categorias(fontes(ctx, estado.item, estado.modo))

        -- Zero e "Tudo": sem filtro. Os demais apontam para a categoria de mesmo indice.
        estado.aba = indice > 0 and abas[indice] and abas[indice].nome or nil
        estado.pagina_receita = 1
    elseif ctx.ui.element == "receita_proxima" or ctx.ui.element == "receita_anterior" then
        -- O mesmo empacotamento que livro() faz, senao a navegacao andaria para paginas que a
        -- tela nao mostra.
        local janela_w, janela_h = janela_do_livro(ctx)
        local painel_x = colunas * CELULA + 24
        local lista = somente_da_aba(fontes(ctx, estado.item, estado.modo), estado.aba)
        local paginas = #paginar(lista, janela_w - painel_x - 8, janela_h - 96)
        local passo = ctx.ui.element == "receita_proxima" and 1 or -1

        estado.pagina_receita =
                math.max(1, math.min(paginas, (estado.pagina_receita or 1) + passo))
    elseif ctx.ui.element == "proxima" then
        estado.pagina_busca = math.min(paginas_de(#itens, colunas),
                                       (estado.pagina_busca or 1) + 1)
    elseif ctx.ui.element == "anterior" then
        estado.pagina_busca = math.max(1, (estado.pagina_busca or 1) - 1)
    else
        return
    end

    -- Redesenhar sem reabrir preserva o foco e o cursor de quem esta digitando na busca.
    ctx.player.update_screen(livro(ctx))
end)

function on_player_joined(ctx)
    if not ctx.player.supports_screens() then
        ctx.player.send_message("Instale o loader no cliente para ver o catalogo.")
        return
    end

    ctx.player.set_overlay("hud", sobreposicao(ctx))
    ctx.player.send_message("Catalogo ativo: abra o inventario.")
end

return { on_player_joined = on_player_joined }

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
            or { busca = "", modo = "produz", pagina = 1, pagina_busca = 1 }
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
    local celulas = {}
    for indice, item in ipairs(itens) do
        celulas[indice] = { item = item, tooltip = item }
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
local function desenhar_receita(receita, x, y)
    local elementos = {}
    local colunas = receita.width > 0 and receita.width or #receita.ingredients

    local celulas = {}
    for posicao, alternativas in ipairs(receita.ingredients) do
        -- Uma posicao pode aceitar varios itens, quando a receita usa uma tag. Mostrar o primeiro
        -- e a leitura honesta enquanto nao houver como alternar entre eles na tela.
        -- Uma tag grande tem dezenas de itens, e a lista inteira estoura o teto de texto do
        -- protocolo. Mostrar as primeiras e dizer quantas faltam cabe e continua informando.
        local mostrados = {}
        for indice = 1, math.min(6, #alternativas) do mostrados[indice] = alternativas[indice] end
        if #alternativas > 6 then
            mostrados[#mostrados + 1] = "e mais " .. (#alternativas - 6) .. "..."
        end

        celulas[posicao] = { item = alternativas[1] or "minecraft:air",
                             tooltip = table.concat(mostrados, "\n") }
    end

    local linhas = math.ceil(#celulas / math.max(1, colunas))
    for _, caixa in ipairs(slots(x, y, math.max(1, colunas), linhas)) do
        elementos[#elementos + 1] = caixa
    end
    elementos[#elementos + 1] = { type = "grid", id = "entrada", x = x, y = y,
                                  columns = math.max(1, colunas), cell = CELULA, items = celulas }
    elementos[#elementos + 1] = { type = "label", x = x + math.max(1, colunas) * CELULA + 6,
                                  y = y + 4, text = "->", color = "#404040", shadow = false }
    elementos[#elementos + 1] = { type = "panel", style = "slot", border = 1,
                                  x = x + math.max(1, colunas) * CELULA + 22, y = y,
                                  w = 16, h = 16 }
    elementos[#elementos + 1] = { type = "item",
                                  x = x + math.max(1, colunas) * CELULA + 22, y = y,
                                  item = receita.output.item, count = receita.output.count,
                                  tooltip = receita.output.item }

    -- A altura ocupada volta junto: uma receita 3x3 tem 54 px e uma sem forma tem 18, entao um
    -- passo fixo empilharia uma sobre a outra. Quem desenha a proxima soma isto ao y.
    return elementos, math.max(CELULA, linhas * CELULA)
end

-- Um processo desenha como uma receita: entradas a esquerda, resultado a direita. O formato foi
-- escolhido proximo de proposito, para o catalogo nao precisar de dois desenhos paralelos.
local function desenhar_processo(processo, x, y)
    local elementos = {}
    local celulas = {}
    for posicao, item in ipairs(processo.inputs) do
        celulas[posicao] = { item = item, tooltip = item }
    end

    local colunas = math.max(1, #celulas)
    for _, caixa in ipairs(slots(x, y, colunas, 1)) do
        elementos[#elementos + 1] = caixa
    end
    elementos[#elementos + 1] = { type = "grid", id = "processo", x = x, y = y,
                                  columns = colunas, cell = CELULA, items = celulas }

    local direita = x + colunas * CELULA
    elementos[#elementos + 1] = { type = "label", x = direita + 6, y = y + 4, text = "->",
                                  color = "#404040", shadow = false }
    elementos[#elementos + 1] = { type = "panel", style = "slot", border = 1,
                                  x = direita + 22, y = y, w = 16, h = 16 }
    elementos[#elementos + 1] = { type = "item", x = direita + 22, y = y,
                                  item = processo.output.item, count = processo.output.count,
                                  tooltip = processo.output.item }

    -- A chance so aparece quando nao e certa: escrever "100%" em tudo seria ruido.
    local rotulo = processo.title
    if processo.output.chance < 1 then
        rotulo = rotulo .. " (" .. math.floor(processo.output.chance * 100) .. "%)"
    end
    elementos[#elementos + 1] = { type = "label", x = x, y = y - 10, text = rotulo,
                                  color = "#404040", shadow = false }

    return elementos, CELULA + 12
end

-- Um drop desenha como uma linha: o bloco, a seta e o item. Para a maior parte do jogo esta e a
-- resposta verdadeira sobre de onde um item vem.
local function desenhar_drop(bloco, item, x, y)
    return {
        { type = "panel", style = "slot", border = 1, x = x, y = y, w = 16, h = 16 },
        { type = "item", x = x, y = y, item = bloco, tooltip = bloco },
        { type = "label", x = x + 22, y = y + 4, text = "->", color = "#404040", shadow = false },
        { type = "panel", style = "slot", border = 1, x = x + 38, y = y, w = 16, h = 16 },
        { type = "item", x = x + 38, y = y, item = item, tooltip = item }
    }, CELULA + 4
end

local function livro(ctx)
    local estado = meu(ctx)
    local itens = filtrar(ctx, estado.busca)
    local colunas = colunas_para(ctx)
    local pagina = math.min(estado.pagina_busca or 1, paginas_de(#itens, colunas))
    local recorte = fatia(itens, pagina, colunas)
    local largura = colunas * CELULA

    local elementos = {
        { type = "panel", style = "vanilla", x = 0, y = 0, w = 300, h = 200 },
        { type = "label", x = 8, y = 8, text = "Catalogo", color = "#404040", shadow = false },

        { type = "input", id = "busca", x = 8, y = 22, w = largura, h = 18,
          value = estado.busca },
        { type = "label", x = 8, y = 44,
          text = #itens .. " resultado(s) - pag " .. pagina .. "/" .. paginas_de(#itens, colunas),
          color = "#404040", shadow = false },

        { type = "viewport", id = "area", x = 8, y = 56, w = largura,
          h = LINHAS_VISIVEIS * CELULA,
          content = altura_da_grade(#recorte, colunas) },
        grade("itens", "area", recorte, colunas),

        { type = "button", id = "anterior", x = 8, y = 170, w = 30, h = 18, text = "<" },
        { type = "button", id = "proxima", x = largura - 22, y = 170, w = 30, h = 18, text = ">" }
    }

    if estado.item then
        elementos[#elementos + 1] = { type = "label", x = largura + 24, y = 22,
                                      text = estado.item, color = "#404040", shadow = false }
        elementos[#elementos + 1] = { type = "button", id = "alternar",
                                      x = largura + 24, y = 36, w = 80, h = 18,
                                      text = estado.modo == "produz" and "Ver usos" or "Ver receita" }

        local topo = 66
        local mostrou = false

        if estado.modo == "produz" then
            -- Como se obtem: receita, processo declarado por mod, ou mineracao.
            for _, receita in ipairs(ctx.server.recipes_for(estado.item, 3)) do
                local desenho, altura = desenhar_receita(receita, largura + 24, topo)
                for _, elemento in ipairs(desenho) do elementos[#elementos + 1] = elemento end
                topo = topo + altura + 8
                mostrou = true
            end

            for _, processo in ipairs(ctx.server.processes({ produces = estado.item, limit = 3 })) do
                local desenho, altura = desenhar_processo(processo, largura + 24, topo + 10)
                for _, elemento in ipairs(desenho) do elementos[#elementos + 1] = elemento end
                topo = topo + altura + 12
                mostrou = true
            end

            for _, bloco in ipairs(ctx.server.dropped_by(estado.item, 3)) do
                local desenho, altura = desenhar_drop(bloco, estado.item, largura + 24, topo)
                for _, elemento in ipairs(desenho) do elementos[#elementos + 1] = elemento end
                topo = topo + altura
                mostrou = true
            end
        else
            -- Para que serve: receita que o consome, processo que o consome, e o que ele derruba
            -- quando o proprio item e um bloco.
            for _, receita in ipairs(ctx.server.recipes_using(estado.item, 3)) do
                local desenho, altura = desenhar_receita(receita, largura + 24, topo)
                for _, elemento in ipairs(desenho) do elementos[#elementos + 1] = elemento end
                topo = topo + altura + 8
                mostrou = true
            end

            for _, processo in ipairs(ctx.server.processes({ uses = estado.item, limit = 3 })) do
                local desenho, altura = desenhar_processo(processo, largura + 24, topo + 10)
                for _, elemento in ipairs(desenho) do elementos[#elementos + 1] = elemento end
                topo = topo + altura + 12
                mostrou = true
            end

            -- drops_of recusa um item que nao seja bloco; pcall evita quebrar a tela por isso.
            local ok, drops = pcall(function() return ctx.server.drops_of(estado.item, 3) end)
            if ok then
                for _, item in ipairs(drops) do
                    local desenho, altura = desenhar_drop(estado.item, item, largura + 24, topo)
                    for _, elemento in ipairs(desenho) do elementos[#elementos + 1] = elemento end
                    topo = topo + altura
                    mostrou = true
                end
            end
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

    return { title = "Catalogo", width = 300, height = 200, dim = true, elements = elementos }
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
    local colunas = colunas_para(ctx)

    if ctx.ui.element == "busca" and ctx.ui.action == "change" then
        estado.busca = ctx.ui.value
        estado.pagina_busca = 1
    elseif ctx.ui.element == "itens" then
        local _, primeiro = fatia(itens, estado.pagina_busca or 1, colunas)
        estado.item = itens[primeiro + tonumber(ctx.ui.value)]
        estado.modo = "produz"
    elseif ctx.ui.element == "alternar" then
        estado.modo = estado.modo == "produz" and "usa" or "produz"
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

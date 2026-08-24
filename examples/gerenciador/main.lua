-- Gerenciador de Mods
--
-- O gerenciador de mods da plataforma -- o Mod Menu do Fabric, a lista do NeoForge -- enxerga um
-- mod so: o proprio loader. Os mods Lua vivem dentro dele e nao aparecem la, entao quem joga nao
-- tem como saber o que esta instalado, em que versao, nem por que algo parou de funcionar.
--
-- Este mod fecha essa lacuna com a propria API do loader: ctx.server.mods() devolve o que cada
-- manifesto declarou, e o resto e uma tela desenhada. Nao ha nada de especial nele -- e o mesmo
-- que qualquer mod pode fazer, e por isso serve de exemplo.
--
--   /mod gerenciador          abre a lista
--   /mod gerenciador <id>     abre direto o detalhe daquele mod

local LARGURA = 260
local ALTURA = 200
local POR_PAGINA = 6
local ALTURA_LINHA = 22

local COR_FUNDO = "#101018E0"
local COR_TITULO = "#FFD966"
local COR_TEXTO = "#E0E0E0"
local COR_FRACO = "#9090A0"
local COR_LINHA = "#FFFFFF14"
local COR_DESLIGADO = "#C06060"

-- O estado e por jogador, e nao por mod: dois jogadores com a lista aberta estao cada um na sua
-- pagina. ctx.state e compartilhado pelo mod inteiro, entao a chave precisa ser o uuid.
local function meu(ctx)
    local uuid = ctx.player.uuid
    ctx.state.por_jogador = ctx.state.por_jogador or {}
    ctx.state.por_jogador[uuid] = ctx.state.por_jogador[uuid] or { pagina = 1 }
    return ctx.state.por_jogador[uuid]
end

--- Corta o texto para caber na largura pedida, com reticencias.
-- Um texto que estoura a linha e desenhado por cima do que estiver ao lado, entao cortar aqui e o
-- que mantem a tela legivel com um mod de descricao longa.
local function encurtar(texto, limite)
    if texto == nil or texto == "" then return "" end
    if #texto <= limite then return texto end
    return string.sub(texto, 1, limite - 1) .. "…"
end

--- A lista de mods, sempre na mesma ordem.
-- ctx.server.mods() ja devolve na ordem de carga; ordenar por nome deixa a lista estavel para quem
-- le, porque a ordem de carga muda quando uma dependencia entra.
local function listar(ctx)
    local mods = ctx.server.mods()
    table.sort(mods, function(a, b) return a.name < b.name end)
    return mods
end

--- A tela de lista, com paginacao.
local function desenhar_lista(ctx)
    local mods = listar(ctx)
    local estado = meu(ctx)

    local paginas = math.max(1, math.ceil(#mods / POR_PAGINA))
    -- A pagina e presa a faixa valida toda vez, e nao so ao virar: um mod recarregado pode encurtar
    -- a lista, e a pagina guardada apontaria para o vazio.
    if estado.pagina > paginas then estado.pagina = paginas end
    if estado.pagina < 1 then estado.pagina = 1 end

    local elementos = {
        { type = "panel", x = 0, y = 0, w = LARGURA, h = ALTURA, color = COR_FUNDO },
        { type = "label", x = 12, y = 10, text = "Mods do Lua Loader", color = COR_TITULO },
        { type = "label", x = 12, y = 24, color = COR_FRACO,
          text = #mods .. " instalado(s)  ·  pagina " .. estado.pagina .. "/" .. paginas },
        { type = "panel", x = 10, y = 38, w = LARGURA - 20, h = 1, color = COR_LINHA },
    }

    local primeiro = (estado.pagina - 1) * POR_PAGINA + 1
    local y = 46

    for i = primeiro, math.min(primeiro + POR_PAGINA - 1, #mods) do
        local m = mods[i]

        -- Cada linha e um botao que ocupa a largura toda: clicar em qualquer ponto abre o detalhe,
        -- que e o que se espera de uma lista. O id carrega o indice para o clique saber qual foi.
        elementos[#elementos + 1] = {
            type = "button", id = "abrir:" .. m.id,
            x = 10, y = y, w = LARGURA - 20, h = ALTURA_LINHA, text = ""
        }
        elementos[#elementos + 1] = {
            type = "label", x = 16, y = y + 3,
            text = encurtar(m.name, 26),
            color = m.enabled and COR_TEXTO or COR_DESLIGADO
        }
        elementos[#elementos + 1] = {
            type = "label", x = 16, y = y + 13, color = COR_FRACO,
            text = encurtar("v" .. m.version .. "  ·  " .. m.blocks .. " bloco(s), "
                            .. m.items .. " item(ns)", 40)
        }
        y = y + ALTURA_LINHA + 2
    end

    if #mods == 0 then
        elementos[#elementos + 1] = {
            type = "label", x = 16, y = 60, color = COR_FRACO,
            text = "Nenhum mod carregado."
        }
    end

    local rodape = ALTURA - 28
    elementos[#elementos + 1] = { type = "panel", x = 10, y = rodape - 6,
                                  w = LARGURA - 20, h = 1, color = COR_LINHA }
    elementos[#elementos + 1] = { type = "button", id = "anterior", x = 10, y = rodape,
                                  w = 62, h = 20, text = "< Anterior" }
    elementos[#elementos + 1] = { type = "button", id = "proxima", x = 76, y = rodape,
                                  w = 62, h = 20, text = "Proxima >" }
    elementos[#elementos + 1] = { type = "button", id = "instalar", x = 142, y = rodape,
                                  w = 46, h = 20, text = "Instalar" }
    elementos[#elementos + 1] = { type = "button", id = "fechar", x = LARGURA - 66, y = rodape,
                                  w = 56, h = 20, text = "Fechar" }

    return {
        title = "Gerenciador de Mods",
        width = LARGURA,
        height = ALTURA,
        blur = true,
        dim = true,
        elements = elementos
    }
end

--- A tela de detalhe de um mod.
local function desenhar_detalhe(ctx, id)
    local escolhido
    for _, m in ipairs(listar(ctx)) do
        if m.id == id then escolhido = m end
    end

    -- O mod pode ter sumido entre abrir a lista e clicar: uma recarga acontece no meio.
    if escolhido == nil then
        return desenhar_lista(ctx)
    end

    local elementos = {
        { type = "panel", x = 0, y = 0, w = LARGURA, h = ALTURA, color = COR_FUNDO },
        { type = "label", x = 12, y = 10, text = encurtar(escolhido.name, 30), color = COR_TITULO },
        { type = "label", x = 12, y = 24, color = COR_FRACO,
          text = escolhido.id .. "  ·  v" .. escolhido.version },
        { type = "panel", x = 10, y = 38, w = LARGURA - 20, h = 1, color = COR_LINHA },
    }

    local y = 46
    if escolhido.description ~= "" then
        -- A descricao e quebrada em linhas de tamanho fixo porque a tela desenha texto sem
        -- quebra automatica: uma frase longa sairia pela borda direita.
        local resto = escolhido.description
        while #resto > 0 and y < 96 do
            elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_TEXTO,
                                          text = string.sub(resto, 1, 44) }
            resto = string.sub(resto, 45)
            y = y + 11
        end
        y = y + 4
    end

    local conteudo = escolhido.blocks .. " bloco(s)  ·  " .. escolhido.items .. " item(ns)  ·  "
                     .. escolhido.recipes .. " receita(s)"
    elementos[#elementos + 1] = { type = "label", x = 14, y = y, text = conteudo, color = COR_TEXTO }
    y = y + 12

    elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_TEXTO,
                                  text = escolhido.events .. " evento(s) mapeado(s)" }
    y = y + 16

    if #escolhido.authors > 0 then
        elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_FRACO,
                                      text = "Autoria: "
                                             .. encurtar(table.concat(escolhido.authors, ", "), 36) }
        y = y + 14
    end

    -- As permissoes sao o que mais importa saber sobre um mod que nao se escreveu: e a lista do
    -- que ele pode fazer com o mundo e com quem joga.
    elementos[#elementos + 1] = { type = "label", x = 14, y = y, text = "Permissoes:",
                                  color = COR_TITULO }
    y = y + 11

    if #escolhido.permissions == 0 then
        elementos[#elementos + 1] = { type = "label", x = 18, y = y, color = COR_FRACO,
                                      text = "nenhuma" }
    else
        local linha = ""
        for _, permissao in ipairs(escolhido.permissions) do
            local tentativa = linha == "" and permissao or (linha .. ", " .. permissao)
            if #tentativa > 42 then
                elementos[#elementos + 1] = { type = "label", x = 18, y = y, text = linha,
                                              color = COR_FRACO }
                y = y + 10
                linha = permissao
            else
                linha = tentativa
            end
            if y > ALTURA - 46 then break end
        end
        if linha ~= "" and y <= ALTURA - 46 then
            elementos[#elementos + 1] = { type = "label", x = 18, y = y, text = linha,
                                          color = COR_FRACO }
        end
    end

    local rodape = ALTURA - 28
    elementos[#elementos + 1] = { type = "button", id = "voltar", x = 10, y = rodape,
                                  w = 80, h = 20, text = "< Voltar" }
    elementos[#elementos + 1] = { type = "button", id = "fechar", x = LARGURA - 70, y = rodape,
                                  w = 60, h = 20, text = "Fechar" }

    return {
        title = "Gerenciador de Mods",
        width = LARGURA,
        height = ALTURA,
        blur = true,
        dim = true,
        elements = elementos
    }
end

--- A tela de instalacao por link.
--
-- E aqui que a API de instalacao aparece de verdade. O fluxo tem dois passos de proposito: colar o
-- endereco so baixa e valida, e o que volta e a lista de permissoes que o mod pede. Instalar de
-- verdade e um segundo clique, depois de alguem ler aquilo -- porque quem cola um link nao tem como
-- saber o que vem nele.
local function desenhar_instalar(ctx)
    local estado = meu(ctx)
    local liberado = ctx.server.install_allowed()

    local elementos = {
        { type = "panel", x = 0, y = 0, w = LARGURA, h = ALTURA, color = COR_FUNDO },
        { type = "label", x = 12, y = 10, text = "Instalar mod por link", color = COR_TITULO },
        { type = "panel", x = 10, y = 26, w = LARGURA - 20, h = 1, color = COR_LINHA },
    }

    if not liberado then
        -- Recusar sem dizer por que faria quem administra procurar um defeito onde ha uma escolha.
        elementos[#elementos + 1] = { type = "label", x = 14, y = 40, color = COR_DESLIGADO,
                                      text = "Instalacao pela API desligada" }
        elementos[#elementos + 1] = { type = "label", x = 14, y = 54, color = COR_FRACO,
                                      text = "Com ela ligada, um mod pode instalar" }
        elementos[#elementos + 1] = { type = "label", x = 14, y = 65, color = COR_FRACO,
                                      text = "outros -- e um mod publicado em pedacos" }
        elementos[#elementos + 1] = { type = "label", x = 14, y = 76, color = COR_FRACO,
                                      text = "pode oferecer os modulos aqui dentro." }

        if ctx.server.is_operator() then
            elementos[#elementos + 1] = { type = "button", id = "ligar_api", x = 14, y = 96,
                                          w = 150, h = 20, text = "Liberar instalacao" }
            elementos[#elementos + 1] = { type = "label", x = 14, y = 120, color = COR_FRACO,
                                          text = "A decisao fica gravada no servidor." }
        else
            elementos[#elementos + 1] = { type = "label", x = 14, y = 96, color = COR_FRACO,
                                          text = "Um operador precisa liberar." }
        end
    else
        elementos[#elementos + 1] = { type = "label", x = 14, y = 34, color = COR_TEXTO,
                                      text = "Endereco do mod.json:" }
        elementos[#elementos + 1] = { type = "input", id = "url", x = 14, y = 46,
                                      w = LARGURA - 28, h = 16, value = estado.url or "" }
        elementos[#elementos + 1] = { type = "button", id = "buscar", x = 14, y = 68,
                                      w = 90, h = 20, text = "Ver o que e" }

        local previa = estado.previa
        if previa then
            local y = 96
            elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_TITULO,
                                          text = encurtar(previa.name .. "  v" .. previa.version, 34) }
            y = y + 12
            elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_TEXTO,
                                          text = previa.blocks .. " bloco(s), "
                                                 .. previa.items .. " item(ns)"
                                                 .. (previa.replaces and "  ·  SUBSTITUI" or "") }
            y = y + 14

            -- As permissoes sao a razao de existir o passo intermediario: e a lista do que aquele
            -- codigo vai poder fazer com o mundo e com quem joga.
            elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_TITULO,
                                          text = "Pede permissao para:" }
            y = y + 11
            if #previa.permissions == 0 then
                elementos[#elementos + 1] = { type = "label", x = 18, y = y, color = COR_FRACO,
                                              text = "nada" }
                y = y + 11
            else
                local linha = ""
                for _, permissao in ipairs(previa.permissions) do
                    local tentativa = linha == "" and permissao or (linha .. ", " .. permissao)
                    if #tentativa > 42 then
                        elementos[#elementos + 1] = { type = "label", x = 18, y = y, text = linha,
                                                      color = COR_FRACO }
                        y = y + 10
                        linha = permissao
                    else
                        linha = tentativa
                    end
                    if y > ALTURA - 54 then break end
                end
                if linha ~= "" and y <= ALTURA - 54 then
                    elementos[#elementos + 1] = { type = "label", x = 18, y = y, text = linha,
                                                  color = COR_FRACO }
                    y = y + 11
                end
            end

            if previa.needs_restart then
                -- O registro do jogo fecha na inicializacao: bloco e item declarados por um mod
                -- instalado agora so existem no proximo reinicio. Dizer antes evita quem instalou
                -- procurar um bloco que nao aparece e concluir que a instalacao falhou.
                elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_DESLIGADO,
                                              text = "Comandos valem ja; blocos ao reiniciar." }
            else
                elementos[#elementos + 1] = { type = "label", x = 14, y = y, color = COR_TEXTO,
                                              text = "Vale assim que instalar." }
            end

            elementos[#elementos + 1] = { type = "button", id = "confirmar", x = 110, y = 68,
                                          w = 90, h = 20, text = "Instalar" }
        end

        if estado.aviso then
            elementos[#elementos + 1] = { type = "label", x = 14, y = ALTURA - 46,
                                          color = COR_DESLIGADO,
                                          text = encurtar(estado.aviso, 44) }
        end
    end

    -- O interruptor fica visivel dos dois lados: desligar precisa ser tao facil quanto ligar, ou a
    -- chave vira algo que so se liga.
    if liberado and ctx.server.is_operator() then
        elementos[#elementos + 1] = { type = "button", id = "desligar_api", x = 96, y = ALTURA - 28,
                                      w = 80, h = 20, text = "Desligar API" }
    end

    local rodape = ALTURA - 28
    elementos[#elementos + 1] = { type = "button", id = "voltar", x = 10, y = rodape,
                                  w = 80, h = 20, text = "< Voltar" }
    elementos[#elementos + 1] = { type = "button", id = "fechar", x = LARGURA - 66, y = rodape,
                                  w = 56, h = 20, text = "Fechar" }

    return {
        title = "Instalar mod",
        width = LARGURA,
        height = ALTURA,
        blur = true,
        dim = true,
        elements = elementos
    }
end

--- Desenha a tela conforme onde o jogador esta.
local function desenhar(ctx)
    local estado = meu(ctx)
    if estado.aba == "instalar" then
        return desenhar_instalar(ctx)
    end
    if estado.detalhe then
        return desenhar_detalhe(ctx, estado.detalhe)
    end
    return desenhar_lista(ctx)
end

mod.screen("lista", function(ctx)
    -- Sem jogador nao ha tela: o evento so chega por um clique de alguem, mas um teste ou uma
    -- recarga podem chamar o callback sem contexto de jogador.
    if ctx.player == nil then return end

    local estado = meu(ctx)
    local acao = ctx.ui.action
    local elemento = ctx.ui.element or ""

    if acao == "close" then
        -- Sair pela tecla de fechar tambem volta a lista ao inicio, para a proxima abertura nao
        -- comecar num detalhe que o jogador ja esqueceu que abriu.
        estado.detalhe = nil
        return
    end

    -- O campo de texto manda cada tecla como change; guardar aqui e o que faz o endereco
    -- sobreviver ao redesenho que vem depois de qualquer clique.
    if elemento == "url" and (acao == "change" or acao == "submit") then
        estado.url = ctx.ui.value
        if acao ~= "submit" then return end
    elseif acao ~= "click" then
        return
    end

    if elemento == "fechar" then
        estado.detalhe = nil
        ctx.player.close_screen()
        return
    end

    if elemento == "ligar_api" or elemento == "desligar_api" then
        local ok, erro = pcall(function()
            return ctx.server.set_install_api(elemento == "ligar_api")
        end)
        if not ok then estado.aviso = tostring(erro) end
    elseif elemento == "instalar" then
        estado.aba = "instalar"
        estado.previa = nil
        estado.aviso = nil
    elseif elemento == "buscar" or (elemento == "url" and acao == "submit") then
        estado.previa = nil
        estado.aviso = nil

        -- pcall porque a API estoura com o motivo -- endereco fora do ar, manifesto recusado,
        -- instalacao desligada. Sem ele o erro so apareceria no log, e a tela ficaria muda.
        local ok, resultado = pcall(function()
            return ctx.server.install_preview(estado.url or "")
        end)
        if ok then
            estado.previa = resultado
        else
            estado.aviso = tostring(resultado)
        end
    elseif elemento == "confirmar" and estado.previa then
        local ok, resultado = pcall(function()
            return ctx.server.install_confirm(estado.previa.id)
        end)
        if ok then
            ctx.player.send_message("Mod " .. estado.previa.name .. " instalado.")

            -- O que o loader conseguiu ligar agora e o que ele nao conseguiu sao coisas
            -- diferentes, e dizer as duas evita a pergunta obvia: "instalou, e agora?"
            if resultado.active then
                ctx.player.send_message("Os comandos e eventos dele ja valem.")
            end
            if resultado.needs_restart then
                ctx.player.send_message("Blocos e itens so aparecem ao reiniciar o jogo.")
            end

            estado.aba = nil
            estado.previa = nil
            estado.url = nil
        else
            estado.aviso = tostring(resultado)
        end
    elseif elemento == "voltar" then
        if estado.aba == "instalar" then
            estado.aba = nil
            estado.previa = nil
            estado.aviso = nil
        else
            estado.detalhe = nil
        end
    elseif elemento == "anterior" then
        estado.pagina = math.max(1, estado.pagina - 1)
    elseif elemento == "proxima" then
        estado.pagina = estado.pagina + 1
    elseif string.sub(elemento, 1, 6) == "abrir:" then
        estado.detalhe = string.sub(elemento, 7)
    end

    -- O retorno do callback e ignorado pelo runtime: quem redesenha e update_screen. Devolver a
    -- tabela e o erro mais facil de cometer aqui, e nao da erro nenhum -- a tela so nao muda.
    ctx.player.update_screen(desenhar(ctx))
end)

mod.command("gerenciador", function(ctx)
    if ctx.player == nil then
        -- Pelo console nao ha tela para abrir; o log responde com o que a tela mostraria.
        for _, m in ipairs(listar(ctx)) do
            ctx.log.info(string.format("%-18s v%-8s %d bloco(s), %d item(ns)%s",
                    m.id, m.version, m.blocks, m.items, m.enabled and "" or "  [desligado]"))
        end
        return
    end

    -- Uma plataforma sem tela desenhada recusa aqui em vez de abrir nada: perguntar antes e o que
    -- evita o comando prometer uma janela que nunca aparece.
    if not ctx.player.supports_screens() then
        ctx.player.send_message("Este cliente nao desenha telas; use /mod list no console.")
        return
    end

    local estado = meu(ctx)
    estado.detalhe = ctx.subcommand ~= "" and ctx.subcommand or nil
    estado.pagina = estado.pagina or 1

    ctx.player.open_screen("lista", desenhar(ctx))
end)

return {}

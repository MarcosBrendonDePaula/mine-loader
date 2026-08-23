-- Painel de Exemplo
--
-- Mostra a interface customizada: uma tela desenhada pelo mod e um HUD fixo.
-- Abra com /mod painel

-- Um modulo do proprio mod, carregado uma vez e compartilhado entre os scripts.
local ui = mod.import("lib/ui.lua")

-- Em Lua um local so e visivel a partir da linha em que aparece, entao tudo que as funcoes
-- abaixo usam precisa ser declarado aqui em cima.
local META = 10

local COR_FUNDO   = ui.CORES.fundo
local COR_TITULO  = ui.CORES.titulo
local COR_TEXTO   = ui.CORES.texto
local COR_BARRA   = ui.CORES.barra

--- Monta a descricao da tela a partir do estado.
-- Uma tela e apenas dados: uma lista de elementos com posicao e tamanho.
local function desenhar(ctx)
    local contador = math.min(META, ctx.state.contador or 0)
    local progresso = contador / META

    return {
        title = "Painel do Loader",
        width = 220,
        height = 160,

        -- O desfoque do jogo por tras da janela. O painel do mod continua na frente dele.
        blur = true,
        dim = true,

        elements = {
            -- Fundo da janela.
            { type = "panel", x = 0, y = 0, w = 220, h = 160, color = COR_FUNDO },

            -- Titulo montado pelo modulo de componentes.
            ui.titulo(10, 10, "Painel do Loader"),

            { type = "label", x = 10, y = 34, text = "Cliques: " .. contador, color = COR_TEXTO },
            { type = "label", x = 10, y = 46, text = "Meta: " .. META, color = COR_TEXTO },

            -- Barra proporcional, de 0 a 1.
            { type = "progress", x = 10, y = 62, w = 200, h = 8, progress = progresso, color = COR_BARRA },

            -- Icone de item do jogo, com quantidade.
            { type = "item", x = 10, y = 78, item = "minecraft:diamond", count = math.max(1, contador) },

            -- Botoes: cada um devolve um evento com o proprio id.
            { type = "button", id = "somar",  x = 40,  y = 78, w = 60, h = 20, text = "Somar" },
            { type = "button", id = "zerar",  x = 105, y = 78, w = 60, h = 20, text = "Zerar" },
            { type = "button", id = "fechar", x = 10,  y = 108, w = 200, h = 20, text = "Fechar" },

            -- Campo de texto: cada tecla volta como change, e Enter como submit.
            -- O tamanho precisa caber dentro da janela: a altura vai de y ate y + h.
            { type = "label", x = 10, y = 130, text = "Seu nome:", color = COR_TEXTO },
            { type = "input", id = "nome", x = 10, y = 140, w = 200, h = 16,
              value = ctx.state.nome or "" }
        }
    }
end

--- Redesenha o HUD a partir do estado.
-- O HUD nao se atualiza sozinho: quem muda o estado precisa reenvia-lo. Sem isso ele congela
-- no valor que tinha quando foi definido, que e o erro mais facil de cometer aqui.
local function atualizar_hud(ctx)
    -- O contador nao passa da meta: mostrar 11/10 confundiria mais do que informaria.
    local contador = math.min(META, ctx.state.contador or 0)
    local progresso = contador / META

    ctx.player.set_hud({
        { type = "panel", x = 2, y = 2, w = 96, h = 28, color = "#00000080" },
        { type = "label", x = 6, y = 6, text = "Painel: " .. contador .. "/" .. META, color = COR_TITULO },
        { type = "progress", x = 6, y = 20, w = 88, h = 5, progress = progresso, color = COR_BARRA }
    })
end

-- A logica da tela e registrada uma vez e vale para qualquer jogador que a abrir.
mod.screen("principal", function(ctx)
    if ctx.player == nil then
        return
    end

    if ctx.ui.action == "close" then
        ctx.log.info("O jogador fechou o painel.")
        return
    end

    if ctx.ui.element == "somar" then
        ctx.state.contador = math.min(META, (ctx.state.contador or 0) + 1)
    elseif ctx.ui.element == "zerar" then
        ctx.state.contador = 0
    elseif ctx.ui.element == "fechar" then
        ctx.player.close_screen()
        return
    elseif ctx.ui.element == "nome" then
        -- O valor digitado chega em ctx.ui.value a cada tecla.
        ctx.state.nome = ctx.ui.value

        -- Enter no campo chega como submit, e nao como mais um change.
        if ctx.ui.action == "submit" then
            ctx.player.send_message("Nome confirmado: " .. ctx.ui.value)
        end
    end

    -- Redesenhar mantem a tela aberta e preserva o texto ja digitado.
    ctx.player.update_screen(desenhar(ctx))

    -- O HUD tambem precisa ser reenviado para acompanhar o novo valor.
    atualizar_hud(ctx)
end)

local function on_player_joined(ctx)
    if ctx.player == nil then
        return
    end

    -- O HUD fica sobre o jogo: nao captura o mouse nem pausa nada.
    atualizar_hud(ctx)
    ctx.player.send_message("Painel: /mod painel abre a tela, /mod painel somar move o HUD.")
end

mod.command("painel", function(ctx)
    if ctx.player == nil then
        return
    end

    if ctx.subcommand == "hud" then
        -- Uma lista vazia limpa o HUD.
        ctx.player.set_hud({})
        ctx.player.send_message("HUD limpo. Use /mod painel hud on para voltar.")
        return
    end

    if ctx.subcommand == "hudon" or ctx.argv[2] == "on" then
        atualizar_hud(ctx)
        ctx.player.send_message("HUD ligado.")
        return
    end

    -- Testar o HUD sem depender da tela: /mod painel somar
    if ctx.subcommand == "somar" then
        ctx.state.contador = math.min(META, (ctx.state.contador or 0) + 1)
        atualizar_hud(ctx)
        ctx.player.send_message("Contador: " .. ctx.state.contador .. "/" .. META
            .. " (veja o HUD no canto)")
        return
    end

    if ctx.subcommand == "zerar" then
        ctx.state.contador = 0
        atualizar_hud(ctx)
        ctx.player.send_message("Contador zerado.")
        return
    end

    -- Um cliente sem o loader nao consegue mostrar a tela; o mod decide o que fazer.
    if not ctx.player.supports_screens() then
        ctx.player.send_message("Seu cliente nao tem o loader instalado; use /mod painel somar.")
        return
    end

    ctx.log.info("Abrindo o painel para " .. ctx.player.name .. ".")

    ctx.player.open_screen("principal", desenhar(ctx))
end)

return { on_player_joined = on_player_joined }

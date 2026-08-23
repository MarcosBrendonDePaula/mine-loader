-- Painel de Exemplo
--
-- Mostra a interface customizada: uma tela desenhada pelo mod e um HUD fixo.
-- Abra com /mod painel

local COR_FUNDO   = "#101018E0"
local COR_TITULO  = "#FFD700"
local COR_TEXTO   = "#FFFFFF"
local COR_BARRA   = "#4CAF50"

--- Monta a descricao da tela a partir do estado.
-- Uma tela e apenas dados: uma lista de elementos com posicao e tamanho.
local function desenhar(ctx)
    local contador = ctx.state.contador or 0
    local progresso = math.min(1.0, contador / 10)

    return {
        title = "Painel do Loader",
        width = 220,
        height = 140,
        elements = {
            -- Fundo da janela.
            { type = "panel", x = 0, y = 0, w = 220, h = 140, color = COR_FUNDO },

            -- Titulo em escala maior.
            { type = "label", x = 10, y = 10, text = "Painel do Loader", color = COR_TITULO, scale = 1.5 },

            { type = "label", x = 10, y = 34, text = "Cliques: " .. contador, color = COR_TEXTO },
            { type = "label", x = 10, y = 46, text = "Meta: 10", color = COR_TEXTO },

            -- Barra proporcional, de 0 a 1.
            { type = "progress", x = 10, y = 62, w = 200, h = 8, progress = progresso, color = COR_BARRA },

            -- Icone de item do jogo, com quantidade.
            { type = "item", x = 10, y = 78, item = "minecraft:diamond", count = math.max(1, contador) },

            -- Botoes: cada um devolve um evento com o proprio id.
            { type = "button", id = "somar",  x = 40,  y = 78, w = 60, h = 20, text = "Somar" },
            { type = "button", id = "zerar",  x = 105, y = 78, w = 60, h = 20, text = "Zerar" },
            { type = "button", id = "fechar", x = 10,  y = 108, w = 200, h = 20, text = "Fechar" },

            -- Campo de texto: o que for digitado volta como evento change.
            { type = "input", id = "nome", x = 10, y = 132, w = 200, h = 0, value = ctx.state.nome or "" }
        }
    }
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
        ctx.state.contador = (ctx.state.contador or 0) + 1
    elseif ctx.ui.element == "zerar" then
        ctx.state.contador = 0
    elseif ctx.ui.element == "fechar" then
        ctx.player.close_screen()
        return
    elseif ctx.ui.element == "nome" then
        -- O valor digitado chega em ctx.ui.value.
        ctx.state.nome = ctx.ui.value
    end

    -- Redesenhar mantem a tela aberta e preserva o texto ja digitado.
    ctx.player.update_screen(desenhar(ctx))
end)

local function on_player_joined(ctx)
    if ctx.player == nil then
        return
    end

    -- O HUD fica sobre o jogo: nao captura o mouse nem pausa nada.
    ctx.player.set_hud({
        { type = "label", x = 4, y = 4, text = "Loader ativo", color = COR_TITULO },
        { type = "progress", x = 4, y = 16, w = 80, h = 4,
          progress = math.min(1.0, (ctx.state.contador or 0) / 10), color = COR_BARRA }
    })
end

mod.command("painel", function(ctx)
    if ctx.player == nil then
        return
    end

    if ctx.subcommand == "hud" then
        -- Uma lista vazia limpa o HUD.
        ctx.player.set_hud({})
        ctx.player.send_message("HUD limpo.")
        return
    end

    -- Um cliente sem o loader nao consegue mostrar a tela; o mod decide o que fazer.
    if not ctx.player.supports_screens() then
        ctx.player.send_message("Seu cliente nao tem o loader instalado.")
        return
    end

    ctx.player.open_screen("principal", desenhar(ctx))
end)

return { on_player_joined = on_player_joined }

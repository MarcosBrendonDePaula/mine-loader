-- Minimap Demo
--
-- Minimapa de superfície que acompanha o jogador sozinho, desenhado no HUD declarativo.
--   /mod minimap_demo on|off
--   /mod minimap_demo zoom <1..4>
--
-- Três decisões sustentam o resto, e nenhuma é óbvia:
--
-- 1. O mapa mostra a **superfície**, não o bloco sob os pés. `top_y` responde a altura do primeiro
--    bloco sólido, e é isso que faz uma montanha parecer uma montanha em vez de uma mancha da cor
--    do chão da caverna em que o jogador está.
-- 2. O terreno é lido uma vez por coluna e guardado. Sem cache, uma volta do laço faria milhares de
--    leituras de mundo e estouraria o orçamento de 20 ms do callback — a leitura de bloco distante
--    ainda paga carregamento de chunk. Com cache, andar custa só a faixa que entrou pela borda.
-- 3. As células viram faixas horizontais antes de virar elementos. Uma tela aceita 256 elementos;
--    um mapa de 25x25 tem 625 células. Terreno real repete cor no eixo X, então juntar vizinhas
--    iguais numa faixa só cabe com folga — e é o que permite o mapa ser grande em vez de um
--    quadradinho de nove por nove.

local TILE = 2                 -- lado de uma célula, em pixels de tela
local RADIUS = 20              -- 41x41 colunas
local INTERVALO = 4            -- tiques entre atualizações; 4 = cinco vezes por segundo
local ORCAMENTO_COLUNAS = 90   -- leituras de terreno novas por volta
local IDADE_MAXIMA = 150       -- voltas até uma coluna já lida ser conferida de novo
local MAX_FAIXAS = 240         -- teto de faixas, abaixo do limite de 256 elementos da tela

local MARGEM = 4              -- afastamento da borda da tela
local MAP_Y = 4               -- distância do topo

-- Um laço vivo por jogador. A geração existe pelo mesmo motivo que numa tela que se atualiza:
-- ligar de novo antes de o laço anterior perceber que parou deixaria dois desenhando juntos.
local SESSOES = {}

-- Cache de colunas do mundo, compartilhado por todos os jogadores: o terreno é o mesmo para todos.
-- Fora de `ctx.state` de propósito — `ctx.state` vai para o disco, e isto é retrato descartável.
local COLUNAS = {}

local CORES = {
    { "water",      0x28, 0x68, 0xB8 },
    { "kelp",       0x28, 0x68, 0xB8 },
    { "lava",       0xD6, 0x5A, 0x20 },
    { "grass",      0x4E, 0x9B, 0x4E },
    { "moss",       0x4E, 0x9B, 0x4E },
    { "leaves",     0x36, 0x7D, 0x36 },
    { "flower",     0x6F, 0xB0, 0x5A },
    { "sand",       0xC9, 0xA4, 0x5A },
    { "terracotta", 0xA4, 0x6B, 0x3F },
    { "snow",       0xE8, 0xF4, 0xFF },
    { "ice",        0xB4, 0xDC, 0xF0 },
    { "log",        0x6B, 0x4E, 0x2E },
    { "planks",     0x8B, 0x6A, 0x43 },
    { "wood",       0x8B, 0x6A, 0x43 },
    { "gravel",     0x8A, 0x86, 0x82 },
    { "dirt",       0x79, 0x59, 0x3C },
    { "path",       0x79, 0x59, 0x3C },
    { "deepslate",  0x4A, 0x4A, 0x4E },
    { "stone",      0x7C, 0x7C, 0x7C },
    { "ore",        0x9A, 0x8C, 0x74 },
    { "netherrack", 0x6E, 0x2C, 0x2C },
    { "basalt",     0x3C, 0x3A, 0x40 },
}

local function cor_do_bloco(id)
    if id == nil or id == "" then return 0x1A, 0x1A, 0x22 end
    for _, entrada in ipairs(CORES) do
        if string.find(id, entrada[1], 1, true) then
            return entrada[2], entrada[3], entrada[4]
        end
    end
    return 0xA8, 0xA8, 0xA8
end

-- Lê uma coluna do mundo: qual é o bloco de superfície e a que altura ele está.
local function ler_coluna(ctx, x, z)
    local altura = ctx.server.top_y(x, z)
    local id = ctx.server.get_block(x, altura - 1, z)
    local r, g, b = cor_do_bloco(id)
    return { h = altura, r = r, g = g, b = b }
end

-- O relevo vem daqui: uma coluna mais alta que a vizinha ao norte clareia, mais baixa escurece.
-- Sem isto o mapa é um mosaico chapado, e uma encosta fica indistinguível de um planalto.
local function sombrear(coluna, vizinha)
    local fator = 1.0
    if vizinha ~= nil then
        local delta = coluna.h - vizinha.h
        if delta > 0 then
            fator = 1.18
        elseif delta < 0 then
            fator = 0.78
        end
    end

    local function canal(v)
        local resultado = math.floor(v * fator)
        if resultado > 255 then return 255 end
        if resultado < 0 then return 0 end
        return resultado
    end

    return string.format("#%02X%02X%02XFF", canal(coluna.r), canal(coluna.g), canal(coluna.b))
end

-- Passa uma coordenada local do mapa (0 = borda esquerda dele) para o canto superior direito da
-- tela. A âncora `top_right` desconta a largura do próprio elemento antes de somar `x`, então um
-- painel precisa devolver a sua largura à conta; um rótulo, que não declara largura, acaba alinhado
-- pela direita — que é o que se quer num painel encostado nessa borda.
local function a_direita(elemento, x_local, lado)
    elemento.anchor = "top_right"
    elemento.x = x_local + (elemento.w or 0) - lado - MARGEM
    return elemento
end

-- Preenche o cache das colunas visíveis, gastando no máximo o orçamento da volta.
--
-- Varre em espiral a partir do centro para o mapa nascer debaixo do jogador e crescer para fora:
-- num primeiro uso, o que interessa aparece na primeira volta em vez de na última.
local function aquecer(ctx, cx, cz, volta)
    local gastos = 0

    for anel = 0, RADIUS do
        for dz = -anel, anel do
            for dx = -anel, anel do
                if math.max(math.abs(dx), math.abs(dz)) == anel then
                    local x, z = cx + dx, cz + dz
                    local chave = x .. ":" .. z
                    local coluna = COLUNAS[chave]

                    if coluna == nil or (volta - coluna.volta) > IDADE_MAXIMA then
                        if gastos >= ORCAMENTO_COLUNAS then return end
                        local nova = ler_coluna(ctx, x, z)
                        nova.volta = volta
                        COLUNAS[chave] = nova
                        gastos = gastos + 1
                    end
                end
            end
        end
    end
end

-- Junta células vizinhas de mesma cor numa faixa só. É o que faz um mapa de 625 células caber num
-- teto de 256 elementos.
-- As linhas saem do centro para as bordas de propósito: quando o terreno é recortado demais e as
-- faixas passam do teto, o que se perde é a borda do mapa, não a metade de baixo dele.
local function linhas_do_centro()
    local ordem = { 0 }
    for passo = 1, RADIUS do
        ordem[#ordem + 1] = -passo
        ordem[#ordem + 1] = passo
    end
    return ordem
end

local function faixas(cx, cz, lado)
    local resultado = {}

    for _, dz in ipairs(linhas_do_centro()) do
        local inicio, cor_atual = nil, nil

        for dx = -RADIUS, RADIUS do
            local coluna = COLUNAS[(cx + dx) .. ":" .. (cz + dz)]
            local cor = "#00000000"
            if coluna ~= nil then
                cor = sombrear(coluna, COLUNAS[(cx + dx) .. ":" .. (cz + dz - 1)])
            end

            if cor ~= cor_atual then
                if cor_atual ~= nil and cor_atual ~= "#00000000" then
                    resultado[#resultado + 1] = a_direita({
                        type = "panel",
                        y = MAP_Y + (dz + RADIUS) * TILE,
                        w = (dx - inicio) * TILE,
                        h = TILE,
                        color = cor_atual
                    }, (inicio + RADIUS) * TILE, lado)
                end
                inicio, cor_atual = dx, cor
            end
        end

        if cor_atual ~= nil and cor_atual ~= "#00000000" then
            resultado[#resultado + 1] = a_direita({
                type = "panel",
                y = MAP_Y + (dz + RADIUS) * TILE,
                w = (RADIUS + 1 - inicio) * TILE,
                h = TILE,
                color = cor_atual
            }, (inicio + RADIUS) * TILE, lado)
        end
    end

    return resultado
end

local function desenhar(ctx, sessao, cx, cz, posicao)
    local lado = (RADIUS * 2 + 1) * TILE
    local elementos = {
        a_direita({ type = "panel", y = MAP_Y - 3,
                    w = lado + 6, h = lado + 6, color = "#101018E8" }, -3, lado)
    }

    for _, faixa in ipairs(faixas(cx, cz, lado)) do
        if #elementos >= MAX_FAIXAS then break end
        elementos[#elementos + 1] = faixa
    end

    -- O marcador vai depois do terreno para ficar por cima, e não some quando as faixas são cortadas.
    local centro_x = RADIUS * TILE
    local centro_y = MAP_Y + RADIUS * TILE
    elementos[#elementos + 1] = a_direita({
        type = "panel", y = centro_y - 1,
        w = TILE + 2, h = TILE + 2, color = "#000000FF"
    }, centro_x - 1, lado)
    elementos[#elementos + 1] = a_direita({
        type = "panel", y = centro_y,
        w = TILE, h = TILE, color = "#F5D547FF"
    }, centro_x, lado)

    -- A ponta indica para onde o jogador anda. Não há leitura de ângulo de visão na API, então a
    -- direção vem do próprio deslocamento — que é o que um minimapa precisa mostrar de qualquer forma.
    if sessao.dx ~= nil and (math.abs(sessao.dx) > 0.02 or math.abs(sessao.dz) > 0.02) then
        local passo = TILE + 2
        local ponta_x, ponta_y = centro_x, centro_y
        if math.abs(sessao.dx) > math.abs(sessao.dz) then
            ponta_x = centro_x + (sessao.dx > 0 and passo or -passo)
        else
            ponta_y = centro_y + (sessao.dz > 0 and passo or -passo)
        end
        elementos[#elementos + 1] = a_direita({
            type = "panel", y = ponta_y,
            w = TILE, h = TILE, color = "#F5D547FF"
        }, ponta_x, lado)
    end

    elementos[#elementos + 1] = a_direita({
        type = "label", y = MAP_Y - 2, text = "N", color = "#FFFFFFFF"
    }, math.floor(lado / 2) + 3, lado)
    elementos[#elementos + 1] = a_direita({
        type = "label", y = MAP_Y + lado + 5,
        text = "X " .. cx .. "  Z " .. cz .. "  Y " .. math.floor(posicao.y),
        color = "#FFFFFFFF"
    }, lado, lado)

    return elementos
end

-- Uma volta do laço: agenda a próxima, lê o que falta, desenha.
--
-- **A próxima volta é agendada antes do trabalho, e o trabalho corre dentro de `pcall`.** Um erro
-- dentro de um callback é logado e não propagado, então um reagendamento no fim da função fazia o
-- minimapa parar em silêncio na primeira volta que estourasse o orçamento de 20 ms — sem erro
-- visível, sem HUD, e sem nada que ligasse uma coisa à outra.
local function acompanhar(ctx, uuid, geracao)
    mod.after(INTERVALO, function(depois)
        local sessao = SESSOES[uuid]
        if sessao == nil or sessao.geracao ~= geracao then return end
        if depois.player == nil or depois.player.uuid ~= uuid then return end

        acompanhar(depois, uuid, geracao)

        local posicao = depois.player.position()
        local cx = math.floor(posicao.x)
        local cz = math.floor(posicao.z)

        if sessao.x ~= nil then
            sessao.dx = cx - sessao.x
            sessao.dz = cz - sessao.z
        end
        sessao.x, sessao.z = cx, cz
        sessao.volta = sessao.volta + 1

        -- Se uma volta falhar, a seguinte já está agendada e tenta de novo: o cache só cresce, e a
        -- volta seguinte tem menos a ler.
        pcall(function()
            aquecer(depois, cx, cz, sessao.volta)
            depois.player.set_hud(desenhar(depois, sessao, cx, cz, posicao))
        end)
    end)
end

local function ligar(ctx)
    if not ctx.player.supports_screens() then
        return false
    end

    local uuid = ctx.player.uuid
    local anterior = SESSOES[uuid]
    local geracao = (anterior and anterior.geracao or 0) + 1
    SESSOES[uuid] = { geracao = geracao, volta = 0 }

    acompanhar(ctx, uuid, geracao)
    return true
end

local function desligar(ctx)
    SESSOES[ctx.player.uuid] = nil
    ctx.player.set_hud({})
end

local function on_player_joined(ctx)
    if ctx.player == nil then
        return
    end

    if ligar(ctx) then
        ctx.player.send_message("Minimap ligado — ele acompanha você sozinho. /mod minimap_demo off para desligar.")
    else
        ctx.player.send_message("Minimap: este cliente não suporta o HUD do loader.")
    end
end

mod.command("minimap_demo", function(ctx)
    if ctx.player == nil then
        return
    end

    local action = ctx.argv[2] or "on"

    if action == "off" then
        desligar(ctx)
        ctx.player.send_message("Minimap desligado.")
        return
    end

    if action == "zoom" then
        local pedido = tonumber(ctx.argv[3] or "")
        if pedido == nil or pedido < 1 or pedido > 4 then
            ctx.player.send_message("Uso: /mod minimap_demo zoom <1..4>")
            return
        end
        TILE = math.floor(pedido) * 2
        ctx.player.send_message("Minimap com zoom " .. math.floor(pedido) .. ".")
        return
    end

    if action == "on" then
        if ligar(ctx) then
            ctx.player.send_message("Minimap ligado.")
        else
            ctx.player.send_message("Este cliente não suporta o HUD do loader.")
        end
        return
    end

    ctx.player.send_message("Uso: /mod minimap_demo on|off|zoom <1..4>")
end)

mod.keybind("toggle", function(ctx)
    if ctx.player == nil then
        return
    end

    if SESSOES[ctx.player.uuid] ~= nil then
        desligar(ctx)
        ctx.player.send_message("Minimap desligado (M).")
    elseif ligar(ctx) then
        ctx.player.send_message("Minimap ligado (M).")
    else
        ctx.player.send_message("Este cliente não suporta o HUD do loader.")
    end
end)

return {
    on_player_joined = on_player_joined
}

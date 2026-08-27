-- Minimap Demo
--
-- Minimapa de superfície client-side: o servidor envia só a definição lógica da câmera e os
-- marcadores. O bridge rasteriza uma imagem pequena a partir dos chunks que o cliente já recebeu.
-- A tecla M abre a configuração; as opções ficam guardadas por jogador.
--   /mod minimap_demo on|off|config|mark|clear
--   /mod minimap_demo zoom <1..4>
--
-- A câmera é criada pela API Lua para demonstrar o caso em que a lógica decide se ela existe. O
-- manifesto poderia declarar a mesma câmera estaticamente quando a definição for sempre necessária.

local CAMERA = {
    projection = "orthographic",
    source = "world",
    anchor = "player",
    orientation = "north",
    resolution = 96,
    radius = 48,
    update_ticks = 5,
    output = "texture"
}

-- Registro lógico curto; o loader publica minimap_demo:minimap e o bridge escolhe a textura física.
-- A condição permite que uma configuração persistente desative o recurso sem expor APIs do cliente.
if mod.state.camera_enabled ~= false then
    mod.camera("minimap", CAMERA)
end

local TILE_PADRAO = 3
local ZOOM_MIN = 1
local ZOOM_MAX = 4
local RAIO_MIN = 12
local RAIO_MAX = 20
local INTERVALO = 4
local MARGEM = 4
local MAP_Y = 4

-- Um laço vivo por jogador. A geração impede que ligar de novo deixe dois HUDs a atualizar juntos.
local SESSOES = {}

local function limitar_zoom(valor)
    local zoom = tonumber(valor) or ZOOM_MIN
    zoom = math.floor(zoom)
    if zoom < ZOOM_MIN then return ZOOM_MIN end
    if zoom > ZOOM_MAX then return ZOOM_MAX end
    return zoom
end

local function ler_config(ctx)
    local zoom = limitar_zoom(ctx.player.data.get("minimap.zoom", ZOOM_MIN))
    local mostrar_coordenadas = ctx.player.data.get("minimap.coordinates", true)
    if type(mostrar_coordenadas) ~= "boolean" then
        mostrar_coordenadas = true
    end
    return {
        zoom = zoom,
        mostrar_coordenadas = mostrar_coordenadas
    }
end

local function guardar_config(ctx, config)
    ctx.player.data.set("minimap.zoom", config.zoom)
    ctx.player.data.set("minimap.coordinates", config.mostrar_coordenadas)
end

local function numero_guardado(ctx, chave)
    local valor = ctx.player.data.get(chave, nil)
    return type(valor) == "number" and valor or nil
end

local function waypoint_guardado(ctx, dimensao, cx, cz, raio)
    local x = numero_guardado(ctx, "minimap.home_x")
    local z = numero_guardado(ctx, "minimap.home_z")
    local casa_dimensao = ctx.player.data.get("minimap.home_dimension", "")
    if x == nil or z == nil or casa_dimensao ~= dimensao then return nil end

    local lado = math.max(1, raio * 2)
    local marcador_x = (x - (cx - raio)) / lado
    local marcador_z = (z - (cz - raio)) / lado
    if marcador_x < 0 or marcador_x > 1 or marcador_z < 0 or marcador_z > 1 then
        return nil
    end
    return {
        type = "waypoint", label = "Casa", x = marcador_x, z = marcador_z,
        color = "#55FF55FF"
    }
end

local function e_hostil(id)
    return string.find(id, "zombie", 1, true) ~= nil
        or string.find(id, "skeleton", 1, true) ~= nil
        or string.find(id, "creeper", 1, true) ~= nil
        or string.find(id, "spider", 1, true) ~= nil
        or string.find(id, "witch", 1, true) ~= nil
        or string.find(id, "slime", 1, true) ~= nil
        or string.find(id, "phantom", 1, true) ~= nil
        or string.find(id, "pillager", 1, true) ~= nil
        or string.find(id, "vindicator", 1, true) ~= nil
        or string.find(id, "evoker", 1, true) ~= nil
        or string.find(id, "blaze", 1, true) ~= nil
        or string.find(id, "ghast", 1, true) ~= nil
        or string.find(id, "magma", 1, true) ~= nil
        or string.find(id, "enderman", 1, true) ~= nil
end

-- Radar continua server-side porque entidades próximas são dados dinâmicos e não fazem parte da
-- captura de terreno. São só marcadores: a textura não tenta renderizar uma segunda cena 3D.
local function radar(ctx, cx, cz, posicao, raio)
    local resultado = {}
    local alcance = math.min(64, raio)
    local entidades = ctx.server.entities_near(cx, math.floor(posicao.y), cz, alcance)
    for _, entidade in ipairs(entidades) do
        if entidade.uuid ~= ctx.player.uuid and entidade.type ~= "minecraft:player"
                and #resultado < 24 then
            local x = (entidade.x - (cx - raio)) / math.max(1, raio * 2)
            local z = (entidade.z - (cz - raio)) / math.max(1, raio * 2)
            if x >= 0 and x <= 1 and z >= 0 and z <= 1 then
                resultado[#resultado + 1] = {
                    type = "entity", x = x, z = z,
                    color = e_hostil(entidade.type) and "#FF5555FF" or "#B8D7FFFF"
                }
            end
        end
    end
    return resultado
end

local function a_direita(elemento, x_local, lado)
    elemento.anchor = "top_right"
    elemento.x = x_local + (elemento.w or 0) - lado - MARGEM
    return elemento
end

local function desenhar(ctx, sessao, dimensao, cx, cz, posicao)
    local tile = sessao.tile or TILE_PADRAO
    local raio = sessao.raio or RAIO_MIN
    local lado = (raio * 2 + 1) * tile
    local marcadores = {
        { type = "player", x = 0.5, z = 0.5, color = "#F5D547FF" }
    }

    local casa = waypoint_guardado(ctx, dimensao, cx, cz, raio)
    if casa ~= nil then marcadores[#marcadores + 1] = casa end
    for _, entidade in ipairs(radar(ctx, cx, cz, posicao, raio)) do
        marcadores[#marcadores + 1] = entidade
    end

    local elementos = {
        {
            type = "map", anchor = "top_right", x = -MARGEM, y = MAP_Y,
            w = lado, h = lado,
            render = "client_camera", camera = "minimap_demo:minimap",
            -- Estes campos são overrides por jogador; a câmera fornece defaults quando omitidos.
            resolution = 96 - (sessao.zoom - 1) * 8,
            radius = raio * 2,
            update_ticks = 5,
            round = true, grid = false,
            direction_x = sessao.dx or 0, direction_z = sessao.dz or 0,
            markers = marcadores
        }
    }

    local mostrar_coordenadas = sessao.mostrar_coordenadas ~= false
    elementos[#elementos + 1] = a_direita({
        type = "label", y = MAP_Y + lado + 5,
        text = dimensao .. "  X " .. cx .. "  Z " .. cz .. "  Y " .. math.floor(posicao.y),
        color = "#FFFFFFFF"
    }, lado, lado)
    if not mostrar_coordenadas then
        elementos[#elementos] = a_direita({
            type = "label", y = MAP_Y + lado + 5, text = dimensao, color = "#FFFFFFFF"
        }, lado, lado)
    end
    return elementos
end

local function acompanhar(ctx, uuid, geracao)
    mod.after(INTERVALO, function(depois)
        local sessao = SESSOES[uuid]
        if sessao == nil or sessao.geracao ~= geracao then return end
        if depois.player == nil or depois.player.uuid ~= uuid then return end

        acompanhar(depois, uuid, geracao)

        local dimensao = depois.player.dimension()
        if sessao.dimensao ~= dimensao then
            sessao.dimensao = dimensao
            sessao.x, sessao.z = nil, nil
            sessao.dx, sessao.dz = 0, 0
        end

        local posicao = depois.player.position()
        local cx = math.floor(posicao.x)
        local cz = math.floor(posicao.z)
        if sessao.x ~= nil then
            sessao.dx = cx - sessao.x
            sessao.dz = cz - sessao.z
        end
        sessao.x, sessao.z = cx, cz

        -- O servidor actualiza só a descrição leve do HUD. A textura de terreno é reconstruída no
        -- cliente segundo update_ticks, sem enviar células nem ler blocos pelo Lua.
        pcall(function()
            depois.player.set_hud(desenhar(depois, sessao, dimensao, cx, cz, posicao))
        end)
    end)
end

local function aplicar_configuracao(ctx, config)
    local sessao = SESSOES[ctx.player.uuid]
    if sessao == nil then return end
    sessao.zoom = config.zoom
    sessao.tile = TILE_PADRAO + config.zoom - 1
    sessao.raio = math.max(RAIO_MIN, RAIO_MAX - (config.zoom - 1) * 3)
    sessao.mostrar_coordenadas = config.mostrar_coordenadas
end

local function ligar(ctx)
    if not ctx.player.supports_screens() then
        return false
    end

    local uuid = ctx.player.uuid
    local anterior = SESSOES[uuid]
    local geracao = (anterior and anterior.geracao or 0) + 1
    local config = ler_config(ctx)
    SESSOES[uuid] = {
        geracao = geracao,
        dimensao = ctx.player.dimension(),
        tile = TILE_PADRAO + config.zoom - 1,
        zoom = config.zoom,
        raio = math.max(RAIO_MIN, RAIO_MAX - (config.zoom - 1) * 3),
        dx = 0,
        dz = 0,
        mostrar_coordenadas = config.mostrar_coordenadas
    }

    acompanhar(ctx, uuid, geracao)
    return true
end

local function desligar(ctx)
    SESSOES[ctx.player.uuid] = nil
    ctx.player.set_hud({})
end

local function desenhar_config(ctx)
    local config = ler_config(ctx)
    local ativo = SESSOES[ctx.player.uuid] ~= nil
    local coordenadas = config.mostrar_coordenadas and "SIM" or "NAO"
    local estado = ativo and "LIGADO" or "DESLIGADO"

    return {
        title = "Configurar Minimap",
        width = 220,
        height = 150,
        blur = true,
        dim = true,
        elements = {
            { type = "panel", x = 0, y = 0, w = 220, h = 150, color = "#101018F0" },
            { type = "label", x = 12, y = 10, text = "MINIMAP", color = "#F5D547FF" },
            { type = "label", x = 12, y = 30,
              text = "Zoom: " .. config.zoom .. "/" .. ZOOM_MAX, color = "#FFFFFFFF" },
            { type = "button", id = "zoom_minus", x = 12, y = 48, w = 92, h = 20,
              text = "Zoom -" },
            { type = "button", id = "zoom_plus", x = 116, y = 48, w = 92, h = 20,
              text = "Zoom +" },
            { type = "button", id = "coordinates", x = 12, y = 75, w = 196, h = 20,
              text = "Coordenadas: " .. coordenadas },
            { type = "button", id = "map_toggle", x = 12, y = 101, w = 196, h = 20,
              text = "Minimap: " .. estado },
            { type = "button", id = "close", x = 12, y = 127, w = 196, h = 20,
              text = "Fechar" }
        }
    }
end

mod.screen("config", function(ctx)
    if ctx.player == nil then return end
    if ctx.ui.action == "close" then return end
    if ctx.ui.element == "close" then
        ctx.player.close_screen()
        return
    end

    local config = ler_config(ctx)
    if ctx.ui.element == "zoom_minus" then
        config.zoom = limitar_zoom(config.zoom - 1)
    elseif ctx.ui.element == "zoom_plus" then
        config.zoom = limitar_zoom(config.zoom + 1)
    elseif ctx.ui.element == "coordinates" then
        config.mostrar_coordenadas = not config.mostrar_coordenadas
    elseif ctx.ui.element == "map_toggle" then
        if SESSOES[ctx.player.uuid] == nil then ligar(ctx) else desligar(ctx) end
    end

    guardar_config(ctx, config)
    aplicar_configuracao(ctx, config)
    ctx.player.update_screen(desenhar_config(ctx))
end)

local function abrir_config(ctx)
    if not ctx.player.supports_screens() then
        ctx.player.send_message("Este cliente não suporta telas do loader.")
        return false
    end
    return ctx.player.open_screen("config", desenhar_config(ctx))
end

local function on_player_joined(ctx)
    if ctx.player == nil then return end
    if ligar(ctx) then
        ctx.player.send_message("Minimap ligado — captura aérea client-side. /mod minimap_demo off para desligar.")
    else
        ctx.player.send_message("Minimap: este cliente não suporta o HUD do loader.")
    end
end

mod.command("minimap_demo", function(ctx)
    if ctx.player == nil then return end
    local action = ctx.argv[1] or ""

    if action == "config" then abrir_config(ctx); return end
    if action == "off" then
        desligar(ctx)
        ctx.player.send_message("Minimap desligado.")
        return
    end
    if action == "mark" then
        local posicao = ctx.player.position()
        ctx.player.data.set("minimap.home_x", posicao.x)
        ctx.player.data.set("minimap.home_z", posicao.z)
        ctx.player.data.set("minimap.home_dimension", ctx.player.dimension())
        ctx.player.send_message("Waypoint Casa guardado em " .. math.floor(posicao.x)
            .. ", " .. math.floor(posicao.z) .. ".")
        return
    end
    if action == "clear" then
        ctx.player.data.remove("minimap.home_x")
        ctx.player.data.remove("minimap.home_z")
        ctx.player.data.remove("minimap.home_dimension")
        ctx.player.send_message("Waypoint Casa removido.")
        return
    end
    if action == "zoom" then
        local pedido = tonumber(ctx.command.arguments.level or "")
        if pedido == nil then
            ctx.player.send_message("Uso: /mod minimap_demo zoom <1..4>")
            return
        end
        local config = ler_config(ctx)
        config.zoom = limitar_zoom(pedido)
        guardar_config(ctx, config)
        aplicar_configuracao(ctx, config)
        ctx.player.send_message("Minimap com zoom " .. config.zoom .. ".")
        return
    end
    if action == "on" then
        if ligar(ctx) then ctx.player.send_message("Minimap ligado.")
        else ctx.player.send_message("Este cliente não suporta o HUD do loader.") end
        return
    end
end)

mod.keybind("toggle", function(ctx)
    if ctx.player == nil then return end
    abrir_config(ctx)
end)

return {
    on_player_joined = on_player_joined
}

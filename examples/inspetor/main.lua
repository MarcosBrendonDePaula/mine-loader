-- Le e abastece o inventario de blocos ao redor.
--
-- Serve de prova da camada de capacidades: o script nao sabe se o bloco e um bau do jogo ou uma
-- maquina de outro mod. Ele pergunta o que aquela posicao oferece, e opera pelo mesmo vocabulario
-- nos dois casos -- que e o ponto de o vocabulario ser do loader, e nao de nenhuma plataforma.
--
--   /mod inspetor            procura inventarios em volta e lista o conteudo
--   /mod inspetor por <item> [n] [x y z]    poe itens no inventario
--   /mod inspetor tirar <item> [n] [x y z]  tira itens do inventario
--
-- Sem coordenadas, opera no primeiro inventario encontrado. Com elas, opera naquela posicao -- o
-- que importa quando ha mais de um por perto e a resposta depende de qual deles respondeu.

local ALCANCE = 6

-- Varre o cubo ao redor do jogador procurando quem responda por itens.
local function inventarios_perto(ctx)
    -- position e funcao; name e uuid sao valores. A distincao nao e obvia e ja custou tempo.
    local pos = ctx.player.position()
    local achados = {}

    for dx = -ALCANCE, ALCANCE do
        for dy = -3, 3 do
            for dz = -ALCANCE, ALCANCE do
                local x, y, z = pos.x + dx, pos.y + dy, pos.z + dz
                local capacidades = ctx.server.capabilities_at(x, y, z)

                for _, capacidade in ipairs(capacidades) do
                    if capacidade == "items" then
                        achados[#achados + 1] = { x = x, y = y, z = z,
                                                  bloco = ctx.server.get_block(x, y, z) }
                    end
                end
            end
        end
    end
    return achados
end

local function descrever(ctx, alvo)
    local conteudo = ctx.server.container_at(alvo.x, alvo.y, alvo.z)
    if #conteudo == 0 then
        return alvo.bloco .. " em " .. alvo.x .. "," .. alvo.y .. "," .. alvo.z .. ": vazio"
    end

    local partes = {}
    for _, entrada in ipairs(conteudo) do
        partes[#partes + 1] = entrada.item .. " x" .. entrada.count
    end
    return alvo.bloco .. " em " .. alvo.x .. "," .. alvo.y .. "," .. alvo.z
           .. ": " .. table.concat(partes, ", ")
end

mod.command("inspetor", function(ctx)
    local achados = inventarios_perto(ctx)

    if #achados == 0 then
        ctx.player.send_message("Nenhum inventario num raio de " .. ALCANCE .. " blocos.")
        return
    end

    local sub = ctx.subcommand

    if sub == "" or sub == nil then
        ctx.player.send_message(#achados .. " inventario(s) por perto:")
        for indice, alvo in ipairs(achados) do
            if indice > 6 then
                ctx.player.send_message("... e mais " .. (#achados - 6) .. ".")
                break
            end
            ctx.player.send_message("  " .. descrever(ctx, alvo))
        end
        return
    end

    local item = ctx.argv[2]
    if not item then
        ctx.player.send_message("Uso: /mod inspetor " .. sub .. " <item> [quantidade]")
        return
    end

    local quantidade = tonumber(ctx.argv[3] or "1") or 1

    -- Coordenadas explicitas, quando dadas: sem elas nao da para saber qual dos inventarios
    -- respondeu, e a resposta e justamente o que esta em teste.
    local alvo = achados[1]
    if ctx.argv[4] and ctx.argv[5] and ctx.argv[6] then
        alvo = { x = tonumber(ctx.argv[4]), y = tonumber(ctx.argv[5]), z = tonumber(ctx.argv[6]) }
        alvo.bloco = ctx.server.get_block(alvo.x, alvo.y, alvo.z)
    end

    if sub == "por" then
        local sobrou = ctx.server.insert_into(alvo.x, alvo.y, alvo.z, item, quantidade)
        ctx.player.send_message("Coloquei " .. (quantidade - sobrou) .. " de " .. quantidade
                                .. " em " .. alvo.bloco .. ".")
    elseif sub == "tirar" then
        local pegou = ctx.server.extract_from(alvo.x, alvo.y, alvo.z, item, quantidade)
        ctx.player.send_message("Tirei " .. pegou .. " de " .. alvo.bloco .. ".")
    else
        ctx.player.send_message("Subcomando desconhecido: " .. sub)
    end
end)

return {}

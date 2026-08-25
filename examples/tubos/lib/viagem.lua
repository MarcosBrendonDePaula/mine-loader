-- A viagem de um item por uma linha de tubos: um passo por tique, guardada na posicao.
--
-- Este modulo e o coracao do exemplo. Ele existe para mostrar tres capacidades funcionando juntas,
-- porque separadas elas parecem menores do que sao:
--
--   forma por estado     o tubo cresce braco em direcao ao vizinho
--   dados por posicao    a carga em viagem mora no tubo em que esta
--   tique agendado       o tubo pede para ser chamado de volta ali daqui a N tiques
--
-- Sem a terceira, o item some de um bau e aparece no outro. Com ela, ele existe em algum lugar do
-- caminho enquanto viaja -- e essa e a diferenca entre uma rede que funciona e uma que parece
-- funcionar.

-- Tiques entre um tubo e o proximo. Quatro faz a viagem ser visivel sem ser lenta.
local TIQUES_POR_TUBO = 4

-- Quantas cargas um tubo segura ao mesmo tempo.
--
-- O teto existe porque cada carga custa uma leitura e uma escrita de dados por tique. Sem ele,
-- mandar mil de uma vez transformaria um tubo num laco que estoura o orcamento de 20 ms -- e o
-- sintoma seria a linha inteira parando, nao o tubo que encheu.
local MAX_CARGAS = 16

-- O teto da busca, pela mesma razao: ela roda dentro de um callback com orcamento.
local MAX_NOS = 256

local LADOS = {
    { x =  1, y =  0, z =  0 },
    { x = -1, y =  0, z =  0 },
    { x =  0, y =  1, z =  0 },
    { x =  0, y = -1, z =  0 },
    { x =  0, y =  0, z =  1 },
    { x =  0, y =  0, z = -1 },
}

local function chave(x, y, z)
    return x .. "," .. y .. "," .. z
end

local function e_tubo(ctx, x, y, z)
    return ctx.server.get_block(x, y, z) == "tubos:tubo"
end

--- O caminho de tubos entre duas posicoes, ou nil se nao houver.
--
-- Busca em largura guardando de onde se chegou a cada no, entao o caminho e o mais curto em numero
-- de tubos. Recursao aqui seria pior: uma linha longa estouraria a pilha do Lua sob orcamento.
local function rota(ctx, origem, destino)
    if origem.x == destino.x and origem.y == destino.y and origem.z == destino.z then
        return { { x = origem.x, y = origem.y, z = origem.z } }
    end

    local de_onde = {}
    local vistos = { [chave(origem.x, origem.y, origem.z)] = true }
    local fila = { { x = origem.x, y = origem.y, z = origem.z } }
    local frente = 1
    local visitados = 1

    while frente <= #fila do
        local atual = fila[frente]
        frente = frente + 1

        for _, lado in ipairs(LADOS) do
            local nx, ny, nz = atual.x + lado.x, atual.y + lado.y, atual.z + lado.z
            local k = chave(nx, ny, nz)

            if not vistos[k] and e_tubo(ctx, nx, ny, nz) then
                vistos[k] = true
                de_onde[k] = atual
                visitados = visitados + 1

                if nx == destino.x and ny == destino.y and nz == destino.z then
                    -- Refaz o caminho de tras para frente e inverte. Guardar a rota inteira em cada
                    -- no durante a busca custaria memoria proporcional ao quadrado da linha.
                    local invertido = { { x = nx, y = ny, z = nz } }
                    local passo = atual
                    while passo do
                        invertido[#invertido + 1] = { x = passo.x, y = passo.y, z = passo.z }
                        passo = de_onde[chave(passo.x, passo.y, passo.z)]
                    end

                    local caminho = {}
                    for i = #invertido, 1, -1 do caminho[#caminho + 1] = invertido[i] end
                    return caminho
                end

                fila[#fila + 1] = { x = nx, y = ny, z = nz }
                if visitados >= MAX_NOS then return nil end
            end
        end
    end
    return nil
end

--- Os inventarios encostados num tubo, que nao sejam tubo.
local function inventarios_em(ctx, no)
    local achados = {}

    for _, lado in ipairs(LADOS) do
        local x, y, z = no.x + lado.x, no.y + lado.y, no.z + lado.z

        if not e_tubo(ctx, x, y, z) then
            for _, capacidade in ipairs(ctx.server.capabilities_at(x, y, z)) do
                if capacidade == "items" then
                    achados[#achados + 1] = { x = x, y = y, z = z }
                    break
                end
            end
        end
    end
    return achados
end

local function cargas_em(ctx, x, y, z)
    local dados = ctx.server.get_block_data(x, y, z)
    return dados.cargas or {}, dados
end

--- Poe uma carga num tubo e agenda o tique que vai move-la.
--
-- Devolve false quando o tubo ja esta cheio. Quem chama precisa tratar isso -- devolver o item para
-- onde veio --, senao uma carga que nao coube e um item que deixou de existir.
local function por_carga(ctx, x, y, z, carga)
    local cargas, dados = cargas_em(ctx, x, y, z)
    if #cargas >= MAX_CARGAS then return false end

    cargas[#cargas + 1] = carga
    dados.cargas = cargas
    ctx.server.set_block_data(x, y, z, dados)
    ctx.server.schedule_block(x, y, z, TIQUES_POR_TUBO)
    return true
end

--- Tenta entregar a carga num inventario encostado neste tubo.
local function descarregar(ctx, x, y, z, carga)
    local resta = carga.count

    for _, alvo in ipairs(inventarios_em(ctx, { x = x, y = y, z = z })) do
        if resta <= 0 then break end
        resta = ctx.server.insert_into(alvo.x, alvo.y, alvo.z, carga.item, resta)
    end
    return resta
end

--- O tique de um tubo: cada carga anda um passo.
--
-- O tubo nao se reagenda quando esvazia. Uma linha parada nao pode continuar custando tique para
-- sempre -- cada tubo ja usado viraria trabalho permanente do servidor.
local function passo(ctx, x, y, z)
    local cargas, dados = cargas_em(ctx, x, y, z)
    if #cargas == 0 then return end

    local ficam = {}

    for _, carga in ipairs(cargas) do
        local proximo = carga.rota[carga.passo + 1]

        if proximo == nil or not e_tubo(ctx, proximo.x, proximo.y, proximo.z) then
            -- Chegou ao fim, ou alguem quebrou o tubo da frente durante a viagem. Nos dois casos a
            -- carga sai pelo inventario mais proximo: item que desaparece e o pior defeito
            -- possivel, e largar item solto no chao ainda nao existe na API do loader.
            local sobrou = descarregar(ctx, x, y, z, carga)
            if sobrou > 0 then
                -- Nao coube: a carga espera aqui e tenta de novo. O tubo vira uma fila, que e o que
                -- quem joga espera ver quando o bau do fim enche.
                carga.count = sobrou
                ficam[#ficam + 1] = carga
            end
        else
            carga.passo = carga.passo + 1
            if not por_carga(ctx, proximo.x, proximo.y, proximo.z, carga) then
                -- O tubo da frente encheu: espera aqui. Voltar o passo importa, senao a carga
                -- pularia um tubo ao seguir viagem.
                carga.passo = carga.passo - 1
                ficam[#ficam + 1] = carga
            end
        end
    end

    dados.cargas = ficam
    ctx.server.set_block_data(x, y, z, dados)

    if #ficam > 0 then
        ctx.server.schedule_block(x, y, z, TIQUES_POR_TUBO)
    end
end

--- Tira do bau encostado na origem e manda pela linha ate o destino.
--
-- Devolve quanto de fato entrou na linha, e o motivo quando nao entrou tudo. O numero real, e nao o
-- pedido: mentir sobre isso faria quem pediu procurar itens que nunca sairam.
local function enviar(ctx, origem, destino, item, quantidade)
    if not e_tubo(ctx, origem.x, origem.y, origem.z) then
        return 0, "a origem nao e um tubo"
    end
    if not e_tubo(ctx, destino.x, destino.y, destino.z) then
        return 0, "o destino nao e um tubo"
    end

    local caminho = rota(ctx, origem, destino)
    if caminho == nil then return 0, "sem caminho de tubos entre os dois" end

    local enviado = 0
    local motivo = nil

    for _, fonte in ipairs(inventarios_em(ctx, origem)) do
        if enviado >= quantidade then break end

        local tirado = ctx.server.extract_from(fonte.x, fonte.y, fonte.z, item, quantidade - enviado)
        if tirado > 0 then
            local carga = { item = item, count = tirado, rota = caminho, passo = 1 }
            if por_carga(ctx, origem.x, origem.y, origem.z, carga) then
                enviado = enviado + tirado
            else
                -- Nao coube na linha: devolve para o bau de onde saiu.
                ctx.server.insert_into(fonte.x, fonte.y, fonte.z, item, tirado)
                motivo = "a linha esta cheia"
            end
        end
    end

    if enviado == 0 then return 0, motivo or "nao ha esse item no bau da origem" end
    return enviado, motivo
end

return {
    TIQUES_POR_TUBO = TIQUES_POR_TUBO,
    MAX_CARGAS = MAX_CARGAS,
    rota = rota,
    passo = passo,
    enviar = enviar,
    cargas_em = cargas_em,
    inventarios_em = inventarios_em,
}

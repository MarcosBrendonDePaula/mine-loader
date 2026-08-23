-- Modulo de componentes reutilizaveis.
-- Um modulo devolve o que quiser: aqui, uma tabela de funcoes.
-- Ele compartilha os mesmos globais do mod, entao enxerga mod.state e a API do loader.

local M = {}

M.CORES = {
    fundo  = "#101018E0",
    titulo = "#FFD700",
    texto  = "#FFFFFF",
    barra  = "#4CAF50"
}

--- Um titulo em escala maior, na posicao indicada.
function M.titulo(x, y, texto)
    return { type = "label", x = x, y = y, text = texto, color = M.CORES.titulo, scale = 1.5 }
end

--- Uma barra de progresso com rotulo em cima.
function M.barra(x, y, largura, valor, rotulo)
    return {
        { type = "label", x = x, y = y, text = rotulo, color = M.CORES.texto },
        { type = "progress", x = x, y = y + 12, w = largura, h = 8,
          progress = math.min(1.0, valor), color = M.CORES.barra }
    }
end

--- Junta varias listas de elementos em uma so.
function M.juntar(...)
    local resultado = {}
    for _, grupo in ipairs({...}) do
        if grupo.type ~= nil then
            resultado[#resultado + 1] = grupo
        else
            for _, elemento in ipairs(grupo) do
                resultado[#resultado + 1] = elemento
            end
        end
    end
    return resultado
end

return M

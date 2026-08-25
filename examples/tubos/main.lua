-- Um tubo que conecta com o vizinho e leva o item passo a passo.
--
-- E o menor exemplo que exercita tres capacidades ao mesmo tempo -- forma que varia com o estado,
-- dados por posicao e tique agendado --, e existe porque as tres so mostram o que valem juntas.
--
-- Ele tambem e o mod que os testes usam. Um teste precisa de um mod de verdade para percorrer o
-- fluxo inteiro, e apontar para um exemplo grande deixaria o teste refem de mudancas que nao tem
-- nada a ver com ele.
--
--   /mod tubos enviar <x1> <y1> <z1> <x2> <y2> <z2> <item> [quantidade]
--
-- Tira do bau encostado no tubo da origem e manda ate o tubo do destino, onde ele sai no bau que
-- estiver encostado la.

local viagem = mod.import("lib/viagem.lua")

mod.command("tubos", function(ctx)
    local args = ctx.argv or {}

    if ctx.subcommand ~= "enviar" then
        ctx.log.info("uso: /mod tubos enviar <x1> <y1> <z1> <x2> <y2> <z2> <item> [quantidade]")
        return
    end

    local numeros = {}
    for i = 2, 7 do
        numeros[#numeros + 1] = tonumber(args[i])
    end
    local item = args[8]

    if #numeros < 6 or item == nil then
        ctx.log.warn("TUBOS faltam argumentos")
        return
    end

    local origem = { x = numeros[1], y = numeros[2], z = numeros[3] }
    local destino = { x = numeros[4], y = numeros[5], z = numeros[6] }
    local quantidade = tonumber(args[9]) or 64

    local enviado, motivo = viagem.enviar(ctx, origem, destino, item, quantidade)
    ctx.log.info("TUBOS enviado=" .. enviado .. " item=" .. item
                 .. " motivo=" .. tostring(motivo))
end)

local function on_loader_ready(ctx)
    ctx.log.info("Tubos pronto: encoste um bau nas duas pontas de uma linha de tubos.")
end

return {
    on_loader_ready = on_loader_ready,
}

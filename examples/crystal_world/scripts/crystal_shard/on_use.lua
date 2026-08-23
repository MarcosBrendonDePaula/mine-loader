-- Clique com o fragmento no ar: conta os usos no estado compartilhado do mod.
return function(ctx)
    ctx.state.usos = (ctx.state.usos or 0) + 1

    if ctx.player ~= nil then
        ctx.player.send_message("O fragmento pulsa. Usos nesta sessao: " .. ctx.state.usos .. ".")
    end
end

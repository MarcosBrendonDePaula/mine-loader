-- O tique agendado de um tubo: mover as cargas que estao nele.

local viagem = mod.import("lib/viagem.lua")

return function(ctx)
    viagem.passo(ctx, ctx.block.x, ctx.block.y, ctx.block.z)
end

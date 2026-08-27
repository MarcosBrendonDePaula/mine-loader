-- Exemplo mínimo: uma política real consultaria mod.state para os claims e permissões.
mod.on("action_attempt", function(ctx)
    if ctx.action == "block.break" and ctx.target.id == "minecraft:obsidian" then
        if ctx.actor and ctx.actor.send_message then
            ctx.actor.send_message("Esta área está protegida neste exemplo.")
        end
        return false
    end
end)

local function on_loader_ready(ctx)
    ctx.log.info("Crystal World carregado ao lado dos outros mods.")
end

local function on_block_used(ctx)
    if ctx.block == nil then
        return
    end
    ctx.log.info("Cristal usado em " .. ctx.block.id .. " (" .. ctx.block.x .. "," .. ctx.block.y .. "," .. ctx.block.z .. ").")
    if ctx.player ~= nil then
        ctx.player.send_message("O cristal brilha para " .. ctx.player.name .. ".")
    end
end

return {
    on_loader_ready = on_loader_ready,
    on_block_used = on_block_used
}

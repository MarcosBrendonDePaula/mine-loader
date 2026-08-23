-- Guilda dos Mineradores
--
-- Ciclo de vida do mod e o comando /mod guilda. A logica de cada bloco e item vive nos arquivos
-- que o manifesto aponta em `behavior`, entao este arquivo cuida apenas do que e do mod inteiro.

-- Quantos minerios cada missao exige.
local META = 8

-- O bloco que conta para a missao.
local ALVO = "minecraft:iron_ore"

--- Devolve a ficha do jogador dentro do estado persistido do mod.
-- O estado sobrevive ao desligamento do servidor, entao o progresso nao se perde.
local function ficha(ctx, nome)
    ctx.state.jogadores = ctx.state.jogadores or {}
    ctx.state.jogadores[nome] = ctx.state.jogadores[nome] or { extraidos = 0, concluidas = 0 }
    return ctx.state.jogadores[nome]
end

local function on_loader_ready(ctx)
    ctx.state.temporada = (ctx.state.temporada or 0) + 1
    ctx.log.info("Guilda aberta. Temporada numero " .. ctx.state.temporada .. ".")
end

local function on_player_joined(ctx)
    if ctx.player == nil then
        return
    end

    local minha = ficha(ctx, ctx.player.name)
    ctx.player.send_message("Bem-vindo a Guilda. Missao: extraia " .. META .. " minerios de ferro.")
    ctx.player.send_action_bar("Progresso: " .. minha.extraidos .. "/" .. META)
end

--- Conta minerios quebrados e conclui a missao quando a meta e atingida.
-- Este evento e global do mod porque o bloco quebrado e do jogo, nao do mod: um bloco vanilla
-- nao tem como apontar um script no nosso manifesto.
local function on_block_broken(ctx)
    if ctx.block == nil or ctx.block.id ~= ALVO then
        return
    end

    -- Sem jogador no evento nao ha a quem creditar; quebras por explosao caem neste caso.
    if ctx.player == nil then
        return
    end

    local minha = ficha(ctx, ctx.player.name)
    if minha.extraidos >= META then
        return
    end

    minha.extraidos = minha.extraidos + 1
    ctx.player.send_action_bar("Progresso: " .. minha.extraidos .. "/" .. META)

    if minha.extraidos < META then
        return
    end

    minha.concluidas = minha.concluidas + 1
    ctx.player.send_message("Missao concluida! Procure um Quadro de Missoes para receber.")
    ctx.server.play_sound("minecraft:entity.player.levelup", ctx.block.x, ctx.block.y, ctx.block.z, 0.8, 1.0)
end

-- Comando do mod, publicado como /mod guilda [status|posto]
mod.command("guilda", function(ctx)
    if ctx.player == nil then
        ctx.log.info("O comando da guilda precisa de um jogador.")
        return
    end

    local minha = ficha(ctx, ctx.player.name)

    if ctx.args == "posto" then
        -- Constroi o posto ao lado do jogador, a partir do desenho declarado no manifesto.
        local onde = ctx.player.position()
        local blocos = ctx.server.place_structure("posto", onde.x + 2, onde.y, onde.z)
        ctx.player.send_message("Posto erguido com " .. blocos .. " blocos.")
        return
    end

    -- Sem argumento, mostra o status. `#ctx.server.players()` exige server.read.
    ctx.player.send_message("Temporada " .. ctx.state.temporada
        .. " | online: " .. #ctx.server.players()
        .. " | mundo: " .. ctx.server.world_name())
    ctx.player.send_message("Voce: " .. minha.extraidos .. "/" .. META
        .. " nesta missao, " .. minha.concluidas .. " concluida(s).")
end)

return {
    on_loader_ready = on_loader_ready,
    on_player_joined = on_player_joined,
    on_block_broken = on_block_broken
}

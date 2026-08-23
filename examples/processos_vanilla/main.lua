-- Interacoes do jogo que nenhuma consulta revela.
--
-- Morte de mob e mineracao sao tabelas de loot, e por isso consultaveis: o catalogo descobre
-- sozinho que a ovelha derruba la ao morrer. Ja usar um item em um mob -- tosquiar, ordenhar,
-- pegar um peixe com balde -- vive dentro do codigo da entidade. O jogo sabe fazer e nao sabe
-- dizer, e sem declaracao essas interacoes ficam invisiveis a qualquer catalogo.
--
-- Este mod nao tem logica: e uma lista. Qualquer catalogo passa a mostrar estas interacoes so por
-- ele estar instalado, porque o registro de processos e global.
--
-- Nao vive dentro do loader de proposito. O nucleo nao conhece conteudo do jogo -- e a regra que
-- mantem o loader portavel -- e como dado em Lua qualquer um corrige uma linha errada sem esperar
-- uma versao nova do loader.

local LAS = {
    white = "Branca", orange = "Laranja", magenta = "Magenta",
    light_blue = "Azul-clara", yellow = "Amarela", lime = "Verde-limao",
    pink = "Rosa", gray = "Cinza", light_gray = "Cinza-clara",
    cyan = "Ciano", purple = "Roxa", blue = "Azul",
    brown = "Marrom", green = "Verde", red = "Vermelha", black = "Preta"
}

for cor, nome in pairs(LAS) do
    mod.process("tosquia_" .. cor, {
        title = "Tosquiar (" .. nome .. ")",
        inputs = { "minecraft:shears" },
        output = { item = "minecraft:" .. cor .. "_wool", count = 1 },
        by = "minecraft:sheep"
    })
end

mod.process("ordenha", {
    title = "Encher o balde",
    inputs = { "minecraft:bucket" },
    output = { item = "minecraft:milk_bucket", count = 1 },
    by = "minecraft:cow"
})

mod.process("ordenha_mooshroom", {
    title = "Encher a tigela",
    inputs = { "minecraft:bowl" },
    output = { item = "minecraft:mushroom_stew", count = 1 },
    by = "minecraft:mooshroom"
})

mod.process("tosquia_mooshroom", {
    title = "Tosquiar",
    inputs = { "minecraft:shears" },
    output = { item = "minecraft:red_mushroom", count = 5 },
    by = "minecraft:mooshroom"
})

mod.process("tosquia_snow_golem", {
    title = "Tosquiar",
    inputs = { "minecraft:shears" },
    output = { item = "minecraft:carved_pumpkin", count = 1 },
    by = "minecraft:snow_golem"
})

-- Peixes em balde: o balde de agua vira balde de peixe ao clicar no bicho.
local PEIXES = {
    cod = "minecraft:cod_bucket",
    salmon = "minecraft:salmon_bucket",
    tropical_fish = "minecraft:tropical_fish_bucket",
    pufferfish = "minecraft:pufferfish_bucket",
    axolotl = "minecraft:axolotl_bucket",
    tadpole = "minecraft:tadpole_bucket"
}

for bicho, balde in pairs(PEIXES) do
    mod.process("balde_" .. bicho, {
        title = "Pegar com balde",
        inputs = { "minecraft:water_bucket" },
        output = { item = balde, count = 1 },
        by = "minecraft:" .. bicho
    })
end

return {}

-- Registro por script, na fase em que o jogo ainda aceita conteudo novo.
--
-- O que isto faz que o manifesto nao faz: gerar. Tres variantes de um guardiao saem de um laco, e
-- acrescentar a quarta e mudar um numero -- nao copiar um bloco de JSON e ajustar tres campos.
--
-- Aqui nao ha mundo: nem servidor, nem jogador, nem bloco para tocar. O contexto e pequeno de
-- proposito, porque oferecer o resto seria oferecer chamadas que so podem falhar.
--
-- Quem decide *quando* isto roda e o adaptador. No Fabric e a inicializacao do mod; no NeoForge, o
-- evento de registro do jogo. O mod diz o que quer registrar, e nunca em que momento.

local NIVEIS = {
    { sufixo = "bronze", nome = "Guardiao de Bronze", vida = 40,  cor = 0xCD7F32 },
    { sufixo = "prata",  nome = "Guardiao de Prata",  vida = 80,  cor = 0xC0C0C0 },
    { sufixo = "ouro",   nome = "Guardiao de Ouro",   vida = 160, cor = 0xFFD700 },
}

return function(ctx)
    for _, nivel in ipairs(NIVEIS) do
        ctx.register.entity({
            id = "guardiao_" .. nivel.sufixo,
            name = nivel.nome,
            base = "minecraft:iron_golem",
            category = "misc",
            defaults = {
                health = nivel.vida,
                attributes = { ["minecraft:generic.attack_damage"] = nivel.vida / 10 },
            },
            loot = {
                drops = {{ item = "minecraft:iron_ingot", min = 1, max = 3 }},
            },
            spawn_egg = {
                name = "Ovo de " .. nivel.nome,
                primary_color = nivel.cor,
                secondary_color = 0x333333,
            },
        })
    end

    -- Tamanho de verdade, sem modelo proprio: o jogo tem um atributo de escala desde a 1.20.5, e
    -- ele muda o desenho e a caixa de colisao juntos. Nao e um modelo custom -- a forma continua
    -- sendo a da base --, mas e a maior diferenca visual que se consegue hoje sem entrar em
    -- geometria propria.
    ctx.register.entity({
        id = "guardiao_colossal",
        name = "Guardiao Colossal",
        base = "minecraft:iron_golem",
        -- Nao "misc": essa categoria nao nasce sozinha no jogo -- e a do barco e do quadro, e o
        -- motor de spawn a substitui por porco. Quem quer nascimento natural precisa de uma
        -- categoria que o jogo sorteia.
        category = "monster",
        defaults = {
            health = 300.0,
            attributes = {
                ["minecraft:generic.scale"] = 2.5,
                ["minecraft:generic.attack_damage"] = 25.0,
                ["minecraft:generic.movement_speed"] = 0.2,
            },
        },
        -- Nascimento natural: sem isto a especie so chega ao mundo por comando, ovo ou script.
        -- Uma tag de bioma alcanca um conjunto que cresce com o jogo e com os outros mods; listar
        -- quarenta biomas a mao envelheceria a cada versao.
        spawn = {
            biomes = { "#minecraft:is_mountain" },
            weight = 8,
            min_group = 1,
            max_group = 2,
            min_light = 0,
            max_light = 7,
            min_y = 60,
        },
        spawn_egg = {
            name = "Ovo de Guardiao Colossal",
            primary_color = 0x704214,
            secondary_color = 0xFFD700,
        },
    })

    -- Nesta fase so se enxerga quem registrou antes: a ordem e a da carga, e nao ha mundo para
    -- perguntar. E o bastante para nao registrar duas vezes o mesmo id.
    ctx.log.info("bestiario gerado: " .. #ctx.register.declared() .. " especie(s) ja declaradas")
end

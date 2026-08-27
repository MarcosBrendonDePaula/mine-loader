# Mine Loader — guia de criação de mod

Este guia mostra como escrever um mod do começo ao fim. Ele acompanha o exemplo
[`examples/guilda`](../examples/guilda), que usa junto quase tudo que o loader oferece.

Para a lista completa de eventos, veja o [catálogo de eventos](EVENTS.md). Para o formato de cada
campo, o [formato de mods](MOD_FORMAT_SPEC.md). Para o que ainda não existe, o
[levantamento de lacunas](API_GAPS.md).

## O menor mod possível

Um mod é uma pasta dentro de `run/mods-lua/` cujo nome é igual ao seu `id`:

```text
mods-lua/meu_mod/
└── mod.json
```

```json
{
  "schema": 1,
  "id": "meu_mod",
  "name": "Meu Mod",
  "version": "1.0.0",
  "blocks": [
    { "id": "pedra_azul", "name": "Pedra Azul" }
  ]
}
```

Isso já registra um bloco jogável. Sem Java, sem Lua, sem build.

## Onde a lógica mora

O manifesto é o índice do mod: cada peça aponta onde está o código que responde por ela.

```json
{
  "blocks": [
    {
      "id": "pedra_azul",
      "name": "Pedra Azul",
      "behavior": { "on_use": "scripts/pedra/on_use.lua" }
    }
  ]
}
```

```lua
-- scripts/pedra/on_use.lua
-- Chamado apenas para este bloco: não é preciso checar qual bloco recebeu o clique.
return function(ctx)
    ctx.log.info("clicaram na pedra em " .. ctx.block.x .. "," .. ctx.block.y .. "," .. ctx.block.z)
end
```

Um script de comportamento **devolve a função** que será chamada. O valor de `behavior` pode ser um
arquivo `.lua`, uma URL `https` ou o nome de uma função exportada pelo `main.lua`.

O `entrypoint` é opcional: um mod pode ter apenas manifesto e scripts por peça.

## Exigir capabilities e domínios do runtime

Use `requires` quando o mod precisa de uma parte específica do contrato do MineLoader. A versão é do
contrato, não do Minecraft; por isso o mesmo manifesto pode funcionar nos bridges Fabric e NeoForge.

```json
{
  "schema": 1,
  "id": "meu_mod",
  "name": "Meu Mod",
  "version": "1.0.0",
  "entrypoint": "main.lua",
  "permissions": ["chat.send", "player.read", "world.read"],
  "requires": {
    "domains": {
      "world": "1.0.0"
    },
    "capabilities": {
      "world.block_state.read": "1.0.0",
      "player.looking_at.read": "1.0.0"
    }
  }
}
```

`domains` são grupos amplos, como `world`, `player` e `entity`. `capabilities` são operações precisas,
como `world.block_state.read`. Todos os requisitos declarados são obrigatórios. Se o runtime não
satisfizer um deles, o mod é recusado antes de registar conteúdo ou executar Lua.

Não confunda com `dependencies`: `dependencies` aponta para outro mod, controla a ordem de carga e
permite `mod.require`; `requires` apenas verifica o contrato já oferecido pelo runtime. Há exemplos
completos em [`docs/examples/README.md`](examples/README.md).

## Tempo, clima e mundo

```lua
ctx.server.set_time_of_day(6000)          -- meio-dia; o relogio vai a 24000
local hora = ctx.server.time_of_day()

ctx.server.set_weather("rain", 6000)      -- clear, rain ou thunder
local clima = ctx.server.weather()

local chao = ctx.server.top_y(x, z)       -- altura do primeiro bloco solido
ctx.server.break_block(x, y, z, true)     -- quebra e solta o drop

-- Volte a me chamar nesta posicao daqui a N tiques. Chega como o evento `block_scheduled`, que o
-- bloco mapeia com `behavior.on_scheduled`. So vale em bloco declarado por um mod, e nao se repete
-- sozinho: continuar e agendar o proximo de dentro do proprio callback.
ctx.server.schedule_block(x, y, z, 10)    -- de 1 a 24000 tiques
```

`break_block` nao e o mesmo que escrever ar: respeita a tabela de loot e derrama o inventario do
bloco, que e o que "quebrar" significa para quem joga.

### Ler e alterar o estado real de um bloco

`get_block` devolve apenas o identificador. Quando a lógica precisa saber se uma porta está aberta,
para que lado uma escada aponta ou se um bloco está alagado, use `block_state`. O retorno é uma tabela
com `id` e `properties`; todos os valores das propriedades são texto.

```lua
local estado = ctx.server.block_state(x, y, z)
if estado.id == "minecraft:oak_door" and estado.properties.open == "false" then
    ctx.server.set_block_state(x, y, z, { open = "true" })
end
```

`set_block_state` faz uma alteração parcial e segura. Ele só aceita propriedades que o bloco já possui
e valores válidos para elas; não troca o bloco nem cria propriedades. As duas operações exigem,
respectivamente, `world.read` e `world.write`.

### Game Rules e dificuldade

Game Rules são configurações do mundo, por isso o loader expõe uma whitelist comum em vez de aceitar
chaves arbitrárias. A leitura devolve sempre texto: `"true"`, `"false"` ou um inteiro convertido para
texto. A escrita aceita booleano, número ou texto simples no Lua, mas o bridge confirma o tipo real.

```lua
local ciclo_clima = ctx.server.game_rule("do_weather_cycle")
if ciclo_clima == "true" then
    ctx.server.set_game_rule("do_weather_cycle", false)
end

local dificuldade = ctx.server.difficulty()
if dificuldade == "peaceful" then
    ctx.server.set_difficulty("normal")
end
```

A whitelist inclui regras de clima, spawning, drops, dano, raids, sono, mensagens e ticks, além de
`spawn_radius`, `max_entity_cramming`, `players_sleeping_percentage`, `snow_accumulation_height` e
`spawn_chunk_radius`. Consulte `API_ESTAVEL.md` para a lista completa. Os valores aceitos para
dificuldade são apenas `peaceful`, `easy`, `normal` e `hard`; um mundo com dificuldade bloqueada recusa
a alteração. `world.write` deve ser tratado como permissão administrativa para estas operações.

### Mapa, mundo físico e waypoints

O que o loader chama de mundo físico já inclui bloco, estado, bioma, luz, altura, clima e redstone.
`player.looking_at()` resolve a mira e o teleporte resolve a posição, mas **waypoints ainda não
existem**. Uma futura capability de mapa será própria do MineLoader e não exigirá JourneyMap, Xaero ou
outro mod de cartografia.

## Entidades ja existentes

```lua
local info = ctx.server.entity_info(uuid)   -- type, x, y, z, health, max_health, name
ctx.server.heal_entity(uuid, 10)
ctx.server.apply_to_entity(uuid, { name = "Renomeado", glowing = true })
```

`apply_to_entity` aceita a mesma tabela de `spawn_entity`. Sem ela, os dados declarados so valiam no
instante do nascimento.

Mover uma criatura sem mata-la e cria-la de novo -- o que perderia nome, vida, equipamento e a
domesticacao:

```lua
ctx.server.teleport_entity(uuid, x, y, z)   -- aparece na hora, mantendo os angulos
ctx.server.push_entity(uuid, 0.4, 0.3, 0)   -- empurra: o jogo resolve colisao e queda
```

E ler o lugar antes de decidir:

```lua
local bioma = ctx.server.biome_at(x, y, z)  -- "minecraft:desert"
local luz = ctx.server.light_at(x, y, z)    -- { block, sky, total, dark_enough_for_monster }
```

A luz vem separada por origem porque e a de **bloco** que decide se um monstro nasce ali. Um lugar
iluminado so pelo sol tem quinze de total ao meio-dia e continua escuro a noite: um mod que olhasse
o total erraria todo dia.

## Reagir ao que acontece com as criaturas

```lua
-- no mod.json: "events": { "entity_died": "on_entity_died" }
local function on_entity_died(ctx)
    if ctx.entity.id == "minecraft:zombie" and ctx.entity.source_name then
        ctx.log.info(ctx.entity.source_name .. " matou um zumbi")
    end
end
```

Sao quatro: `entity_spawned`, `entity_damaged`, `entity_died` e `entity_tamed`. Valem para
**qualquer** criatura do mundo, e nao so para as que o seu mod declarou -- e filtrar por
`ctx.entity.id` e decisao sua. So `entity_damaged` aceita cancelamento: devolver `false` impede o
dano.

O `ctx.entity` e uma fotografia do instante, com valores e nao funcoes: no momento da morte,
perguntar a vida ao mundo ja responderia zero. Ver `EVENTS.md` para a lista de campos.

## Criar uma especie propria

Uma criatura nova e declarada no `mod.json`, derivando de uma do jogo. A base entrega modelo,
animacao e comportamento; voce declara so o que muda:

```json
"entities": [
  {
    "id": "guardiao",
    "name": "Guardiao de Cristal",
    "base": "minecraft:iron_golem",
    "defaults": { "health": 60.0 },
    "loot": { "drops": [ { "item": "meu_mod:fragmento", "min": 2, "max": 5 } ] },
    "spawn_egg": { "name": "Ovo de Guardiao" }
  }
]
```

Isso ja basta: a criatura existe, tem vida propria, cai o que voce declarou e ganha um ovo na aba do
criativo. A partir dai da para trocar a pele (`texture`), dar forma propria (`model`), declarar
comportamento (`ai`) e fazer nascer sozinha no mundo (`spawn`).

Uma especie pode herdar de outra, inclusive de outro mod -- e como um pacote de dificuldade
acrescenta um chefe sem repetir o bestiario:

```json
{ "id": "elite", "name": "Guardiao de Elite",
  "base": "outro_mod:guardiao", "defaults": { "health": 120.0 } }
```

E quando o bestiario precisa ser **gerado** -- dez variantes de uma formula, em vez de dez blocos de
JSON quase iguais --, ha a fase de registro: um script proprio, declarado em `registration`, que
roda antes de o jogo congelar os registros. Ver `MOD_FORMAT_SPEC.md` e o exemplo `bestiario`.

## O jogador

```lua
-- leitura
local vida = ctx.player.health()          -- { current, max }
local comida = ctx.player.food()          -- { level, saturation }
local xp = ctx.player.experience()        -- { level, progress }
local modo = ctx.player.game_mode()
local onde = ctx.player.dimension()
local carga = ctx.player.inventory()      -- { { slot, item, count }, ... }

-- escrita
ctx.player.set_health(20)
ctx.player.set_food(20, 5)
ctx.player.give_experience(3)
ctx.player.set_game_mode("adventure")
ctx.player.apply_effect("minecraft:speed", 200, 1)
ctx.player.clear_effects()
ctx.player.clear_inventory()

-- aviso
ctx.player.show_title("Fase 2", "prepare-se", 10, 60, 10)
ctx.player.play_sound_to("minecraft:block.note_block.bell", 1.0, 1.5)
```

`play_sound_to` toca so para aquele jogador, ao contrario de `ctx.server.play_sound`, que toca no
mundo e e ouvido por todos em volta. Um retorno de interface pertence a quem agiu.

```lua
if ctx.server.is_operator() then ... end   -- nivel 2 ou mais, para quem disparou
```

**Repare que e `ctx.server`, e nao `ctx.player`.** A pergunta e sobre a autoridade de quem agiu
naquele servidor, e nao um atributo do corpo do jogador como vida ou posicao; agrupa-la com estes
sugeriria que ela viaja junto com o jogador, e ela nao viaja.

Um mod que abre um painel de administracao deve perguntar, e nao presumir: o comando `/mod` esta ao
alcance de qualquer jogador.

A escrita exige a permissao `player.modify`, separada de `player.read` e de `player.inventory`:
mudar vida ou modo de jogo altera as regras sob os pes de quem joga.

## Consultar o registro

```lua
ctx.server.items({ namespace = "minecraft", contains = "ingot", limit = 64 })
ctx.server.blocks({ contains = "stone" })
ctx.server.entity_types({ namespace = "minecraft" })
```

As tres aceitam o mesmo filtro e o mesmo teto -- sem limite declarado, 256; o maximo e 4096.

## Uma tela que se atualiza sozinha

`mod.after` agenda uma funcao para daqui a N tiques. **A tarefa lembra de quem a agendou**: se ela
nasceu dentro de um evento de jogador -- um clique de tela, um comando --, `ctx.player` volta a
valer dentro dela. E o que permite uma maquina ter numero que anda.

```lua
local INTERVALO = 1   -- um tique; a tela do jogo anda assim

local function acompanhar(ctx, estado, geracao)
    mod.after(INTERVALO, function(depois)
        -- Para quando a tela fecha, e quando outra abertura tomou o lugar desta.
        if not estado.aberta or estado.geracao ~= geracao then return end
        if depois.player == nil then return end

        depois.player.update_screen(desenhar(depois, estado))
        acompanhar(depois, estado, geracao)
    end)
end
```

Tres coisas que precisam estar no laco, e cuja falta nao da erro nenhum:

- **Parar quando a tela fecha.** O evento de tela com `ctx.ui.action == "close"` e quem apaga a
  marca. Sem isso, cada abertura deixa uma tarefa viva pelo resto da sessao.
- **Uma geracao por abertura.** Reabrir antes de o laco anterior perceber que fechou daria duas
  tarefas correndo juntas sobre a mesma tela.
- **Conferir `depois.player`.** Ele e nulo quando a tarefa nao veio de um jogador, e quando o
  jogador saiu do servidor.

**O que custa por volta e o envio da tela**, e nao ler os dados. Se a tela mostra algo que exige
varredura -- uma rede de blocos, por exemplo --, guarde o resultado e refaca-o **por evento**, e nao
a cada volta: um cache por tempo erra dos dois lados, curto demais nao economiza e longo demais
mostra o que nao existe mais.

## Perguntar ao jogo

Quatro perguntas que o proprio jogo responde. Todas exigem `server.read`, **nenhuma consome nada** e
todas valem para o conteudo de qualquer mod instalado -- e essa e a razao de existirem: uma tabela
escrita dentro do mod saberia so o que o autor dele conhecia, e nasceria errada no primeiro modpack.

```lua
-- O que sai de um arranjo de bancada 3x3.
local saida = ctx.server.crafting_result({
    "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks",
    "minecraft:oak_planks", nil,                    "minecraft:oak_planks",
    "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks",
})
-- saida = { item = "minecraft:chest", count = 4 }, ou nil quando o arranjo nao faz nada

-- Como se faz um item, e o que se faz com ele.
local receitas = ctx.server.recipes_for("minecraft:chest")
local usos     = ctx.server.recipes_using("minecraft:oak_planks")

-- Por quantos tiques um item queima numa fornalha.
local tiques = ctx.server.fuel_burn_time("minecraft:coal")   -- 1600
local nada   = ctx.server.fuel_burn_time("minecraft:stone")  -- 0
```

**Nil e cadeia vazia sao a mesma coisa** nos nove slots de `crafting_result`: exigir a distincao
faria toda tela preencher os buracos com `""`.

**`fuel_burn_time` devolve zero, e nao nil**, quando o item nao queima. "Nao queima" e uma resposta,
e devolver nil faria toda conta precisar de um `or 0` antes de somar. O numero e o mesmo que a
fornalha usa, entao o combustivel que outro mod registrou tambem conta -- e um gerador escrito em
Lua aceita carvao, tabua e a vara de blaze sem listar nenhum deles.

## Entidade e item com dados

`spawn_entity` e `give_item` aceitam uma tabela com o que o mod quer declarar. Sem ela, nasce a
entidade comum e o item comum.

### Entidade

```lua
ctx.server.spawn_entity("minecraft:zombie", x, y, z, {
    -- identidade
    name = "Chefe da Masmorra",
    name_visible = true,

    -- natureza
    tame = true,          -- lobo, gato, papagaio, cavalo
    baby = false,
    persistent = true,    -- impede o jogo de remover o bicho quando ninguem esta por perto
    no_ai = false,
    variant = "white",    -- cor do cavalo; so no adaptador Fabric por ora

    -- corpo
    health = 40,
    attributes = {
        ["minecraft:generic.movement_speed"] = 0.35,
        ["minecraft:generic.attack_damage"] = 12
    },
    effects = {
        { id = "minecraft:strength", duration = 1200, amplifier = 1 },
        { id = "minecraft:speed" }   -- sem duracao, trinta segundos
    },
    equipment = {
        main_hand = { item = "minecraft:diamond_sword",
                      name = "Lamina do Chefe",
                      enchantments = { ["minecraft:sharpness"] = 3 },
                      drop_chance = 1.0 },
        head = "minecraft:diamond_helmet"   -- forma curta, so o identificador
    },

    -- estado
    invulnerable = false,
    silent = false,
    no_gravity = false,
    glowing = true,       -- contorno visivel atraves de blocos
    fire_ticks = 0,
    frozen_ticks = 0,

    -- orientacao
    yaw = 90,
    pitch = 0
})
```

Espaços de equipamento: `main_hand`, `off_hand`, `head`, `chest`, `legs`, `feet`.

### Item

```lua
ctx.player.give_item("minecraft:diamond_sword", 1, {
    name = "Espada do Chefe",
    lore = {"Forjada em cristal"},
    color = 0xFF0000,           -- so armadura de couro
    custom_model_data = 3,

    damage = 0,
    unbreakable = true,

    enchantments = { ["minecraft:sharpness"] = 5, ["minecraft:unbreaking"] = 3 },
    attributes = { ["minecraft:generic.attack_damage"] = 15 },

    keep_on_death = true,
    no_drop = true
})
```

### O que vale saber

**Campo ausente não é o mesmo que campo declarado com o valor padrão.** Ausente deixa o jogo
decidir, como faria sem o mod; `baby = false` declarado impede o jogo de escolher outra coisa.

**O que a entidade não suporta é ignorado, não recusado.** Declarar `tame` para uma lista de bichos
não falha nos que não são domesticáveis. Um identificador de atributo ou efeito que não exista é
ignorado pela mesma razão. O que é recusado com erro: identificador malformado e duração negativa.

**Encantamento com nível zero é descartado** — é a ausência dele, e guardá-lo mostraria uma linha
sem efeito no item.

**A inclinação é recortada** a noventa graus para cada lado, que é o que o jogo aceita. Deixar
passar produziria uma cabeça torcida ao contrário.

**`variant` cobre o cavalo, e só ele.** Cada espécie nomeia a própria variante de um jeito, e não há
contrato do jogo que sirva a todas; um mapeamento inventado acertaria uma espécie e mentiria nas
outras. Um nome que a espécie não conhece é ignorado, como qualquer campo que não se aplica. As duas
plataformas fazem o mesmo.

**Por que não é NBT.** O formato interno de item virou componentes na 1.20.5, e um mod que tivesse
escrito a forma anterior pararia de funcionar sem ter mudado uma linha. O vocabulário aqui é uma
pergunta que qualquer versão responde; traduzir para o que aquela versão chama daquilo é trabalho
do adaptador. É por isso que os campos são um conjunto fechado, e não um mapa livre.

**Atributos e efeitos são mapas**, e não campos fixos: o jogo tem dezenas deles e ganha novos a
cada versão, e uma lista fechada aqui envelheceria a cada uma.

## Permissões

Um mod só faz o que declara. Pedir uma permissão que não será usada é ruído; usar uma operação sem
declarar a permissão dela é erro em tempo de execução.

```json
"permissions": ["chat.send", "player.read", "world.write"]
```

| Permissão | Libera |
|---|---|
| `chat.send` | Mensagens no chat e na action bar |
| `player.read` | Item na mão, posição, vida, contagem de itens |
| `player.inventory` | Dar e remover itens |
| `player.move` | Teleporte |
| `player.menu` | Abrir, atualizar e fechar janelas |
| `world.read` | Ler blocos, estado, regras, dificuldade, bioma, luz, hora, clima e redstone; tocar som e emitir partículas |
| `world.write` | Alterar blocos/estado, regras, dificuldade, preencher, posicionar estrutura, gravar dados e agendar tique |
| `world.containers` | Ler, inserir e extrair do inventário de um bloco |
| `server.read` | Jogadores conectados, hora do dia, dimensão, regras, dificuldade e mods carregados |
| `server.command.register` | Registrar comandos |
| `server.install` | Instalar e desinstalar mods por link — veja `INSTALACAO.md` |
| `entity.read` / `entity.spawn` / `entity.modify` | Entidades |
| `entity.register` | Declarar espécie nova por script. Mais forte que as três acima: acrescenta um tipo ao registro do jogo, que vale para o mundo inteiro e não se desfaz sem reiniciar |

## Saber para onde quem joga está olhando

```lua
local alvo = ctx.player.looking_at()      -- alcance de 5 blocos, o de construção
local longe = ctx.player.looking_at(20)   -- até 64

if alvo ~= nil then
    ctx.server.broadcast(alvo.x .. "," .. alvo.y .. "," .. alvo.z .. " lado " .. alvo.side)
end
```

Devolve `x`, `y`, `z` e `side` (`"up"`, `"down"`, `"north"`, `"south"`, `"west"`, `"east"`), ou
**nil** quando a linha de visão não encontra bloco. Exige `player.read`.

**`nil` e não zero:** olhar para o céu é uma resposta legítima, e devolver uma posição faria o mod
agir sobre a origem do mundo sem ninguém ter mirado nela.

Serve para um comando não pedir coordenada digitada — mirar é o gesto natural, e a alternativa é
abrir o F3 e anotar três números do bloco que se está vendo na frente.

## Desenhar um bloco com malha

Quando caixas não bastam — um cano, uma máquina, qualquer coisa arredondada — o bloco pode apontar
um arquivo `.obj`:

```json
"resources": { "forma": { "type": "model", "from": "models/cano.obj" } },
"blocks": [{ "id": "cano", "render": { "model": "@forma", "texture": { "ref": "metal" } } }]
```

O arquivo é encaixado no bloco automaticamente. Se ele for um **catálogo de peças** — o miolo, a
manga de cada lado, as placas —, `obj_parts` diz o que desenhar em cada estado; veja
`MOD_FORMAT_SPEC.md`.

Vale nas duas plataformas, com o mesmo arquivo virando o mesmo desenho — é para isso que o leitor
mora no núcleo em vez de cada lado usar o seu.

## Desenvolver um mod que mora em outro repositório

Um mod não precisa estar dentro de `mods-lua` para ser carregado. Aponte a pasta e o loader lê
direto de lá:

```bash
./gradlew :runtimes:fabric:1.21.4:runClient -Pmods=E:/meu-mod/logistica
MINE_LOADER_MODS=/caminho/do/mod ./gradlew :runtimes:neoforge:1.21.4:runServer
```

Aceita várias pastas, separadas por `;` no Windows e `:` no resto. Cada uma pode ser **a pasta do
mod** (a que tem `mod.json`) ou **uma pasta que contém vários**.

**Por que isso existe.** A alternativa era copiar o mod para dentro de `mods-lua`, e a cópia
envelhece: o servidor passa a rodar contra um script velho **dizendo que passou** — o pior resultado
possível, porque parece verificação. Aconteceu neste repositório.

Se o mesmo id existir na pasta extra e na pasta do jogo, **a pasta extra ganha** — quem apontou está
trabalhando naquele mod. O log diz as duas origens, para não restar dúvida de qual está rodando.

Para **publicar**, o caminho é outro: `remote_base` no manifesto faz o loader buscar os arquivos na
web quando eles não existem no disco — `entrypoint`, módulos de `mod.import`, comportamento de
bloco, textura, modelo e `$import`. Um mod publicado pode ser instalado com um `mod.json` de poucas
linhas.

## Guardar informação

São três lugares diferentes, e escolher o errado costuma ser a causa de bugs difíceis.

| Onde | Escopo | Sobrevive a reinício |
|---|---|---|
| `mod.state` / `ctx.state` | Todo o mod | Sim, gravado em disco |
| `get_block_data` / `set_block_data` | Aquela posição no mundo | Sim, junto com o mundo |
| Variável Lua local | Aquele script, aquela sessão | Não |

```lua
-- Progresso do mod inteiro, persistido:
ctx.state.total = (ctx.state.total or 0) + 1

-- Dados daquele bloco específico, na posição em que está:
local dados = ctx.server.get_block_data(ctx.block.x, ctx.block.y, ctx.block.z)
dados.usos = (dados.usos or 0) + 1
ctx.server.set_block_data(ctx.block.x, ctx.block.y, ctx.block.z, dados)
```

O bloco precisa declarar `"block_data": true` para guardar dados próprios.

## Janelas

Uma janela é uma grade de itens desenhada pelo mod. Cada slot é um **botão**: o jogador não retira
nada, e o clique volta para o script com o índice do slot.

```lua
-- Registra a lógica da janela uma vez, no corpo do script.
mod.menu("loja", function(ctx)
    if ctx.menu.slot == 8 then
        ctx.player.close_menu()
        return
    end
    ctx.player.send_message("clicou no slot " .. ctx.menu.slot .. " com botao " .. ctx.menu.button)
    -- Redesenhar mantém a janela aberta.
    ctx.player.update_menu(desenhar(ctx))
end)

-- Abre a janela para um jogador.
ctx.player.open_menu("loja", "Minha Loja", 3, {
    { item = "minecraft:diamond", count = 5, label = "Comprar" },
    "minecraft:emerald",
    nil,                                  -- slot vazio
    { item = "minecraft:barrier", label = "Fechar" }
})
```

O contexto do clique traz `ctx.menu.id`, `ctx.menu.slot`, `ctx.menu.button` e `ctx.menu.item`.

A janela usa a tela de container do próprio jogo, então funciona em qualquer cliente vanilla. Em
troca, não há HUD, botões desenhados nem campos de texto: o vocabulário é item, quantidade e rótulo.

## Comandos

```lua
mod.command("guilda", function(ctx)
    if ctx.subcommand == "status" then
        ctx.player.send_message("tudo certo")
    elseif ctx.subcommand == "dar" then
        -- /mod guilda dar diamante 3
        ctx.player.give_item("minecraft:" .. ctx.argv[2], tonumber(ctx.argv[3]) or 1)
    end
end)
```

O comando é publicado como `/mod <nome>`. O contexto traz três formas do que foi digitado:

| Campo | Conteúdo para `/mod guilda dar diamante 3` |
|---|---|
| `ctx.args` | `"dar diamante 3"` |
| `ctx.argv` | `{"dar", "diamante", "3"}` |
| `ctx.subcommand` | `"dar"` |

Para ter uma estrutura real no autocomplete, declare o schema no `mod.json` e exija a capability `server.command.schema`:

```json
{
  "permissions": ["server.command.register"],
  "requires": {
    "capabilities": {
      "server.command.schema": "1.0.0"
    }
  },
  "commands": {
    "guilda": {
      "children": [
        { "literal": "status" },
        { "literal": "dar", "children": [
          { "argument": {
            "name": "item",
            "type": "word",
            "suggestions": ["diamante", "ferro", "ouro"]
          }, "children": [
            { "argument": {
              "name": "quantidade",
              "type": "integer",
              "min": 1,
              "max": 64
            }}
          ]}
        ]}
      ]
    }
  }
}
```

No Lua, associe apenas o callback:

```lua
mod.command("guilda", function(ctx)
    if ctx.argv[1] == "dar" then
        local item = ctx.command.arguments.item
        local quantidade = ctx.command.arguments.quantidade
        ctx.player.give_item("minecraft:" .. item, quantidade)
    end
end)
```

Neste formato, o Minecraft sugere `status`, `dar` e os itens declarados, valida a quantidade entre 1 e 64 e entrega os valores nomeados em `ctx.command.arguments`. O formato antigo continua válido para mods que preferem interpretar `ctx.args` manualmente. A referência completa está em [COMMANDS.md](COMMANDS.md).

## Tempo

```lua
-- Faz algo daqui a 2 segundos (40 ticks).
mod.after(40, function(depois)
    depois.server.broadcast("passaram dois segundos")
end)
```

Isso evita contar ticks à mão dentro do evento `tick`, que é global e caro.

## Construir no mundo

```lua
ctx.server.set_block("minecraft:stone", x, y, z)
ctx.server.fill("minecraft:glass", x1, y1, z1, x2, y2, z2)
local id = ctx.server.get_block(x, y, z)
```

Para construções maiores, declare a estrutura no manifesto e posicione com uma chamada:

```lua
local blocos = ctx.server.place_structure("posto", x, y, z)
```

## Dividir o mod em arquivos

```json
{
  "blocks": [{ "$import": "parts/blocks/quadro.json" }],
  "items":  [{ "$import": "parts/items/emblema.json" }]
}
```

E para publicar o mod na web, com o usuário instalando apenas um manifesto pequeno:

```json
{
  "remote_base": "https://raw.githubusercontent.com/usuario/repo/main/meu_mod/",
  "blocks": [{ "$import": "parts/blocks/quadro.json" }]
}
```

Cada arquivo é procurado primeiro no disco e, se não existir, sob a base. Sem `sha256`, o conteúdo é
buscado a cada carga, então publicar uma versão nova atualiza o mod na próxima inicialização.

## Organizar o código em módulos

Um mod pode ter arquivos Lua próprios, carregados com `mod.import`:

```lua
-- lib/ui.lua
local M = {}
M.CORES = { titulo = "#FFD700" }
function M.titulo(x, y, texto)
    return { type = "label", x = x, y = y, text = texto, color = M.CORES.titulo, scale = 1.5 }
end
return M
```

```lua
-- main.lua
local ui = mod.import("lib/ui.lua")
```

| Propriedade | Comportamento |
|---|---|
| Execução | O arquivo roda uma vez, mesmo importado em vários lugares |
| Retorno | O que o arquivo devolve é o que o import entrega; sem retorno, entrega `true` |
| Escopo | O módulo compartilha os globais do mod, então enxerga `mod.state` e a API do loader |
| Caminho | Resolvido dentro da pasta do mod, ou sob `remote_base` quando o mod é publicado na web |
| Ciclos | Um import circular é recusado com a cadeia no erro |

O `require`, `dofile` e `loadfile` padrão do Lua continuam fora do ambiente: eles procurariam arquivos
em qualquer lugar da máquina. `mod.import` faz o mesmo papel com o caminho preso ao mod.

Não confunda com `mod.require`, que serve para usar **outro mod** como biblioteca:

| Função | Alcance |
|---|---|
| `mod.import("lib/x.lua")` | Um arquivo do próprio mod |
| `mod.require("outro_mod")` | A API pública de outro mod, declarado em `dependencies` |

## Usar outro mod como biblioteca

```json
"dependencies": { "ui_lib": "1.0.0" }
```

```lua
local ui = mod.require("ui_lib")
```

A dependência normalmente carrega antes de quem a consome. Se o mod for carregado isoladamente, o
runtime usa o catálogo descoberto e resolve a biblioteca sob demanda na primeira chamada a
`mod.require()`. A exportação fica em cache para chamadas seguintes.

Se `ui_lib` depender de outro mod, a resolução é recursiva:

```text
meu_mod -> ui_lib -> base_lib
```

Uma dependência que volte a um mod ainda em resolução é recusada com a cadeia no erro:

```text
mod_a -> mod_b -> mod_c -> mod_a
```

Isso vale mesmo quando os manifestos escapam da ordem normal de arranque. A biblioteca precisa continuar
declarada em `dependencies`; `mod.require()` não instala código nem procura ficheiros fora do catálogo.

Dentro de uma biblioteca, use `mod.server` em vez de `ctx.server`: assim ela age com as próprias
permissões, e não com as de quem a chamou.

## Limites que o loader impõe

Eles existem para que um erro de script não derrube o servidor, e um mod comum nunca os encontra.

| Limite | Valor |
|---|---|
| Tempo de um callback | 20 ms |
| Blocos por `fill` ou estrutura | 32.768 |
| Coordenada | 30.000.000 |
| Tarefas agendadas simultâneas | 4.096 |
| Itens por operação de inventário | 1.024 |

Passar do tempo interrompe **aquele** callback, com o mod identificado no log; os outros seguem
normalmente.

## Ciclo de desenvolvimento

```powershell
./gradlew.bat runClient      # abre o jogo com os mods de run/mods-lua
```

Com o jogo aberto, depois de alterar um script:

```text
/lua reload meu_mod
```

A recarga recompila os scripts e descarta tarefas e comandos do ambiente anterior. O `mod.state`
sobrevive, para que alterar código não apague o progresso acumulado. Alterar o `mod.json` — registrar
um bloco novo, mudar uma receita — ainda exige reiniciar.

Comandos úteis de diagnóstico:

| Comando | Mostra |
|---|---|
| `/lua list` | Mods carregados |
| `/lua blocks` | Blocos registrados |
| `/lua commands` | Comandos publicados pelos mods |
| `/lua reload` | Recarrega todos os scripts |

Os comandos de mod ficam sob `/mod <nome>`, e não no nível raiz, para não colidirem com o jogo nem
entre si. Use `/lua commands` para ver quais existem.

## Erros comuns

**O mod não aparece.** O nome da pasta precisa ser igual ao `id`. O log diz o motivo exato de cada
mod recusado, e um mod inválido não impede os outros de carregar.

**"permissão ausente".** A operação existe, mas o `permissions` não a declarou.

**Campo ignorado.** O loader avisa na carga quais campos declarados ele ainda não aplica, um a um.
Se algo não surtiu efeito, o log dirá.

**O script não roda.** Um arquivo de `behavior` precisa **devolver** uma função, não apenas
defini-la.

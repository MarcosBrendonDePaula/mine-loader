# Mine Loader — especificação do formato de mods

**Versão do formato:** 1  
**Arquivo principal:** `mod.json`  
**Extensão de lógica:** Lua 5.2 compatível com o runtime LuaJ do protótipo

## 1. Pacote

Um mod é uma pasta independente cujo nome corresponde ao seu `id`. O pacote deve conter um `mod.json` válido e pode conter um entrypoint Lua, recursos locais, dados de jogo e testes.

```text
example_mod/
├── mod.json
├── main.lua
├── assets/example_mod/
│   ├── textures/block/
│   ├── models/block/
│   └── models/item/
├── data/example_mod/
└── tests/
```

O loader não executará arquivos que escapem da raiz do pacote. Separadores alternativos, caminhos absolutos, `..` e links simbólicos externos devem ser rejeitados.

## 2. Manifesto

```json
{
  "schema": 1,
  "id": "example_mod",
  "name": "Example Mod",
  "version": "0.1.0",
  "description": "Um mod de exemplo.",
  "authors": ["Autor"],
  "entrypoint": "main.lua",
  "permissions": ["chat.send", "world.write"],
  "events": {
    "server_started": "on_server_started",
    "tick": "on_tick"
  },
  "dependencies": {},
  "blocks": [],
  "enabled": true
}
```

O `id` deve usar o formato de identificador do Minecraft, com namespace em minúsculas, números, `_`, `-` ou `.`. O `version` usa três componentes numéricos no perfil inicial. Dependências deverão apontar para IDs de mods e uma faixa de versão.

O campo `enabled` permite desabilitar o pacote sem removê-lo. Um mod desabilitado não pode registrar conteúdo nem iniciar scripts.

### 2.1 Lado do mod (`side`)

`"side"` aceita `"server"` ou `"both"`, e diz se quem entra num servidor precisa ter o mod instalado
também.

A distinção existe porque o Lua roda só no servidor e a tela viaja como dados: um mod de comando,
evento, menu ou tela funciona para quem entrou sem ter baixado nada. Já um bloco declarado precisa
estar registrado nos dois lados, ou a sincronização de registro do jogo recusa a conexão — e por
isso `"server"` é **recusado** num mod que declara bloco ou item, em vez de produzir uma
desconexão que ninguém liga ao manifesto.

**`"client"` não existe**, e a ausência é deliberada: nenhum script roda no cliente hoje, então o
valor não teria efeito. Um campo aceito e ignorado é pior que um campo ausente — o ausente dá erro,
o ignorado dá silêncio.

**Ausente significa "deduza":** um mod que registra bloco ou item é `both`, e o resto é `server`. O
padrão é deduzir porque a resposta já está no manifesto, e pedi-la de novo só criaria a chance de as
duas discordarem.

## 3. Blocos

A declaração de bloco é composta por identidade, material, configurações, estados, renderização, item, drops, tags e comportamento.

```json
{
  "id": "crystal_block",
  "name": "Bloco de Cristal",
  "material": {
    "map_color": "light_blue",
    "sound": "glass",
    "instrument": "harp",
    "piston_behavior": "normal"
  },
  "settings": {
    "hardness": 1.5,
    "resistance": 6.0,
    "slipperiness": 0.6,
    "velocity_multiplier": 1.0,
    "jump_velocity_multiplier": 1.0,
    "luminance": 0,
    "requires_tool": false,
    "random_ticks": false,
    "collidable": true,
    "opaque": true,
    "burnable": false,
    "replaceable": false,
    "suffocates": true,
    "blocks_vision": true,
    "post_process": false,
    "emissive": false
  },
  "states": {
    "properties": [],
    "default": {}
  },
  "render": {
    "model": "cube_all",
    "texture": {
      "source": "local",
      "path": "assets/crystal_block/textures/block/crystal_block.png",
      "fallback": "minecraft:block/glass"
    },
    "variant_textures": {}
  },
  "item": {
    "register": true,
    "max_count": 64,
    "rarity": "common",
    "fire_resistant": false
  },
  "drops": {
    "mode": "self",
    "count": 1
  },
  "tags": [],
  "behavior": {}
}
```

## 4. Propriedades e capacidade de recarga

| Campo | Tipo | Recarga inicial |
|---|---|---:|
| `id` | string | Não |
| `material` | objeto | Não |
| `settings.hardness` | número | Valor inicial; pode ter getter dinâmico se suportado |
| `settings.resistance` | número | Valor inicial; pode ter getter dinâmico se suportado |
| `settings.slipperiness` | número | Valor inicial; pode ter getter dinâmico se suportado |
| `settings.luminance` | inteiro | Estado inicial; `lua_luminance` pode mudar por posição |
| `states.properties` | array | Não após registro |
| `render.texture` | objeto | Requer preparação do resource pack |
| `render.variant_textures` | mapa | Preparado antes do cliente carregar recursos |
| `item` | objeto | Não |
| `drops` | objeto | Pode ser recarregado quando convertido em dados |
| `behavior` | objeto | Depende do callback e do tipo de bloco |

O loader deve retornar uma mensagem de diagnóstico quando um campo solicitado não for suportado dinamicamente. Não deve aceitar silenciosamente uma alteração que o jogo não consiga aplicar.

## 5. Recursos e texturas

### Recursos nomeados

Um recurso é declarado uma vez em `resources` e referenciado onde for preciso por `"@nome"`:

```json
{
  "resources": {
    "cristal": {
      "type": "image",
      "from": "assets/crystal_world/textures/block/crystal_block.png",
      "fallback": "minecraft:block/amethyst_block"
    },
    "mesa": { "type": "model", "from": "models/mesa.json" },
    "remota": {
      "type": "image",
      "from": "https://cdn.example.org/blocks/crystal-blue.png",
      "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      "max_bytes": 1048576
    }
  },

  "blocks": [
    { "id": "altar", "render": { "texture": "@cristal" } },
    { "id": "cofre", "render": { "texture": "@cristal" } }
  ]
}
```

`type` é `image`, `model`, `sound`, `script` ou `data`. `from` é um caminho dentro do mod ou um
endereço HTTPS — um campo só, porque o prefixo já diz qual dos dois é. `sha256` é conferido depois
de baixar, e fica junto do recurso que protege.

O `fallback` do recurso vale para todos os usos; quem referencia pode declarar o seu, e nesse caso o
dele vence:

```json
"variant_textures": {
  "0": "@quadro",
  "1": { "ref": "quadro", "fallback": "minecraft:block/emerald_block" }
}
```

A seção inteira pode vir de outro arquivo, como qualquer parte do manifesto:

```json
"resources": { "$import": "parts/resources.json" }
```

**Uma referência a um recurso que não existe, ou de tipo errado, recusa o mod na carga** — com o
nome na mensagem e a lista do que existe. Descoberto em jogo, o sintoma seria um cubo roxo, que não
diz nome de recurso nenhum.

### Declaração no lugar

A forma inline continua válida, e é o que faz um manifesto antigo seguir funcionando. Uma textura
possui `source` igual a `local` ou `remote`. Para fonte local, `path` é relativo à raiz do mod. Para fonte remota, `url` precisa ser HTTPS. `sha256` é recomendado para conteúdo remoto e poderá ser obrigatório em servidores.

```json
{
  "source": "remote",
  "url": "https://cdn.example.org/blocks/crystal-blue.png",
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "max_bytes": 1048576,
  "fallback": "minecraft:block/glass"
}
```

O loader deverá converter a imagem validada para PNG no resource pack gerado. O resource pack não deve incluir um arquivo baixado antes de terminar a validação de tamanho, imagem e hash.

## 6. Estados visuais

O campo `render.variant_textures` usa chaves numéricas de 0 a 15. A implementação inicial expõe o estado `lua_variant` com o mesmo intervalo.

```json
"variant_textures": {
  "0": { "source": "local", "path": "assets/example_mod/textures/block/off.png" },
  "1": { "source": "local", "path": "assets/example_mod/textures/block/on.png" }
}
```

Variantes não declaradas usam a variante 0. Um script pode chamar:

```lua
ctx.server.set_block_variant("example_mod:crystal_block", 0, 100, 0, 1)
```

Essa chamada altera o estado do bloco, não o registry. O cliente recebe a mudança como uma atualização normal de blockstate.

## 7. Lua e callbacks

O entrypoint pode retornar uma tabela de funções. O manifesto mapeia eventos a nomes dessas funções.

```lua
local function on_server_started(ctx)
    ctx.log.info("Mod ativo")
end

local function on_tick(ctx)
    -- lógica curta e determinística
end

return {
    on_server_started = on_server_started,
    on_tick = on_tick
}
```

A forma complementar `mod.on("event", callback)` também é suportada pelo protótipo, mas eventos desconhecidos ou não permitidos devem causar erro de validação.

## 8. Permissões

Permissões são strings conhecidas pelo loader. O mod não pode inventar uma permissão e esperar que ela seja aceita.

| Permissão | Acesso |
|---|---|
| `chat.send` | Mensagens públicas e mensagens para jogadores. |
| `player.read` | Nome e UUID do jogador no contexto de evento. |
| `server.read` | Leitura de informações públicas do servidor. |
| `server.command.register` | Registro de comandos do mod. |
| `world.write` | Alteração autorizada de estados e propriedades de conteúdo do loader. |

Permissões futuras para recursos remotos, conteúdo de cliente e persistência devem ser específicas e ter limites próprios.

## 9. Compatibilidade do formato

O campo `schema` identifica o formato estrutural. O loader deve distinguir erro de sintaxe, erro de schema, incompatibilidade de versão e falha de runtime. Uma migração poderá transformar schema 1 em schema 2, mas não deve alterar o conteúdo silenciosamente.

Campos de extensão deverão usar uma chave de namespace, por exemplo `extensions.example_mod`. Campos desconhecidos na raiz devem ser rejeitados no modo estrito e preservados apenas no modo de ferramentas.

## Itens

O campo `items` declara itens que nao pertencem a um bloco. Cada item exige `id` e `name`.

```json
{
  "items": [
    {
      "id": "ruby",
      "name": "Rubi",
      "max_stack_size": 64,
      "max_damage": 0,
      "rarity": "rare",
      "fire_resistant": false,
      "texture": {
        "source": "local",
        "path": "assets/hello_lua/textures/item/ruby.png",
        "fallback": "minecraft:item/redstone"
      }
    }
  ]
}
```

| Campo | Valor | Observacao |
|---|---|---|
| `max_stack_size` | 1 a 64 | Padrao 64. |
| `max_damage` | inteiro >= 0 | Maior que zero exige `max_stack_size` igual a 1; caso contrario o manifesto e rejeitado. |
| `rarity` | `common`, `uncommon`, `rare`, `epic` | Outro valor e rejeitado. |
| `fire_resistant` | booleano | Item nao queima no lava/fogo. |
| `texture` | objeto de textura | Sem `path`, o loader usa `fallback`. |

## Especies declaradas

O campo `entities` declara uma especie propria, **derivada de uma do jogo**. Cada especie exige
`id`, `name` e `base`.

A base nao e conveniencia: uma criatura viva e modelo, animacao e comportamento, tres sistemas
separados. Derivar de uma especie do jogo entrega os tres, e o manifesto declara so o que muda. Uma
especie sem base nasceria invisivel e parada, e um mob invisivel nao se parece com erro de
manifesto -- por isso o manifesto e recusado sem ela.

```json
{
  "entities": [
    {
      "id": "crystal_guardian",
      "name": "Guardiao de Cristal",
      "base": "minecraft:iron_golem",
      "category": "misc",
      "fire_immune": true,
      "defaults": {
        "health": 60.0,
        "glowing": true,
        "attributes": {
          "minecraft:generic.movement_speed": 0.3,
          "minecraft:generic.attack_damage": 9.0
        }
      },
      "loot": {
        "drops": [
          { "item": "crystal_world:crystal_shard", "min": 2, "max": 5 }
        ]
      },
      "spawn_egg": {
        "name": "Ovo de Guardiao de Cristal",
        "primary_color": 8375321,
        "secondary_color": 3381759
      }
    }
  ]
}
```

| Campo | Valor | Observacao |
|---|---|---|
| `base` | id do jogo, com namespace | Obrigatorio. Fora da lista de bases suportadas, o mod e recusado na carga com a lista no erro. |
| `category` | `monster`, `creature`, `ambient`, `misc`, `water_creature`, `water_ambient`, `underground_water_creature`, `axolotls` | Sem declarar, a da base. Decide limite populacional e nascimento natural. |
| `width`, `height` | 0 a 16, em blocos | Zero herda a da base. |
| `tracking_range` | 0 a 32 | Zero herda. Distancia em que o cliente acompanha a criatura. |
| `update_interval` | inteiro >= 0 | Zero herda. A cada quantos tiques a posicao e reenviada. |
| `fire_immune` | booleano | |
| `summonable` | booleano | Padrao verdadeiro. Falso tira do `/summon`. |
| `saveable` | booleano | Padrao verdadeiro. Falso faz a criatura sumir ao descarregar o mundo. |
| `defaults` | objeto de dados de entidade | O **mesmo** vocabulario de `spawn_entity`: nome, natureza, corpo, equipamento, efeitos, estado, rotacao, variante. |
| `loot` | objeto | Ver abaixo. |
| `spawn_egg` | objeto | Ausente nao gera ovo. |
| `spawn` | objeto | Nascimento natural. Ausente significa que a especie **so** chega ao mundo por comando, ovo ou script. **Nao e herdado.** Ver abaixo. |
| `model` | `"@recurso"`, caminho no mod ou URL | A **forma** da criatura: ossos e caixas em JSON. Sem declarar, ela tem a forma da base. **Nao e herdada.** Ver abaixo. |
| `texture` | `"@recurso"`, caminho no mod ou URL | A pele da criatura. **Sem declarar, ela usa a da base** -- e o que faz um guardiao sair identico a um golem de ferro. Copiada para `textures/entity/<id>.png`. **Nao e herdada.** |
| `tags` | lista de ids | Geradas em `tags/entity_type`, com `replace: false`. **Nao sao herdadas**: o data pack sai do manifesto declarado, e herdar aqui daria uma especie que o adaptador considera marcada e o jogo nao. |

**Bases suportadas hoje:** `minecraft:zombie`, `minecraft:skeleton`, `minecraft:creeper`,
`minecraft:spider`, `minecraft:pig`, `minecraft:cow`, `minecraft:sheep`, `minecraft:chicken`,
`minecraft:wolf`, `minecraft:iron_golem`.

A lista e explicita e cresce por adaptador. Registrar a partir de uma base qualquer produziria uma
entidade que se declara como a base original e se perderia ao salvar o mundo -- entao o loader
recusa a base desconhecida em vez de aproximar.

### Herdar a especie de outro mod

`base` tambem aceita uma especie declarada por outro mod, no formato `outromod:especie`. E como um
pacote de dificuldade acrescenta um chefe sem repetir o bestiario inteiro:

```json
{
  "dependencies": { "crystal_world": "0.1.0" },
  "entities": [
    {
      "id": "elite_guardian",
      "name": "Guardiao de Elite",
      "base": "crystal_world:crystal_guardian",
      "defaults": { "health": 120.0 }
    }
  ]
}
```

O filho vence campo a campo, e o que ele nao declara vem do pai. Sem a mescla, o exemplo acima
teria que repetir os atributos, o equipamento e o saque inteiros -- e as duas copias envelheceriam
separadas.

| O que | Como se junta |
|---|---|
| `category`, tamanho, alcance | o do filho, se declarado; senao o do pai |
| `fire_immune` | verdadeiro se qualquer um dos dois for |
| `summonable`, `saveable` | falso se qualquer um dos dois for: o pai restringir vale para os descendentes |
| `defaults` simples (vida, brilho, variante...) | o do filho, campo a campo |
| `defaults.attributes` e `defaults.equipment` | **por chave**: declarar so a velocidade nao apaga o dano do pai |
| `defaults.effects` | **somam**: sao coisas independentes |
| `loot.table` | a mais especifica vence |
| `loot.drops` | **somam**, pelo mesmo motivo dos efeitos |
| `spawn_egg` | **nao e herdado**: um ovo por descendente, todos com a mesma cor, seria indistinguivel na aba do criativo |
| `tags` | **nao sao herdadas**: o que o filho declara e o que vale |
| `texture` | **nao e herdada**, pelo mesmo motivo das tags |
| `model` | **nao e herdado**, pelo mesmo motivo |
| `spawn` | **nao e herdado** -- e a decisao mais conservadora possivel: uma variante de elite nao deveria, sem dizer nada, dobrar a populacao do mundo de quem instalou |

A `base` efetiva registrada e sempre a do ancestral do jogo: e dela que vem modelo e comportamento.
No exemplo, `elite_guardian` e registrado como derivado de `minecraft:iron_golem`, e nao de
`crystal_world:crystal_guardian`.

A ordem em que os mods sao descobertos **nao importa**: o loader ordena por heranca antes de
registrar. Uma heranca circular e recusada com o caminho na mensagem, e uma base desconhecida derruba
so aquela especie -- o mod ao lado continua valendo. Ver `examples/bestiario`.

Em tempo de execucao a leitura fica em `ctx.server.declared_entities()` e
`ctx.server.entity_definition(id)`, sob a permissao `entity.read`.

### Registrar por script

O caminho normal e declarar em `entities`: o adaptador le o manifesto e registra sozinho, no momento
que cada plataforma exige. Quem escreve o mod nao nomeia evento nenhum do Minecraft.

Quando o bestiario precisa ser **gerado** -- tres variantes de um guardiao saindo de um laco, em vez
de tres blocos de JSON quase iguais --, ha a fase de registro:

```json
{
  "permissions": ["entity.register"],
  "registration": { "on_register": "scripts/registrar.lua" }
}
```

```lua
return function(ctx)
    ctx.register.entity({
        id = "guardiao_ouro",
        name = "Guardiao de Ouro",
        base = "minecraft:iron_golem",
        defaults = { health = 160.0 },
    })
end
```

| O que | Regra |
|---|---|
| Quando roda | Antes de o jogo congelar os registros. **O adaptador decide o ponto exato** -- inicializacao do mod no Fabric, `RegisterEvent` no NeoForge. O script nunca nomeia um evento do jogo. |
| O que o `ctx` tem | `ctx.log`, `ctx.register.entity`, `ctx.register.declared`, `ctx.mod_id`. **Nao ha mundo**: nem servidor, nem jogador, nem bloco. Oferecer o resto seria oferecer chamadas que so podem falhar. |
| Arquivo proprio | Aponta um `.lua` seu, e nao uma funcao do `main.lua`. Carregar o entrypoint aqui faria o topo dele executar duas vezes -- um defeito que nao da erro, so estado errado. |
| Namespace | Carimbado pelo loader, com o id do mod. O script nao escolhe, senao um mod publicaria conteudo no nome de outro. |
| Permissao | `entity.register`, exigida no manifesto. Criar especie e mais forte que criar, ler ou modificar uma: acrescenta um tipo ao registro do jogo. |
| Orcamento | 5 s, mais folgado que os 20 ms de um callback: roda uma vez, com o jogo carregando, e montar um bestiario e trabalho legitimo. |
| Heranca | O que o script registra passa pela **mesma** resolucao do manifesto: uma especie gerada tambem pode ter `base` apontando outra declarada. |
| Remoto | O valor aceita URL, ou caminho relativo resolvido por `remote_base`, como o `behavior` de um bloco. Fixe a versao com `registration_sha256`; sem ele o script e buscado a cada carga. A carga registra em aviso qual endereco foi usado. |



### Forma propria

`model` aponta um JSON de ossos e caixas. A criatura ganha forma propria e **continua usando a
animacao e o comportamento da base** -- ela anda como um golem e parece outra coisa.

```json
{
  "texture_size": [128, 128],
  "bones": {
    "head": {
      "pivot": [0, -7, -2],
      "cubes": [ { "from": [-5, -14, -5], "size": [10, 10, 10], "uv": [0, 0] } ]
    },
    "body": {
      "pivot": [0, -7, 0],
      "cubes": [ { "from": [-5, -2, -3], "size": [10, 16, 6], "uv": [0, 40] } ]
    }
  }
}
```

**Os nomes dos ossos nao sao livres.** A classe de modelo da base procura os filhos por nome --
`head`, `body`, `right_arm` -- para gira-los a cada quadro. Um modelo que usa os mesmos nomes e
animado pela base sem que ela saiba que mudou; um nome fora da lista **nao da erro no jogo**: a peca
simplesmente nao aparece. O loader avisa na carga, dizendo qual osso e o que a base espera.

| Base | Ossos que ela anima |
|---|---|
| `minecraft:zombie`, `minecraft:skeleton` | `head`, `hat`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg` |
| `minecraft:iron_golem` | `head`, `body`, `right_arm`, `left_arm`, `right_leg`, `left_leg` |
| `minecraft:pig`, `minecraft:cow`, `minecraft:sheep`, `minecraft:creeper` | `head`, `body`, `right_hind_leg`, `left_hind_leg`, `right_front_leg`, `left_front_leg` |
| `minecraft:chicken` | `head`, `beak`, `red_thing`, `body`, `right_leg`, `left_leg`, `right_wing`, `left_wing` |
| `minecraft:wolf` | `head`, `body`, `upper_body`, `tail`, e as quatro patas |
| `minecraft:spider` | `head`, `body0`, `body1`, e as oito patas |

| Campo | Valor | Observacao |
|---|---|---|
| `texture_size` | dois inteiros | Padrao `[64, 64]`. Precisa bater com a textura declarada. |
| `bones` | objeto `nome -> osso` | Pelo menos um. Um osso sem caixa e recusado: ele nao apareceria. |
| `pivot` | tres numeros | O ponto em torno do qual a animacao gira. Sem declarar, a origem. |
| `cubes[].from`, `size` | tres numeros cada | Em pixels, a escala do formato do jogo. `size` negativo e recusado. |
| `cubes[].uv` | dois inteiros | Canto da textura de onde a caixa e recortada. |
| `cubes[].inflate` | numero | Cresce a caixa sem mover o pivo -- e como se faz uma camada externa. |
| `cubes[].mirror` | booleano | Espelha o recorte, para um braco reusar a arte do outro. Vale so para a caixa que pediu. |

A hierarquia e **plana**: todo osso e filho da raiz, porque e assim que as classes de modelo do jogo
procuram as pecas. Um osso preso a outro ainda nao e declaravel.

### Saque

Uma especie nova procura a tabela de saque com o **proprio** id, e nao a da base. Sem gerar nada,
um zumbi declarado morreria sem deixar carne podre, e isso pareceria decisao de quem escreveu o
mod. O loader entao gera a tabela no datapack virtual:

| Campo | Valor | Observacao |
|---|---|---|
| `loot.table` | id de tabela, com namespace | Substitui a da base. Sem declarar, a da base e herdada **por referencia**, e nao copiada -- uma mudanca no jogo continua valendo. |
| `loot.drops[].item` | id com namespace | Obrigatorio. |
| `loot.drops[].min`, `max` | inteiros | `max` menor que `min` e recusado. |
| `loot.drops[].chance` | maior que 0 ate 1 | Chance zero e recusada: e um drop declarado que nunca cai. |
| `loot.drops[].requires_player_kill` | booleano | So cai quando quem matou foi um jogador. |

Os `drops` **somam** ao que a tabela herdada ja produz. Substituir seria pior: declarar um unico
drop proprio apagaria em silencio tudo que a base derrubava, e a perda so apareceria matando o
bicho.

### Nascimento natural

```json
"spawn": {
  "biomes": ["#minecraft:is_mountain"],
  "weight": 8,
  "min_group": 1,
  "max_group": 2,
  "min_light": 0,
  "max_light": 7,
  "min_y": 60
}
```

| Campo | Valor | Observacao |
|---|---|---|
| `biomes` | ids ou tags (`#minecraft:is_forest`) | Obrigatorio. **Vazio nao significa "todos"**: significa que nada foi declarado, e a especie nao nasce em lugar nenhum. Uma tag alcanca um conjunto que cresce com o jogo e com os outros mods. |
| `weight` | inteiro > 0 | Peso do sorteio entre as candidatas do bioma. Peso zero nunca e sorteado, e por isso e recusado. |
| `min_group`, `max_group` | inteiros | Tamanho do grupo que nasce de uma vez. |
| `min_light`, `max_light` | 0 a 15 | Luz de **bloco**, nao o total: um lugar iluminado so pelo sol tem quinze ao meio-dia e continua escuro a noite. Faixa invertida e recusada -- ela nunca fecharia. |
| `min_y`, `max_y` | inteiros, opcionais | Ausente usa a faixa do mundo. |

**A categoria decide se isso funciona.** `misc` nao nasce sozinho no jogo -- e a categoria do barco e
do quadro, e o motor de spawn a substitui por porco. Como `minecraft:iron_golem` e `misc`, uma
especie derivada dele herda essa categoria: para ela nascer naturalmente e preciso declarar
`category` como `monster`, `creature` ou `ambient`. O loader recusa com essa mensagem em vez de
deixar a especie nunca aparecer.

Declarar peso alto **nao garante nada**: se a condicao de luz ou altura nao fechar, a criatura
simplesmente nao nasce. Para conferir uma posicao, `ctx.server.biome_at(x, y, z)` e
`ctx.server.light_at(x, y, z)` -- a segunda devolve `block`, `sky`, `total` e
`dark_enough_for_monster`.

### Ovo de criacao

| Campo | Valor | Observacao |
|---|---|---|
| `register` | booleano | Padrao verdadeiro. |
| `id` | id de item | Vazio usa `<id da especie>_spawn_egg`. |
| `name` | texto | Vazio usa `<nome da especie> Spawn Egg`. |
| `primary_color`, `secondary_color` | 0 a 16777215 (0xRRGGBB) | Cores do **ovo**, nao do bicho: o jogo nao olha a textura da criatura, e sem declarar sai cinza sobre cinza. |

O ovo entra na aba criativa do mod junto dos blocos e itens.

## Aba do inventario criativo

Sem `creative_tab`, o conteudo do mod nao aparece no inventario criativo e so pode ser obtido por comando. A aba recebe os blocos e itens do mod, na ordem em que foram declarados.

```json
{
  "creative_tab": {
    "register": true,
    "id": "main",
    "name": "Hello Lua",
    "icon": "hello_lua:ruby_block"
  }
}
```

O `icon` precisa do formato `mod:item`. Se o item indicado nao existir, o loader avisa e usa o primeiro conteudo declarado.

## Drops do bloco

O campo `loot` controla o que o bloco dropa. O loader gera a loot table dentro do data pack virtual.

| `mode` | Efeito |
|---|---|
| `self` | Dropa o proprio bloco. Padrao. |
| `item` | Dropa o item indicado em `loot.item`. |
| `none` | Nao dropa nada. |
| `table` | Usa a tabela externa indicada em `loot.table`; o loader nao a gera. |

O campo `count` multiplica a quantidade dropada.

## Tags

O campo `tags` insere o bloco em tags do jogo, tambem via data pack virtual, sempre com `"replace": false` para preservar o conteudo vanilla.

```json
{
  "tags": ["minecraft:mineable/pickaxe", "minecraft:needs_iron_tool"]
}
```

Declarar `settings.requires_tool` sem a tag de mineracao correspondente faz o bloco nao dropar nada ao ser minerado.

## Estados declarados

O campo `state.properties` cria propriedades reais de blockstate. Os tipos aceitos sao `bool`, `int` e `string`.

```json
{
  "state": {
    "properties": [
      {"name": "polished", "type": "bool", "values": ["false", "true"]}
    ],
    "default": {"polished": "false"}
  }
}
```

Alem dessas, o loader sempre adiciona `lua_variant` e `lua_luminance`, usadas pela API Lua.

## Inventário do bloco

Um bloco pode guardar itens na própria posição. É o que separa um bloco decorativo de uma máquina,
e o que torna possível baú customizado, mochila e tanque.

```json
"inventory": {
  "size": 27,
  "title": "Cofre de Cristal",
  "open_on_use": true,
  "allow_insert": true,
  "allow_extract": false,
  "drop_on_break": true
}
```

`size` vai de 1 a 54 e precisa ser múltiplo de 9 — a janela desenha fileiras de nove, e um slot
sobrando ficaria fora dela. `open_on_use` desligado deixa o clique para o script, que é o caso de
um bloco que cobra algo antes de abrir.

**`allow_insert` e `allow_extract` valem para quem acessa por um lado** — funil, tubo, a máquina
vizinha. Não valem para o mod pela API do loader. Com inserção permitida e extração negada o bloco
vira um cofre; uma fornalha que recusa saída automática ainda precisa tirar o próprio minério para
processá-lo, e esse é o único caminho que ela tem.

Um bloco com inventário guarda dados por posição automaticamente, como se tivesse `block_data`.

## Forma e modelo do bloco

A forma declarada vale para as três coisas ao mesmo tempo: colisão, contorno e desenho. Antes ela
só mudava a colisão, e o resultado era um bloco incoerente — uma laje com colisão de laje e
aparência de cubo inteiro, em que o jogador via um bloco cheio e atravessava a metade de cima.

```json
"shape": { "collision": "table", "outline": "table" }
```

Nomes aceitos: `full_cube`, `slab`, `slab_bottom`, `slab_top`, `carpet`, `layer`, `pane`, `panel`,
`post`, `pillar`, `plate`, `cross`, `plant`, `small`, `table`.

`visual` herda o contorno quando não é declarado: um bloco que diz ser uma mesa para se andar em
cima precisa parecer uma mesa, e repetir o nome só multiplicaria a chance de os dois divergirem.
Declare `visual` apenas quando ver e colidir devam diferir.

Para o que os nomes não cobrem, declare as caixas — cada uma `[x1, y1, z1, x2, y2, z2]` em unidades
de 0 a 16, e quantas forem precisas. Quando presentes, vencem o nome:

```json
"shape": { "boxes": [[4, 0, 4, 12, 10, 12], [2, 10, 2, 14, 14, 14]] }
```

### Modelo desenhado fora do loader

Um arquivo de modelo — como o que o **Blockbench** exporta — entra como recurso do tipo `model`. O
modelo nomeia as próprias texturas, e o bloco liga cada nome a um recurso:

```json
"resources": {
  "mesa":    { "type": "model", "from": "models/mesa.json" },
  "madeira": { "type": "image", "from": "assets/madeira.png" },
  "ferro":   { "type": "image", "from": "assets/ferro.png" }
},

"render": {
  "model": "@mesa",
  "textures": { "tampo": "@madeira", "pe": "@ferro" }
}
```

O loader copia o modelo, copia cada textura e reescreve os nomes para apontar para elas. O desenho
passa intacto — `uv`, rotação de elemento e tudo mais que o loader não interpreta —, que é o motivo
de desenhar numa ferramenta em vez de declarar caixas.

O mapeamento é declarado, e não deduzido pela ordem: é o que permite dois blocos compartilharem o
mesmo desenho com imagens diferentes, e dispensa o desenho conhecer o mod.

Um modelo declarado vence a geração por forma. As variantes visuais continuam existindo para o
caminho gerado; com modelo declarado, todas apontam para ele, porque o modelo já traz as suas
texturas.

## Campos aceitos mas ainda nao aplicados

O manifesto aceita campos que o loader ainda nao implementa. Eles nao impedem a carga do mod, mas sao listados no log durante a inicializacao, um a um, para que nenhuma declaracao passe despercebida.

Nesta versao, os campos avisados sao `type` diferente de `generic`, `base`, os nomes antigos em
`behavior`, todo o bloco `placement`, uma forma cujo nome o loader nao conhece e, em `render`,
`model` que nao seja um nome conhecido nem uma referencia, `render_layer`, `translucent`, `cutout`,
`emissive` e `tint`.

Os campos avisados valem para **as duas plataformas**: nao ha hoje campo do manifesto que o Fabric
aplique e o NeoForge ignore, nem o contrario. `docs/COMPATIBILIDADE.md` mantem a comparacao, e
continua sendo onde a proxima plataforma encontra a lista de trabalho.

## Estruturas declaradas

O campo `structures` descreve construcoes como dados, em vez de exigir um laco em Lua. Cada estrutura tem uma paleta de simbolos e as camadas do desenho.

```json
{
  "structures": [
    {
      "id": "crystal_tower",
      "name": "Torre de Cristal",
      "origin": "bottom_center",
      "palette": {
        "C": "crystal_world:crystal_block",
        "G": "minecraft:glass",
        "L": "minecraft:glowstone",
        ".": "minecraft:air",
        " ": null
      },
      "layers": [
        ["CCC", "CLC", "CCC"],
        ["CCC", "C.C", "CCC"],
        ["CCC", "CGC", "CCC"]
      ]
    }
  ]
}
```

Cada entrada de `layers` e uma camada em Y, da mais baixa para a mais alta. Dentro de uma camada, cada string avanca em Z e cada caractere avanca em X.

| Simbolo na paleta | Efeito |
|---|---|
| `"mod:bloco"` | Coloca o bloco indicado. |
| `null` | Nao toca na posicao, preservando o que ja existe no mundo. |

O campo `origin` define como a estrutura e ancorada: `bottom_center` centra o desenho no ponto pedido, `corner` usa o ponto como canto minimo.

Todo simbolo desenhado precisa existir na paleta. Um simbolo desconhecido impede a carga do mod, em vez de falhar so na hora de construir.

O posicionamento e feito por `ctx.server.place_structure(id, x, y, z)`, que exige `world.write` e devolve quantos blocos foram colocados. Um mod so alcanca as proprias estruturas. O limite de 32.768 blocos vale aqui tambem.

## Estrutura salva no jogo

Uma estrutura pode vir de um arquivo `.nbt` — o que o **bloco de estrutura** do Minecraft grava.
Isso permite construir dentro do jogo, salvar e distribuir junto do mod, em vez de transcrever a
construção para texto:

```json
"structures": [
  { "id": "torre", "name": "Torre de Vigia", "from": "structures/torre.nbt" }
]
```

O arquivo é lido na carga do mod e vira a mesma declaração que um manifesto escreveria à mão — com
paleta e camadas. Não é economia de código: é o que faz `origin`, o teto de volume e a validação de
símbolo valerem igual para os dois caminhos.

Aceita comprimido (como o jogo grava) ou cru (como algumas ferramentas gravam). O `name` e o
`origin` do manifesto vencem os do arquivo; sem `origin` declarado, vale `corner`, que é de onde o
bloco de estrutura grava.

O que o arquivo traz e o loader ainda não aplica: **propriedades de estado** (a orientação de um
tronco, a metade de uma laje) e **entidades**. O bloco é posicionado pelo identificador, no estado
padrão. Guardar o que não é aplicado faria a estrutura prometer uma orientação que ela não teria.

Ar capturado dentro da área fica transparente — preserva o que já existe no mundo, em vez de abrir
buracos em volta. Posições que o arquivo não lista também.

O limite é o da paleta em texto: 89 blocos diferentes por estrutura. Acima disso o mod é recusado
com a contagem na mensagem.

## Dividir o mod em varios arquivos

Um mod com muitos blocos, itens ou estruturas produz um `mod.json` grande demais para ler. Qualquer objeto do manifesto pode ser substituido por uma referencia `$import`:

```json
{
  "blocks": [{ "$import": "parts/blocks/crystal_block.json" }],
  "items": [{ "$import": "parts/items/crystal_shard.json" }],
  "structures": [{ "$import": "parts/structures/crystal_tower.json" }]
}
```

O objeto que contem `$import` e trocado pelo conteudo do arquivo apontado, que pode ser um objeto ou um array inteiro. Um arquivo importado tambem pode importar outros.

Regras do import:

| Regra | Motivo |
|---|---|
| O caminho e relativo a pasta do mod | O manifesto nao pode ler arquivos arbitrarios da maquina. |
| Caminhos absolutos, com `:` ou que escapem da raiz sao recusados | Mesma protecao aplicada ao entrypoint Lua. |
| `$import` nao pode dividir o objeto com outros campos | Evita ambiguidade sobre o que vence. |
| Cadeias circulares e acima de 16 niveis sao recusadas | Impede laco infinito na leitura. |
| Arquivo ausente e erro, nao silencio | Um pedaco faltando mudaria o mod sem aviso. |

Uma falha de import invalida apenas o mod afetado; os demais continuam carregando normalmente.

### Import por URL

Um pedaco do manifesto tambem pode vir da rede, para compartilhar conteudo entre mods ou distribuir partes separadamente:

```json
{
  "blocks": [
    {
      "$import": "https://exemplo.com/pacotes/blocos-comuns.json",
      "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    }
  ]
}
```

O campo `sha256` e **obrigatorio** em import remoto, e nao por formalidade. O manifesto define blocos, itens e as permissoes do mod. Sem fixar o conteudo, um servidor comprometido, ou apenas um arquivo editado depois, mudaria o comportamento do mod sem que ninguem percebesse. Com o hash, o autor declara exatamente qual conteudo aceita, e qualquer divergencia impede a carga.

| Regra do import remoto | Motivo |
|---|---|
| Somente `https` | Conteudo em texto puro pode ser trocado no caminho. |
| Redirecionamento precisa terminar em `https` | Um redirect anularia a regra anterior. |
| `sha256` obrigatorio e conferido antes do uso | O conteudo remoto e mutavel; o hash o torna fixo. |
| Maximo de 256 KB | Um pedaco de manifesto nao precisa ser maior que isso. |
| Timeout de 15 segundos | A carga do mod nao pode ficar presa em um servidor lento. |
| Desabilitado quando nao ha cache configurado | Validacao offline e testes nao devem acessar a rede. |

O conteudo baixado e guardado em cache indexado pelo proprio hash, entao o mesmo pedaco nao volta a rede em cargas seguintes. Como o nome do arquivo em cache e o hash verificado, o loader confia no cache local sem baixar de novo.

`sha256` e o unico campo aceito ao lado de `$import`. Qualquer outro e recusado, para nao haver duvida sobre o que vence entre o objeto local e o importado.

## Logica por bloco

O manifesto e o indice: cada bloco aponta onde mora a logica que responde a cada evento.

```json
{
  "blocks": [
    {
      "id": "ruby_block",
      "name": "Bloco de Rubi",
      "behavior": {
        "on_use": "scripts/ruby_block/on_use.lua",
        "on_broken": "scripts/ruby_block/on_broken.lua"
      }
    }
  ]
}
```

O valor de cada campo pode ser de tres formas:

| Forma | Exemplo | Significado |
|---|---|---|
| Arquivo | `scripts/x.lua` | Arquivo dentro da pasta do mod, que devolve uma funcao. |
| URL | `https://exemplo.com/x.lua` | Script remoto, sujeito as mesmas regras do import por URL. |
| Funcao | `on_ruby_used` | Nome de uma funcao devolvida pelo entrypoint. |

Um script de comportamento devolve a funcao que sera chamada:

```lua
-- Chamado apenas para este bloco: nao e preciso checar qual bloco recebeu o evento.
return function(ctx)
    ctx.log.info("clicaram em " .. ctx.block.id)
end
```

O `entrypoint` e opcional. Um mod pode ter apenas manifesto e scripts por bloco, sem `main.lua`.

A lista de eventos disponiveis, o cancelamento e o estado compartilhado estao no [catalogo de eventos](EVENTS.md).

## Mods como biblioteca

Um mod pode usar outro como biblioteca. Nao existe tipo especial: uma biblioteca e um mod que
exporta funcoes e normalmente nao declara conteudo proprio.

```json
{
  "id": "app_mod",
  "dependencies": { "ui_lib": "1.0.0" }
}
```

```lua
-- ui_lib/main.lua: o que o entrypoint devolve e a API publica do mod.
local function titulo(texto)
    return "[ " .. texto .. " ]"
end
return { titulo = titulo }
```

```lua
-- app_mod/main.lua
local ui = mod.require("ui_lib")
```

`mod.require` so alcanca mods declarados em `dependencies`. Isso mantem visivel no manifesto de quem
depende de quem, em vez de a dependencia aparecer escondida no meio do codigo.

### Ordem de carga

Uma dependencia declarada carrega antes de quem a consome. A ordem deixou de ser alfabetica por
diretorio justamente porque, com bibliotecas, carregar na ordem errada faria `mod.require` encontrar
nada, com uma falha dificil de diagnosticar.

| Situacao | Resultado |
|---|---|
| Dependencia ausente | O mod dependente nao carrega; os demais continuam. |
| Versao menor que a exigida | O mod dependente nao carrega. |
| Dependencia circular | Nenhum dos mods do ciclo carrega. |

A versao e comparada no formato `maior.menor.correcao`, e o valor declarado e a versao minima
aceita.

### Permissoes entre mods

Uma biblioteca roda com as **proprias** permissoes, nao com as de quem a chamou. Para isso ela usa
`mod.server`, criado com o manifesto dela:

```lua
-- Dentro da lib: funciona mesmo que o mod que chamou nao tenha chat.send.
local function anunciar(texto)
    mod.server.broadcast(texto)
end
```

| API | Permissoes verificadas |
|---|---|
| `mod.server` | Do mod onde o script esta escrito |
| `ctx.server` | Do mod que recebeu o evento |

A consequencia precisa ser conhecida por quem administra um servidor: instalar uma biblioteca da a
todos os mods que a declararem acesso indireto ao que ela faz. Por isso `mod.require` exige a
declaracao em `dependencies`, de modo que esse alcance fique registrado no manifesto e possa ser
auditado antes da instalacao.

## Instalar um mod publicado na web

O campo `remote_base` define um endereco base para os caminhos relativos do manifesto. Cada arquivo
e procurado primeiro no disco e, se nao existir, sob essa base. Isso permite publicar o mod inteiro
na web e instala-lo com um manifesto de poucas linhas:

```json
{
  "schema": 1,
  "id": "github_mod",
  "name": "GitHub Mod",
  "version": "1.0.0",
  "remote_base": "https://raw.githubusercontent.com/usuario/repo/main/examples/github_mod/",
  "permissions": ["chat.send", "world.write"],
  "blocks": [{ "$import": "parts/blocks/github_block.json" }],
  "items": [{ "$import": "parts/items/commit.json" }]
}
```

A base vale para tres coisas: os proprios `$import`, os scripts declarados em `behavior` e as
texturas com `source: local`. Sem ela, um pedaco importado por URL nao conseguiria referenciar os
proprios arquivos, porque os caminhos declarados nele apontam para a pasta do mod de origem.

| Regra | Motivo |
|---|---|
| O arquivo local tem prioridade | Permite sobrescrever um pedaco do mod publicado sem alterar a base. |
| Sem `remote_base`, arquivo ausente continua sendo erro | O comportamento local nao muda para quem nao usa a base. |
| Os caminhos herdam as regras de import remoto | Somente https, limite de tamanho e timeout continuam valendo. |

Como um caminho resolvido pela base nao declara `sha256`, ele e buscado a cada carga: publicar uma
versao nova atualiza o mod na proxima inicializacao. Para fixar uma versao, use `$import` com a URL
completa e o hash.

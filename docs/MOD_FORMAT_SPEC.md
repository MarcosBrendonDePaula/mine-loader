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

## 5. Texturas

Uma textura possui `source` igual a `local` ou `remote`. Para fonte local, `path` é relativo à raiz do mod. Para fonte remota, `url` precisa ser HTTPS. `sha256` é recomendado para conteúdo remoto e poderá ser obrigatório em servidores.

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

## Campos aceitos mas ainda nao aplicados

O manifesto aceita campos que o loader ainda nao implementa. Eles nao impedem a carga do mod, mas sao listados no log durante a inicializacao, um a um, para que nenhuma declaracao passe despercebida.

Nesta versao, os campos avisados sao `type` diferente de `generic`, `base`, todo o bloco `behavior`, todo o bloco `placement`, `shape` diferente de `full_cube` e, em `render`, `model` diferente de `cube_all`, `render_layer`, `translucent`, `cutout`, `emissive` e `tint`.

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

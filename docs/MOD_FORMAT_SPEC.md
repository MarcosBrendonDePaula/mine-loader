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

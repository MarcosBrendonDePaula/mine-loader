# Hotkeys declarativas

O MineLoader suporta hotkeys declarativas a partir do contrato `client.input.keybind` versão `1.0.0`. O mod declara a tecla no `mod.json` e associa o identificador a uma função Lua no servidor. O cliente não executa Lua: recebe dados, detecta a transição de tecla e envia apenas o id qualificado do binding ao servidor.

## Manifesto

A permissão `client.input.register` é obrigatória quando o mod declara keybinds. A declaração usa nomes portáveis no formato `key.keyboard.<nome>`; a primeira versão aceita teclado físico e os modificadores `ctrl`, `shift` e `alt`.

```json
{
  "permissions": ["client.input.register"],
  "keybinds": [
    {
      "id": "toggle_map",
      "key": "key.keyboard.m",
      "category": "map",
      "modifiers": []
    },
    {
      "id": "quick_action",
      "key": "key.keyboard.r",
      "category": "map",
      "modifiers": ["ctrl"]
    }
  ],
  "requires": {
    "domains": {
      "client": "1.0.0"
    },
    "capabilities": {
      "client.input.keybind": "1.0.0"
    }
  }
}
```

Os ids são locais ao mod e tornam-se `mod_id:id` no transporte. Dois mods podem usar o mesmo id curto sem colisão. O catálogo é publicado quando o jogador entra e é republicado quando um mod é instalado ou recarregado em runtime.

## Callback Lua

A função deve ser ligada com `mod.keybind`, e o id precisa existir na lista `keybinds` do manifesto. O callback recebe `ctx.keybind` além do contexto normal do jogador.

```lua
mod.keybind("toggle_map", function(ctx)
    if ctx.player == nil then
        return
    end

    ctx.player.send_message(
        "hotkey=" .. ctx.keybind.id .. " key=" .. ctx.keybind.key
    )
end)
```

O contexto da hotkey contém `id`, `key`, `category`, `action`, `mod` e `modifiers`. A ação actual é sempre `pressed`: o cliente envia uma vez por transição de solta para pressionada, não a cada tick enquanto a tecla permanece premida.

## Limites e segurança

O core limita o catálogo a 128 bindings e valida ids, categorias, teclas e modificadores antes de carregar o mod. O servidor aceita apenas ids que foram previamente registados pelo próprio mod, confere a versão do protocolo e executa o callback dentro do orçamento normal de Lua.

Hotkeys não são processadas quando o jogador está numa tela aberta, no chat, no inventário ou no menu de pausa. Isto evita que uma tecla usada para escrever ou interagir com uma tela também dispare uma acção global. O HUD continua sem capturar teclado; a hotkey é um canal separado de input client-side.

A implementação existe de forma equivalente em Fabric 1.21.1/1.21.4 e NeoForge 1.21.1/1.21.4. Os bridges absorvem as APIs de input de cada plataforma, enquanto o formato do manifesto, os ids, a versão e o contexto Lua permanecem comuns.

## Minimap

O `examples/minimap_demo` declara `key.keyboard.m` como `toggle`. Em jogo, `M` abre a UI declarativa de configuração do minimap. Nessa tela é possível alterar o zoom, mostrar/esconder coordenadas e ligar/desligar o HUD. Os comandos continuam disponíveis para diagnóstico e controlo explícito:

```text
/mod minimap_demo on
/mod minimap_demo off
/mod minimap_demo config
/mod minimap_demo zoom 2
```

Esta capability apenas fornece input. Ela não transforma o minimap em integração com Xaero, JourneyMap, Iris, Sodium ou outro mod externo, nem expõe chunks, shaders ou APIs internas de renderização.

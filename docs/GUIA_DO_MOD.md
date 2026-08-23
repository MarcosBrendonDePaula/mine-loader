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
| `world.read` | Ler blocos e dados, tocar som, emitir partículas |
| `world.write` | Alterar blocos, preencher, posicionar estrutura, gravar dados |
| `server.read` | Jogadores conectados, hora do dia, dimensão |
| `server.command.register` | Registrar comandos |
| `entity.read` / `entity.spawn` / `entity.modify` | Entidades |

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

A dependência carrega antes de quem a consome. Dentro de uma biblioteca, use `mod.server` em vez de
`ctx.server`: assim ela age com as próprias permissões, e não com as de quem a chamou.

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

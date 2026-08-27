# Schemas declarativos de comandos

O MineLoader suporta duas formas de registar comandos. A forma antiga continua a aceitar texto livre:

```lua
mod.command("exemplo", function(ctx)
    -- ctx.args, ctx.argv e ctx.subcommand continuam disponíveis.
end)
```

A forma estruturada declara a árvore do comando antes do callback. O bridge transforma essa árvore no dispatcher de comandos da plataforma, e o Minecraft passa a conseguir validar argumentos e sugerir literais.

## Capability e permissões

Um mod que usa schema deve manter a permissão normal de registo e exigir a capability versionada:

```json
{
  "permissions": ["server.command.register"],
  "requires": {
    "capabilities": {
      "server.command.schema": "1.0.0"
    }
  }
}
```

A capability é independente da versão do Minecraft. O core valida a versão do contrato e Fabric/NeoForge materializam a mesma árvore através dos seus respectivos bridges.

## Forma Lua

A assinatura estruturada é:

```lua
mod.command("nome", schema, callback)
```

O schema é uma lista de nós. Cada nó declara exactamente um `literal` ou um `argument` e pode possuir `children`:

```lua
mod.command("minimap_demo", {
    { literal = "on" },
    { literal = "off" },
    { literal = "config" },
    { literal = "zoom", children = {
        { argument = {
            name = "level",
            type = "integer",
            min = 1,
            max = 4,
            suggestions = { "1", "2", "3", "4" }
        }}
    }}
}, function(ctx)
    -- callback
end)
```

A árvore resultante é equivalente a:

```text
/mod minimap_demo on
/mod minimap_demo off
/mod minimap_demo config
/mod minimap_demo zoom <level: integer 1..4>
```

No Minecraft, `on`, `off`, `config` e `zoom` aparecem como literais sugeridos. Depois de `zoom`, os valores `1`, `2`, `3` e `4` aparecem como sugestões e o argumento também é recusado fora do intervalo declarado.

## Nós e argumentos

| Campo | Aplicação | Regras |
|---|---|---|
| `literal` | palavra fixa do comando | minúsculas, começa por letra, até 32 caracteres |
| `argument.name` | nome interno do argumento | mesmo formato de identificador |
| `argument.type` | parser portável | `word`, `string`, `greedy_string`, `integer`, `double`, `boolean` |
| `argument.min` | limite inferior | apenas argumentos `integer` ou `double` |
| `argument.max` | limite superior | apenas argumentos `integer` ou `double` |
| `argument.suggestions` | sugestões estáticas | até 32 textos, cada um com até 64 caracteres |
| `children` | próximos nós da árvore | até 8 níveis de profundidade |
| `executes` | permite executar naquele ponto | por omissão, é `true` quando o nó não tem filhos |

Os tipos são intencionalmente pequenos e portáveis. O core não expõe `ArgumentType`, `CommandSourceStack`, `ServerCommandSource` ou qualquer classe de Brigadier ao Lua.

## Contexto do callback

O callback mantém os campos antigos e recebe um campo `ctx.command` novo:

```lua
mod.command("teleport", {
    { literal = "to", children = {
        { argument = { name = "x", type = "integer" }, children = {
            { argument = { name = "z", type = "integer" } }
        }}
    }}
}, function(ctx)
    local x = ctx.command.arguments.x
    local z = ctx.command.arguments.z
    local caminho = ctx.command.path
end)
```

| Campo | Conteúdo |
|---|---|
| `ctx.args` | texto depois do nome do comando |
| `ctx.argv` | tokens do caminho, preservados para compatibilidade |
| `ctx.subcommand` | primeiro token, como antes |
| `ctx.command.name` | nome curto do comando |
| `ctx.command.structured` | `true` para schema, `false` para comando legado |
| `ctx.command.arguments` | tabela por nome, com números e booleanos convertidos |
| `ctx.command.path` | lista de tokens do caminho executado |

O cliente nunca escolhe o callback nem envia uma função. O servidor continua a executar o Lua e usa apenas os valores que o parser do bridge validou.

## Sugestões

Literais têm autocomplete automaticamente porque fazem parte da árvore. Argumentos recebem sugestões quando o schema declara uma lista estática:

```lua
{ argument = {
    name = "mode",
    type = "word",
    suggestions = { "safe", "fast", "silent" }
}}
```

A primeira versão não permite callback Lua para sugestões dinâmicas. Isto é deliberado: sugestões são solicitadas pelo cliente enquanto ele escreve, e executar Lua arbitrário nessa frequência poderia bloquear a thread do servidor. Uma futura capability pode fornecer fontes fechadas, como `registry:item` ou `loaded_mod`, com limites e cache próprios.

## Migração dos comandos antigos

Nada obriga um mod existente a migrar. O formato antigo continua a ser publicado com um argumento `greedy_string`, e `ctx.args`, `ctx.argv` e `ctx.subcommand` mantêm o comportamento anterior.

A migração recomendada é trocar:

```lua
mod.command("minimap_demo", function(ctx)
    local action = ctx.argv[1]
end)
```

por:

```lua
mod.command("minimap_demo", {
    { literal = "on" },
    { literal = "off" }
}, function(ctx)
    local action = ctx.argv[1]
end)
```

Para comandos migrados, o callback deve usar `ctx.command.arguments.<nome>` para argumentos tipados, em vez de converter manualmente posições de `ctx.argv`.

## Limites e segurança

O core recusa schemas vazios, nós nulos, nós que declaram `literal` e `argument` ao mesmo tempo, identificadores inválidos, tipos desconhecidos, limites invertidos, sugestões excessivas e árvores profundas. O limite actual é de 128 nós e 8 níveis.

O bridge constrói uma árvore real de Brigadier. O parser recusa números fora dos limites antes de chamar o Lua. Os callbacks continuam sujeitos ao orçamento normal de execução do runtime, e a permissão `server.command.register` continua obrigatória.

O schema não cria uma API de cliente. Ele apenas descreve a sintaxe que o servidor publica; o Lua continua server-side, e o mesmo mod usa o mesmo contrato em Fabric 1.21.1/1.21.4 e NeoForge 1.21.1/1.21.4.

## Estado actual

A capability está implementada no core e nos quatro bridges mantidos. O exemplo `minimap_demo` usa schema para `/mod minimap_demo on|off|config|zoom <1..4>`. Os comandos sem schema continuam a funcionar no formato livre.

GameTests de servidor validam carregamento dos manifestos e os testes do core validam parsing, argumentos nomeados, limites e compatibilidade legada. A sugestão visual do cliente deve ser confirmada num cliente Minecraft, pois GameTests não simulam a caixa de chat nem pixels da interface.

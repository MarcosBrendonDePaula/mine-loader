# Exemplos de requirements do manifesto

Estes exemplos não são mods carregados pela bateria de GameTests. São pacotes de referência para a
sintaxe de dependências baseadas no contrato do MineLoader.

## `requires.domains`

Um domínio agrupa uma área da API. O valor é a versão mínima do contrato do domínio, não a versão do
Minecraft nem a versão do loader. `domain_consumer` exige `world` e `player` na versão `1.0.0`.

```json
"requires": {
  "domains": {
    "world": "1.0.0",
    "player": "1.0.0"
  }
}
```

O requisito de domínio é uma dependência **conjuntiva**: todos os domínios declarados precisam ser
satisfeitos pelo runtime.

## `requires.capabilities`

Uma capability é uma operação menor dentro de um domínio. `capability_consumer` precisa apenas de
leitura de estado de bloco, leitura de redstone e leitura de mira do jogador. Um mod com hotkey pode
exigir `client.input.keybind` sem pedir o domínio inteiro de UI.

```json
"requires": {
  "capabilities": {
    "world.block_state.read": "1.0.0",
    "world.redstone.read": "1.0.0",
    "world.explode": "1.0.0",
    "world.lightning": "1.0.0",
    "player.looking_at.read": "1.0.0",
    "player.equipment.read": "1.0.0",
    "player.inventory.slot": "1.0.0",
    "client.input.keybind": "1.0.0"
  }
}
```

Capabilities também são conjuntivas. A lista deve ser pequena e representar aquilo que o mod realmente
usa; declarar o domínio inteiro quando só uma operação é necessária reduz a precisão do diagnóstico.

## `dependencies` continua a ser outra coisa

`dependencies` declara uma relação entre **mods**. Ela controla ordem de carga, versão do mod e acesso
a `mod.require`. `requires` declara o contrato do **runtime** e não carrega código.

```json
{
  "dependencies": {
    "library_provider": "2.0.0"
  },
  "requires": {
    "domains": {
      "world": "1.0.0"
    },
    "capabilities": {
      "world.block_state.read": "1.0.0"
    }
  }
}
```

`full_consumer` usa os dois mecanismos: importa código de `library_provider` e, separadamente, exige
que o runtime ofereça a leitura de estado de bloco.

## Regras de compatibilidade

O loader valida `requires` antes de registrar conteúdo ou executar Lua. Um domínio ou capability
inexistente, uma versão mínima acima da entregue, um identificador malformado ou uma versão que não seja
`maior.menor.correcao` faz o mod ser recusado com uma mensagem que identifica o requisito.

Uma alteração compatível que apenas adiciona funções preserva a versão maior do domínio. Uma mudança
incompatível no formato, semântica ou permissões exige uma nova versão maior do domínio ou capability.
As versões usadas aqui são do contrato do MineLoader; `fabric/1.21.4` e `neoforge/1.21.4` podem
satisfazer a mesma versão de domínio.

## Catálogo actual

O perfil comum actual entrega todos estes domínios na versão `1.0.0`: `core`, `world`, `player`,
`entity`, `inventory`, `registry`, `events`, `scheduler`, `ui`, `client` e `resources`.
 O catálogo detalhado de capabilities está em `docs/API_ESTAVEL.md` e no `RuntimeContract` do core.
A nova leva acrescenta `world.explode`, `world.lightning`, `player.equipment.read`,
`player.inventory.slot` e `events.block.break`; as duas últimas APIs de jogador continuam protegidas
por `player.read` ou `player.inventory` além da capability declarada.

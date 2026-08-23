# Mine Loader — guia para trabalhar neste repositório

Modloader declarativo para Minecraft Java 1.21.1 (Fabric). Mods são pastas com `mod.json` mais
lógica em Lua (LuaJ); o loader registra blocos e itens, monta um resource pack virtual e executa os
scripts. Nenhum mod escreve Java.

## Comandos

```bash
./gradlew build          # compila os três lados e roda os testes
./gradlew :core:test     # só os testes do núcleo — rápidos, sem Minecraft
./gradlew runServer      # servidor de desenvolvimento; lê mods de run/mods-lua
./gradlew runClient      # cliente, para ver tela, HUD e sobreposição
./gradlew runGametest    # servidor headless com os @GameTest
```

Java 21. No Windows use `./gradlew.bat`. O CI roda `build` mais os GameTests em todo push.

## Estrutura

| Onde | O quê | Conhece Minecraft |
|---|---|---|
| `core/` | Núcleo: manifesto, runtime Lua, protocolo de UI, contratos de plataforma | **não** |
| `src/main/` | Adaptador Fabric: registro de conteúdo, rede, bridge | sim |
| `src/client/` | Cliente: desenha telas, HUD e sobreposições | sim |
| `examples/` | Mods de exemplo, em Lua | — |
| `docs/` | Especificações; veja o índice no README | — |

**A regra que sustenta o resto:** o núcleo nunca importa classe do Minecraft nem do Fabric. Tudo
passa por `GameBridge` e `PlayerHandle`, em `core/.../platform/`. Acrescentar uma operação ao Lua
significa: método no contrato → implementação no adaptador → função no `LuaRuntime` → dublê em
`TestBridge`/`TestPlayer`. Esquecer o último quebra a compilação dos testes, o que é o objetivo.

## Convenções

**Identificadores em inglês, comentários e Javadoc em português.** A API pública já era inglês
(`openScreen`, `setHud`); misturar `lista` e `indice` no meio disso é o pior dos dois mundos. Os
scripts em `examples/` são a exceção: variáveis em português ali, porque são documentação.

**Comentários explicam por quê, não o quê.** O padrão do repositório é registrar a decisão e o que
aconteceria sem ela — leia qualquer arquivo antes de escrever um comentário novo. Comentário que
narra a linha seguinte não combina com o resto.

**Toda permissão protege uma operação real.** Ao criar uma operação Lua, chame `requirePermission`
com uma permissão já existente, ou explique por que precisa de uma nova. As permissões estão
listadas em `docs/API_GAPS.md`.

## Regras que não são óbvias

**O cliente interpreta dados, nunca código.** O servidor envia uma descrição JSON de tela; o cliente
tem um renderizador único. Nunca introduza um caminho em que o servidor mande código para o cliente
— é a decisão central da camada de UI, explicada em `docs/UI_SPEC.md`.

**Vocabulário fechado nos dois sentidos.** Ações (`click`, `change`, `submit`, `close`), tipos de
elemento e alvos de sobreposição são conjuntos em `ScreenProtocol`. O cliente não inventa ações; o
mod não nomeia classes do cliente. Acrescentar um valor é editar o núcleo, não o adaptador.

**A validação mora no núcleo.** Um erro de descrição precisa virar mensagem para quem escreveu o
mod, testável sem cliente. O cliente, do outro lado, ignora o que não entende em vez de falhar.

**Todo script Lua roda com orçamento.** 20 ms por callback, verificado a cada 2.048 instruções. Não
introduza operação que bloqueie — rede, disco síncrono — dentro de um callback.

**O sandbox nega `io`, `os`, `package`, `debug`, `require`, `load` e afins.** `string`, `table` e
`math` estão disponíveis.

## Armadilhas ao escrever ou revisar um mod de exemplo

- `ctx.player.name` e `ctx.player.uuid` são **valores**, não funções. `ctx.player.uuid()` falha.
- O mapeamento `"events"` do `mod.json` só enxerga o que o script **retorna**:
  termine com `return { on_player_joined = on_player_joined }`. Uma função global não basta.
- `ctx.state` é por mod, não por jogador. Para estado por jogador, chaveie por `ctx.player.uuid`.
- Um erro de Lua dentro de um callback é **logado, não propagado**: um teste que só verifica o
  efeito passa a falhar em silêncio. Verifique o efeito, como faz `catalogExampleRunsEndToEnd`.

## Testes

`core/src/test/` roda sem Minecraft, com `TestBridge` e `TestPlayer` no lugar da plataforma — é onde
quase toda lógica é verificável. `src/main/java/dev/lualoader/gametest/` sobe um servidor de verdade
para o que depende do jogo.

O teste `ScreenTest.catalogExampleRunsEndToEnd` carrega `examples/catalogo` do repositório e percorre
o fluxo inteiro. Ao mudar a API de UI, ele é o que avisa que os exemplos pararam de funcionar.

# Mine Loader — guia para trabalhar neste repositório

Modloader declarativo para Minecraft Java, sobre Fabric e NeoForge. Mods são pastas com `mod.json`
mais lógica em Lua (LuaJ); o loader registra blocos e itens, monta um resource pack virtual e executa
os scripts. Nenhum mod escreve Java, e o mesmo mod roda nos quatro runtimes desta branch — Fabric e
NeoForge para 1.21.1 e 1.21.4. `docs/COMPATIBILIDADE.md` é onde essa promessa é conferida.

## Comandos

```bash
./gradlew compileAllRuntimes       # compila core + os quatro bridges
./gradlew :core:test                  # só os testes do núcleo — rápidos, sem Minecraft
./gradlew testAllRuntimes             # testes unitários dos módulos
./gradlew gameTestAllRuntimes         # 18 GameTests em cada runtime
./gradlew checkAllRuntimes            # verificação completa
```

**Os GameTests rodam nos quatro runtimes, e é de propósito.** Enquanto rodavam só no Fabric, seis
divergências entre os adaptadores se acumularam sem quebrar nada: eventos globais que nunca
disparavam, receitas que não chegavam ao servidor, ferramentas declaradas que viravam item comum.
Ao acrescentar um recurso, verifique em cada plataforma e versão.

```bash
./gradlew :runtimes:fabric:1.21.1:runServer
./gradlew :runtimes:fabric:1.21.4:runServer
./gradlew :runtimes:neoforge:1.21.1:runServer
./gradlew :runtimes:neoforge:1.21.4:runServer

./gradlew :runtimes:fabric:1.21.1:runClient
./gradlew :runtimes:fabric:1.21.4:runClient
./gradlew :runtimes:neoforge:1.21.1:runClient
./gradlew :runtimes:neoforge:1.21.4:runClient
```

Acrescente `-Pmundo="New World"` a qualquer `runClient` para entrar direto no mundo, pulando o menu.



Java 21. No Windows use `./gradlew.bat`. O CI roda compilação, testes unitários e GameTests nos quatro runtimes em todo push.

## Estrutura

| Onde | O quê | Conhece Minecraft |
|---|---|---|
| `core/` | Núcleo: manifesto, runtime Lua, protocolo de UI, contratos de plataforma | **não** |
| `runtimes/fabric/1.21.1/` | Bridge Fabric da baseline 1.21.1 | sim |
| `runtimes/fabric/1.21.4/` | Bridge Fabric experimental 1.21.4, incluindo client | sim |
| `runtimes/neoforge/1.21.1/` | Bridge NeoForge da baseline 1.21.1 | sim |
| `runtimes/neoforge/1.21.4/` | Bridge NeoForge experimental 1.21.4 | sim |
| `examples/` | Mods de exemplo, em Lua | — |
| `tools/` | Servidor dirigível e utilitários de verificação | — |
| `docs/` | Especificações; veja o índice no README | — |

**A regra que sustenta o resto:** o núcleo nunca importa classe do Minecraft, do Fabric nem do
NeoForge. Tudo passa por `GameBridge` e `PlayerHandle`, em `core/.../platform/`. Acrescentar uma
operação ao Lua significa: método no contrato → implementação em **cada** adaptador → função no
`LuaRuntime` → dublê em `TestBridge`/`TestPlayer`. Esquecer o último quebra a compilação dos
testes, o que é o objetivo.

**Quando algo é agnóstico, ele pertence ao núcleo — mesmo que hoje só um lado use.** O
`ScreenModel` e a geometria de ancoragem viveram no cliente Fabric por acidente histórico, e portar
a interface para o NeoForge quase custou trezentas linhas de aritmética duplicada. Agora as duas
plataformas concordam sobre onde cada elemento fica e discordam só sobre como pintá-lo, e um erro
de alinhamento virou teste no núcleo em vez de dois bugs independentes.

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

**Recurso que entra em uma plataforma entra em `docs/COMPATIBILIDADE.md` na mesma mudança**, mesmo
que só funcione em uma — principalmente nesse caso. A lista é o que transforma "o Fabric faz e o
NeoForge não" de surpresa para quem escreve o mod em tarefa conhecida, e é de onde sai o roteiro de
trabalho quando uma plataforma nova entra. Uma matriz que envelhece em silêncio é pior que nenhuma,
porque alguém confia nela.

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

- `ctx.player.name` e `ctx.player.uuid` são **valores**; `position()`, `health()` e `held_item()` são
  **funções**. A distinção não é óbvia e já custou tempo duas vezes: `uuid()` e `position` falham,
  cada um pelo motivo oposto. Confira em `LuaRuntime.playerApiFor` antes de usar.
- O mapeamento `"events"` do `mod.json` só enxerga o que o script **retorna**:
  termine com `return { on_player_joined = on_player_joined }`. Uma função global não basta.
- `ctx.state` é por mod, não por jogador. Para estado por jogador, chaveie por `ctx.player.uuid`.
- Um erro de Lua dentro de um callback é **logado, não propagado**: um teste que só verifica o
  efeito passa a falhar em silêncio. Verifique o efeito, como faz `catalogExampleRunsEndToEnd`.

## Testes

`core/src/test/` roda sem Minecraft, com `TestBridge` e `TestPlayer` no lugar da plataforma — é onde
quase toda lógica é verificável. `TestBridge` é abstrata de propósito: ela obriga cada teste a dizer
o que precisa, para um contrato novo não passar despercebido por um dublê que responde a tudo.
Os GameTests vivem em `runtimes/*/*/src/main/java/**/gametest/` e sobem um servidor de verdade para o que depende do jogo.

Alguns testes carregam mods de `examples/` em tempo de execução — `catalogExampleRunsEndToEnd` e os
outros que citam `Path.of("..", "examples", ...)`. Eles percorrem o fluxo inteiro de um mod real, e
são o que avisa quando uma mudança de API quebra os exemplos.

**Ao editar um `.lua` de `examples/`, rode `./gradlew :core:test --rerun-tasks`.** O Gradle não
enxerga esses arquivos como entrada da tarefa de teste, então um `./gradlew test` comum reporta
sucesso sem ter executado nada — o pior resultado possível, porque parece verificação.

### Os quatro níveis, do mais barato ao único que vê pixel

| Nível | Alcança | Custo |
|---|---|---|
| `./gradlew :core:test` | manifesto, validação, geometria de tela, runtime Lua | segundos |
| `./gradlew gameTestAllRuntimes` | registro, entidade de bloco, NBT — num servidor de verdade, **nas quatro combinações** | variável; NeoForge baixa assets na primeira execução |
| `/mod autoteste` no servidor | as APIs contra o jogo real: registro com milhares de itens, loot de datapack, inventário de outro mod | minuto |
| `runClient` | **só aqui se vê se um pixel está no lugar** | manual |

A distância entre os dois primeiros e os dois últimos é onde os defeitos moram. Vários desta
sessão só apareceram no jogo, e um deles com o log dizendo "pack montado, zero modelos faltando"
enquanto a tela mostrava cubos roxos. **Log verde não é verificação visual.**

### Verificar sem estar no jogo

`tools/servidor-dirigivel.sh` sobe um servidor cuja entrada de console vem de um arquivo, então dá
para mandar comandos e ler o resultado sem ninguém no jogo:

```bash
tools/servidor-dirigivel.sh iniciar          # sobe (PLATAFORMA=neoforge para o outro)
tools/servidor-dirigivel.sh esperar          # bloqueia até aceitar comandos
tools/servidor-dirigivel.sh cmd "mod autoteste"
tools/servidor-dirigivel.sh log 40           # últimas linhas
tools/servidor-dirigivel.sh parar
```

O log fica em `build/servidor-<plataforma>.log`. Se o `grep` reclamar de arquivo binário, passe por
`strings` antes.

**Três armadilhas que já custaram conclusões erradas aqui:**

- **O servidor pode se dizer pronto antes do mundo existir.** No NeoForge os comandos dos mods são
  publicados antes disso, e um comando enviado ali falha com `serverlevel is null` — que não se
  parece com "cedo demais". O script já espera pelos dois sinais; se escrever verificação nova,
  não presuma.
- **Sem jogador, só o spawn tica.** Um bloco longe do spawn aceita `schedule_block` — a chamada
  responde certo — e o tique nunca chega, porque o servidor só processa a fila de chunks que estão
  tiquando. O sintoma é uma lógica que "não roda" com tudo aparentemente configurado, e custou uma
  investigação inteira. Monte a verificação perto de 0,0; ler bloco distante também paga
  carregamento de chunk, caro o bastante para estourar o orçamento de 20 ms.
- **O mundo do servidor sobrevive ao reinício, e um teste que falha no meio deixa lixo nele.** Um
  caso que estoura o orçamento antes de desmontar deixa os blocos onde estavam, e a rodada seguinte
  os encontra. O sintoma não se parece com a causa: uma rede de cinco canos foi vista com vinte e
  seis nós, e a falha apareceu num caso que não tinha nada a ver com o que quebrou. Ao investigar
  contagem estranha de rede, **limpe a área antes de acreditar no número**.
- **`fill` do console falha sem jogador, e a mensagem não diz isso.** Ela é `That position is not
  loaded` — o chunk não está carregado porque não há ninguém por perto. `forceload add <x1> <z1>
  <x2> <z2>` antes do `fill` resolve, e é como se limpa a área de testes entre rodadas:

  ```bash
  tools/servidor-dirigivel.sh cmd "forceload add -2 -2 110 12"
  tools/servidor-dirigivel.sh cmd "fill -2 99 -2 110 101 12 air"
  ```
- **`ln -s` no Git Bash do Windows cria uma cópia, não um link.** Apontar `run/mods-lua/<mod>` para
  uma pasta fora do repositório assim produz um retrato congelado: o servidor carrega a versão do
  momento do comando, e toda edição posterior é invisível. O sintoma é o mesmo de "o servidor não
  recarregou" -- o teste que você acabou de escrever não aparece na lista --, mas a correção é
  outra. Use um junction:

  ```powershell
  New-Item -ItemType Junction -Path "<repo>/run/mods-lua/<mod>" -Target "<origem>"
  ```
- **Ao matar servidor órfão por PID, não mate o cliente junto.** Um comando que mata todo `java.exe`
  que não seja o `GradleDaemon` derruba o jogo que estiver aberto. Pare o servidor pelo script, e
  confira a linha de comando do processo antes de matar por PID.
- **O seletor `@e` do console não enxerga entidades em todo servidor.** Para contar o que caiu no
  chão, use `entities_near` pela API do loader — é o caminho que um mod usaria, e responde.
- **Um servidor órfão segura a porta e o mundo.** `parar` antes de `iniciar`, sempre. O script
  limpa órfãos da mesma plataforma, mas não toca no cliente que você deixou aberto.
- **`parar` nem sempre mata.** Aconteceu várias vezes numa sessão: o comando volta, o processo
  continua vivo, e o próximo `iniciar` falha com `outro processo bloqueou parte do arquivo` — que
  não se parece com "o servidor anterior ainda está aí". Pior: o `cmd` seguinte vai para o servidor
  **velho**, que responde normalmente. Depois de `parar`, confira que morreu; no Windows,
  `Get-CimInstance Win32_Process -Filter "Name='java.exe'"` e mate por PID o que não for
  `GradleDaemon`.
- **Não rode o Gradle com o cliente aberto.** Uma compilação reescreve os `.class` embaixo dele, e o
  jogo quebra quando for carregar uma classe que ainda não tinha carregado — `NoClassDefFoundError`
  numa classe que existe e está compilada. A leitura óbvia do relatório é "o código está quebrado",
  e não está. Feche o cliente antes de compilar.
- **O mod é carregado no arranque, e editar o `.lua` depois não recarrega nada.** O comando roda a
  versão anterior e responde com a maior naturalidade. Um teste que você acabou de escrever
  simplesmente não aparece na lista, ou aparece falhando por um motivo que você já corrigiu.
  **Reinicie o servidor a cada mudança de script.**

### Escrever verificação nova

Prefira acrescentar um caso a `examples/autoteste/main.lua` a criar um mod de teste avulso: ele já
roda nas duas plataformas com um comando, e é o que pega divergência entre elas. Foi assim que se
descobriu que `extract_from` respeitava `allow_extract` no NeoForge e não no Fabric — as duas
plataformas fazendo coisas diferentes com o mesmo manifesto.

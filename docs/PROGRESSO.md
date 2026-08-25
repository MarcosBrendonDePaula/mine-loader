# Progresso — o que foi feito e o que falta

Acompanhamento de sessão longa. Diferente de `CHECKLIST_MODLOADER.md`, que é a régua externa do que
um modloader precisa ter, aqui fica **o estado do trabalho em curso**: o que fechou, o que está no
meio e o que vem a seguir.

**Regra:** ao fechar um item, risque-o na mesma mudança que o implementa. Um acompanhamento que
envelhece em silêncio é pior que nenhum — é a mesma razão de `COMPATIBILIDADE.md` existir.

Última revisão: quatro limites removidos — `events` sem `entrypoint`, `placement.facing`, a forma
que varia com o estado e o tique agendado por posição. Os quatro saíram da migração do Logistic
Pipes. Junto veio um conserto de ferramenta que valia mais do que parece: `run/mods-lua` era uma
cópia de `examples/`, e o servidor rodava contra scripts velhos dizendo que passou.

---

## Feito

### Espécies declaradas — o bestiário

- [x] **Espécie no manifesto** (`entities`), derivada de uma base do jogo. Base obrigatória e
      recusada quando desconhecida, com a lista das suportadas no erro.
- [x] **Registro nas duas plataformas**, com atributos, ovo de criação e desenhista.
- [x] **Saque próprio**, herdando a tabela da base **por referência** — copiar congelaria os drops
      na versão em que o mod foi escrito.
- [x] **Herança entre espécies**, inclusive de outro mod. Ordenação e detecção de ciclo no núcleo.
- [x] **Fase de registro** (`registration`): script próprio que roda antes de o jogo congelar os
      registros. Fecha a divergência de que o Lua carrega em momentos diferentes em cada plataforma.
- [x] **Tags de espécie**, geradas em `tags/entity_type`.
- [x] **Textura própria** (`texture`) e **forma própria** (`model`) — ossos e caixas em JSON,
      animados pela base.
- [x] **Comportamento declarado** (`ai`): vocabulário fechado de metas e alvos.
- [x] **Nascimento natural** (`spawn`) por bioma, peso, grupo, luz e altura.
- [x] Leitura de **bioma e luz** (`biome_at`, `light_at`).
- [x] Quatro **eventos de criatura** e **mover/empurrar** entidade.

### Limites removidos

- [x] **Inventário por slot** — `container_at` numera cada linha, e `insert_into` e `extract_from`
      aceitam o índice. É o que destrava o filtro por slot dos módulos de chassi, e a máquina com
      entrada e saída separadas.

- [x] **Nucleo e braco com varias caixas** (`shape.cores`, `shape.arms`). Uma caixa por peca cobre
      cano, cerca e muro, e nao cobre o cano do Logistic Pipes -- que tem placas nas faces alem do
      miolo. As caixas de um braco giram juntas, como uma peca so. Junto, a condicao que decide o
      que e um bloco que conecta saiu de tres copias para uma, no nucleo.

- [x] **Pecas de malha por condicao, com recorte e ajuste fino** (`obj_parts` com lista,
      `keep_within`, `expand`, `double_sided`, textura por peca). Um OBJ de mod e um catalogo de
      pecas, e nem todo nome serve para separa-las: as placas da face de um cano se chamam igual nos
      seis lados, e so a regiao as distingue.

- [x] **`tools/inspecionar-modelo.sh`** -- diz o que o pacote manda desenhar sem abrir o jogo, na
      mesma ordem que o cliente. Escrita depois de tres rodadas diagnosticando por captura de tela.

- [x] **Modelo `.obj` de bloco, nas duas plataformas.** O formato do jogo descreve caixas, e uma
      malha nao e uniao de caixas -- sem isso, portar um mod que desenha assim esbarra no desenho.
      O leitor mora no nucleo justamente para o mesmo arquivo virar o mesmo desenho nos dois lados;
      o NeoForge tem leitor proprio e nao e ele que roda.

- [x] **`obj_parts`: recortar o catalogo por conexao.** Um OBJ de mod costuma trazer o miolo, a
      manga de cada lado e as placas no mesmo arquivo. O mod declara o que desenhar em cada estado,
      e a textura e **por peca** -- o corpo le o atlas proprio da malha, e a imagem que identifica o
      bloco aparece so onde o mod mandar.

- [x] **Pastas de mod fora do jogo** (`MINE_LOADER_MODS`, ou `-Pmods=`). O loader carrega direto da
      pasta apontada, sem copiar. Copiar era o que se fazia, e a copia envelhecia: o servidor rodava
      contra um script velho dizendo que passou.

- [x] **`entrypoint` vindo da base remota.** Era o unico pedaco de um mod que ainda exigia arquivo
      local -- modulo, comportamento, textura e `$import` ja buscavam. Um mod publicado agora pode
      ser instalado com um `mod.json` de poucas linhas.

- [x] **Bloco que conecta e guarda dados ao mesmo tempo.** Eram exclusivos no registrador das duas
      plataformas: um cano com `block_data` perdia a conexão inteira, em silêncio. Descoberto ao
      fazer o item viajar — a carga precisa morar na posição do cano.

- [x] **Tique agendado por posição** — `ctx.server.schedule_block(x, y, z, tiques)` e o evento
      `block_scheduled`, mapeado por `behavior.on_scheduled`. A fila é a **do jogo**, gravada com o
      chunk: o que estava a caminho volta na próxima sessão, em vez de sumir com o servidor. Não se
      repete sozinho — continuar é o script agendar o próximo —, e é recusado em bloco do jogo, onde
      o tique iria para o método vanilla e nada chegaria ao script.

- [x] **Uma pasta de mods só, de ponta a ponta.** `neoforge/run/mods-lua` já apontava para
      `run/mods-lua`, mas essa pasta compartilhada era uma **cópia** de `examples/`. Resultado: a
      bateria ficava verde contra um script velho, e o log dizia que passou — o pior resultado
      possível. A tarefa `linkExemplos` agora liga cada exemplo, e roda antes de `runServer` e
      `runClient`.

- [x] **Forma do bloco variando com o estado** — o cano que conecta. `shape.core`, `shape.arm` e
      `shape.connects_to`; o adaptador registra seis propriedades booleanas, calcula ao colocar e a
      cada mudança de vizinhança, e o pacote gerado escreve um blockstate `multipart` — sete peças,
      não sessenta e quatro variantes. **A colisão acompanha o desenho.**

- [x] **`events` sem `entrypoint` era aceito em silêncio.** Agora é recusado na carga, dizendo que
      o mapeamento aponta para funções de um script que não existe. Custou tempo real nesta sessão:
      o mod carregava, não fazia nada, e nenhuma linha de log explicava.
- [x] **`placement.facing` era declarado e ignorado.** Agora vale nas duas plataformas, com uma
      variante de blockstate por direção e o mesmo modelo girado.

### Interface

- [x] **Tela de mods no menu principal**: lista com filtro e páginas, ligar/desligar, instalar por
      link. Primeira tela do loader sem servidor do outro lado.
- [x] **Catálogo de mods** (`ModLoader.catalog`), que enxerga o desativado e o quebrado — coisa que
      `discover` não faz, e nem deve.
- [x] **Ícone do mod** (`icon`).

### Migração

- [x] **Logistic Pipes**: primeiro mod migrado. Cano, provedor e terminal, com o ciclo completo de
      pedido e entrega conferido no servidor dirigível.
- [x] **O item viaja pelo cano**, um passo a cada quatro tiques, com a carga guardada na posição.
      Era a maior diferença visível para o original. Seis casos no núcleo prendem o comportamento,
      inclusive os dois que mais importam: cano quebrado no meio e baú de destino cheio **não podem
      apagar item do mundo**.
- [x] Porte autônomo publicado em
      [`logistic-pipes-lua`](https://github.com/MarcosBrendonDePaula/logistic-pipes-lua), sob MMPL
      por reusar a arte do original.

---

## Em andamento

**Nada em aberto no código.** O tique agendado fechou e está verificado nos quatro níveis: 6 casos
no núcleo, um GameTest em **cada** plataforma que confere a fila do jogo e a recusa em bloco
vanilla, e `tique_agendado` na bateria — **33/33 nas duas**.

### O render de malha do Fabric esta com defeito

**O NeoForge desenha certo; o Fabric nao.** Mesmo pacote, mesmo leitor no nucleo, mesmos numeros de
face conferidos pela ferramenta de inspecao -- e o resultado na tela e diferente. Isso descarta o
modelo, o manifesto e o parser: o defeito esta na conversao para quads do lado Fabric, que passa
pelo Indigo enquanto o NeoForge usa o caminho vanilla.

O que ja foi tentado e nao resolveu sozinho:

- **`double_sided`**, que o mod original faz em cada peca (`backfacedCopy`). Melhorou o corpo.
- **`keep_within`**, que fechou a face livre -- as placas do original nao tem lado no nome.
- **`expand`**, contra duas superficies brigando pelo mesmo pixel.
- **AO desligado nos dois**, porque o calculo assume a face encostada na parede do cubo.

Uma ja foi achada assim: `hasDepth()` (Yarn) e o `isGui3d()` (Mojang) do NeoForge, e estava
`false` de um lado e `true` do outro -- o item virava figura chapada na mao e na barra de acesso
rapido no Fabric, e objeto no NeoForge. **Metodos com nomes diferentes que significam a mesma coisa
sao onde as duas plataformas divergem sem ninguem perceber.**

Onde procurar a seguir, em ordem: o formato do vertice que montamos a mao (posicao, cor, uv, luz e
normal em `int[32]`), a normal empacotada, e o `shade` do quad. O Indigo le esses campos com mais
rigor que o caminho vanilla, e um valor que o vanilla ignora pode virar defeito la.

**Nenhum teste pega isso.** Passaram 18/18 GameTests em cada plataforma, a suite do nucleo e a
ferramenta de inspecao -- todos verdes, porque nenhum deles ve pixel. Quem apontou foi quem estava
jogando.

**Sobre a malha, o que falta e de olho:** os dois clientes montam as 65 pecas com numeros
identicos (164 no miolo, 40 na manga, 2 na placa), mas **numeros iguais nao sao telas iguais** --
comparar Fabric e NeoForge lado a lado ainda nao foi feito. E o item na mao continua sendo um cubo:
o modelo de item nao pode herdar da malha, senao o cliente nao abre.

**Duas pendências de olho, não de código:**

- Nada da forma que varia com o estado foi visto no `runClient`. O blockstate está certo no arquivo
  e a propriedade está certa no mundo, mas se o braço aparece no lugar, só a tela diz.
- **O mod migrado mudou de casa.** `examples/logistica` saiu do mine-loader: o mod vive em
  [`logistic-pipes-lua`](https://github.com/MarcosBrendonDePaula/logistic-pipes-lua), com a arte do
  original, e aqui se aponta a pasta com `-Pmods=`. Duas copias divergiriam no primeiro ajuste, e a
  licenca do original (MMPL) nao permite trazer a arte para um repositorio MIT.
- **`examples/tubos` ficou no lugar dele** como o exemplo minimo do mecanismo, e e o que os testes
  usam: forma por estado, dados por posicao e tique agendado, sem mais nada em volta.

---

## Onde a sessão parou

**O desenho do cano estava lendo o arquivo errado.** O OBJ do original tem um vocabulário que o
manifesto não usava, e as contagens de face provam: desenhávamos `Edge_M_` e `Corner_M_` — 164 faces
que são as **doze arestas e os oito cantos** do miolo. O corpo é `Spacer`, e estava de fora. Era um
arame com a silhueta certa.

Junto, três descobertas que valem mais que o conserto:

- **`Side_BC_%s` é o braço para o inventário.** No Logistic Pipes `BC` é BuildCraft — o vizinho que
  não é cano. O original tem **dois** braços por lado, 92 faces cada, e nunca pedimos o segundo. Era
  por isso que o cano não crescia braço para o baú.
- **O `keep_within` resolvia um problema que não existe.** A nota anterior dizia que "as placas do
  original não têm lado no nome, e só a região as distingue". Têm: a linha `g` traz
  `Texture_Side_W3` junto de `Side_Texture_Plate63`. A máquina de recorte inteira compensava uma
  leitura equivocada do arquivo.
- **O aviso `os bracos por conexao nao serao desenhados` era falso.** Ele disparava antes de olhar
  se as peças já haviam escrito o blockstate multipart — ou seja, gritava justamente na vez em que
  o recurso funciona.

**O que ainda falta no desenho, e por quê.** `Corner_M_`, `Corner_I_` e `Corner_I3_` ocupam a
**mesma caixa**: são três variantes do mesmo canto, e o original escolhe conforme quais dos três
lados vizinhos estão ligados. `Mount_N_U` e `Support_N_U` ficam na costura entre dois lados. O
`obj_parts` só sabe dizer "este lado está ligado" — falta condição **por par** e **por trio** de
lados. Enquanto não existir, as junções ficam com o canto errado.

### O custo de estado, que era grande e ninguém tinha medido

Quinze mods de exemplo criavam **128.416 blockstates**. O Minecraft inteiro tem cerca de 26.000.

Duas propriedades do loader eram registradas em **todo** bloco declarativo, dezesseis valores cada:
`lua_variant` e `lua_luminance`. Zero dos quinze blocos declarava luminosidade, e exatamente um a
mudava por script. Agora as duas são declaradas — `variant_textures` com mais de uma, e
`state.dynamic_luminance` — e quem chama a operação sem declarar recebe a recusa dizendo o que
fazer, em vez da mensagem do Minecraft sobre propriedade ausente.

| | estados dos mods Lua |
|---|---|
| antes | 128.416 |
| depois | **9.396** |

**Medido no servidor, não estimado.** Com um GC forçado e o histograma do heap: 226 MB de dado vivo,
dos quais `BlockState` e o cache dele são 5,2 MB **somando todos os mods da instância**, e o LuaJ
inteiro é 0,4 MB. O consumo é do jogo — Metaspace de 123 MB do AE2, heap comprometido que o G1 não
devolve, e o daemon do Gradle, que é outro JVM e costuma ser confundido com o servidor.

### Fechado nesta rodada

- **`connects_to: "@items"`** — o cano liga a qualquer coisa que guarde item, **por capability**. Era
  a discordância de fundo: a rede alcançava o baú perguntando à capability, e o desenho procurava um
  id numa lista. O lado deixou de ser booleano e passou a ter três valores (`none`/`block`/
  `inventory`), nos dois adaptadores — é o que permite escolher entre os dois braços.
- **`obj_parts.connected_inventory`** — peça própria do lado do baú, caindo na de `connected` quando
  o mod não declara.
- **`ctx.server.crafting_result(padrão)`** — recebe nove slots e devolve o que sai, perguntando ao
  próprio jogo. As outras duas operações de receita respondem **pelo resultado**, e nenhuma servia a
  um cano de fabricação, que tem o padrão e quer o produto.
- **Tela do fabricador com bancada 3×3**, no elemento `grid` que já existia. Fecha o item 3 da lista
  de pendências: era o único bloco cuja configuração exigia comando e agora tem tela.
- **Cache de recurso remoto consultado antes do download.** Ele era indexado pelo hash do conteúdo, e
  o conteúdo só se conhecia depois de baixar: economizava disco e não economizava rede. Com `sha256`
  declarado, agora nem abre a conexão — e todo recurso remoto passou a ser guardado, não só textura.
- **`/mod logistica mapa`** — a rede inteira com a vizinhança de cada cano, e os canos que existem
  por perto e não entraram nela.

Bateria do mod em **13/13**, com três casos novos. Um deles corrigiu uma premissa minha: **uma tora
sozinha na bancada faz quatro tábuas** — receita sem formato ignora posição, e eu a tinha usado como
exemplo de arranjo que não produz nada.

## Onde a sessão parou (rodada anterior)

**Publicado e verde:** build, 18/18 GameTests em cada plataforma, suíte do núcleo, e 10/10 na
bateria do mod migrado.

**Fechado nesta rodada, tudo saído de jogar de verdade:**

- **`player.looking_at`** — o bloco mirado e a face atingida. Fecha dois itens do checklist
  (raycast e direção do olhar), e faz um comando não precisar de coordenada digitada.
- **Erro de script avisa quem clicou.** Um erro de Lua num callback é registrado e não propagado, o
  que impede um mod quebrado de derrubar o jogo — mas quem clicava via o clique não fazer nada.
  Custou uma investigação inteira: uma cor declarada como número derrubava a montagem de uma tela,
  o log tinha a resposta na primeira linha, e dentro do jogo o sintoma era silêncio absoluto.
- **O diagnóstico de manifesto passou a olhar itens**, não só blocos — um item podia declarar a
  textura no formato de bloco e cair no substituto sem uma linha de aviso.

**O que está em aberto, em ordem de quanto atrapalha:**

1. **O desenho da malha no Fabric.** Ver a seção própria, abaixo. O NeoForge desenha; o Fabric não.
   Uma nota importante de método: o AO foi desligado nos dois lados "para não divergir por
   configuração", e isso **quebrou o lado que funcionava**. Foi restaurado no NeoForge. Quando um
   lado funciona e o outro não, o lado bom é a referência — não se mexe nele.
2. **O chassi não tem caso na bateria.** Foi verificado à mão: 32 barras saíram sozinhas de um baú
   e chegaram ao outro. Sem caso automático, quebra em silêncio.
3. **Configurar exige comando.** Abastecedor, satélite, fabricador e os slots do chassi não têm
   tela de configuração; clicar neles só mostra o estado e o comando a usar.

## A refinar, achado ao portar

Coisas que o loader faz de um jeito mais simples do que precisaria, encontradas usando-o de verdade.
Nenhuma bloqueia; todas custam algo a quem escreve o mod.

- **`inventory.size` tem que ser múltiplo de nove.** A regra vem da janela do jogo, que desenha
  fileiras de nove, e a mensagem de recusa explica isso. Mas um bloco com três slots é legítimo — o
  chassi do mod migrado tem de um a cinco no original — e hoje ele precisa declarar nove e ignorar o
  resto. Sairia com uma tela própria em vez da janela do jogo, que é trabalho da camada de UI.

- **Sem jogador, só o spawn tica.** Um bloco longe do spawn aceita `schedule_block` — a chamada
  responde certo — e o tique nunca chega. Isso é do jogo, não do loader, mas o loader poderia
  avisar: hoje o silêncio parece defeito do mod. Está registrado no `CLAUDE.md` como armadilha de
  verificação, e "carregar e manter chunk sob demanda" já está em `API_GAPS.md`.

- **Um erro de Lua em callback é logado, não propagado.** Já documentado, e mordeu de novo: o tique
  do chassi não rodava e não havia nada no log apontando para a causa. Um contador de erros por
  bloco, ou um aviso na primeira falha de cada handler, tornaria isso visível sem mudar a regra.

## O que falta

### Lacunas que a migração do Logistic Pipes encontrou

Estão em `API_GAPS.md`, em ordem de quanto doem:

1. ~~**Forma por estado.**~~ **Fechado.**
2. ~~**Tique agendado por posição.**~~ **Fechado**, e o exemplo já usa: o item atravessa os canos.
3. ~~**Ler inventário por slot.**~~ **Fechado.** `container_at` numera cada linha, e `insert_into`
   e `extract_from` aceitam um slot opcional.
4. **Evento de bloco quebrado com o inventário íntegro.** A rede só se refaz quando alguém abre a
   tela.
5. ~~**`events` sem `entrypoint` é aceito em silêncio.**~~ **Fechado.**

### Nível 7 do checklist, o que sobrou

- **Animação própria.** A forma declarada se move com a animação da base: um bicho de quatro patas
  derivado de um bípede anda como bípede. O caminho está mapeado em `API_GAPS.md` — o jogo tem
  keyframes desde a 1.19.4, e mapeia quase um-para-um para JSON. Decisão que vale lembrar: animação
  própria e animação da base são **exclusivas**.
- **Hierarquia de ossos.** O formato é plano porque é assim que as classes de modelo do jogo
  procuram as peças.

### Mod migrado, o que falta portar

- `supplier` — mantém um baú abastecido
- `satellite` — endereço nomeado na rede
- `crafting` — fabrica sob demanda
- `chassis` + módulos — o que faltava (ler inventário por slot) já existe

### Outros

- **Tela de mods no NeoForge.** Existe só no cliente Fabric; está na matriz como sim/não.
- **Escala da criatura na tela.** `minecraft:generic.scale` registra sem aviso, mas ainda não foi
  conferido visualmente.
- **UI por HTML e CSS.** Estudo em `UI_HTML_DESIGN.md`. A tela do menu principal reforçou o
  argumento: montar descrição em Java virou concatenação de JSON à mão, e o primeiro defeito foi
  cor sem alfa — que passa despercebida porque o número *parece* branco.

---

## As tarefas, se a sessão retomar

O acompanhamento fino vive na lista de tarefas da sessão, com passo a passo e verificação de cada
uma. Este documento é o mapa; elas são o roteiro.

| # | Tarefa | Estado |
|---|---|---|
| 14 | ~~Forma do bloco variando com o estado~~ | **fechado** |
| 15 | ~~Recusar `events` sem `entrypoint`~~ | **fechado** |
| 20 | ~~Aplicar `placement.facing`~~ | **fechado** |
| 16 | ~~Tique agendado por posição~~ | **fechado** |
| 17 | ~~Ler inventário por slot~~ | **fechado** |
| 18 | Evento de bloco quebrado com o inventário íntegro | a fazer |
| 19 | Portar os canos que faltam, e fazer o item viajar pelo cano | a fazer |
| 12 | UI por HTML e CSS | adiado por decisão |

## Como retomar

```bash
./gradlew build                       # tudo, nas duas plataformas
./gradlew runGametest                 # GameTests do Fabric
./gradlew :neoforge:runGameTestServer # os mesmos, no NeoForge

tools/servidor-dirigivel.sh iniciar   # servidor sem cliente
tools/servidor-dirigivel.sh cmd "mod autoteste"
```

Os dois pontos em `:runClient` e `:runServer` não são enfeite — sem eles sobem as duas plataformas
ao mesmo tempo, escrevendo no mesmo log. Custou tempo nesta sessão.

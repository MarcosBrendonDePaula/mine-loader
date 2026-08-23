# Mine Loader — o que falta para um modder construir

Este documento olha o loader do ponto de vista de quem quer criar um mod: o que já é possível hoje,
o que não é, e em que ordem faz sentido resolver. Ele complementa o [catálogo de eventos](EVENTS.md),
que trata de quando o código roda; aqui a pergunta é o que o código consegue fazer quando roda.

## A API disponível hoje

| Área | Operações |
|---|---|
| Log | `mod.log.info`, `mod.log.warn` |
| Servidor | `broadcast` |
| Mundo | `get_block`, `set_block`, `fill`, `place_structure` |
| Bloco declarativo | `set_block_variant`, `set_block_property`, `set_block_luminance` |
| Dados por bloco | `get_block_data`, `set_block_data` |
| Feedback | `play_sound`, `spawn_particles`, `send_action_bar` |
| Jogador | `name`, `uuid`, `send_message`, `position`, `health`, `teleport` |
| Inventário | `count_item`, `give_item`, `take_item`, `held_item` |
| Janela | `mod.menu`, `open_menu`, `update_menu`, `close_menu`, `open_menu_id` |
| Tela desenhada | `mod.screen`, `open_screen`, `update_screen`, `close_screen`, `set_hud`, `supports_screens` |
| Tela do jogo | `set_overlay`, `clear_overlay` |
| Registro do jogo | `items`, `recipes_for`, `recipes_using`, `drops_of`, `dropped_by` |
| Processos do mod | `mod.process`, `processes` |
| Inventário de bloco | `capabilities_at`, `container_at`, `insert_into`, `extract_from` |
| Tela do jogador | `screen_size` |
| Leitura do servidor | `players`, `time_of_day`, `world_name` |
| Entidades | `spawn_entity`, `entities_near`, `remove_entity`, `damage_entity` |
| Agendamento | `mod.after` |
| Comandos | `mod.command`, publicado em `/mod <nome>` |
| Estado | `mod.state`, por mod, persistido em disco |
| Entre mods | `mod.require`, com `dependencies` |

Conteúdo declarável: blocos, itens, aba criativa, estruturas, loot, tags, estados de bloco.

## O que dá para construir hoje

**Mods de decoração e construção.** Blocos novos com textura, variantes, dureza, luminância, drops e
tags funcionam bem. Estruturas declaradas em JSON podem ser posicionadas por script. Um mod que
adiciona blocos bonitos e uma ferramenta que constrói é inteiramente possível.

## O que não dá, e por quê

### Inventário do jogador

Não existe nenhuma operação de inventário: dar item, remover, contar, checar o que está na mão. Sem
isso não é possível fazer recompensa, economia, loja, missão, ferramenta que consome munição, ou
qualquer troca de item por ação.

É a lacuna que bloqueia mais tipos de mod ao mesmo tempo.

### Persistência

`mod.state` vive só em memória. Um mod que conta, acumula progresso, guarda quem já recebeu algo ou
mantém um placar perde tudo quando o servidor para. Existe a classe `StateStore`, que grava e lê o
estado em JSON, mas ela ainda não está ligada ao runtime.

Falta também estado **por bloco**: uma máquina, um baú customizado ou um bloco que lembra quem o
colocou precisam guardar dados naquela posição, o que hoje não existe.

### Receitas

Um item novo não é obtenível por craft. Só por `/give`, drop de bloco ou script. Sem receitas, todo
conteúdo novo depende de comando ou de mineração para chegar ao jogador.

### Feedback ao jogador

Nenhum som, nenhuma partícula, nenhuma mensagem na action bar ou título na tela. Um mod pode mudar o
mundo, mas o jogador não recebe nenhum retorno sensorial disso — só texto no chat.

É barato de implementar e muda muito a sensação de usar um mod.

### Comandos próprios

Um mod não pode registrar comandos. A permissão `server.command.register` chegou a ser prevista, mas
não há nada implementado por trás dela. Sem comandos, não há como um administrador operar o mod, nem
o jogador acionar algo fora de clicar em blocos.

### Temporizadores

Não existe "faça isso daqui a cinco segundos". A única forma de agendar é contar ticks manualmente
dentro do evento `tick`, que é global e caro. Qualquer coisa com duração — um efeito temporário, uma
construção progressiva, um cooldown — precisa ser escrita à mão.

### Entidades

Nada. Não é possível criar mobs, invocar entidades existentes, reagir a morte ou dano, nem detectar
quem está perto de um bloco. Mods de combate, criaturas e companheiros estão fora de alcance.

### Interface

Não há telas, menus, HUD ou containers. Um bloco não pode abrir uma janela. Um mod de máquina,
mochila ou loja depende disso.

### Modelos de bloco

Todo bloco é um cubo. `render.model` aceita apenas `cube_all`; escadas, lajes, cercas, plantas e
qualquer forma não cúbica não existem, e o campo é avisado como não aplicado na carga.

## Conhecer o conteudo do jogo

Um catalogo -- o papel que o JEI cumpre -- precisa responder tres perguntas sobre um item: o que ele
e, como se obtem, e para que serve. As tres tem API.

| Pergunta | Operacao |
|---|---|
| Que itens existem | `ctx.server.items({ namespace, contains, limit })` |
| Que receita o produz | `ctx.server.recipes_for(item, limit)` |
| Que receita o consome | `ctx.server.recipes_using(item, limit)` |
| Que bloco ou mob o derruba | `ctx.server.dropped_by(item, limit)` |
| Que itens um bloco ou mob derruba | `ctx.server.drops_of(fonte, limit)` |

Todas passam por `server.read` e todas tem teto obrigatorio. O motivo do teto e o mesmo nas cinco: o
jogo nao mantem indice reverso, entao cada pergunta custa uma varredura -- do registro, do livro de
receitas ou dos blocos. Um mod deve guardar o que ja perguntou em vez de repetir a consulta a cada
quadro.

**Como os drops sao descobertos.** O loader varre as tabelas de loot carregadas e deduz o dono de
cada uma pelo nome: `blocks/stone` e do bloco, `entities/sheep/white` e da ovelha. Perguntar a
tabela de cada bloco e de cada tipo de entidade seria o caminho obvio, e erra -- a ovelha tem uma
tabela por cor, escolhida dentro da instancia, e o tipo so conhece a generica, que da carne e nao
la. Varrer resolve os dois casos e ainda descobre variantes que ninguem precisou prever.

**Limites.** So entradas de item sao lidas: uma entrada que aponta para outra tabela e ignorada.
Tabelas sem dono -- bau de masmorra, pesca, presente de aldeao -- ficam de fora, porque nao
respondem "o que este bloco derruba".

**O que nao e loot.** Morte de mob e mineracao sao dados, e por isso consultaveis. Ja uma interacao
que vive em codigo -- tosquiar uma ovelha, encher um balde numa vaca, pegar um peixe com balde --
nao esta registrada em lugar nenhum do jogo: ele sabe fazer, mas nao sabe dizer. Para aparecer num
catalogo, precisa ser declarada como processo.

E o mesmo caso de uma mecanica inventada por um mod, e por isso a mesma solucao serve aos dois. O
exemplo `processos_vanilla` faz exatamente isso: declara tosquia, ordenha e balde de peixe, e todo
catalogo passa a mostra-las so por ele estar instalado, porque o registro e global.

Isso nao vive dentro do loader de proposito. O nucleo nao conhece conteudo do jogo -- e a regra que
o mantem portavel -- e como dado em Lua qualquer um corrige uma linha errada sem esperar uma versao
nova do loader.

### Processos: mecanica que o jogo nao conhece

O livro de receitas do jogo so conhece as receitas do jogo. Uma mecanica inventada por um mod -- dar
trigo a uma vaca e receber leite, moer minerio, curtir couro -- nao existe la, e por isso seria
invisivel a qualquer catalogo. E o que o JEI resolve com categorias de receita.

```lua
mod.process("ordenha", {
    title  = "Alimentar a vaca",
    inputs = { "minecraft:wheat" },
    output = { item = "minecraft:milk_bucket", count = 1, chance = 0.5 },
    by     = "minecraft:cow"      -- quem executa: entidade, bloco ou item
})
```

```lua
ctx.server.processes({ produces = item })   -- o que produz isto
ctx.server.processes({ uses = item })       -- o que consome isto
ctx.server.processes({ by = "minecraft:cow" })
```

O registro e **global**: um catalogo e um mod que lista o que os outros declararam, e nao teria como
fazer isso se cada mod so enxergasse os proprios processos. Uma recarga descarta os processos
daquele mod, junto com as telas e as tarefas.

O formato ficou proximo do de uma receita do jogo de proposito, para um catalogo desenhar os dois
com o mesmo codigo em vez de manter dois desenhos paralelos.

## Falar com o resto do ecossistema

Um catalogo le o jogo. Operar o jogo -- tirar carvao de um bau, alimentar a maquina de outro mod --
exige alcancar o inventario de um bloco, e e ai que a portabilidade fica em risco.

Cada plataforma tem o proprio mecanismo para isso, e os tres sao incompativeis:

| Plataforma | Mecanismo |
|---|---|
| Fabric | Transfer API, `Storage<ItemVariant>` |
| NeoForge | Capabilities, `IItemHandler` |
| Paper | `Inventory`, do Bukkit |

Se o contrato citasse um deles, um mod escrito para este loader deixaria de rodar nos outros. Por
isso o nucleo nomeia a **ideia**, e nao a API:

```lua
local capacidades = ctx.server.capabilities_at(x, y, z)   -- { "items" }
local conteudo    = ctx.server.container_at(x, y, z)      -- { {slot=, item=, count=} }
local sobrou      = ctx.server.insert_into(x, y, z, "minecraft:coal", 8)
local pegou       = ctx.server.extract_from(x, y, z, "minecraft:iron_ingot", 4)
```

O vocabulario de capacidades e fechado -- `items`, `fluid`, `energy` -- e cada adaptador traduz para
o mecanismo da casa. Hoje so `items` tem operacoes; as outras duas estao previstas e ainda nao
respondem, porque anunciar uma capacidade que o loader nao sabe usar faria o mod perguntar e nao ter
o que fazer com a resposta.

**E isso que os mod loaders nao dao.** Quem escreve para Fabric reescreve para NeoForge, porque as
duas APIs nao se conhecem. Um mod Lua escrito para este loader roda nas duas assim que houver
adaptador, sem mudar uma linha -- e alcanca a maquina de um mod de terceiros sem que esse mod saiba
que o loader existe, porque o que ele implementou foi o padrao da plataforma dele.

**O que isso nao resolve.** Chamar codigo de outro mod continua fora: nao ha ponte para a API Java
dele, e nem deveria haver -- um script de terceiros com acesso a JVM seria o fim do sandbox. E uma
mecanica que o outro mod executa sem registrar receita nem expor inventario permanece invisivel,
pelo mesmo motivo que a tosquia permanecia: o jogo sabe fazer e nao sabe dizer.

## Prioridade sugerida

A ordem considera quantos tipos de mod cada item destrava, e não a dificuldade.

| Ordem | O que | Estado |
|---|---|---|
| 1 | Inventário do jogador | implementado |
| 2 | Persistência de `mod.state` | implementado |
| 3 | Sons e partículas | implementado |
| 4 | Receitas | implementado |
| 5 | Comandos próprios | implementado |
| 6 | Temporizadores | implementado |
| 7 | Estado por bloco | implementado |
| 8 | Entidades | implementado para entidades do jogo |
| 9 | Interface | implementado como menu de itens |
| 10 | Modelos além do cubo | implementado como formas de colisão |

## Limites do que foi implementado

Três itens foram entregues com alcance menor do que o nome sugere, e é importante saber onde eles
param antes de planejar um mod em cima deles.

**Entidades.** O loader invoca, lista, remove e machuca entidades **do jogo**. Criar uma espécie
nova, com modelo e comportamento próprios, continua fora: isso exige registrar um tipo de entidade,
um modelo e um renderizador no cliente, além de sincronização de rede. Um mod pode usar um Allay como
guardião; não pode criar o Moa.

**Interface.** Além da tela desenhada e do HUD, o mod desenha sobre telas do próprio jogo — o
inventário, o forno, o menu de pausa — com `set_overlay`, e o `tooltip` de qualquer elemento passou
a ser desenhado. A grade de itens, a rolagem, a leitura de receitas, os drops e os processos declarados por mod
também existem, e com eles um catálogo — listar, buscar, ver o que produz, o que consome e o que
derruba um item — cabe em um mod de Lua. O exemplo `catalogo` faz exatamente isso.

O que separa esse catálogo de um JEI de verdade é interação com a tela de baixo: arrastar um item
para o inventário, preencher a mesa de trabalho com um clique, e um atalho de teclado. Os três
dependem da mesma peça ausente, descrita abaixo.

**A peça ausente: os slots da tela de baixo.** O loader desenha sobre o inventário, mas não lê nem
mexe nele. Sem isso não há arrastar item, preencher receita, nem saber o que o jogador está
segurando naquela tela. É o que hoje separa o catálogo de um JEI.

Também fora: aba, barra de rolagem arrastável e tecla como ação, que impede um atalho ao estilo
`R` e `U` do JEI.

A janela de itens usa a tela de container do próprio jogo, o que faz o recurso funcionar em
qualquer cliente vanilla com o loader instalado. Cada slot é um botão: o clique volta ao script com
o índice, o item e o botão usado, e o mod redesenha sem fechar a tela. O que continua fora é a tela
desenhada de verdade — HUD, botões próprios, campos de texto — porque isso exige um renderizador no
cliente e um protocolo de rede próprio.

**Formas de bloco.** Existem lajes, tapetes, painéis, postes, placas, plantas e mesas, definidas como
caixas de colisão. Escadas, cercas e portões continuam fora, porque dependem de estado direcional e
de conexão com vizinhos, que o manifesto ainda não declara. O modelo visual continua sendo um cubo
com textura: a forma muda a colisão e o contorno, não o desenho.

Dimensões próprias e geração de mundo seguem inteiramente fora do alcance.

## Permissões

| Permissão | Protege |
|---|---|
| `chat.send` | Mensagens no chat e na action bar |
| `player.read` | Leitura de jogador: item na mão, posição, vida, contagem de itens |
| `player.inventory` | Dar e remover itens |
| `player.move` | Teleporte |
| `player.menu` | Abrir e fechar menus, telas, HUD e sobreposições |
| `world.read` | Ler blocos, dados de bloco, tocar som e emitir partículas |
| `world.write` | Alterar blocos, preencher regiões, posicionar estruturas, gravar dados |
| `world.containers` | Ler e mexer no inventário de um bloco |
| `entity.read` | Listar entidades próximas |
| `entity.spawn` | Invocar entidades |
| `entity.modify` | Remover e machucar entidades |
| `server.command.register` | Registrar comandos |
| `entity.read`, `entity.spawn`, `entity.modify` | Entidades |

| `server.read` | Ler jogadores conectados, hora do dia, dimensão, itens, receitas, drops e processos |

Nenhuma permissão declarável fica sem uso: todas protegem uma operação real.

## Estudo de caso: recriar o Aether II

Serve como régua porque o Aether II é um mod grande e conhecido, com quase todas as categorias de
conteúdo que um loader pode precisar suportar.

### O que já daria para fazer

| Parte do Aether | Situação |
|---|---|
| Blocos decorativos: holystone, quicksoil, blocos de minério | Possível hoje |
| Minérios como blocos com drop próprio | Possível, via `loot` e `tags` |
| Ambrosium Shard e similares como itens simples | Possível |
| Dungeons como desenho de blocos | Possível declarar, mas só posicionável por script |
| Aba criativa do mod | Possível |

Isso cobre a aparência de uma fatia pequena do mod, e nada da jogabilidade.

### O que falta, em ordem de gravidade

**Dimensão própria.** O Aether é, antes de tudo, outra dimensão, com portal, céu, iluminação e regras
próprias. Nada disso existe no loader: não há como declarar dimensão nem portal.

**Geração de mundo.** Ilhas flutuantes, biomas, árvores skyroot, veios de minério e dungeons nascendo
no terreno. O loader posiciona estruturas quando um script manda; não existe geração automática, nem
bioma, nem regra de distribuição.

**Entidades.** Moa, Aerbunny, Zephyr, Cockatrice, Sentries, chefes como o Slider e a Valkyrie Queen.
Não há nada de entidades: nem criar, nem invocar, nem reagir a dano ou morte. Um mod inteiro de
criaturas está fora de alcance.

**Ferramentas e armaduras.** As linhas de skyroot, holystone, zanite, gravitite e phoenix. Itens hoje
têm apenas empilhamento, durabilidade nominal, raridade e textura: não existe material de ferramenta,
dano, velocidade de mineração, nível de colheita, proteção nem encantamento.

**Máquinas com interface.** Enchanter, Freezer, Incubator e Altar são blocos com inventário próprio,
progresso e tela. Faltam as três pernas disso: estado por bloco, interface e receitas de tipo
próprio.

**Montaria e voo.** O Moa é uma entidade rideável com salto múltiplo. Depende de entidades e de
controle de movimento do jogador, nenhum dos dois existente.

**Efeitos e atributos.** Gravidade reduzida, efeitos de poção próprios, dano de queda alterado.

**Modelos não cúbicos.** Folhas, mudas, plantas, escadas, lajes, cercas, portões. Todo bloco do
loader é um cubo.

**Progressão de dungeon.** Chaves, salas trancadas, baús com loot, boss travando a saída. Depende de
estado por bloco, entidades e loot de baú, que hoje só existe para bloco quebrado.

### Leitura honesta

Recriar o Aether II hoje é inviável, e não por uma peça faltando: o loader cobre bem a parte
declarativa de blocos e itens simples, que é talvez um décimo do que aquele mod é. O restante depende
de quatro sistemas inteiros que não existem — dimensões, worldgen, entidades e interface.

Isso não invalida o caminho. Indica que o alvo realista para as próximas etapas é o mod de conteúdo e
mecânica leve — blocos com comportamento, ferramentas simples, recompensa, economia, minigame — e que
um mod de conversão total continua exigindo Java por um bom tempo.

A ordem de prioridade acima já reflete isso: inventário, persistência, som e receitas destravam
muitos mods pequenos e médios, enquanto dimensões e entidades, apesar de mais chamativas, só passam a
valer quando a base estiver firme.

## Revisao contra a API do jogo

Uma revisao do que foi construido, comparando com o que a API do Minecraft realmente oferece,
encontrou quatro problemas. Todos foram corrigidos.

**Dimensao errada.** Treze operacoes chamavam `getOverworld()` diretamente. Um bloco usado no Nether
leria e escreveria dados no overworld, sem erro visivel: o altar do exemplo teria contagens
misturadas entre dimensoes. O adaptador passa a publicar o mundo do evento antes de entregar ao
runtime, e todas as operacoes agem nele. Fora de um evento, como em uma tarefa agendada, o overworld
continua sendo usado, por ser o comportamento previsivel quando o script nao informou onde atuar.

**Retorno mentiroso em `give_item`.** O contrato prometia devolver a quantidade que nao coube, mas a
implementacao devolvia sempre zero e o Lua recebia a quantidade pedida. Um script que checasse o
retorno para avisar o jogador nunca detectaria inventario cheio. Agora o que nao cabe e derrubado no
mundo e reportado de verdade.

**Nenhum limite de execucao.** Um `while true do end` em qualquer mod prendia a thread principal do
servidor para sempre, e o Minecraft nao tem como recuperar uma thread travada. Isso e o que separa
rodar mods proprios de aceitar mods de terceiros. O interpretador passa a ser interrompido por um
gancho de instrucoes com limite de tempo por callback; a tabela `debug` continua fora do alcance do
script.

**Particulas com parametro.** `spawn_particles` so aceita particulas simples. Tipos que exigem
parametros, como `dust` e `block`, sao recusados com mensagem clara em vez de falharem em silencio.

### Limite de execucao

| Aspecto | Valor |
|---|---|
| Tempo por callback | 20 ms, bem abaixo dos 50 ms de um tick |
| Verificacao | a cada 2.048 instrucoes, para nao pesar no caso normal |
| Alcance | vale por callback: interromper um nao afeta o proximo nem outros mods |

Um script comum nao percebe o limite. Um script que passa dele e interrompido com erro no log,
identificando o mod.

### Continuacao da revisao

Os dois itens deixados em aberto na primeira passagem foram fechados, e a revisao do ciclo de
recarga encontrou mais um problema.

**`server.read` sem uso.** Era a ultima permissao declarada que nao protegia nada. Passou a cobrir
tres leituras que faltavam de qualquer forma: jogadores conectados, hora do dia e dimensao corrente.
Agora toda permissao declaravel protege uma operacao real.

**Estado so gravado ao desligar.** Uma queda do servidor perdia tudo que os mods acumularam desde o
inicio. Foi acrescentado salvamento automatico a cada cinco minutos, que limita a perda sem gravar a
cada tick.

**Recarga deixava restos do script antigo.** Uma tarefa agendada por `mod.after` e um comando
registrado antes da recarga continuavam apontando para funcoes do ambiente descartado. A tarefa
rodaria com codigo velho, e o comando executaria a versao anterior mesmo depois de o arquivo mudar.
Agora `/lua reload` descarta as tarefas pendentes e os comandos daquele mod antes de recompilar, e
informa quantas tarefas foram descartadas.

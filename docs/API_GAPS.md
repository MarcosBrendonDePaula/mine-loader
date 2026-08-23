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
| Menu | `open_menu`, `close_menu` |
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

**Interface.** O menu usa a tela de container do próprio jogo, o que faz o recurso funcionar em
qualquer cliente vanilla com o loader instalado. Em troca, é uma grade de itens somente leitura: não
há HUD, tela desenhada, botões nem campos de texto, e o jogador não retira o que está exposto.

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
| `player.menu` | Abrir e fechar menus |
| `world.read` | Ler blocos, dados de bloco, tocar som e emitir partículas |
| `world.write` | Alterar blocos, preencher regiões, posicionar estruturas, gravar dados |
| `entity.read` | Listar entidades próximas |
| `entity.spawn` | Invocar entidades |
| `entity.modify` | Remover e machucar entidades |
| `server.command.register` | Registrar comandos |

`server.read` continua sem proteger operação alguma e deve ser removida ou passar a cobrir algo.

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

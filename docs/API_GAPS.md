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
| Jogador | `name`, `uuid`, `send_message` |
| Estado | `mod.state`, compartilhado por mod, apenas em memória |
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

| Ordem | O que | Destrava |
|---|---|---|
| 1 | Inventário do jogador | Recompensa, economia, loja, missão, ferramenta com custo |
| 2 | Persistência de `mod.state` | Qualquer mod com progresso ou memória entre sessões |
| 3 | Sons e partículas | Feedback em todo mod que já existe |
| 4 | Receitas | Conteúdo novo obtenível sem comando |
| 5 | Comandos próprios | Operação e administração do mod |
| 6 | Temporizadores | Efeitos com duração, cooldown, construção progressiva |
| 7 | Estado por bloco | Máquinas, containers, blocos com memória |
| 8 | Entidades | Mobs, combate, deteção de proximidade |
| 9 | Interface | Menus, HUD, containers visuais |
| 10 | Modelos além do cubo | Formas de bloco variadas |

Os quatro primeiros somados são o que separa "dá para fazer blocos bonitos" de "dá para fazer um mod
de verdade".

## Permissões sem uso

Três permissões são aceitas no manifesto e nunca verificadas em lugar nenhum, porque não existe
operação que elas protejam:

| Permissão | Situação |
|---|---|
| `player.read` | Nenhuma leitura de jogador é protegida; `ctx.player.name` é livre. |
| `server.read` | Não há operação de leitura de servidor. |
| `server.command.register` | Comandos não existem. |

Elas devem passar a proteger as operações correspondentes conforme cada área for implementada. Até
lá, declarar qualquer uma delas não muda nada — o mesmo problema de contrato vazio que o diagnóstico
de campos não aplicados resolveu para os blocos.

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

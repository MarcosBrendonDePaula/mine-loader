# O que um modder consegue construir hoje

Este documento tem uma função só: dizer a verdade sobre o alcance da API, para ninguém começar um
mod e descobrir no meio que falta a peça central. Quando um item sai da lista do que falta, ele sai
na mesma mudança que o implementa.

## A API disponível hoje

| Área | Operações |
|---|---|
| Log | `mod.log.info`, `mod.log.warn` |
| Servidor | `broadcast`, `players`, `time_of_day`, `set_time_of_day`, `weather`, `set_weather`, `world_name`, `mods` |
| Mundo | `get_block`, `set_block`, `break_block`, `fill`, `top_y`, `place_structure` |
| Bloco declarativo | `set_block_variant`, `set_block_property`, `set_block_luminance` |
| Dados por bloco | `get_block_data`, `set_block_data` |
| Inventário de bloco | `capabilities_at`, `container_at`, `insert_into`, `extract_from` |
| Feedback | `play_sound` com categoria, `spawn_particles` com velocidade |
| Jogador — leitura | `name`, `uuid`, `position`, `health`, `food`, `experience`, `game_mode`, `dimension`, `held_item`, `inventory`, `screen_size` |
| Jogador — escrita | `teleport`, `set_health`, `set_food`, `give_experience`, `set_game_mode`, `apply_effect`, `clear_effects` |
| Jogador — mensagem | `send_message`, `send_action_bar`, `show_title`, `play_sound_to` |
| Inventário | `count_item`, `give_item`, `take_item`, `clear_inventory` |
| Janela | `mod.menu`, `open_menu`, `update_menu`, `close_menu`, `open_menu_id` |
| Tela desenhada | `mod.screen`, `open_screen`, `update_screen`, `close_screen`, `set_hud`, `supports_screens` — as tres primeiras e `set_hud` dizem se chegaram ao cliente |
| Tela do jogo | `set_overlay`, `clear_overlay` |
| Diagnóstico de tela | `dump_screen` — devolve onde cada elemento foi parar, e vai para o log |
| Entidades | `spawn_entity`, `entities_near`, `entity_info`, `remove_entity`, `damage_entity`, `heal_entity`, `apply_to_entity`, `teleport_entity`, `push_entity` |
| Bestiário do loader | `declared_entities`, `entity_definition` |
| Fase de registro | `register.entity`, `register.declared` |
| Leitura de mundo | `biome_at`, `light_at` |
| Registro do jogo | `items`, `blocks`, `entity_types`, `recipes_for`, `recipes_using`, `drops_of`, `dropped_by` |
| Inventário por slot | `insert_into` e `extract_from` aceitam um slot opcional |
| Processos do mod | `mod.process`, `processes` |
| Agendamento | `mod.after` |
| Comandos | `mod.command`, publicado em `/mod <nome>` |
| Cliente | `client_screen_opened`, `client_screen_closed`, com `ctx.client.screen` |
| Estado | `mod.state`, por mod, persistido em disco |
| Entre mods | `mod.require`, com `dependencies` |
| Instalação | `mods`, `install_preview`, `install_confirm`, `uninstall`, `install_allowed`, `install_api_enabled`, `set_install_api`, `is_operator` — veja `INSTALACAO.md` |

Entidade e item aceitam dados declarados na criação — nome, equipamento, efeitos, encantamentos,
atributos. Veja `GUIA_DO_MOD.md`.

## O que dá para construir hoje

Máquina com inventário próprio, baú customizado, loja com menu, painel com HUD, catálogo de itens,
mod de economia, sistema de missões, chefe de masmorra equipado, estrutura que se constrói ao
clicar, bloco que reage e guarda estado, item com comportamento próprio, e conteúdo declarado com
textura, modelo, receita, loot e tag.

## O que ainda não dá

### Operações que faltam metade

Cada uma destas tem o par que a torna útil, e não o outro. São as mais baratas de fechar, e as que
mais surpreendem quem esbarra nelas.

| Existe | Falta |
|---|---|
| `apply_effect`, `clear_effects` no jogador | ler os efeitos ativos |
| `spawn_entity`, `damage_entity`, `heal_entity` | mover uma entidade |
| `mod.after`, que dispara uma vez | repetir a cada N tiques, e cancelar |
| `play_sound_to`, direcionado a um jogador | partícula direcionada |
| `get_block`, que devolve o identificador | ler o estado do bloco |

### Estado de bloco

`get_block` responde qual bloco está ali, e nada mais. Se a porta está aberta, para onde a escada
aponta, se o bloco está alagado ou energizado — nada disso é legível nem escrevível.

É a raiz de duas lacunas já registradas: `placement.facing` não aplicado, e a estrutura `.nbt`
perdendo a orientação de escadas e troncos. Fechar aqui resolve as duas.

`state.properties` deixou de ser uma diferença entre plataformas — as duas registram os estados
declarados —, mas continua meio caminho: o bloco **tem** os estados, e o Lua não os lê nem escreve.
Hoje só `set_block_variant` alcança a aparência, e é o vocabulário do loader, não o do jogo.

### O jogador no mundo

**Direção do olhar.** Sem ela não há mira, seleção à distância, nem "o bloco que estou olhando" —
provavelmente a lacuna mais limitante da API hoje.

**Postura**: agachado, correndo, voando. Um mod que reage a como o jogador se move não tem o que ler.

### Efeitos de mundo

**Explosão** e **raio** não existem. São os dois efeitos que um mod de magia ou combate procura
primeiro.

**Largar item solto no mundo**, sem passar pelo inventário de alguém, também não.

**Bioma e nível de luz** são legíveis por `biome_at` e `light_at`. A luz volta separada por origem
— bloco, céu e total —, porque é a luz de **bloco** que decide se um monstro nasce: um lugar
iluminado só pelo sol tem quinze de total ao meio-dia e continua escuro à noite.

### Conteúdo que o loader não registra

**Entidade nova, do zero.** Uma espécie própria *derivada* de uma do jogo já é declarável em
`entities` — id, nome, tamanho, atributos, equipamento, saque e ovo de criação —, e é o que cobre a
maioria dos casos: um guardião que é um golem mais forte, um lobo que resiste ao gelo. Ela herda
modelo, animação e comportamento da base, e por isso a base é obrigatória.

A espécie pode ser declarada em `entities`, herdada de outra espécie declarada — inclusive de outro
mod — ou criada por script na fase de registro (`registration`), com o mesmo resultado nas duas
plataformas. Ela aceita pele própria (`texture`), atributos, efeitos, equipamento, saque, tags e ovo
de criação; a escala visual sai do atributo `minecraft:generic.scale`.

O que continua faltando é a **forma**:

**Geometria própria existe** (`entities[].model`): ossos e caixas em JSON, animados pela base. Os
nomes de osso são os que a base anima, e um nome fora dessa lista é avisado na carga — no jogo ele
não daria erro, a peça só não apareceria.

O que continua faltando:

- **Animação própria.** A forma nova se move com a animação da base. Um bicho de quatro patas
  derivado de um bípede vai andar como bípede, porque é a animação do bípede que gira os ossos.

  O caminho está claro: o jogo tem um sistema de keyframes desde a 1.19.4 — `Animation`,
  `Keyframe`, `Transformation` — e é o que anima o Warden, a Rã e o Tatu. Um keyframe é tempo,
  alvo (`ROTATE`, `TRANSLATE`, `SCALE`) e interpolação (`LINEAR` ou `CUBIC`): declara-se onde o
  osso está em cada instante e **o jogo preenche o meio**. Mapeia quase um-para-um para JSON, e o
  Blockbench já exporta nessa forma. A alternativa do ecossistema é o GeckoLib, que traria uma
  dependência e um formato que não é o do jogo.

  O que decide o desenho quando isso entrar: **animação própria e animação da base são
  exclusivas.** Tocar keyframes exige uma classe de modelo própria, e nela a base deixa de mover os
  ossos. O padrão será: sem `animation` declarada, herda a da base como hoje; com ela, a espécie
  assume o controle.
(A IA **é** declarável desde `entities[].ai`: metas — `float`, `wander`, `panic`, `melee_attack`,
`follow_item`, `avoid`, `look_at_player`, `look_around` — e alvos — `hurt_by`, `attack_player`,
`attack_entity`. Sem declarar, herda a da base.)
- **Hierarquia de ossos.** O formato de hoje é plano: todo osso é filho da raiz, porque é assim que
  as classes de modelo do jogo procuram as peças. Um osso preso a outro ainda não é declarável.

As bases suportadas são uma lista explícita no adaptador; uma base fora dela é recusada na carga, e
não aproximada — registrar assim daria um mob invisível.

**Fluido, dimensão, bioma, encantamento, efeito.** Nenhum é declarável. Cada um tem um registro com
regras próprias, e o loader só cobre bloco e item.

**Geração de mundo.** Um minério declarado não aparece no terreno. Estruturas só entram por
`place_structure`, chamado por um script.

Espécie **nasce** sozinha (`entities[].spawn`): bioma ou tag de bioma, peso, tamanho de grupo,
faixa de luz e de altura. O que ainda falta é o mesmo para **bloco** — minério gerado no terreno.

### Achados ao portar o Logistic Pipes

O Logistic Pipes é o **primeiro mod migrado** para este loader. Foi escolhido de propósito: ele
parou de ser atualizado pelo motivo que o loader existe para resolver — acompanhar as versões do
Minecraft em Java custa caro, e um mod declarativo não paga esse preço.

O porte vive em `examples/logistica` e, com a arte do original, em
[`logistic-pipes-lua`](https://github.com/MarcosBrendonDePaula/logistic-pipes-lua). Escrever um mod
real, portado de um mod real, serve para descobrir o que falta antes que quem escreve um mod
descubra. O que apareceu:

**Manifesto que declara `events` sem `entrypoint` é aceito em silêncio.** O loader registra os
blocos, não executa script nenhum, e o mapeamento de eventos aponta para funções que não podem
existir. Nada reclama, e o sintoma é um mod que carrega e não faz nada — o modo de falhar mais caro
possível, porque parece que o loader está quebrado. Precisa ser recusado na carga.

**A forma de um bloco não varia com o estado.** `shape` é declarado uma vez, e vale para todas as
variantes; `render.variant_textures` troca a textura por variante, e não a geometria. Um cano não
tem como crescer braços em direção aos vizinhos, e a rede fica sendo peças soltas encostadas — que
é a diferença visual mais gritante para o original. É o mesmo mecanismo que cerca, vidraça e muro
do jogo usam, e nenhum deles é declarável hoje.

**Não há tique agendado por posição.** Um cano que move item ao longo do tempo precisaria de "volte
a me chamar nesta posição daqui a N tiques". Hoje só existe `block_random_tick`, que é aleatório, e
o agendador global `mod.after`, que não sabe de posição. O porte contorna entregando na hora — o
item some de um baú e aparece no outro, sem viagem —, e é a maior diferença visível para o
original.

**Não há como ler um inventário por slot pela rede.** `container_at` soma por item, o que basta para
um estoque, e não permite reproduzir filtros por slot como os módulos de chassi do original.

**Falta evento de bloco quebrado com o inventário ainda íntegro.** Uma rede precisa saber que um cano
sumiu para se reconfigurar; hoje a varredura é refeita a cada abertura de tela, que é caro e só
acontece quando alguém olha.

### Comportamento

**Eventos de entidade: nascimento, dano, morte e domesticação existem** (`entity_spawned`,
`entity_damaged`, `entity_died`, `entity_tamed`), e valem para qualquer criatura do mundo, não só
para as declaradas pelo loader. `entity_damaged` é cancelável.

O que ainda falta é **ataque como evento próprio** — hoje se descobre pelo `source_uuid` de quem
apanhou, o que responde "quem bateu" mas não avisa quando um golpe erra.

**Redstone e comparador.** Um bloco declarado não emite nem lê sinal.

**Tick agendado de bloco.** Existe `block_random_tick`; não existe "volte a me chamar daqui a N
tiques nesta posição", que é a base de uma máquina que processa ao longo do tempo.

**Receita customizada.** Dá para declarar receita de bancada e fornalha do jogo, não um tipo novo de
processamento com regras próprias.

### Interface

**Slot funcional em tela desenhada.** O menu usa a tela de baú e tem slots de verdade; a tela
desenhada mostra itens, mas não recebe um item arrastado. Uma máquina com entrada e saída separadas
precisa disso.

**Botão e campo de texto dentro de uma área rolável.** Os dois viram widgets do jogo, posicionados
uma vez, e não passam pelo recorte nem pela rolagem. A descrição é **recusada** em vez de aceita e
desenhada errado — mas a lista clicável e rolável, que é o que se queria, ainda depende de montar o
clique sobre uma `grid`.

### Aparência

**Orientação de bloco existe** (`placement.facing`): `horizontal` e `all` seguem o lado clicado,
`player` encara quem colocou. O blockstate gerado ganha uma variante por direção, com o mesmo modelo
girado — e não seis modelos para manter em sincronia.

O que ainda falta é a orientação **numa estrutura `.nbt`**: escadas e troncos colocados por
`place_structure` continuam perdendo a direção, porque quem a aplica é a colocação, e ali o bloco
chega pronto.

**Som e música próprios.** O mod pode tocar sons do jogo, não distribuir os seus.

**Animação e partícula própria.** Só as do jogo.

## Diferenças entre plataformas

**Nenhuma operação da API responde diferente entre Fabric e NeoForge hoje.** O que falta, falta nas
duas — e é o que este documento lista. `placement` e `render` (layer, emissive, tint) são declarados
e ignorados dos dois lados, e por isso aparecem aqui, e não como diferença.

A comparação por plataforma vive em `COMPATIBILIDADE.md`, e continua existindo mesmo com as colunas
iguais: é ali que a próxima plataforma encontra a lista de trabalho.

## Permissões

| Permissão | Protege |
|---|---|
| `chat.send` | mensagem, barra de ação, título, som para o jogador |
| `player.read` | nome, posição, vida, fome, experiência, modo, dimensão |
| `player.modify` | escrever vida, fome, experiência, modo de jogo, efeitos |
| `player.inventory` | contar, dar, tirar, listar, limpar |
| `player.move` | teleporte |
| `player.menu` | abrir e atualizar janela |
| `world.read` | ler bloco, hora, clima, altura do terreno |
| `world.write` | escrever bloco, quebrar, preencher, hora, clima |
| `world.containers` | inventário de bloco |
| `entity.read` | listar por raio, dados de uma entidade |
| `entity.spawn` | criar entidade |
| `entity.register` | declarar espécie nova, só na fase de registro |
| `entity.modify` | ferir, curar, remover, aplicar dados |
| `server.read` | jogadores online, registro do jogo |
| `server.command.register` | registrar comando |
| `server.install` | prever, instalar e desinstalar mods por link |

`player.modify` é separada de `player.read` e de `player.inventory` de propósito: escrever vida ou
modo de jogo muda as regras sob os pés de quem joga, e um mod que só quer contar itens não deveria
carregar esse poder junto.

# O que um modder consegue construir hoje

Este documento tem uma função só: dizer a verdade sobre o alcance da API, para ninguém começar um
mod e descobrir no meio que falta a peça central. Quando um item sai da lista do que falta, ele sai
na mesma mudança que o implementa.

## A API disponível hoje

| Área | Operações |
|---|---|
| Log | `mod.log.info`, `mod.log.warn` |
| Servidor | `broadcast`, `players`, `time_of_day`, `set_time_of_day`, `weather`, `set_weather`, `world_name`, `mods`, `difficulty`, `set_difficulty` |
| Mundo | `get_block`, `block_state`, `set_block_state`, `set_block`, `break_block`, `fill`, `top_y`, `place_structure`, `game_rule`, `set_game_rule`, `redstone_signal` |
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
| Leitura de mundo | `biome_at`, `light_at`, `block_state`, `game_rule` |
| Registro do jogo | `items`, `blocks`, `entity_types`, `recipes_for`, `recipes_using`, `drops_of`, `dropped_by` |
| Inventário por slot | `insert_into` e `extract_from` aceitam um slot opcional |
| Processos do mod | `mod.process`, `processes` |
| Agendamento | `mod.after` |
| Comandos | `commands` no `mod.json` + `mod.command`/`mod.command_extend` no Lua, publicado em `/mod <nome>`; schema tipado e autocomplete via `server.command.schema` |
| Cliente | `client_screen_opened`, `client_screen_closed`, com `ctx.client.screen` |
| Estado | `mod.state`, por mod, persistido em disco |
| Entre mods | `mod.require`, com `dependencies` |
| Contrato do runtime | `requires.domains` e `requires.capabilities` no manifesto, com versão mínima e recusa explícita |
| Instalação | `mods`, `install_preview`, `install_confirm`, `uninstall`, `install_allowed`, `install_api_enabled`, `set_install_api`, `is_operator` — veja `INSTALACAO.md` |

Entidade e item aceitam dados declarados na criação — nome, equipamento, efeitos, encantamentos,
atributos. Veja `GUIA_DO_MOD.md`. Exemplos de `requires` estão em `docs/examples/README.md`.

## O que dá para construir hoje

Máquina com inventário próprio, baú customizado, loja com menu, painel com HUD, catálogo de itens,
mod de economia, sistema de missões, chefe de masmorra equipado, estrutura que se constrói ao
clicar, bloco que reage e guarda estado, item com comportamento próprio, e conteúdo declarado com
textura, modelo, receita, loot e tag. O exemplo `minimap_demo` acrescenta um minimapa funcional
com câmera lógica aérea client-side de baixa resolução, radar, waypoint persistente e configuração;
os detalhes ficam em [MINIMAP.md](MINIMAP.md).

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
| `map` no HUD, com grelha, marcadores e câmera aérea v1 | mapa-múndi persistente, texturas completas de blocos e navegação integrada |

### Estado de bloco — fechado

`get_block` continua respondendo somente o identificador, mas `block_state` agora devolve um snapshot
com `{id, properties}`. O Lua pode ler `facing`, `open`, `waterlogged`, `powered` e `axis` quando a
propriedade existe no bloco real. `set_block_state` altera um subconjunto dessas propriedades e rejeita
nomes ou valores inválidos; ele não troca o bloco nem cria propriedades.

A implementação existe nos quatro bridges e os GameTests exercitam uma porta vanilla, incluindo
leitura e escrita de `open` e `facing`. A orientação declarada em `placement` e a orientação de blocos
numa estrutura `.nbt` continuam sendo assuntos separados: esta API não corrige automaticamente a
colocação de estruturas existentes.

### Regras e dificuldade — fechadas

`game_rule` e `set_game_rule` já existem nos quatro runtimes. O contrato usa uma whitelist comum de
regras booleanas e inteiras, documentada em `API_ESTAVEL.md`; nomes desconhecidos, tipos errados e
valores inteiros fora do limite seguro são recusados. A escrita usa `world.write`, porque uma Game Rule
muda o mundo inteiro e não apenas o mod que a chamou.

`difficulty` e `set_difficulty` também já existem. Os únicos valores são `peaceful`, `easy`, `normal` e
`hard`; a bridge recusa uma dificuldade bloqueada em vez de contornar a configuração. Não há uma API
para regras customizadas nem para alterar a dificuldade de apenas um jogador.

### O jogador no mundo

**Direção do olhar — fechada.** `ctx.player.looking_at(distância)` devolve o primeiro bloco atingido, a
face e a posição, com alcance limitado. O retorno é `nil` quando a linha de visão não encontra bloco.

**Postura** continua pendente: agachado, correndo, voando e nadando ainda não têm um snapshot estável
no contrato. Também faltam velocidade e vetor de movimento.

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

**A forma de um bloco varia com o estado** (`shape.core`, `shape.arm`, `shape.connects_to`): um
cano cresce braços em direção aos vizinhos, e cerca, muro e vidraça viraram declaráveis junto. O
blockstate gerado é `multipart` — sete peças, e não as sessenta e quatro combinações de seis
booleanos. **A colisão acompanha o desenho**, que é a metade fácil de esquecer: ver o braço e
atravessá-lo é pior que não ter braço.

**Tique agendado por posição** (`ctx.server.schedule_block(x, y, z, tiques)` e o evento
`block_scheduled`, mapeado por `behavior.on_scheduled`): "volte a me chamar nesta posição daqui a N
tiques". É a base de uma máquina que processa ao longo do tempo, e do cano que move item passo a
passo.

Três decisões que valem lembrar:

- **A fila é a do jogo, não um temporizador do loader.** A diferença aparece ao salvar: a fila do
  jogo é gravada com o chunk e volta na próxima sessão, enquanto um temporizador em memória perderia
  todo item que estivesse a caminho quando o servidor caísse. Pela mesma razão o chunk descarregado
  leva junto o que estava agendado nele, em vez de acumular chamadas para um lugar que ninguém está
  olhando.
- **Não se repete sozinho.** Cada tique vale uma vez; continuar é o script agendar o próximo. Um
  loader que repetisse teria que decidir quando parar, e essa decisão é do mod.
- **Só vale em bloco declarado pelo loader.** Agendar num bloco do jogo é recusado: a fila
  aceitaria, o tique iria para o método do bloco vanilla, e o pedido pareceria aceito sem nada
  chegar ao script.

O prazo vai de 1 a 24000 tiques — um dia de jogo. Zero e negativo o jogo trataria como "agora", o
que de dentro do próprio tique é recursão sem folga.

**O exemplo já usa.** O porte do Logistic Pipes move a carga de cano em cano, um passo a cada quatro
tiques, e a carga mora no `block_data` do cano em que está — some junto com o cano, em vez de apontar
para uma posição que não existe mais, e sobrevive ao servidor cair.

Fazer isso encontrou outro limite, que já foi fechado junto: **um bloco não podia conectar e guardar
dados ao mesmo tempo.** O registrador escolhia um dos dois — no NeoForge a condição era literalmente
`connects && !withData` —, então um cano que declarasse `block_data` perdia a conexão inteira, com
as seis propriedades no blockstate e nenhuma mudando nunca. Sem erro nenhum no log. As duas
capacidades são independentes e agora compõem.

**Inventário por slot** — fechado. `container_at` já numerava cada linha; o que faltava era
endereçar o slot que ele nomeia, e `insert_into` e `extract_from` passaram a aceitar um índice
opcional. Sem isso não dá para reproduzir filtro por slot como os módulos de chassi do original, nem
respeitar máquina com entrada e saída separadas: inserir sem dizer onde pode encher o slot de saída.

**Falta evento de bloco quebrado com o inventário ainda íntegro.** Uma rede precisa saber que um cano
sumiu para se reconfigurar; hoje a varredura é refeita a cada abertura de tela, que é caro e só
acontece quando alguém olha.

### Comportamento

**Eventos de entidade: nascimento, dano, morte e domesticação existem** (`entity_spawned`,
`entity_damaged`, `entity_died`, `entity_tamed`), e valem para qualquer criatura do mundo, não só
para as declaradas pelo loader. `entity_damaged` é cancelável.

O que ainda falta é **ataque como evento próprio** — hoje se descobre pelo `source_uuid` de quem apanhou,
o que responde "quem bateu" mas não avisa quando um golpe erra.

**Redstone e comparador — leitura fechada.** `ctx.server.redstone_signal(x, y, z)` lê a potência
recebida de `0` a `15` nos quatro bridges. Emissão dinâmica de sinal por bloco declarativo ainda não é
um contrato separado.


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
| `world.read` | ler bloco, estado, Game Rules, dificuldade, hora, clima, altura, bioma, luz e redstone |
| `world.write` | escrever bloco/estado, regras, dificuldade, quebrar, preencher, hora, clima, agendar tique e estrutura |
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

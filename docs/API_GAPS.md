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
| Feedback | `play_sound`, `spawn_particles` |
| Jogador — leitura | `name`, `uuid`, `position`, `health`, `food`, `experience`, `game_mode`, `dimension`, `held_item`, `inventory`, `screen_size` |
| Jogador — escrita | `teleport`, `set_health`, `set_food`, `give_experience`, `set_game_mode`, `apply_effect`, `clear_effects` |
| Jogador — mensagem | `send_message`, `send_action_bar`, `show_title`, `play_sound_to` |
| Inventário | `count_item`, `give_item`, `take_item`, `clear_inventory` |
| Janela | `mod.menu`, `open_menu`, `update_menu`, `close_menu`, `open_menu_id` |
| Tela desenhada | `mod.screen`, `open_screen`, `update_screen`, `close_screen`, `set_hud`, `supports_screens` |
| Tela do jogo | `set_overlay`, `clear_overlay` |
| Entidades | `spawn_entity`, `entities_near`, `entity_info`, `remove_entity`, `damage_entity`, `heal_entity`, `apply_to_entity` |
| Registro do jogo | `items`, `blocks`, `entity_types`, `recipes_for`, `recipes_using`, `drops_of`, `dropped_by` |
| Inventário por slot | `insert_into` e `extract_from` aceitam um slot opcional |
| Processos do mod | `mod.process`, `processes` |
| Agendamento | `mod.after` |
| Comandos | `mod.command`, publicado em `/mod <nome>` |
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

É a raiz de três lacunas já registradas: `placement.facing` não aplicado, `state.properties` só no
Fabric, e a estrutura `.nbt` perdendo a orientação de escadas e troncos. Fechar aqui resolve as três.

### O jogador no mundo

**Direção do olhar.** Sem ela não há mira, seleção à distância, nem "o bloco que estou olhando" —
provavelmente a lacuna mais limitante da API hoje.

**Postura**: agachado, correndo, voando. Um mod que reage a como o jogador se move não tem o que ler.

### Efeitos de mundo

**Explosão** e **raio** não existem. São os dois efeitos que um mod de magia ou combate procura
primeiro.

**Largar item solto no mundo**, sem passar pelo inventário de alguém, também não.

**Bioma e nível de luz** numa posição não são legíveis. Um mod que gera algo condicionalmente
precisa perguntar isso antes de decidir.

### Conteúdo que o loader não registra

**Entidade nova.** Dá para criar e configurar as do jogo, não declarar uma espécie própria. Um mob
customizado exige modelo, animação e comportamento, que são três sistemas separados.

**Fluido, dimensão, bioma, encantamento, efeito.** Nenhum é declarável. Cada um tem um registro com
regras próprias, e o loader só cobre bloco e item.

**Geração de mundo.** Um minério declarado não aparece no terreno. Estruturas só entram por
`place_structure`, chamado por um script.

### Comportamento

**Eventos de entidade.** Há eventos de bloco e de item, e nenhum de entidade: morte, dano, nascimento
e domesticação não avisam o mod. É a lacuna que mais bloqueia mod de combate.

**Redstone e comparador.** Um bloco declarado não emite nem lê sinal.

**Tick agendado de bloco.** Existe `block_random_tick`; não existe "volte a me chamar daqui a N
tiques nesta posição", que é a base de uma máquina que processa ao longo do tempo.

**Receita customizada.** Dá para declarar receita de bancada e fornalha do jogo, não um tipo novo de
processamento com regras próprias.

### Interface

**Slot funcional em tela desenhada.** O menu usa a tela de baú e tem slots de verdade; a tela
desenhada mostra itens, mas não recebe um item arrastado. Uma máquina com entrada e saída separadas
precisa disso.

### Aparência

**Orientação de bloco.** `placement.facing` não é aplicado: um bloco declarado fica sempre na mesma
direção, independente de como foi colocado. É a mesma lacuna que faz uma estrutura `.nbt` perder a
orientação de escadas e troncos.

**Som e música próprios.** O mod pode tocar sons do jogo, não distribuir os seus.

**Animação e partícula própria.** Só as do jogo.

## Diferenças entre plataformas

O adaptador NeoForge ainda não aplica `state.properties`, `placement`, ferramentas/armaduras nem
`variant` de entidade. A lista completa e atualizada está em `COMPATIBILIDADE.md`, que é onde essa
comparação vive.

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
| `entity.modify` | ferir, curar, remover, aplicar dados |
| `server.read` | jogadores online, registro do jogo |
| `server.command.register` | registrar comando |

`player.modify` é separada de `player.read` e de `player.inventory` de propósito: escrever vida ou
modo de jogo muda as regras sob os pés de quem joga, e um mod que só quer contar itens não deveria
carregar esse poder junto.

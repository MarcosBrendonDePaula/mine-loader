# Controles de mundo para mods declarativos

Este documento separa o que o MineLoader já expõe do que ainda precisa ser implementado para cobrir casos comuns de automação, aventura, combate, economia, exploração e administração de servidores. A regra é exportar **intenções estáveis**, não classes internas de Fabric, NeoForge ou Minecraft.

## O que já existe

Hora e clima já são parte do contrato: `ctx.server.time_of_day()`, `set_time_of_day`, `weather` e `set_weather` funcionam nos quatro runtimes. O loader também oferece nome da dimensão corrente, altura do terreno, bloco, estado do bloco, quebra com loot, preenchimento, estrutura `.nbt`, bioma, luz, redstone, som, partículas, inventários de blocos, entidades, agendamento, menus e dados persistentes por jogador.

| Área | API estável actual | Observação |
|---|---|---|
| Relógio | `time_of_day`, `set_time_of_day` | Escala de `0` a `23999`; escrever preserva os dias passados |
| Clima | `weather`, `set_weather` | `clear`, `rain`, `thunder`, com duração em ticks |
| Bloco | `get_block`, `block_state`, `set_block_state` | Estado usa `{id, properties}` e só aceita propriedades existentes |
| Regras | `game_rule`, `set_game_rule` | Whitelist comum e valores booleanos/inteiros validados |
| Dificuldade | `difficulty`, `set_difficulty` | Quatro valores; recusa mundo com dificuldade bloqueada |
| Terreno | `top_y`, `biome_at`, `light_at` | Consulta física; sem dependência de mod de mapa |
| Construção | `set_block`, `fill`, `break_block`, `place_structure` | Limites de região e coordenadas continuam activos |
| Automação | `redstone_signal`, `schedule_block`, `mod.after`, `mod.every`, `mod.cancel` | A fila de blocos é a do jogo; tarefas Lua têm limite e são limpas no reload |
| Jogador | `player.effects()`, `player.movement()`, `player.equipment()`, `inventory_slot`, `set_inventory_slot` | Snapshots neutros e slots explícitos; equipamento exige `player.equipment.read`, slots exigem `player.inventory.slot` |
| Loot | `ctx.server.drop_item(item, x, y, z, count)` | Exige `entity.spawn`; 1 a 4096 itens por chamada, divididos em stacks |
| Efeitos de mundo | `ctx.server.explode(...)`, `ctx.server.strike_lightning(...)` | Permissões/capabilities próprias; força `> 0` e `<= 8`, sem fogo e sem destruição por padrão |
| Eventos | `mod.on("block_broken", callback)` | Quebra iniciada por jogador, global para ids vanilla e declarativos; `false` cancela |

A documentação oficial do Fabric trata Game Rules como configurações específicas do mundo que controlam, entre outros exemplos, nascimento de monstros e passagem do tempo [1]. Por isso o MineLoader expõe uma whitelist própria em vez de entregar um mapa livre de propriedades internas.

## Estado de bloco, regras e dificuldade

O estado é transportado como texto para esconder diferenças de mappings e tipos Java. Um mod pode ler `facing`, `open`, `waterlogged`, `powered` ou `axis` quando a propriedade existe naquele bloco, e pode escrever uma alteração parcial sem reenviar o estado completo.

```lua
local state = ctx.server.block_state(x, y, z)
if state.id == "minecraft:oak_door" and state.properties.open == "false" then
    ctx.server.set_block_state(x, y, z, {open = "true"})
end
```

`set_block_state` não troca o bloco, não cria propriedades e não transforma um valor inválido num estado aproximado. O bridge traduz o texto para o tipo real da plataforma e recusa nomes ou valores desconhecidos. A API é adequada para portas, escadas, blocos direcionais, máquinas e conexões, não para expor o objecto inteiro do jogo.

As Game Rules usam `ctx.server.game_rule(name)` e `ctx.server.set_game_rule(name, value)`. A lista inclui regras comuns de clima, luz, spawning, drops, dano, raids, sono, mensagens e ticks, além de `spawn_radius`, `max_entity_cramming`, `players_sleeping_percentage`, `snow_accumulation_height` e `spawn_chunk_radius`. O nome da regra é validado no core e a whitelist é declarada em `GameBridge.GAME_RULES`.

A dificuldade aceita apenas `peaceful`, `easy`, `normal` e `hard`. Como é uma configuração global do mundo, a permissão `world.write` deve ser tratada como poder administrativo. Um script que usa uma alteração temporária deve guardar e restaurar o valor; a API não cria uma cópia de dificuldade por jogador.

## “Mapa” são três problemas diferentes

“Controle do mapa” pode significar várias coisas e não deve virar uma API ambígua.

| Significado | Estado no MineLoader | Próximo passo seguro |
|---|---|---|
| Consultar o mundo físico | Já existe: bloco, estado, bioma, luz, altura, clima e redstone | Completar eventos e raycast avançado |
| Navegar | Posição, `looking_at`, teleporte e dimensão do jogador já existem | Teleporte entre dimensões com contrato explícito |
| Cartografia | Waypoints próprios ainda não existem | Criar marcadores do MineLoader, não integração obrigatória |
| Mapa explorado vanilla | Ainda não é exportado | Estudar leitura/escrita limitada de mapas e marcadores |
| JourneyMap/Xaero | Não é contrato do loader | Não acoplar o núcleo a mods externos |

Uma futura API de waypoints pode usar nomes como `map.marker_add`, `map.marker_remove` e `map.markers`, com nome, cor, dimensão e posição serializáveis. Ela deve continuar útil sem JourneyMap, Xaero ou outro cliente instalado.

Dimensões novas, portais e worldgen são uma etapa separada. Criar uma dimensão exige definir regras de céu, bioma, geração, altura, respawn e acesso. Não vale prometer dimensão declarativa antes de existir um schema completo e GameTests para criação de mundo novo.

## Próximas APIs de maior valor

### Prioridade P0: mundo vivo

| Capability | API / próxima etapa | Motivo | Limite obrigatório |
|---|---|---|---|
| Drops no mundo | `ctx.server.drop_item(item, x, y, z, count)` | Recompensas, máquinas e processos customizados | 1–4096 itens por chamada; exige `entity.spawn` |
| Evento de quebra | `mod.on("block_broken", callback)` | Redes e máquinas reagem sem polling | Só quebra iniciada por jogador; snapshot de id/posição/variante e `false` cancela |
| Explosão | `ctx.server.explode(x, y, z, force[, breakBlocks])` | Magia, bombas, máquinas e bosses | Coordenadas limitadas; força `> 0` e `<= 8`; fogo desligado; `false` por padrão |
| Raio | `ctx.server.strike_lightning(x, y, z)` | Tempestades e eventos especiais | Coordenadas finitas/limitadas e permissão própria |

### Prioridade P1: jogador e máquinas

Efeitos activos, postura, vetor de movimento, equipamento e slots do jogador estão disponíveis como
snapshots ou substituições explícitas. Para inventários de máquinas ainda falta um contrato de
transferência por face e filtros de inserção; fluidos e energia devem usar unidades do próprio loader,
não `FluidStack`, `IItemHandler` ou capabilities.

| Capability | API | Uso |
|---|---|---|
| Efeitos activos | `ctx.player.effects()` | Status, cura, progressão e interfaces |
| Equipamento | `ctx.player.equipment()` | Classes, set bônus e protecção sem `ItemStack` |
| Postura e movimento | `ctx.player.movement()` | Agachar, correr, voar, nadar e verificar velocidade |
| Slot do jogador | `inventory_slot`, `set_inventory_slot` | Kits, filtros e ferramentas; `0..63`, limpeza com `0` |
| Transferência por face | `container.insert(..., face)` e `extract(..., face)` | Funis, tubos e máquinas direccionais |
| Fluido/energia | Unidades próprias do loader | Automação portável sem classes de plataforma |

## Conteúdo natural e cliente

Worldgen, fluidos, biomas novos, encantamentos e tags composicionais são importantes para deixar de ser apenas um sistema de blocos e scripts, mas são sensíveis à versão. Worldgen deve começar por um vocabulário fechado para features, colocação, altura e bioma; biomas completos e dimensões novas não devem entrar por atalhos.

Na frente visual ainda há trabalho consciente: OBJ está desactivado em 1.21.4, modelos e skins de entidades usam fallback vanilla, cores de spawn egg e reparação estão degradadas, e partículas NeoForge 1.21.4 continuam pendentes conforme a matriz. GameTests de servidor não provam pixels, modelos, telas client-side, shaders ou a qualidade visual de uma textura.

## Eventos que completam os controles

Funções directas são insuficientes sem eventos que entreguem o momento certo. `block_broken` já
está disponível nos quatro runtimes para quebras iniciadas por jogadores; os eventos seguintes ainda
são pendências.

| Evento | Estado | Valor |
|---|---|---|
| `block_broken` | Fechado | Reconfigurar máquinas e redes quando um jogador inicia a quebra; snapshot sem drops e `false` cancela |
| `world_loaded` | Futuro | Inicializar dados e tarefas por dimensão |
| `player_changed_dimension` | Futuro | Missões e regras dependentes da dimensão |
| `player_died` / `player_respawned` | Futuro | Penalidades, recompensas e narrativa |
| `item_consumed` / `item_broken` | Futuro | Poções, ferramentas e progressão |
| `explosion` | Futuro | Proteger áreas e reagir à destruição |
| `chunk_loaded` / `chunk_unloaded` | Futuro | Redes e caches espaciais com orçamento de custo |

Eventos de alta frequência, como chunk e inventário por tick, precisam de orçamento de execução e activação explícita no manifesto. Uma API que permite criar milhares de entidades, drops ou partículas por tick pode derrubar o servidor mesmo sem escapar da JVM.

## Ordem recomendada

A sequência que maximiza utilidade sem aumentar demais o risco agora deixa estado de bloco, regras,
dificuldade, drop de itens, efeitos, movimento, equipamento, slots, quebra global, explosão, raio e
scheduler recorrente fechados. O próximo bloco é transferência por face, fluidos e energia; por fim
waypoints, teleporte entre dimensões e worldgen limitado. Networking declarativo deve
usar payloads pequenos e versionados, schema fechado, direcção explícita, limite de tamanho e validação
no servidor [2].

A 1.21.4 só deve ser promovida de experimental para compatível quando OBJ, renderização declarada de entidades, formato de receitas e as capacidades degradadas forem validados. Compilar não é o mesmo que ter paridade visual.

## Referências

[1]: https://docs.fabricmc.net/develop/game-rules "Fabric Documentation — Game Rules"

[2]: https://docs.fabricmc.net/develop/networking "Fabric Documentation — Networking"

[3]: https://docs.neoforged.net/docs/1.20.6/datastorage/capabilities/ "NeoForge Documentation — Capabilities"

[4]: https://docs.neoforged.net/docs/items/datacomponents/ "NeoForge Documentation — Data Components"

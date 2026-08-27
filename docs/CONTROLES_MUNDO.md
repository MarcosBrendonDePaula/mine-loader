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
| Automação | `redstone_signal`, `schedule_block` | A fila de ticks é a do jogo e só aceita bloco declarativo |

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

| Capability | API futura | Motivo | Limite obrigatório |
|---|---|---|---|
| Drops no mundo | `world.drop_item(id, quantidade, x, y, z)` | Recompensas, máquinas e processos customizados | Quantidade, frequência e raio limitados |
| Evento de quebra | `block_broken` com posição, id, jogador e drops | Redes e máquinas reagem sem polling | Contexto capturado antes de o bloco desaparecer |
| Explosão | `world.explode(x, y, z, força, modo)` | Magia, bombas, máquinas e bosses | Força, área e frequência limitadas |
| Raio | `world.strike_lightning(x, y, z)` | Tempestades e eventos especiais | Frequência e permissão específicas |

### Prioridade P1: jogador e máquinas

Depois entram efeitos ativos, armadura equipada, postura, vetor de movimento, slots do jogador e partículas direcionadas. Para inventários de máquinas ainda falta um contrato de transferência por face e filtros de inserção; fluidos e energia devem usar unidades do próprio loader, não `FluidStack`, `IItemHandler` ou capabilities.

| Capability | API futura | Uso |
|---|---|---|
| Efeitos activos | `player.effects()` | Status, cura, progressão e interfaces |
| Equipamento | `player.equipment()` | Classes, set bônus e protecção |
| Postura | `player.pose()` | Agachar, correr, voar, nadar |
| Movimento | `player.velocity()` | Zonas de velocidade, parkour e física declarativa |
| Slot do jogador | `player.inventory_slot(n)` e escrita validada | Kits, filtros e ferramentas |
| Transferência por face | `container.insert(..., face)` e `extract(..., face)` | Funis, tubos e máquinas direccionais |
| Fluido/energia | Unidades próprias do loader | Automação portável sem classes de plataforma |

## Conteúdo natural e cliente

Worldgen, fluidos, biomas novos, encantamentos e tags composicionais são importantes para deixar de ser apenas um sistema de blocos e scripts, mas são sensíveis à versão. Worldgen deve começar por um vocabulário fechado para features, colocação, altura e bioma; biomas completos e dimensões novas não devem entrar por atalhos.

Na frente visual ainda há trabalho consciente: OBJ está desactivado em 1.21.4, modelos e skins de entidades usam fallback vanilla, cores de spawn egg e reparação estão degradadas, e partículas NeoForge 1.21.4 continuam pendentes conforme a matriz. GameTests de servidor não provam pixels, modelos, telas client-side, shaders ou a qualidade visual de uma textura.

## Eventos que completam os controles

Funções directas são insuficientes sem eventos que entreguem o momento certo. A ordem de maior valor é:

| Evento futuro | Valor |
|---|---|
| `block_broken` | Reconfigurar máquinas e redes quando um bloco desaparece |
| `world_loaded` | Inicializar dados e tarefas por dimensão |
| `player_changed_dimension` | Missões e regras dependentes da dimensão |
| `player_died` / `player_respawned` | Penalidades, recompensas e narrativa |
| `item_consumed` / `item_broken` | Poções, ferramentas e progressão |
| `explosion` | Proteger áreas e reagir à destruição |
| `chunk_loaded` / `chunk_unloaded` | Redes e caches espaciais com orçamento de custo |

Eventos de alta frequência, como chunk e inventário por tick, precisam de orçamento de execução e activação explícita no manifesto. Uma API que permite criar milhares de entidades, drops ou partículas por tick pode derrubar o servidor mesmo sem escapar da JVM.

## Ordem recomendada

A sequência que maximiza utilidade sem aumentar demais o risco é estado de bloco, regras, dificuldade, drops e quebra; depois explosão, raio e jogador completo; em seguida transferência por face, fluidos e energia; por fim waypoints, teleporte entre dimensões e worldgen limitado. Networking declarativo deve usar payloads pequenos e versionados, schema fechado, direcção explícita, limite de tamanho e validação no servidor [2].

A 1.21.4 só deve ser promovida de experimental para compatível quando OBJ, renderização declarada de entidades, formato de receitas e as capacidades degradadas forem validados. Compilar não é o mesmo que ter paridade visual.

## Referências

[1]: https://docs.fabricmc.net/develop/game-rules "Fabric Documentation — Game Rules"

[2]: https://docs.fabricmc.net/develop/networking "Fabric Documentation — Networking"

[3]: https://docs.neoforged.net/docs/1.20.6/datastorage/capabilities/ "NeoForge Documentation — Capabilities"

[4]: https://docs.neoforged.net/docs/items/datacomponents/ "NeoForge Documentation — Data Components"

# API estável para mods declarativos

O MineLoader não tenta exportar todas as classes de Fabric, NeoForge ou Minecraft. Ele exporta **capacidades de gameplay** com nomes e dados estáveis, enquanto cada bridge traduz essa intenção para a versão em execução. O mod Lua depende deste documento e do manifesto; não depende de mappings, mixins, `IItemHandler`, `Storage<ItemVariant>`, `AttachmentType` ou classes internas.

A seleção segue padrões comuns das documentações oficiais: eventos são hooks para casos recorrentes e compatibilidade entre mods [1], networking mantém o estado sincronizado entre cliente e servidor [2], registros são a base para itens, blocos e entidades [3], capabilities separam comportamento de implementação [4] e dados persistentes podem ser associados a entidades, chunks, block entities e níveis [5]. Receitas, loot, tags e worldgen também fazem parte do ecossistema normal de modding [6] [7].

## Contrato disponível

A mesma API Lua é carregada nos quatro runtimes mantidos: Fabric 1.21.1, Fabric 1.21.4, NeoForge 1.21.1 e NeoForge 1.21.4.

| API Lua | Permissão | Retorno/efeito | Matriz |
|---|---|---|---|
| `ctx.server.redstone_signal(x, y, z)` | `world.read` | Potência recebida, de `0` a `15` | quatro runtimes |
| `ctx.server.block_state(x, y, z)` | `world.read` | `{ id = "mod:bloco", properties = { nome = "valor" } }` | quatro runtimes |
| `ctx.server.set_block_state(x, y, z, properties)` | `world.write` | `true` quando o mundo aceita a alteração | quatro runtimes |
| `ctx.server.game_rule(name)` | `world.read` | Texto estável: `true`, `false` ou inteiro | quatro runtimes |
| `ctx.server.set_game_rule(name, value)` | `world.write` | Altera uma regra da whitelist | quatro runtimes |
| `ctx.server.difficulty()` | `world.read` | `peaceful`, `easy`, `normal` ou `hard` | quatro runtimes |
| `ctx.server.set_difficulty(value)` | `world.write` | Altera a dificuldade, se o mundo não estiver bloqueado | quatro runtimes |
| `ctx.server.time_of_day()` | `world.read` | Hora em ticks, de `0` a `23999` | quatro runtimes |
| `ctx.server.set_time_of_day(value)` | `world.write` | Altera a hora sem rebobinar os dias já passados | quatro runtimes |
| `ctx.server.weather()` | `world.read` | `clear`, `rain` ou `thunder` | quatro runtimes |
| `ctx.server.set_weather(kind, duration)` | `world.write` | Altera o clima por uma duração em ticks | quatro runtimes |
| `ctx.player.data.{get,has,set,remove}` | `player.read`/`player.modify` | Dados persistentes no escopo jogador + mod | core e runtimes |
| `ctx.player.effects()` | `player.read` | Lista snapshot de efeitos activos: id, duração, amplificador e flags visuais | quatro runtimes |
| `ctx.player.movement()` | `player.read` | Snapshot de velocidade, chão, agachamento, sprint, natação, voo e elytra | quatro runtimes |
| `ctx.player.equipment()` | `player.read` + `player.equipment.read` | Snapshot de `main_hand`, `off_hand`, `head`, `chest`, `legs` e `feet`, sempre com `{item, count}` | quatro runtimes |
| `ctx.player.inventory_slot(slot)` | `player.inventory` + `player.inventory.slot` | Snapshot de um slot lógico `0..63`; vazio é `minecraft:air` com quantidade `0` | quatro runtimes |
| `ctx.player.set_inventory_slot(slot, item, count[, itemSpec])` | `player.inventory` + `player.inventory.slot` | Substitui um slot; `count = 0` limpa-o e a bridge ainda respeita o stack máximo real do item | quatro runtimes |
| `ctx.server.drop_item(item, x, y, z, count)` | `entity.spawn` | Cria itens soltos em stacks, devolvendo a quantidade criada; máximo 4096 por chamada | quatro runtimes |
| `ctx.server.explode(x, y, z, force[, breakBlocks])` | `world.explode` | Explosão server-side; força `> 0` e `<= 8`, fogo sempre desactivado e blocos intactos por padrão | quatro runtimes |
| `ctx.server.strike_lightning(x, y, z)` | `world.lightning` | Convoca um raio server-side em coordenadas finitas e limitadas | quatro runtimes |
| `items[].food` no `mod.json` | `registry.item.food` | Torna um item declarativo com nutrição, saturação e `always_edible` | quatro runtimes |
| `items[].fuel_burn_time` no `mod.json` | `registry.item.fuel` | Regista tempo de queima em ticks para o item declarado | quatro runtimes |
| `mod.every(ticks, callback)` / `mod.cancel(id)` | `scheduler.every` | Tarefa recorrente com ID lógico; `false` no callback ou cancelamento encerra | core e runtimes |
| `mod.keybind(id, callback)` | `client.input.register` | Callback server-side para tecla declarada no manifesto | quatro runtimes |
| `mod.camera(id, definição)` | `client.camera.register` e `client.camera.virtual` | Câmera lógica ortográfica, publicada como catálogo S2C versionado; o bridge gere a textura | quatro runtimes |
| `commands` no JSON + `mod.command`/`mod.command_extend` no Lua | `server.command.register` e `server.command.schema` | Árvore tipada, autocomplete e `ctx.command.arguments`, com merge que recusa conflitos | quatro runtimes |

A hora e o clima **já fazem parte da API**; não são uma lacuna futura. A separação entre leitura e escrita é deliberada: consultar um mundo não deve conceder a um mod a capacidade de alterar o relógio, o clima ou as regras administrativas.

Hotkeys usam o contrato `client.input.keybind` `1.0.0`. A tecla e os modificadores são dados declarados no `mod.json`; o cliente detecta a transição e envia apenas o id qualificado, enquanto a função associada continua a executar no servidor. O formato completo está em [HOTKEYS.md](HOTKEYS.md).

## Efeitos de mundo

`ctx.server.explode(x, y, z, force[, breakBlocks])` é uma operação server-side deliberadamente pequena. As coordenadas são finitas e limitadas pelo mesmo orçamento de mundo do core; a força deve ser maior que zero e no máximo `8`. O quinto argumento é opcional e vale `false`, portanto um mod precisa pedir explicitamente `true` para destruir blocos. A API não acende fogo e não expõe fonte, modo de interacção ou objectos da plataforma.

```lua
ctx.server.explode(ctx.player.position()[1], 70, ctx.player.position()[3], 2.5)
ctx.server.explode(120, 64, -30, 1.5, true) -- destruição explícita
```

`ctx.server.strike_lightning(x, y, z)` cria um raio na posição indicada. Ambas as operações exigem uma permissão própria e a capability correspondente; `requires.capabilities` é negociação explícita do contrato, não um bypass de `permissions`. O loader não implementa neste evento uma política de frequência por mod além dos limites da chamada: scripts devem agendar efeitos com parcimónia e evitar loops por tick.

## Evento global de quebra de bloco

`mod.on("block_broken", callback)` recebe a quebra iniciada por um jogador, antes de o jogo a concluir, com `ctx.block.id`, `ctx.block.x`, `ctx.block.y`, `ctx.block.z`, `ctx.block.variant`, `ctx.block.variant_count` e `ctx.player`. O callback retorna `false` para cancelar a quebra; qualquer outro retorno permite-a. O evento é global: o id pode ser vanilla ou de outro mod, e não fica limitado ao namespace do mod que registou o callback. O contrato v1 não inclui face, mão, `ItemStack` nem drops: neste ponto a quebra ainda não foi concluída e os drops não são conhecidos pelo core.

O mesmo nome continua a suportar `behavior.on_broken` de blocos declarativos. Para evitar dois disparos, uma quebra de bloco declarativo tratada por `on_broken` não chama também o callback global do próprio mod. O evento cobre somente quebra iniciada por jogador. Não é emitido para explosões, pistões, substituições de script ou remoções indirectas.

```lua
mod.on("block_broken", function(ctx)
    if ctx.block.id == "minecraft:iron_ore" then
        ctx.player.send_message("Esta área está protegida")
        return false
    end
end)
```

## Estado de bloco

`block_state` responde com um snapshot agnóstico. O Lua recebe apenas o identificador do bloco e um mapa de propriedades convertidas para texto. Nenhuma instância `BlockState`, `Property`, mundo ou objeto Java atravessa a fronteira.

```lua
local state = ctx.server.block_state(x, y, z)
if state.id == "minecraft:oak_door" then
    ctx.log.info("porta aberta: " .. tostring(state.properties.open))
    ctx.log.info("direcao: " .. state.properties.facing)
end
```

`set_block_state` altera somente propriedades que já pertencem ao bloco naquela posição. Ele não troca o bloco, não cria uma propriedade nova e não aceita um nome ou valor desconhecido silenciosamente. O mapa pode ser parcial: para abrir uma porta não é preciso reenviar `facing`, `hinge`, `half` e `powered`.

```lua
ctx.server.set_block_state(x, y, z, {
    open = "true",
    facing = "south"
})
```

O valor é textual porque o nome e o conjunto de valores de uma propriedade mudam de classe entre mappings, mas a intenção é a mesma. O bridge traduz o texto para o tipo real da versão, por exemplo uma propriedade booleana, direcional ou enumerada. Um valor inválido produz erro de bridge; não há fallback para um estado aproximado.

## Game Rules com whitelist

Game Rules são configurações do mundo, não um mapa livre para qualquer chave. O contrato usa o conjunto fechado abaixo para manter a mesma superfície nas quatro combinações e impedir acesso acidental a regras de comando, debug ou específicas de uma versão.

| Tipo | Nomes estáveis |
|---|---|
| Booleano | `do_fire_tick`, `mob_griefing`, `keep_inventory`, `do_mob_spawning`, `do_mob_loot`, `do_tile_drops`, `do_entity_drops`, `natural_regeneration`, `do_daylight_cycle`, `do_weather_cycle`, `send_command_feedback`, `announce_advancements`, `disable_raids`, `do_insomnia`, `do_immediate_respawn`, `drowning_damage`, `fall_damage`, `fire_damage`, `freeze_damage`, `do_patrol_spawning`, `do_trader_spawning`, `do_warden_spawning`, `forgive_dead_players`, `universal_anger`, `do_vines_spread`, `show_death_messages` |
| Inteiro | `random_tick_speed`, `spawn_radius`, `max_entity_cramming`, `players_sleeping_percentage`, `snow_accumulation_height`, `spawn_chunk_radius` |

A leitura devolve sempre texto para não criar dois formatos de retorno entre Lua e Java. A escrita aceita texto, número ou booleano simples no Lua, mas o bridge confirma o tipo real da regra. Valores inteiros negativos ou excessivamente grandes são recusados pelo contrato de segurança. A permissão `world.write` deve ser tratada como poder administrativo quando o mod altera regras globais do mundo.

```lua
local ciclo = ctx.server.game_rule("do_daylight_cycle")
if ciclo == "true" then
    ctx.server.set_game_rule("do_daylight_cycle", false)
end

local dificuldade = ctx.server.difficulty()
if dificuldade == "peaceful" then
    ctx.server.set_difficulty("normal")
end
```

Apenas `peaceful`, `easy`, `normal` e `hard` são aceitos para dificuldade. Se o mundo tiver a dificuldade bloqueada, `set_difficulty` recusa a operação em vez de contornar a configuração. A alteração é global ao servidor de teste ou mundo em execução; um mod deve restaurar valores temporários e não disputar a configuração com outros mods.

## Equipamento e slots do jogador

`ctx.player.equipment()` devolve uma tabela snapshot com os seis nomes lógicos de equipamento. Cada campo tem apenas `item` e `count`; um campo vazio é sempre `minecraft:air` e `0`. O Lua não recebe índices de armadura, `ItemStack`, componentes ou uma referência viva ao jogador.

`ctx.player.inventory_slot(slot)` lê um índice do inventário interno através de uma faixa comum de `0` a `63`; cada bridge valida o tamanho real do jogador e recusa índices que não existam naquela plataforma. `ctx.player.set_inventory_slot(slot, item, count[, itemSpec])` aceita quantidade de `0` a `64`; quantidade zero limpa o slot e pode usar `nil` como item. Para quantidade positiva, o identificador deve existir e a bridge recusa uma quantidade acima do stack máximo real do item. `itemSpec`, quando usado, é o mesmo vocabulário declarativo de itens da API de entrega.

```lua
local gear = ctx.player.equipment()
if gear.main_hand.item == "minecraft:iron_pickaxe" then
    local slot = ctx.player.inventory_slot(5)
    ctx.player.set_inventory_slot(5, slot.item, slot.count + 1)
end
```

As três operações exigem as permissões `player.read` ou `player.inventory` e as capabilities `player.equipment.read` e `player.inventory.slot`. A escrita substitui o slot inteiro; não tenta inserir, empilhar ou deslocar itens para outros slots.

## Redstone e dados persistentes

A potência redstone foi modelada como leitura do sinal que chega à posição. Ela não finge que qualquer bloco pode emitir sinal dinâmico: emissão depende do conteúdo registrado e da semântica do bloco. Essa escolha permite que máquinas declarativas reajam a alavancas, comparadores, trilhos e blocos de outros mods sem expor a API interna de nenhuma plataforma.

Os dados do jogador são separados de `mod.state`. O primeiro pertence a um jogador específico; o segundo pertence ao mod como um todo. O MineLoader guarda os dados em um ficheiro escopado, `<mod>.players.json`, com troca atômica. O script nunca escolhe o caminho do ficheiro. As chaves aceitam somente `[A-Za-z0-9_.-]` e no máximo 64 caracteres; os valores aceitam apenas tipos que sobrevivem a JSON, com profundidade limitada a 32 níveis.

```lua
mod.on("player_joined", function(ctx)
    local visitas = ctx.player.data.get("visitas", 0)
    ctx.player.data.set("visitas", visitas + 1)
    ctx.player.send_message("Visita número " .. (visitas + 1))
end)
```

## Dependências de contrato no manifesto

O manifesto usa `requires` para declarar o contrato mínimo do runtime. As versões são do MineLoader, não do Minecraft: as quatro combinações mantidas podem satisfazer `world: 1.0.0` mesmo usando bridges e mappings diferentes.

```json
{
  "requires": {
    "domains": {
      "world": "1.0.0",
      "player": "1.0.0"
    },
    "capabilities": {
      "world.block_state.read": "1.0.0",
      "world.redstone.read": "1.0.0",
      "world.explode": "1.0.0",
      "registry.item.food": "1.0.0",
      "registry.item.fuel": "1.0.0",
      "player.equipment.read": "1.0.0",
      "player.inventory.slot": "1.0.0"
    }
  }
}
```

`domains` agrupa áreas da API; `capabilities` é mais preciso e identifica operações individuais. Todos os requisitos são obrigatórios nesta primeira versão. O loader valida-os antes de registar conteúdo ou executar Lua e recusa o mod se o nome for desconhecido, a versão mínima for superior ou a versão estiver malformada.

`requires` não substitui `dependencies`. `dependencies` aponta para outro mod, controla ordem de carga e autoriza `mod.require`; `requires` apenas negocia o perfil de APIs que o runtime já entrega.

## Comida e combustível declarativos

Itens independentes podem declarar propriedades de comida e combustível directamente no manifesto. A bridge traduz os mesmos dados para os componentes/propriedades da versão em execução; nenhum `FoodComponent`, `FoodProperties`, `FuelRegistry` ou `ItemStack` atravessa para o core ou para Lua. A configuração não altera itens vanilla nem itens registados por outro mod.

```json
{
  "items": [{
    "id": "racao",
    "name": "Ração",
    "food": {
      "nutrition": 6,
      "saturation": 0.8,
      "always_edible": true
    },
    "fuel_burn_time": 400
  }]
}
```

`food.nutrition` aceita de `0` a `20` pontos de fome; `food.saturation` aceita números finitos de `0` a `4`; e `food.always_edible` permite o consumo com a barra cheia. A primeira versão fecha a comida básica e não promete efeitos de poção, consumo rápido customizado ou conversão após consumo, porque esses campos não têm a mesma representação nos quatro alvos mantidos. `fuel_burn_time` aceita de `0` a `32767` ticks, em que `0` significa que o item não é combustível. Nesta versão, um item combustível não pode ser ferramenta ou armadura, embora possa também ser comida.

Não existe permissão adicional: registar conteúdo é parte da carga declarativa, como itens, ferramentas e armaduras já existentes. Um mod que precise garantir a presença da API pode declarar `registry.item.food: 1.0.0` e/ou `registry.item.fuel: 1.0.0` em `requires.capabilities`. A consulta Lua já existente `ctx.server.fuel_burn_time("mod:item")` devolve o tempo efectivo de queima depois de o registro estar pronto.

A separação entre dados comuns e detalhes de consumo segue a forma como Fabric modela comida através de FoodComponent e registra combustíveis por uma API de fuel [9] [10], enquanto NeoForge 1.21.1 usa FoodProperties em Item.Properties [11].

| Campo | Exemplo | Significado |
|---|---|---|
| `requires.domains` | `world: 1.0.0` | Exige o contrato inteiro de um domínio |
| `requires.capabilities` | `world.block_state.read: 1.0.0` | Exige uma operação específica |
| `dependencies` | `biblioteca_ui: 2.0.0` | Exige código de outro mod |

A lista canónica de domínios e capabilities vive em `RuntimeContract` no core. Um bridge pode mudar a implementação interna, mas não pode publicar um nome diferente para a mesma operação nem declarar uma versão maior sem alterar a semântica do contrato.

## Schemas de comandos

`commands` no `mod.json` é a forma estática e recomendada de declarar a árvore de um comando. O schema é uma árvore de literais e argumentos portáveis (`word`, `string`, `greedy_string`, `integer`, `double` e `boolean`). O bridge publica a árvore no dispatcher do Minecraft, por isso os literais e as sugestões estáticas aparecem no autocomplete e os limites numéricos são validados antes de o Lua correr.

O Lua associa o callback com `mod.command(nome, callback)`. Para comandos condicionais que só podem ser conhecidos durante a execução do entrypoint, `mod.command(nome, schema, callback)` continua disponível; `mod.command_extend(nome, schema)` pode acrescentar ramos a um comando estático. Se duas declarações descrevem o mesmo caminho com definições diferentes, o loader recusa a carga. O formato antigo `mod.command(nome, callback)` sem declaração no manifesto continua a publicar um `greedy_string` e permanece compatível.

Um mod que usa schema deve exigir `server.command.schema: 1.0.0` e manter `server.command.register`. O callback recebe `ctx.command.arguments`, `ctx.command.path` e `ctx.command.structured`, além de `ctx.args`, `ctx.argv` e `ctx.subcommand`.

O core valida a árvore antes de a instalar: cada nó declara exactamente um literal ou argumento, os identificadores e tipos são fechados, os limites numéricos são consistentes e há limites de 128 nós, 8 níveis, 32 sugestões por argumento e 64 caracteres por sugestão. Sugestões dinâmicas executadas por Lua ainda não fazem parte do contrato.

A especificação completa está em [COMMANDS.md](COMMANDS.md).

## Bibliotecas e require dinâmico

`mod.require("outro_mod")` só resolve ids declarados em `dependencies`. O bootstrap regista o catálogo de mods descobertos e o runtime pode compilar a biblioteca no momento da primeira chamada, caso ela ainda não tenha sido carregada pelo loop principal. Depois disso, a tabela exportada fica em cache e chamadas seguintes devolvem a mesma API.

```lua
local ui = mod.require("ui_lib")
local titulo = ui.formatar("olá")
```

A resolução sob demanda não procura ficheiros arbitrários nem instala código. Ela só usa mods já descobertos pelo loader e verifica a versão mínima declarada. Se a cadeia tentar voltar a um mod que ainda está a ser compilado, o runtime falha com a cadeia completa, por exemplo `mod_a -> mod_b -> mod_c -> mod_a`, em vez de recursar infinitamente ou deixar scripts parciais no mapa carregado.

Os ciclos são também recusados pelo resolver estático antes da execução quando aparecem directamente nos manifestos. A protecção em `mod.require` cobre ainda carga individual, recarga e qualquer caminho dinâmico que não tenha passado pela ordem normal de arranque.

## Regras de estabilidade

A API pública deve evoluir por adição, não por renomeação silenciosa. Uma função existente não pode mudar o formato do retorno numa versão do Minecraft. Quando uma plataforma não consegue oferecer a capability, o bridge deve recusar com `BridgeException` nomeando a operação, ou aplicar um fallback documentado; nunca deve retornar dados inventados.

O Lua recebe tabelas e escalares simples. Ele não recebe objetos Java, referências a mundos, entidades vivas, `ItemStack`, sockets ou callbacks de plataforma. Esse limite é deliberado: reduz a superfície de incompatibilidade e mantém o mod declarativo transportável entre versões.

| Tipo de evolução | Tratamento |
|---|---|
| Nova função sem quebrar as antigas | Pode entrar numa revisão menor do contrato |
| Novo campo opcional numa tabela de retorno | Deve ter fallback ausente/nulo bem documentado |
| Mudança de formato ou semântica | Exige versão nova do contrato e validação dos manifests |
| Capability inexistente em um runtime | Recusa explícita ou fallback documentado |
| API específica de uma plataforma | Fica dentro do bridge, não entra no core |
| Nova Game Rule | Só entra após existir nas quatro versões e ter tipo/limite comum |

## Mapa não é uma única capability

“Mapa” pode significar três coisas diferentes. A consulta física do mundo já inclui bloco, estado, bioma, luz, altura, clima e redstone. Navegação inclui posição, raycast, teleporte e dimensão. Cartografia inclui waypoints, marcadores próprios e uma imagem aérea client-side.

O elemento `map` oferece três modos. `server_cells` recebe uma grelha compacta de cores calculada pelo servidor; `client_topdown` é o modo legado sem catálogo; `client_camera` referencia uma câmera lógica qualificada, publicada pelo contrato `client.camera.virtual: 1.0.0`. Nos dois modos client-side, o bridge rasteriza uma imagem pequena a partir dos chunks disponíveis, sem uma segunda passagem 3D geral por frame. A especificação completa está em [MINIMAP.md](MINIMAP.md).

O MineLoader não deve acoplar o contrato a JourneyMap, Xaero ou outro mod de mapa. A câmera v1 não expõe framebuffer, OpenGL, `NativeImage`, `Identifier` ou nomes de textura ao Lua. Uma futura API geral de marcadores pode ser própria, por exemplo `map.marker_add`, `map.marker_remove` e `map.markers`, com nome, cor, dimensão e posição serializáveis. Isso permite mapas, cidades e missões sem transformar uma integração opcional em dependência de todos os mods.

Dimensões novas, portais e worldgen são outra etapa. Criar uma dimensão exige definir céu, bioma, geração, altura, respawn e acesso. Não é correto prometer `teleport_dimension` ou dimensão declarativa completa antes de existir um schema fechado e GameTests para mundos novos.

## Próximas APIs priorizadas

As APIs de `drop_item`, efeitos activos, movimento, tarefas recorrentes, quebra global, explosão, raio, equipamento, slots, comida declarativa e combustível fazem parte da expansão útil actual.
 O drop cria entidades limitadas; efeitos, movimento e equipamento devolvem snapshots neutros; slots têm escrita explícita e limitada; e `block_broken` só observa a acção directa de um jogador. A evolução seguinte deve focar eventos de mundo, transferência por face, fluidos e energia com unidades próprias do loader, não `FluidStack`, `IItemHandler` ou classes equivalentes. Waypoints, teleporte entre dimensões e worldgen limitado devem nascer como contratos próprios, não como aliases de APIs internas.

Networking declarativo deve usar payloads pequenos e versionados, schema fechado, direção explícita, limite de tamanho e validação no servidor. Expor `send_packet` com bytes arbitrários seria incompatível com a sandbox [2]. Data components devem ser expostos somente como dados imutáveis e portáveis para itens declarados, não como o mapa inteiro de componentes internos [8].

## Validação

A expansão é considerada válida quando o core passa a suíte JUnit, os quatro bridges compilam e os GameTests reais continuam verdes. O pacote actual tem testes de contrato para estado de bloco, Game Rules, dificuldade, permissões, limites de explosão/raio, snapshots de equipamento, slots, limpeza e cancelamento global de quebra; cada runtime também exercita operações de bridge dentro de um servidor Minecraft real. Estes GameTests provam integração server-side e não pixels, FPS, autocomplete visual ou qualidade de renderização.

A validação completa da matriz é:

```bash
./gradlew :core:test
./gradlew compileAllRuntimes
./gradlew testAllRuntimes
./gradlew gameTestAllRuntimes
./gradlew checkAllRuntimes
```

A implementação não promove automaticamente capabilities visuais experimentais como OBJ ou renderização customizada. Essas áreas continuam discriminadas em `docs/COMPATIBILIDADE.md`.

## Referências

[1]: https://docs.fabricmc.net/develop/events "Fabric Documentation — Events"

[2]: https://docs.fabricmc.net/develop/networking "Fabric Documentation — Networking"

[3]: https://docs.neoforged.net/docs/concepts/registries/ "NeoForge Documentation — Registries"

[4]: https://docs.neoforged.net/docs/1.20.6/datastorage/capabilities/ "NeoForge Documentation — Capabilities"

[5]: https://docs.neoforged.net/docs/datastorage/attachments/ "NeoForge Documentation — Data Attachments"

[6]: https://docs.fabricmc.net/develop/recipes "Fabric Documentation — Recipes"

[7]: https://docs.fabricmc.net/develop/data-generation/features "Fabric Documentation — Feature Generation"

[8]: https://docs.neoforged.net/docs/items/datacomponents "NeoForge Documentation — Data Components"

[9]: https://docs.fabricmc.net/develop/items/food "Fabric Documentation — Food Items"

[10]: https://docs.fabricmc.net/develop/items/first-item "Fabric Documentation — Creating Your First Item"

[11]: https://docs.neoforged.net/docs/1.21.1/items/ "NeoForge Documentation 1.21.1 — Items"

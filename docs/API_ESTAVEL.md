# API estável para mods declarativos

O MineLoader não tenta exportar todas as classes de Fabric, NeoForge ou Minecraft. Ele exporta **capacidades de gameplay** com nomes e dados estáveis, enquanto cada bridge traduz essa intenção para a versão em execução. O mod Lua depende deste documento e do manifesto; não depende de mappings, mixins, `IItemHandler`, `Storage<ItemVariant>`, `AttachmentType` ou classes internas.

A seleção segue os padrões que aparecem nas documentações oficiais dos loaders: eventos são hooks para casos comuns e compatibilidade entre mods [1], networking mantém o estado sincronizado entre cliente e servidor [2], registros são a base para itens/blocos/entidades [3], capabilities separam comportamento de implementação [4] e dados persistentes podem ser associados a entidades, chunks, block entities e níveis [5]. Recursos como receitas, loot, tags e worldgen também fazem parte do ecossistema normal de modding [6] [7].

## Capacidades adicionadas nesta versão

| API Lua | Permissão | Contrato | Implementação |
|---|---|---|---|
| `ctx.server.redstone_signal(x, y, z)` | `world.read` | Retorna a potência redstone recebida, entre `0` e `15` | Fabric 1.21.1/1.21.4 e NeoForge 1.21.1/1.21.4 |
| `ctx.player.data.get(chave, padrão)` | `player.read` | Lê um valor persistente associado ao jogador e ao mod | Core, serialização JSON atômica |
| `ctx.player.data.has(chave)` | `player.read` | Indica se a chave existe | Core |
| `ctx.player.data.set(chave, valor)` | `player.modify` | Grava texto, número, booleano ou tabela serializável | Core, autosave e salvamento no desligamento |
| `ctx.player.data.remove(chave)` | `player.modify` | Remove a chave e retorna se ela existia | Core |

A potência redstone foi modelada como leitura do sinal que chega à posição. Ela não finge que qualquer bloco pode emitir sinal dinâmico: emissão depende do conteúdo registrado e da semântica do bloco. Essa escolha evita uma API enganosa e permite que máquinas declarativas reajam a alavancas, comparadores, trilhos e blocos de outros mods.

Os dados do jogador são separados de `mod.state`. O primeiro pertence a um jogador específico; o segundo pertence ao mod como um todo. O MineLoader guarda os dados em um ficheiro escopado, `<mod>.players.json`, com troca atômica. O script nunca escolhe o caminho do ficheiro. As chaves aceitam somente `[A-Za-z0-9_.-]` e no máximo 64 caracteres; os valores aceitam apenas tipos que sobrevivem a JSON, com profundidade limitada a 32 níveis.

## Exemplos

Um mod de máquina pode reagir a redstone no evento `tick`:

```lua
mod.on("tick", function(ctx)
    if ctx.server.redstone_signal(10, 64, -3) > 0 then
        -- A máquina recebeu sinal e pode avançar o processo declarado.
    end
end)
```

A forma mais clara para testar o sinal é guardá-lo e reagir somente quando muda:

```lua
local ultimo_sinal = -1

mod.on("tick", function(ctx)
    local sinal = ctx.server.redstone_signal(10, 64, -3)
    if sinal ~= ultimo_sinal then
        ultimo_sinal = sinal
        ctx.server.broadcast("Sinal da máquina: " .. sinal)
    end
end)
```

Um mod de progressão pode guardar visitas sem usar NBT, attachments ou classes de entidade:

```lua
mod.on("player_joined", function(ctx)
    local visitas = ctx.player.data.get("visitas", 0)
    ctx.player.data.set("visitas", visitas + 1)
    ctx.player.send_message("Esta é a sua visita número " .. (visitas + 1))
end)
```

A API também permite distinguir ausência de valor de um valor falso ou zero:

```lua
if not ctx.player.data.has("tutorial_concluido") then
    ctx.player.data.set("tutorial_concluido", false)
end

ctx.player.data.remove("chave_temporaria")
```

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

## Próximas APIs priorizadas

A pesquisa mostra que networking, data components, recursos/datapacks e worldgen são áreas frequentes. Elas não foram adicionadas como simples aliases porque precisam de contratos mais cuidadosos.

**Networking declarativo** deve vir como payloads pequenos e versionados, com schema fechado, limite de tamanho, direção explícita e validação no servidor. A documentação Fabric reforça que o servidor deve validar o conteúdo recebido; expor `send_packet` com bytes arbitrários seria incompatível com a sandbox [2].

**Data components** devem ser expostos somente como dados imutáveis e portáveis para itens declarados, não como o mapa inteiro de componentes internos. A documentação NeoForge recomenda valores imutáveis, frequentemente records, e codecs para persistência e rede [8].

**Worldgen** deve começar por um vocabulário fechado para features, colocação e filtros de bioma. Fabric separa configured features, placed features e biome modifications [7], enquanto NeoForge trata worldgen como registros dinâmicos/datapack [3]. A diferença torna essa capability adequada para uma etapa própria, com GameTests de geração e verificação de datapack.

**Receitas, loot, tags e advancements** devem ganhar operações declarativas adicionais quando houver um formato comum testável nas quatro combinações atuais. O MineLoader já consulta receitas, drops e tags; a próxima evolução deve ampliar tipos e composição sem copiar codecs específicos no core.

## Validação

A expansão é considerada válida quando o core passa a sua suíte JUnit, os quatro bridges compilam e os GameTests existentes continuam verdes. A leitura redstone possui teste de contrato no core e implementação real nos quatro bridges. A persistência de jogador possui teste de reinicialização do runtime, usando o mesmo UUID em duas instâncias.

A validação completa da matriz continua sendo:

```bash
./gradlew :core:test
./gradlew compileAllRuntimes
./gradlew testAllRuntimes
./gradlew gameTestAllRuntimes
./gradlew checkAllRuntimes
```

A implementação não promove automaticamente capabilities visuais experimentais como OBJ ou renderização customizada de entidades. Essas áreas continuam discriminadas em `docs/COMPATIBILIDADE.md`.

## Referências

[1]: https://docs.fabricmc.net/develop/events "Fabric Documentation — Events"

[2]: https://docs.fabricmc.net/develop/networking "Fabric Documentation — Networking"

[3]: https://docs.neoforged.net/docs/concepts/registries/ "NeoForge Documentation — Registries"

[4]: https://docs.neoforged.net/docs/1.20.6/datastorage/capabilities/ "NeoForge Documentation — Capabilities"

[5]: https://docs.neoforged.net/docs/datastorage/attachments/ "NeoForge Documentation — Data Attachments"

[6]: https://docs.neoforged.net/docs/resources/ "NeoForge Documentation — Resources"

[7]: https://docs.fabricmc.net/develop/data-generation/features "Fabric Documentation — Feature Generation"

[8]: https://docs.neoforged.net/docs/items/datacomponents "NeoForge Documentation — Data Components"

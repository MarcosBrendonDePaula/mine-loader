# Compatibilidade por runtime

A promessa do MineLoader é que o mod declarativo dependa do contrato do loader, não das classes internas do Minecraft. Esta matriz mostra onde essa promessa foi realmente verificada. **“Sim” significa que a capability compilou e foi exercitada pelo runtime; “degradado” significa que existe uma tradução parcial declarada; “não” significa que o bridge recusa ou desativa a capability.**

## Matriz plataforma × versão × capability

| Capability | Fabric 1.21.1 | Fabric 1.21.4 | NeoForge 1.21.1 | NeoForge 1.21.4 |
|---|---:|---:|---:|---:|
| Core/API declarativa comum | sim | sim | sim | sim |
| Compilação server/common | sim | sim | sim | sim |
| Compilação client | sim | sim | n/a no source set separado | n/a no source set separado |
| Descoberta de manifestos e scripts Lua | sim | sim | sim | sim |
| Registro de blocos e itens | sim | sim | sim | sim |
| Registro de entidades e ovos de criação | sim | sim | sim | sim |
| Resource pack gerado | sim | sim | sim | sim |
| Eventos, comandos e scheduler | sim | sim | sim | sim |
| Inventário e block data | sim | sim | sim | sim |
| Leitura de potência redstone | sim, contrato + bridge | sim, contrato + bridge | sim, contrato + bridge | sim, contrato + bridge |
| `player.data` persistente | sim, core + teste de reinício | sim, core + bridge | sim, core + teste de reinício | sim, core + bridge |
| Menus declarados | sim | sim | sim | sim |
| Tags, drops e estruturas | sim | sim | sim | sim |
| Herança entre entidades declaradas | sim | sim | sim | sim |
| GameTests obrigatórios | 19/19 | 19/19 | 19/19 | 19/19 |
| Modelo `.obj` de bloco | sim | **não — desativado** | sim | **não — desativado** |
| Modelo/skin customizados de entidades | sim | **degradado para renderer vanilla** | sim | **degradado para renderer vanilla** |
| Cores customizadas do spawn egg | sim | **degradado para cores padrão** | sim | **degradado para cores padrão** |
| `settings.drops_like` | sim | sim, materializado no loot gerado | sim | sim, materializado no loot gerado |
| `repairItem` de ferramenta/armadura | sim | **degradado para tags/material padrão** | sim | **degradado para tags/material padrão** |
| `spawn_particles` | sim | sim | sim | **pendente/recusado pelo bridge** |
| Receitas de datapack sem warnings | sim | **pendente de revisão do formato 1.21.4** | sim | **pendente de revisão do formato 1.21.4** |

As linhas de OBJ, renderer de entidades, cores de ovos, reparação e partículas não devem ser escondidas atrás de uma coluna verde de compilação. Elas representam decisões conscientes do porte 1.21.4: o bridge compila e carrega o restante do mod, mas recusa ou degrada a capability que ainda não foi adaptada ao sistema novo.

## Evidência executada

| Verificação | Resultado |
|---|---|
| `./gradlew :core:test` | passou, incluindo os contratos de redstone e `player.data` |
| `./gradlew compileAllRuntimes` | passou para Fabric 1.21.1, Fabric 1.21.4, NeoForge 1.21.1 e NeoForge 1.21.4 |
| `:runtimes:fabric:1.21.1:runGametest` | 19/19 testes obrigatórios passaram |
| `:runtimes:fabric:1.21.4:runGametest` | 19/19 testes obrigatórios passaram |
| `:runtimes:neoforge:1.21.1:runGameTestServer` | 19/19 testes obrigatórios passaram |
| `:runtimes:neoforge:1.21.4:runGameTestServer` | 19/19 testes obrigatórios passaram |

Os testes usam os mesmos exemplos em `examples/`, sincronizados para cada diretório de jogo. Eles cobrem registro, propriedades declaradas, inventários, persistência, automação, eventos, fila de ticks, leitura de redstone, tags, ovos e herança. **Eles são testes de servidor:** não conseguem verificar pixels, iluminação, modelos na mão do jogador, telas client-side ou qualidade visual.

## O que significa “mesmo mod”

O mesmo core, os mesmos manifestos e os mesmos scripts Lua foram carregados nas quatro combinações da matriz. O que muda é o bridge, que absorve a API de Fabric/NeoForge e a versão do Minecraft. Essa é a prova prática da direção arquitetural: o autor do mod não precisa duplicar o contrato para cada versão.

Isso não significa que toda API visual esteja pronta. Na 1.21.4 o sistema de renderização mudou, e o porte ainda mantém renderers vanilla como fallback e desliga OBJ de forma explícita. Um mod que dependa dessas capabilities precisa esperar uma nova revisão do bridge ou receber a recusa clara, em vez de carregar uma malha quebrada silenciosamente.

## Critério para promover 1.21.4 a compatível

A versão 1.21.4 deve continuar marcada como **experimental** até que OBJ, renderização declarada de entidades, formato de receitas e as capabilities degradadas sejam validados. O mínimo já alcançado é significativo, mas não é paridade completa: compilação dos quatro runtimes e 18/18 GameTests em cada combinação provam que o core e as partes de servidor atravessam a versão; não provam que o cliente desenha tudo igual.

## Plataformas ainda não cobertas

Forge antigo, Quilt, Paper/Spigot e Bedrock continuam fora desta matriz. Um novo runtime deve ganhar uma pasta própria, uma entrada no `settings.gradle`, propriedades locais e as tarefas agregadas, além de executar os mesmos contratos antes de ser considerado compatível.

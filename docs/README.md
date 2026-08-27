# Documentação do MineLoader

O MineLoader é um modloader declarativo para Minecraft Java. Mods são pastas com `mod.json`, Lua e recursos; o loader valida o pacote, aplica permissões, regista conteúdo e executa a lógica através de um contrato próprio. O objectivo é manter os mods dependentes da API do MineLoader, e não de classes internas de Minecraft, Fabric ou NeoForge.

> **Regra de navegação:** os documentos de contrato dizem o que um mod pode assumir; a matriz diz em que runtimes isso foi verificado; o roadmap e os documentos de estudo dizem o que ainda está a ser decidido.

## Começar por aqui

| Se queres... | Lê primeiro |
|---|---|
| Entender o projecto em poucos minutos | [README principal](../README.md) |
| Criar o primeiro mod | [Guia do mod](GUIA_DO_MOD.md) |
| Escrever um `mod.json` | [Formato do mod e manifesto](MOD_FORMAT_SPEC.md) |
| Ver exemplos executáveis | [Exemplos executáveis](../examples/) |
| Saber quais APIs já são estáveis | [API estável](API_ESTAVEL.md) |
| Confirmar a compatibilidade real | [Matriz de compatibilidade](COMPATIBILIDADE.md) |
| Executar ou adicionar runtimes | [Runtimes e testes](RUNTIMES.md) |

## 1. Para quem cria mods

| Documento | Conteúdo |
|---|---|
| [GUIA_DO_MOD.md](GUIA_DO_MOD.md) | Tutorial prático de manifestos, Lua, blocos, itens, entidades, inventário, UI, recursos e permissões. |
| [tutorials/README.md](tutorials/README.md) | Fonte JSON canónica dos guias de bloco, item, UI e Lua consumida diretamente pelo site público. |
| [GITHUB_PAGES.md](GITHUB_PAGES.md) | Endereço público, estrutura de build em `docs/` e regras de manutenção do site estático. |
| [MOD_FORMAT_SPEC.md](MOD_FORMAT_SPEC.md) | Especificação normativa do `mod.json`, campos aceites, recursos, dependencies, requirements e limites. |
| [API_ESTAVEL.md](API_ESTAVEL.md) | Contrato público agnóstico, snapshots, APIs de mundo, jogador, bibliotecas e regras de evolução. |
| [EVENTS.md](EVENTS.md) | Eventos globais e por objecto, contexto, cancelamento e prioridades. |
| [DYNAMIC_BLOCKS.md](DYNAMIC_BLOCKS.md) | Blocos declarativos e propriedades alteráveis em runtime. |
| [UI_SPEC.md](UI_SPEC.md) | Modelo de menus, telas, HUD e protocolo servidor-cliente. |
| [HOTKEYS.md](HOTKEYS.md) | Hotkeys declarativas, manifesto, callback Lua e segurança do input client-side. |
| [COMMANDS.md](COMMANDS.md) | Schemas declarativos de comandos, árvore Brigadier, argumentos tipados, autocomplete e migração. |
| [MINIMAP.md](MINIMAP.md) | Minimapa declarativo com câmera lógica aérea client-side, textura de baixa resolução, radar, waypoints, configuração e limites honestos. |
| [UI_HTML_DESIGN.md](UI_HTML_DESIGN.md) | Estudo da futura camada de interface por HTML/CSS; não é contrato implementado. |
| [CONTROLES_MUNDO.md](CONTROLES_MUNDO.md) | Estado de bloco, Game Rules, dificuldade, hora, clima, explosão, raio, quebra global, jogador, mapa, dimensões e prioridades de mundo. |
| [examples/README.md](examples/README.md) | Manifestos que demonstram capabilities, domínios e bibliotecas entre mods. |

## 2. Segurança e distribuição

| Documento | Conteúdo |
|---|---|
| [SECURITY_SPEC.md](SECURITY_SPEC.md) | Modelo de confiança, sandbox Lua, permissões, recursos remotos, integridade, falhas e rollback. |
| [INSTALACAO.md](INSTALACAO.md) | Instalação local/remota, `dependency_sources`, cache, confirmação e comandos administrativos. |
| [AI_PIPELINE.md](AI_PIPELINE.md) | Como uma futura geração por IA deve produzir pacotes que continuam sujeitos a validação do loader. |

## 3. Contrato técnico do loader

| Documento | Conteúdo |
|---|---|
| [SPECIFICATION.md](SPECIFICATION.md) | Especificação geral do produto, arquitectura, ciclo de vida e critérios de aceitação. |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Fronteiras entre core, bridges, runtime Lua, recursos, sincronização e reload. |
| [RUNTIMES.md](RUNTIMES.md) | Estrutura `runtimes/<plataforma>/<versão>`, tarefas Gradle e como adicionar uma versão. |
| [COMPATIBILIDADE.md](COMPATIBILIDADE.md) | Evidência por capability em Fabric/NeoForge 1.21.1 e 1.21.4, incluindo degradações conhecidas. |
| [CHECKLIST_MODLOADER.md](CHECKLIST_MODLOADER.md) | Checklist de implementação e progresso funcional. |

## 4. Lacunas e planeamento

| Documento | Como interpretar |
|---|---|
| [API_GAPS.md](API_GAPS.md) | Backlog priorizado de APIs que modders ainda podem precisar. Uma lacuna marcada como fechada aponta para o contrato correspondente. |
| [ROADMAP.md](ROADMAP.md) | Marcos de produto e ordem de evolução do loader. |
| [PROGRESSO.md](PROGRESSO.md) | Diário técnico e histórico de decisões, testes e problemas encontrados. Não substitui a especificação actual. |

## Fonte de verdade

| Pergunta | Fonte principal |
|---|---|
| O que a API significa? | [API_ESTAVEL.md](API_ESTAVEL.md) e [MOD_FORMAT_SPEC.md](MOD_FORMAT_SPEC.md) |
| Que conteúdo o site apresenta nos tutoriais? | [tutorials/index.json](tutorials/index.json) e os documentos por guia em [`tutorials/`](tutorials/) |
| Funciona nas versões mantidas? | [COMPATIBILIDADE.md](COMPATIBILIDADE.md) |
| Como foi implementado? | Código em `core/` e `runtimes/`, acompanhado pelos testes |
| O que ainda não existe? | [API_GAPS.md](API_GAPS.md) e a secção de limitações da matriz |
| O que é uma proposta? | [ROADMAP.md](ROADMAP.md), [CONTROLES_MUNDO.md](CONTROLES_MUNDO.md) e [UI_HTML_DESIGN.md](UI_HTML_DESIGN.md) |
| O que aconteceu durante o desenvolvimento? | [PROGRESSO.md](PROGRESSO.md) |

## Convenções

Os documentos de API devem usar nomes exactos do Lua, distinguir leitura de escrita e indicar a permissão necessária. Uma capability só deve ser marcada como comum depois de existir no core, nas quatro bridges mantidas e nos testes correspondentes. Nesta leva, isso inclui `world.explode`, `world.lightning`, `player.equipment.read`, `player.inventory.slot`,
`events.block.break` e `events.action.authorization`. O exemplo executável `examples/land_claims`
demonstra a última capability.

Documentos de estudo podem conter alternativas e código ilustrativo, mas precisam indicar claramente quando uma proposta ainda não é implementada. Em particular, shaders, pipelines de renderização avançados, mapa-múndi persistente e integrações com Iris/Sodium/Canvas não fazem parte do contrato actual. O minimapa documentado em [MINIMAP.md](MINIMAP.md) é uma demonstração funcional baseada na capability de câmera virtual; não é uma API de cartografia completa nem uma câmera 3D geral.

A matriz de runtimes continua deliberadamente honesta: compilação server-side e GameTests não provam pixels, iluminação, modelos client-side ou shaders. A versão 1.21.4 mantém limitações visuais documentadas em [COMPATIBILIDADE.md](COMPATIBILIDADE.md).

[Voltar ao README principal](../README.md)

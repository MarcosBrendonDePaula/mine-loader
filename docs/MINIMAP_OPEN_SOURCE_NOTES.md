# Notas de estudo: minimapas open source

Data da consulta: 2026-08-27.

## JustMap

Fonte: https://github.com/Bulldog83/JustMap

O repositório é público e declara licença LGPL-3.0. A árvore mostra uma separação útil entre `map/data`, `map/minimap`, `map/icon`, `map/waypoint`, `config`, `network` e mixins client/server. O projecto tem classes explícitas para `ChunkGrid`, `EntityRadar`, `MapDataManager`, `WorldMapper`, `MapTexture`, ícones de jogadores/entidades/waypoints, `WaypointKeeper` e uma `Minimap` com skins. Esta organização sugere separar captura/cache, composição visual e elementos de radar, em vez de recalcular o mundo inteiro durante cada frame.

O repositório mantém foco em Fabric e está direccionado a versões antigas, por isso é uma referência de arquitectura e UX, não uma dependência que possa ser copiada para o MineLoader.

## VoxelMap Updated

Fonte: https://github.com/fantahund/VoxelMap

O repositório é público e organiza código comum e adapters separados para Fabric, Forge e NeoForge. A página descreve minimapa, mapa-múndi, waypoints e ícones de mobs. A árvore contém `MapChunk`, `MapChunkCache`, `FullMapData`, `PersistentMap`, `WaypointContainer`, `WorldUpdateListener`, `MinimapContext`, classes de renderização e texturas dinâmicas, além de recursos de moldura/stencil e vários ícones de radar/waypoint.

A principal ideia aproveitável é uma pipeline em camadas: dados de chunks actualizados incrementalmente, cache persistente por mundo/dimensão, textura dinâmica actualizada em background e uma camada de composição que desenha moldura, jogador, entidades e waypoints. O código usa APIs internas e diferenças de plataforma; o MineLoader deve absorver isso nos bridges e expor ao Lua apenas dados serializáveis/contratos versionados.

## Decisão provisória

Usar JustMap como referência de decomposição de módulos e VoxelMap como referência de pipeline/caching/UX. Não copiar código, assets ou APIs de plataforma. A primeira evolução do `minimap_demo` deve priorizar: mapa topográfico incremental, cache por dimensão, orientação/configuração consistente, marcador do jogador, waypoints declarativos, radar com filtros e renderização client-side em uma superfície neutra comum às quatro versões.

## Padrão observado no cache de chunks

A classe `MapChunkCache` do VoxelMap mantém uma grelha fixa centrada no chunk actual. Quando o jogador avança poucos chunks, desloca a matriz e cria apenas as colunas/linhas que entraram no campo; quando a mudança é grande ou muda de mundo, repopula toda a grelha. Cada `MapChunk` pode ser marcado como modificado por eventos de chunk e verificado separadamente. Este padrão evita reconstruir todos os dados a cada frame e é uma boa base para o cache neutro do MineLoader.

Fonte de código: https://github.com/fantahund/VoxelMap/blob/master/common/src/main/java/com/mamiyaotaru/voxelmap/util/MapChunkCache.java

## Padrões de processamento e composição

No VoxelMap, cada `MapChunk` controla coordenadas, estado carregado/modificado e notifica um observador apenas quando o chunk entrou, saiu ou foi marcado como alterado. A actualização é orientada a eventos e não a uma reconstrução cega por frame.

No JustMap, a classe `Minimap` separa preparação por tick, parâmetros de configuração, posicionamento responsivo, informação textual, radar de entidades, waypoints, camada vertical e renderer rápido/bufferizado. O mapa pode rodar ou manter o norte fixo; o tamanho, escala, posição, moldura/skin e framebuffer são tratados como estado de configuração. O radar aplica filtros independentes para jogadores, hostis e criaturas, limita a quantidade de entidades e respeita regras do servidor.

Fontes de código:

- https://github.com/fantahund/VoxelMap/blob/master/common/src/main/java/com/mamiyaotaru/voxelmap/util/MapChunk.java
- https://github.com/Bulldog83/JustMap/blob/master/src/main/java/ru/bulldog/justmap/map/minimap/Minimap.java

## Estado actual do MineLoader

O `minimap_demo` já usa uma boa ideia de primeira fase: cache de colunas partilhado, aquecimento em espiral, orçamento de leituras por actualização, expiração de colunas antigas, classificação simples de blocos, sombreado por diferença de altura e compressão de células em faixas horizontais para respeitar o limite de elementos do HUD. O ponto fraco é que o mapa ainda é uma aproximação por cores e faixas; não tem cache por dimensão, textura de tiles, rotação visual, moldura/recorte, waypoints ou radar real.

A próxima versão deve conservar o orçamento e o cache incremental, mas mover a composição rica para client-side. O servidor deve continuar a enviar apenas snapshots de dados portáveis (células, jogador, entidades, waypoints e configuração), enquanto cada bridge desenha o mesmo modelo visual com as APIs locais.

## Limite actual do HUD do MineLoader

Fabric e NeoForge têm renderers equivalentes que recebem um `ScreenModel` textual com elementos genéricos e desenham rectângulos, texto e ícones através do renderer de telas. O HUD não captura input e é escondido quando há uma tela aberta ou o HUD vanilla está oculto. A API actual não possui uma primitiva de mapa rasterizado, recorte circular, rotação, textura dinâmica ou camada de ícones posicionados por coordenadas do mundo.

Conclusão de desenho: não é suficiente aumentar o número de faixas Lua. Para uma melhoria real e eficiente, convém introduzir um modelo neutro de `MapHud` no core/protocolo e dois renderers equivalentes por plataforma, mantendo a captura de mundo e o orçamento fora do cliente. O protocolo deve transportar uma grelha compacta de células e elementos sem expor classes Minecraft.

## Protocolo neutro existente

`ScreenModel` já é um DTO core que lê JSON e nunca expõe objetos Minecraft. O `ScreenProtocol` fixa limites para elementos, células, camadas e payload. Isso reforça o caminho correcto: uma nova capacidade de mapa deve ter DTO próprio, limites de tamanho e versão, sem transformar o `ScreenModel` em um rasterizador genérico nem passar `MatrixStack`, `GuiGraphics`, blocos ou entidades vivas através da API.

## Composição actual do exemplo

O exemplo transforma cada célula de terreno em faixas horizontais e usa painéis coloridos, um marcador central e texto de coordenadas. Isto é engenhoso para o protocolo de telas, mas limita qualidade, tamanho e suavidade: uma grelha 41×41 consome potencialmente centenas de elementos, não há textura contínua, rotação ou ícones de radar.

O renderer Fabric/NeoForge já sabe desenhar painéis, imagens, itens, entidades e texto, mas trata cada elemento separadamente. A solução proposta é adicionar um tipo de elemento `map` ao modelo neutro, com uma grelha compacta e marcadores, e uma rotina de desenho especializada por bridge. A superfície restante continua a usar o mesmo layout/âncoras do HUD.

## Decisão de integração

A evolução será feita como um novo tipo de elemento `map` dentro do protocolo HUD existente. O core continuará responsável apenas por validar e serializar uma definição fechada: largura/altura visual, grelha de cores, escala, forma, posição do jogador, norte, waypoints e entidades. O servidor Lua constrói esse snapshot a partir de `top_y`, `get_block`, `entities_near` e `player.data`; o cliente apenas desenha.

Nos bridges, Fabric usará `DrawContext` e NeoForge usará `GuiGraphics`, mantendo a mesma geometria e os mesmos dados. O mapa será um único elemento, não centenas de painéis. Isso reduz o payload e permite máscara circular, moldura, sombreado, marcador do jogador, waypoints e radar numa rotina dedicada.

Fora do escopo desta primeira evolução ficam mapa-múndi persistente, leitura de chunks fora do raio por threads próprias e compatibilidade com JourneyMap/Xaero. A API continuará a ser MineLoader, não um clone das classes internas de outros mods.

## Licenciamento e limites de reutilização

A cópia local do JustMap contém `LICENSE` com LGPL-3.0. A cópia local do VoxelMap Updated não contém um ficheiro de licença no topo do repositório, pelo que não é tratada como fonte de código ou assets reutilizáveis. O MineLoader não incorpora código, texturas, sprites ou nomes de classes desses projectos; apenas aplica padrões de arquitectura observados, com implementação própria e API própria.

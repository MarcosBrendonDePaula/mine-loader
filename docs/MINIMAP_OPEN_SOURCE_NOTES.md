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

## Decisão de arquitectura

Usar JustMap como referência de decomposição de módulos e VoxelMap como referência de pipeline/caching/UX. Não copiar código, assets ou APIs de plataforma. A implementação própria do MineLoader separa contrato no core, catálogo S2C, captura no bridge, textura dinâmica e composição de radar/waypoints. A primeira câmera é ortográfica, aérea, de baixa resolução e ancorada no jogador; não é uma câmera 3D arbitrária nem uma segunda passagem completa do `WorldRenderer` por frame.

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

O `minimap_demo` deixou de rasterizar o terreno no Lua. O servidor mantém apenas a lógica por jogador, radar, waypoints, coordenadas e configuração. O Lua regista `mod.camera("minimap", definição)`, o loader qualifica o ID como `minimap_demo:minimap` e o catálogo versionado é publicado aos clientes.

Cada bridge mantém o estado da textura por ID qualificado, deriva um recurso físico privado e rasteriza uma imagem pequena a partir da superfície disponível no cliente. O custo actual é limitado por resolução e intervalo, mas ainda é uma implementação simples de amostragem: não há mapa-múndi persistente, streaming de tiles, actualização incremental por chunk ou renderização de modelos completos.

## Limite actual do HUD do MineLoader

Fabric e NeoForge têm renderers equivalentes que recebem um `ScreenModel` textual com elementos genéricos e desenham rectângulos, texto, ícones e o elemento `map`. O HUD não captura input e é escondido quando há uma tela aberta ou o HUD vanilla está oculto. A textura aérea é dinâmica e privada do bridge; o core só transporta a referência lógica da câmera, parâmetros limitados e marcadores.

Conclusão de desenho: não era suficiente aumentar o número de faixas Lua. O caminho adoptado foi um contrato `CameraProtocol` versionado, catálogo S2C, validação de permissão/capability, estado por ID qualificado e quatro implementações equivalentes de rasterização. A grelha `server_cells` continua como fallback e compatibilidade.

## Protocolo neutro existente

`ScreenModel` já é um DTO core que lê JSON e nunca expõe objetos Minecraft. O `ScreenProtocol` fixa limites para elementos, células, camadas e payload. Isso reforça o caminho correcto: uma nova capacidade de mapa deve ter DTO próprio, limites de tamanho e versão, sem transformar o `ScreenModel` em um rasterizador genérico nem passar `MatrixStack`, `GuiGraphics`, blocos ou entidades vivas através da API.

## Composição actual do exemplo

O exemplo transforma cada célula de terreno em faixas horizontais e usa painéis coloridos, um marcador central e texto de coordenadas. Isto é engenhoso para o protocolo de telas, mas limita qualidade, tamanho e suavidade: uma grelha 41×41 consome potencialmente centenas de elementos, não há textura contínua, rotação ou ícones de radar.

O renderer Fabric/NeoForge já sabe desenhar painéis, imagens, itens, entidades e texto, mas trata cada elemento separadamente. A solução proposta é adicionar um tipo de elemento `map` ao modelo neutro, com uma grelha compacta e marcadores, e uma rotina de desenho especializada por bridge. A superfície restante continua a usar o mesmo layout/âncoras do HUD.

## Decisão de integração

A câmera foi integrada como um novo contrato lógico usado pelo elemento `map`. O core valida e serializa somente `projection`, `source`, `anchor`, `orientation`, resolução, raio, intervalo e saída. O Lua pode declarar a câmera no manifesto ou criá-la dinamicamente; se manifesto e Lua usarem o mesmo ID, definições divergentes falham. O servidor envia marcadores e configuração, não blocos ou pixels.

Nos bridges, Fabric usa `DrawContext` e NeoForge usa `GuiGraphics`, mantendo a mesma geometria. A imagem é uma textura pequena por câmera, não centenas de painéis e não uma chamada completa do pipeline 3D. O bridge absorve `NativeImage`, `DynamicTexture`, `BlockColors`, altura e registro da textura; essas classes não atravessam o core.

Fora do escopo desta primeira evolução ficam mapa-múndi persistente, leitura forçada de chunks remotos, tiles em background, entidades renderizadas na captura e compatibilidade com JourneyMap/Xaero. A API continua a ser MineLoader, não um clone das classes internas de outros mods.

## Licenciamento e limites de reutilização

A cópia local do JustMap contém `LICENSE` com LGPL-3.0. A cópia local do VoxelMap Updated não contém um ficheiro de licença no topo do repositório, pelo que não é tratada como fonte de código ou assets reutilizáveis. O MineLoader não incorpora código, texturas, sprites ou nomes de classes desses projectos; apenas aplica padrões de arquitectura observados, com implementação própria e API própria.

## Referência adicional: MapWriter

Fonte: https://github.com/daveyliam/mapwriter

O MapWriter é público, contém `LICENSE` MIT e descreve-se como minimapa open source. A árvore é histórica (última actividade visível em 2019 e voltada para versões antigas), portanto não será usado como dependência. A página e os acknowledgements mostram uma separação entre overlay, mapa e texturas de moldura/seta; a lição relevante é que a apresentação é uma camada própria e não deve ser confundida com a captura dos dados.

A ideia de uma “câmera de cima” merece uma distinção: uma segunda câmera ortográfica que renderiza o mundo inteiro num framebuffer parece simples, mas em Minecraft exigiria repetir o pipeline client-side de chunks, entidades, iluminação, culling e texturas num alvo off-screen. Isso é caro e atravessa APIs específicas de cada versão. Para o MineLoader, a abordagem segura é uma captura client-side de baixa resolução baseada nos chunks já carregados, com cache de tiles e textura dinâmica; não uma entidade-câmera exposta ao core.

A inspeção adicional do JustMap encontrou `ExtendedFramebuffer`, `Image` com `NativeImage` e renderizadores próprios. Isso confirma que uma apresentação rica pode usar uma textura dinâmica/target separado, mas também confirma o custo de manter um framebuffer e o estado de renderização entre versões. A primeira implementação de captura aérea deve encapsular isso exclusivamente no client bridge e ter fallback para a grelha server-side se o alvo não puder ser criado.

## Decisão final para captura aérea

A leitura dos métodos do VoxelMap confirmou o padrão mais eficiente: uma imagem dinâmica actualizada por deslocamento, mudança de intervalo ou alteração de estado relevante, com upload apenas quando necessário. O mapa é uma textura rasterizada, não uma segunda câmera que repete o `WorldRenderer` inteiro a cada frame.

No MineLoader, `map_render = "client_camera"` referencia um ID qualificado publicado pelo catálogo `CameraProtocol`. `map_resolution`, `map_radius` e `map_update_ticks` são overrides opcionais; quando omitidos, herdam da câmera. `server_cells` continua como fallback e compatibilidade, e `client_topdown` fica como modo legado sem catálogo. A implementação não passa `Framebuffer`, `RenderSystem`, `Camera`, `NativeImage` ou `BlockState` pelo core.

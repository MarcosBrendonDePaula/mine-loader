# Checklist — o que um modloader precisa ter

Régua externa para medir o Mine Loader, e o mapa de progressão que sai dela.

Diferente de `API_GAPS.md`, que descreve o estado atual em prosa, aqui a lista é **independente do
que já construímos**: o que qualquer modloader precisa oferecer, tenhamos ou não começado. É isso
que permite responder "o loader alcança este tipo de mod?" antes de alguém começar a escrever um.

**Legenda:** `[x]` pronto nas duas plataformas · `[~]` parcial, com a ressalva ao lado · `[ ]` não
existe

**Ao fechar um item, risque-o na mesma mudança que o implementa** — a mesma regra de
`COMPATIBILIDADE.md`. Uma lista que envelhece em silêncio é pior que nenhuma, porque alguém confia
nela.

**A régua definitiva, porém, é outra: um mod de verdade rodando.** O
[Logistic Pipes](https://github.com/rs485/logisticspipes) é o **primeiro mod migrado** para este
loader — a rede de canos que encontra e entrega itens está de pé em `examples/logistica`, escrita só
em `mod.json` e Lua, sem uma linha de Java. Um porte autônomo, que reusa a arte do original e por
isso é MMPL, vive em
[`logistic-pipes-lua`](https://github.com/MarcosBrendonDePaula/logistic-pipes-lua).

Ele importa mais que qualquer item riscado nesta lista, por duas razões. Primeiro porque o Logistic
Pipes parou de ser atualizado pelo motivo que este loader existe para resolver: acompanhar as
versões do Minecraft em Java custa caro, e um mod declarativo não paga esse preço — quem acompanha a
versão é o loader. Segundo porque **portá-lo achou quatro lacunas que esta lista não tinha**, e
todas estão em `API_GAPS.md`. Uma régua escrita de cabeça mede o que a gente imaginou; um mod real
mede o que falta.

**Tudo aqui foi conferido no código, não nos documentos.** Um recurso que existe só no Fabric conta
como `[~]`, não como `[x]`: um mod que depende dele não roda nas duas plataformas, e a promessa
central do loader é que rode. Onde a checagem contradisse um documento, a contradição está
registrada na seção "O que a auditoria encontrou".

---

## 1. Infraestrutura do loader

- [x] Descoberta de mods em disco
- [x] Manifesto declarativo com schema versionado
- [x] Validação offline com arquivo, caminho JSON e sugestão
- [x] Relatório de campos aceitos e não aplicados (`ManifestDiagnostics`)
- [x] Dependências entre mods, com resolução de ordem e detecção de ciclo
- [x] Biblioteca entre mods (`mod.require`), com permissões próprias
- [x] Habilitar e desabilitar mod sem removê-lo
- [x] Isolamento de falha: erro de um mod não derruba os outros
- [x] Recarga de script em tempo de execução (`/lua reload`)
- [x] Estado persistido por mod
- [x] Comandos de diagnóstico (`/mod list`, `/mod blocks`, `/mod commands`)
- [x] Os mods do loader visíveis para quem joga — `ctx.server.mods()` e o exemplo `gerenciador`
- [x] Instalar mod por link, dentro do jogo, com prévia de permissões
- [x] Desinstalar mod
- [x] Dependência declarada buscada automaticamente (`dependency_sources`), sob chave
- [x] Carga a quente do que vive no runtime; conteúdo declarado ainda exige reinício
- [ ] Atualizar um mod instalado (hoje é reinstalar por cima)
- [ ] Fixar a versão de um mod instalado por sha256
- [x] Autosave periódico de estado — a cada 6.000 tiques
- [ ] `ManifestDiagnostics` completo — hoje ele omite quatro campos mortos
- [ ] Versionamento de API com aviso de depreciação para quem escreve o mod
- [ ] Migração de dados salvos quando o mod muda de versão
- [ ] Conflito de ID entre dois mods detectado antes de subir
- [x] Lado declarado (`side`), deduzido quando ausente
- [ ] Ordem de carregamento explícita além de dependência (`before` / `after`)

## 2. Segurança e limites

- [x] Sandbox: nega `io`, `os`, `package`, `debug`, `luajava`, `require`, `dofile`, `loadfile`,
      `load`, `loadstring`
- [x] Orçamento de tempo por callback — 20 ms, verificado a cada 2.048 instruções
- [x] Permissões declaradas no manifesto e verificadas na operação — quatorze, sem hierarquia
- [x] Integridade de recurso remoto por sha256
- [x] Limite de tamanho de recurso (`maxBytes`)
- [x] Tetos de operação: `fill` 32.768 blocos, coordenada 30 milhões, 4.096 tarefas agendadas,
      128 módulos por mod, 64 KiB de payload de tela
- [x] Import remoto só por HTTPS, com cache e hash
- [ ] Orçamento de memória por mod
- [ ] Limite de operações por tique — hoje um `set_block` em laço só é freado pelo tempo
- [x] Instalação em dois passos, com as permissões declaradas à vista antes de gravar
- [x] Chaves de instalação desligadas por padrão e persistidas em disco
- [ ] Auditoria: registro do que cada mod fez com permissão sensível
- [ ] Quarentena: mod que falha repetidamente é desligado sozinho

As quatorze permissões: `chat.send`, `server.read`, `server.command.register`, `world.read`,
`world.write`, `world.containers`, `entity.read`, `entity.spawn`, `entity.modify`, `player.read`,
`player.modify`, `player.inventory`, `player.menu`, `player.move`.

## 3. Conteúdo declarável

### Registros do jogo

- [x] Bloco — material, física, forma e colisão
- [x] Item avulso
- [x] Entidade de bloco com inventário próprio
- [x] Tag de bloco e de item
- [x] Forma de colisão e contorno, inclusive caixas próprias
- [x] Receita de bancada
- [x] Receita de fornalha, alto-forno, defumador e fogueira
- [x] Loot table
- [x] Ferramenta — tipo, nível, velocidade, dano, encantabilidade
- [x] Armadura — slot, proteção, tenacidade, resistência a empurrão
- [x] Estado de bloco declarado (`state.properties`)
- [x] Propriedades do item de bloco (`block.item`)
- [x] Aba criativa, com `register: false` respeitado
- [x] Largar o loot de outro bloco (`settings.drops_like`)
- [x] Inflamabilidade e propagação de fogo (`material.flammability`, `material.burn_spread`)
- [ ] Tipo de bloco além de `generic` — `type` e `base` são declarados e nunca lidos
- [ ] Comida — saturação, tempo de consumo, efeito ao comer
- [ ] Combustível e tempo de queima
- [ ] Tipo de receita próprio, com regras de processamento novas
- [x] Entidade — espécie própria derivada de uma do jogo (`entities`), nas duas plataformas
- [x] Entidade — forma própria (`model`), animada pela base
- [x] Entidade — comportamento próprio (`ai`)
- [ ] Entidade — animação própria
- [ ] Fluido
- [ ] Bioma
- [ ] Dimensão
- [ ] Encantamento
- [ ] Efeito de poção
- [ ] Atributo
- [ ] Profissão de aldeão e trocas
- [ ] Avanço / conquista
- [ ] Tipo de partícula própria
- [ ] Evento de som próprio
- [ ] Variante de quadro, estandarte e afins

### Geração de mundo

- [ ] Minério declarado que nasce no terreno
- [ ] Feature de superfície — árvore, arbusto, formação
- [ ] Estrutura colocada pelo gerador (hoje só via `place_structure` chamado por script)
- [x] Regra de spawn de mob por bioma (`entities[].spawn`)
- [x] Forma do bloco variando com o estado — cerca, vidraça, cano que conecta
- [ ] Alteração de terreno declarada

Nenhum item desta subseção existe. É o bloco mais coeso de tudo que falta, e o único cuja ausência
fecha um gênero inteiro sozinha.

## 4. Recursos e aparência

- [x] Resource pack virtual montado pelo loader
- [x] Textura local
- [x] Textura remota, com cache por conteúdo e fallback
- [x] Recursos nomeados — declarados uma vez, referenciados por `@nome`
- [x] Modelo desenhado fora do loader, aceito como está
- [x] Blockstate com variantes
- [x] Forma declarada valendo também para o desenho, com oclusão de face
- [x] Sincronização servidor→cliente dos recursos aprovados
- [x] Data pack (receita, loot) — montado nas duas plataformas
- [ ] **Camada de renderização** — `renderLayer`, `translucent` e `cutout` são declarados e ignorados
- [ ] **Emissividade e tinta** — `emissive` e `tint` são declarados e ignorados
- [ ] **Orientação de bloco** — `placement.facing` é declarado e ignorado
- [ ] **Alagável** — `placement.waterloggable` é declarado e ignorado
- [ ] Som próprio distribuído pelo mod
- [ ] Música própria
- [ ] Textura animada (`.mcmeta`)
- [ ] Transformações de modelo por contexto — mão, GUI, chão, moldura
- [ ] Arquivo de idioma / traduções
- [ ] Shader ou efeito pós-processamento

Cinco dos sete campos de `render` não fazem nada em plataforma nenhuma.

## 5. API de mundo

- [x] Ler e escrever bloco
- [x] Quebrar bloco, com ou sem drop
- [x] Preencher região
- [x] Altura do terreno (`top_y`)
- [x] Dados arbitrários por posição (`get_block_data` / `set_block_data`)
- [x] Colocar estrutura `.nbt` salva pelo bloco de estrutura, com rotação
- [x] Hora do dia e clima, leitura e escrita
- [x] Som posicionado, com categoria
- [x] Partícula posicionada, com direção
- [x] Inventário de bloco — capacidades, conteúdo, inserir, extrair, e por slot
- [x] Consulta ao registro — itens, blocos, tipos de entidade, receitas, drops
- [x] **Ler o estado do bloco** — facing, open, waterlogged, powered, axis
- [x] **Escrever o estado do bloco** — alteração parcial com propriedades validadas
- [x] Ler bioma numa posição
- [x] Ler nível de luz numa posição
- [ ] Explosão
- [ ] Raio
- [ ] Largar item solto no mundo, sem passar pelo inventário de alguém
- [x] Ler sinal de redstone — emissão dinâmica ainda não faz parte do contrato
- [x] Game Rules com whitelist e dificuldade do mundo
- [x] Tique agendado por posição (`schedule_block` / `on_scheduled`) — "volte aqui em N tiques"
- [x] Raycast — o primeiro bloco que uma linha atravessa (`player.looking_at`)
- [ ] Carregar e manter chunk sob demanda

## 6. API de jogador

- [x] Nome, uuid, posição, vida, fome, experiência, modo de jogo, dimensão
- [x] Item na mão e inventário
- [x] Tamanho da tela
- [x] Teleporte
- [x] Escrever vida, fome, experiência e modo de jogo
- [x] Aplicar e limpar efeitos
- [x] Mensagem, action bar, título e som direcionado
- [x] Contar, dar, tirar e limpar item
- [x] Item declarado ao dar — nome, lore, dano, encantamento, `custom_model_data`
- [x] **Direção do olhar** — `player.looking_at` devolve o bloco mirado e a face atingida
- [ ] Ler efeitos ativos
- [ ] Postura — agachado, correndo, voando, nadando
- [ ] Velocidade e vetor de movimento
- [ ] Empurrão e impulso
- [ ] Partícula direcionada a um jogador
- [ ] Ler e escrever um slot específico do inventário do jogador
- [ ] Armadura equipada
- [x] Nível de operador (`is_operator`, `permission_level`)
- [ ] Estatísticas e avanços
- [ ] `ItemSpec.color`, `.attributes`, `.keepOnDeath`, `.noDrop` — declarados e ignorados **nas duas**

## 7. API de entidade

- [x] Criar entidade do jogo, com nome, equipamento, efeitos, encantamentos e atributos declarados
- [x] Listar entidades por raio
- [x] Ler informações de uma entidade
- [x] Remover, causar dano, curar
- [x] Aplicar efeito a uma entidade
- [x] `EntitySpec.variant` — cavalo, nas duas plataformas
- [x] Mover ou teleportar entidade (`teleport_entity`, `push_entity`)
- [ ] Ler e escrever NBT arbitrário
- [ ] Definir alvo ou comportamento de IA
- [ ] Montar e desmontar passageiro
- [ ] Ler inventário de uma entidade

## 8. Eventos

- [x] Bloco: usado, atacado, quebrado, colocado
- [x] Item: usado, usado em bloco
- [x] Menu: clique
- [x] Tela: `click`, `change`, `submit`, `close`
- [x] Bloco: tique aleatório e vizinho mudou
- [x] Jogador: entrou, saiu
- [x] Servidor: iniciou, parou, loader pronto
- [x] Tique de servidor
- [x] `mod.after` — agendado e executado nas duas
- [ ] `mod_reloaded` — aceito no registro, **nunca disparado em plataforma nenhuma**
- [ ] `menu_closed` — aceito no registro, **nunca disparado em plataforma nenhuma**
- [ ] `onPlace` — campo do manifesto registrado e nunca disparado
- [x] **Entidade: nascimento, dano, morte, domesticação** — `entity_spawned`, `entity_damaged`, `entity_died`, `entity_tamed`
- [ ] Entidade: ataque como evento próprio
- [ ] **Jogador: morte, renascimento, ataque, dano recebido**
- [ ] Jogador: mudou de dimensão, dormiu, pegou item, subiu de nível
- [ ] Item: craftado, fundido, consumido, quebrou
- [ ] Bloco: explodiu, queimou, cresceu
- [ ] Chunk: carregou, descarregou
- [ ] **Cancelamento** — um evento que o mod possa vetar

Dezessete eventos, e nenhum de entidade. Sete deles não disparam no NeoForge; dois não disparam em
lugar nenhum.

## 9. Interface

- [x] Menu com slots reais, sobre a tela de baú do jogo
- [x] Tela desenhada, descrita em dados — dez tipos de elemento
- [x] HUD
- [x] Sobreposição de tela do jogo — onze alvos
- [x] Vocabulário fechado de ações, elementos, âncoras e alvos
- [x] Eventos do cliente: tela do jogo aberta e fechada
- [x] Diagnóstico de tela (`dump_screen`) — posições resolvidas e colisões
- [ ] Botão e campo de texto dentro de um viewport — hoje recusado: widget não rola
- [x] Evento de tecla, com atalho declarado no manifesto (`client.input.keybind`)
- [x] Schema declarativo de comandos no JSON/Lua, merge determinístico, argumentos tipados e autocomplete (`server.command.schema`)
- [x] Geometria e ancoragem no núcleo, compartilhadas entre as plataformas
- [x] Recusa explícita quando a plataforma não suporta (`supports_screens`)
- [x] O cliente interpreta dados, nunca código
- [x] Botão e campo de texto como widgets reais na tela desenhada
- [x] Grade e viewport com rolagem
- [ ] **Slot funcional em tela desenhada** — mostra item, não recebe item arrastado
- [ ] Arrastar e soltar entre slots
- [ ] Tooltip rico além de texto
- [ ] Tela de configuração do mod
- [ ] Reconexão sem perder a tela aberta

**É a camada mais bem portada do projeto** — os dois clientes desenham os mesmos tipos, tratam os
mesmos widgets e reconhecem os mesmos onze alvos. É o resultado direto de `ScreenModel` e a
geometria terem ido para o núcleo.

## 10. Rede e multiplayer

- [x] Canal de rede próprio do loader — oito mensagens
- [x] Servidor decide, cliente renderiza
- [x] Cliente informa o tamanho da tela ao servidor
- [x] Sincronização de recursos na conexão
- [~] Mensagem cliente→mod — fatos de vocabulário fechado; texto livre não
- [ ] Comportamento definido quando o cliente não tem o loader

## 11. Plataformas

- [x] Fabric
- [x] NeoForge — bridge e interface alinhadas com Fabric na matriz mantida; limitações visuais de
      1.21.4 continuam discriminadas em `COMPATIBILIDADE.md`
- [x] Núcleo que não conhece nenhuma das duas
- [x] Matriz de compatibilidade mantida — capability nova só entra após compilação e teste real
- [ ] Quilt
- [ ] Paper / Spigot — servidor puro, sem registro de conteúdo
- [ ] Mais de uma versão do Minecraft

## 12. Ferramentas e experiência de quem escreve o mod

- [x] Exemplos progressivos — onze mods em `examples/`
- [x] Testes de núcleo sem Minecraft — 21 arquivos, cerca de 200 casos
- [x] Autoteste rodável dentro do jogo (`/mod autoteste`)
- [x] Servidor dirigível por arquivo, sem cliente
- [x] Documentação de formato, API, eventos, segurança e interface
- [x] GameTest em servidor real — 22 casos em cada combinação mantida
- [ ] Template de mod novo, gerado por comando
- [ ] Validador CLI independente do jogo
- [ ] Stubs de tipo ou autocompletar para o Lua
- [ ] Erro de Lua com linha e pilha visíveis para quem escreveu
- [ ] Hot reload de textura sem reiniciar
- [ ] Perfilador: quanto cada mod custa por tique
- [ ] Índice ou repositório de mods
- [ ] Geração assistida por IA com validação (M8 do roadmap)

---

## O que a auditoria encontrou

Auditoria por leitura de código no commit `e087d8a`. Seis achados, nenhum registrado em documento
algum — e um deles contradizia o que estava escrito.

**Os cinco primeiros já foram corrigidos**, e ficam registrados aqui porque o interessante não é
cada defeito: é o padrão que eles formam, na última subseção. O sexto, os campos mortos, segue
aberto.

### 1. O NeoForge não disparava nenhum evento global `[corrigido]`

`LuaRuntime.triggerAll` é o caminho de todo evento que não é de bloco, item ou menu. **Nenhum
arquivo em `neoforge/` o chama.** As únicas chamadas do repositório estão em `LuaLoaderMod.java:80,
95, 98, 104, 106, 110`, do lado do Fabric.

Não disparam no NeoForge: `loader_ready`, `server_started`, `server_stopped`, `player_joined`,
`player_left`, `tick`. Somam-se `block_random_tick` e `block_neighbor_update`, que também não
ocorrem — o primeiro porque `settings.randomTicks` nem chega ao bloco.

`COMPATIBILIDADE.md:73` afirma "sim | sim" para `loader_ready`, `server_started` e `player_joined`.
**É o pior defeito possível numa matriz de compatibilidade**, porque ela existe justamente para
alguém confiar sem testar.

### 2. O `mod.after` não executava no NeoForge `[corrigido]`

`LuaRuntime.advanceScheduler` (`:165`) é chamado uma única vez em todo o repositório:
`LuaLoaderMod.java:109`. No NeoForge o relógio interno nunca avança, então **nenhuma tarefa agendada
vence** — e o autosave de estado a cada 6.000 tiques também não ocorre, deixando a gravação só para
o desligamento.

`mod.after` é a base de qualquer coisa temporizada. No NeoForge ele aceita a tarefa e nunca a chama.

### 3. Receitas e loot tables não chegavam ao servidor NeoForge `[corrigido]`

`NeoForgeLuaLoader.java:128` retorna cedo para tudo que não seja `PackType.CLIENT_RESOURCES`. O
montador escreve receita em `data/<ns>/recipe/` e loot em `data/<ns>/loot_table/blocks/` — metade
que nunca é montada.

No Fabric o mixin em `ResourcePackManagerMixin.java:21` cobre os dois tipos. É a mesma promessa
declarativa produzindo comportamento diferente conforme a plataforma.

### 4. Ferramenta e armadura não existiam no NeoForge `[corrigido]`

Zero ocorrências de `ToolDefinition`, `ArmorDefinition`, `repairItem`, `enchantability`, `toughness`
ou `knockbackResistance` em `neoforge/`. O item registrado é um `Item` comum
(`NeoForgeContentRegistrar.java:209`).

Uma picareta declarada é, no NeoForge, um enfeite empilhável.

### 5. Campos declarados e ignorados `[em aberto]`

**Mortos nas duas plataformas**, e o próprio `ManifestDiagnostics` os denuncia: todo o bloco
`placement` (`canReplace`, `canPlaceAt`, `facing`, `waterloggable`, `rotateWithPlayer`), cinco de
sete campos de `render` (`renderLayer`, `translucent`, `cutout`, `emissive`, `tint`), `block.type`,
`block.base`, `behavior.onPlace`.

**Mortos e silenciosos** — nem o diagnóstico os menciona: `material.flammability`,
`material.burnSpread`, `settings.dropsLike`, `settings.requiredFeatures`, `behaviorSha256`, e em
`ItemSpec` os campos `color`, `attributes`, `keepOnDeath`, `noDrop`.

Há ainda `ItemBehaviorAdvanced`, uma classe idêntica a `ItemBehaviorDefinition` que nenhum campo do
manifesto referencia — código morto.

Campo ignorado é pior que campo ausente: o ausente dá erro, o ignorado dá silêncio.

### 6. As propriedades de bloco divergiam muito `[corrigido]`

`BlockSettingsFactory` no Fabric aplica cerca de vinte e cinco campos. `NeoForgeContentRegistrar.
settingsOf` (`:285`) aplica **seis**: dureza, resistência, cor no mapa, som, exige-ferramenta e
luminância.

Ficam de fora no NeoForge: `instrument`, `pistonBehavior`, `burnable`, `replaceable`, `liquid`,
`air`, `solid`, `nonOpaque`, `noCollision`, `randomTicks`, `slipperiness`, `velocityMultiplier`,
`jumpVelocityMultiplier`, `blockBreakParticles`, `dynamicBounds`, `breakInstantly`, `offset`,
`dropsNothing`.

Um gelo escorregadio declarado uma vez escorrega numa plataforma e não na outra.

### O que está saudável, e o que isso ensina

`GameBridge` (38 operações) e `PlayerHandle` (39) têm **paridade total**: as duas plataformas
sobrescrevem tudo, nenhuma cai no `default` que lança. A camada de interface também — mesmos
elementos, mesmos widgets, mesmos onze alvos de sobreposição.

**A divergência mora exatamente onde não há contrato obrigando.** O que passa por interface Java
fica alinhado, porque o compilador cobra. O que passa por leitura de manifesto e por registro de
callback diverge, porque só a disciplina cobra.

**E a causa estrutural: não existe um único GameTest no NeoForge.** Os sete casos que precisam de
servidor de verdade rodam contra uma plataforma só. Nada pega essas divergências automaticamente —
por isso puderam se acumular, e por isso o último item aberto do nível 1 é o que mais importa: sem
ele, esta seção volta a crescer.

### 7. Um defeito que só o jogo real mostrou `[corrigido]`

Não veio da auditoria de código: apareceu ao rodar o autoteste num servidor de verdade, e só quando
já havia tempestade no mundo. `set_weather("clear")` seguido de `weather()` respondia `thunder`.

A causa é que `isThundering()` do jogo não lê a flag do clima — ele compara a intensidade *visual*
da tempestade, que sobe e desce ao longo de vários tiques. A flag já estava limpa; a intensidade
ainda não tinha descido. A leitura passou a sair de `getLevelData()`, que é o estado de verdade.

**Valia nas duas plataformas**, porque as duas chamavam o mesmo método do jogo — e passava
despercebido porque o teste só falha se o mundo já estiver em tempestade no instante em que roda.
Nenhuma leitura de código o encontraria: as duas implementações eram idênticas e ambas erradas.

É o argumento dos quatro níveis de teste do `CLAUDE.md` aparecendo inteiro numa linha só. A
auditoria estática achou seis divergências entre plataformas; o servidor de verdade achou a que era
igual nas duas.

---

## Sistema de progressão

Os itens acima não têm o mesmo peso. Um loader não fica "60% pronto": ele destrava **gêneros de
mod**, um de cada vez, e cada gênero tem um conjunto mínimo de capacidades sem o qual é impossível.

Cada nível abaixo é esse conjunto. Um nível só está fechado quando **um mod real daquele gênero roda
nas duas plataformas** — não quando os itens estão riscados.

### Nível 0 — Fundação `[alcançado]`

Descoberta, manifesto, sandbox, quatorze permissões, resource pack, registro de bloco e item, ponte
de mundo e jogador, interface completa, duas plataformas, quatro níveis de teste.

**Prova:** os onze mods de `examples/` rodam.

### Nível 1 — Paridade `[fechado]`

Antes de acrescentar qualquer capacidade nova, fazer valer o que já foi prometido.

- [x] `triggerAll` no NeoForge — os seis eventos globais que não disparavam
- [x] `advanceScheduler` no NeoForge — `mod.after` e o autosave
- [x] Data pack no NeoForge — receitas e loot tables voltaram a ser montadas
- [x] `ToolDefinition` e `ArmorDefinition` no NeoForge
- [x] As dezoito propriedades de bloco que só o Fabric lia, `randomTicks` incluída
- [x] `state.properties` no NeoForge
- [x] `block.item` e `creativeTab.register` no NeoForge
- [x] `EntitySpec.variant` no NeoForge
- [x] `COMPATIBILIDADE.md` corrigido — inclusive uma linha que errava para os *dois* lados
- [x] `block_random_tick` e `block_neighbor_update` no NeoForge
- [x] Os campos mortos: `drops_like`, `flammability` e `burn_spread` aplicados nas duas;
      `required_features` e a classe `ItemBehaviorAdvanced` removidas
- [x] `ManifestDiagnostics` completo — e um aviso novo para `drops_like` anulado por `drops_nothing`
- [x] **GameTests rodando no NeoForge** — 22 casos, e no CI junto com os do Fabric

**Prova:** `examples/autoteste` roda 13/13 nas duas plataformas, e os GameTests 22/22 em cada combinação.
Os casos `eventos_globais` e `agendador` foram escritos *antes* de olhar o resultado e falharam no
NeoForge na primeira execução — que é o que os torna prova e não cerimônia.

### A lição que o nível 1 deixou

O teste que compara manifesto com bloco registrado **passou com o defeito reintroduzido de
propósito**. Ele lia os valores do manifesto e comparava com o bloco — mas todos os exemplos
declaravam exatamente os padrões do jogo, então não havia o que divergir: o adaptador podia ignorar
o manifesto inteiro e o teste continuaria verde.

A correção foi acrescentar `hello_lua:bloco_de_prova`, cujo único propósito é declarar valores
**diferentes** dos padrões. Só depois disso o teste falhou com o defeito e passou sem ele — nas duas
plataformas.

**Um teste que não se viu falhar não é verificação, é decoração.** Vale para os 22 casos daqui: a
regressão foi introduzida de propósito e revertida em cada plataforma, e o que ficou registrado é
que eles *conseguem* falhar.

**Por que vem primeiro.** É a única fase que não acrescenta capacidade nenhuma e mesmo assim é a
mais urgente. Enquanto estiver aberta, todo mod escrito carrega o risco de se comportar diferente
conforme a plataforma — e cada capacidade nova multiplica esse risco por dois. O último item é o que
impede a fase de se repetir: os outros onze existem porque nada os pegava.

### Nível 1.5 — Pares faltando `[em andamento]`

Nada de capacidade nova: completar operações que já existiam pela metade. Saíram de uma varredura
do que os itens riscados de fato entregam, e não do que a documentação diz que entregam.

- [x] Receita de fornalha, alto-forno, defumador e fogueira — o loader **lia** receitas de queima
      do jogo e não conseguia declarar uma; a documentação afirmava que conseguia
- [x] Tag de item — só existia tag de bloco, então um item declarado não entrava em
      `minecraft:planks` nem numa tag própria para servir de ingrediente
- [x] Slot em `insert_into` e `extract_from` — `container_at` sempre numerou os slots, e não havia
      como endereçar o slot que ele nomeava
- [x] Direção da partícula — a velocidade era zero fixo, então dava para fazer fumaça aparecer e
      não subir
- [x] Categoria do som — sem ela o jogador não consegue baixar o volume do mod sem baixar o do jogo
- [x] Rotação em `place_structure` — uma masmorra nascia sempre virada para o mesmo lado
- [ ] Slot no inventário do jogador — mesmo desencontro: `inventory()` numera, `give_item` não
      endereça
- [ ] `entity_info` além de posição e vida — falta rotação, velocidade, no chão, filhote, dono
- [ ] Partícula com parâmetro (`dust` com cor, `block` com bloco)
- [ ] Parar um som em execução

**A lição.** Nenhum destes aparecia como lacuna: os itens correspondentes estavam riscados. O que
faltava era perguntar, item por item, *"estamos entregando tudo que dá disso?"* — e a resposta foi
não em sete dos que pareciam prontos. Vale repetir a pergunta a cada nível fechado.

### Nível 2 — Interação física → destrava *decoração* e *utilidade*

- [ ] Ler estado de bloco
- [ ] Escrever estado de bloco
- [ ] `placement.facing` e `rotateWithPlayer` aplicados
- [ ] `waterloggable` aplicado
- [ ] `renderLayer`, `translucent`, `cutout`, `emissive` e `tint` aplicados

**Prova:** um mod de mobília — cadeira que aponta para onde foi colocada, vidro colorido
translúcido, lanterna alagável que brilha.

**Por que é o de maior retorno.** Estado de bloco fecha sozinho quatro lacunas já registradas:
`placement.facing` ignorado, `state.properties` só no Fabric, a estrutura `.nbt` perdendo a
orientação de escadas e troncos, e a impossibilidade de ler se uma porta está aberta.

### Nível 3 — Agência do jogador → destrava *magia*, *arma*, *ferramenta*

- [ ] Direção do olhar
- [ ] Raycast
- [ ] Postura e velocidade
- [ ] Ler efeitos ativos
- [ ] Empurrão e impulso
- [ ] Partícula direcionada
- [ ] Explosão e raio
- [ ] `ItemSpec.attributes` aplicado

**Prova:** uma varinha que atinge o bloco mirado a vinte blocos de distância.

**Por que.** Sem direção do olhar não existe mira, seleção à distância, nem "o bloco que estou
olhando". É provavelmente a lacuna mais limitante da API hoje: todo mod de magia e de arma começa
por ela.

### Nível 4 — Reação → destrava *combate* e *RPG*

- [ ] Eventos de entidade: morte, dano, nascimento, ataque, domesticação
- [ ] Eventos de jogador: morte, renascimento, ataque, dano recebido
- [ ] Cancelamento de evento
- [ ] Mover entidade
- [ ] NBT de entidade
- [ ] `keepOnDeath` e `noDrop` aplicados

**Prova:** um mod de progressão que dá experiência por abate e cancela dano com armadura própria.

**Por que.** São dezessete eventos e nenhum de entidade — um mod de combate não tem onde se
pendurar. O cancelamento entra aqui porque combate é o primeiro gênero que precisa dizer "não" ao
jogo.

### Nível 5 — Processo → destrava *tech* e *automação*

- [ ] Redstone: ler e emitir sinal
- [x] Tique agendado por posição (`schedule_block` / `on_scheduled`)
- [ ] Slot funcional em tela desenhada
- [ ] Arrastar e soltar entre slots
- [ ] Combustível e tempo de queima
- [ ] Tipo de receita próprio

**Prova:** uma fornalha customizada — entrada, saída, combustível, progresso visível, ligada por
redstone.

**Por que.** Automação é o gênero de maior apelo em modpack, e o único que precisa dos quatro
primeiros ao mesmo tempo: sem tique agendado a máquina não processa, sem slot funcional não recebe
insumo, sem redstone não integra, sem barra não comunica. A barra de progresso e o tique agendado já
existem; faltam os outros quatro.

### Nível 6 — Geração → destrava *exploração*

- [ ] Minério no terreno
- [ ] Feature de superfície
- [ ] Estrutura no gerador
- [x] Ler bioma e nível de luz (`biome_at`, `light_at`)
- [x] Regra de spawn por bioma

**Prova:** um minério declarado encontrado ao cavar num mundo novo.

**Por que.** Hoje um minério declarado não existe no terreno. Nenhum modpack de exploração é
possível, e é o gênero mais popular.

### Nível 7 — Vida própria → destrava *mob* e *dimensão*

- [ ] Registro de entidade própria
- [x] Modelo de entidade (ossos e caixas declarados)
- [ ] Animação de entidade
- [x] Comportamento de IA declarado (`entities[].ai`)
- [ ] Fluido
- [ ] Bioma
- [ ] Dimensão

**Prova:** uma criatura própria que anda, ataca e morre.

**Por que por último.** Cada item é um sistema inteiro. Entidade própria sozinha é modelo mais
animação mais IA mais sincronização de rede — comparável em peso a tudo que já foi construído.

### O que a progressão diz

| Nível | Gênero destravado | Itens | Peso |
|---|---|---|---|
| 0 | conteúdo estático, servidor | — | feito |
| 1 | *nenhum* — paridade | 12 | fechado |
| 1.5 | *nenhum* — pares faltando | 10 | 6 fechados |
| 2 | decoração, utilidade | 5 | baixo |
| 3 | magia, arma, ferramenta | 8 | médio |
| 4 | combate, RPG | 6 | médio |
| 5 | tech, automação | 6 | médio-alto |
| 6 | exploração | 5 | alto |
| 7 | mob, dimensão | 6 | muito alto |

Os níveis 1 a 4 somam trinta e um itens, quase todos baratos, e levam a cobertura de "conteúdo
estático mais lógica de servidor" para "quase tudo que não é geração nem entidade nova".

Os níveis 6 e 7 são projetos separados, não continuação. Vale decidir explicitamente se entram — um
loader que faz muito bem os níveis 0 a 5 é mais útil que um que faz mal os oito.

**E o nível 1 é o que mais muda o valor do projeto por linha escrita.** Ele não acrescenta nada:
apenas faz o loader cumprir a promessa que já anuncia — o mesmo mod rodando nas duas plataformas sem
mudar uma linha.

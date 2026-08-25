# Compatibilidade entre plataformas

O que cada adaptador entrega, e o que falta. A promessa do projeto é que o mesmo mod rode em
qualquer plataforma sem mudar uma linha; esta lista é onde a promessa é conferida.

**Regra:** um recurso que entra em uma plataforma entra nesta lista na mesma mudança, mesmo que só
funcione em uma. É o que impede a diferença de virar surpresa para quem escreve o mod — e o que dá
a lista de trabalho quando um adaptador novo aparece.

Legenda: **sim** funciona · **não** ainda não implementado · **n/a** não se aplica àquela plataforma

**Esta lista compara plataformas.** O que falta em todas elas — estado de bloco, direção do olhar,
evento de entidade, explosão — vive em `API_GAPS.md`. Uma linha só aparece aqui quando as colunas
divergem, ou quando divergiram e voltaram a convergir; sem essa divisão, os dois documentos
acabariam repetindo um ao outro pela metade.

---

## Contratos de plataforma

As operações de `GameBridge` e `PlayerHandle`. São o que um script Lua alcança, e a razão de o
núcleo não conhecer Minecraft.

| Área | Operações | Fabric | NeoForge |
|---|---|---|---|
| Servidor e mundo | broadcast, ler e escrever hora e clima, altura do terreno, ler, escrever e quebrar bloco, preencher | sim | sim |
| Inventário do jogador | segurar, contar, dar, tirar, listar, limpar | sim | sim |
| Corpo do jogador | posição, vida, fome, experiência, modo de jogo, dimensão, efeitos, teleporte | sim | sim |
| Mensagens | chat, barra de ação, título, som para um jogador | sim | sim |
| Menus | abrir, atualizar, fechar, id aberto | sim | sim |
| Telas desenhadas | abrir, atualizar, fechar, tamanho do cliente | sim | sim |
| HUD e sobreposições | definir, limpar | sim | sim |
| Diagnóstico de tela | `dump_screen` | sim | sim |
| Som e partículas | tocar, emitir, com categoria e velocidade | sim | sim |
| Nível de permissão | nível bruto e `is_operator` sobre ele | sim | sim |
| Entidades | criar, remover, ferir, curar, listar, ler dados, aplicar dados, **teleportar, empurrar** | sim | sim |
| Dados declarados | entidade (nome, natureza, corpo, equipamento, efeitos, estado, rotação, variante) e item (aparência, durabilidade, encantamento, atributos) | sim | sim |
| Dados por bloco | ler, gravar | sim | sim |
| Inventário de bloco | capacidades, ler, inserir, extrair | sim | sim |
| Registro do jogo | listar itens, blocos e entidades, receitas, drops | sim | sim |
| Bestiário do loader | `declared_entities`, `entity_definition` | sim | sim |
| Leitura de mundo | `biome_at`, `light_at` | sim | sim |
| Fase de registro | `register.entity`, `register.declared` | sim | sim |

> **Nenhuma operação recusa em nenhuma das duas plataformas.** Quando uma faltar, ela recusa com o
> próprio nome em vez de responder errado — um mod descobre na primeira chamada, e não por um
> comportamento estranho meia hora depois.

## Conteúdo declarado no manifesto

| Recurso | Fabric | NeoForge | Nota |
|---|---|---|---|
| Blocos e itens | sim | sim | |
| Recursos nomeados (`resources` + `"@nome"`) | sim | sim | Vive no núcleo; a forma inline continua válida |
| Aba criativa | sim | sim | |
| Resource pack gerado (textura, modelo, nome) | sim | sim | |
| Receitas de bancada | sim | sim | Geradas como datapack pelo núcleo |
| Receitas de fornalha, alto-forno, defumador e fogueira | sim | sim | Idem |
| Tags de bloco e de item | sim | sim | Idem |
| Tabelas de loot | sim | sim | Idem |
| Estruturas em texto | sim | sim | O posicionador vive no núcleo |
| Estruturas de arquivo `.nbt` | sim | sim | Lidas na carga; estado e entidades não são aplicados |
| Modelo declarado (Blockbench) | sim | sim | Recurso do tipo `model` |
| Comandos de mod | sim | sim | |
| Instalar mod por link | sim | sim | Motor no núcleo; ver `INSTALACAO.md` |
| Republicar comandos após instalar | sim | sim | |
| Nível de operador (`is_operator`) | sim | sim | |
| Dados por bloco (`block_data`) | sim | sim | |
| **Inventário de bloco** (`inventory`) | sim | sim | |
| Variantes visuais (`render.variant_textures`) | sim | sim | |
| **Estados declarados** (`state.properties`) | sim | sim | |
| Formas de colisão (`shape`) | sim | sim | Nomes prontos ou caixas próprias em `shape.boxes` |
| Forma que conecta (`shape.core`, `arm`, `connects_to`) | sim | sim | Blockstate multipart; **a colisão acompanha o desenho**; GameTest nas duas |
| Orientação (`placement.facing`) | sim | sim | `horizontal`, `all` ou `player`; blockstate com uma variante por direção |
| **Alagável** (`placement.waterloggable`) | **não** | **não** | Declarado e ignorado nas duas |
| **Aparência** (`render`: layer, emissive, tint) | **não** | **não** | Declarado e ignorado nas duas |
| Propriedades de material e física (`material`, `settings`) | sim | sim | Conferidas por GameTest nas duas |
| Loot de outro bloco (`settings.drops_like`) | sim | sim | |
| Inflamabilidade (`material.flammability`, `burn_spread`) | sim | sim | |
| Item do bloco (`block.item`) | sim | sim | |
| Espécie declarada (`entities`) | sim | sim | Deriva de uma base do jogo; GameTest nas duas confere a vida declarada |
| Ovo de criação (`entities[].spawn_egg`) | sim | sim | Entra na aba criativa do mod |
| Ícone do mod (`icon`) | sim | sim | Só a lista de mods usa; não é conteúdo do jogo |
| **Tela de mods no menu principal** | sim | **não** | Botão, lista com filtro e página, ligar/desligar e instalar por link. Só o cliente Fabric por ora |
| Saque da espécie (`entities[].loot`) | sim | sim | Datapack gerado no núcleo; herda a tabela da base por referência |
| Herança entre mods (`base` apontando outra espécie declarada) | sim | sim | Ordenação e detecção de ciclo no núcleo; GameTest nas duas |
| Fase de registro por script (`registration`) | sim | sim | Fabric na inicialização, NeoForge no `RegisterEvent`; GameTest nas duas |
| Script de registro remoto (URL ou `remote_base`) | sim | sim | Mesma trava de hash do `behavior` de bloco, por `registration_sha256` |
| Textura e modelo de espécie remotos | sim | sim | URL ou `remote_base`, com cache e trava por hash; exige `https` |
| Nascimento natural (`entities[].spawn`) | sim | sim | Fabric por API de bioma, NeoForge por modificador no data pack — caminhos diferentes, regra igual |
| Comportamento declarado (`entities[].ai`) | sim | sim | Vocabulário fechado de metas e alvos; cada plataforma nomeia as classes do jogo de um jeito |
| Forma própria (`entities[].model`) | sim | sim | Ossos e caixas em JSON; usa a **animação da base**. Nomes de osso conferidos na carga |
| Textura de espécie (`entities[].texture`) | sim | sim | Recurso, caminho ou URL; sem declarar usa a pele da base. Não é herdada |
| Ovo com modelo e cor (`spawn_egg`) | sim | sim | Modelo gerado do molde do jogo; **só o cliente mostra se falta** |
| Tags de espécie (`entities[].tags`) | sim | sim | Geradas em `tags/entity_type`; GameTest nas duas pergunta ao jogo, e não ao disco |
| Escala da criatura (`minecraft:generic.scale` em `defaults.attributes`) | sim | sim | Atributo do jogo desde a 1.20.5; muda desenho e colisão juntos. Registra sem aviso nas duas; **ainda não conferido na tela** |
| Atributos, efeitos e equipamento da espécie (`defaults`) | sim | sim | Mesmo vocabulário de `spawn_entity`; um atributo desconhecido é avisado e ignorado, não recusado |
| Caixa de colisão (`width`, `height`) | sim | sim | Colisão, **não** aparência: não escala o desenho |
| Variante de entidade (`spawn.variant`) | sim | sim | Só cavalo, nas duas |
| **Ferramentas e armaduras** (`item.tool` / `item.armor`) | sim | sim | |
| Modelos não cúbicos | sim | sim | O modelo sai da mesma forma que a colisão, gerada no núcleo |
| Lado do mod (`side`) | sim | sim | Validado no núcleo; deduzido quando ausente |

## Eventos

| Evento | Fabric | NeoForge | Observação |
|---|---|---|---|
| `loader_ready`, `server_started`, `server_stopped` | sim | sim | |
| `player_joined`, `player_left` | sim | sim | |
| `tick`, e o agendador de `mod.after` | sim | sim | |
| `block_used`, `block_attacked`, `block_placed`, `block_broken` | sim | sim | |
| `block_random_tick`, `block_neighbor_update` | sim | sim | |
| `block_scheduled` e `schedule_block` | sim | sim | A fila e a do jogo nas duas: gravada com o chunk, volta ao recarregar |
| `item_used`, `item_used_on_block` | sim | sim | |
| `client_screen_opened`, `client_screen_closed` | sim | sim | Relatados pelo cliente; ver `EVENTS.md` |
| Clique em menu | sim | sim | |
| Evento de tela (`click`, `change`, `submit`, `close`) | sim | sim | |
| `entity_spawned`, `entity_damaged`, `entity_died` | sim | sim | Valem para **qualquer** criatura, não só as declaradas; `autoteste` confere nas duas |
| `entity_tamed` | sim | sim | No NeoForge vem de `AnimalTameEvent`; no Fabric não há evento de plataforma e o disparo sai da ponte |
| `mod_reloaded`, `menu_closed` | **não** | **não** | Aceitos no registro e nunca disparados |

## O limite da espécie declarada

Tudo que a matriz acima marca vem de **derivar de uma espécie do jogo**. A base entrega modelo,
animação e comportamento como um pacote fechado, e é isso que faz uma espécie declarada custar dez
linhas de JSON em vez de três sistemas inteiros.

O preço é o limite: dá para **repintar e redimensionar** o golem, não para dizer que a criatura tem
outra forma.

| O que muda a aparência hoje | Como |
|---|---|
| Pele própria | `texture` |
| **Forma própria** | `model` — ossos e caixas em JSON |
| Tamanho | atributo `minecraft:generic.scale` |
| Silhueta de partida | escolher entre as bases suportadas |
| **Animação própria** | **não existe** — a da base move a forma nova |

A forma própria funciona por um detalhe que vale saber: as classes de modelo do jogo recebem uma
raiz e procuram os filhos **por nome** para girá-los. Uma geometria declarada com os mesmos nomes de
osso que a base usa é animada por ela sem que ela saiba que mudou. O preço é que os nomes são um
vocabulário fechado — e um nome errado não dá erro, a peça só não aparece, por isso o loader avisa
na carga.

O comportamento **é** declarável (`ai`), com um vocabulário fechado de metas e alvos: sem declarar,
a espécie herda a IA da base inteira, que é o padrão certo — um lobo declarado se comporta como lobo
sem que ninguém precise descrever o que é ser lobo.

A animação não aparece na matriz porque ela compara plataformas, e falta nas duas igualmente. Estão em `API_GAPS.md`, e são o que resta do Nível 7 de `CHECKLIST_MODLOADER.md`.

## Uma divergência estrutural, e o que foi feito com ela

O momento em que o Lua carrega **não é o mesmo nas duas plataformas**, e não dá para igualar:

| | Fabric | NeoForge |
|---|---|---|
| Onde o Lua carrega | inicialização do mod (`LuaLoaderMod`) | ao servidor subir (`NeoForgeLuaLoader`) |
| Registro do jogo já congelou? | não | **sim** |

Isso significava que registrar conteúdo por script funcionaria no Fabric e **falharia sempre** no
NeoForge. Não é um adaptador atrasado: é o ciclo de vida de cada plataforma.

A primeira resposta foi não oferecer a operação — uma que só vale numa plataforma é pior que
nenhuma, porque o mod passa nos testes de quem o escreveu e some para metade de quem o usa. A
resposta certa foi outra: **dar ao NeoForge o momento que faltava**, em vez de tirar a capacidade do
Fabric.

A **fase de registro** (`registration` no manifesto) roda antes de o jogo congelar os registros, em
cada plataforma no ponto que lhe cabe — inicialização do mod no Fabric, `RegisterEvent` no NeoForge.
O mod declara **o quê**; o adaptador decide **quando**, e nenhum nome de evento do Minecraft aparece
no script. O que o script registra é guardado e aplicado pelo adaptador no seu próprio momento, e
passa pela mesma resolução de herança que o conteúdo do manifesto.

Nessa fase não há mundo: nem servidor, nem jogador, nem bloco para tocar. O contexto é pequeno de
propósito — oferecer o resto seria oferecer chamadas que só podem falhar.

## Como cada linha é conferida

Uma matriz escrita à mão envelhece em silêncio, e é pior que nenhuma porque alguém confia nela. Por
isso as colunas têm quem as verifique:

| Ferramenta | Alcança | Roda nas duas |
|---|---|---|
| `./gradlew :core:test` | o que é agnóstico: manifesto, validação, geometria de tela e de entidade, runtime Lua, fase de registro, herança entre espécies | n/a — é o núcleo |
| `./gradlew runGametest` e `:neoforge:runGameTestServer` | registro, propriedades de bloco, entidade de bloco, NBT, num servidor de verdade | sim, e ambos no CI |
| `/mod autoteste` | as APIs contra o jogo real | sim, pelo mesmo Lua |

O `autoteste` é o que mais pega divergência, porque é o **mesmo script** rodando dos dois lados: uma
plataforma que faz diferente reporta FALHOU onde a outra reporta OK. Foi assim que se descobriu que
`extract_from` respeitava `allow_extract` no NeoForge e não no Fabric.

Os GameTests do NeoForge existem por um motivo parecido: até eles, a coluna do NeoForge nesta tabela
era afirmação de quem escreveu o adaptador, e não resultado de execução.

## Interoperabilidade com mods externos

O loader alcança o inventário de um bloco de outro mod sem saber que ele existe.

| | Fabric | NeoForge |
|---|---|---|
| Como | Fabric Transfer API (`Storage<ItemVariant>`) | Capabilities (`IItemHandler`) |
| Ler conteúdo | sim | sim |
| Inserir e extrair | sim | sim |

**Uma diferença de semântica que vale saber**, porque não é óbvia e já divergiu entre as duas: as
permissões `allow_insert` e `allow_extract` valem para quem acessa **por um lado** — funil, tubo, a
máquina vizinha. Não valem para o mod pela API do loader. Uma fornalha que recusa saída automática
ainda precisa tirar o próprio minério para processá-lo, e esse é o único caminho que ela tem;
bloquear ali tornaria a declaração uma armadilha para quem a escreveu.

---

## Plataformas futuras

O segundo adaptador levou menos de uma hora para expor um bug de segurança latente no núcleo que o
primeiro escondia. Cada plataforma nova paga esse tipo de dividendo, e o custo é só o adaptador.

### Forge (1.20.1 e anteriores)

A mais próxima: NeoForge é um fork do Forge, e boa parte do adaptador é reaproveitável. O trabalho
real não é a API, é a **versão do Minecraft** — mapeamentos, assinaturas e o formato do resource
pack mudam entre versões, e nada disso vive no núcleo. Vale para alcançar quem ficou na 1.20.1, que
ainda é a maioria dos packs grandes.

### Quilt

Fork do Fabric com API compatível. Provavelmente o adaptador Fabric roda quase sem mudança, o que a
torna barata — mas também a que menos ensina, porque não força nenhuma decisão nova.

### Paper / Spigot / Purpur

**A mais interessante das quatro, e a que mais testaria a arquitetura.** É servidor puro: não existe
cliente para instalar o loader, e nem existe registro de bloco novo. Um mod que só usa eventos,
comandos, inventários e menus rodaria; um que declara bloco, não.

Isso é valioso justamente por ser desconfortável: obrigaria o loader a responder "esta plataforma
não registra conteúdo" de forma limpa, em vez de assumir que toda plataforma pode tudo. O contrato
já prevê recusa — `supports_screens` existe por isso —, e essa seria a prova de que o desenho
aguenta.

### Bedrock

Fora de alcance por ora: o motor é outro e a extensão se faz por *behavior packs*, não por
carregamento de código. O manifesto declarativo do loader tem parentesco com esse modelo, o que
torna a ideia tentadora — mas não haveria Lua rodando do lado do servidor.

---

## Quando um adaptador novo entrar

A ordem que funcionou nos dois primeiros, e o porquê de cada passo vir antes do seguinte:

1. **Descobrir mods e carregar scripts.** Sem isso não há o que testar.
2. **Registrar blocos e itens.** O registro do jogo fecha durante a inicialização — conteúdo
   declarado depois disso simplesmente não existe.
3. **Resource pack.** Sem ele o conteúdo aparece como cubo roxo e sem nome: funcional e invisível.
4. **Ponte de mundo e jogador.** É o que a maioria dos scripts usa.
5. **Eventos de bloco e item.** Antes disso o conteúdo aparece e não reage a nada.
6. **Comandos de mod.**
7. **Menus.** Baratos: são a tela de baú do jogo, e não exigem nada do lado do cliente.
8. **Telas, HUD e sobreposições.** Caro: exige canal de rede e renderizador. Entregue inteiro ou
   não entregue — assim que o canal existe, `supports_screens` responde `true`, e um mod que confia
   nisso tentaria abrir uma tela que não existiria.

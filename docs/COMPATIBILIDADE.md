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
| Entidades | criar, remover, ferir, curar, listar, ler dados, aplicar dados | sim | sim |
| Dados declarados | entidade (nome, natureza, corpo, equipamento, efeitos, estado, rotação, variante) e item (aparência, durabilidade, encantamento, atributos) | sim | sim |
| Dados por bloco | ler, gravar | sim | sim |
| Inventário de bloco | capacidades, ler, inserir, extrair | sim | sim |
| Registro do jogo | listar itens, blocos e entidades, receitas, drops | sim | sim |

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
| **Posicionamento** (`placement`: facing, waterlog) | **não** | **não** | Declarado e ignorado nas duas |
| **Aparência** (`render`: layer, emissive, tint) | **não** | **não** | Declarado e ignorado nas duas |
| Propriedades de material e física (`material`, `settings`) | sim | sim | Conferidas por GameTest nas duas |
| Loot de outro bloco (`settings.drops_like`) | sim | sim | |
| Inflamabilidade (`material.flammability`, `burn_spread`) | sim | sim | |
| Item do bloco (`block.item`) | sim | sim | |
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
| `item_used`, `item_used_on_block` | sim | sim | |
| `client_screen_opened`, `client_screen_closed` | sim | sim | Relatados pelo cliente; ver `EVENTS.md` |
| Clique em menu | sim | sim | |
| Evento de tela (`click`, `change`, `submit`, `close`) | sim | sim | |
| `mod_reloaded`, `menu_closed` | **não** | **não** | Aceitos no registro e nunca disparados |

## Como cada linha é conferida

Uma matriz escrita à mão envelhece em silêncio, e é pior que nenhuma porque alguém confia nela. Por
isso as colunas têm quem as verifique:

| Ferramenta | Alcança | Roda nas duas |
|---|---|---|
| `./gradlew :core:test` | o que é agnóstico: manifesto, validação, geometria de tela, runtime Lua | n/a — é o núcleo |
| `./gradlew runGametest` e `:neoforge:runGameTestServer` | registro, propriedades de bloco, entidade de bloco, NBT, num servidor de verdade | sim, e ambos no CI |
| `/mod autoteste` | as APIs contra o jogo real, 26 casos | sim, pelo mesmo Lua |

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

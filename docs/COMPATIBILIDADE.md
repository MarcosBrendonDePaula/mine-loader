# Compatibilidade entre plataformas

O que cada adaptador entrega, e o que falta. A promessa do projeto é que o mesmo mod rode em
qualquer plataforma sem mudar uma linha; esta lista é onde a promessa é conferida.

**Regra:** um recurso que entra em uma plataforma entra nesta lista na mesma mudança, mesmo que só
funcione em uma. É o que impede a diferença de virar surpresa para quem escreve o mod — e o que dá
a lista de trabalho quando um adaptador novo aparece.

Legenda: **sim** funciona · **não** ainda não implementado · **n/a** não se aplica àquela plataforma

---

## Contratos de plataforma

As 53 operações de `GameBridge` e `PlayerHandle`. São o que um script Lua alcança, e a razão de o
núcleo não conhecer Minecraft.

| Área | Operações | Fabric | NeoForge |
|---|---|---|---|
| Servidor e mundo | broadcast, tempo, dimensão, ler e escrever bloco, preencher | sim | sim |
| Inventário do jogador | segurar, contar, dar, tirar | sim | sim |
| Corpo do jogador | posição, vida, teleporte | sim | sim |
| Mensagens | chat, barra de ação | sim | sim |
| Menus | abrir, atualizar, fechar, id aberto | sim | sim |
| Telas desenhadas | abrir, atualizar, fechar, tamanho do cliente | sim | sim |
| HUD e sobreposições | definir, limpar | sim | sim |
| Som e partículas | tocar, emitir | sim | sim |
| Entidades | criar, remover, ferir, listar por raio | sim | sim |
| Dados por bloco | ler, gravar | sim | sim |
| Inventário de bloco | capacidades, ler, inserir, extrair | sim | sim |
| Registro do jogo | listar itens, receitas, drops | sim | sim |

> **Nenhuma operação recusa em nenhuma das duas plataformas.** Quando uma faltar, ela recusa com o
> próprio nome em vez de responder errado — um mod descobre na primeira chamada, e não por um
> comportamento estranho meia hora depois.

## Conteúdo declarado no manifesto

| Recurso | Fabric | NeoForge | Nota |
|---|---|---|---|
| Blocos e itens | sim | sim | |
| Aba criativa | sim | sim | |
| Resource pack gerado (textura, modelo, nome) | sim | sim | |
| Receitas | sim | sim | Geradas como datapack pelo núcleo |
| Tabelas de loot | sim | sim | Idem |
| Tags | sim | sim | Idem |
| Estruturas | sim | sim | O posicionador vive no núcleo |
| Comandos de mod | sim | sim | |
| Dados por bloco (`block_data`) | sim | sim | |
| **Inventário de bloco** (`inventory`) | sim | sim | |
| Variantes visuais (`render.variant_textures`) | sim | sim | |
| **Estados declarados** (`state.properties`) | sim | **não** | Só muda no Fabric |
| Formas de colisão (`shape`) | sim | sim | Nomes prontos ou caixas próprias em `shape.boxes` |
| **Posicionamento** (`placement`: facing, waterlog) | sim | **não** | Só muda no Fabric |
| **Ferramentas e armaduras** (`item.tool` / `item.armor`) | sim | **não** | Só muda no Fabric |
| Modelos não cúbicos | sim | sim | O modelo sai da mesma forma que a colisão, gerada no núcleo |

## Eventos

| Evento | Fabric | NeoForge |
|---|---|---|
| `loader_ready`, `server_started`, `player_joined` | sim | sim |
| `block_used`, `block_attacked`, `block_placed`, `block_broken` | sim | sim |
| `item_used`, `item_used_on_block` | sim | sim |
| Clique em menu | sim | sim |
| Evento de tela (`click`, `change`, `submit`, `close`) | sim | sim |

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

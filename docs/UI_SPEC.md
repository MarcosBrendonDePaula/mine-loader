# Mine Loader — interface customizada

Especificação da camada de interface: telas desenhadas pelo mod e elementos fixos na tela do jogador.

O que existe hoje é uma janela feita sobre a tela de container do jogo — uma grade de itens onde
cada slot é um botão. Ela funciona em cliente vanilla, e é isso que a torna útil. Mas o vocabulário
é item, quantidade e rótulo: não há texto livre, botão com forma própria, barra de progresso, imagem
nem campo de entrada. Este documento descreve o que falta para o mod desenhar de verdade.

## Por que isso exige uma peça nova

O loader inteiro roda no servidor. Nenhum pixel é desenhado lá: quem desenha é o cliente. Um mod em
Lua no servidor não tem como pintar na tela de quem joga.

Portanto, interface customizada depende de três coisas que hoje não existem:

| Peça | Papel |
|---|---|
| Código no cliente | Desenha, recebe mouse e teclado |
| Protocolo entre os dois | Leva a descrição da tela e traz os eventos de volta |
| Linguagem de descrição | Como o mod diz o que quer, sem escrever Java |

O projeto já está preparado para a primeira: o build declara `splitEnvironmentSourceSets()` e um
source set `client` que hoje está vazio.

## Princípio: o cliente interpreta dados, nunca código

O servidor envia uma **descrição** da tela, não um script. O cliente tem um renderizador único que
sabe desenhar qualquer descrição válida.

Isso não é detalhe de implementação, é a decisão central:

- **Segurança.** Um mod remoto já pode enviar Lua para o servidor. Se pudesse enviar código para o
  cliente, cada jogador que entrasse num servidor estaria executando código de terceiros na própria
  máquina. Com dados, o pior caso é uma tela feia ou grande demais, e ambos são limitáveis.
- **Um renderizador só.** Não é uma tela por mod: é uma tela que interpreta descrições. Um mod novo
  não exige código de cliente novo, e o cliente não precisa saber que mods existem.
- **Funciona com mod remoto.** Um mod baixado por URL descreve sua interface no mesmo JSON que
  descreve seus blocos.

## Modelo de tela

Uma tela é uma árvore de elementos com posição e tamanho. O layout é absoluto, em coordenadas de
interface, com âncoras para acompanhar telas de tamanhos diferentes.

```json
{
  "title": "Forja",
  "width": 256,
  "height": 166,
  "elements": [
    { "type": "panel", "x": 0, "y": 0, "w": 256, "h": 166, "color": "#20202080" },
    { "type": "label", "x": 8, "y": 8, "text": "Forja de Cristal", "color": "#FFFFFF" },
    { "type": "progress", "x": 8, "y": 24, "w": 240, "h": 6, "value": 0.4 },
    { "type": "item", "x": 8, "y": 40, "item": "minecraft:iron_ingot", "count": 12 },
    { "type": "button", "id": "forjar", "x": 8, "y": 70, "w": 80, "h": 20, "text": "Forjar" },
    { "type": "input", "id": "nome", "x": 8, "y": 100, "w": 120, "h": 20, "value": "" }
  ]
}
```

Coordenadas são relativas ao canto da tela, que é centralizada. Âncora `center`, `top_left`,
`bottom_right` e afins permitem elementos presos a bordas.

### Elementos

| Tipo | Desenha | Interage |
|---|---|---|
| `panel` | Retângulo com cor e borda | não |
| `label` | Texto, com cor, escala e alinhamento | não |
| `image` | Textura por identificador, com recorte | não |
| `item` | Ícone de item do jogo, com quantidade | opcional |
| `progress` | Barra proporcional a um valor de 0 a 1 | não |
| `button` | Área clicável com texto | clique |
| `input` | Campo de texto editável | digitação |
| `grid` | Grade de itens, com colunas e passo | clique na célula |
| `viewport` | Recorte com rolagem, para o que não cabe | roda do mouse |
| `map` | Grelha compacta de cores com máscara e marcadores | não |

Um conjunto pequeno cobre a maior parte do que um mod precisa. Elementos compostos — listas,
abas, grades — são montados a partir desses, e podem virar tipos próprios quando o padrão aparecer.

## Fundo por regra, e nao por imagem

A janela do Minecraft nao e uma textura: e um retangulo cinza com uma borda clara em cima e a
esquerda e uma escura embaixo e a direita, o que da a impressao de luz vindo do canto superior
esquerdo. Descrever isso como regra deixa o painel acompanhar qualquer tamanho, e dispensa o mod
distribuir e o cliente baixar uma imagem.

```lua
{ type = "panel", style = "vanilla", x = 0, y = 0, w = 176, h = 166 }
{ type = "panel", style = "slot", x = 8, y = 8, w = 16, h = 16, border = 1 }
```

| Estilo | Resultado |
|---|---|
| `flat` | Retangulo chapado. O padrao, e o que existia antes |
| `vanilla` | Cinza e bisel da janela do jogo |
| `slot` | Bisel invertido: o quadrado parece cavado, como um slot |
| `inset` | Igual a `slot`, para areas maiores que um slot |
| `divider` | Linha de separacao, horizontal ou vertical conforme a proporcao |
| `sheet` | A arte que o mod trouxe, em **nove pedacos** |

`border`, `border_light` e `border_dark` ajustam espessura e cores quando o padrao nao serve. Sem
`color`, `vanilla` e `slot` usam os cinzas do jogo.

## Arte propria: a folha e a moldura de nove pedacos

O fundo por regra cobre a janela do jogo. Um mod que porta a interface de outro mod precisa da arte
**daquele** mod, e ate aqui nao tinha como: uma tela podia **nomear** uma textura, e um mod nao
tinha como **entregar** uma -- todo recurso declarado virava textura de bloco ou de item.

**Entregar.** Um recurso de tipo `gui` aterrissa em `assets/<mod>/textures/gui/<nome>.png`. O
caminho e fixo de proposito: a tela precisa escreve-lo a mao, e um nome gerado pelo montador seria
impossivel de adivinhar do lado do Lua.

```json
"resources": {
  "moldura": { "type": "gui", "from": "assets/original/gui/gui_border.png" }
}
```

**Recortar.** Uma folha de interface do jogo tem 256x256 com o painel num canto, entao desenhar o
arquivo inteiro nunca serve. `u` e `v` dizem de onde recortar, e `sheet_w`/`sheet_h` dizem o tamanho
da folha -- o padrao e 256, que e o de toda folha de GUI do jogo.

```lua
{ type = "image", x = 0, y = 0, w = 195, h = 96,
  u = 0, v = 0, texture = "logistica:textures/gui/fabricador_tela.png" }
```

**Esticar sem deformar.** Uma moldura precisa servir a qualquer tamanho de tela, e esticar a imagem
inteira engorda a linha da borda e deforma o canto. `style = "sheet"` desenha em nove pedacos: os
quatro cantos saem inteiros, as quatro bordas esticam num eixo so, e o miolo estica nos dois.

```lua
{ type = "panel", style = "sheet", x = 0, y = 0, w = 256, h = 200,
  texture = "logistica:textures/gui/moldura.png",
  u = 0, v = 0, sw = 256, sh = 199, sheet_w = 256, sheet_h = 248,
  border_top = 23, border_left = 23, border_right = 23, border_bottom = 29 }
```

`sw` e `sh` sao o recorte **na folha**, que e diferente do tamanho **na tela** -- e a distincao que
o nove-pedacos existe para explorar. A espessura e por lado porque arte de mod e assimetrica: a
moldura do Logistic Pipes tem o pe mais alto que o topo, e um numero simetrico dobraria a linha de
baixo.

## O que esta camada NAO faz

Vale dizer com todas as letras, porque tentar o contrario custou uma sessao.

**Esta camada mostra dados. Ela nao mexe em itens.** Nao ha slot, nao ha arrastar, nao ha
shift-clique, nao ha inventario do jogador, e cada clique vai ao servidor e volta com a tela inteira
redesenhada. Serve para um terminal, uma lista, um estado, um painel de configuracao por botao.

Para **mexer em item**, o caminho e o inventario declarado no bloco: ele abre a janela do proprio
jogo, com slots de verdade, arrastar, shift-clique e o inventario do jogador embaixo. Ver
`MOD_FORMAT_SPEC.md`, em `inventory` -- inclusive `ghost`, para um slot que desenha uma intencao
sem guardar item.

Uma tela desenhada a mao que tenta imitar slot vira gesto inventado, e gesto inventado o jogador
chama de inutilizavel -- com razao.

## Botao com icone

Um `button` que declara `item` desenha o icone daquele item, alem do texto. Serve a uma fileira de
abas: um icone de fornalha diz o que a aba faz sem depender de ler o nome, e a fileira nao cresce
com o tamanho de cada nome.

```lua
{ type = "button", id = "aba_forno", x = 8, y = 8, w = 22, h = 22,
  item = "minecraft:furnace", text = "" }
```

Sem texto, o icone fica centralizado; com texto, encostado a esquerda. O icone e desenhado depois do
botao, porque o botao e um widget do jogo e cobriria qualquer coisa pintada antes dele.

O botao nao responde por texto de ajuda -- widgets do jogo tem o proprio caminho para isso. Para dar
nome a uma aba de icone, sobreponha um `item` com `tooltip` na mesma posicao.

### Divisorias

`divider` desenha uma linha de duas cores -- escura e clara, como um sulco -- e nao um retangulo
chapado, que sobre painel cinza sumiria. A orientacao sai da proporcao: mais larga que alta e
horizontal, o contrario e vertical.

```lua
{ type = "panel", style = "divider", x = 8, y = 40, w = 200, h = 2 }
{ type = "panel", style = "divider", x = 120, y = 8, w = 2, h = 160 }
```

## Entidade desenhada

Um mob nao e um item, e por isso nao tem icone: pedir `minecraft:wither_skeleton` num slot de item
deixava o slot vazio. O tipo `entity` desenha a entidade em si, como o jogo faz na tela de
inventario.

```lua
{ type = "entity", entity = "minecraft:cow", x = 8, y = 8, w = 40, h = 50 }
```

O tamanho do desenho sai da altura pedida, e nao de uma escala fixa: um Enderman e uma galinha tem
alturas muito diferentes, e uma escala unica deixaria um dos dois fora do quadro.

Uma entidade que nao pode ser desenhada -- um tipo que nao e vivo, ou um mod que recusa cria-la fora
do mundo -- cai para o ovo de spawn correspondente. Um icone aproximado diz mais que um vazio.

**`item` tambem aceita identificador de entidade.** E conveniencia para lista misturada, como "o que
derruba isto", onde a fonte tanto pode ser um bloco quanto um bicho: em vez de o mod ter de
descobrir qual dos dois e, o cliente desenha o que existir.

## Sombra do texto

Todo texto era desenhado com sombra. Sobre o mundo isso e o certo -- a sombra e o que separa o texto
do cenario. Sobre um painel claro e o contrario: a sombra tambem e escura, as duas se misturam e o
texto fica sujo e mais pesado do que deveria. O jogo desenha titulo de container sem sombra pelo
mesmo motivo.

```lua
{ type = "label", x = 8, y = 8, text = "Forja", color = "#404040", shadow = false }
```

O padrao continua `true`, que serve a HUD e a telas sobre o mundo.

## Tamanho da tela do jogador

O mod monta a interface no servidor e por isso nao enxerga a tela de quem joga. As ancoras sabem
colar um elemento na borda, mas nao sabem se ele cabe: um painel de 156 px ao lado do inventario
cabe em escala 2 e sai da tela em escala 3, porque a escala divide a resolucao.

```lua
local tela = ctx.player.screen_size()   -- { width = 427, height = 240 }, ou nil
if tela then
    local colunas = math.floor(((tela.width - 176) / 2 - 16) / 18)
end
```

Devolve `nil` quando o cliente nao tem o loader ou ainda nao informou -- o mod escolhe um padrao,
em vez de receber um numero inventado que pareceria confiavel. O cliente reinforma a cada mudanca
de escala ou de tamanho da janela.

## Grade de itens

Uma grade 9x5 exigia 45 elementos com `x` e `y` calculados um a um, e cada ajuste de espaçamento
significava recalcular todos. `grid` faz isso ser um elemento:

```lua
{ type = "grid", id = "itens", x = 8, y = 8, columns = 9, cell = 18, items = {
    "minecraft:stone",
    { item = "minecraft:diamond", count = 3, tooltip = "Diamante\nRaro" }
} }
```

A forma curta -- so o identificador -- existe porque o caso comum e justamente uma lista de itens.
A completa aceita `count` e `tooltip`.

| Campo | Padrao | O que faz |
|---|---|---|
| `columns` | 9 | Itens por linha |
| `cell` | 18 | Passo entre celulas, em pixels; 18 e o do inventario |
| `items` | obrigatorio | Ate 512 celulas |

O clique volta como acao `click`, com o **indice da celula** no valor, a partir de 1. O script nao
recebe posicao em pixels: recebe qual item foi apontado.

```lua
mod.screen("catalogo", function(ctx)
    if ctx.ui.element == "itens" then
        local item = minha_lista[tonumber(ctx.ui.value)]
    end
end)
```

O texto de ajuda de uma grade e por celula, e nao da grade inteira -- e o que faz o nome do item
aparecer ao passar por ele, como no inventario.

## Rolagem

Uma lista maior que a janela precisa de duas coisas: um recorte, e um deslocamento que responda a
roda do mouse. `viewport` e o recorte; os elementos que apontam para ele com `group` rolam dentro.

```lua
{ type = "viewport", id = "area", x = 8, y = 8, w = 144, h = 108, content = 400 },
{ type = "grid", id = "itens", group = "area", x = 0, y = 0, columns = 8, items = celulas }
```

Dentro de um grupo, `x` e `y` passam a ser relativos ao canto do viewport. Monta-se a lista uma vez
e ela rola sem recalcular coordenada nenhuma.

**O deslocamento vive no cliente.** Rolar nao envia pacote: seria uma ida e volta pela rede a cada
entalhe da roda, e a lista andaria com atraso visivel. O servidor declara o que existe; o cliente
decide o que esta a vista.

**`content` e declarado, nao medido.** E a altura total do conteudo, e diz ate onde a rolagem pode
ir. O cliente nao mede sozinho porque parte do conteudo pode nem estar na descricao atual. Sem
`content`, ou com ele menor que a altura do viewport, nao ha o que rolar -- e o erro mais provavel
ao montar a primeira lista.

**`button` e `input` dentro de um grupo sao recusados.** Os dois viram widgets de verdade do jogo,
posicionados uma vez e desenhados pelo proprio jogo -- eles nao passam pelo recorte nem pela
rolagem. Uma tela que os colocasse dentro de um viewport mostraria todos de uma vez, parados, por
cima do resto. Aceitar e desenhar errado seria pior que recusar: quem escreve o mod descobriria no
jogo, e nao na mensagem.

Uma lista rolavel e clicavel monta-se com uma `grid` no grupo: as celulas rolam com o recorte, e o
clique volta identificando a celula.

## Onde cada peça vive

Rede é específica de cada plataforma: Fabric, NeoForge e Paper têm APIs de pacote diferentes e
incompatíveis. Se o núcleo falasse de rede diretamente, a camada de interface amarraria o loader ao
Fabric — exatamente o que a separação em módulos existe para evitar.

Por isso o transporte fica atrás do contrato, como todo o resto:

| Camada | Responsabilidade | Conhece rede |
|---|---|---|
| `core` | Modelo da tela, validação, limites, API Lua, roteamento do evento ao mod dono | não |
| adaptador | Serializar, enviar, receber e devolver ao núcleo | sim |
| cliente do adaptador | Desenhar e capturar mouse e teclado | sim |

O núcleo trata a tela como um valor: uma árvore de elementos validada, com o total de elementos e o
tamanho do texto dentro dos limites. Ele entrega essa árvore ao adaptador e recebe de volta eventos
já traduzidos para termos próprios — id da tela, id do elemento, ação e valor.

O contrato acrescentado ao `PlayerHandle` é pequeno de propósito:

```java
/** Abre uma tela desenhada. Devolve false quando o cliente não tem o loader. */
boolean openScreen(String screenId, String descriptionJson);

/** Substitui o conteúdo da tela aberta sem reabri-la. */
boolean updateScreen(String descriptionJson);

/** Fecha a tela do loader, se houver. */
void closeScreen();

/** Define os elementos fixos na tela do jogador. Lista vazia limpa. */
boolean setHud(String descriptionJson);

/** Desenha sobre uma tela que o proprio jogo abre. */
boolean setOverlay(String overlayId, String descriptionJson);

/** Remove uma sobreposicao registrada. */
boolean clearOverlay(String overlayId);
```

A descrição trafega como texto JSON porque é o formato que o manifesto já usa, o que permite a um mod
declarar uma tela no próprio `mod.json` e o núcleo repassá-la sem conhecer nenhum tipo de pacote.

O adaptador NeoForge implementa os mesmos métodos com a rede dele, e nenhum mod precisou mudar — era
a aposta desta separação, e ela se pagou: as duas plataformas concordam sobre onde cada elemento
fica, e discordam só sobre como pintá-lo.

## O padrao de comunicacao

O protocolo e definido aqui, e nao em cada adaptador. Um adaptador escolhe como transportar os
bytes; o que os bytes significam e comum a todos. Sem isso, dois adaptadores acabariam com telas
sutilmente diferentes e um mod deixaria de ser portavel — que e justamente o que a separacao em
camadas existe para impedir.

### Canais

| Canal | Sentido | Carga |
|---|---|---|
| `lua_loader:screen_open` | servidor para cliente | versao, id da tela, descricao |
| `lua_loader:screen_update` | servidor para cliente | descricao |
| `lua_loader:screen_close` | servidor para cliente | vazio |
| `lua_loader:hud_set` | servidor para cliente | descricao |
| `lua_loader:overlay_set` | servidor para cliente | versao, id, descricao com o alvo |
| `lua_loader:overlay_clear` | servidor para cliente | id |
| `lua_loader:screen_event` | cliente para servidor | versao, id da tela, id do elemento, acao, valor |
| `lua_loader:client_info` | cliente para servidor | versao, largura e altura da tela |

Todo canal usa o mesmo namespace do loader, para que o conjunto seja reconhecivel e para nao colidir
com outros mods.

### Versao do protocolo

Cada carga comeca com um numero de versao. As regras de compatibilidade:

| Situacao | Comportamento |
|---|---|
| Cliente com versao menor | O servidor nao abre a tela e a chamada Lua devolve `false` |
| Cliente com versao maior | O cliente ignora campos que nao conhece e desenha o resto |
| Elemento de tipo desconhecido | E ignorado; a tela abre sem ele, e nao quebrada |

A versao muda quando o significado de um campo existente muda. Acrescentar um tipo de elemento ou um
campo opcional nao muda a versao, porque as duas regras acima ja cobrem esse caso.

### Formato da carga

A descricao trafega como texto JSON em UTF-8. Nao e o formato mais compacto, e essa e uma escolha
consciente: e o mesmo formato do manifesto, entao um mod pode declarar uma tela no `mod.json`, o
nucleo repassa sem conhecer pacote nenhum, e o conteudo e legivel ao depurar. O custo e limitado
pelo teto de tamanho.

### Acoes do evento

O evento que volta ao servidor tem um vocabulario fechado, para o Lua nao precisar interpretar
strings livres vindas do cliente:

| Acao | Quando | Valor |
|---|---|---|
| `click` | Botao pressionado | vazio |
| `change` | Campo de texto alterado | conteudo do campo |
| `submit` | Enter em um campo | conteudo do campo |
| `close` | Tela fechada pelo jogador | vazio |

Uma acao desconhecida e descartada pelo servidor: o cliente nao dita o vocabulario.

## Protocolo, nos dois adaptadores

Dois sentidos, ambos com carga limitada.

| Sentido | Pacote | Conteúdo |
|---|---|---|
| servidor → cliente | `open_screen` | descrição completa da tela |
| servidor → cliente | `update_screen` | descrição completa, sem reabrir |
| servidor → cliente | `close_screen` | fecha a tela do loader |
| servidor → cliente | `set_hud` | elementos fixos na tela |
| cliente → servidor | `screen_event` | id da tela, id do elemento, ação e valor |

A descrição é enviada inteira, e não como diferença. É mais tráfego, mas remove uma classe inteira
de bugs de sincronização: não existe estado divergente entre os dois lados, porque o cliente nunca
guarda o que o servidor não mandou.

### Cliente sem o loader

O servidor precisa saber se o jogador consegue receber a tela. Fabric informa quais canais o cliente
registrou; um jogador sem o loader simplesmente não tem o canal. Nesse caso:

- `open_screen` não é enviado e a chamada Lua devolve `false`;
- o mod decide o que fazer, e a janela de container continua disponível como alternativa que
  funciona em qualquer cliente.

Isso mantém a promessa atual: um mod que usa apenas a janela de itens roda em cliente vanilla.

## Sobreposicao em tela do jogo

Abrir uma tela e participar de uma existente sao coisas diferentes. `open_screen` substitui o que
estava na frente; uma sobreposicao acompanha o inventario, o forno ou o menu de pausa. Sem ela nao
ha como acrescentar um botao ao menu de pausa, um painel ao lado do bau ou um aviso na tela de
morte, porque nenhum deles pode substituir a tela em que aparece.

```lua
ctx.player.set_overlay("catalogo", {
    target = "inventory",
    elements = {
        { type = "panel", anchor = "gui_top_right", x = 4, y = 0, w = 82, h = 166,
          color = "#101018E0" },
        { type = "item", anchor = "gui_top_right", x = 10, y = 22,
          item = "minecraft:iron_ingot", tooltip = "Lingote de ferro" },
        { type = "button", id = "proxima", anchor = "gui_top_right",
          x = 48, y = 140, w = 34, h = 18, text = ">" }
    }
})

ctx.player.clear_overlay("catalogo")
```

O clique volta pelo mesmo `mod.screen` com o mesmo nome: para o mod, um botao dentro de uma
sobreposicao chega igual a um botao de uma tela propria.

### Alvos

O mod nomeia o alvo, nunca a classe da tela: classes do cliente mudam entre versoes do jogo e nao
existem em outra plataforma, entao cita-las quebraria a portabilidade que a separacao em camadas
existe para manter. Um alvo que aquele cliente nao reconhece simplesmente nunca casa.

| Alvo | Tela |
|---|---|
| `any` | qualquer tela |
| `container` | qualquer tela com inventario |
| `inventory` | inventario do jogador |
| `creative` | inventario criativo |
| `crafting` | mesa de trabalho |
| `furnace` | forno, forno de fundicao e defumador |
| `chest` | bau e barril |
| `anvil` | bigorna |
| `pause` | menu de pausa |
| `death` | tela de morte |
| `title` | tela inicial |

### Ancoras de janela

Cinco ancoras novas — `gui_top_left`, `gui_top_right`, `gui_left`, `gui_right` e `gui_center` —
referem-se ao retangulo da tela do jogo por baixo, e nao a tela toda. E o que permite colar um
painel a direita do inventario em qualquer resolucao, sem repetir a conta que o cliente ja faz para
centralizar aquela janela.

Fora de uma tela com inventario nao ha janela a que se prender, e elas equivalem as ancoras comuns.

### O que a sobreposicao nao faz

| Limite | Motivo |
|---|---|
| Sem `input` | Um campo dentro de uma tela do jogo disputaria o foco com a tela |
| Botao so muda na proxima abertura | O widget e criado quando a tela abre; o desenho, esse, e por quadro |
| Sem acesso aos slots da tela | Ler ou mexer no inventario daquela tela exigiria outro protocolo |
| Teto de 16 por jogador | Uma sobreposicao por mod ja e bastante; o teto evita acumulo silencioso |

Um reenvio com o mesmo id substitui a sobreposicao anterior. Sair do servidor limpa todas: nada
vaza para a sessao seguinte.

## Texto de ajuda

O campo `tooltip` existia no protocolo desde o inicio e nunca era desenhado — o valor trafegava,
chegava ao cliente e parava ali. Agora ele aparece quando o cursor para sobre o elemento, em uma
tela propria e em uma sobreposicao. Linhas separam-se por `
`.

```lua
{ type = "item", x = 8, y = 8, item = "minecraft:diamond",
  tooltip = "Diamante
Custa 12 esmeraldas" }
```

O HUD nao desenha texto de ajuda: sem cursor, nao ha o que apontar.

### Item sem texto declarado

Um `item` ou uma celula de `grid` sem `tooltip` responde com o **nome traduzido** do item e o
identificador abaixo. O servidor nao teria como traduzir: ele so tem o identificador, e o idioma e
escolha de cada cliente. Mostrar so o identificador obrigaria quem joga a decora-los; mostrar so o
nome tiraria de quem escreve o mod a informacao de que precisa.

Declarar `tooltip` continua substituindo os dois -- e o que fazer quando a celula representa varios
itens, como uma posicao de receita que aceita qualquer tabua.

A area considerada e a do elemento. `label` e `item` dimensionam-se pelo conteudo — a largura do
texto e 16 por 16, respectivamente — e os demais usam `w` e `h`. Quando dois elementos se sobrepoem,
vale o ultimo declarado, que e o que ficou por cima.

## HUD

O HUD é diferente de uma tela: fica sobre o jogo, não captura o mouse e não pausa nada. Usa os
mesmos elementos, sem `button` e `input`, e é desenhado por um gancho de renderização em vez de uma
tela.

```lua
ctx.player.set_hud({
    { type = "label", x = 4, y = 4, text = "Missao: 3/8", color = "#FFD700" },
    { type = "progress", x = 4, y = 16, w = 80, h = 4, value = 0.375 }
})
```

Um HUD por mod, para que dois mods não briguem pelo mesmo canto sem que ninguém perceba: cada um
desenha o seu, e o loader os empilha em ordem de carga.

`set_hud` devolve se o HUD chegou ao cliente, como `open_screen` e `set_overlay`. Era a única das
três a não responder nada, e um mod não tinha como saber que desenhou para um cliente que não tem o
loader — o desenho simplesmente não aparecia, sem nada no log.

### O HUD não se atualiza sozinho

O que foi enviado fica na tela até ser substituído. Um HUD montado a partir de um valor que muda —
progresso, contador, vida — precisa ser reenviado sempre que esse valor mudar, senão congela no
estado em que estava quando foi definido.

É o erro mais fácil de cometer: definir o HUD ao entrar no mundo, com o progresso ainda em zero, e
concluir que a barra está quebrada. Ela está correta e desatualizada.

O padrão que funciona é uma função que desenha o HUD a partir do estado, chamada em todo ponto que
altera esse estado:

```lua
local function atualizar_hud(ctx)
    ctx.player.set_hud({
        { type = "label", x = 6, y = 6, text = "Progresso: " .. ctx.state.contador },
        { type = "progress", x = 6, y = 20, w = 88, h = 5,
          progress = math.min(1.0, ctx.state.contador / 10) }
    })
end
```

## API Lua pretendida

```lua
-- Registra a lógica da tela, como já se faz com mod.menu
mod.screen("forja", function(ctx)
    if ctx.ui.element == "forjar" then
        ctx.player.update_screen(desenhar(ctx))
    elseif ctx.ui.element == "nome" then
        ctx.state.nome = ctx.ui.value
    end
end)

-- Abre para um jogador; devolve false se o cliente não tiver o loader
local abriu = ctx.player.open_screen("forja", desenhar(ctx))
```

O contexto do evento traz `ctx.ui.screen`, `ctx.ui.element`, `ctx.ui.action` e `ctx.ui.value`.

## Elemento `map`

`map` é uma primitiva de HUD para dados cartográficos já amostrados pelo mod. Não lê chunks no
cliente, não aceita código e não conhece classes de Minecraft no core. O mod envia `columns * rows`
cores em `cells`, e o bridge desenha a grelha, a moldura, a forma `round`/`square`, a bússola e os
marcadores normalizados.

```lua
{
    type = "map", anchor = "top_right", x = 4, y = 4, w = 150, h = 150,
    columns = 25, rows = 25, cells = cores, round = true,
    direction_x = 1, direction_z = 0,
    markers = {
        { type = "player", x = 0.5, z = 0.5, color = "#F5D547" },
        { type = "waypoint", label = "Casa", x = 0.8, z = 0.2, color = "#55FF55" }
    }
}
```

O elemento permite até 64 colunas, 64 linhas e 4096 células por payload. Um marcador pode ser
`player`, `waypoint` ou `entity`; `x` e `z` ficam entre 0 e 1. O contrato não promete textura de
bloco, iluminação ou mapa-múndi: essas são decisões do mod que produz as cores, enquanto a máscara e
a composição visual são responsabilidades do cliente.

## Tamanho dos elementos

| Tipo | Tamanho |
|---|---|
| `button`, `input` | `w` e `h` obrigatorios e maiores que zero |
| `panel`, `progress`, `image` | `w` e `h` definem a area desenhada |
| `label`, `item` | Dimensionam-se sozinhos pelo conteudo |

Um elemento interativo sem tamanho era aceito em silencio, e o cliente arbitrava um minimo — o que
colocava o campo fora do lugar esperado, transbordando a janela. Agora a descricao e recusada com o
motivo.

O tamanho da janela nao recorta o que e desenhado: um elemento em `y` proximo de `height` continua
sendo desenhado alem da borda. Ao posicionar, considere que a area vai de `y` ate `y + h`.

## Ordem de desenho

A tela e desenhada em tres camadas, nesta ordem:

| Camada | O que |
|---|---|
| 1 | Fundo: desfoque e escurecimento |
| 2 | Elementos do mod: `panel`, `label`, `image`, `item`, `progress`, `map` |
| 3 | Widgets do jogo: `button` e `input` |

A ordem e explicita porque o metodo de renderizacao do jogo repinta o fundo antes de desenhar os
proprios widgets. Chama-lo depois dos elementos do mod apagaria a camada 2 inteira: o painel sumia
atras do fundo e apenas os botoes sobreviviam. O renderizador mantem a propria lista de widgets e
os desenha por ultimo.

Dentro da camada 2, os elementos aparecem na ordem em que foram declarados: o primeiro fica atras.
Um painel de fundo deve ser o primeiro elemento da lista.

## Fundo da tela

Desde a 1.20.5 o jogo desfoca o mundo atras de qualquer tela. Isso serve a um menu de pausa, mas
atrapalha um painel consultado durante a partida, em que o jogador quer continuar vendo o que
acontece.

| Campo | Padrao | Efeito |
|---|---|---|
| `blur` | `false` | Desfoca o mundo atras, como um menu do jogo |
| `dim` | `true` | Escurece o fundo, para a janela ganhar contraste |

```lua
ctx.player.open_screen("menu", {
    blur = true,     -- para uma tela de menu
    dim = true,
    elements = { ... }
})
```

## Redesenhar com campos de texto

Um campo de texto dispara `change` a cada tecla, e um script costuma redesenhar a tela em resposta
ao proprio evento. Se cada redesenho recriasse os widgets, o jogador perderia o foco e a posicao do
cursor a cada letra digitada.

Por isso o renderizador reaproveita os campos que continuam existindo, identificados pelo `id`: o
texto, o cursor, a selecao e o foco sobrevivem ao redesenho. Um campo com `id` novo e criado do
zero, e um que desapareceu da descricao e descartado.

A consequencia para quem escreve o mod: o `value` de um campo so e aplicado quando ele aparece pela
primeira vez. Para forcar um valor depois, use um `id` diferente ou feche e reabra a tela — do
contrario o loader estaria disputando o campo com quem esta digitando nele.

## Escala de texto

A fonte do Minecraft e bitmap. Uma escala inteira multiplica cada pixel e mantem o texto nitido;
uma escala fracionaria como 1.5 interpola e deixa o resultado borrado.

| Escala | Resultado |
|---|---|
| 1, 2, 3 | Nitido |
| 1.5, 1.25, 2.5 | Borrado |

O loader aceita qualquer valor entre 0.25 e 4, porque um mod pode querer o efeito, mas quem busca
texto legivel deve usar numero inteiro.

## Limites

Como qualquer superfície que aceita dados de terceiros, precisa de teto:

| Limite | Motivo |
|---|---|
| Elementos por tela | Uma tela com milhares de elementos trava o cliente |
| Tamanho do pacote | Descrição enorme prende a rede |
| Frequência de atualização | Redesenhar a cada tick é desperdício |
| Tamanho de texto e de campo | Texto sem limite vira ataque de memória |

O renderizador ignora elemento desconhecido em vez de falhar: um cliente com loader antigo abre a
tela sem os elementos que não entende, e não uma tela quebrada.

## Descobrir por que a tela saiu torta

O log responde a pergunta errada quando uma tela sai errada: ele diz que a descricao foi enviada, e
foi. A conta que transforma `x`, `y` e `anchor` em posicao na tela e o que ninguem ve, e e onde os
defeitos moram.

`dump_screen` refaz essa conta e mostra o resultado -- onde cada elemento foi parar, e o que esta
errado com isso. Vai para o log e volta como texto, entao serve tanto para ler depois quanto para o
mod mostrar na propria tela:

```lua
local relatorio = ctx.player.dump_screen(minha_tela)
```

**Usa o mesmo codigo do nucleo que o cliente usa**, e nao uma reimplementacao: um diagnostico com
aritmetica propria divergiria do desenho real, e mentiria justamente no caso que se foi investigar.
Quando nao ha cliente, assume 427 por 240 -- o tamanho de uma janela padrao.

O teste `ScreenOverlapTest`, no nucleo, cobre a mesma matematica: sobreposicao e elemento fora da
tela deixaram de ser algo que so se ve no jogo.

## Estado da implementacao

| Peca | Situacao |
|---|---|
| Vocabulario do protocolo, no nucleo | pronto |
| Validacao da descricao, no nucleo | pronto |
| Contrato no `PlayerHandle` | pronto |
| Transporte nos adaptadores Fabric e NeoForge | pronto |
| Cliente com tela generica, nos dois | pronto |
| `dump_screen` e `ScreenOverlapTest` | pronto |
| `panel`, `label`, `progress`, `item`, `image`, `button`, `input` | pronto |
| HUD genérico e mapa compacto | pronto |
| Deteccao de cliente sem loader | pronto |
| Sobreposicao em tela do jogo | pronto |
| Texto de ajuda | pronto |
| Grade de itens | pronto |
| Rolagem com viewport | pronto |
| Painel com bisel, sem textura | pronto |
| Texto sem sombra | pronto |
| Tamanho da tela informado ao mod | pronto |
| Hotkeys declarativas globais (`client.input.keybind`) | pronto; ver [HOTKEYS.md](HOTKEYS.md) |

O que continua fora: abas; arrastar; barra de rolagem visivel, com alca arrastavel -- hoje a
rolagem so responde a roda; campo de texto dentro de uma sobreposicao; leitura ou escrita nos slots
da tela sobreposta; recorte de textura em `image`, que hoje assume a folha inteira do tamanho do elemento; e
captura de teclas dentro de campos ou widgets de uma tela declarada. Hotkeys globais fora de telas já
existem como capability separada; ver [HOTKEYS.md](HOTKEYS.md).

## Ordem de construção

1. Source set de cliente e o pacote de abertura de tela, com `panel` e `label` — prova o caminho
   inteiro de ponta a ponta.
2. `button` e o evento de volta ao Lua — fecha o ciclo de interação.
3. `image`, `item` e `progress` — o que a maioria das telas usa.
4. `input` — exige guardar o que foi digitado entre atualizações.
5. HUD.
6. Detecção de cliente sem loader e o caminho alternativo.

Cada etapa é utilizável sozinha, e a primeira é a que remove a maior incerteza: se o pacote chega,
a tela abre e o clique volta, o resto é acrescentar tipos de elemento.

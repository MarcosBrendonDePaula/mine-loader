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

Um conjunto pequeno cobre a maior parte do que um mod precisa. Elementos compostos — listas,
abas, grades — são montados a partir desses, e podem virar tipos próprios quando o padrão aparecer.

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
void setHud(String descriptionJson);
```

A descrição trafega como texto JSON porque é o formato que o manifesto já usa, o que permite a um mod
declarar uma tela no próprio `mod.json` e o núcleo repassá-la sem conhecer nenhum tipo de pacote.

Um adaptador NeoForge implementaria os mesmos quatro métodos com a rede dele, e nenhum mod
precisaria mudar.

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
| `lua_loader:screen_event` | cliente para servidor | versao, id da tela, id do elemento, acao, valor |

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

## Protocolo, no adaptador Fabric

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

## Estado da implementacao

| Peca | Situacao |
|---|---|
| Vocabulario do protocolo, no nucleo | pronto |
| Validacao da descricao, no nucleo | pronto |
| Contrato no `PlayerHandle` | pronto |
| Transporte no adaptador Fabric | pronto |
| Cliente com tela generica | pronto |
| `panel`, `label`, `progress`, `item`, `image`, `button`, `input` | pronto |
| HUD | pronto |
| Deteccao de cliente sem loader | pronto |

O que continua fora: elementos compostos como lista com rolagem, abas e grade; arrastar; e qualquer
elemento que exija estado proprio no cliente alem do texto ja digitado.

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

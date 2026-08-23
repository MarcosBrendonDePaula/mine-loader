# Mine Loader — engine de HTML/CSS para interfaces

Design de uma camada de marcação e estilo sobre a interface já existente (`UI_SPEC.md`). Este
documento não é implementação: é a decisão de o que construir, em que ordem, e o que deliberadamente
não construir.

## O problema que isto resolve

A camada atual funciona e é honesta sobre o que é: posicionamento absoluto. Cada elemento carrega
`x`, `y`, `w`, `h` em pixels, e quem escreve o mod calcula tudo à mão. Isso é aceitável para uma
tela de seis elementos e insuportável a partir daí:

```lua
-- O que se escreve hoje para uma lista de três linhas com rótulo e botão
{ type = "label",  x = 8,  y = 8,  text = "Ferro" },
{ type = "button", x = 180, y = 6,  w = 60, h = 20, id = "b1", text = "Forjar" },
{ type = "label",  x = 8,  y = 32, text = "Ouro" },
{ type = "button", x = 180, y = 30, w = 60, h = 20, id = "b2", text = "Forjar" },
{ type = "label",  x = 8,  y = 56, text = "Diamante" },
{ type = "button", x = 180, y = 54, w = 60, h = 20, id = "b3", text = "Forjar" },
```

Três problemas concretos, todos de manutenção e nenhum de capacidade:

1. **Aritmética manual.** Trocar a altura da linha de 24 para 28 exige reescrever todo `y`.
2. **Nada se adapta.** Um texto traduzido mais longo, uma fonte diferente, uma tela de outro
   tamanho — nada acomoda, porque nada é calculado.
3. **Repetição sem abstração.** Não há como dizer "estes três itens são uma coluna"; só há como
   dizer onde cada um cai.

Um subconjunto de HTML/CSS resolve exatamente isso, e é por isso que vale a pena: não porque a web
seja um bom modelo de UI, mas porque **fluxo automático** e **estilo separado da estrutura** são
duas ideias que economizam trabalho real. Tudo em HTML/CSS que não sirva a essas duas ideias é peso
morto aqui e deve ficar de fora.

O mesmo exemplo, com o que este documento propõe:

```html
<style>
  .linha { display: flex; align-items: center; padding: 4px; gap: 8px; }
  .nome  { flex: 1; color: #FFFFFF; }
</style>
<div class="linha"><span class="nome">Ferro</span>   <button id="b1">Forjar</button></div>
<div class="linha"><span class="nome">Ouro</span>    <button id="b2">Forjar</button></div>
<div class="linha"><span class="nome">Diamante</span><button id="b3">Forjar</button></div>
```

Nenhuma coordenada. Mudar a altura da linha é mudar um `padding`.

---

## Recomendação principal, antes dos detalhes

Construir uma engine **pequena e deliberadamente incompleta**, com quatro decisões estruturantes:

| Decisão | Escolha | Alternativa rejeitada |
|---|---|---|
| Onde parsear | **No cliente**, HTML/CSS cru trafega | Parsear no servidor e mandar árvore resolvida |
| Como parsear | **Parser próprio**, ~900 linhas, sem dependência nova | jsoup (400 KB), CSS Parser (W3C SAC) |
| Layout | **Bloco + inline simples + flexbox de um eixo** | Flexbox completo, grid, float, position absoluta |
| Relação com o existente | **Compila para o protocolo atual, estendido** | Substituir ou coexistir como sistemas paralelos |

O nome informal para isso é *layout de caixa em uma passada*: cada nó é medido uma vez, posicionado
uma vez, e o resultado é uma lista plana de retângulos — exatamente o vocabulário que `LuaScreen` já
desenha hoje.

---

## Escopo: o subconjunto

### Tags suportadas

| Tag | Papel | Por que entra |
|---|---|---|
| `<div>` | Caixa de bloco genérica | É o container. Sem ele não há nada. |
| `<span>` | Caixa inline genérica | Texto com estilo dentro de uma linha. |
| `<p>` | Bloco de texto | Açúcar de `<div>` com margem vertical; barato e familiar. |
| `<h1>`–`<h3>` | Título | Açúcar de `<div>` com `scale` maior e negrito. Três níveis bastam. |
| `<button>` | Botão | Já existe no protocolo; herda o evento `click`. |
| `<input>` | Campo de texto | Já existe; `type="text"` e `type="password"` apenas. |
| `<img>` | Textura do jogo | `src="modid:textures/gui/x.png"`, não URL. |
| `<item>` | Ícone de item — **tag própria** | Não é HTML. É a única invenção, e ela se paga. |
| `<progress>` | Barra de progresso | Existe em HTML real, mapeia direto no elemento atual. |
| `<br>` | Quebra de linha | Uma linha de código no layout inline. |
| `<ul>`/`<li>` | Lista | Açúcar de bloco com marcador. Opcional, etapa tardia. |
| `<style>` | CSS embutido | Único lugar onde CSS pode vir. |

`<item id="minecraft:iron_ingot" count="12">` é a única tag inventada. A alternativa purista seria
`<img src="item://minecraft:iron_ingot">`, e ela é pior: um item não é uma imagem — tem contagem,
durabilidade, encantamento brilhando, e tooltip com nome traduzido. Forçá-lo em `<img>` mentiria
sobre o que é.

### Tags explicitamente fora

| Tag | Por que não |
|---|---|
| `<script>` | **Nunca.** É a linha que o projeto inteiro existe para não cruzar. Encontrar essa tag é erro de validação no servidor, não algo ignorado silenciosamente. |
| `<a>`, `<iframe>`, `<video>`, `<audio>`, `<object>`, `<embed>` | Todas puxam recurso externo. Um mod remoto pedindo o cliente para buscar uma URL é um vetor de rastreamento de IP e de tráfego não solicitado. |
| `<form>` | O modelo de submissão HTTP não existe aqui. `<button>` mais evento cobre o caso. |
| `<table>` | O algoritmo de tabela é o mais complexo do CSS depois de grid — largura de coluna depende de todo o conteúdo, com duas passadas e regras de colapso de borda. Uma tabela simples é `display:flex` com colunas; uma tabela complexa não deveria estar num GUI de mod. |
| `<select>`, `<textarea>`, `<canvas>`, `<svg>` | Cada um exige um widget novo e estado próprio no cliente. Podem entrar depois, um a um, se a demanda aparecer. |
| Qualquer tag desconhecida | Tratada como `<div>` anônimo: o conteúdo aparece, a tag é ignorada. Falhar seria pior. |

### Propriedades CSS suportadas

| Grupo | Propriedades | Notas |
|---|---|---|
| Caixa | `width`, `height`, `min-width`, `max-width`, `padding`, `margin`, `border`, `box-sizing` | `box-sizing: border-box` é o **padrão**, não `content-box`. Ver abaixo. |
| Fluxo | `display: block \| inline \| inline-block \| flex \| none` | Cinco valores. Nada mais. |
| Flex | `flex-direction`, `justify-content`, `align-items`, `gap`, `flex` (só o *grow*) | Um eixo, sem `wrap`. |
| Texto | `color`, `text-align`, `font-size`, `font-weight: bold`, `text-shadow: none` | `font-size` vira `scale`, não pixels reais. |
| Pintura | `background-color`, `background-image`, `border-color`, `border-width`, `opacity` | `background-image` aceita textura do jogo e nine-slice. |
| Overflow | `overflow: hidden \| scroll` | `scroll` é a etapa mais cara; ver plano incremental. |

Unidades: `px`, `%`, `auto`. E só. Sem `em`, `rem`, `vh`, `vw`, `calc()`.

**`em` e `rem` ficam fora porque a fonte do Minecraft não é escalável de verdade.** A fonte bitmap
tem 9 px de altura de linha; escalar por 1,5 produz interpolação borrada. As escalas úteis são
inteiras e poucas, então `font-size` aceita apenas um pequeno conjunto (`1x`, `1.5x`, `2x`, `3x`) e
mapeia no `scale` que o renderizador já tem. Fingir suporte a `font-size: 13px` seria prometer uma
precisão que a fonte não entrega.

**`calc()` fica fora** porque exige um parser de expressão, um passo de avaliação e regras de
mistura de unidades — e resolve um problema que `flex: 1` já resolve nos casos reais de UI de mod.

### Seletores CSS suportados

| Seletor | Exemplo | Especificidade |
|---|---|---|
| Tag | `button` | 1 |
| Classe | `.linha` | 10 |
| Id | `#forjar` | 100 |
| `style=` inline | `<div style="...">` | 1000 |

Sem descendente (`.a .b`), sem filho (`>`), sem pseudo-classes exceto **`:hover`**, sem
`:nth-child`, sem media queries.

`:hover` entra sozinho porque é a única pseudo-classe que muda a sensação da interface — um botão
que não reage ao mouse parece quebrado. Custa pouco: um teste de retângulo por frame, aplicado a uma
segunda tabela de estilos já calculada.

Seletor descendente fica fora porque exige subir a árvore por candidato a cada casamento, e classes
resolvem o mesmo problema com uma palavra a mais no atributo. É o corte com melhor razão
custo/benefício de toda a lista.

### Herança

Herdam-se apenas: `color`, `text-align`, `font-size`, `font-weight`. Todo o resto começa no valor
inicial. Isso é o mesmo que o CSS real faz, e é o que evita que `padding` de um container vaze para
os filhos.

---

## Layout: a parte difícil

Esta é a seção que decide se o projeto é viável. A ordem abaixo vai do barato ao caro; a
recomendação é parar no nível 3.

### Nível 0 — Modelo de caixa

Toda caixa tem quatro anéis: conteúdo, `padding`, `border`, `margin`. A decisão que mais importa
aqui é o `box-sizing` padrão.

**Recomendação: `border-box` por padrão.** Em `content-box` (o padrão da web), `width: 100px;
padding: 8px; border: 1px` produz uma caixa de 118 px de largura. Todo desenvolvedor web moderno
começa seu CSS com `* { box-sizing: border-box }` justamente porque `content-box` surpreende. Não há
razão para herdar um erro histórico de 1996 num sistema novo. `width: 100px` significa 100 px na
tela; ponto.

Margens **não colapsam**. Colapso de margem vertical é uma das regras mais confusas do CSS e existe
por causa de documentos de texto, não de interfaces. Duas caixas com `margin: 8px` ficam a 16 px de
distância, que é o que a pessoa que escreveu esperava.

Custo: ~80 linhas. Benefício: é a base de tudo. **Obrigatório.**

### Nível 1 — Fluxo de blocos

Cada `display: block` ocupa toda a largura disponível do pai (menos margens) e empilha
verticalmente. A altura de um bloco sem `height` é a soma das alturas dos filhos.

O algoritmo é uma recursão de duas fases:

```
layout(no, larguraDisponivel):
    largura = resolveLargura(no, larguraDisponivel)     # px, %, ou auto = disponível
    interna = largura - padding - border
    y = 0
    para cada filho:
        layout(filho, interna)
        filho.y = y + filho.marginTop
        filho.x = padding.left + filho.marginLeft
        y = filho.y + filho.altura + filho.marginBottom
    no.altura = altura declarada, ou y + padding.bottom
```

Uma passada, sem retrocesso, porque a largura flui de cima para baixo e a altura de baixo para cima.
É o caso feliz do layout e explica por que layout de bloco puro é rápido.

Custo: ~150 linhas. **Obrigatório** — é isso que elimina a aritmética manual.

### Nível 2 — Inline e quebra de linha

Conteúdo inline (texto e `<span>`) se acumula numa "caixa de linha" até estourar a largura, então
quebra. Precisa medir texto, o que só o cliente pode fazer — `textRenderer.getWidth(String)`.

Simplificações que valem a pena:

- **Quebra só em espaço**, sem hifenização, sem regras Unicode de quebra de linha (UAX #14). Um
  texto CJK sem espaços vira uma linha longa que é cortada por `overflow`. É um limite real, e
  aceitável — documentado, não escondido.
- **Altura de linha fixa** em 9 px vezes a escala, mais 2 px de entrelinha. Sem `line-height`
  variável, sem alinhamento de baseline entre fontes de tamanhos diferentes numa mesma linha (o
  maior manda, os outros centralizam nele).
- **`vertical-align` não existe.** Tudo é centralizado verticalmente na linha.

Custo: ~200 linhas. **Recomendado** — sem isso, texto longo simplesmente vaza, e é o que mais
acontece na prática quando um mod é traduzido.

### Nível 3 — Flexbox de um eixo

Este é o ponto de parada recomendado. Flexbox completo tem `flex-wrap`, `flex-basis`,
`flex-shrink`, `order`, `align-self`, `align-content`, eixos cruzados e uma especificação de nove
etapas. O subconjunto que resolve 95% da UI de mod é bem menor:

| Suportado | Ignorado |
|---|---|
| `flex-direction: row \| column` | `row-reverse`, `column-reverse` |
| `justify-content: start \| center \| end \| space-between` | `space-around`, `space-evenly` |
| `align-items: start \| center \| end \| stretch` | `baseline`, `align-self` |
| `gap: Npx` | `row-gap`/`column-gap` separados |
| `flex: N` (só o fator de crescimento) | `flex-shrink`, `flex-basis`, `order`, `flex-wrap` |

O algoritmo simplificado, num eixo:

```
1. Meça cada filho no seu tamanho natural (largura declarada, ou conteúdo).
2. sobra = tamanhoDoContainer - soma(tamanhosNaturais) - gaps
3. Se sobra > 0 e há filhos com flex: distribua sobra proporcional ao fator de cada um.
4. Se sobra < 0: corte proporcionalmente (shrink implícito e uniforme), com piso no mínimo.
5. Posicione no eixo principal conforme justify-content.
6. Posicione no eixo cruzado conforme align-items; stretch preenche.
```

Sem `wrap`, isto é uma passada extra sobre os filhos — não muda a complexidade assintótica.

Custo: ~180 linhas. **Recomendado.** É o que torna "rótulo à esquerda, botão à direita, esticando
com a janela" uma linha de CSS em vez de uma conta.

### Nível 4 — O que NÃO fazer

| Recurso | Por que fica de fora |
|---|---|
| `flex-wrap` | Exige agrupar filhos em linhas antes de distribuir, e depois distribuir o eixo cruzado entre as linhas. Praticamente dobra o código do flex. Uma grade de itens é melhor servida por um elemento `grid` próprio (ver abaixo) do que por wrap genérico. |
| CSS Grid | O algoritmo de trilhas com `fr`, `minmax`, `auto-fit` e colocação automática é maior que todo o resto desta engine somado. Se a demanda for "grade de N×M células iguais", isso é um atributo, não um sistema de layout. |
| `float` | Existe para texto ao redor de imagens em documentos. Nenhum GUI de mod precisa disso. |
| `position: absolute/relative/fixed` | Tentador, mas cria um segundo sistema de coordenadas com blocos contendo, empilhamento e `z-index`. E o protocolo atual **já é** posicionamento absoluto: quem precisa disso usa a camada antiga. |
| `transform`, `transition`, `animation` | Animação exige estado por frame no cliente e interpolação. É uma feature separada, não parte do layout. |
| Herança de tamanho por porcentagem de altura | `height: 50%` de um pai `auto` é uma dependência circular. Porcentagem de altura só é resolvida se o pai tem altura definida; caso contrário vira `auto`. Mesmo comportamento do CSS real, e a única regra circular que precisa ser explicada. |

### Um elemento `grid` próprio, em vez de CSS Grid

A necessidade real por trás de "quero uma grade" em UI de Minecraft é quase sempre um inventário:
N colunas de células do mesmo tamanho. Isso não pede um sistema de layout; pede um atributo.

```html
<div display="grid" columns="9" cell="18">
  <item id="minecraft:stone" count="64"></item>
  ...
</div>
```

Trinta linhas de código, resolve o caso concreto, e não cria a expectativa de que `grid-template-areas`
vai funcionar. Este é o tipo de troca que o documento inteiro defende: resolver o problema real em
vez de importar a solução geral.

---

## Parsing

### Opções avaliadas

| Opção | Tamanho | Licença | Veredito |
|---|---|---|---|
| **jsoup** | ~440 KB | MIT | Excelente parser de HTML, tolerante a erro, com seletores CSS. Mas traz um DOM completo, normalização de HTML5 e um motor de seletores muito maior que o subconjunto. Não parseia CSS: precisaria de uma segunda lib. |
| **CSS Parser (SourceForge)** | ~350 KB | LGPL | Parser SAC completo de CSS. **LGPL é problema** num mod distribuído como jar: obriga a permitir relinkagem, o que é atrito real na distribuição via Modrinth/CurseForge. |
| **ph-css** | ~800 KB | Apache 2.0 | Muito completo, muito grande. |
| **Flying Saucer / iText** | multi-MB | variadas | Fora de questão. |
| **Parser próprio** | ~900 linhas | própria | Recomendado. |
| **Formato intermediário (JSON com estrutura de árvore)** | 0 | — | Não é HTML; perde a razão de ser da feature. Mas é um bom formato *de saída* do parser. |

### Recomendação: parser próprio

O argumento não é "não invente a roda". É que **o subconjunto é pequeno o bastante para que a roda
pronta seja maior que o carro**. Uma estimativa concreta:

| Peça | Linhas | O que faz |
|---|---|---|
| Tokenizador HTML | ~180 | Texto, `<tag>`, `</tag>`, atributos com aspas, comentários, `<br>` auto-fechado |
| Construtor de árvore | ~120 | Pilha de tags abertas, fechamento implícito para tags conhecidas como vazias, tolerância a tag não fechada |
| Tokenizador CSS | ~150 | Regras, seletores separados por vírgula, declarações `prop: valor;`, comentários |
| Resolução de valores | ~200 | Cores, unidades, atalhos (`padding: 4px 8px`), enums |
| Casamento de seletor + cascata | ~130 | Ordena por especificidade, aplica, herda |
| Sanitização e limites | ~120 | Profundidade, contagem de nós, tags proibidas, tamanho |
| **Total** | **~900** | |

Novecentas linhas é comparável ao `ScreenBuilder` mais o `ScreenModel` atuais somados, e o projeto
já demonstrou apetite por esse tamanho de peça. Em troca: zero dependência nova, controle total
sobre o que é aceito, e mensagens de erro escritas para quem escreve mods em Lua — não mensagens de
uma lib genérica falando de DOM.

Ressalva honesta: **um parser de HTML tolerante a erro é mais difícil do que parece.** A
especificação HTML5 de tratamento de erro tem centenas de casos (tags implicitamente fechadas,
reconstrução de elementos de formatação ativos, "adoption agency algorithm"). A saída aqui é não
tentar: o parser aceita **HTML bem formado** com um punhado de tolerâncias explícitas, e rejeita o
resto com erro claro. Isso é aceitável porque o HTML aqui é escrito por um humano num arquivo de
mod, não raspado da web.

Tolerâncias que valem a pena implementar:

- Tag não fechada no fim do documento: fechada implicitamente.
- `<br>`, `<img>`, `<input>`, `<item>` sem barra final: tratadas como vazias.
- Atributo sem aspas se não tiver espaço: aceito.
- Tag desconhecida: vira `<div>` anônimo, conteúdo preservado.
- Tag de fechamento sem abertura: descartada com aviso no log.

Tudo além disso é erro de validação, com linha e coluna.

---

## Onde cada peça roda

Esta é a decisão com o trade-off mais interessante.

| | Parsear no servidor | Parsear no cliente |
|---|---|---|
| Tráfego | Maior: árvore resolvida em JSON é 3–5× o HTML fonte | Menor: HTML/CSS comprime muito bem |
| Código no cliente | Só o renderizador atual, quase inalterado | +900 linhas de parser, +600 de layout |
| Reage a redimensionar janela | **Não.** Layout congelado no tamanho assumido | **Sim.** Recalcula no `init()` |
| Reage a texto de largura variável | **Não.** O servidor não conhece a fonte do cliente | **Sim.** `textRenderer.getWidth()` está lá |
| Reage a idioma do jogador | **Não** | **Sim** |
| Validação e limites | No núcleo, testável sem abrir o jogo | Precisa duplicar checagem de segurança |
| Cliente sem loader | Já não funciona nos dois casos | Idem |
| Compatibilidade de versão | Servidor precisa saber a versão do cliente | Cliente ignora o que não conhece — regra que já existe |

### Recomendação: híbrido, com a divisão no ponto certo

**Parsing e validação no núcleo. Layout no cliente.**

O raciocínio: as duas linhas em negrito na tabela decidem. Layout no servidor é impossível de fazer
bem, porque **medir texto exige a fonte, e a fonte está no cliente.** Um servidor que faz layout
teria que chutar a largura de "Diamante" e chutaria errado em português, alemão e chinês. Não é uma
limitação contornável; é a razão pela qual layout tem que ficar no cliente.

Mas parsing não tem essa restrição. E colocar o parser no núcleo dá três coisas que importam:

1. **Erro de HTML aparece no log do servidor, para quem escreveu o mod**, no momento em que a tela é
   montada — não numa máquina remota que ninguém vai olhar.
2. **A checagem de segurança fica num lugar só.** `<script>` é rejeitado no núcleo, testável sem
   Minecraft, exatamente como `ScreenBuilder` valida hoje.
3. **Os limites (nós, profundidade, tamanho) são aplicados antes do pacote sair**, que é onde
   limites servem para alguma coisa.

O que trafega é a **árvore de estilo resolvida**: a estrutura HTML já parseada, com o CSS já
cascateado em cada nó, mas **sem posições calculadas**. O cliente recebe caixas com propriedades e
roda o layout.

```
Lua (HTML/CSS texto)
  → núcleo: parse HTML + parse CSS + cascata + sanitização + limites
  → JSON: árvore de nós com estilo computado
  → rede
  → cliente: layout (mede texto, resolve flex, produz retângulos)
  → cliente: renderiza
  → cliente: hit-test do mouse contra os retângulos
  → evento de volta pelo canal existente
```

O custo é o tráfego: a árvore resolvida é maior que o HTML fonte. Mitigação: só propriedades
não-padrão são serializadas em cada nó — na prática a maioria dos nós carrega três ou quatro
campos. O teto de 64 KB do protocolo continua valendo e é generoso para uma UI de mod.

O código no cliente que isto exige é o motor de layout, ~600 linhas. É o preço de UI que se adapta,
e não há como pagar menos.

---

## Compatibilidade com o que existe

Três caminhos possíveis para a relação com `ScreenProtocol` atual.

### Opção A — Substituir

HTML vira o único jeito. `panel`/`label`/`button` desaparecem.

Contra: quebra todo mod existente, e a camada atual é genuinamente melhor para alguns casos (HUD,
onde posição absoluta é exatamente o que se quer; e telas simples de três elementos, onde HTML é
cerimônia). Descartado.

### Opção B — Coexistir como sistemas paralelos

Dois protocolos, duas telas de cliente, dois renderizadores.

Contra: duplica o renderizador, a validação e os limites. Uma correção de bug em desenho de texto
precisa ser feita duas vezes. Duas superfícies de segurança para auditar. Descartado — é o caminho
que parece barato no começo e cobra juros para sempre.

### Opção C — HTML compila para o protocolo estendido — **recomendado**

O layout no cliente produz, como saída, exatamente a lista plana de elementos posicionados que
`LuaScreen` já sabe desenhar. HTML é uma **camada de autoria**; o protocolo continua sendo a
representação.

O que isso dá de graça:

- O renderizador é um só. `panel` continua sendo `context.fill`, `label` continua sendo
  `drawTextWithShadow`.
- Mods existentes não mudam nada.
- Um mod pode misturar: HTML para o corpo da tela, elementos absolutos para um detalhe.
- Depurar é fácil: dá para logar a lista de retângulos resultante e comparar com o que se esperava.

O protocolo precisa de três extensões, todas retrocompatíveis (campo opcional novo não muda a
versão, pela regra que `ScreenProtocol` já documenta):

| Extensão | Motivo |
|---|---|
| `border` em `panel` (cor e espessura, ou nine-slice) | Caixas com borda são o pão de cada dia de CSS |
| `clip` (retângulo de recorte) em qualquer elemento | `overflow: hidden` precisa disso |
| Um campo `html` na descrição da tela, alternativo a `elements` | O ponto de entrada |

E um tipo de elemento novo, `nineslice`, para bordas no estilo do jogo.

Assim a árvore HTML entra pelo topo, o layout roda, e o que sai pelo fundo é o vocabulário atual.
Nada do que já funciona é tocado.

### API Lua

```lua
-- Forma direta: HTML como texto
ctx.player.open_screen_html("forja", [[
  <style>
    .painel { background-color: #20202080; padding: 8px; }
    .titulo { font-size: 1.5x; color: #FFD700; }
    .linha  { display: flex; align-items: center; gap: 8px; padding-top: 4px; }
    .nome   { flex: 1; }
  </style>
  <div class="painel">
    <h2 class="titulo">Forja de Cristal</h2>
    <progress value="0.4"></progress>
    <div class="linha">
      <item id="minecraft:iron_ingot" count="12"></item>
      <span class="nome">Ferro</span>
      <button id="forjar">Forjar</button>
    </div>
    <input id="nome" placeholder="Nome do item"></input>
  </div>
]])
```

E, para o caso mais comum de todos — HTML fixo com dados variáveis — uma interpolação mínima:

```lua
ctx.player.open_screen_html("forja", html, { nome = "Ferro", qtd = 12 })
-- no HTML: <span>{nome}</span> x <span>{qtd}</span>
```

**A interpolação substitui apenas texto, com escape automático de `<`, `>` e `&`.** Isso não é
detalhe: sem escape, um nome de jogador contendo `<button id="admin">` viraria um botão de verdade.
O escape é a diferença entre um template e uma injeção. `{nome}` não pode produzir estrutura, nunca
— nem que o valor venha de dentro do próprio mod.

Nada de condicionais, laços ou expressões no template. Quem precisa de lógica monta a string em Lua,
que é uma linguagem de programação completa e está ali do lado. Uma linguagem de template dentro do
HTML seria uma terceira linguagem para aprender e uma nova superfície para bugs.

---

## Interatividade

O ponto que o título do documento levanta: `<button onclick="...">` sem JavaScript.

**Não existe `onclick`.** O atributo é rejeitado — assim como `onmouseover` e todo o resto da
família `on*`. O que existe é `id`, e o `id` é o que volta ao Lua:

```html
<button id="forjar">Forjar</button>
```

```lua
mod.screen("forja", function(ctx)
    if ctx.ui.element == "forjar" then ... end
end)
```

Isto é exatamente o que o protocolo já faz hoje. HTML não muda o modelo de eventos em nada; só muda
como o elemento é declarado. O vocabulário de ações fechado (`click`, `change`, `submit`, `close`)
continua valendo, e uma ação desconhecida continua sendo descartada pelo servidor.

Rejeitar `on*` em vez de ignorar é deliberado: se um atributo `onclick` fosse silenciosamente
ignorado, alguém escreveria `onclick="fazerCoisa()"`, veria o botão não fazer nada, e passaria uma
hora depurando. Um erro dizendo "onclick não existe; use id e mod.screen" resolve em cinco segundos.

### Hit-testing

O layout produz retângulos absolutos. Um clique é um teste linear contra a lista de elementos
interativos, de trás para frente (o último desenhado ganha). Com no máximo algumas centenas de
elementos, isso é irrelevante em custo.

`<button>` e `<input>` continuam virando widgets nativos do Minecraft (`ButtonWidget`,
`TextFieldWidget`), posicionados no retângulo que o layout calculou. Isso preserva de graça: foco,
navegação por Tab, seleção de texto, cursor piscando, som de clique — tudo que reimplementar seria
trabalho perdido e pior.

O `updateModel` atual, que preserva texto digitado entre atualizações, continua funcionando pelo
mesmo mecanismo: o `id` é a chave.

### Estados visuais

| Estado | Como funciona |
|---|---|
| `:hover` | Teste de retângulo por frame; aplica a segunda tabela de estilos |
| `disabled` | Atributo em `<button>`; widget desabilitado, sem evento |
| foco | Gerido pelo widget nativo |
| `:active` (pressionado) | Fora — o widget nativo já dá o feedback visual do jogo |

---

## Estilo visual: reconciliar CSS com a estética do Minecraft

Um `<div>` com `background-color: #808080` desenhado com `context.fill` fica **errado** dentro do
Minecraft. Não tecnicamente errado: esteticamente estranho. A interface do jogo é feita de texturas
com borda em relevo de 3 px, e um retângulo chapado ao lado disso parece um bug.

A solução é dar duas rotas, e fazer a rota boa ser a fácil.

### Nine-slice como cidadão de primeira classe

```css
.janela {
  background-image: minecraft:textures/gui/container/generic_54.png;
  border-slice: 4px;   /* quantos px de cada canto são preservados */
  border-width: 4px;   /* onde a borda começa e o conteúdo termina */
}
```

Nine-slice divide a textura em nove regiões: quatro cantos que nunca esticam, quatro bordas que
esticam num eixo, e um centro que estica nos dois (ou repete). É como toda janela do jogo é
desenhada e é o que faz uma caixa de qualquer tamanho parecer nativa.

O renderizador precisa de `drawTexture` chamado nove vezes com regiões calculadas. Isso é um tipo de
elemento novo (`nineslice`) e ~60 linhas.

### Classes prontas

O mais importante para adoção: um CSS embutido no cliente com as aparências do jogo já definidas.

| Classe | Aparência |
|---|---|
| `.mc-window` | Fundo de janela de container, com nine-slice |
| `.mc-panel` | Painel escuro embutido, tipo slot |
| `.mc-slot` | Célula 18×18 de inventário |
| `.mc-button` | Botão vanilla (mas `<button>` já usa por padrão) |
| `.mc-tooltip` | Fundo roxo-escuro com borda gradiente |

Assim `<div class="mc-window">` produz algo que parece Minecraft sem uma linha de CSS, e
`background-color` continua disponível para quem quer o retângulo chapado de propósito.

### Fonte

A fonte é bitmap de 9 px com sombra de 1 px. Consequências que precisam estar documentadas:

- `font-size` só aceita escalas de um conjunto pequeno (`1x`, `1.5x`, `2x`, `3x`). Ver a seção de
  escopo.
- `font-family` não existe. Existe uma fonte, e a alternativa `minecraft:uniform` para CJK.
- `font-weight: bold` usa o código de formatação `§l` do próprio jogo, não uma fonte separada.
- `text-shadow: none` é suportado porque a sombra é o padrão e às vezes atrapalha; qualquer outro
  valor de `text-shadow` é ignorado.
- Códigos de cor `§` no texto continuam funcionando e têm precedência sobre `color`.

### Cores

`#RRGGBB` e `#RRGGBBAA` — o mesmo formato que `ScreenBuilder` já valida. Mais um conjunto pequeno de
nomes (`white`, `black`, `red`, ..., mais os 16 nomes de cor do próprio Minecraft). Sem `rgb()`,
`hsl()`, gradientes ou `currentColor`.

---

## Riscos e limites

### Custo por frame

O layout roda **quando a descrição muda ou a janela é redimensionada**, não a cada frame. Isso é a
regra mais importante de desempenho de toda a engine, e precisa ser invariante do código, não
disciplina.

O que roda por frame é apenas: percorrer a lista plana de retângulos e chamar `fill`/`drawText`, e o
teste de `:hover`. Uma tela de 200 elementos são 200 chamadas de desenho — comparável a uma tela de
container vanilla e irrelevante a 60 fps.

O risco real é um mod chamar `update_screen` a cada tick com HTML novo, forçando parse mais layout 20
vezes por segundo. Mitigações:

- Limite de frequência de atualização por tela, no núcleo (já previsto em `UI_SPEC.md`).
- Cache do resultado do parse no cliente, com chave no hash da árvore recebida: HTML idêntico não
  recalcula.

### Limites propostos

| Limite | Valor | Motivo |
|---|---|---|
| Nós na árvore | 512 | Acima disso o layout começa a aparecer no profiler |
| Profundidade de aninhamento | 32 | Recursão de layout estoura a pilha antes de qualquer coisa útil |
| Regras CSS | 128 | Casamento é linear por nó; 512×128 já é meio milhão de testes |
| Seletores por regra | 8 | — |
| Tamanho do HTML fonte | 32 KB | — |
| Tamanho do JSON resolvido | 64 KB | Teto já existente do protocolo |
| Caixas de linha por bloco de texto | 256 | Um texto patológico não pode gerar layout infinito |

Todos aplicados **no núcleo**, antes de o pacote sair, e testáveis sem abrir o jogo.

### HTML inválido

Uma regra por camada, e cada uma escolhida pelo que ajuda quem está do lado dela:

| Camada | Diante de entrada inválida | Por quê |
|---|---|---|
| Núcleo (parse) | **Falha com mensagem, linha e coluna** | Quem escreveu o mod está do outro lado e precisa saber |
| Núcleo (segurança) | **Falha sempre** — `<script>`, `on*`, URL externa | Nunca degradar silenciosamente numa questão de segurança |
| Cliente (layout) | **Ignora o que não entende** | O jogador não pode fazer nada a respeito; melhor uma tela parcial que nenhuma |

É a mesma divisão que `ScreenBuilder` e `ScreenModel` já praticam hoje, e é por isso que ela deve
ser mantida: consistência com o que o projeto já decidiu.

### Riscos que não têm mitigação completa

1. **Escopo escorregando.** "Só falta `flex-wrap`", "só falta seletor descendente", "só falta
   `calc()`". Cada um é pequeno; juntos são um navegador. A defesa é este documento: a lista do que
   fica de fora é tão normativa quanto a lista do que entra. Adicionar algo dali exige mudar o
   documento primeiro.

2. **Expectativa de conformidade.** Quem sabe CSS vai escrever CSS que não funciona, e vai reportar
   como bug. A defesa é o nome: chamar isto de "HTML/CSS" já é meio caminho para essa confusão.
   Talvez valha um nome próprio — `mcml`, ou similar — e uma página de documentação que comece com a
   tabela do que **não** existe.

3. **Depuração.** Quando o layout sai errado, não há inspetor de elementos. A defesa é um comando
   (`/lua screen dump`) que imprime a árvore com os retângulos calculados, e uma opção de desenhar
   as bordas de todas as caixas — o equivalente pobre do `* { outline: 1px solid red }`.

4. **Custo total.** ~900 linhas de parser mais ~600 de layout mais ~300 de renderização de estilo é
   um componente de porte médio, comparável ao runtime Lua. Vale a pena se a UI for uma parte central
   do que os mods fazem. Se a maioria dos mods usar duas telas simples, o posicionamento absoluto
   atual já bastava, e este esforço estaria mal alocado. **Essa pergunta deve ser respondida antes
   da etapa 3 do plano abaixo**, olhando os mods que existirem até lá.

---

## Plano incremental

Cada etapa é utilizável sozinha e prova algo antes de a próxima começar.

### Etapa 1 — Parser HTML e árvore, sem CSS

Tags: `<div>`, `<span>`, `<p>`, `<button>`, `<input>`, `<br>`. Sem estilo. Layout de bloco puro,
largura total, altura por conteúdo. Atributos `id` e `style` (só `color` e `background-color`).

Prova: HTML chega, vira árvore, vira retângulos, aparece na tela, botão dispara evento. Se isso
funciona, o resto é acrescentar propriedades.

### Etapa 2 — CSS com `<style>`, classes e a cascata

Tokenizador CSS, seletores de tag/classe/id, especificidade, herança das quatro propriedades
herdáveis. Propriedades da caixa: `padding`, `margin`, `width`, `height`, `background-color`,
`color`.

Prova: separação de estrutura e estilo funciona — o exemplo da abertura deste documento roda.

### Etapa 3 — Texto inline e quebra de linha

Medição via `textRenderer`, caixas de linha, `text-align`, `font-size` como escala, `<h1>`–`<h3>`.

Prova: texto longo se comporta. É aqui que a decisão de fazer layout no cliente se paga
visivelmente, e é o marco natural para revisar se o investimento continua justificado.

### Etapa 4 — Flexbox de um eixo

`display: flex`, `flex-direction`, `justify-content`, `align-items`, `gap`, `flex: N`.

Prova: barras de ferramentas, linhas com botão à direita, colunas que dividem espaço.

### Etapa 5 — Estética do jogo

`nineslice`, `border`, as classes `.mc-*`, `:hover`. Nada de novo em layout; só aparência.

Prova: uma tela escrita em HTML fica indistinguível de uma tela do jogo.

### Etapa 6 — `<img>`, `<item>`, `<progress>`

Os três elementos que já existem no protocolo, agora acessíveis por tag. Barato, porque o
renderizador já os desenha; só falta o caminho de autoria.

### Etapa 7 — `overflow: hidden` e recorte

Retângulo de recorte no protocolo, `enableScissor` no cliente. Pré-requisito da próxima.

### Etapa 8 — `overflow: scroll`

Estado de rolagem por elemento no cliente, roda do mouse, barra de rolagem. É a etapa que introduz
estado próprio no cliente além do texto digitado — a fronteira que `UI_SPEC.md` hoje declara fora de
escopo — e por isso vem por último e deve ser decidida com o que se souber até lá.

### Etapa 9 — HUD por HTML

O mesmo pipeline, sem interação, desenhado no gancho de HUD. Fácil depois de tudo acima; e o HUD é
justamente o caso onde posicionamento absoluto continua sendo defensável, então esta etapa pode
simplesmente não acontecer sem prejuízo.

---

## O que este documento recomenda não fazer

Reunido num lugar só, porque é a parte mais fácil de esquecer:

- Não implementar `<script>`, `on*`, ou qualquer execução no cliente. Nunca, em nenhuma etapa.
- Não implementar CSS Grid, `float`, `position: absolute`, `flex-wrap`, `calc()`, `em`/`rem`.
- Não implementar seletores descendentes nem pseudo-classes além de `:hover`.
- Não implementar tabelas.
- Não implementar uma linguagem de template com lógica dentro do HTML.
- Não fazer layout no servidor.
- Não manter dois renderizadores.
- Não prometer conformidade com a web em lugar nenhum da documentação.

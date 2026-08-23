# Mine Loader — interface por HTML, CSS e DOM em Lua

Design de uma camada de marcação, estilo e manipulação de árvore sobre a interface já existente
(`UI_SPEC.md`). Este documento não é implementação: é a decisão de o que construir, em que ordem, e
o que deliberadamente não construir.

## A motivação: fluxo é mais simples que pixel

O ponto de partida deste projeto não é sofisticação — é o contrário. **Escrever HTML e CSS é mais
básico do que calcular posicionamento em pixel.** A camada atual obriga quem escreve o mod a fazer
aritmética de coordenadas para cada elemento, e a refazer essa aritmética inteira sempre que
qualquer coisa muda de tamanho.

Vale ver isso concretamente. A mesma tela — um título, uma barra de progresso e três linhas com
ícone, nome e botão — nas duas formas.

### Hoje: coordenadas absolutas

```lua
local function desenhar(ctx)
    return {
        title = "Forja de Cristal",
        width = 260, height = 150,
        elements = {
            { type = "panel", x = 0, y = 0, w = 260, h = 150, color = "#20202080" },
            { type = "label", x = 8, y = 8, text = "Forja de Cristal",
              color = "#FFD700", scale = 1.5 },
            { type = "progress", x = 8, y = 30, w = 244, h = 6, progress = ctx.state.p },

            { type = "item",   x = 8,   y = 46, item = "minecraft:iron_ingot", count = 12 },
            { type = "label",  x = 30,  y = 50, text = "Ferro" },
            { type = "button", x = 192, y = 44, w = 60, h = 20, id = "f1", text = "Forjar" },

            { type = "item",   x = 8,   y = 70, item = "minecraft:gold_ingot", count = 3 },
            { type = "label",  x = 30,  y = 74, text = "Ouro" },
            { type = "button", x = 192, y = 68, w = 60, h = 20, id = "f2", text = "Forjar" },

            { type = "item",   x = 8,   y = 94, item = "minecraft:diamond", count = 1 },
            { type = "label",  x = 30,  y = 98, text = "Diamante" },
            { type = "button", x = 192, y = 92, w = 60, h = 20, id = "f3", text = "Forjar" },
        }
    }
end
```

Doze elementos, vinte e quatro coordenadas escritas à mão. Repare no que isso custa:

- O `y` de cada linha (46, 70, 94) é uma progressão manual. Mudar o espaçamento de 24 para 28 exige
  reescrever nove números.
- O `y` do rótulo é 50 e o do item é 46, porque o texto tem 9 px e o ícone tem 16 e alguém centrou
  os dois na mão. Trocar a escala do texto quebra esse alinhamento.
- O `x = 192` do botão é `260 - 8 - 60`. Mudar a largura da janela exige recalcular.
- Se "Diamante" virar "Diamant de cristal pur" numa tradução, ele passa por baixo do botão. Não há
  como o autor prever isso, porque ele não conhece a fonte do cliente.

### Com fluxo

```lua
local doc = ctx.player.open_screen("forja", [[
  <style>
    .janela { width: 260px; padding: 8px; background-color: #20202080; }
    .titulo { font-size: 1.5x; color: #FFD700; }
    .linha  { display: flex; align-items: center; gap: 6px; margin-top: 4px; }
    .nome   { flex: 1; }
  </style>
  <div class="janela">
    <h2 class="titulo">Forja de Cristal</h2>
    <progress id="barra" value="0"></progress>
    <div class="linha">
      <item id="minecraft:iron_ingot" count="12"></item>
      <span class="nome">Ferro</span><button onclick="forjar" data-tipo="ferro">Forjar</button>
    </div>
    <div class="linha">
      <item id="minecraft:gold_ingot" count="3"></item>
      <span class="nome">Ouro</span><button onclick="forjar" data-tipo="ouro">Forjar</button>
    </div>
    <div class="linha">
      <item id="minecraft:diamond" count="1"></item>
      <span class="nome">Diamante</span><button onclick="forjar" data-tipo="diamante">Forjar</button>
    </div>
  </div>
]])
```

Zero coordenadas. O espaçamento das linhas é um `margin-top` em um lugar. O alinhamento vertical do
ícone com o texto é `align-items: center`, e continua correto se a escala do texto mudar. O botão
fica à direita porque o nome tem `flex: 1`, e continua à direita em qualquer largura de janela. Um
nome traduzido mais longo empurra o botão em vez de passar por baixo dele.

**Esse é o argumento inteiro.** Não é que HTML seja mais poderoso; é que ele é menos trabalho para o
caso comum. Se o design acabar exigindo que o autor pense em coordenadas na maior parte dos casos,
ele falhou no objetivo — e essa é a régua contra a qual cada decisão abaixo deve ser medida.

### A consequência para as prioridades

O layout em fluxo é **o recurso principal**, não um extra. A ordem de valor, do maior para o menor:

1. **Empilhamento vertical automático** — nunca mais escrever um `y`.
2. **Largura em porcentagem e `auto`** — nunca mais escrever um `w` derivado da janela.
3. **`margin` e `padding`** — espaçamento declarado, não somado.
4. **Centralização declarativa** — `margin: 0 auto`, `text-align: center`, `align: center`.
5. Flexbox de um eixo — distribuição de espaço em uma linha.
6. Todo o resto.

Os quatro primeiros itens já resolvem a maioria das telas e são também os mais baratos de
implementar. O plano incremental no fim do documento segue essa ordem, e não a ordem em que os
recursos aparecem numa referência de CSS.

### E a segunda consequência: divergir da web quando ela complica

O público-alvo não é quem conhece CSS a fundo. É quem quer uma tela sem calcular pixels. Onde o
comportamento real do CSS for confuso, **a regra mais previsível ganha**, e a divergência é
documentada em vez de escondida.

| Comportamento | CSS real | Aqui | Por quê |
|---|---|---|---|
| `box-sizing` padrão | `content-box`: `width: 100px` com `padding: 8px` vira 116 px | **`border-box`**: 100 px é 100 px | Todo projeto web moderno começa com `* { box-sizing: border-box }`. Não há motivo para herdar um erro de 1996. |
| Colapso de margem | Duas caixas com `margin: 8px` ficam a 8 px | **Não colapsa**: ficam a 16 px | Colapso existe para documentos de texto. Aqui só produz "por que tem menos espaço do que pedi". |
| Altura de bloco vazio | Regras sutis com `padding` e float | **Zero, mais padding** | Previsível. |
| `%` de altura sem pai definido | Comportamento condicional complexo | **Vira `auto`**, com aviso no log | Única regra circular, e ela é explicada em vez de silenciosa. |
| Espaço em branco entre tags | `<span>a</span> <span>b</span>` produz um espaço | **Idem** — mas quebras de linha e indentação viram um espaço só | O comportamento útil sem as sutilezas de `white-space`. |
| Elemento inline com `width` | Ignorado silenciosamente | **Aviso no log**, sugerindo `inline-block` | Um aviso vale uma hora de depuração. |
| Herança | Muitas propriedades herdam | **Só `color`, `text-align`, `font-size`, `font-weight`** | Evita `padding` do pai vazar. Lista curta é memorizável. |

Cada divergência acima é uma escolha a favor de quem escreve o mod e contra a fidelidade à web. Elas
precisam estar juntas numa página da documentação, porque a única coisa pior que divergir é divergir
sem avisar.

---

## Recomendação principal, antes dos detalhes

Uma engine **pequena e deliberadamente incompleta**, com cinco decisões estruturantes:

| Decisão | Escolha | Alternativa rejeitada |
|---|---|---|
| Linguagem de lógica | **Lua**, com um DOM de verdade exposto a ele | JavaScript (proibido pela arquitetura) |
| Onde o DOM vive | **Servidor é a autoridade**; cliente é espelho atualizado por *patches* | Reenviar a árvore inteira a cada mudança |
| Onde parsear | **Núcleo** parseia e valida; **cliente** faz o layout | Layout no servidor (impossível: a fonte está no cliente) |
| Como parsear | **Parser próprio**, ~900 linhas, sem dependência nova | jsoup (440 KB), CSS Parser (LGPL) |
| Relação com a camada atual | **Coexiste**, compilando para o mesmo protocolo estendido | Substituir `mod.screen`; ou dois renderizadores paralelos |

O nome informal para o motor: *layout de caixa em uma passada*. Cada nó é medido uma vez,
posicionado uma vez, e o resultado é uma lista plana de retângulos — exatamente o vocabulário que
`LuaScreen` já desenha hoje.

---

## O DOM em Lua

Esta é a mudança de direção que mais afeta o design. Não se trata de mandar HTML estático e receber
cliques: trata-se de o script **manipular a árvore** com o mesmo vocabulário que um script de página
usa no navegador — só que em Lua, no servidor, e sem nada executando na máquina do jogador.

### Por que um DOM, e não só reenviar HTML

Sem DOM, atualizar um número na tela significa remontar a string de HTML inteira e reenviá-la. Isso
funciona e é o que o protocolo faz hoje, mas cobra três preços:

- **Tráfego.** Reenviar 8 KB de HTML para mudar "12" para "11" é desperdício por um fator de mil.
- **Estado perdido.** Reconstruir a árvore joga fora rolagem, foco e seleção de texto. O
  `updateModel` atual já contorna isso na marra, preservando texto digitado por `id`; um DOM
  resolve por construção.
- **Escrita mais difícil.** Uma função que remonta a tela inteira precisa saber desenhar todos os
  estados. Uma função que faz `nome.text = novo` precisa saber uma coisa só.

Com DOM, a diferença é esta:

```lua
-- Sem DOM: remonta e reenvia tudo
ctx.player.update_screen(desenhar_tela_inteira(ctx))

-- Com DOM: muda o que mudou
doc:get("barra").value = 0.62
doc:get("contador").text = "11"
```

O segundo bloco gera dois *patches* de poucas dezenas de bytes.

### Nomenclatura: nomes da web ou estilo do projeto?

A tensão é real. `document.getElementById` é familiar para milhões de pessoas; `doc:get_by_id`
combina com o resto da API do loader (`mod.on`, `ctx.player.send_message`, `ctx.server.set_block`),
que é toda em `snake_case`.

**Recomendação: manter os conceitos e os nomes de eventos da web, adaptar a grafia ao Lua do
projeto.** Ou seja, `snake_case` para métodos, `:` para chamada de método, mas
`get_element_by_id`/`query_selector` reconhecíveis, e nomes de evento (`click`, `change`, `submit`)
idênticos aos da web.

O raciocínio: a familiaridade que importa é **conceitual**, não ortográfica. Quem sabe o que
`getElementById` faz reconhece `get_element_by_id` instantaneamente. Mas uma API em `camelCase`
dentro de um projeto inteiro em `snake_case` seria uma ilha, e cada mod acabaria misturando os dois
estilos na mesma linha. Consistência interna vale mais que fidelidade ortográfica.

E, onde o nome da web for longo sem ganho, há um apelido curto: `doc:get("id")` ao lado de
`doc:get_element_by_id("id")`. Na prática todo mundo usa o curto.

### A API proposta

#### Consulta

| Lua | Equivalente web | Devolve |
|---|---|---|
| `doc:get(id)` | `getElementById` | Elemento ou `nil` |
| `doc:get_element_by_id(id)` | idem | Alias longo |
| `doc:query(sel)` | `querySelector` | Primeiro que casa, ou `nil` |
| `doc:query_all(sel)` | `querySelectorAll` | Lista (tabela-array), nunca `nil` |
| `doc.body` | `document.body` | Elemento raiz |
| `el:query(sel)`, `el:query_all(sel)` | idem, com escopo | Busca só nos descendentes |
| `el.parent`, `el.children`, `el.next`, `el.previous` | `parentNode`, `childNodes`, … | Navegação |

Os seletores aceitos em `query` são os mesmos do CSS (tag, `.classe`, `#id`) — não faz sentido ter
dois dialetos de seletor no mesmo sistema.

#### Leitura e escrita de conteúdo

| Lua | Equivalente web | Notas |
|---|---|---|
| `el.text` | `textContent` | Leitura e escrita. Escrever substitui todos os filhos por texto. |
| `el.value` | `.value` | Só em `<input>`. Reflete o que o jogador digitou. |
| `el:get_attr(nome)` / `el:set_attr(nome, v)` | `getAttribute`/`setAttribute` | Atributos arbitrários, inclusive `data-*` |
| `el.tag` | `tagName` | Só leitura |

**`innerHTML` não existe.** Escrever HTML dentro de um elemento a partir de uma string exige rodar o
parser sobre conteúdo possivelmente interpolado, que é exatamente o buraco por onde toda injeção de
HTML já passou. Quem quer inserir estrutura usa `doc:create` e `el:append`, que não têm essa
ambiguidade. Existe `el:set_html(fonte)` como escape hatch **explícito**, que parseia a string no
núcleo com as mesmas regras e limites do documento inicial — o nome diferente é o aviso.

#### Estilo e classes

| Lua | Equivalente web |
|---|---|
| `el.style.color = "#FF0000"` | `style.color` |
| `el.style["background-color"] = "#000"` | idem, para nomes com hífen |
| `el:add_class(nome)`, `el:remove_class(nome)` | `classList.add/remove` |
| `el:toggle_class(nome, ativo)` | `classList.toggle` |
| `el:has_class(nome)` | `classList.contains` |
| `el.classes` | `className` (leitura e escrita, string) |

`el.style` é uma tabela com metatabela: `__newindex` valida a propriedade e registra o *patch*.
Escrever uma propriedade desconhecida gera aviso no log e não silêncio — que é a diferença entre
"não funciona" e "não funciona e eu não sei por quê".

**Prefira classes a estilo inline.** A documentação deve dizer isso em voz alta: `el:toggle_class`
gera um patch de 20 bytes, enquanto mexer em seis propriedades de `style` gera seis. Além disso,
classes mantêm a aparência no CSS, onde ela pode ser mudada sem tocar a lógica.

#### Criar, mover e remover nós

| Lua | Equivalente web |
|---|---|
| `doc:create("div")` | `createElement` |
| `doc:create("div", { class = "linha" })` | criar com atributos, num passo |
| `el:append(filho)` | `appendChild` |
| `el:prepend(filho)` | `prepend` |
| `el:insert_before(novo, ref)` | `insertBefore` |
| `el:remove()` | `remove()` |
| `el:clear()` | esvazia os filhos |
| `el:replace_with(novo)` | `replaceWith` |
| `el:clone(profundo)` | `cloneNode` |

Um elemento criado e não anexado existe só no servidor e não gera tráfego. Isso importa: montar uma
subárvore de trinta nós e anexá-la de uma vez gera **um** patch de inserção, não trinta.

```lua
local lista = doc:get("inventario")
lista:clear()
for _, entrada in ipairs(ctx.state.itens) do
    local linha = doc:create("div", { class = "linha" })
    linha:append(doc:create("item", { id = entrada.id, count = entrada.qtd }))
    linha:append(doc:create("span", { class = "nome" })):set_text(entrada.nome)
    lista:append(linha)     -- um patch por linha
end
```

#### O que NÃO portar da web

| Não existe | Por quê |
|---|---|
| `innerHTML` como propriedade | Ver acima. Existe `set_html` explícito. |
| `outerHTML`, `insertAdjacentHTML` | Mesma razão, sem o caso de uso que justifique. |
| `getBoundingClientRect`, `offsetWidth`, `scrollTop` | **A geometria não existe no servidor.** O layout roda no cliente; o servidor não sabe onde nada está. Expor isso exigiria uma ida e volta pela rede com latência, e um script que lesse `offsetWidth` num laço travaria. Esta é a diferença conceitual mais importante entre este DOM e o do navegador, e precisa estar no primeiro parágrafo da documentação. |
| `document.write` | Não faz sentido nem na web. |
| `MutationObserver` | Observar as próprias mutações num sistema onde o script é a única fonte de mutação é um laço sem propósito. |
| Shadow DOM, custom elements, templates | Componentização se faz com módulos Lua (ver adiante), que já existem e são mais simples. |
| `window`, `location`, `navigator`, `fetch`, `localStorage` | Nada disso tem análogo. Persistência é `mod.state`, que já existe. |
| `setTimeout` no DOM | Já existe `ctx.server.schedule`. Não duplicar. |
| Coleções vivas (`HTMLCollection`) | `query_all` devolve uma tabela-array congelada. Coleções vivas são fonte clássica de bug de iteração. |

A ausência de geometria merece ser dita de novo, porque é o que mais vai surpreender: **este DOM
descreve estrutura e estilo, nunca posição.** Quem precisa saber onde um elemento caiu está tentando
resolver com script um problema que o CSS deveria resolver.

### Permissão

O `mod.screen` atual exige `player.menu`, herdado da janela de itens. Um DOM é mais do que isso, e a
permissão deveria dizer o que é.

**Recomendação: uma permissão nova `ui.screen`**, com `player.menu` continuando a valer para telas
como retrocompatibilidade durante uma versão, e um aviso de depreciação no log. Manifesto que declara
só `player.menu` continua funcionando; manifesto novo declara `ui.screen`. É o mesmo tipo de
transição que o projeto já pratica com campos de manifesto.

---

## Onde o DOM vive: a decisão central

O Lua roda no servidor. O desenho acontece no cliente. Portanto a árvore existe nos dois lugares, e
a pergunta é como mantê-las iguais.

### Autoridade no servidor, espelho no cliente

O DOM do servidor é **a** árvore. O cliente tem uma cópia estrutural cuja única função é ser
desenhada. O cliente nunca altera a estrutura por conta própria — com uma exceção nomeada, o texto
digitado num `<input>`, que sobe pelo canal de evento e é aplicado no servidor como qualquer outra
mudança.

Essa exceção precisa de uma regra explícita, porque é a única fonte de divergência possível:

> O `value` de um `<input>` é do cliente enquanto o campo tem foco. O servidor pode lê-lo (recebe
> `change` a cada digitação) mas escrevê-lo enquanto o jogador digita sobrescreve o que ele está
> escrevendo. O renderizador ignora um patch de `value` sobre um campo focado, e registra no log.

Sem essa regra, um mod que valida o campo a cada tecla apagaria o texto de quem digita rápido.

### Patches, não reenvio

A alternativa — reenviar a árvore a cada mudança — é mais simples de implementar e está errada por
uma ordem de grandeza. Comparando os dois numa tela de 8 KB atualizando um contador a cada segundo:

| | Reenvio completo | Patches |
|---|---|---|
| Tráfego por mudança | ~8 KB | ~40 bytes |
| Tráfego por hora, um jogador | ~28 MB | ~140 KB |
| Estado do cliente (rolagem, foco) | perdido, ou preservado por heurística | preservado por construção |
| Custo de layout no cliente | árvore inteira, sempre | só o ramo afetado |
| Complexidade | baixa | média |

O reenvio completo é insustentável com dez jogadores e uma tela viva. Patches são a decisão certa, e
a complexidade adicional é contida se o vocabulário de patch for pequeno.

### O vocabulário de patch

Cada nó recebe um **id numérico estável**, atribuído pelo servidor na criação e nunca reutilizado
dentro da vida de um documento. O patch referencia o nó por esse id — não por seletor, que exigiria
o cliente rodar casamento de seletor, e não por caminho, que quebra a cada inserção.

| Operação | Carga | Uso |
|---|---|---|
| `text` | id, string | `el.text = x` |
| `attr` | id, nome, valor (ou nulo para remover) | `set_attr`, `value`, `count` |
| `style` | id, propriedade, valor | `el.style.x = y` |
| `class` | id, string completa de classes | `add_class`/`remove_class`/`toggle_class` |
| `insert` | id do pai, índice, subárvore serializada | `append`, `prepend`, `insert_before` |
| `remove` | id | `el:remove()` |
| `move` | id, id do novo pai, índice | mover nó existente sem reserializar |
| `replace` | id, subárvore | `replace_with` |
| `reset` | árvore inteira | fallback, ver abaixo |

Nove operações. `move` existe separado de `remove`+`insert` porque reordenar uma lista de trinta
itens sem ele reserializa a lista inteira.

`class` manda a string completa em vez de "adicionou X, removeu Y" de propósito: é idempotente,
imune a ordem de aplicação, e uma string de classes é curta. Uma otimização aqui economizaria bytes
e compraria uma classe inteira de bugs de dessincronização.

### Coalescência e o momento do envio

Os patches **não são enviados um a um.** Cada documento tem um diário de mutações, preenchido
durante o callback e esvaziado no fim dele:

```
callback Lua roda
  → cada mutação anexa um patch ao diário
callback termina
  → coalescer o diário
  → se o resultado for maior que o limiar, virar um único `reset`
  → serializar e enviar um pacote
```

A coalescência é simples e resolve o caso patológico mais comum:

| Regra | Efeito |
|---|---|
| Dois `text` no mesmo nó | Fica o último |
| Dois `style` na mesma propriedade do mesmo nó | Fica o último |
| `attr` repetido no mesmo nó e nome | Fica o último |
| Patch em nó que foi removido depois | Descartado |
| Patch em nó dentro de uma subárvore inserida no mesmo lote | Descartado (já está na subárvore) |

Um laço que escreve `contador.text` mil vezes gera um patch. Isso não é otimização prematura: é o
que impede que um `for` mal escrito no Lua vire mil pacotes de rede.

**Um envio por callback, no máximo um por tick por tela.** Se dois callbacks no mesmo tick tocarem o
mesmo documento, os diários se juntam. A frequência máxima efetiva passa a ser 20 Hz, que é a do
servidor, e o limite de frequência que `UI_SPEC.md` já prevê continua valendo por cima disso.

### O fallback para `reset`

Se o lote coalescido passar de um limiar — proposta: 128 patches ou 8 KB serializados — vale mais
mandar a árvore inteira. Isso acontece quando um script reconstrói metade da tela, e nesse caso a
árvore é mais barata e mais segura que a sequência de operações.

O `reset` também é a rede de segurança contra dessincronização: qualquer suspeita de divergência
(cliente recebe patch para id desconhecido, ordem de pacote quebrada) faz o cliente pedir um
`reset`, e o servidor manda. A árvore é sempre reconstruível a partir do servidor, então nenhum
estado é perdido exceto rolagem e foco — e isso é aceitável num caminho de exceção.

### Limite de tamanho e como ele muda

O teto de 64 KB por pacote de `ScreenProtocol` continua valendo, mas passa a se aplicar por lote de
patches, não por tela. O documento inteiro ganha um teto próprio, medido no servidor:

| Limite | Valor proposto | Motivo |
|---|---|---|
| Nós no documento | 512 | Acima disso o layout aparece no profiler |
| Profundidade | 32 | A recursão de layout estoura antes de ser útil |
| Patches por lote | 128 (acima, vira `reset`) | Contém o pior caso |
| Bytes por lote | 8 KB (acima, vira `reset`) | Idem |
| Documentos abertos por jogador | 1 tela + 1 HUD | Uma tela por vez, como já é hoje |
| Mutações por callback | 4.096 (acima, erro de script) | Um laço patológico é bug, não uso |

O último merece atenção: exceder gera **erro no script**, não corte silencioso. Um mod que faz
quatro mil mutações num callback tem um bug, e o autor precisa saber.

---

## Eventos

### `onclick` aponta para uma função Lua, não executa código

```html
<button onclick="forjar" data-tipo="ferro">Forjar</button>
```

O valor do atributo é o **nome de uma função registrada**, não uma expressão. Não é `forjar()`, não
aceita argumentos, não é avaliado. O cliente nunca vê esse atributo de forma acionável: ele recebe
apenas "este elemento é interativo e tem id N"; o roteamento acontece no servidor, quando o evento
volta.

Isso preserva a regra que o projeto inteiro existe para preservar: **o cliente interpreta dados,
nunca código.** E preserva também a arquitetura de eventos que já funciona — o canal
`screen_event` não muda em nada.

Duas formas de registrar, e ambas valem a pena:

```lua
-- Declarativa, via atributo: o nome resolve na tabela de handlers da tela
mod.screen("forja", {
    html = mod.import("telas/forja.html"),
    on = {
        forjar = function(ctx, ev)
            local tipo = ev.target:get_attr("data-tipo")
            forjar_item(ctx, tipo)
        end,
        fechar = function(ctx, ev) ctx.player.close_screen() end,
    }
})

-- Programática, no elemento
doc:get("botao"):on("click", function(ctx, ev) ... end)
```

A declarativa é melhor para o caso estático (o HTML já diz quem trata o quê, e dá para ler a tela
sem ler o script). A programática é necessária para elementos criados em tempo de execução. Ter as
duas custa pouco, porque a segunda é o mecanismo e a primeira é açúcar que resolve o nome nela.

`el:off("click", fn)` remove; `el:on` devolve um identificador para remoção sem guardar a função.

### O objeto de evento

```lua
function(ctx, ev)
    ev.type        -- "click", "change", "submit", "close"
    ev.target      -- elemento que originou (objeto de DOM, não id)
    ev.current     -- elemento cujo handler está rodando (difere no bubbling)
    ev.value       -- conteúdo do campo, em change/submit; "" nos demais
    ev.button      -- 0 esquerdo, 1 direito, 2 meio; só em click
    ev.shift, ev.ctrl, ev.alt  -- modificadores
    ev:stop()      -- interrompe a subida
end
```

`ctx` continua sendo o contexto de sempre — `ctx.player`, `ctx.state`, `ctx.server` — para o handler
não precisar de um segundo mecanismo para falar com o jogo. `ev` é só o evento.

O vocabulário de `ev.type` continua sendo o conjunto fechado que `ScreenProtocol.ACTIONS` já define.
Ação desconhecida vinda do cliente continua sendo descartada pelo servidor: o cliente não dita o
vocabulário.

### Bubbling: sim, sem fase de captura

A propagação para os ancestrais vale a pena, e a fase de captura não.

**A favor do bubbling:** ele existe para *delegação*, e a delegação é exatamente o padrão que uma
lista dinâmica precisa. Sem ele, uma lista de cinquenta itens exige cinquenta handlers registrados e
re-registrados a cada reconstrução. Com ele:

```lua
doc:get("inventario"):on("click", function(ctx, ev)
    local linha = ev.target:closest(".linha")
    if linha then usar_item(ctx, linha:get_attr("data-id")) end
end)
```

Um handler, e ele continua correto depois de a lista ser reconstruída inteira. Custa ~25 linhas:
subir por `parent` até a raiz, chamando handlers do tipo certo, parando se `ev:stop()` foi chamado.
`el:closest(sel)` é mais 10 linhas e é o que torna a delegação legível.

**Contra a fase de captura:** ela existe na web por um acordo histórico entre Netscape e Microsoft,
e quase ninguém a usa deliberadamente. Ela dobraria a superfície conceitual do modelo de eventos
para resolver casos que aqui não aparecem. Fora.

`stopImmediatePropagation`, `preventDefault` e `event.eventPhase` também ficam fora: `preventDefault`
não tem sentido num sistema sem comportamento padrão de navegação, e os outros dois são
refinamentos de um mecanismo que aqui é simples de propósito.

### Roteamento e segurança do evento

O evento chega do cliente como (id da tela, id do nó, ação, valor). O servidor:

1. Confere que a tela existe e pertence ao jogador que mandou.
2. Confere que o id do nó existe **naquele documento**. Um id inventado é descartado com log.
3. Confere que o nó é interativo. Um `change` num `<div>` é descartado.
4. Monta `ev`, resolve os handlers subindo a árvore, chama dentro do orçamento.

O passo 2 importa mais do que parece: sem ele, um cliente modificado dispara eventos em nós que não
tem como ter clicado. Com ele, o pior que um cliente hostil consegue é clicar em botões que estão
mesmo na tela dele — que é o que ele poderia fazer com o mouse de qualquer jeito.

---

## Orçamento de execução

O loader já limita cada callback a 20 ms por um gancho de instrução em `ExecutionBudget`. A pergunta
é se isso basta quando o callback manipula DOM.

### O que o orçamento atual cobre bem

Manipulação de DOM em Lua **é** execução Lua, então cada `el.text = x` passa pelo `onInstruction` e
conta no relógio. Um laço infinito que mexe no DOM é cortado exatamente como qualquer outro laço
infinito. Nada a mudar aqui.

### Os três buracos

**1. Custo em Java não é medido em instruções, mas é medido no relógio.** Um `set_html` com 30 KB de
HTML roda o parser inteiro dentro de uma única instrução Lua. O gancho de instrução não vai vê-lo,
mas o `deadline` é comparado com o relógio de parede, então a próxima verificação — até 2.048
instruções depois — vai pegá-lo. Na prática o callback estoura o limite logo depois, e não durante.
Isso é aceitável, mas significa que **uma única operação Java pode passar de 20 ms sem ser
interrompida**. A mitigação é o teto de 32 KB de HTML: parsear 32 KB leva ~1 ms num parser simples,
folgado dentro do orçamento. Vale medir, e não supor.

**2. O custo real está no cliente, e o orçamento do servidor não o vê.** Um callback pode gerar, em
2 ms de Lua, um `reset` que custa 30 ms de layout no cliente. O orçamento do servidor está
perfeitamente satisfeito e o jogador perde quadros. **Nenhum limite de tempo no servidor conserta
isso** — o que conserta são os limites estruturais (512 nós, 32 de profundidade), que são o que
realmente limita o trabalho do cliente. É por isso que aqueles limites existem no núcleo e não no
adaptador.

**3. Muitos jogadores multiplicam o custo.** Vinte jogadores com a mesma tela aberta significam
vinte documentos e vinte lotes de patch. Cada callback individual respeita 20 ms; o tick não. Um
evento que dispara `update` para todo mundo pode somar 400 ms.

### Recomendação: manter o orçamento, acrescentar dois limites de UI

Não criar um orçamento próprio para UI. Duplicar o mecanismo de limite traria dois relógios,
duas configurações e a pergunta de qual vale quando um handler de UI chama uma função comum.

Em vez disso, três acréscimos pequenos:

| Acréscimo | Onde | O que resolve |
|---|---|---|
| Teto de mutações por callback (4.096) | Contador no diário; erro ao estourar | Laço que gera patches sem parar (buraco 1 e 3) |
| Limites estruturais do documento (512 nós, 32 de profundidade) | Verificado a cada `append` | Custo de layout no cliente (buraco 2) |
| Teto de documentos vivos por mod | No registro de telas | Vazamento de documento por jogador (buraco 3) |

O primeiro é o mais importante e o mais barato: um `int++` por mutação, comparado com um limite. Ele
transforma "o servidor engasgou e ninguém sabe por quê" em "o mod X excedeu o limite de mutações no
handler Y".

E uma regra de ouro para a documentação, que resolve o buraco 3 melhor que qualquer limite: **um
evento de um jogador deve atualizar a tela daquele jogador.** Atualizar a tela de todos exige um
laço explícito, que é visível na revisão do código.

---

## Ciclo de vida

### Quando o documento nasce e morre

| Momento | O que acontece |
|---|---|
| `ctx.player.open_screen(id, html)` | Núcleo parseia HTML e CSS, valida, monta o documento, atribui ids, serializa a árvore, envia `screen_open`. Devolve o objeto `doc` ao Lua, ou `nil` se o cliente não tem o loader. |
| Handler roda | Mutações vão para o diário; lote enviado no fim |
| Jogador fecha a tela | Cliente manda `close`; servidor dispara o handler `close`, depois descarta o documento |
| `ctx.player.close_screen()` | Servidor manda `screen_close` e descarta o documento |
| Jogador desconecta | Documento descartado sem disparar `close` |
| Jogador morre, muda de dimensão, abre outra tela | Cliente fecha; mesmo caminho de `close` |

O documento é **por jogador**, não por tela registrada. `mod.screen("forja", ...)` registra o
comportamento; cada `open_screen` cria uma instância. Dois jogadores com a forja aberta têm dois
documentos independentes, e é isso que permite que um veja um estado diferente do outro.

O `doc` devolvido ao Lua vale enquanto a tela estiver aberta. Depois disso, qualquer operação nele
lança erro com mensagem clara — não silêncio, e não `nil` que estoura três linhas adiante. É a mesma
regra que `PlayerHandle` já segue ao ser invalidado depois do callback.

Para obter o documento fora do callback que o abriu: `ctx.player.screen()` devolve o documento aberto
daquele jogador, ou `nil`. Isso é o que permite um `tick` atualizar uma barra de progresso.

### Convivência com `/lua reload`

O reload atual já limpa `menus` e `screens` do mod recarregado (`LuaRuntime` remove por `modId`).
Com DOM, isso deixa de bastar: os **clientes continuam com telas abertas** cujos ids não existem mais
no servidor. Um clique nelas vira um evento que não roteia para lugar nenhum, e o jogador fica
olhando uma tela morta.

**Recomendação: o reload fecha as telas dos mods recarregados.** É explicável, é imediato, e evita a
tela zumbi.

A alternativa — tentar reabrir a tela e restaurar o estado — é tentadora e não vale. O estado da tela
está espalhado entre `mod.state` (que sobrevive), o documento (que não), e closures de handler (que
mudaram de identidade na recarga). Reconstruir isso corretamente é um problema de migração de estado,
e resolvê-lo mal produz telas em estado inconsistente, que é pior que uma tela fechada.

O que vale é o meio-termo barato: **um evento `screen_closed_by_reload`** entregue ao mod depois do
reload, com a lista de jogadores que estavam com telas abertas. Um mod que queira reabrir reabre;
um que não queira não faz nada; e a decisão fica com quem tem o contexto.

```lua
mod.on("reloaded", function(ctx)
    for _, jogador in ipairs(ctx.reopened_screens) do
        abrir_forja(ctx, jogador)
    end
end)
```

Durante a substituição do ambiente, os callbacks já são pausados (o ciclo de vida em
`ARCHITECTURE.md` prevê isso), então não há risco de um handler do ambiente antigo rodar contra um
documento do novo.

### Organização com módulos Lua

O sistema de módulos (`mod.import`, com cache, detecção de ciclo e resolução dentro da pasta do mod)
é o que dá componentização a este design — e é por isso que Shadow DOM e custom elements ficaram de
fora. Um componente aqui é uma função Lua que devolve uma subárvore:

```lua
-- ui/componentes.lua
local M = {}

function M.linha_item(doc, item, quantidade, nome, acao)
    local linha = doc:create("div", { class = "linha" })
    linha:append(doc:create("item", { id = item, count = quantidade }))
    linha:append(doc:create("span", { class = "nome" })):set_text(nome)
    local botao = doc:create("button", { ["data-acao"] = acao })
    botao.text = "Usar"
    linha:append(botao)
    return linha
end

function M.titulo(doc, texto)
    local h = doc:create("h2", { class = "titulo" })
    h.text = texto
    return h
end

return M
```

```lua
-- telas/forja.lua
local componentes = mod.import("ui/componentes.lua")

local function montar(ctx, doc)
    local corpo = doc:get("corpo")
    corpo:clear()
    corpo:append(componentes.titulo(doc, "Forja de Cristal"))
    for _, receita in ipairs(ctx.state.receitas) do
        corpo:append(componentes.linha_item(doc, receita.item, receita.qtd,
                                            receita.nome, receita.id))
    end
end

return { montar = montar }
```

Isso é composição de verdade, com as regras de escopo do Lua, sem inventar um sistema de componentes.
O cache de módulo garante que `componentes.lua` roda uma vez por mod, e a detecção de ciclo já cobre
o erro de dois componentes se importarem.

**O CSS também deve ser importável.** `mod.import("ui/estilo.css")` devolvendo a string permite
compartilhar uma folha de estilos entre telas — que é a metade da promessa de "estilo separado da
estrutura" que ficaria de fora se cada tela tivesse que embutir o próprio `<style>`. O mesmo vale
para `.html`, que é o que o exemplo de `mod.screen` acima usa. O import passa a aceitar `.lua`,
`.html` e `.css`; os dois últimos devolvem a string crua, sem executar nada.

---

## Escopo: o subconjunto de HTML e CSS

### Tags suportadas

| Tag | Papel | Por que entra |
|---|---|---|
| `<div>` | Caixa de bloco genérica | É o container. Sem ele não há nada. |
| `<span>` | Caixa inline genérica | Texto com estilo dentro de uma linha. |
| `<p>` | Bloco de texto | Açúcar de `<div>` com margem vertical; barato e familiar. |
| `<h1>`–`<h3>` | Título | Açúcar de `<div>` com escala maior e negrito. Três níveis bastam. |
| `<button>` | Botão | Já existe no protocolo; herda o evento `click`. |
| `<input>` | Campo de texto | Já existe; `type="text"` e `type="password"` apenas. |
| `<img>` | Textura do jogo | `src="modid:textures/gui/x.png"`, nunca URL. |
| `<item>` | Ícone de item — **tag própria** | Não é HTML. É a única invenção, e ela se paga. |
| `<progress>` | Barra de progresso | Existe em HTML real, mapeia direto no elemento atual. |
| `<br>` | Quebra de linha | Uma linha de código no layout inline. |
| `<ul>`/`<li>` | Lista | Açúcar de bloco com marcador. Etapa tardia. |
| `<style>` | CSS embutido | Único lugar de onde CSS pode vir, além do import. |

`<item id="minecraft:iron_ingot" count="12">` é a única tag inventada. A alternativa purista seria
`<img src="item://minecraft:iron_ingot">`, e ela é pior: um item não é uma imagem — tem contagem,
durabilidade, encantamento brilhando e tooltip com nome traduzido. Forçá-lo em `<img>` mentiria
sobre o que é.

### Tags explicitamente fora

| Tag | Por que não |
|---|---|
| `<script>` | **Nunca.** É a linha que o projeto inteiro existe para não cruzar. Encontrá-la é erro de validação no núcleo, não algo ignorado em silêncio. |
| `<a>`, `<iframe>`, `<video>`, `<audio>`, `<object>`, `<embed>` | Todas puxam recurso externo. Um mod remoto pedindo ao cliente que busque uma URL é um vetor de rastreamento de IP e de tráfego não solicitado. |
| `<form>` | O modelo de submissão HTTP não existe aqui. `<button>` mais evento cobre o caso. |
| `<table>` | O algoritmo de tabela é o mais complexo do CSS depois de grid — largura de coluna depende de todo o conteúdo, com duas passadas e colapso de borda. Uma tabela simples é `flex` com colunas; uma complexa não deveria estar num GUI de mod. |
| `<select>`, `<textarea>`, `<canvas>`, `<svg>` | Cada um exige um widget e estado próprios no cliente. Podem entrar depois, um a um, se a demanda aparecer. |
| Tag desconhecida | Tratada como `<div>` anônimo: o conteúdo aparece, a tag é ignorada, um aviso vai ao log. |

### Propriedades CSS suportadas

| Grupo | Propriedades | Notas |
|---|---|---|
| Caixa | `width`, `height`, `min-width`, `max-width`, `padding`, `margin`, `border`, `box-sizing` | `border-box` é o **padrão**. `margin: auto` centraliza. |
| Fluxo | `display: block \| inline \| inline-block \| flex \| none` | Cinco valores. Nada mais. |
| Flex | `flex-direction`, `justify-content`, `align-items`, `gap`, `flex` (só o *grow*) | Um eixo, sem `wrap`. |
| Texto | `color`, `text-align`, `font-size`, `font-weight: bold`, `text-shadow: none` | `font-size` vira escala, não pixels. |
| Pintura | `background-color`, `background-image`, `border-color`, `border-width`, `border-slice`, `opacity` | `background-image` aceita textura do jogo e nine-slice. |
| Overflow | `overflow: hidden \| scroll` | `scroll` é a etapa mais cara; ver plano incremental. |

Unidades: `px`, `%`, `auto`. E só.

**`em` e `rem` ficam fora porque a fonte do Minecraft não é escalável de verdade.** A fonte bitmap
tem 9 px de altura de linha; escalar por 1,5 interpola e borra. As escalas úteis são poucas, então
`font-size` aceita apenas um conjunto pequeno (`1x`, `1.5x`, `2x`, `3x`) e mapeia no `scale` que o
renderizador já tem. Fingir suporte a `font-size: 13px` prometeria uma precisão que a fonte não
entrega.

**`calc()` fica fora** porque exige parser de expressão, avaliação e regras de mistura de unidades —
para resolver um problema que `flex: 1` e `%` já resolvem nos casos reais.

### Seletores CSS suportados

| Seletor | Exemplo | Especificidade |
|---|---|---|
| Tag | `button` | 1 |
| Classe | `.linha` | 10 |
| Id | `#forjar` | 100 |
| `style=` inline e `el.style` | — | 1000 |

Sem descendente (`.a .b`), sem filho (`>`), sem `:nth-child`, sem media queries. Uma pseudo-classe:
**`:hover`**, porque é a única que muda a sensação da interface — um botão que não reage ao mouse
parece quebrado — e custa um teste de retângulo por frame contra uma tabela já calculada.

Seletor descendente fica fora porque exige subir a árvore por candidato a cada casamento, e classes
resolvem o mesmo problema com uma palavra a mais. É o corte com melhor razão custo/benefício da
lista. (O `query` do DOM aceita os mesmos seletores, pela mesma razão.)

### Herança

Herdam-se apenas `color`, `text-align`, `font-size` e `font-weight`. Todo o resto começa no valor
inicial. É o que evita que `padding` de um container vaze para os filhos, e é uma lista curta o
bastante para caber na cabeça.

---

## Layout: a parte difícil

Esta seção decide se o projeto é viável. A ordem vai do barato ao caro; a recomendação é parar no
nível 3.

### Nível 0 — Modelo de caixa

Quatro anéis: conteúdo, `padding`, `border`, `margin`. `border-box` por padrão, margens que não
colapsam — as duas divergências já justificadas na abertura.

`margin: auto` na horizontal centraliza, e isso é obrigatório desde a primeira etapa: é o item 4 da
lista de prioridades, o jeito mais direto de centralizar um bloco, e custa cinco linhas.

Custo: ~80 linhas. **Obrigatório.**

### Nível 1 — Fluxo de blocos

Cada `display: block` ocupa a largura disponível do pai e empilha verticalmente. A altura de um bloco
sem `height` é a soma das alturas dos filhos.

```
layout(no, larguraDisponivel):
    largura = resolveLargura(no, larguraDisponivel)    # px, %, ou auto = disponível
    interna = largura - padding - border
    y = 0
    para cada filho:
        layout(filho, interna)
        filho.y = y + filho.marginTop
        filho.x = padding.left + margemEsquerda(filho)   # auto centraliza
        y = filho.y + filho.altura + filho.marginBottom
    no.altura = altura declarada, ou y + padding.bottom
```

Uma passada, sem retrocesso: a largura flui de cima para baixo, a altura de baixo para cima.

Custo: ~150 linhas. **Obrigatório** — é isso que elimina a aritmética manual, ou seja, é o projeto.

### Nível 2 — Inline e quebra de linha

Texto e `<span>` se acumulam numa caixa de linha até estourar a largura, então quebram. Exige medir
texto, o que só o cliente pode fazer (`textRenderer.getWidth`).

Simplificações que valem a pena:

- **Quebra só em espaço.** Sem hifenização, sem UAX #14. Texto CJK sem espaços vira uma linha longa
  cortada por `overflow`. É um limite real, documentado e não escondido.
- **Altura de linha fixa** em 9 px vezes a escala, mais 2 px de entrelinha. Sem `line-height`
  variável, sem baseline entre escalas diferentes na mesma linha (o maior manda, os outros
  centralizam nele).
- **`vertical-align` não existe.** Tudo centraliza verticalmente na linha.

Custo: ~200 linhas. **Recomendado** — sem isso, texto longo vaza, que é o que mais acontece quando um
mod é traduzido.

### Nível 3 — Flexbox de um eixo

Ponto de parada recomendado. Flexbox completo tem `wrap`, `basis`, `shrink`, `order`, `align-self`,
`align-content` e uma especificação de nove etapas. O subconjunto útil aqui é bem menor:

| Suportado | Ignorado |
|---|---|
| `flex-direction: row \| column` | `row-reverse`, `column-reverse` |
| `justify-content: start \| center \| end \| space-between` | `space-around`, `space-evenly` |
| `align-items: start \| center \| end \| stretch` | `baseline`, `align-self` |
| `gap: Npx` | `row-gap`/`column-gap` separados |
| `flex: N` (só o fator de crescimento) | `flex-shrink`, `flex-basis`, `order`, `flex-wrap` |

```
1. Meça cada filho no tamanho natural (largura declarada, ou conteúdo).
2. sobra = container - soma(naturais) - gaps
3. sobra > 0 e há filhos com flex: distribua proporcional ao fator.
4. sobra < 0: corte proporcional e uniforme, com piso no mínimo.
5. Posicione no eixo principal por justify-content.
6. Posicione no eixo cruzado por align-items; stretch preenche.
```

Sem `wrap`, é uma passada extra sobre os filhos.

Custo: ~180 linhas. **Recomendado.** É o que torna "rótulo à esquerda, botão à direita, esticando
com a janela" uma linha de CSS em vez de uma conta.

### Nível 4 — O que NÃO fazer

| Recurso | Por que fica de fora |
|---|---|
| `flex-wrap` | Exige agrupar filhos em linhas e depois distribuir o eixo cruzado entre elas. Praticamente dobra o código do flex. Uma grade de itens é melhor servida por um elemento próprio. |
| CSS Grid | O algoritmo de trilhas com `fr`, `minmax`, `auto-fit` e colocação automática é maior que toda esta engine somada. |
| `float` | Existe para texto ao redor de imagens em documentos. Nenhum GUI de mod precisa. |
| `position: absolute/relative/fixed` | Cria um segundo sistema de coordenadas com bloco contenedor, empilhamento e `z-index`. E a camada atual **já é** posicionamento absoluto: quem precisa disso usa ela. |
| `transform`, `transition`, `animation` | Exige estado por frame e interpolação no cliente. É uma feature separada. |

### Um elemento `grid` próprio, em vez de CSS Grid

A necessidade real por trás de "quero uma grade" em Minecraft é quase sempre um inventário: N colunas
de células iguais. Isso não pede um sistema de layout; pede um atributo.

```html
<div display="grid" columns="9" cell="18">
  <item id="minecraft:stone" count="64"></item>
  ...
</div>
```

Trinta linhas, resolve o caso concreto, e não cria a expectativa de que `grid-template-areas` vai
funcionar. É o tipo de troca que o documento inteiro defende.

---

## Parsing

### Opções avaliadas

| Opção | Tamanho | Licença | Veredito |
|---|---|---|---|
| **jsoup** | ~440 KB | MIT | Ótimo parser de HTML, tolerante a erro, com seletores. Mas traz DOM completo, normalização HTML5 e um motor de seletores muito maior que o subconjunto. E não parseia CSS: exigiria uma segunda lib. |
| **CSS Parser (SourceForge)** | ~350 KB | LGPL | Parser SAC completo. **LGPL é problema** num jar distribuído: obriga a permitir relinkagem, o que é atrito real na distribuição via Modrinth/CurseForge. |
| **ph-css** | ~800 KB | Apache 2.0 | Muito completo, muito grande. |
| **Flying Saucer / iText** | multi-MB | variadas | Fora de questão. |
| **Parser próprio** | ~900 linhas | própria | Recomendado. |

### Recomendação: parser próprio

O argumento não é "não invente a roda". É que **o subconjunto é pequeno o bastante para que a roda
pronta seja maior que o carro**:

| Peça | Linhas | O que faz |
|---|---|---|
| Tokenizador HTML | ~180 | Texto, `<tag>`, `</tag>`, atributos, comentários, tags vazias |
| Construtor de árvore | ~120 | Pilha de tags abertas, fechamento implícito, tolerâncias |
| Tokenizador CSS | ~150 | Regras, seletores por vírgula, declarações, comentários |
| Resolução de valores | ~200 | Cores, unidades, atalhos (`padding: 4px 8px`), enums |
| Casamento de seletor e cascata | ~130 | Ordena por especificidade, aplica, herda |
| Sanitização e limites | ~120 | Profundidade, contagem, tags proibidas, tamanho |
| **Total** | **~900** | |

Novecentas linhas é comparável ao `ScreenBuilder` mais o `ScreenModel` somados, e o projeto já
demonstrou apetite por peças desse tamanho. Em troca: zero dependência nova, controle total sobre o
que é aceito, e mensagens de erro escritas para quem escreve mods em Lua — não mensagens de uma lib
genérica falando de DOM.

Ressalva honesta: **um parser de HTML tolerante a erro é mais difícil do que parece.** O tratamento
de erro do HTML5 tem centenas de casos (elementos de formatação ativos, *adoption agency algorithm*).
A saída é não tentar: aceitar **HTML bem formado** com um punhado de tolerâncias explícitas, e
rejeitar o resto com erro claro. É aceitável porque o HTML aqui é escrito por um humano num arquivo
de mod, não raspado da web.

Tolerâncias que valem a pena:

- Tag não fechada no fim do documento: fechada implicitamente.
- `<br>`, `<img>`, `<input>`, `<item>` sem barra final: tratadas como vazias.
- Atributo sem aspas, se não tiver espaço: aceito.
- Tag desconhecida: vira `<div>` anônimo, conteúdo preservado.
- Tag de fechamento sem abertura: descartada, com aviso.

Tudo além disso é erro de validação, com linha e coluna.

### Interpolação: `{nome}` escapa, sempre

Para o caso mais comum — HTML fixo com dados variáveis:

```lua
ctx.player.open_screen("forja", html, { nome = "Ferro", qtd = 12 })
-- no HTML: <span>{nome}</span> x <span>{qtd}</span>
```

**A interpolação substitui apenas texto, com escape automático de `<`, `>` e `&`.** Sem escape, um
nome de jogador contendo `<button onclick="admin">` viraria um botão de verdade. `{nome}` não pode
produzir estrutura, nunca — nem que o valor venha de dentro do próprio mod.

Nada de condicionais, laços ou expressões no template. Quem precisa de lógica usa o DOM, que é a
resposta certa e está logo ali. Uma linguagem de template dentro do HTML seria uma terceira
linguagem para aprender.

---

## Onde cada peça roda

| | Parsear no servidor | Parsear no cliente |
|---|---|---|
| Tráfego | Maior: árvore resolvida é 3–5× o fonte | Menor: HTML/CSS comprime bem |
| Código no cliente | Só o renderizador, quase inalterado | +900 linhas de parser |
| **Onde o DOM pode viver** | **No servidor, com o Lua** | Só no cliente, ou duplicado |
| Erro de HTML | Log do servidor, para quem escreveu | Máquina remota, que ninguém olha |
| Segurança | Num lugar só, testável sem Minecraft | Duplicada |
| Reage a texto e idioma | Não | Sim |
| Reage a redimensionar | Não | Sim |

A linha em negrito passou a decidir. **Se o DOM é do servidor — e ele precisa ser, porque o Lua roda
lá — então o parser precisa estar do lado do servidor**, ou o servidor não teria árvore para
manipular. O que era um trade-off equilibrado virou consequência da decisão anterior.

### Recomendação: parsing no núcleo, layout no cliente

Layout no servidor é impossível de fazer bem: **medir texto exige a fonte, e a fonte está no
cliente.** Um servidor faria layout chutando a largura de "Diamante", e chutaria errado em português,
alemão e chinês. Não é contornável.

Parsing não tem essa restrição, e no núcleo dá três coisas:

1. Erro de HTML aparece no log do servidor, para quem escreveu o mod.
2. A checagem de segurança fica num lugar só, testável sem Minecraft — como `ScreenBuilder` já faz.
3. Os limites são aplicados antes de o pacote sair, que é onde limites servem para alguma coisa.

O fluxo completo:

```
Lua (HTML/CSS texto, ou mod.import de .html/.css)
  → núcleo: parse + cascata + sanitização + limites  →  DOM (autoridade)
  → Lua manipula o DOM; mutações vão para o diário
  → fim do callback: coalescer → lote de patches (ou reset)
  → rede
  → cliente: aplica patches na árvore espelho
  → cliente: layout (mede texto, resolve flex) → retângulos
  → cliente: renderiza a lista plana de retângulos
  → cliente: hit-test do mouse → evento pelo canal existente
```

O custo é o tráfego da árvore resolvida, mitigado por só serializar propriedades não-padrão — na
prática a maioria dos nós carrega três ou quatro campos — e por os patches serem o caminho normal
depois da abertura.

O código de cliente que isto exige é o motor de layout, ~600 linhas, mais ~150 de aplicação de
patch. É o preço de UI que se adapta, e não há como pagar menos.

---

## Compatibilidade com a camada atual

A camada de coordenadas absolutas continua em uso, e este design é para depois. A relação entre as
duas importa.

### Opção A — Substituir `mod.screen`

Contra: quebra todo mod existente, e a camada atual é genuinamente melhor para o HUD, onde
posicionamento absoluto é exatamente o que se quer. Descartado.

### Opção B — Dois sistemas paralelos

Dois protocolos, duas telas de cliente, dois renderizadores. Contra: duplica renderizador, validação
e limites. Uma correção em desenho de texto teria que ser feita duas vezes; duas superfícies de
segurança para auditar. Descartado — é o caminho que parece barato e cobra juros para sempre.

### Opção C — Coexistir, compilando para o mesmo protocolo — **recomendado**

O layout no cliente produz, como saída, exatamente a lista plana de elementos posicionados que
`LuaScreen` já desenha. HTML é uma **camada de autoria**; o protocolo continua sendo a representação.

O que isso dá de graça:

- Um renderizador só. `panel` continua sendo `context.fill`, `label` continua sendo
  `drawTextWithShadow`.
- Mods existentes não mudam nada. `mod.screen("forja", function(ctx) ... end)` continua valendo.
- Um mod pode misturar: HTML no corpo, elementos absolutos num detalhe.
- Depurar é fácil: dá para logar a lista de retângulos e comparar com o esperado.

`mod.screen` ganha uma **segunda forma**, distinguida pelo tipo do segundo argumento:

```lua
mod.screen("forja", function(ctx) ... end)              -- forma atual, coordenadas
mod.screen("forja", { html = ..., on = { ... } })       -- forma nova, DOM
```

Uma tabela em vez de uma função. Sem flag, sem versão no manifesto, sem função nova com nome pior. O
mesmo padrão vale para `open_screen`: string de HTML abre um documento e devolve `doc`; tabela de
elementos mantém o comportamento atual e devolve booleano.

O protocolo precisa de quatro extensões, todas retrocompatíveis — campo opcional novo não muda a
versão, pela regra que `ScreenProtocol` já documenta:

| Extensão | Motivo |
|---|---|
| `border` em `panel` (cor, espessura, ou nine-slice) | Caixas com borda são o pão de cada dia do CSS |
| `clip` (retângulo de recorte) | `overflow: hidden` precisa |
| Um canal `screen_patch` | O caminho de mutação incremental |
| Um campo `tree` na descrição, alternativo a `elements` | O ponto de entrada do documento |

Mais um tipo de elemento, `nineslice`, para bordas no estilo do jogo.

O HUD **fica na camada absoluta por enquanto**, e talvez para sempre: é o único caso onde
posicionamento absoluto é a resposta certa (um contador no canto superior esquerdo não quer fluxo).
Levar HTML para o HUD é a última etapa do plano justamente porque pode não valer a pena.

---

## Estilo visual: reconciliar CSS com a estética do Minecraft

Um `<div>` com `background-color: #808080` desenhado com `context.fill` fica **errado** dentro do
jogo. Não tecnicamente: esteticamente. A interface do Minecraft é feita de texturas com relevo de
3 px, e um retângulo chapado ao lado disso parece um bug.

A solução é dar duas rotas e fazer a rota boa ser a fácil.

### Nine-slice como cidadão de primeira classe

```css
.janela {
  background-image: minecraft:textures/gui/container/generic_54.png;
  border-slice: 4px;   /* px de cada canto preservados na textura */
  border-width: 4px;   /* onde a borda termina e o conteúdo começa */
}
```

Nine-slice divide a textura em nove regiões: quatro cantos que nunca esticam, quatro bordas que
esticam num eixo, e um centro que estica nos dois. É como toda janela do jogo é desenhada, e é o que
faz uma caixa de qualquer tamanho parecer nativa. Custa um `drawTexture` nove vezes com regiões
calculadas: um tipo de elemento novo e ~60 linhas.

### Classes prontas

O mais importante para adoção: um CSS embutido no cliente com as aparências do jogo já definidas.

| Classe | Aparência |
|---|---|
| `.mc-window` | Fundo de janela de container, com nine-slice |
| `.mc-panel` | Painel escuro embutido, tipo slot |
| `.mc-slot` | Célula 18×18 de inventário |
| `.mc-button` | Botão vanilla (mas `<button>` já usa por padrão) |
| `.mc-tooltip` | Fundo roxo-escuro com borda em gradiente |

`<div class="mc-window">` produz algo que parece Minecraft sem uma linha de CSS, e
`background-color` continua disponível para quem quer o retângulo chapado de propósito.

### Fonte

Bitmap de 9 px com sombra de 1 px. Consequências que precisam estar documentadas:

- `font-size` aceita só um conjunto pequeno de escalas (`1x`, `1.5x`, `2x`, `3x`).
- `font-family` não existe. Existe uma fonte, mais `minecraft:uniform` para CJK.
- `font-weight: bold` usa o código `§l` do próprio jogo, não uma fonte separada.
- `text-shadow: none` é suportado porque a sombra é o padrão e às vezes atrapalha; qualquer outro
  valor é ignorado.
- Códigos `§` no texto continuam funcionando e têm precedência sobre `color`.

### Cores

`#RRGGBB` e `#RRGGBBAA` — o mesmo formato que `ScreenBuilder` já valida — mais um conjunto pequeno de
nomes (`white`, `black`, `red`, …, e os 16 nomes de cor do Minecraft). Sem `rgb()`, `hsl()`,
gradientes ou `currentColor`.

---

## Riscos e limites

### Custo por frame

O layout roda **quando a árvore ou o tamanho da janela muda**, não a cada frame. Essa é a regra de
desempenho mais importante da engine, e precisa ser invariante do código, não disciplina.

Por frame roda apenas: percorrer a lista plana de retângulos chamando `fill`/`drawText`, e o teste de
`:hover`. Uma tela de 200 elementos são 200 chamadas de desenho — comparável a uma tela de container
vanilla, irrelevante a 60 fps.

Um refinamento que vale a pena depois, não antes: **relayout parcial**. Um patch de `text` num nó de
largura fixa não muda o layout de mais nada; um patch de `insert` muda tudo abaixo dele. Marcar
subárvores como sujas e recalcular só elas é a otimização natural, e não deve ser feita na primeira
versão — o relayout completo de 512 nós já é rápido o bastante, e a versão incremental tem bugs
sutis de invalidação que só valem a pena quando o profiler pedir.

### Limites propostos

| Limite | Valor | Motivo |
|---|---|---|
| Nós no documento | 512 | Acima disso o layout aparece no profiler |
| Profundidade | 32 | A recursão estoura antes de ser útil |
| Regras CSS | 128 | Casamento é linear por nó; 512×128 já é meio milhão de testes |
| HTML fonte | 32 KB | Também o teto do custo de parse dentro do orçamento |
| Patches por lote | 128 (acima vira `reset`) | Contém o pior caso |
| Bytes por lote | 8 KB (acima vira `reset`) | Idem |
| JSON por pacote | 64 KB | Teto já existente |
| Mutações por callback | 4.096 | Acima é bug de script, e o autor precisa saber |
| Caixas de linha por bloco | 256 | Texto patológico não gera layout infinito |
| Documentos por jogador | 1 tela + 1 HUD | Como já é hoje |

Todos aplicados **no núcleo**, antes de o pacote sair, testáveis sem abrir o jogo.

### Entrada inválida

Uma regra por camada, cada uma escolhida pelo que ajuda quem está do lado dela:

| Camada | Diante de entrada inválida | Por quê |
|---|---|---|
| Núcleo (parse) | **Falha com mensagem, linha e coluna** | Quem escreveu o mod está do outro lado |
| Núcleo (segurança) | **Falha sempre** — `<script>`, `on*` desconhecido, URL externa | Nunca degradar em silêncio numa questão de segurança |
| Núcleo (DOM) | **Erro no script** — nó removido, documento fechado, propriedade inválida | Silêncio aqui vira uma hora de depuração |
| Cliente (patch) | **Patch para id desconhecido pede `reset`** | Divergência é recuperável, não fatal |
| Cliente (layout) | **Ignora o que não entende** | O jogador não pode fazer nada; melhor tela parcial que nenhuma |

É a mesma divisão que `ScreenBuilder` e `ScreenModel` já praticam, e mantê-la é consistência com o
que o projeto decidiu.

### Riscos sem mitigação completa

1. **Escopo escorregando.** "Só falta `flex-wrap`", "só falta seletor descendente", "só falta
   `calc()`", "só falta `getBoundingClientRect`". Cada um é pequeno; juntos são um navegador. A
   defesa é este documento: a lista do que fica de fora é tão normativa quanto a do que entra, e
   adicionar algo de lá exige mudar o documento primeiro.

2. **Expectativa de conformidade.** Quem sabe CSS vai escrever CSS que não funciona e reportar como
   bug. Chamar isto de "HTML/CSS" já é meio caminho para essa confusão. Vale um nome próprio —
   `mcml`, ou similar — e uma página de documentação que **comece** pela tabela de divergências e
   pela lista do que não existe.

3. **Dessincronização de DOM.** Servidor e cliente com árvores diferentes é a classe de bug mais
   difícil de diagnosticar deste design, porque o sintoma (tela errada) fica longe da causa (um
   patch aplicado fora de ordem). Mitigações: `reset` como rede de segurança, um contador de
   sequência no lote para detectar buraco, e um comando `/lua screen verify` que compara o hash da
   árvore dos dois lados. Vale construir o comando junto com os patches, não depois.

4. **Depuração de layout.** Não há inspetor de elementos. A defesa é `/lua screen dump`, que imprime
   a árvore com os retângulos calculados, e uma opção de desenhar as bordas de todas as caixas — o
   equivalente pobre de `* { outline: 1px solid red }`.

5. **Custo total.** ~900 linhas de parser, ~600 de layout, ~400 de DOM e patches, ~300 de
   renderização de estilo. É um componente de porte médio, comparável ao runtime Lua. Vale a pena se
   a UI for parte central do que os mods fazem. **Essa pergunta deve ser respondida antes da
   etapa 4**, olhando os mods que existirem até lá.

---

## Plano incremental

Cada etapa é utilizável sozinha e prova algo antes de a próxima começar. A ordem segue a lista de
prioridades da abertura — o fluxo primeiro, o sofisticado depois.

### Etapa 1 — Empilhamento vertical

Parser HTML mínimo (`<div>`, `<span>`, `<p>`, `<button>`, `<input>`, `<br>`), sem CSS, sem DOM.
Layout de bloco puro: largura total, altura por conteúdo, empilhamento. Atributos `id` e `style` com
`color` e `background-color`. Envio como árvore completa; `update_screen` reenvia.

Prova: **nunca mais escrever um `y`.** É a maior parte do valor do projeto, na etapa mais barata. Se
isso funciona, o resto é acrescentar propriedades.

### Etapa 2 — Caixa e centralização

`padding`, `margin`, `width` em `px` e `%`, `height`, `margin: auto`, `text-align`. Ainda sem
`<style>`: só `style=` inline.

Prova: os quatro primeiros itens da lista de prioridades estão completos. Uma tela centralizada com
espaçamento correto, sem uma coordenada.

### Etapa 3 — CSS com `<style>`, classes e cascata

Tokenizador CSS, seletores de tag/classe/id, especificidade, herança das quatro propriedades
herdáveis. `mod.import` de `.css` e `.html`.

Prova: estrutura e estilo separados de verdade. O exemplo da abertura roda.

### Etapa 4 — DOM em Lua, com patches

`doc:get`, `doc:query`, `el.text`, `el.style`, `el:add_class`, `doc:create`, `el:append`,
`el:remove`. Diário de mutações, coalescência, canal `screen_patch`, fallback `reset`. Eventos por
`onclick` nomeado e `el:on`, com bubbling e `el:closest`.

Prova: uma tela viva que atualiza sem reenviar. **É o marco natural para revisar se o investimento
total continua justificado**, porque tudo antes daqui é útil mesmo se o projeto parar.

### Etapa 5 — Texto inline e quebra de linha

Medição via `textRenderer`, caixas de linha, `font-size` como escala, `<h1>`–`<h3>`.

Prova: texto longo se comporta, e traduções não quebram a tela. É onde a decisão de fazer layout no
cliente se paga visivelmente.

### Etapa 6 — Flexbox de um eixo

`display: flex`, `flex-direction`, `justify-content`, `align-items`, `gap`, `flex: N`.

Prova: barras de ferramentas, linhas com botão à direita, colunas que dividem espaço.

### Etapa 7 — Estética do jogo

`nineslice`, `border`, classes `.mc-*`, `:hover`. Nada novo em layout; só aparência.

Prova: uma tela em HTML fica indistinguível de uma tela do jogo.

### Etapa 8 — `<img>`, `<item>`, `<progress>`, `grid`

Os três elementos que já existem no protocolo, agora acessíveis por tag, mais o `grid` de inventário.
Barato, porque o renderizador já os desenha; falta só o caminho de autoria.

### Etapa 9 — `overflow: hidden` e recorte

Retângulo de recorte no protocolo, `enableScissor` no cliente. Pré-requisito da próxima.

### Etapa 10 — `overflow: scroll`

Estado de rolagem por elemento no cliente, roda do mouse, barra. Introduz estado próprio no cliente
além do texto digitado — a fronteira que `UI_SPEC.md` hoje declara fora de escopo — e por isso vem
por último, decidida com o que se souber até lá.

### Etapa 11 — HUD por HTML

O mesmo pipeline, sem interação, no gancho de HUD. Fácil depois de tudo acima. E o HUD é justamente o
caso onde posicionamento absoluto continua defensável, então **esta etapa pode simplesmente não
acontecer sem prejuízo** — o que é o melhor sinal de que ela pertence ao fim da lista.

---

## O que este documento recomenda não fazer

Reunido num lugar só, porque é a parte mais fácil de esquecer:

- Não implementar `<script>`, `on*` como expressão, nem qualquer execução no cliente. Nunca, em
  nenhuma etapa. `onclick` é o **nome** de uma função Lua, e nada além disso.
- Não expor geometria ao Lua (`offsetWidth`, `getBoundingClientRect`, `scrollTop`). O servidor não
  sabe onde nada está, e fingir que sabe custaria uma ida e volta pela rede.
- Não expor `innerHTML` como propriedade. `set_html` explícito, e só.
- Não implementar fase de captura de eventos, `preventDefault`, nem `MutationObserver`.
- Não implementar CSS Grid, `float`, `position: absolute`, `flex-wrap`, `calc()`, `em`/`rem`.
- Não implementar seletores descendentes nem pseudo-classes além de `:hover`.
- Não implementar tabelas.
- Não implementar uma linguagem de template com lógica dentro do HTML — o DOM é a resposta.
- Não fazer layout no servidor.
- Não reenviar a árvore inteira a cada mutação.
- Não manter dois renderizadores.
- Não tentar restaurar telas automaticamente depois de `/lua reload` — fechar e avisar o mod.
- Não fazer relayout incremental na primeira versão.
- Não prometer conformidade com a web em lugar nenhum da documentação.

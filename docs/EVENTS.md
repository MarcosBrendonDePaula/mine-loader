# Mine Loader — catálogo de eventos

Este documento lista os eventos que o loader expõe hoje e os que pretende expor. Serve como
contrato: um evento só entra na lista de implementados quando é disparado de verdade, com contexto
definido e teste cobrindo.

## Dois tipos de evento

O loader tem duas formas de entregar um evento, e a escolha entre elas não é estilo:

| Tipo | Onde é declarado | Quem recebe |
|---|---|---|
| **Global do mod** | `events` no manifesto, ou `mod.on(...)` no Lua | O mod inteiro, para qualquer ocorrência |
| **Por objeto** | `behavior` dentro do bloco ou item | Apenas aquele bloco ou item |

Um evento que tem dono natural — clicar num bloco, usar um item — deve ser **por objeto**, para que o
script não precise descobrir com quem está falando. Um evento sem dono — servidor subiu, jogador
entrou — é **global**.

Eventos por objeto têm prioridade: quando o manifesto associa lógica a um bloco, o callback global
do mod não é chamado para aquele bloco.

## Implementado hoje

### Globais

| Evento | Quando ocorre | Contexto |
|---|---|---|
| `loader_ready` | O loader terminou de carregar todos os mods | `ctx.log`, `ctx.time` |
| `server_started` | O servidor ficou pronto | `ctx.server`, `ctx.time` |
| `server_stopped` | O servidor está parando | `ctx.server`, `ctx.time` |
| `player_joined` | Um jogador entrou | `ctx.player` |
| `tick` | Fim de cada tick do servidor | `ctx.server` |
| `player_left` | Um jogador saiu | `ctx.player` |
| `block_used` | Clique direito em bloco declarativo | `ctx.block`, `ctx.player` |
| `block_attacked` | Clique esquerdo em bloco declarativo | `ctx.block`, `ctx.player` |
| `block_placed` | O bloco foi colocado no mundo | `ctx.block`, `ctx.player` |
| `block_broken` | O bloco deixou de existir na posição | `ctx.block` |
| `block_random_tick` | Tick aleatório, com `settings.random_ticks` ligado | `ctx.block` |
| `block_neighbor_update` | Um bloco vizinho mudou | `ctx.block` |

### Por objeto (bloco)

| `behavior` | Evento correspondente |
|---|---|
| `on_use` | `block_used` |
| `on_attack` | `block_attacked` |
| `on_placed` | `block_placed` |
| `on_broken` | `block_broken` |
| `on_random_tick` | `block_random_tick` |
| `on_neighbor_update` | `block_neighbor_update` |
| `on_break` | apelido antigo de `on_attack`, ainda aceito |

## Cancelamento

Um callback pode impedir a ação padrão do jogo devolvendo `false`:

```lua
return function(ctx)
    if ctx.player == nil then
        return false  -- bloqueia a interação
    end
end
```

Devolver `nil`, nada ou qualquer outro valor deixa o jogo seguir normalmente, para que um script
que apenas observa não precise se preocupar com o retorno. Se vários mods reagem ao mesmo evento,
basta um pedir cancelamento para a ação ser bloqueada.

Hoje o cancelamento vale para `block_used` e `block_attacked`. Eventos de notificação, como
`block_broken`, informam algo que já aconteceu e ignoram o retorno.

## Estado compartilhado

Todos os scripts de um mod compartilham `mod.state`, uma tabela criada pelo loader:

```lua
-- scripts/torre/on_use.lua
return function(ctx)
    ctx.state.construidas = (ctx.state.construidas or 0) + 1
    ctx.log.info("torres construidas nesta sessao: " .. ctx.state.construidas)
end
```

A mesma tabela é alcançada por `mod.state` no corpo do script e por `ctx.state` dentro de um
callback.

| Propriedade | Comportamento |
|---|---|
| Escopo | Um mod não enxerga o estado de outro. |
| Recarga | O estado sobrevive a `/lua reload`, para que alterar um script durante o desenvolvimento não apague o que foi acumulado. |
| Persistência | Vive em memória e some quando o servidor para. Salvar em disco ainda não existe. |

Variáveis globais Lua soltas também são visíveis entre os scripts do mesmo mod, porque compartilham
o ambiente, mas não são o caminho recomendado: elas se perdem em uma recarga e colidem com os nomes
da API.

## Nomes antigos ainda aceitos

| Campo antigo | Situação |
|---|---|
| `behavior.on_break` | Apelido de `on_attack`. Descrevia bater no bloco, não quebrá-lo, e o nome confundia: um script que contasse quebras contaria cada batida da mineração. Continua funcionando, com aviso na carga. |
| `behavior.on_place` | Nunca foi implementado; foi substituído por `on_placed`. Aparece no diagnóstico como campo não aplicado. |

## Planejado

A ordem reflete utilidade para quem cria conteúdo, não facilidade de implementação.

### Bloco, por objeto

| Evento | Quando ocorre | Prioridade |
|---|---|---|
| `on_stepped_on` | Uma entidade pisou no bloco | baixa |
| `on_entity_collision` | Uma entidade encostou no bloco | baixa |
| `on_exploded` | O bloco foi destruído por explosão | baixa |

### Item, por objeto

Nenhum evento de item existe hoje: itens são registrados, mas não têm comportamento.

| Evento | Quando ocorre | Prioridade |
|---|---|---|
| `on_use` | Clique com o item na mão, no ar | alta |
| `on_use_on_block` | Clique com o item sobre um bloco | alta |
| `on_attack_entity` | O item foi usado para atacar | média |
| `on_crafted` | O item saiu de uma receita | baixa |
| `on_inventory_tick` | A cada tick, com o item no inventário | baixa, custo alto |

### Jogador, global

| Evento | Quando ocorre | Prioridade |
|---|---|---|
| `player_died` | O jogador morreu | média |
| `player_respawned` | O jogador renasceu | média |
| `player_chat` | O jogador enviou uma mensagem | média |
| `player_changed_dimension` | O jogador mudou de dimensão | baixa |

### Mundo e servidor, global

| Evento | Quando ocorre | Prioridade |
|---|---|---|
| `mod_reloaded` | Um mod foi recarregado por `/lua reload` | alta |
| `world_loaded` | Um mundo terminou de carregar | média |
| `explosion` | Uma explosão ocorreu | baixa |
| `chunk_loaded` / `chunk_unloaded` | Um chunk entrou ou saiu de memória | baixa, volume alto |

## Decisões que precisam ser tomadas antes de crescer a lista

Estas questões valem para todos os eventos novos, e resolvê-las depois seria mais caro.

**Custo por evento.** `tick`, `on_inventory_tick` e `chunk_loaded` disparam com frequência alta. Eles
não podem ser entregues a mods que não os declararam, e provavelmente precisam de um orçamento de
tempo por callback, para que um script lento não derrube o TPS do servidor.

**Contexto invalidado.** A especificação já diz que contextos não devem expor referências de longa
duração a objetos internos do Minecraft. Com mais eventos, é preciso decidir se o `ctx` é invalidado
ao fim do callback, para que guardar `ctx.player` numa variável Lua não vire um vazamento.

**Thread.** Eventos originados fora da thread do servidor precisam ser agendados nela antes de tocar
o mundo. Hoje todos os eventos já chegam na thread correta, mas isso deixa de valer assim que
existirem eventos de rede ou de carregamento assíncrono.

## Regra de entrada

Um evento só é listado como implementado quando cumpre as quatro condições:

1. É disparado pelo adaptador em um ponto real do jogo.
2. Tem contexto documentado nesta página.
3. Tem teste no núcleo, com uma bridge falsa, sem depender do jogo.
4. Aparece no diagnóstico de campos não aplicados enquanto não estiver pronto.

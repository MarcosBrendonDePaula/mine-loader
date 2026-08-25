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
| `block_scheduled` | Chegou o tique pedido com `schedule_block` naquela posição | `ctx.block` |
| `item_used` | Item usado na mão, sem alvo | `ctx.item`, `ctx.player` |
| `item_used_on_block` | Item usado sobre um bloco | `ctx.item` com `target_block` e posição, `ctx.player` |
| `menu_click` | Slot clicado em uma janela do mod | `ctx.menu` com `id`, `slot`, `button` e `item`, `ctx.player` |

### Do cliente

O lado que faltava. Até acoplá-lo, todo evento nascia no servidor — ciclo de vida, tique, bloco,
item — e o cliente era só um renderizador: recebia descrição de tela e devolvia clique. Um mod não
tinha como saber que o jogador abriu o inventário, mesmo o loader já desenhando sobreposições
justamente sobre aquela tela.

| Evento | Quando |
|---|---|
| `client_screen_opened` | o jogador abriu uma tela do jogo |
| `client_screen_closed` | fechou aquela tela |

O callback recebe `ctx.client.screen` com o nome da tela, e o nome vem de `ScreenProtocol.TARGETS`
— o **mesmo** conjunto que a sobreposição usa. Reusar é o que impede um mod de aprender dois
vocabulários para falar da mesma tela. Do mais específico para o mais genérico: um baú chega como
`chest`, e não como `container`.

**O código continua sem atravessar a rede.** O cliente não passa a rodar script: ele relata um fato
de um vocabulário fechado, e quem reage continua no servidor. A regra de `UI_SPEC.md` — o cliente
interpreta dados, nunca código — fica de pé.

O nome do evento e o da tela são conferidos contra os conjuntos fechados **antes de qualquer script
ver o valor**: o que chega pela rede vem da máquina de quem joga, e um script não deveria precisar
desconfiar do próprio contexto. Um nome fora da lista é descartado em silêncio, e não vira erro —
um cliente mais novo que o servidor relataria fatos que este ainda não conhece, e derrubar a
conexão por isso seria transformar diferença de versão em falha.

### Por objeto (bloco)

| `behavior` | Evento correspondente |
|---|---|
| `on_use` | `block_used` |
| `on_attack` | `block_attacked` |
| `on_placed` | `block_placed` |
| `on_broken` | `block_broken` |
| `on_random_tick` | `block_random_tick` |
| `on_neighbor_update` | `block_neighbor_update` |
| `on_scheduled` | `block_scheduled` |
| `on_break` | apelido antigo de `on_attack`, ainda aceito |

**`block_scheduled` é o único que o próprio mod pede.** Os outros o jogo entrega quando algo
acontece; este chega porque o script chamou `ctx.server.schedule_block(x, y, z, tiques)` naquela
posição. É o que permite uma máquina processar ao longo do tempo, ou um cano mover um item passo a
passo em vez de teleportá-lo.

Três coisas que não são óbvias:

- **Não se repete.** Cada tique vale uma vez; continuar é agendar o próximo de dentro do próprio
  callback. Um evento que se repetisse obrigaria o loader a decidir quando parar, e essa decisão é
  de quem escreve o mod.
- **Só chega a bloco declarado por um mod.** Agendar num bloco do jogo é recusado na hora do pedido:
  a fila aceitaria, mas o tique iria para o método do bloco vanilla, e nada chegaria ao script.
- **A fila é a do jogo, e sobrevive ao save.** Ela é gravada com o chunk, então o que estava
  agendado volta na próxima sessão — e um chunk descarregado leva junto o que estava marcado nele.

O prazo vai de 1 a 24000 tiques, um dia de jogo. Zero e negativo são recusados porque o jogo os
trataria como "agora", o que de dentro do próprio callback é recursão sem folga.

### De criatura

| Evento | Quando dispara | Cancelável |
|---|---|---|
| `entity_spawned` | Uma criatura entra no mundo | não |
| `entity_damaged` | Uma criatura vai apanhar | **sim** |
| `entity_died` | Uma criatura morreu | não |
| `entity_tamed` | Uma criatura foi domesticada | não |

**Valem para qualquer criatura do mundo, e não só para as que o loader declarou.** É o que os torna
úteis: um mod de combate reage ao zumbi do jogo. Filtrar por tipo é decisão de quem escreve o mod —
`ctx.entity.id` traz o tipo.

O jogador não dispara nenhum deles. Anunciar a entrada de quem joga como "uma criatura nasceu" faria
todo mod de combate contar jogador como bicho.

O contexto traz `ctx.entity` com uma **fotografia** do instante:

| Campo | O quê |
|---|---|
| `uuid`, `id` | identificador no mundo e tipo |
| `x`, `y`, `z` | onde estava |
| `health`, `max_health` | vida no momento do evento, antes do que ele causa |
| `name` | nome personalizado, quando há |
| `amount` | dano envolvido, em `entity_damaged` |
| `source` | como o dano chegou, no vocabulário do jogo |
| `source_uuid`, `source_name` | quem causou, quando houve quem |

São valores, e não funções, porque o adaptador resolve tudo antes de disparar. No instante da morte,
perguntar a vida ao mundo já responderia zero, e um script que consultasse depois chegaria sempre
tarde demais. Os campos de origem só aparecem quando houve uma: um bicho que morreu de queda não tem
quem o matou, e um campo vazio ali faria o script tratar "ninguém" como um nome.

Só `entity_damaged` aceita cancelamento: devolver `false` impede o dano. Os outros são avisos do que
já aconteceu, e o retorno deles não muda nada.

### Da fase de registro

| Evento | Quando dispara |
|---|---|
| `on_register` | Antes de o jogo congelar os registros |

Declarado em `registration`, e aponta um **arquivo** `.lua`, como o `behavior` de um bloco — não uma
função do entrypoint. Carregar o `main.lua` aqui faria o topo dele executar duas vezes.

É uma fase, e não um evento comum: aqui não há servidor, jogador nem bloco para tocar, porque o
mundo ainda não existe. O contexto é pequeno de propósito — `ctx.log`, `ctx.register` e `ctx.mod_id`
—, já que oferecer o resto seria oferecer chamadas que só podem falhar. Ver `MOD_FORMAT_SPEC.md`.

### Por janela

Uma janela registra a própria lógica com `mod.menu(id, funcao)`. O callback recebe `ctx.menu` com o
slot clicado e o item exibido nele, e pode redesenhar com `ctx.player.update_menu(...)` sem fechar a
tela. Uma janela pertence ao mod que a registrou: um clique nunca alcança o callback de outro mod.

### Por objeto (item)

| `behavior` | Evento correspondente |
|---|---|
| `on_use` | `item_used` |
| `on_use_on_block` | `item_used_on_block` |

O contexto de um evento de item traz `ctx.item.id` e, quando o uso teve alvo, `ctx.item.target_block`
com as coordenadas em `ctx.item.x/y/z`. Ambos aceitam cancelamento: devolver `false` impede o efeito
padrão do item.

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

Hoje o cancelamento vale para `block_used`, `block_attacked`, `item_used` e `item_used_on_block`.
Eventos de notificação, como `block_broken`, informam algo que já aconteceu e ignoram o retorno.

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

| Evento | Quando ocorre | Prioridade |
|---|---|---|
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

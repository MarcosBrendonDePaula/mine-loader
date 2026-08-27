# Minimapa declarativo

O `minimap_demo` é a primeira demonstração de um minimapa feito sobre a API do MineLoader. A implementação combina um **snapshot de mapa no servidor** com um renderer client-side equivalente nos quatro runtimes mantidos. O Lua decide quais dados entram no snapshot; Fabric e NeoForge apenas desenham o mesmo modelo neutro.

A organização foi inspirada por padrões observados em projectos open source como [JustMap](https://github.com/Bulldog83/JustMap) e [VoxelMap Updated](https://github.com/fantahund/VoxelMap): cache incremental por região, composição separada do radar e dos waypoints, configuração persistente e uma camada de apresentação dedicada. O MineLoader não copia código, texturas ou APIs desses projectos.

## O que já funciona

| Função | Comportamento |
|---|---|
| Terreno | Lê a superfície com `top_y` e `get_block`, classifica blocos por uma paleta e aplica sombreado baseado na diferença de altura. |
| Cache | Guarda colunas por dimensão, aquece o mapa em espiral, limita leituras por actualização e revalida entradas antigas. |
| HUD | Envia uma única definição `type = "map"`, em vez de centenas de painéis. O cliente desenha grelha, sombra, moldura, recorte redondo e bússola. |
| Jogador | Mostra o jogador no centro e uma ponta baseada no último deslocamento conhecido. |
| Radar | Mostra entidades próximas como pontos; hostis usam vermelho e outras entidades usam azul claro. O resultado é limitado a 24 marcadores. |
| Waypoint | `/mod minimap_demo mark` guarda `Casa` na posição actual e `/mod minimap_demo clear` remove-o. O waypoint é associado à dimensão. |
| Configuração | A tecla `M` abre a configuração. O zoom controla simultaneamente o tamanho das células e o raio representado; coordenadas podem ser ocultadas. |
| Compatibilidade | A mesma descrição é interpretada por Fabric 1.21.1/1.21.4 e NeoForge 1.21.1/1.21.4. |

## Uso no exemplo

```text
/mod minimap_demo on
/mod minimap_demo off
/mod minimap_demo config
/mod minimap_demo mark
/mod minimap_demo clear
/mod minimap_demo zoom <1..4>
```

O comando `mark` guarda apenas um waypoint pessoal por jogador. Uma versão futura pode evoluir isso para uma lista de waypoints com nome, cor, dimensão e visibilidade, mas não deve criar árvores de HUD diferentes por jogador: condições individuais são avaliadas no callback server-side.

## Elemento `map`

O elemento faz parte do protocolo de HUD existente e é construído em Lua dentro de `ctx.player.set_hud`. A definição usa uma lista row-major de cores, com exactamente `columns * rows` células:

```lua
ctx.player.set_hud({
    {
        type = "map",
        anchor = "top_right",
        x = 4, y = 4,
        w = 150, h = 150,
        columns = 25,
        rows = 25,
        cells = cores,
        round = true,
        grid = false,
        direction_x = 1,
        direction_z = 0,
        markers = {
            { type = "player", x = 0.5, z = 0.5, color = "#F5D547" },
            { type = "waypoint", label = "Casa", x = 0.8, z = 0.2,
              color = "#55FF55" },
            { type = "entity", x = 0.6, z = 0.4, color = "#FF5555" }
        }
    }
})
```

| Campo | Obrigatório | Regra |
|---|---:|---|
| `type` | sim | Deve ser `map`. |
| `w`, `h` | sim | Tamanho visual positivo, dentro dos limites de tela. |
| `columns`, `rows` | não | Por defeito 25; cada dimensão vai até 64. |
| `cells` | sim | Lista de cores `#RRGGBB` ou `#RRGGBBAA` com exactamente `columns * rows` entradas. `false`/`nil` representa célula transparente. |
| `round` | não | Por defeito `true`; activa o recorte elíptico. |
| `grid` | não | Desenha linhas discretas quando as células têm espaço suficiente. |
| `direction_x`, `direction_z` | não | Vector normalizado aproximado para orientar o marcador do jogador. |
| `north` | não | Texto da bússola; por defeito `N`. |
| `markers` | não | Até 64 marcadores `waypoint`, `entity` ou `player`, com `x`/`z` normalizados entre 0 e 1. |

Os valores de `x` e `z` dos marcadores são relativos ao rectângulo já enviado, não são coordenadas Minecraft. O código Lua converte posições do mundo para essa forma antes de construir o HUD.

## Segurança e desempenho

O mapa não recebe classes de Minecraft, texturas internas, chunks vivos ou callbacks client-side arbitrários. O servidor controla as leituras através das permissões `world.read`, `entity.read`, `player.read`, `player.modify` e `player.menu`. O cliente recebe somente dados limitados e desenha-os; um cliente antigo que desconheça `map` ignora o elemento sem executar código.

A amostragem actual ainda é deliberadamente simples: uma coluna usa o bloco da superfície e uma paleta de identificadores. Isso produz um minimapa legível e barato, mas não equivale ainda à rasterização por modelos, biomas, iluminação ou texturas de um minimapa dedicado. O cache é por dimensão e pode crescer durante a sessão; não é ainda um mapa-múndi persistente com ficheiros regionais.

## Verificação

Os testes core verificam a serialização de um mapa com grelha e marcadores e recusam grelhas incompletas. A compilação e os testes dos quatro runtimes também cobrem o novo elemento. Os GameTests continuam server-side: comprovam carga, contrato e callbacks, mas **não substituem a verificação visual no cliente**. A posição real do HUD, máscara circular, legibilidade em diferentes escalas e sobreposição com outros mods ainda devem ser confirmadas manualmente no jogo.

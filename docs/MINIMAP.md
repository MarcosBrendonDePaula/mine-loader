# Minimapa declarativo

O `minimap_demo` usa uma **câmera virtual lógica** do MineLoader. O servidor publica a definição da câmera e continua a enviar apenas marcadores leves, como jogador, entidades próximas e waypoints. O bridge client-side rasteriza uma imagem aérea pequena a partir dos chunks que o cliente já recebeu e desenha essa textura no HUD.

A ideia segue padrões observados em [JustMap](https://github.com/Bulldog83/JustMap), [VoxelMap Updated](https://github.com/fantahund/VoxelMap) e [MapWriter](https://github.com/marcus8448/MapWriter): cache de dados, actualização incremental, radar separado e uma camada de apresentação dedicada. O MineLoader não copia código, texturas, assets ou APIs desses projectos.

## O que já funciona

| Função | Comportamento |
|---|---|
| Câmera lógica | Contrato `client.camera.virtual: 1.0.0`, versão 1, com `projection = "orthographic"`, `source = "world"`, `anchor = "player"` e `output = "texture"`. |
| Registro | Pode ser declarado em `mod.json` ou criado por `mod.camera("id", definição)` em Lua. O ID curto é qualificado pelo loader, por exemplo `minimap_demo:minimap`. |
| Textura | O bridge deriva um recurso físico interno determinístico a partir do ID qualificado. O Lua não escolhe `Identifier`, nome de textura, `NativeImage`, framebuffer ou API gráfica. |
| Captura | A superfície é amostrada no cliente e enviada para uma imagem pequena; não há uma segunda passagem 3D completa do `WorldRenderer` por frame. |
| HUD | `type = "map"` suporta `render = "client_camera"`, `render = "client_topdown"` legado e `render = "server_cells"`. Marcadores e moldura continuam sobre a imagem. |
| Jogador | Mostra o jogador no centro e pode indicar a direcção aproximada pelo deslocamento server-side conhecido. |
| Radar | Mostra entidades próximas como pontos; hostis usam vermelho e outras entidades usam azul claro. O resultado é limitado a 24 marcadores. |
| Waypoint | `/mod minimap_demo mark` guarda `Casa` na posição actual e `/mod minimap_demo clear` remove-o. O waypoint é associado à dimensão. |
| Configuração | A tecla `M` abre a configuração. O zoom altera o tamanho visual e os overrides de raio/resolução enviados no HUD; coordenadas podem ser ocultadas. |
| Compatibilidade | O contrato e os bridges existem em Fabric 1.21.1/1.21.4 e NeoForge 1.21.1/1.21.4. |

## Uso no exemplo

```text
/mod minimap_demo on
/mod minimap_demo off
/mod minimap_demo config
/mod minimap_demo mark
/mod minimap_demo clear
/mod minimap_demo zoom <1..4>
```

O comando `mark` guarda um waypoint pessoal por jogador. As condições individuais são avaliadas no servidor; a textura do terreno não é recalculada no Lua nem enviada como uma grelha de cores.

## Câmeras lógicas

A câmera é uma definição de dados, não uma instância de `Camera` do Minecraft. O modder escolhe um ID curto e o loader acrescenta o namespace do mod. Assim, dois mods podem declarar `minimap` sem partilhar estado ou textura.

```lua
local id = mod.camera("minimap", {
    projection = "orthographic",
    source = "world",
    anchor = "player",
    orientation = "north",
    resolution = 96,
    radius = 48,
    update_ticks = 5,
    output = "texture"
})
-- id == "minimap_demo:minimap"
```

O registro dinâmico é útil quando uma condição Lua decide se a câmera deve existir. Uma declaração estática é melhor quando a câmera faz parte do contrato permanente do mod:

```json
{
  "permissions": ["client.camera.register"],
  "requires": {
    "capabilities": {
      "client.camera.virtual": "1.0.0"
    }
  },
  "cameras": {
    "minimap": {
      "projection": "orthographic",
      "source": "world",
      "anchor": "player",
      "orientation": "north",
      "resolution": 96,
      "radius": 48,
      "update_ticks": 5,
      "output": "texture"
    }
  }
}
```

O mesmo ID pode aparecer no manifesto e no Lua para ligar uma definição estática a uma condição ou confirmar a configuração. Nesse caso, a definição precisa ser estruturalmente idêntica. Divergências não são resolvidas silenciosamente: o loader rejeita a carga ou o registro dinâmico.

### Limites do contrato v1

| Campo | Valores v1 |
|---|---|
| `projection` | Apenas `orthographic`. |
| `source` | Apenas `world`. |
| `anchor` | Apenas `player`. |
| `orientation` | `north` ou `player`; a rotação `player` ainda é uma orientação declarada para o bridge, não uma promessa de renderização visual já validada em todos os clientes. |
| `resolution` | De 16 a 192 pixels; o exemplo usa no máximo 96. |
| `radius` | De 8 a 96 blocos. |
| `update_ticks` | De 1 a 40 ticks. |
| `output` | Apenas `texture`. |

Câmeras em perspectiva, entidades renderizadas, pós-processamento, framebuffers expostos e uma câmera 3D arbitrária pertencem a uma capability futura. Não fazem parte deste contrato.

## Elemento `map`

O elemento é construído em Lua dentro de `ctx.player.set_hud`. Para usar uma câmera publicada, passe o ID qualificado ao modo `client_camera`:

```lua
ctx.player.set_hud({
    {
        type = "map",
        anchor = "top_right",
        x = -4, y = 4,
        w = 120, h = 120,
        render = "client_camera",
        camera = "minimap_demo:minimap",
        -- Se omitidos, estes três campos herdam a definição da câmera.
        resolution = 96,
        radius = 48,
        update_ticks = 5,
        round = true,
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
| `w`, `h` | sim | Tamanho visual positivo, dentro dos limites da tela. |
| `render` | não | `server_cells`, `client_topdown` ou `client_camera`; por defeito `server_cells`. |
| `camera` | em `client_camera` | ID qualificado, como `meu_mod:minimap`; o modo recusa referência vazia. |
| `resolution` | não | De 16 a 192 quando explícito; zero interno significa herdar da câmera. |
| `radius` | não | De 8 a 96 quando explícito; zero interno significa herdar da câmera. |
| `update_ticks` | não | De 1 a 40 quando explícito; zero interno significa herdar da câmera. |
| `columns`, `rows`, `cells` | só em `server_cells` | Grelha row-major de cores para o fallback compatível. Nos modos client-side, `cells` fica vazia. |
| `round` | não | Activa o recorte elíptico aproximado e a decoração dos cantos. |
| `grid` | não | Desenha linhas discretas na grelha server-side. |
| `direction_x`, `direction_z` | não | Vector aproximado usado pelo marcador do jogador. |
| `north` | não | Texto da bússola; por defeito `N`. |
| `markers` | não | Até 64 marcadores `waypoint`, `entity` ou `player`, com `x`/`z` normalizados entre 0 e 1. |

O modo `server_cells` continua disponível para compatibilidade, clientes sem o bridge ou mapas em que o servidor precisa decidir cada célula. Ele não é usado pelo exemplo actual porque fazer `top_y` e `get_block` para milhares de colunas no Lua custa mais e transforma a rede num transporte de imagem.

## Rede e clientes sem o loader

O servidor envia um catálogo S2C versionado com as câmeras disponíveis. O payload é opcional para clientes vanilla: quem não tem o bridge não interpreta a câmera e não recebe execução Lua client-side. A definição contém apenas campos fechados e números limitados.

Quando o mod é carregado, recarregado ou instalado com o servidor activo, o bridge republica o catálogo aos jogadores online. No JOIN, o catálogo é enviado juntamente com as hotkeys. No logout, o cliente limpa o catálogo e os recursos dinâmicos para não reutilizar uma câmera de outro mundo.

## Segurança e desempenho

O core só conhece DTOs, validação e serialização. Fabric e NeoForge absorvem `DynamicTexture`/`NativeImage`, `BlockColors`, `Heightmap`, `GuiGraphics` e as diferenças de mapping. Lua recebe o ID lógico e tabelas; não recebe classes Minecraft, chunks vivos, OpenGL, RenderSystem, GLFW, framebuffer ou nome de textura arbitrário.

A captura actual faz `resolution²` amostras por atualização. Por isso os limites existem e o exemplo mantém resolução baixa e `update_ticks` acima de zero. A amostragem consulta o estado client-side já disponível; este protótipo ainda deve ser observado em jogo para confirmar custo em mundos grandes, chunks remotos, troca de dimensão e diferentes escalas de interface. Não é correcto prometer custo constante ou equivalência visual com um minimapa dedicado.

A textura é partilhada apenas pelo ID lógico qualificado dentro do bridge. Cada câmera tem estado próprio; não existe um `TEXTURE_ID` global para todos os mods. O modo legado `client_topdown` usa uma chave interna implícita porque não possui catálogo, e não é a API recomendada para mods novos.

## Verificação

Os testes do core verificam contrato, round-trip, limite de ID, duplicados, declaração estática, registro Lua fora da compilação, permissões, capability, conflito manifesto+Lua e rollback de recarga. A compilação cobre os quatro bridges e os quatro mappings.

Os GameTests continuam server-side: comprovam carga, contrato, rede declarativa e callbacks, mas **não provam pixels, HUD, FPS, máscara circular, rotação, textura, autocomplete visual ou ausência de colisão gráfica**. A captura deve ser confirmada manualmente no cliente Fabric e NeoForge, incluindo movimento, troca de dimensão, zoom, mais de uma câmera e desempenho.

# Blocos dinâmicos e Lua

## O que já funciona

O bloco registrado pelo loader possui dois estados internos estáveis, `lua_variant` e `lua_luminance`, além de getters físicos controlados pelo runtime. O script Lua pode chamar:

```lua
ctx.server.set_block_variant("hello_lua:ruby_block", x, y, z, 0)
ctx.server.set_block_property("hello_lua:ruby_block", "hardness", 6)
```

`lua_variant` recebe valores de 0 a 15. O resource pack gerado cria um modelo para cada variante declarada em `render.variant_textures`, e o blockstate seleciona o modelo conforme o valor do estado. A troca do estado é uma operação normal de mundo e pode ser sincronizada pelo servidor para clientes conectados.

## Exemplo de bloco que muda de textura

```json
{
  "id": "ruby_block",
  "name": "Bloco de Rubi",
  "settings": {
    "hardness": 5,
    "resistance": 6
  },
  "render": {
    "variant_textures": {
      "0": {
        "source": "local",
        "path": "assets/example/textures/block/ruby.png"
      },
      "1": {
        "source": "remote",
        "url": "https://example.org/ruby-blue.png",
        "sha256": "<hash SHA-256 com 64 caracteres hexadecimais>"
      }
    }
  }
}
```

```lua
local ticks = 0

function on_tick(ctx)
    ticks = ticks + 1
    if ticks % 40 == 0 then
        local variant = math.floor(ticks / 40) % 2
        ctx.server.set_block_variant("example:ruby_block", 0, 100, 0, variant)
    end
end

return { on_tick = on_tick }
```

A textura não é trocada baixando um arquivo a cada tick. Ela é preparada uma vez no resource pack virtual, enquanto o Lua troca apenas o estado inteiro do bloco. Esse é o caminho adequado para animações simples, ciclos de fase, blocos ligados/desligados e variações visuais.

## Propriedades estáticas e dinâmicas

| Grupo | Estado no protótipo |
|---|---|
| ID, tipo de bloco, classe base e registries | Estáticos depois da inicialização. |
| Dureza, resistência, atrito e multiplicadores | Podem ser alterados pelos nomes explicitamente autorizados em `set_block_property`. |
| `lua_variant` e `lua_luminance` | Podem ser alterados por posição no mundo. |
| Textura, modelo e blockstate | Preparados no resource pack; a troca por tick usa estados, não download. |
| Colisão, outline e forma visual | Declarados no JSON; ainda não existe API Lua completa para alterar sua forma. |
| Sons, pistão, ferramenta, combustível e opacidade | Aplicados no registro inicial; não são mutados por Lua neste estágio. |
| Portas, escadas, slabs, entidades de bloco e redstone especializado | Exigem tipos de comportamento específicos e estão fora do bloco genérico atual. |

A razão dessa separação é que propriedades de `AbstractBlock.Settings` influenciam a construção e o comportamento interno da instância. Se o Lua puder alterar qualquer campo sem uma camada intermediária, o jogo pode ficar inconsistente entre servidor, cliente, chunks carregados e caches de colisão. O loader deve expor somente operações que tenham semântica de atualização bem definida.

## Limite atual

O teste executado no servidor confirmou registro do bloco, geração das duas texturas locais, alternância de `lua_variant` a cada 40 ticks e alteração da dureza. A confirmação visual final no cliente ainda requer iniciar um cliente de desenvolvimento e observar o bloco no mundo; a infraestrutura de resource pack virtual já está preparada para esse próximo teste.

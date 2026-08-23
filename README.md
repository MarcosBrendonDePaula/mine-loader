# Minecraft Lua Loader

[![CI](https://github.com/MarcosBrendonDePaula/mine-loader/actions/workflows/ci.yml/badge.svg)](https://github.com/MarcosBrendonDePaula/mine-loader/actions/workflows/ci.yml)

## Especificações

- [Especificação geral do projeto](docs/SPECIFICATION.md)
- [Guia de criação de mod](docs/GUIA_DO_MOD.md)
- [Formato de mods e manifesto JSON](docs/MOD_FORMAT_SPEC.md)
- [Blocos dinâmicos e Lua](docs/DYNAMIC_BLOCKS.md)
- [Especificação de segurança](docs/SECURITY_SPEC.md)
- [Catálogo de eventos](docs/EVENTS.md)
- [Interface customizada](docs/UI_SPEC.md)
- [Estudo: interface por HTML e CSS](docs/UI_HTML_DESIGN.md)
- [O que falta para um modder construir](docs/API_GAPS.md)
- [Roadmap de implementação](docs/ROADMAP.md)
- [Pipeline de geração por IA](docs/AI_PIPELINE.md)

Protótipo de um modloader declarativo para Minecraft Java 1.21.1. O núcleo Java descobre mods em `mods-lua`, lê `mod.json`, registra blocos declarativos, monta um resource pack virtual e executa a lógica do mod em LuaJ.

O núcleo não conhece plataforma: existem dois adaptadores, **Fabric** e **NeoForge**, e o mesmo mod em Lua roda nos dois sem mudança. O Fabric é o completo; o NeoForge cobre o caminho central e recusa com mensagem clara o que ainda não implementa.

## Requisitos

O projeto usa Java 21. No Windows, o Gradle Wrapper já está disponível em `gradlew.bat`; no Linux e macOS, use `./gradlew`. Não é necessário instalar Gradle separadamente.

## Build

Linux/macOS:

```bash
./gradlew build              # nucleo, testes e adaptador Fabric
./gradlew :neoforge:build    # adaptador NeoForge
```

Windows PowerShell:

```powershell
./gradlew.bat build
```

## Rodar nas duas plataformas

```bash
./gradlew runClient              # Fabric
./gradlew :neoforge:runClient    # NeoForge
```

Cada run tem o proprio diretorio de jogo, e portanto a propria pasta `mods-lua`. Para nao manter
duas copias dos mesmos mods:

```bash
./gradlew :neoforge:linkModsLua
```

Isso aponta `neoforge/run/mods-lua` para `run/mods-lua`, e um mod editado passa a valer nas duas
plataformas de uma vez -- que e justamente o que se quer verificar.

## Executar servidor de desenvolvimento

```bash
./gradlew runServer
```

No Windows:

```powershell
./gradlew.bat runServer
```

O servidor procura mods em `run/mods-lua`. O exemplo pode ser copiado assim:

```text
run/mods-lua/hello_lua/mod.json
run/mods-lua/hello_lua/main.lua
run/mods-lua/hello_lua/assets/hello_lua/textures/block/ruby_block.png
run/mods-lua/hello_lua/assets/hello_lua/textures/block/ruby_block_alt.png
```

## Exemplos

A pasta `examples/` traz mods prontos para copiar para `run/mods-lua`. Cada um exercita uma parte
diferente do loader.

| Exemplo | O que demonstra |
|---|---|
| `hello_lua` | Bloco declarativo com variantes, textura local e propriedades dinâmicas |
| `crystal_world` | Estruturas, dados por bloco e aba criativa |
| `github_mod` | Mod remoto: manifesto e scripts baixados por URL |
| `guilda` | Estado persistido, comandos próprios e menu de itens |
| `loja` | Inventário do jogador: dar, tirar e contar itens |
| `painel` | Tela desenhada e HUD |
| `catalogo` | Sobreposição no inventário, grade rolável, busca, receitas, drops e processos |
| `processos_vanilla` | Declara as interações que o jogo executa em código: tosquia, ordenha, balde de peixe |
| `inspetor` | Lê e abastece o inventário de qualquer bloco, inclusive de mods de terceiros |

O `catalogo` é o mais completo: usa quase toda a camada de interface e as consultas de conteúdo, e
serve como referência de como as peças se combinam.

## Manifesto mínimo

```json
{
  "schema": 1,
  "id": "hello_lua",
  "name": "Hello Lua",
  "version": "0.1.0",
  "entrypoint": "main.lua",
  "permissions": ["chat.send", "world.write"],
  "events": { "tick": "on_tick" },
  "blocks": [{
    "id": "ruby_block",
    "name": "Bloco de Rubi",
    "material": { "map_color": "red", "sound": "stone" },
    "settings": { "hardness": 5, "resistance": 6, "requires_tool": true },
    "render": {
      "texture": {
        "source": "local",
        "path": "assets/hello_lua/textures/block/ruby_block.png"
      },
      "variant_textures": {
        "0": { "source": "local", "path": "assets/hello_lua/textures/block/ruby_block.png" },
        "1": { "source": "remote", "url": "https://example.org/ruby_alt.png", "sha256": "<64 hex characters>" }
      }
    }
  }]
}
```

## Lua

O script retorna uma tabela com funções nomeadas no campo `events`:

```lua
local ticks = 0

function on_tick(ctx)
    ticks = ticks + 1
    if ticks % 40 == 0 then
        local variant = math.floor(ticks / 40) % 2
        ctx.server.set_block_variant("hello_lua:ruby_block", 0, 100, 0, variant)
        ctx.server.set_block_property("hello_lua:ruby_block", "hardness", 5 + variant)
    end
end

return { on_tick = on_tick }
```

A API inicial expõe `ctx.log.info`, `ctx.log.warn`, `ctx.server.broadcast`, `ctx.server.set_block_variant`, `ctx.server.set_block_property`, `ctx.player.name`, `ctx.player.uuid` e `ctx.player.send_message`, sempre respeitando as permissões do manifesto.

## Comandos

Com permissão de operador, o servidor oferece `/lua list`, `/lua blocks`, `/lua reload` e `/lua reload <mod_id>`. A recarga substitui o ambiente Lua do mod; os blocos já registrados não são removidos nem recriados.

## Texturas e recursos remotos

Texturas locais e remotas são convertidas para PNG no resource pack gerado em `run/lua-loader/generated-pack`. Downloads usam HTTPS, timeout, limite de tamanho, validação de imagem e SHA-256 opcional. O cache fica em `run/lua-loader/cache`.

A mudança de textura durante o jogo deve usar variantes de blockstate. O Lua muda `lua_variant`, e o Minecraft escolhe o modelo correspondente. Não é necessário disparar um reload de recurso a cada tick.

## Limitações conscientes

O MVP é direcionado a Minecraft 1.21.1 e registra inicialmente blocos genéricos. As propriedades do builder são lidas no registro inicial; apenas propriedades dinâmicas implementadas pelo `DeclarativeBlock` podem mudar depois. Não existe ainda hot-unload de classes Java, acesso irrestrito à JVM, compatibilidade automática com mods Fabric/NeoForge, integração completa de todas as subclasses especiais de bloco ou gerador de mods por prompt conectado a um modelo de IA.

O sistema de IA será adicionado sobre este contrato: a IA produzirá um pacote JSON/Lua/recursos, e o loader continuará sendo responsável por validação, permissões, testes e instalação.

## Testes

```bash
./gradlew test
./gradlew build
```

O teste de integração do servidor deve mostrar no log o registro de `hello_lua:ruby_block`, o carregamento do script, a montagem das variantes `ruby_block_v0.png` e `ruby_block_v1.png` e mensagens periódicas de alternância de variante e dureza.

# Minecraft Lua Loader

[![CI](https://github.com/MarcosBrendonDePaula/mine-loader/actions/workflows/ci.yml/badge.svg)](https://github.com/MarcosBrendonDePaula/mine-loader/actions/workflows/ci.yml)

## Especificações

- [Especificação geral do projeto](docs/SPECIFICATION.md)
- [Guia de criação de mod](docs/GUIA_DO_MOD.md)
- [Formato de mods e manifesto JSON](docs/MOD_FORMAT_SPEC.md)
- [Exemplos de requirements de capabilities e domínios](docs/examples/README.md)
- [Blocos dinâmicos e Lua](docs/DYNAMIC_BLOCKS.md)
- [Especificação de segurança](docs/SECURITY_SPEC.md)
- [Catálogo de eventos](docs/EVENTS.md)
- [Interface customizada](docs/UI_SPEC.md)
- [Estudo: interface por HTML e CSS](docs/UI_HTML_DESIGN.md)
- [Compatibilidade por runtime e versão](docs/COMPATIBILIDADE.md)
- [Runtimes, versões e matriz de testes](docs/RUNTIMES.md)
- [API estável para mods declarativos](docs/API_ESTAVEL.md)
- [O que falta para um modder construir](docs/API_GAPS.md)
- [Checklist de recursos e progressão](docs/CHECKLIST_MODLOADER.md)
- [Instalar mods por link](docs/INSTALACAO.md)
- [Progresso — o que foi feito e o que falta](docs/PROGRESSO.md)
- [Roadmap de implementação](docs/ROADMAP.md)
- [Pipeline de geração por IA](docs/AI_PIPELINE.md)

Protótipo de um modloader declarativo para Minecraft Java. O núcleo Java descobre mods em `mods-lua`, lê `mod.json`, registra blocos declarativos, monta um resource pack virtual e executa a lógica do mod em LuaJ. A branch de prova mantém bridges Fabric e NeoForge para Minecraft 1.21.1 e 1.21.4 no mesmo projeto.

O núcleo não conhece plataforma: existem adaptadores **Fabric** e **NeoForge**, separados por versão em `runtimes/<plataforma>/<versão>`. Os mesmos manifestos e scripts Lua são carregados pelos quatro runtimes. O manifesto pode exigir capabilities e versões de domínios do contrato, sem mencionar a versão do Minecraft; veja [`docs/examples/README.md`](docs/examples/README.md). A 1.21.1 é a baseline; a 1.21.4 já passa compilação e GameTests, mas continua experimental nas capabilities visuais e em alguns formatos de recurso. A matriz honesta está em [`docs/COMPATIBILIDADE.md`](docs/COMPATIBILIDADE.md).

Isso não é afirmação de quem escreveu o adaptador: os GameTests rodam nas quatro combinações no CI, e o mod `autoteste` exercita as APIs contra o jogo de verdade com o mesmo script em cada plataforma — uma divergência reporta FALHOU onde outra reporta OK.

## Requisitos

O projeto usa Java 21. No Windows, o Gradle Wrapper já está disponível em `gradlew.bat`; no Linux e macOS, use `./gradlew`. Não é necessário instalar Gradle separadamente.

## Build

Linux/macOS:

```bash
./gradlew compileAllRuntimes   # core + Fabric/NeoForge 1.21.1 e 1.21.4
./gradlew testAllRuntimes      # suíte JUnit do core e tarefas test dos runtimes
./gradlew gameTestAllRuntimes  # 22 GameTests em cada combinação
./gradlew checkAllRuntimes     # verificação completa, incluindo GameTests
```

Windows PowerShell:

```powershell
./gradlew.bat build
```

## Rodar nas duas plataformas

```bash
./gradlew :runtimes:fabric:1.21.1:runClient
./gradlew :runtimes:fabric:1.21.4:runClient
./gradlew :runtimes:neoforge:1.21.1:runClient
./gradlew :runtimes:neoforge:1.21.4:runClient
```

Cada run tem o proprio diretorio de jogo, e portanto a propria pasta `mods-lua`. Para nao manter
duas copias dos mesmos mods:

```bash
./gradlew :runtimes:neoforge:1.21.4:linkModsLua
```

Isso aponta a pasta de mods Lua do runtime NeoForge para a pasta partilhada do projeto. Para testar
uma versão específica, use a tarefa `linkModsLua` desse runtime; os GameTests, por sua vez,
sincronizam automaticamente `examples/` para cada versão.

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
| `crystal_world` | Estruturas, dados por bloco, aba criativa e uma **espécie declarada** com forma, pele, saque e comportamento próprios |
| `github_mod` | Mod remoto: manifesto e scripts baixados por URL |
| `guilda` | Estado persistido, comandos próprios e menu de itens |
| `loja` | Inventário do jogador: dar, tirar e contar itens |
| `painel` | Tela desenhada e HUD |
| `catalogo` | Sobreposição no inventário, grade rolável, busca, receitas, drops e processos |
| `processos_vanilla` | Declara as interações que o jogo executa em código: tosquia, ordenha, balde de peixe |
| `inspetor` | Lê e abastece o inventário de qualquer bloco, inclusive de mods de terceiros |
| `autoteste` | Exercita as APIs dentro do jogo e reporta OK ou FALHOU por verificação |
| `ferraria` | Ferramentas e armaduras declaradas no manifesto, sem uma linha de Java |
| `gerenciador` | Lista os mods do loader numa tela e instala novos por link |
| `bestiario` | Espécie declarada por script: gera variantes num laço e herda a de outro mod |
| `logistica` | Rede de canos que encontra e entrega itens — porte da ideia central do Logistic Pipes |

O `catalogo` é o mais completo: usa quase toda a camada de interface e as consultas de conteúdo, e
serve como referência de como as peças se combinam.

O `logistica` existe por outro motivo: é o **primeiro mod migrado** para este loader, e um teste de
esforço. Ele porta a ideia central de um mod
de verdade — o [Logistic Pipes](https://github.com/rs485/logisticspipes) — e serve para descobrir o
que falta na API antes que quem escreve um mod descubra. As quatro lacunas que ele encontrou estão
em [`API_GAPS.md`](docs/API_GAPS.md).

A versão que vive aqui usa texturas próprias e é MIT como o resto do repositório. Há também um
[**porte autônomo**](https://github.com/MarcosBrendonDePaula/logistic-pipes-lua), que reusa a arte do
mod original e por isso é MMPL — a licença do Logistic Pipes exige que qualquer derivado a mantenha,
e é justamente por isso que ele mora fora daqui.

O `gerenciador` existe porque a lista de mods do Fabric e do NeoForge não enxerga os mods deste
loader — para elas há um mod só, o próprio loader. Quem joga precisa de algum lugar onde ver o que
está instalado e acrescentar um mod novo, e esse lugar é um mod em Lua como qualquer outro.

## Mods no menu principal

O botão **Mods Lua**, no canto do menu principal, abre uma lista sem precisar entrar num mundo: o
que está instalado, com filtro e páginas, um botão para ligar ou desligar cada um, e um campo para
instalar por link.

Ela lê o **catálogo**, e não a lista de mods carregados — um mod desligado é pulado na carga, e
mostrar só o que carregou nunca deixaria alguém reativá-lo. Um mod com manifesto quebrado também
aparece, com o motivo, em vez de sumir como se nunca tivesse sido copiado.

**Tudo ali vale a partir do próximo início do jogo**, e a tela diz isso em todas as telas. Os
registros do Minecraft congelam na inicialização: ligar um mod agora não faz aparecer o bloco que
ele registraria, e desligar não desfaz o que ele já registrou.

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

O campo opcional `"side"` diz se quem entra no servidor precisa ter o mod instalado também:
`"both"` para quem registra bloco ou item, `"server"` para o resto. Ausente, é deduzido do próprio
manifesto — veja [`docs/MOD_FORMAT_SPEC.md`](docs/MOD_FORMAT_SPEC.md).

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

A API inicial e a expansão atual expõem `ctx.log.info`, `ctx.log.warn`, `ctx.server.broadcast`, `ctx.server.set_block_variant`, `ctx.server.set_block_property`, `ctx.server.redstone_signal`, `ctx.server.block_state`, `ctx.server.set_block_state`, `ctx.server.game_rule`, `ctx.server.set_game_rule`, `ctx.server.difficulty`, `ctx.server.set_difficulty`, hora, clima, `ctx.player.name`, `ctx.player.uuid`, `ctx.player.send_message` e `ctx.player.data`, sempre respeitando as permissões do manifesto. A especificação completa, os limites e as próximas prioridades estão em [`docs/API_ESTAVEL.md`](docs/API_ESTAVEL.md).

## Comandos

Com permissão de operador, o servidor oferece `/lua list`, `/lua blocks`, `/lua reload` e `/lua reload <mod_id>`. A recarga substitui o ambiente Lua do mod; os blocos já registrados não são removidos nem recriados.

## Texturas e recursos remotos

Texturas locais e remotas são convertidas para PNG no resource pack gerado em `run/lua-loader/generated-pack`. Downloads usam HTTPS, timeout, limite de tamanho, validação de imagem e SHA-256 opcional. O cache fica em `run/lua-loader/cache`.

A mudança de textura durante o jogo deve usar variantes de blockstate. O Lua muda `lua_variant`, e o Minecraft escolhe o modelo correspondente. Não é necessário disparar um reload de recurso a cada tick.

## Limitações conscientes

A branch de prova tem baseline em Minecraft 1.21.1 e um porte experimental para 1.21.4. As propriedades do builder são lidas no registro inicial; apenas propriedades dinâmicas implementadas pelo `DeclarativeBlock` podem mudar depois. No porte 1.21.4, OBJ está desativado, modelos/skins customizados de entidades usam renderer vanilla, cores customizadas de ovos têm fallback, reparação de ferramentas/armaduras usa tags padrão, e partículas NeoForge estão pendentes. Não existe hot-unload de classes Java, acesso irrestrito à JVM, compatibilidade automática com mods Fabric/NeoForge ou gerador de mods por prompt conectado a um modelo de IA.

O sistema de IA será adicionado sobre este contrato: a IA produzirá um pacote JSON/Lua/recursos, e o loader continuará sendo responsável por validação, permissões, testes e instalação.

## Testes

```bash
./gradlew :core:test
./gradlew testAllRuntimes
./gradlew gameTestAllRuntimes
./gradlew checkAllRuntimes
```

Os GameTests rodam nas quatro combinações e o CI executa a matriz em todo push. Cada runtime carrega os mesmos exemplos e precisa mostrar `All 22 required tests passed :)`. A compilação client
não substitui esses testes, e os GameTests não substituem uma inspeção visual; as limitações estão
separadas na matriz.

Os testes do nucleo rodam contra um dublê, e por isso nao alcancam o que so aparece com o jogo
de verdade: um registro com mil e trezentos itens, uma tabela de loot com entradas condicionais, o
inventario de um bloco de outro mod. Para isso ha o servidor dirigivel e o mod `autoteste`:

```bash
tools/servidor-dirigivel.sh iniciar
tools/servidor-dirigivel.sh esperar
tools/servidor-dirigivel.sh cmd "mod autoteste"
tools/servidor-dirigivel.sh log 20
tools/servidor-dirigivel.sh parar
```

O servidor le comandos do console, e o script redireciona esse console para um arquivo -- entao
verificar uma mudanca de ponta a ponta deixa de exigir alguem no jogo no momento certo. Telas, HUD e
sobreposicao continuam fora: sem cliente, nao ha o que desenhar.

O teste de integração do servidor deve mostrar no log o registro de `hello_lua:ruby_block`, o carregamento do script, a montagem das variantes `ruby_block_v0.png` e `ruby_block_v1.png` e mensagens periódicas de alternância de variante e dureza.

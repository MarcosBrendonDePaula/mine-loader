# MineLoader

[![CI](https://github.com/MarcosBrendonDePaula/mine-loader/actions/workflows/ci.yml/badge.svg)](https://github.com/MarcosBrendonDePaula/mine-loader/actions/workflows/ci.yml)

> **MineLoader** é um modloader declarativo para Minecraft Java: o modder escreve um manifesto JSON, Lua e recursos; o loader valida o pacote e as permissões, regista conteúdo e executa a lógica através de uma API própria e estável.

O projecto testa a mesma ideia em quatro combinações: **Fabric 1.21.1, Fabric 1.21.4, NeoForge 1.21.1 e NeoForge 1.21.4**. O core não importa Minecraft, Fabric ou NeoForge. Cada runtime possui uma bridge que absorve as diferenças da plataforma, enquanto o mod depende apenas do contrato do MineLoader.

## Estado actual

| Área | Estado |
|---|---|
| Core agnóstico | Implementado e testado |
| Fabric 1.21.1 | Baseline mantida |
| Fabric 1.21.4 | Experimental, com limitações visuais documentadas |
| NeoForge 1.21.1 | Implementado e testado |
| NeoForge 1.21.4 | Experimental, com limitações visuais documentadas |
| GameTests | 22/22 em cada combinação mantida |
| `mod.require()` | Bibliotecas entre mods, com resolução sob demanda e detecção de ciclos |
| `requires.domains` / `requires.capabilities` | Negociação de contrato no manifesto |
| Shaders client-side | Ainda não fazem parte da API estável |

A matriz completa, incluindo OBJ, renderers de entidades, partículas, receitas e outras degradações, está em [docs/COMPATIBILIDADE.md](docs/COMPATIBILIDADE.md). **Compilar não é o mesmo que ter paridade visual**: os GameTests são principalmente server-side e não provam pixels, iluminação, telas client-side ou shaders.

## Documentação

O índice em [docs/README.md](docs/README.md) organiza os documentos por público e por finalidade.

| Quero... | Começo por... |
|---|---|
| Entender a proposta | [Especificação geral](docs/SPECIFICATION.md) |
| Criar um mod | [Guia do mod](docs/GUIA_DO_MOD.md) |
| Escrever `mod.json` | [Formato do manifesto](docs/MOD_FORMAT_SPEC.md) |
| Consultar funções estáveis | [API estável](docs/API_ESTAVEL.md) |
| Declarar capabilities e domínios | [Exemplos de requirements](docs/examples/README.md) |
| Ver eventos disponíveis | [Catálogo de eventos](docs/EVENTS.md) |
| Saber o que funciona por versão | [Matriz de compatibilidade](docs/COMPATIBILIDADE.md) |
| Instalar mods por link | [Instalação](docs/INSTALACAO.md) |
| Trabalhar no loader | [Arquitectura](docs/ARCHITECTURE.md) e [runtimes](docs/RUNTIMES.md) |
| Ver prioridades futuras | [API gaps](docs/API_GAPS.md) e [roadmap](docs/ROADMAP.md) |

## Começar rapidamente

### Requisitos

É necessário **Java 21**. O repositório já inclui o Gradle Wrapper; não é preciso instalar Gradle separadamente.

No Linux e macOS:

```bash
./gradlew compileAllRuntimes
./gradlew testAllRuntimes
./gradlew gameTestAllRuntimes
```

No Windows PowerShell:

```powershell
./gradlew.bat build
```

### Criar o primeiro mod

Um mod mínimo pode ter apenas estes dois ficheiros:

```text
run/mods-lua/hello_lua/
├── mod.json
└── main.lua
```

`mod.json`:

```json
{
  "schema": 1,
  "id": "hello_lua",
  "name": "Hello Lua",
  "version": "1.0.0",
  "entrypoint": "main.lua",
  "permissions": ["chat.send"],
  "events": {
    "server_started": "on_server_started"
  }
}
```

`main.lua`:

```lua
function on_server_started(ctx)
    ctx.server.broadcast("Hello Lua carregado")
end

return {
    on_server_started = on_server_started
}
```

Para executar um servidor de desenvolvimento:

```bash
./gradlew runServer
```

O servidor lê mods de `run/mods-lua`. Para testar uma versão específica, use a tarefa `runClient` ou `runServer` do runtime correspondente. Cada runtime tem o seu directório de jogo; a tarefa `linkModsLua` evita manter cópias dos mesmos mods.

## Manifesto e compatibilidade

O manifesto declara conteúdo, permissões, eventos e dependências. Dependências entre mods ficam em `dependencies`; dependências da API do loader ficam em `requires`.

```json
{
  "schema": 1,
  "id": "meu_mod",
  "name": "Meu Mod",
  "version": "1.0.0",
  "entrypoint": "main.lua",
  "dependencies": {
    "ui_lib": "2.0.0"
  },
  "requires": {
    "domains": {
      "world": "1.0.0",
      "player": "1.0.0"
    },
    "capabilities": {
      "world.block_state.read": "1.0.0"
    }
  }
}
```

`dependencies` permite usar `mod.require("ui_lib")` e controla a ordem de carga. `requires` apenas verifica se o runtime oferece o contrato; não instala código nem substitui uma dependency entre mods. A resolução de bibliotecas é feita sob demanda quando necessário e recusa ciclos com a cadeia completa, sem recursão infinita nem scripts parciais.

As regras completas, campos aceites e limites estão em [docs/MOD_FORMAT_SPEC.md](docs/MOD_FORMAT_SPEC.md). O schema oficial está em [spec/mod.schema.json](spec/mod.schema.json).

## API pública

O Lua recebe tabelas e escalares simples. Classes Java, referências vivas do Minecraft, OpenGL, JVM e APIs internas de Fabric/NeoForge não atravessam a fronteira.

| Domínio | APIs já disponíveis |
|---|---|
| Mundo | `block_state`, `set_block_state`, `game_rule`, `set_game_rule`, `difficulty`, `set_difficulty`, hora, clima, bioma, luz e redstone |
| Jogador | nome, UUID, mensagens, mira, dados persistentes, inventário e operações declaradas |
| Conteúdo | blocos, itens, entidades, spawn eggs, tags, loot, estruturas, processos e herança declarativa |
| Eventos | ciclo de vida, ticks, jogador, blocos, itens, entidades, cliente e menus |
| Interface | menus, telas, HUD, sobreposições e protocolo fechado servidor-cliente |
| Bibliotecas | `mod.require()` para exports Lua de outro mod declarado em `dependencies` |
| Contrato | `requires.domains` e `requires.capabilities` com versões do MineLoader |

A referência normativa é [docs/API_ESTAVEL.md](docs/API_ESTAVEL.md). Para controles de mundo e o que ainda falta, consulte [docs/CONTROLES_MUNDO.md](docs/CONTROLES_MUNDO.md) e [docs/API_GAPS.md](docs/API_GAPS.md).

## Exemplos incluídos

A pasta [examples/](examples/) contém mods usados pela bateria de GameTests e prontos para copiar para `run/mods-lua`.

| Exemplo | Demonstra |
|---|---|
| `hello_lua` | Bloco declarativo, variantes, textura e propriedades dinâmicas |
| `crystal_world` | Estruturas, dados por bloco, aba criativa e entidades declaradas |
| `github_mod` | Manifesto e scripts remotos com validação |
| `guilda` | Estado persistente, comandos e menus |
| `loja` | Inventário do jogador |
| `painel` | Tela desenhada e HUD |
| `catalogo` | Consultas de conteúdo, receitas, drops e processos |
| `processos_vanilla` | Interacções executadas pelo jogo |
| `inspetor` | Inventário de blocos, inclusive de terceiros |
| `autoteste` | Verificações dentro do jogo e relatório OK/FALHOU |
| `ferraria` | Ferramentas e armaduras declarativas |
| `gerenciador` | Catálogo, activação e instalação por link |
| `bestiario` | Entidades declaradas, variantes e herança |
| `logistica` | Rede de canos e entrega de itens |

Os exemplos documentais de capabilities, domínios e bibliotecas entre mods ficam separados em [docs/examples/](docs/examples/), para não serem carregados pela bateria de GameTests.

## Testar a matriz

Os comandos agregados cobrem o core e os quatro runtimes:

```bash
./gradlew :core:test
./gradlew compileAllRuntimes
./gradlew testAllRuntimes
./gradlew gameTestAllRuntimes
./gradlew checkAllRuntimes
```

Para executar um cliente específico:

```bash
./gradlew :runtimes:fabric:1.21.1:runClient
./gradlew :runtimes:fabric:1.21.4:runClient
./gradlew :runtimes:neoforge:1.21.1:runClient
./gradlew :runtimes:neoforge:1.21.4:runClient
```

Os GameTests verificam contratos de servidor, registo, propriedades, inventários, persistência, automação, eventos, fila de ticks, redstone, tags, ovos e herança. A validação visual precisa de uma etapa separada no cliente.

## Arquitectura

```text
mod.json + Lua + recursos
            │
            ▼
     core / contrato estável
            │
     ┌──────┴──────┐
     ▼             ▼
Fabric bridge   NeoForge bridge
 1.21.1/1.21.4   1.21.1/1.21.4
            │
            ▼
        Minecraft
```

O core contém manifesto, validação, dependências, runtime Lua, permissões, estado, protocolos e contratos agnósticos. Cada bridge traduz esses contratos para a API local da versão, sem alterar o código dos mods.

## Limitações conscientes

A 1.21.4 continua experimental nas áreas visuais e em alguns formatos de recursos. OBJ está desactivado, modelos e skins customizados de entidades usam fallback vanilla, cores customizadas de spawn eggs e reparação de ferramentas/armaduras estão degradadas, partículas NeoForge 1.21.4 continuam pendentes e receitas da versão ainda precisam de revisão.

Shaders e efeitos de pós-processamento ainda não são uma capability estável. Arquivos GLSL podem existir como recursos, mas o MineLoader ainda não fornece um pipeline client-side comum para activar shaders, gerir framebuffers, uniforms, reload e pontos de aplicação. Não há compatibilidade automática com Iris, Sodium, Canvas ou outros mods de renderização.

O projecto também não pretende ser um clone do KubeJS nem expor toda a JVM. A proposta é uma camada menor, fechada e versionada para mods declarativos e imutáveis entre versões do Minecraft.

## Próximas prioridades

A ordem actual de maior valor é completar drops e eventos de quebra com contexto íntegro, explosão e raio, slots/armadura/efeitos do jogador, fluidos e energia, e depois waypoints, teleporte entre dimensões e worldgen limitado. A API client-side de shaders só deve entrar como um domínio próprio, depois de uma bridge visual comprovada nas quatro combinações.

O backlog vivo está em [docs/API_GAPS.md](docs/API_GAPS.md), o roadmap em [docs/ROADMAP.md](docs/ROADMAP.md) e o diário histórico em [docs/PROGRESSO.md](docs/PROGRESSO.md).

## Licença

O repositório é distribuído sob a licença [MIT](LICENSE). Arte ou código de projectos externos mantidos fora deste repositório podem ter licenças diferentes; consulte a documentação do exemplo correspondente.

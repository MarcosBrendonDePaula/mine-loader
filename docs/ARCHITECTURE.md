# Minecraft Lua Loader — arquitetura do MVP

## Objetivo

O projeto será um runtime de mods para Minecraft Java em que cada mod possui um manifesto JSON e um script Lua. O Java fará a ponte com o jogo, controlará o ciclo de vida e exporá uma API pequena, estável e segura para os scripts.

O MVP não tentará descarregar classes Java arbitrárias nem substituir imediatamente os registries vanilla de blocos e itens. O objetivo inicial é validar o ciclo completo: descobrir um mod, validar o manifesto, iniciar o estado Lua, receber eventos do jogo, executar callbacks e recarregar o script.

## Camadas e independência de plataforma

O projeto é dividido em dois módulos Gradle com uma fronteira verificada pelo compilador.

| Módulo | Conteúdo | Minecraft no classpath |
|---|---|---|
| `core` | manifesto, validação, runtime Lua, montagem de resource pack, cache remoto e as interfaces de plataforma | **Não** |
| raiz | adaptador Fabric: registro de blocos, resource pack virtual, mixins e entrypoint | Sim |

O `core/build.gradle` deliberadamente não declara dependência de Minecraft nem de Fabric. Um `import net.minecraft.*` dentro do núcleo não compila, então a separação não depende de disciplina de quem escreve o código.

### Contrato de plataforma

O núcleo nunca chama o jogo diretamente. Toda operação atravessa `dev.lualoader.platform`:

| Tipo | Papel |
|---|---|
| `GameBridge` | Operações que o núcleo solicita ao jogo: `broadcast`, `setBlockVariant`, `setBlockProperty`, `setBlockLuminance`. |
| `PlayerHandle` | Referência neutra a um jogador, válida apenas durante o callback que a recebeu. |
| `BridgeException` | Falha de plataforma traduzida para um tipo que o núcleo entende. |

O adaptador Fabric implementa esse contrato em `FabricGameBridge` e `FabricPlayerHandle`. O `LuaRuntime` recebe a bridge por `attach()` e usa `GameBridge.DETACHED` quando nenhuma plataforma está conectada, o que permite validar manifestos e scripts sem iniciar o jogo.

### Controle de mundo

O script recebe primitivas para ler e escrever blocos arbitrarios, do jogo ou de qualquer mod. Antes delas, o Lua so conseguia alterar blocos declarativos que ja existiam na posicao, o que impedia construir qualquer coisa.

| API Lua | Permissao | Efeito |
|---|---|---|
| `ctx.server.get_block(x, y, z)` | `world.read` | Devolve o identificador do bloco, ou `minecraft:air`. |
| `ctx.server.set_block(id, x, y, z)` | `world.write` | Substitui o bloco na posicao. |
| `ctx.server.fill(id, x1, y1, z1, x2, y2, z2)` | `world.write` | Preenche a regiao e devolve quantos blocos mudaram. |

O `fill` existe como operacao propria porque preencher bloco a bloco a partir do Lua seria ordens de grandeza mais lento; o adaptador reutiliza uma posicao mutavel e ignora blocos que ja estao no estado desejado.

Os limites ficam no nucleo, nao no adaptador, para valerem em qualquer plataforma e serem testaveis sem abrir o jogo: um `fill` aceita no maximo 32.768 blocos, o equivalente a um cubo de 32 de lado, e coordenadas fora de 30.000.000 sao recusadas. Sem esses limites, um erro de script pediria bilhoes de blocos e travaria a thread do servidor.

### Acrescentar outra plataforma

Um novo alvo (NeoForge, por exemplo) não altera o núcleo. Ele precisa de um módulo próprio que forneça quatro coisas: o entrypoint do loader, a implementação de `GameBridge`, a implementação de `PlayerHandle` e o registro de conteúdo declarativo equivalente ao `BlockRegistrar`, além da geração do resource pack virtual na API daquela plataforma. Manifesto, sandbox Lua, permissões e validação são compartilhados sem duplicação.

O trabalho real de um segundo adaptador está no registro de blocos e no resource pack, que são específicos de cada plataforma por natureza. O ganho da camada é que esse esforço fica confinado ao adaptador.

## Modelo de execução

O loader terá quatro partes. O `ModScanner` procura diretórios em `mods-lua/`; o `ManifestValidator` valida cada `mod.json`; o `LuaRuntime` cria um ambiente isolado por mod; e o `MinecraftBridge` converte eventos e operações permitidas em chamadas da API do jogo.

Cada mod terá seu próprio ambiente Lua e não receberá acesso direto a classes Java, reflexão, sistema de arquivos, rede, processos ou carregadores de classes. A comunicação será feita somente por objetos de contexto fornecidos pelo loader.

## Ciclo de vida

O ciclo de vida mínimo será:

`DISCOVERED → VALIDATED → LOADED → ACTIVE → RELOADING → ACTIVE`

Em caso de erro durante a recarga, o loader descarta o novo ambiente e mantém o snapshot anterior ativo. Durante a substituição, os callbacks do mod serão pausados para impedir que duas versões do mesmo script executem ao mesmo tempo.

## Formato do diretório

```text
mods-lua/
└── hello_lua/
    ├── mod.json
    └── main.lua
```

O diretório do mod precisa ter o mesmo nome do campo `id` para facilitar descoberta e diagnóstico. O script definido em `entrypoint` deve estar dentro do diretório do mod.

## Contrato do manifesto

Os campos obrigatórios são `schema`, `id`, `name`, `version` e `entrypoint`. O campo `events` associa nomes de eventos a funções globais Lua. O campo `permissions` declara as capacidades solicitadas pelo mod; o loader aprovará apenas permissões conhecidas.

Exemplo:

```json
{
  "schema": 1,
  "id": "hello_lua",
  "name": "Hello Lua",
  "version": "0.1.0",
  "entrypoint": "main.lua",
  "permissions": ["chat.send", "player.read"],
  "events": {
    "server_started": "on_server_started",
    "player_joined": "on_player_joined"
  }
}
```

## API Lua inicial

O ambiente oferecerá um objeto global `mod` com operações limitadas:

```lua
mod.log.info("mensagem")
mod.log.warn("aviso")
mod.on("server_started", function(ctx)
    ctx.log.info("servidor iniciado")
end)
```

Para simplificar a primeira implementação, o loader aceitará também callbacks globais definidos no manifesto. O modelo final preferido será `mod.on`, porque permite registrar callbacks diretamente no script e deixa o manifesto reservado para metadados e permissões.

A API prevista para o primeiro ciclo é:

| API | Permissão | Descrição |
|---|---|---|
| `mod.log.info(text)` | Nenhuma | Escreve uma mensagem identificada pelo mod no log do loader. |
| `mod.log.warn(text)` | Nenhuma | Escreve um aviso. |
| `mod.on(event, callback)` | Nenhuma | Registra um callback para um evento permitido. |
| `ctx.server.broadcast(text)` | `chat.send` | Envia mensagem pública pelo servidor. |
| `ctx.player.name` | `player.read` | Lê o nome do jogador associado ao evento. |
| `ctx.player.send_message(text)` | `chat.send` | Envia mensagem para um jogador. |
| `ctx.time` | Nenhuma | Fornece o tick ou timestamp do evento. |

## Eventos do MVP

O primeiro conjunto será pequeno para manter a ponte testável: `loader_ready`, `server_started`, `server_stopped`, `player_joined` e `tick`. O evento `tick` não será habilitado por padrão em mods que não o declararem, evitando custo desnecessário.

Eventos são dados pelo loader e não devem expor referências de longa duração a objetos internos do Minecraft. Contextos podem ser invalidados depois do callback para reduzir vazamentos e uso acidental fora da thread correta.

## Recarga

O comando planejado será `/lua reload [mod_id]`. A operação lerá novamente o manifesto e o script, criará um novo ambiente Lua, registrará os callbacks no novo ambiente e só substituirá o ambiente antigo depois que todas as etapas forem concluídas.

A recarga deve ser executada no thread apropriado do servidor. Leitura de arquivos e validação podem ocorrer fora da thread do jogo, mas chamadas à ponte Minecraft serão agendadas no executor do servidor.

## Segurança

Lua não é considerado seguro apenas por ser uma linguagem pequena. O MVP removerá bibliotecas de carregamento de arquivos, pacotes, debug, `os`, `io`, `coroutine` quando não forem necessárias e qualquer ponte automática para Java. Limites de instruções e tempo de execução serão adicionados antes de permitir mods de terceiros não confiáveis.

A sandbox não será apresentada como isolamento de segurança perfeito. O loader deve tratar mods como código potencialmente confiável apenas quando instalados pelo administrador do servidor.

## Fora do escopo inicial

Blocos, itens e entidades Java genuinamente novos; execução de bytecode; compatibilidade automática com mods Fabric ou NeoForge; acesso a banco de dados; rede arbitrária; edição de arquivos do mundo; e hot-unload de classes Java ficam fora do MVP.

## Direção futura

Depois de validar o runtime, será possível adicionar uma camada de dados JSON para receitas, loot tables, tags e configurações. Em seguida poderemos criar uma API de conteúdo virtual, com IDs estáveis controlados pelo loader, antes de investigar integração profunda com registries vanilla.

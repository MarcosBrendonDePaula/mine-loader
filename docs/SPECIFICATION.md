# Mine Loader — especificação do projeto

**Status:** rascunho implementável 0.1  
**Versão-alvo inicial:** Minecraft Java 1.21.1  
**Runtime:** Java 21  
**Bootstrap atual:** Fabric Loader/Loom, usado apenas como ponto de integração inicial  
**Formato de mod:** `mod.json` + Lua + recursos locais ou remotos

## 1. Visão do produto

O Mine Loader será uma plataforma para criação de mods de Minecraft Java sem exigir que cada criador escreva um mod Java completo. Um mod será descrito por um manifesto JSON, poderá conter scripts Lua para lógica e poderá declarar recursos como texturas, modelos, sons, receitas e configurações.

O objetivo não é limitar o Minecraft a um conjunto pequeno de scripts, mas oferecer uma camada estável, validável e extensível sobre ele. O loader será responsável por interpretar o contrato, controlar permissões, registrar conteúdo, fornecer eventos, sincronizar dados e executar as mudanças no momento correto.

> **Princípio central:** a IA ou o criador propõe conteúdo; o loader valida e decide o que pode entrar no jogo.

## 2. Objetivos

| Objetivo | Definição de aceitação |
|---|---|
| Criar um mod sem Java | Uma pasta com `mod.json` e Lua pode ser descoberta e carregada. |
| Declarar blocos por JSON | O manifesto pode registrar um bloco com configurações e item correspondente. |
| Executar lógica Lua | O script recebe eventos e uma API controlada, sem acesso automático à JVM. |
| Alterar conteúdo em runtime | Estados declarativos, variantes visuais e propriedades explicitamente dinâmicas podem mudar durante o jogo. |
| Usar recursos externos | Texturas remotas podem ser baixadas, verificadas, armazenadas em cache e expostas como recursos locais. |
| Recarregar com segurança | Um erro em uma nova versão não desativa a versão anterior. |
| Facilitar a criação por IA | Um gerador futuro poderá produzir um pacote validável, testável e revisável. |
| Funcionar em Windows | O repositório deve incluir Gradle Wrapper e scripts `.bat` para preparação e execução. |

## 3. Fora do escopo imediato

O primeiro ciclo não prometerá hot-unload de classes Java, compatibilidade automática com todo mod Fabric/NeoForge, alteração arbitrária de registries vanilla após o congelamento, execução de Lua com acesso direto a Java, rede livre dentro dos scripts ou download de arquivos sem validação.

Itens e blocos especiais, entidades de bloco, menus, renderizadores complexos, worldgen avançado, shaders e integração profunda com sistemas internos serão adicionados por APIs específicas. Eles não devem ser simulados com campos genéricos que produzam estados inconsistentes.

## 4. Arquitetura

O sistema será dividido em componentes com fronteiras claras.

| Componente | Responsabilidade |
|---|---|
| Bootstrap | Iniciar o jogo e conectar o loader ao ciclo de inicialização do Minecraft. A implementação atual usa Fabric como bootstrap. |
| ModScanner | Encontrar diretórios de mods e garantir que o ID, os caminhos e os arquivos obrigatórios sejam válidos. |
| ManifestValidator | Validar JSON, schema, IDs, versões, permissões, dependências e referências. |
| RegistryBridge | Registrar blocos, itens e outros objetos no momento permitido pelo Minecraft. |
| DeclarativeContent | Representar blocos, itens e conteúdo definido por dados, incluindo estados controlados pelo loader. |
| LuaRuntime | Criar um ambiente por mod, carregar scripts, registrar callbacks e executar eventos. |
| MinecraftBridge | Converter operações da API pública em chamadas agendadas na thread correta do jogo. |
| ResourceManager | Resolver recursos locais/remotos, verificar conteúdo, manter cache e gerar o resource pack virtual. |
| SyncManager | Sincronizar manifesto, estados e versões entre servidor e cliente. |
| ReloadManager | Preparar, validar, aplicar e confirmar uma nova versão sem deixar duas versões ativas. |
| Diagnostics | Registrar logs, erros por mod, tempos de callback, permissões e estado da última recarga. |
| Tooling | Oferecer comandos, validação offline, testes, geração de exemplos e scripts de bootstrap. |

A integração inicial com Fabric é deliberadamente substituível. O código específico do bootstrap deverá ficar isolado para que, no futuro, o Mine Loader possa usar uma camada própria ou outra base sem reescrever a API de mods.

## 5. Ciclo de vida do mod

O ciclo de vida formal será:

```text
DISCOVERED
    ↓
VALIDATED
    ↓
PREPARED
    ↓
REGISTERED
    ↓
ACTIVE
    ├── RELOADING ──→ PREPARED ──→ ACTIVE
    └── DISABLED
```

Se a validação, preparação ou aplicação falhar, o mod será marcado como `FAILED` para diagnóstico e não substituirá uma versão ativa. O estado anterior será mantido até que uma versão nova passe por todas as verificações.

O loader deverá distinguir mudanças estáticas de mudanças dinâmicas. Alterar a descrição, o Lua ou um recurso pode ser recarregável. Alterar o ID, a classe base do bloco ou a existência de um registro vanilla normalmente exige reinicialização.

## 6. Estrutura de um mod

```text
mods-lua/<mod_id>/
├── mod.json
├── main.lua
├── assets/<namespace>/
│   ├── textures/
│   ├── models/
│   ├── blockstates/
│   └── sounds/
├── data/<namespace>/
├── generated/
└── tests/
```

O nome do diretório deverá coincidir com `id`. O manifesto e o entrypoint precisam estar dentro da raiz do mod. Caminhos absolutos, `..`, links simbólicos fora da raiz e arquivos executáveis não fazem parte do contrato.

## 7. Manifesto JSON

Os campos obrigatórios da versão 1 do manifesto são `schema`, `id`, `name`, `version` e `entrypoint`. O campo `permissions` declara capacidades; `events` associa eventos a funções Lua; `blocks` declara blocos.

Exemplo mínimo:

```json
{
  "schema": 1,
  "id": "crystal_world",
  "name": "Crystal World",
  "version": "0.1.0",
  "entrypoint": "main.lua",
  "permissions": ["chat.send", "world.write"],
  "events": {
    "server_started": "on_server_started",
    "tick": "on_tick"
  },
  "blocks": []
}
```

Cada bloco deverá possuir um ID local ao namespace do mod. A declaração completa poderá conter material, configurações de física, ferramenta, sons, estados, renderização, item, drops, tags e callbacks. Campos ainda não implementados devem ser rejeitados quando forem inválidos, ou preservados como extensões versionadas quando o namespace da extensão for conhecido.

O schema canônico fica em [`spec/mod.schema.json`](../spec/mod.schema.json). O schema é parte da API pública e deverá ser versionado; mudanças incompatíveis exigem incremento de `schema` ou uma regra explícita de migração.

## 8. Modelo de bloco

O modelo de bloco terá três grupos.

| Grupo | Exemplos | Política |
|---|---|---|
| Registro estático | ID, classe base, material inicial, tipo de item, registries | Definido antes do registro e não removido em runtime. |
| Estado do bloco | `lua_variant`, `lua_luminance`, propriedades de direção ou fase | Pode mudar por posição se estiver declarado e validado. |
| Propriedade dinâmica | Dureza, resistência, atrito e multiplicadores expostos pela classe declarativa | Pode mudar por API explícita; não são campos Lua arbitrários. |

A primeira implementação usa `lua_variant` de 0 a 15 para selecionar modelos e texturas. O Lua muda o estado no mundo; o cliente escolhe o modelo correspondente pelo blockstate. Essa solução evita baixar ou reconstruir texturas a cada tick.

Propriedades que alteram a estrutura do registro, a classe do bloco, o número de estados ou o contrato de sincronização não devem ser mutadas de forma implícita. Uma futura API poderá oferecer formas dinâmicas, colisão e sons, mas cada uma deverá definir invalidação de cache e sincronização.

## 9. Recursos locais e remotos

Uma textura pode ser local ou remota:

```json
"texture": {
  "source": "remote",
  "url": "https://example.org/crystal.png",
  "sha256": "<64 caracteres hexadecimais>",
  "max_bytes": 1048576,
  "fallback": "minecraft:block/stone"
}
```

O ResourceManager deverá aplicar HTTPS por padrão, timeout, limite de bytes, validação de imagem, limite de dimensões, hash SHA-256 opcional ou obrigatório conforme a política do servidor e cache por conteúdo. O download não poderá bloquear a thread do jogo durante uma recarga normal.

O recurso remoto será transformado em um recurso local dentro do resource pack virtual. O servidor deverá informar a versão/hash dos recursos ao cliente. Em servidores multiplayer, o administrador poderá exigir uma lista de hosts permitidos ou um pacote previamente aprovado.

A referência do Fabric para resource packs e listeners serve como ponto de integração da implementação atual [1] [2]. O Mine Loader, porém, manterá uma abstração própria para não expor detalhes internos do Fabric aos criadores de mods.

## 10. Runtime Lua

Cada mod terá um ambiente Lua separado. A API pública será construída pelo loader e não incluirá acesso automático a Java, reflexão, `io`, `os`, `package`, `debug`, processos, sockets ou carregamento de arquivos arbitrários.

API inicial:

| API | Permissão | Finalidade |
|---|---|---|
| `ctx.log.info(text)` | Nenhuma | Registrar informação identificada pelo mod. |
| `ctx.log.warn(text)` | Nenhuma | Registrar aviso. |
| `ctx.server.broadcast(text)` | `chat.send` | Enviar mensagem pública. |
| `ctx.server.set_block_variant(id, x, y, z, value)` | `world.write` | Alterar o estado visual de um bloco declarativo. |
| `ctx.server.set_block_property(id, property, value)` | `world.write` | Alterar propriedade dinâmica autorizada. |
| `ctx.server.set_block_luminance(id, x, y, z, value)` | `world.write` | Alterar luminosidade declarativa por posição. |
| `ctx.player.name` | `player.read` | Ler nome durante evento de jogador. |
| `ctx.player.uuid` | `player.read` | Ler UUID durante evento de jogador. |
| `ctx.player.send_message(text)` | `chat.send` | Enviar mensagem a um jogador. |

Eventos iniciais: `loader_ready`, `server_started`, `server_stopped`, `player_joined` e `tick`. O evento `tick` deverá ser inscrito explicitamente para evitar trabalho desnecessário.

Exemplo:

```lua
local ticks = 0

function on_tick(ctx)
    ticks = ticks + 1
    if ticks % 40 == 0 then
        local variant = math.floor(ticks / 40) % 2
        ctx.server.set_block_variant("crystal_world:crystal", 0, 100, 0, variant)
    end
end

return { on_tick = on_tick }
```

O runtime deverá registrar duração e falhas dos callbacks. Um erro em um mod não poderá interromper o tick dos demais mods nem derrubar o servidor.

## 11. Recarga e rollback

A recarga seguirá quatro passos:

1. **Prepare:** ler arquivos e construir um novo ambiente fora da thread principal quando possível.
2. **Validate:** validar schema, permissões, referências, scripts, recursos e limites.
3. **Apply:** agendar mudanças na thread correta do jogo, pausando callbacks do mod antigo.
4. **Commit:** trocar o snapshot ativo e liberar o ambiente anterior quando não houver referências pendentes.

Se qualquer etapa falhar, o snapshot anterior continua ativo. A recarga de Lua não recriará registros de blocos. A recarga de recursos poderá atualizar modelos e texturas, mas a operação deverá ser agrupada para evitar vários reloads consecutivos.

A JVM não oferece uma estratégia geral para descarregar classes individuais de um mod já ativo; redefinição de classes e instrumentação têm limitações próprias [3]. Por isso, o hot-reload do Mine Loader priorizará dados, Lua e recursos, deixando bytecode Java para a inicialização.

## 12. Cliente, servidor e rede

O servidor será a autoridade para estados do mundo e execução de lógica que afeta gameplay. O cliente receberá os dados necessários para renderização e interface. O loader deverá comparar ID, versão, schema e hash do pacote antes de aceitar a conexão.

Um mod poderá declarar `client_only`, `server_only` ou `both` em uma versão futura do manifesto. Até essa definição, o protótipo considera o mod como compartilhado e mantém a lógica de gameplay no servidor.

Recursos remotos nunca deverão ser aceitos silenciosamente pelo cliente em desacordo com a política do servidor. O modo seguro é distribuir hashes e exigir confirmação administrativa para hosts externos.

## 13. Segurança

A sandbox Lua reduz a superfície da API, mas não deve ser descrita como isolamento perfeito contra código hostil enquanto o runtime estiver dentro da mesma JVM. Mods de terceiros deverão ser tratados como código confiável apenas quando instalados pelo administrador.

Controles obrigatórios:

| Controle | Regra |
|---|---|
| Permissões | Toda operação de mundo, chat, jogador ou rede exige uma permissão declarada. |
| Caminhos | Apenas arquivos dentro da raiz do mod ou cache controlado. |
| Rede | Apenas ResourceManager, HTTPS, timeout e hosts aprovados. Lua não acessa rede diretamente. |
| Recursos | Limite de tamanho, dimensões, MIME e hash. |
| Lua | Sem ponte Java automática, reflexão, processos ou carregamento livre. |
| Tempo | Limite por callback e diagnóstico de scripts lentos. |
| Falhas | Erro de mod é isolado e produz rollback, não crash global. |
| Auditoria | Log de versão, permissões, recursos e resultados de validação. |

## 14. Compatibilidade e versionamento

O projeto terá uma matriz explícita de compatibilidade entre versão do Minecraft, versão do loader, versão do schema e versão da API Lua. Um mod deverá informar sua versão de API mínima e máxima quando esse campo for introduzido.

O primeiro alvo oficial será Minecraft 1.21.1 com Java 21. Mudanças internas entre versões ficarão atrás de adaptadores. A API do mod não deverá depender diretamente de nomes Yarn ou classes Fabric.

Compatibilidade com mods Fabric e NeoForge — carregar um mod escrito para *elas* — será um projeto separado. O Mine Loader suporta mods no formato próprio, e usa as duas apenas como hospedeiras: há um adaptador para cada, em paridade, e o núcleo não conhece nenhuma das duas.

## 15. Ferramentas de desenvolvimento

O repositório deverá oferecer:

| Ferramenta | Uso |
|---|---|
| Gradle Wrapper | Build reproduzível sem instalar Gradle globalmente. |
| `setup-dev.bat` | Preparar Java, clonar/atualizar e montar o ambiente em Windows. |
| `run-client.bat` | Iniciar o cliente de desenvolvimento. |
| `run-server.bat` | Iniciar o servidor de desenvolvimento. |
| Validador offline | Validar `mod.json` e referências antes de iniciar o jogo. |
| Testes unitários | Verificar parser, permissões, recursos, Lua e rollback. |
| Testes de integração | Iniciar servidor/cliente e verificar registro e eventos. |
| Diagnóstico | Exibir mods, blocos, recursos e estado de recarga. |

## 16. Geração assistida por IA

A IA será uma camada de autoria, não parte da autoridade do runtime. O fluxo será prompt → especificação estruturada → arquivos JSON/Lua/assets → validação → testes → aprovação → instalação.

O gerador deverá produzir também um relatório contendo permissões, recursos remotos, referências, arquivos e resultados de testes. A IA não poderá instalar ou ativar um mod sem passar pelo mesmo validador usado por mods escritos manualmente.

## 17. Critérios de aceite do próximo marco

O próximo marco será considerado concluído quando um usuário conseguir copiar o exemplo para `mods-lua`, iniciar o servidor, ver o bloco registrado, executar um callback Lua, alternar `lua_variant`, observar a mudança física autorizada e recarregar o script com `/lua reload hello_lua`.

O marco seguinte deverá iniciar o cliente, carregar o resource pack virtual e validar visualmente pelo menos duas variantes do bloco. O teste deverá guardar logs e prints sem incluir caches ou mundos de execução no Git.

## 18. Roadmap resumido

| Marco | Entrega |
|---|---|
| M0 — Protótipo | Manifesto, bloco declarativo, Lua, variantes, cache e comandos básicos. |
| M1 — Cliente | Resource pack virtual validado visualmente e sincronização básica. |
| M2 — API de conteúdo | Itens, receitas, loot, tags, sons, partículas e callbacks de interação. |
| M3 — Recarga | Snapshot, rollback, diagnóstico e reload de dados agrupado. |
| M4 — Tooling | Validador offline, scripts Windows, templates e testes de integração. |
| M5 — IA | Gerador de pacote JSON/Lua/recursos com relatório e aprovação. |
| M6 — Ecossistema | Documentação, exemplos, versionamento, distribuição e APIs especializadas. |

## Referências

[1]: https://docs.fabricmc.net/develop/loader/ "Fabric Documentation — Loader"
[2]: https://wiki.fabricmc.net/tutorial:custom_resources "Fabric Wiki — Custom Resources"
[3]: https://docs.oracle.com/javase/8/docs/api/java/lang/instrument/Instrumentation.html "Oracle Java Documentation — Instrumentation"

# Mine Loader — roadmap de implementação

Este roadmap transforma a visão do projeto em marcos verificáveis. Cada marco deve preservar compatibilidade com os mods já aceitos ou fornecer uma migração explícita.

## M0 — Fundação publicada

**Objetivo:** ter um projeto compilável, com um mod de exemplo e documentação básica.

A entrega inclui o Gradle Wrapper, Minecraft 1.21.1, Java 21, Fabric como bootstrap, parser de manifesto, registro de bloco declarativo, LuaJ, comandos de diagnóstico e testes unitários. O mod `hello_lua` já demonstra um bloco, callbacks Lua, troca de `lua_variant`, texturas locais e alterações dinâmicas autorizadas.

**Critério de aceite:** `gradlew.bat test` e `gradlew.bat build` passam em uma máquina Windows com Java 21.

## M1 — Ambiente de desenvolvimento simples

**Objetivo:** permitir que outra pessoa prepare uma máquina rapidamente.

Serão adicionados `setup-dev.bat`, `run-client.bat` e `run-server.bat`. O bootstrap deverá verificar Java, clonar ou atualizar o repositório, copiar o exemplo para `run/mods-lua` e exibir mensagens compreensíveis. O script não deverá apagar alterações locais sem confirmação.

**Critério de aceite:** uma máquina limpa consegue executar o servidor de desenvolvimento com um único script depois de instalar Java 21 e Git.

## M2 — Contrato de conteúdo

**Objetivo:** tornar o manifesto JSON uma API estável.

O schema será dividido em manifest, bloco, item, recurso, estado, evento e permissões. A ferramenta de validação offline deverá mostrar erros com arquivo, caminho JSON e sugestão de correção. IDs duplicados, caminhos externos e propriedades incompatíveis deverão ser rejeitados antes de iniciar o jogo.

**Critério de aceite:** cada exemplo do repositório valida no modo estrito e possui um caso de erro coberto por teste.

## M3 — API de blocos e itens

**Objetivo:** cobrir os casos comuns de criação de conteúdo.

A API deverá suportar blocos cúbicos, blocos com variantes, itens, receitas, loot tables, tags, sons, partículas e drops. Tipos especiais, como slabs, escadas, portas, entidades de bloco e menus, deverão possuir adaptadores próprios, não uma coleção de flags genéricas.

**Critério de aceite:** um mod JSON consegue criar um conjunto pequeno de conteúdo jogável sem Java e sem editar manualmente arquivos gerados.

## M4 — Runtime Lua robusto

**Objetivo:** transformar Lua em uma extensão previsível para gameplay.

Serão implementados limites de tempo, orçamento de callbacks, tratamento de erro por mod, filas de operação para a thread do jogo, APIs de mundo, jogadores, inventário e comandos. A API será versionada e as permissões serão verificadas no momento de cada operação.

**Critério de aceite:** um erro ou callback lento de um mod não derruba nem bloqueia permanentemente os demais mods.

## M5 — Recursos e cliente-servidor

**Objetivo:** fazer recursos locais e remotos funcionarem de forma consistente.

O ResourceManager terá cache por conteúdo, hashes, fallback, limites de tamanho e políticas de host. O SyncManager comparará versões e hashes e fornecerá ao cliente os recursos aprovados. O cliente deverá conseguir renderizar o bloco com todas as variantes declaradas.

**Critério de aceite:** o servidor e o cliente apresentam o mesmo blockstate e a mesma textura após uma conexão limpa.

## M6 — Reload e snapshots

**Objetivo:** permitir evolução durante o desenvolvimento sem reiniciar sempre.

O ReloadManager implementará `prepare`, `validate`, `apply` e `commit`, mantendo a versão anterior em caso de erro. Scripts Lua poderão ser recarregados; dados e recursos serão recarregados em lote. Registries estruturais continuarão exigindo reinicialização até que exista uma ponte segura.

**Critério de aceite:** modificar o Lua e executar `/lua reload <mod_id>` troca a lógica ativa e preserva o mundo quando o novo script é válido.

## M7 — Ferramentas e testes

**Objetivo:** reduzir o tempo entre uma ideia e um mod funcionando.

A entrega incluirá templates, exemplos progressivos, validador CLI, testes de integração do servidor e cliente, logs de diagnóstico e relatórios de geração de recursos. O projeto deverá incluir instruções para Windows e Linux.

**Critério de aceite:** um novo desenvolvedor consegue criar um mod a partir do template e entender uma falha apenas pelo diagnóstico do loader.

## M8 — Geração assistida por IA

**Objetivo:** gerar mods a partir de uma descrição, sem abrir mão de validação.

A IA produzirá primeiro uma especificação intermediária. Um gerador determinístico criará `mod.json`, Lua, assets e relatório. O pacote passará pelo mesmo validador, sandbox e testes dos mods manuais. A ativação exigirá aprovação do usuário ou do administrador.

**Critério de aceite:** prompts de exemplo geram pacotes consistentes, sem permissões ocultas, caminhos inválidos ou chamadas de API não suportadas.

## M9 — Ecossistema

**Objetivo:** preparar o loader para uso público.

Serão adicionados documentação de API, sistema de versões, changelog, releases, migrações de schema, exemplos comunitários e política de segurança. A compatibilidade com outros loaders será investigada como projeto separado.

**Critério de aceite:** um mod criado para uma versão documentada do schema pode ser atualizado ou receber uma mensagem de migração clara.

## Prioridade de implementação

A ordem recomendada é **M1 → M2 → M4 → M5 → M6 → M3 especializado → M7 → M8**. A razão é estabilizar o contrato e a segurança antes de expandir a quantidade de conteúdo. Criar mais flags sem uma API de validação e diagnóstico aumentaria a superfície de bugs.

## Decisões abertas

A versão futura deverá escolher se o Lua continuará sendo executado por LuaJ ou por outra implementação, se recursos remotos serão permitidos por padrão, qual política de assinatura será usada em servidores e até onde a compatibilidade com Fabric/NeoForge será necessária.

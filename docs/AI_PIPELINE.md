# Pipeline de criação de mods assistida por IA

## Visão geral

A plataforma permitirá que o usuário descreva um mod em linguagem natural. A IA não escreverá diretamente dentro do processo do Minecraft. Ela produzirá um pacote intermediário com manifesto JSON, scripts Lua, arquivos de recurso e um relatório de permissões. O loader validará o pacote, executará testes isolados e somente depois o colocará no diretório de mods habilitados.

A geração poderá ser repetida indefinidamente para criar novos mods ou variantes, mas cada resultado terá um identificador, uma versão e um conjunto de arquivos próprios. Isso torna a ideia de “mods infinitos” operacional sem transformar o runtime em um executor de conteúdo desconhecido e sem rastreabilidade.

## Fluxo

```text
Prompt do usuário
      ↓
Especificação estruturada do mod
      ↓
Geração de mod.json + Lua + recursos
      ↓
Validação de schema, permissões e referências
      ↓
Teste estático e simulação de eventos
      ↓
Preview/aprovação
      ↓
Instalação no mods-lua/<id>
      ↓
Registro no jogo e recarga controlada
```

A geração deve ocorrer em duas passagens. Na primeira, a IA transforma o pedido em uma especificação declarativa: blocos, propriedades, estados, eventos, permissões e recursos desejados. Na segunda, um gerador determinístico converte essa especificação em JSON, Lua e caminhos de assets. Isso reduz a chance de a IA produzir arquivos incompatíveis entre si.

## Artefato gerado

Cada mod gerado será uma pasta autossuficiente:

```text
mods-lua/<mod_id>/
├── mod.json
├── main.lua
├── assets/
│   └── <namespace>/
│       ├── textures/
│       ├── models/
│       └── blockstates/
├── generated/
│   └── generation-report.json
└── tests/
    └── smoke.lua
```

O `generation-report.json` registrará o prompt resumido, a versão do schema, os arquivos produzidos, permissões pedidas, URLs de recursos remotos, hashes disponíveis e o resultado da validação. O prompt completo poderá ser armazenado opcionalmente pelo usuário, mas não deve ser necessário para executar o mod.

## Camadas de validação

| Camada | Verificação | Resultado esperado |
|---|---|---|
| Sintaxe | JSON e Lua bem formados | O pacote pode ser lido sem erro. |
| Contrato | Campos obrigatórios, tipos e enumerações | O manifesto respeita o schema da versão. |
| Segurança | Permissões, URLs, tamanho, hash e caminhos | O mod não acessa capacidades não declaradas. |
| Referências | Blocos, itens, texturas, eventos e callbacks existentes | Nenhuma referência quebrada na instalação. |
| Runtime | Tempo, memória, número de callbacks e chamadas por tick | O script permanece dentro dos limites definidos. |
| Jogo | Registro, renderização, colocação e quebra | O Minecraft consegue carregar o pacote. |

Falhas de validação devem impedir a instalação. Durante uma recarga, o loader manterá a versão anterior ativa caso o pacote novo não passe em qualquer camada.

## Recursos remotos

A IA poderá sugerir uma textura remota, mas o pacote instalado armazenará a URL, o tipo MIME detectado, o tamanho, o hash SHA-256 e a data de obtenção. O loader usará HTTPS, limites de tamanho e timeout. O download ocorrerá fora da thread do jogo e a textura somente será exposta ao resource manager depois de validada.

Para servidores, a política padrão será exigir que o cliente receba os mesmos recursos por um pacote assinado ou por uma URL aprovada pelo administrador. O servidor não deve mandar o cliente buscar conteúdo arbitrário sem uma política explícita.

## Papel do Lua

A IA poderá gerar Lua para eventos e comportamentos que não são expressáveis por propriedades estáticas. O script receberá somente a API do loader, e o manifesto declarará as permissões correspondentes. Código Lua gerado não terá acesso direto a Java, à rede, a processos, ao sistema de arquivos ou a reflexão.

A primeira versão dará prioridade a callbacks previsíveis, como `on_load`, `on_server_started`, `on_player_joined`, `on_use` e `on_tick`. Comportamentos que exigirem acesso a classes internas do Minecraft deverão ser rejeitados ou implementados mais tarde por uma API explícita.

## Aprovação e reversão

A interface futura mostrará o resumo do mod antes de ativá-lo: blocos criados, eventos inscritos, permissões, recursos remotos e tamanho do pacote. A ativação será uma troca atômica de diretório ou snapshot lógico. A reversão usará a última versão validada.

## Princípio de projeto

A IA é um gerador de propostas; o loader é a autoridade que decide o que pode executar. Essa separação permite evoluir a geração de conteúdo sem colocar a estabilidade do jogo nas mãos de texto gerado sem validação.

# Mine Loader — especificação de segurança

## 1. Modelo de confiança

Um mod Mine Loader executa lógica dentro do processo do Minecraft. Lua não deve ser tratado como uma barreira de segurança perfeita enquanto compartilhar a mesma JVM do jogo. A política padrão é considerar mods instalados pelo administrador como código confiável e limitar cuidadosamente os mods recebidos de terceiros.

A segurança será feita por camadas: validação de pacote, permissões explícitas, API mínima, limites de recursos, isolamento de falhas, políticas de rede e auditoria.

## 2. Permissões

O manifesto declara as capacidades do mod. O loader verifica a permissão durante a preparação e novamente quando a operação é chamada.

| Capacidade | Regra |
|---|---|
| `chat.send` | Permite mensagens públicas ou direcionadas. Deve respeitar limites de tamanho e frequência. |
| `player.read` | Permite apenas dados necessários do jogador no contexto do evento. |
| `server.read` | Permite leitura de informações públicas e não sensíveis. |
| `server.command.register` | Permite comandos sob o namespace do mod e exige checagem de operador para operações administrativas. |
| `world.write` | Permite mudanças declarativas no mundo, com limites de posição, frequência e tipo de operação. |
| `resource.remote` | Futura permissão para baixar recursos; deverá exigir URL HTTPS, hash ou política do servidor. |

Permissões não reconhecidas são erro de validação. O loader não deve inferir permissões a partir do conteúdo Lua.

`events.action.authorization` é uma capability de negociação, não uma permissão administrativa: ela
habilita o callback `action_attempt`, mas não concede `world.write`, acesso a inventários ou qualquer
operação adicional. O callback recebe apenas snapshots e pode devolver `false` para negar a ação.
Como uma falha de um autorizador poderia abrir uma brecha, erros Lua, de bridge ou de runtime são
tratados como veto (**fail-closed**) e ficam associados ao mod no log.

## 3. Sandbox Lua

O ambiente Lua deve remover ou substituir bibliotecas que forneçam acesso ao sistema operacional, rede, arquivos, processos, reflexão ou carregamento arbitrário. A ponte Java não será publicada como API genérica.

A API pública oferecerá objetos de dados e funções estreitas. Contextos de eventos não devem permanecer armazenados pelo script para uso posterior. Operações no Minecraft serão realizadas na thread correta por uma fila controlada.

A implementação deve registrar tempo, número de instruções quando disponível, alocações relevantes e quantidade de operações por tick. O limite excedido interrompe o callback atual e gera diagnóstico do mod.

## 4. Recursos remotos

Recursos remotos são uma superfície de risco própria. O ResourceManager deve:

1. aceitar HTTPS por padrão e rejeitar esquemas diferentes;
2. limitar redirects e impedir mudança para um esquema não HTTPS;
3. aplicar timeout de conexão e leitura;
4. limitar o tamanho do corpo independentemente de `Content-Length`;
5. validar imagem, formato e dimensões antes de salvar;
6. verificar SHA-256 quando declarado ou exigido pelo servidor;
7. escrever primeiro em arquivo temporário e trocar atomicamente;
8. armazenar cache por hash de conteúdo;
9. respeitar a lista de hosts aprovada pelo administrador;
10. usar fallback local quando um recurso opcional falhar.

O download nunca deve ser executado na thread de tick. Um recurso que falhar não pode travar o servidor inteiro.

## 5. Integridade do pacote

O loader deve produzir um relatório de conteúdo antes da ativação. O relatório inclui ID, versão, schema, permissões, arquivos, hashes, callbacks, URLs e limites solicitados.

Em uma versão futura, o pacote poderá ser assinado. O servidor deverá enviar ao cliente uma identidade do pacote e os hashes dos recursos. A aceitação de um mod com a mesma ID e conteúdo diferente deve exigir uma decisão clara do administrador.

## 6. Falhas e rollback

O erro de um mod deve ser isolado. Erros de sintaxe, exceções Lua, callbacks lentos, recursos inválidos e falhas de rede devem ser associados ao ID do mod e registrados sem interromper o ciclo dos demais.

Recargas usam snapshots. A versão nova só se torna ativa depois de preparação e validação. Se a aplicação falhar, o snapshot anterior retorna. Alterações no mundo que já foram aplicadas não podem ser desfeitas automaticamente sem uma transação explícita; por isso, APIs de escrita devem ser pequenas e previsíveis.

## 7. Dados do usuário

O loader não deve enviar prompts, mundos, mensagens de jogador ou arquivos locais para serviços externos sem uma configuração explícita. O gerador de IA deve operar com o mínimo de contexto necessário e exibir quais arquivos serão enviados.

Logs públicos não devem conter tokens, caminhos pessoais desnecessários, conteúdo completo de prompts privados ou dados pessoais do jogador.

## 8. Checklist antes da ativação

| Verificação | Bloqueia instalação? |
|---|---:|
| Manifesto JSON inválido | Sim |
| ID inválido ou duplicado | Sim |
| Entrypoint fora da raiz | Sim |
| Permissão desconhecida | Sim |
| Lua com erro de compilação | Sim |
| URL não HTTPS | Sim, salvo política explícita |
| Hash incompatível | Sim quando hash é obrigatório |
| Textura grande ou inválida | Sim para o recurso; não para os demais mods |
| Callback lento | Desativa ou limita o mod conforme política |
| Erro durante recarga | Mantém versão anterior |

## 9. Princípio operacional

A capacidade de um mod deve ser menor ou igual à capacidade que o manifesto declara, e a capacidade declarada deve ser menor ou igual à política do servidor. Nenhuma camada de geração por IA pode ultrapassar essa regra.

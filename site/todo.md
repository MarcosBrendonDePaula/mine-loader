# Expansão documental do MineLoader

- [x] Definir a estrutura das rotas e a navegação de documentação.
- [x] Criar a página de primeiros passos com instalação, manifesto e primeira execução.
- [x] Criar a página de progressão com o percurso do mod simples ao mod com capabilities.
- [x] Criar o catálogo de APIs por domínio, com estado e exemplos curtos.
- [x] Criar a referência de declaração de mod e manifestos JSON copiáveis.
- [x] Verificar navegação, responsividade, links, tipagem e build.
- [ ] Guardar checkpoint final e entregar a versão navegável.

## Matriz viva via GitHub

- [x] Definir e publicar o JSON canónico da matriz de compatibilidade no repositório MineLoader.
- [x] Consultar o JSON da branch `main` no site e renderizar os runtimes com fallback explícito.
- [x] Consultar indicadores públicos do repositório e expor estado de atualização sem credenciais.
- [x] Validar carregamento, erro de rede, responsividade e build da integração.
- [ ] Guardar checkpoint final da documentação conectada.

## Tree view de runtimes

- [x] Agrupar os runtimes por plataforma e família semântica de versão, como `1.21.*`.
- [x] Renderizar a árvore com nós expansíveis, estados e resultados de GameTests.
- [x] Verificar a árvore em desktop e mobile e salvar checkpoint.

## Tutoriais práticos

- [x] Confirmar exemplos canónicos de bloco, item, menu/UI e comportamento Lua.
- [x] Criar o índice de tutoriais e as quatro rotas de aprendizado.
- [x] Adicionar exemplos copiáveis, permissões e capabilities em cada tutorial.
- [x] Verificar rotas, código e responsividade em desktop/mobile; `pnpm check` e `pnpm build` concluídos.
- [ ] Guardar checkpoint final e entregar a versão navegável dos tutoriais.

## Tutoriais dinâmicos via GitHub

- [ ] Definir o schema versionado de documento tutorial JSON e os dados de índice.
- [ ] Criar e validar os quatro documentos JSON no repositório MineLoader.
- [ ] Publicar a fonte JSON e a documentação que explica seu contrato.
- [ ] Fazer o site consultar, validar e renderizar o índice e as páginas a partir da `main`.
- [ ] Preservar fallback explícito, contraste e responsividade quando a fonte estiver indisponível.
- [ ] Validar os dois projetos, salvar checkpoints e entregar a integração.

## GitHub Pages em /docs

- [ ] Gerar o artefato estático do site no diretório `docs/` do repositório MineLoader.
- [ ] Configurar a base pública e o roteamento do site para a rota `/docs`.
- [ ] Garantir fallback estático para links diretos nas páginas de documentação.
- [ ] Criar o workflow de build e publicação no GitHub Pages.
- [ ] Validar o artefato publicado, URLs de assets e consulta aos JSON da `main`.

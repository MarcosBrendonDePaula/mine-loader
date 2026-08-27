# Fonte do site público

Esta pasta contém o frontend React do site do MineLoader. O GitHub Pages deste repositório está configurado para servir a pasta `docs/` da branch `main`, portanto o artefato estático é gerado diretamente ali.

```bash
pnpm install --frozen-lockfile
pnpm check
pnpm build:pages
```

`build:pages` usa a base `/mine-loader/`, preserva as rotas internas como `/docs` e escreve em `../docs`. O arquivo `docs/404.html` restaura links diretos de rotas da SPA; `docs/.nojekyll` impede que o Pages remova arquivos ou diretórios do bundle.

Os tutoriais não ficam duplicados nesta pasta. O navegador consulta `docs/tutorials/index.json` e os quatro documentos de tutorial na branch `main`, com cache curto e estado explícito de falha. Ao alterar conteúdo dos tutoriais, mantenha o schema e execute `node docs/tutorials/verify.mjs`.

## CI

O workflow [Validate and build documentation site](../.github/workflows/site-pages.yml) roda em mudanças sob `site/`, `docs/tutorials/`, `docs/compatibility.json` e `docs/assets/`. Ele verifica os JSONs, valida TypeScript, gera `docs/` e, em `main`, cria um commit automático apenas se o artefato mudou. Em pull requests, o job falha se o build obrigatório de `docs/` não estiver incluído na alteração.

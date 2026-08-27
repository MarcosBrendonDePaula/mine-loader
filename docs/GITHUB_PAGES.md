# Publicação da documentação web

O GitHub Pages do repositório publica a branch `main` a partir de `/docs`. A raiz pública confirmada é:

```text
https://marcosbrendondepaula.github.io/mine-loader/
```

As rotas da documentação ficam sob `/docs`, por exemplo:

```text
https://marcosbrendondepaula.github.io/mine-loader/docs
https://marcosbrendondepaula.github.io/mine-loader/docs/tutoriais
```

O código-fonte do frontend está em [`../site/`](../site/). Para gerar o artefato que o Pages serve, execute na raiz do repositório:

```bash
pnpm --dir site install --frozen-lockfile
node docs/tutorials/verify.mjs
pnpm --dir site check
pnpm --dir site build:pages
```

O último comando escreve o bundle em `docs/`, usa a base `/mine-loader/` e inclui `404.html` para preservar links diretos de rotas da SPA. O workflow [site-pages.yml](../.github/workflows/site-pages.yml) executa essa sequência automaticamente quando muda a fonte do site, um tutorial JSON, a matriz de compatibilidade ou um asset do site.

> **Regra de manutenção:** não edite manualmente `docs/index.html` nem `docs/assets/index-*`. Altere a fonte em `site/` ou os dados canónicos em `docs/tutorials/`, gere novamente o artefato e deixe o CI confirmar que a saída está atualizada.

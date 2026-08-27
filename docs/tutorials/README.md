# Tutoriais JSON

Os quatro guias que o site público renderiza são documentos de dados, não cópias de texto no frontend. O índice é [`index.json`](index.json); cada entrada aponta para um documento individual, como [`bloco.json`](bloco.json), [`item.json`](item.json), [`ui.json`](ui.json) ou [`lua.json`](lua.json).

> **Fonte de verdade:** este diretório descreve apenas contratos publicados do MineLoader. Uma proposta ou API marcada como futura não deve entrar no tutorial até possuir implementação, documentação normativa e evidência compatível com a matriz.

| Arquivo | Papel | Consumidor |
|---|---|---|
| `index.json` | Lista, ordem e metadados dos guias | Índice navegável do site |
| `*.json` | Texto, exemplos, permissões, capabilities e limites de um guia | Página individual do site |
| `tutorial.schema.json` | Contrato legível por ferramentas de um documento | Editores e validação externa |
| `verify.mjs` | Verificação determinística mínima, sem dependências | CI local e revisão antes de publicar |

Cada documento usa `schema: 1` e `kind: "mine_loader_tutorial"`. O campo `title` pode conter `|` para marcar a quebra editorial que o site apresenta; isso não altera o texto semântico do título. `evidence.kind` informa a prova visual de cada rota, enquanto `sections` carrega a explicação, código e tabelas.

Todo tutorial também declara `beginner`. Essa seção é obrigatória e responde, antes do primeiro trecho de código, quatro perguntas de quem acabou de chegar: o que precisa ter pronto (`prerequisites`), quais arquivos criar (`files`), qual sinal confirma que deu certo (`success`) e para onde seguir em seguida (`next`). Não remova esses campos para simplificar um documento: eles são o contrato de primeira experiência do site.

## Atualizar um tutorial

Edite primeiro o documento JSON correspondente e mantenha `sources` apontando para a documentação ou exemplo que sustenta cada afirmação. Quando um contrato de runtime mudar, atualize também a documentação normativa apropriada e, se houver mudança de suporte, `docs/compatibility.json` e `docs/COMPATIBILIDADE.md` na mesma alteração.

Antes de publicar, execute:

```bash
node docs/tutorials/verify.mjs
```

O verificador confirma que os quatro documentos existem, têm identificadores únicos, declaram os campos que o site precisa e usam layouts de seção conhecidos. Ele não substitui a bateria do loader: alterações em código, manifesto ou bridge continuam exigindo os testes Gradle aplicáveis.

## Site público e CI

O site React vive em [`site/`](../../site/) e é gerado em `docs/`, que é a fonte configurada do GitHub Pages deste repositório. O navegador lê os arquivos deste diretório diretamente da branch `main`, então uma alteração publicada em `tutorials/*.json` aparece sem recompilar o frontend. O workflow [`site-pages.yml`](../../.github/workflows/site-pages.yml) valida estes documentos em toda mudança relevante e reconstrói o artefato estático quando necessário.

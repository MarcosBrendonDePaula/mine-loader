# Runtimes por plataforma e versão

O MineLoader mantém um único `core/` e um adaptador por combinação de plataforma e versão do Minecraft. O core contém a API declarativa, os manifestos, o runtime Lua, a validação, a geração de recursos e as regras que não devem conhecer classes internas do Minecraft. Cada bridge em `runtimes/<plataforma>/<minecraft-version>/` traduz esse contrato para a API daquela versão.

```text
core/
runtimes/
├── fabric/
│   ├── 1.21.1/
│   └── 1.21.4/
└── neoforge/
    ├── 1.21.1/
    └── 1.21.4/
```

A versão do Minecraft, os mappings e o loader ficam no `build.gradle` do runtime correspondente. O `gradle.properties` raiz contém apenas valores comuns, como a versão do LuaJ e a versão do mod. Assim, atualizar `runtimes/fabric/1.21.4` não muda acidentalmente a dependência de `runtimes/fabric/1.21.1`.

## Tarefas agregadas

| Tarefa | O que verifica | O que não substitui |
|---|---|---|
| `./gradlew compileAllRuntimes` | Compila o core e os quatro bridges; nos runtimes Fabric também compila o source set client | Não prova o comportamento dentro de um mundo |
| `./gradlew buildAllRuntimes` | Compila e gera os jars do core e dos quatro bridges | Não prova o comportamento dentro de um mundo |
| `./gradlew testAllRuntimes` | Executa `core:test` e as tarefas `test` de cada runtime | Os runtimes ainda não têm testes JUnit próprios; os GameTests são separados |
| `./gradlew gameTestAllRuntimes` | Inicia Minecraft headless em cada combinação e executa os GameTests com os exemplos Lua sincronizados | Não verifica pixels do cliente, OBJ ou qualidade visual |
| `./gradlew checkAllRuntimes` | Junta `check` dos módulos e a matriz de GameTests | Não transforma capability experimental em compatível |

Também é possível executar uma combinação isolada, por exemplo:

```bash
./gradlew :runtimes:fabric:1.21.4:runGametest
./gradlew :runtimes:neoforge:1.21.4:runGameTestServer
```

Os GameTests usam a mesma pasta `examples/` do repositório. Cada tarefa sincroniza uma cópia limpa para o seu diretório de jogo antes de iniciar. O resultado esperado é `All 19 required tests passed :)`; o processo deve ser considerado falho se o log mostrar que os mods foram carregados como zero, mesmo que a tarefa Gradle não propague esse estado.

## O que a prova cobre

Os 19 GameTests de cada runtime verificam registro de blocos e entidades, propriedades declaradas, inventário de bloco, persistência NBT, automação, eventos, fila de ticks, leitura de redstone, tags, ovos de criação e herança entre mods. Na matriz atual, Fabric 1.21.1, Fabric 1.21.4, NeoForge 1.21.1 e NeoForge 1.21.4 executaram os 19 testes obrigatórios com sucesso. O core também possui a sua suíte JUnit independente.

Isso prova uma coisa importante para a tese do projeto: os mesmos exemplos declarativos e o mesmo core chegam ao Minecraft por quatro bridges diferentes. Não prova que toda capability visual ou todo detalhe de datapack é idêntico; esses itens ficam discriminados em `COMPATIBILIDADE.md`.

## Como adicionar outra versão

Primeiro deve-se copiar o bridge da versão mais próxima para uma nova pasta, preservar a fonte da versão anterior e fixar as coordenadas do novo Minecraft no próprio build script. Depois, o novo projeto deve ser incluído em `settings.gradle` e nas tarefas agregadas. O próximo passo é fazer o compilador revelar as mudanças de API; essas mudanças ficam no bridge, nunca no core, salvo quando o contrato declarativo realmente precisa evoluir.

A versão nova só deve ser marcada como compatível depois de compilar cliente e servidor, executar os testes do core, rodar os GameTests com os mesmos exemplos e rever cada capability que tenha sido degradada ou desativada. Uma compilação verde isolada é evidência de tradução de tipos, não de paridade de comportamento.

## Limites conhecidos da prova

Os GameTests são servidores headless. Eles não observam renderização de pixels, modelos OBJ na mão do jogador, skins de entidades, telas abertas no cliente ou efeitos visuais. Essas áreas têm de ser verificadas por compilação client, inspeção visual e testes manuais/automatizados de cliente quando a capability for importante. Por isso a matriz marca OBJ e renderização customizada de entidades como indisponíveis em 1.21.4, em vez de inferir compatibilidade a partir do servidor verde.

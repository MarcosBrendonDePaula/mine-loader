# Progresso — o que foi feito e o que falta

Acompanhamento de sessão longa. Diferente de `CHECKLIST_MODLOADER.md`, que é a régua externa do que
um modloader precisa ter, aqui fica **o estado do trabalho em curso**: o que fechou, o que está no
meio e o que vem a seguir.

**Regra:** ao fechar um item, risque-o na mesma mudança que o implementa. Um acompanhamento que
envelhece em silêncio é pior que nenhum — é a mesma razão de `COMPATIBILIDADE.md` existir.

Última revisão: quatro limites removidos — `events` sem `entrypoint`, `placement.facing`, a forma
que varia com o estado e o tique agendado por posição. Os quatro saíram da migração do Logistic
Pipes. Junto veio um conserto de ferramenta que valia mais do que parece: `run/mods-lua` era uma
cópia de `examples/`, e o servidor rodava contra scripts velhos dizendo que passou.

---

## Feito

### Espécies declaradas — o bestiário

- [x] **Espécie no manifesto** (`entities`), derivada de uma base do jogo. Base obrigatória e
      recusada quando desconhecida, com a lista das suportadas no erro.
- [x] **Registro nas duas plataformas**, com atributos, ovo de criação e desenhista.
- [x] **Saque próprio**, herdando a tabela da base **por referência** — copiar congelaria os drops
      na versão em que o mod foi escrito.
- [x] **Herança entre espécies**, inclusive de outro mod. Ordenação e detecção de ciclo no núcleo.
- [x] **Fase de registro** (`registration`): script próprio que roda antes de o jogo congelar os
      registros. Fecha a divergência de que o Lua carrega em momentos diferentes em cada plataforma.
- [x] **Tags de espécie**, geradas em `tags/entity_type`.
- [x] **Textura própria** (`texture`) e **forma própria** (`model`) — ossos e caixas em JSON,
      animados pela base.
- [x] **Comportamento declarado** (`ai`): vocabulário fechado de metas e alvos.
- [x] **Nascimento natural** (`spawn`) por bioma, peso, grupo, luz e altura.
- [x] Leitura de **bioma e luz** (`biome_at`, `light_at`).
- [x] Quatro **eventos de criatura** e **mover/empurrar** entidade.

### Limites removidos

- [x] **Inventário por slot** — `container_at` numera cada linha, e `insert_into` e `extract_from`
      aceitam o índice. É o que destrava o filtro por slot dos módulos de chassi, e a máquina com
      entrada e saída separadas.

- [x] **Tique agendado por posição** — `ctx.server.schedule_block(x, y, z, tiques)` e o evento
      `block_scheduled`, mapeado por `behavior.on_scheduled`. A fila é a **do jogo**, gravada com o
      chunk: o que estava a caminho volta na próxima sessão, em vez de sumir com o servidor. Não se
      repete sozinho — continuar é o script agendar o próximo —, e é recusado em bloco do jogo, onde
      o tique iria para o método vanilla e nada chegaria ao script.

- [x] **Uma pasta de mods só, de ponta a ponta.** `neoforge/run/mods-lua` já apontava para
      `run/mods-lua`, mas essa pasta compartilhada era uma **cópia** de `examples/`. Resultado: a
      bateria ficava verde contra um script velho, e o log dizia que passou — o pior resultado
      possível. A tarefa `linkExemplos` agora liga cada exemplo, e roda antes de `runServer` e
      `runClient`.

- [x] **Forma do bloco variando com o estado** — o cano que conecta. `shape.core`, `shape.arm` e
      `shape.connects_to`; o adaptador registra seis propriedades booleanas, calcula ao colocar e a
      cada mudança de vizinhança, e o pacote gerado escreve um blockstate `multipart` — sete peças,
      não sessenta e quatro variantes. **A colisão acompanha o desenho.**

- [x] **`events` sem `entrypoint` era aceito em silêncio.** Agora é recusado na carga, dizendo que
      o mapeamento aponta para funções de um script que não existe. Custou tempo real nesta sessão:
      o mod carregava, não fazia nada, e nenhuma linha de log explicava.
- [x] **`placement.facing` era declarado e ignorado.** Agora vale nas duas plataformas, com uma
      variante de blockstate por direção e o mesmo modelo girado.

### Interface

- [x] **Tela de mods no menu principal**: lista com filtro e páginas, ligar/desligar, instalar por
      link. Primeira tela do loader sem servidor do outro lado.
- [x] **Catálogo de mods** (`ModLoader.catalog`), que enxerga o desativado e o quebrado — coisa que
      `discover` não faz, e nem deve.
- [x] **Ícone do mod** (`icon`).

### Migração

- [x] **Logistic Pipes**: primeiro mod migrado. Cano, provedor e terminal, com o ciclo completo de
      pedido e entrega conferido no servidor dirigível.
- [x] Porte autônomo publicado em
      [`logistic-pipes-lua`](https://github.com/MarcosBrendonDePaula/logistic-pipes-lua), sob MMPL
      por reusar a arte do original.

---

## Em andamento

**Nada em aberto no código.** O tique agendado fechou e está verificado nos quatro níveis: 6 casos
no núcleo, um GameTest em **cada** plataforma que confere a fila do jogo e a recusa em bloco
vanilla, e `tique_agendado` na bateria — **33/33 nas duas**.

**Duas pendências de olho, não de código:**

- Nada da forma que varia com o estado foi visto no `runClient`. O blockstate está certo no arquivo
  e a propriedade está certa no mundo, mas se o braço aparece no lugar, só a tela diz.
- **O mecanismo do tique existe; o mod ainda não usa.** O porte do Logistic Pipes continua
  entregando na hora, e essa é a maior diferença visível para o original. Fazer o item viajar é
  reescrever a entrega do exemplo, e está na tarefa do porte — não no limite, que já saiu.

---

## O que falta

### Lacunas que a migração do Logistic Pipes encontrou

Estão em `API_GAPS.md`, em ordem de quanto doem:

1. ~~**Forma por estado.**~~ **Fechado.**
2. ~~**Tique agendado por posição.**~~ **Fechado.** Falta o exemplo usar: o item ainda some de um
   baú e aparece no outro.
3. ~~**Ler inventário por slot.**~~ **Fechado.** `container_at` numera cada linha, e `insert_into`
   e `extract_from` aceitam um slot opcional.
4. **Evento de bloco quebrado com o inventário íntegro.** A rede só se refaz quando alguém abre a
   tela.
5. ~~**`events` sem `entrypoint` é aceito em silêncio.**~~ **Fechado.**

### Nível 7 do checklist, o que sobrou

- **Animação própria.** A forma declarada se move com a animação da base: um bicho de quatro patas
  derivado de um bípede anda como bípede. O caminho está mapeado em `API_GAPS.md` — o jogo tem
  keyframes desde a 1.19.4, e mapeia quase um-para-um para JSON. Decisão que vale lembrar: animação
  própria e animação da base são **exclusivas**.
- **Hierarquia de ossos.** O formato é plano porque é assim que as classes de modelo do jogo
  procuram as peças.

### Mod migrado, o que falta portar

- `supplier` — mantém um baú abastecido
- `satellite` — endereço nomeado na rede
- `crafting` — fabrica sob demanda
- `chassis` + módulos — o que faltava (ler inventário por slot) já existe

### Outros

- **Tela de mods no NeoForge.** Existe só no cliente Fabric; está na matriz como sim/não.
- **Escala da criatura na tela.** `minecraft:generic.scale` registra sem aviso, mas ainda não foi
  conferido visualmente.
- **UI por HTML e CSS.** Estudo em `UI_HTML_DESIGN.md`. A tela do menu principal reforçou o
  argumento: montar descrição em Java virou concatenação de JSON à mão, e o primeiro defeito foi
  cor sem alfa — que passa despercebida porque o número *parece* branco.

---

## As tarefas, se a sessão retomar

O acompanhamento fino vive na lista de tarefas da sessão, com passo a passo e verificação de cada
uma. Este documento é o mapa; elas são o roteiro.

| # | Tarefa | Estado |
|---|---|---|
| 14 | ~~Forma do bloco variando com o estado~~ | **fechado** |
| 15 | ~~Recusar `events` sem `entrypoint`~~ | **fechado** |
| 20 | ~~Aplicar `placement.facing`~~ | **fechado** |
| 16 | ~~Tique agendado por posição~~ | **fechado** |
| 17 | ~~Ler inventário por slot~~ | **fechado** |
| 18 | Evento de bloco quebrado com o inventário íntegro | a fazer |
| 19 | Portar os canos que faltam, e fazer o item viajar pelo cano | a fazer |
| 12 | UI por HTML e CSS | adiado por decisão |

## Como retomar

```bash
./gradlew build                       # tudo, nas duas plataformas
./gradlew runGametest                 # GameTests do Fabric
./gradlew :neoforge:runGameTestServer # os mesmos, no NeoForge

tools/servidor-dirigivel.sh iniciar   # servidor sem cliente
tools/servidor-dirigivel.sh cmd "mod autoteste"
```

Os dois pontos em `:runClient` e `:runServer` não são enfeite — sem eles sobem as duas plataformas
ao mesmo tempo, escrevendo no mesmo log. Custou tempo nesta sessão.

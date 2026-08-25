# Progresso — o que foi feito e o que falta

Acompanhamento de sessão longa. Diferente de `CHECKLIST_MODLOADER.md`, que é a régua externa do que
um modloader precisa ter, aqui fica **o estado do trabalho em curso**: o que fechou, o que está no
meio e o que vem a seguir.

**Regra:** ao fechar um item, risque-o na mesma mudança que o implementa. Um acompanhamento que
envelhece em silêncio é pior que nenhum — é a mesma razão de `COMPATIBILIDADE.md` existir.

Última revisão: bestiário declarativo completo, tela de mods no menu principal, e o primeiro mod
migrado rodando.

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

- [ ] **Forma do bloco variando com o estado** — o cano que conecta. É a lacuna mais estruturante
      que a migração achou: `shape` é declarado uma vez e não varia, então os canos ficam sendo
      peças soltas encostadas. Desbloqueia cerca, muro, vidraça e grade de uma vez.

      Desenho: o bloco declara núcleo, braço e a quem se conecta; o adaptador registra seis
      propriedades booleanas e as calcula ao colocar e a cada mudança de vizinhança; o pacote gerado
      escreve um blockstate `multipart` — sete modelos, não sessenta e quatro. A colisão precisa
      acompanhar, senão o jogador atravessa o braço.

---

## O que falta

### Lacunas que a migração do Logistic Pipes encontrou

Estão em `API_GAPS.md`, em ordem de quanto doem:

1. **Forma por estado** — em andamento, acima.
2. **Tique agendado por posição.** Não existe "volte a me chamar nesta posição daqui a N tiques".
   Hoje o item some de um baú e aparece no outro, sem viagem visível — a maior diferença para o
   original.
3. **Ler inventário por slot.** `container_at` soma por item, o que basta para um estoque e impede
   reproduzir os filtros dos módulos de chassi.
4. **Evento de bloco quebrado com o inventário íntegro.** A rede só se refaz quando alguém abre a
   tela.
5. **`events` sem `entrypoint` é aceito em silêncio.** O loader registra os blocos, não executa
   script nenhum, e o mapeamento aponta para funções que não podem existir. Barato de fechar, e o
   modo de falhar mais caro que existe — parece que o loader está quebrado.

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
- `chassis` + módulos — dependem de ler inventário por slot

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
| 14 | Forma do bloco variando com o estado — o cano que conecta | em andamento |
| 15 | Recusar manifesto que declara `events` sem `entrypoint` | a fazer — o mais barato |
| 16 | Tique agendado por posição | a fazer |
| 17 | Ler inventário por slot | a fazer |
| 18 | Evento de bloco quebrado com o inventário íntegro | a fazer |
| 19 | Portar os canos que faltam do Logistic Pipes | a fazer |
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

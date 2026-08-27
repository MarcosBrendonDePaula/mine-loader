# Direcção visual — MineLoader

## Três abordagens consideradas

### 1. Caderno de Campo
**Very Brief Intro:** Uma página com espírito de diário de exploração: papel mineral, marcações manuscritas controladas e blocos como achados catalogados. Passa a sensação de ferramenta criada por modders para modders.

**Probability:** 0.04

### 2. Planta de Mineração
**Very Brief Intro:** Um manual técnico de engenharia de jogo, combinando desenho editorial suíço, diagramas de especificação e volumes voxelizados. A página deve parecer uma estação de controlo que torna a complexidade entre versões legível.

**Probability:** 0.08

### 3. Terminal de Basalto
**Very Brief Intro:** Uma interface escura e industrial inspirada em uma sala de servidores dentro de uma mina, com placas de basalto e indicadores de cobre. A atmosfera privilegia profundidade e confiabilidade sem recorrer a estética cyberpunk.

**Probability:** 0.03

## Abordagem escolhida — Planta de Mineração

### Design Movement
Editorial suíço aplicado a um manual técnico de engenharia, com materialidade voxel e referências discretas a mapas de mineração. Não é uma interface de jogo: é o sistema de construção que deixa o jogo portátil.

### Core Principles
1. **Prova antes de promessa:** números de compatibilidade, versões e contratos aparecem como elementos de primeira classe.
2. **Estrutura assimétrica:** uma coluna de informação firme encontra mapas, blocos e linhas técnicas em escalas diferentes.
3. **Matéria, não ornamento:** quadriculado de planta, textura de papel mineral e blocos isométricos servem para explicar portabilidade.
4. **Contraste de ferramenta:** tipografia editorial grande para ideias; monoespaçada precisa para tudo que é verificável.

### Color Philosophy
O fundo é branco mineral quase quente, como documentação impressa. Grafite azulado e carvão sustentam legibilidade; o **Cobre de Fornalha** marca ação, versões e pontos de confiança. Verde de musgo é reservado para estados comprovados, nunca usado como decoração. A hero usa uma imagem escura de basalto para criar contraste e dar peso visual ao manifesto.

### Layout Paradigm
A navegação e a primeira dobra funcionam como uma prancha técnica vertical: uma coluna estreita de metadados à esquerda, um título que invade duas escalas e uma imagem de sistema à direita. As secções seguintes alternam faixas de documentação, painel de terminal e blocos de evidência, evitando uma grelha centralizada repetitiva.

### Signature Elements
1. Um retículo de coordenadas e linhas de medição discretas nas margens.
2. Cartões de “prova” com cantos recortados e etiqueta de versão monoespaçada.
3. Cubos/volumes voxelizados de basalto, cobre e musgo como metáfora visual do bridge.

### Interaction Philosophy
Os links parecem referências de manual: sublinham-se por crescimento de linha. Os cartões sobem apenas dois pixels e revelam o identificador técnico. O botão principal parece uma etiqueta de versão que encaixa no conteúdo.

### Animation
Entradas em cascata de 40–70 ms, usando apenas opacidade e transformações de até 10 px. Linhas de retículo fazem um pequeno draw-in ao carregar; os blocos isométricos deslocam-se muito suavemente em hover. Tudo é desativado com `prefers-reduced-motion`.

### Typography System
**Space Grotesk** para títulos e números grandes: geométrica, pragmática e firme. **IBM Plex Mono** para código, labels, versões e metadados. Hierarquia: display em caixa alta moderada, corpo em Space Grotesk 400/500 e dados em Plex Mono 500 com espaçamento aumentado.

### Brand Essence
**MineLoader é o contrato que permite que mods Lua atravessem versões e plataformas de Minecraft Java sem reescrita.** Personalidade: rigoroso, acessível, construtivo.

### Brand Voice
Direta, factual e confiante; evita slogans vazios e explica o que foi provado.

Exemplos: “Escreve uma vez. Mantém o contrato.” e “Quatro runtimes. Um só mod.”

### Wordmark & Logo
Um monograma `M` construído por três blocos isométricos encaixados, com um canal central que sugere uma ponte. O wordmark usa Space Grotesk em caixa alta, com o `O` aberto como uma porta de bloco.

### Signature Brand Color
**Cobre de Fornalha — `#D8842B`**. É usado como a marca de atividade, calor de compilação e junção entre bridges.

## Style Decisions

- O wordmark deve ser tratado como assinatura visual: monograma em bloco isométrico amplo, `M` marcado e `O` aberto/estruturado no cabeçalho e no rodapé.
- **Cobre de Fornalha** fica reservado para ações, versões, junções de bridge, números de prova e marcadores comprovados; ênfase em títulos usa sublinhado técnico, não preenchimento decorativo.
- Cada bloco principal precisa de pelo menos um sinal de prancha técnica: identificador, coordenada, tag de contrato, régua, versão ou marca de validação.
- Cartões não devem parecer features genéricas: usam cantos recortados, códigos de capability, marcadores de matriz e pequenos volumes de material como evidência visual.

- Cada tutorial usa uma prova visual específica dentro da linguagem de blueprint: bloco em vista isométrica, matriz de registry, grade de container ou fluxo de dados Lua.
- A marca MineLoader aparece como assinatura construída no trilho documental, combinando o símbolo isométrico e o “O” aberto/estruturado.
- Painéis de código são sempre contrabalançados por faixas de capability, coordenadas, validação ou diagramas de material próximos.

# Instalar mods por link

Como um mod entra no loader sem sair do jogo, o que passa a valer na hora, e o que só vale depois de
reiniciar.

## O problema

O gerenciador de mods da plataforma — o Mod Menu do Fabric, a lista do NeoForge — enxerga **um** mod:
o próprio loader. Os mods Lua vivem dentro dele e não aparecem lá. Quem joga não tem como saber o
que está instalado, em que versão, nem por que algo parou de funcionar. E acrescentar um mod
significava fechar o jogo, achar a pasta e copiar arquivos.

`examples/gerenciador` resolve os dois: lista o que está instalado e instala o que falta, usando a
API do próprio loader. Não há nada de privilegiado nele — é o mesmo que qualquer mod pode fazer.

## O fluxo

Instalar acontece em **dois passos**, e não em um.

```
ctx.server.install_preview(url)   -- baixa e valida, sem gravar nada
ctx.server.install_confirm(id)    -- grava o que a prévia mostrou
```

Um passo só seria mais simples de usar e pior de confiar. Quem cola um link não tem como saber o que
vem nele, e **a lista de permissões é a única coisa que responde isso antes de o código rodar**. A
prévia devolve exatamente isso:

| Campo | O que é |
|---|---|
| `id`, `name`, `version`, `description` | o que o manifesto declara |
| `authors` | quem assina |
| `permissions` | **o que aquele código vai poder fazer** |
| `blocks`, `items` | quanto conteúdo ele registra |
| `replaces` | se já existe um mod com esse id |
| `needs_restart` | se tem conteúdo que só entra na próxima inicialização |

`install_confirm` grava **o texto que a prévia validou**, e não uma busca nova. Entre ver as
permissões e concordar com elas, o endereço poderia passar a servir outro conteúdo — e quem
concordou teria concordado com o que leu, não com o que chegou depois.

## O que precisa reiniciar, e o que não precisa

Esta é a parte que mais confunde, e a resposta depende do que o mod traz.

| O mod traz | Vale quando |
|---|---|
| Comandos, eventos, menus, telas, processos | **na hora** |
| Blocos e itens | só ao reiniciar o jogo |

**Por quê.** O registro do Minecraft fecha durante a inicialização. Um bloco declarado depois disso
simplesmente não existe — não é uma limitação do loader, é de onde ele mora. O runtime Lua, por
outro lado, é do loader inteiro, e aceita um script novo a qualquer momento.

Um mod com conteúdo é carregado assim mesmo, e não deixado de lado: os scripts dele passam a valer
imediatamente, e só os blocos ficam para depois. A resposta de `install_confirm` diz as duas coisas
separadamente:

```lua
local resultado = ctx.server.install_confirm(previa.id)
resultado.active        -- os scripts já estão rodando
resultado.needs_restart -- ainda falta reiniciar para o conteúdo aparecer
```

**Um detalhe que não é óbvio:** os nomes de comando são literais na árvore que o jogo publica,
montada uma vez na inicialização. Um mod instalado agora registra o comando no runtime e ele ficaria
invisível para quem digita. O loader republica a árvore e a reenvia aos jogadores — sem isso, o
cliente recusaria o comando antes mesmo de mandá-lo ao servidor.

## O que vai para o disco

Só o manifesto e o `entrypoint`. Texturas, modelos e scripts de bloco continuam remotos, resolvidos
pelo caminho que o loader já tinha para mods publicados na web — é o que `remote_base` sempre fez. O
`remote_base` sai do próprio endereço, a menos que o manifesto declare o seu.

**O `entrypoint` é a exceção, e de propósito.** Uma textura buscada a cada partida no máximo muda de
aparência; um script buscado a cada partida pode mudar de *comportamento* depois de alguém ter lido
as permissões e concordado. Gravá-lo na instalação é o que faz "o que eu aprovei é o que roda"
continuar verdade amanhã.

## As duas chaves

Instalar código é a operação mais poderosa do loader, então nada disso acontece por padrão. Há duas
chaves, guardadas em `lua-loader/instalacao.json`, e as duas nascem **desligadas**.

### `allow_api_install` — um mod pode instalar outro

É o caso do mod modular: alguém publica um conjunto em pedaços — um núcleo mais módulos opcionais — e
oferece dentro do jogo a lista do que existe, para quem joga escolher o que instalar.

Com ela desligada, `install_preview` e `install_confirm` recusam com o motivo. O gerenciador mostra o
botão de liberar para quem é operador.

### `auto_install_dependencies` — o loader busca o que falta

Um mod declara o que precisa e de onde vem:

```json
{
  "dependencies":        { "biblioteca": "1.0.0" },
  "dependency_sources":  { "biblioteca": "https://exemplo/biblioteca/mod.json" }
}
```

São dois campos e não um porque respondem coisas diferentes. `dependencies` diz **o que** o mod
precisa e é o contrato — vale sem endereço nenhum. `dependency_sources` diz apenas **onde achar**, e
um mod distribuído de outro jeito pode não ter uma.

Com a chave desligada, uma dependência ausente continua sendo o que sempre foi: erro no log e o mod
não carrega. Ligada, o loader busca antes do registro — que é a única janela em que o conteúdo da
dependência ainda pode entrar no jogo.

A busca é conservadora de propósito: só o que o mod declarou, sem descoberta automática nem
repositório central, e em no máximo quatro rodadas — uma dependência pode trazer as suas, e sem teto
uma cadeia mal declarada instalaria em laço.

## As três portas

A API de instalação só responde quando **todas** estão abertas:

1. o mod declarou a permissão `server.install`;
2. quem administra o servidor ligou `allow_api_install`;
3. quem está agindo é **operador** (nível 2).

A permissão sozinha não basta, e isso é deliberado. Um mod declara as próprias permissões, então ela
diz "este mod pretende instalar outros" — uma informação útil, que aparece na tela do gerenciador —
mas não autoriza nada. Quem autoriza é o servidor, e o operador.

Trocar a chave também exige ser operador, e não exige `server.install`: mudar a política é mais forte
que usá-la.

## Recusas

Todas com o motivo, porque um "não" mudo faria quem administra procurar um defeito onde há uma
escolha:

| Situação | O que acontece |
|---|---|
| Endereço não é `https://` | recusa; texto em claro não tem integridade nenhuma |
| Manifesto acima de 256 KiB | recusa |
| Manifesto que o loader recusaria ao carregar | recusa, com o motivo no log |
| `install_confirm` sem prévia | recusa; é o que garante que alguém leu as permissões |
| A origem de uma dependência entrega outro mod | nada é instalado |
| Um mod tentando desinstalar a si mesmo | recusa |

A validação da prévia é a **mesma** da carga normal: o manifesto passa pelo `ModLoader` de verdade.
Uma validação própria divergiria da real na primeira regra nova, e "instalou" deixaria de querer
dizer "carrega".

## Desinstalar

`ctx.server.uninstall(id)` apaga a pasta do mod e devolve se ela existia. Passa pelas mesmas três
portas. O conteúdo registrado continua no jogo até reiniciar, pelo mesmo motivo de sempre.

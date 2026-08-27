# Revisão de primeira experiência — 2026-08-27

## Observações da publicação atual

A página **Primeiros passos** já explica a pasta `run/mods-lua/` e separa manifesto de Lua, mas começa depois de uma lacuna importante: não informa onde o leitor obtém e instala o MineLoader, como escolhe um runtime mantido, nem qual comando ou perfil deve iniciar. O segundo passo também diz para “iniciar o runtime escolhido” sem apresentar uma ação concreta e reproduzível.

A página **Como progredir** é uma boa referência para quem já publicou conteúdo, porém abre com conceitos de contrato, capabilities e composição cedo demais para alguém que ainda não conseguiu carregar um pacote. Ela deve vir depois de uma primeira vitória comprovável, não antes dela.

O trilho lateral contém mais links do que a altura comum de viewport. Ele precisa ser uma área com altura controlada e `overflow-y: auto` no desktop, sem perder o cabeçalho, a marca ou o indicador de compatibilidade. Em mobile, a navegação deve continuar expandir sem prender o conteúdo.

## Critério de reescrita

Todo guia de início precisa responder, na ordem: **o que preciso ter**, **qual arquivo crio**, **onde salvo**, **como executo**, **o que devo enxergar se deu certo** e **qual guia faço em seguida**. Conceitos como capabilities, dependencies e bridges entram apenas quando a primeira ação torna-os necessários.

## Aplicação nesta revisão

O percurso foi reordenado para começar com a primeira mensagem em log, seguir por bloco ou item, depois por código Lua e por fim por UI. Os quatro tutoriais JSON passaram a carregar uma seção `beginner` obrigatória, com pré-requisito, arquivos, resultado esperado e continuação. A barra lateral ganhou rolagem própria no desktop e altura limitada com rolagem no menu mobile.

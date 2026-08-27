/**
 * Planta de Mineração: guia de entrada progressivo, com cada passo medido e
 * código copiável que parte do menor mod real e leva à lógica Lua.
 */
import { ArrowDown, Check, FolderTree, Play, ShieldCheck } from "lucide-react";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";

const smallestMod = `{
  "schema": 1,
  "id": "meu_mod",
  "name": "Meu Mod",
  "version": "1.0.0",
  "blocks": [
    { "id": "pedra_azul", "name": "Pedra Azul" }
  ]
}`;

const entrypoint = `-- main.lua
mod.on("server_started", function(ctx)
  ctx.log.info("Meu mod está ativo")
end)`;

export default function GettingStarted() {
  return (
    <DocsShell
      index="01"
      eyebrow="Primeiros passos"
      title={<>Do zero a um bloco<br /><em>sem escrever Java.</em></>}
      summary="O primeiro mod é uma pasta com um manifesto. Comece pelo conteúdo declarativo, confirme a carga e só então acrescente Lua para comportamento."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>01.1</span> O menor pacote</div>
        <div className="doc-two-col">
          <p className="doc-lead">Um mod é uma pasta dentro de <code>run/mods-lua/</code>. O nome da pasta e o campo <code>id</code> devem representar o mesmo mod.</p>
          <div className="doc-copy"><p>Para registrar um bloco jogável, você precisa apenas de <code>mod.json</code>. O loader gera a ponte de conteúdo para cada plataforma no momento apropriado de registro.</p><p>Não é necessário criar projeto Java, usar mappings ou instalar uma API diferente para cada runtime.</p></div>
        </div>
        <div className="doc-path"><FolderTree size={19} /><code>run/mods-lua/meu_mod/mod.json</code><span>RAIZ DO PACOTE</span></div>
      </section>

      <section className="doc-section numbered-steps">
        <div className="doc-section-label"><span>01.2</span> Sequência recomendada</div>
        <article className="doc-step"><div className="step-no">01</div><div><h2>Crie o manifesto</h2><p>Salve o arquivo abaixo como <code>run/mods-lua/meu_mod/mod.json</code>. O <code>schema</code> identifica o formato, enquanto <code>id</code>, <code>name</code> e <code>version</code> dão identidade ao pacote.</p></div></article>
        <DocCode language="mod.json">{smallestMod}</DocCode>
        <article className="doc-step"><div className="step-no">02</div><div><h2>Inicie o runtime escolhido</h2><p>Inicie um dos runtimes mantidos. No carregamento, o MineLoader descobre os pacotes, valida a estrutura e registra o bloco antes de o jogo congelar o registry.</p></div><Play size={22} /></article>
        <article className="doc-step"><div className="step-no">03</div><div><h2>Confirme o diagnóstico</h2><p>O log deve identificar o mod carregado. Um id inválido, uma chave desconhecida ou uma estrutura incompatível interrompe apenas o pacote em erro com uma mensagem explícita.</p></div><Check size={22} /></article>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>01.3</span> Acrescentar lógica</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Quando o conteúdo existir, declare um <code>entrypoint</code> e coloque a reação em Lua.</p>
          <div className="doc-copy"><p>O script não recebe um servidor Java, entidades ou stacks. Ele trabalha com o vocabulário seguro do loader e com contextos de dados simples.</p><p>Use Lua para eventos globais, comandos, tarefas, menus e regras de gameplay; use o manifesto para o que precisa estar definido antes do registro.</p></div>
        </div>
        <DocCode language="main.lua">{entrypoint}</DocCode>
        <DocCallout title="Separe declaração de comportamento" tone="proof">Um bloco, item ou entidade que precisa existir na carga vai no manifesto. Uma decisão que depende de evento, contexto ou estado do mod vai para Lua.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>01.4</span> Antes de crescer</div>
        <DocTable>
          <thead><tr><th>Você quer…</th><th>Próximo lugar</th><th>Por quê</th></tr></thead>
          <tbody>
            <tr><td>Declarar mais conteúdo</td><td><code>mod.json</code></td><td>Itens, blocos, receitas, tags e entidades pertencem ao registro.</td></tr>
            <tr><td>Agir quando algo acontece</td><td><code>main.lua</code></td><td>Eventos e callbacks são comportamento que pode depender do contexto.</td></tr>
            <tr><td>Ler ou alterar mundo/jogador</td><td>Permissão + capability</td><td>O contrato torna o alcance do mod explícito antes de executar.</td></tr>
            <tr><td>Dividir arquivos grandes</td><td><code>$import</code> e <code>mod.import</code></td><td>Manifesto e Lua podem crescer sem perder a raiz declarativa.</td></tr>
          </tbody>
        </DocTable>
        <DocCallout title="O que não fazer" tone="warning">Não tente importar Fabric, NeoForge ou classes do Minecraft a partir do Lua. Esses detalhes pertencem aos bridges e não são parte do contrato do mod.</DocCallout>
      </section>
    </DocsShell>
  );
}

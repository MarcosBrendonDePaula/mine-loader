/**
 * Planta de Mineração: guia de entrada como uma primeira bancada de trabalho.
 * A pessoa prepara o runtime, cria dois arquivos, inicia e vê um sinal claro
 * antes de encontrar contratos ou superfícies mais avançadas.
 */
import { Check, FileCode2, FolderTree, Play, TerminalSquare } from "lucide-react";
import { Link } from "wouter";
import { DocCallout, DocCode, DocsShell } from "@/components/DocsShell";

const windowsWrapper = ".\\";

const startServer = `# Linux e macOS
git clone https://github.com/MarcosBrendonDePaula/mine-loader.git
cd mine-loader
./gradlew runServer

# Windows PowerShell
git clone https://github.com/MarcosBrendonDePaula/mine-loader.git
cd mine-loader
${windowsWrapper}gradlew.bat runServer`;

const firstManifest = `{
  "schema": 1,
  "id": "meu_mod",
  "name": "Meu primeiro mod",
  "version": "0.1.0",
  "entrypoint": "main.lua",
  "events": {
    "server_started": "on_server_started"
  }
}`;

const firstLua = `function on_server_started(ctx)
  ctx.log.info("Meu primeiro mod carregou.")
end

return {
  on_server_started = on_server_started
}`;

export default function GettingStarted() {
  return (
    <DocsShell
      index="01"
      eyebrow="Primeiros passos"
      title={<>Crie seu primeiro mod<br /><em>antes de aprender o resto.</em></>}
      summary="Você não precisa conhecer Java, Fabric ou NeoForge para começar. Primeiro vamos criar dois arquivos, iniciar o servidor de desenvolvimento e confirmar uma mensagem no log."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>01.1</span> O que você precisa</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Instale o Java 21 e tenha um editor de texto. Só isso para criar o primeiro mod.</p>
          <div className="doc-copy"><p>O repositório já traz o Gradle Wrapper. Não instale Gradle, Fabric API ou NeoForge separadamente para seguir este começo.</p><p>Use o servidor de desenvolvimento primeiro: ele carrega o MineLoader e lê os mods da pasta <code>run/mods-lua/</code>.</p></div>
        </div>
        <div className="first-run-map" aria-label="Fluxo da primeira execução">
          <div className="first-run-map-head"><span>MAPA // PRIMEIRA EXECUÇÃO</span><i /><span>01 → 02 → 03</span></div>
          <div className="first-run-map-grid"><div><span>01</span><strong>REPOSITÓRIO</strong><p>Java 21 + Gradle Wrapper</p></div><div><span>02</span><strong>PASTA DO MOD</strong><p><code>run/mods-lua/meu_mod</code></p></div><div><span>03</span><strong>PROVA</strong><p>Uma linha no log</p></div></div>
        </div>
        <DocCode language="terminal">{startServer}</DocCode>
        <DocCallout title="Primeira inicialização demora mais" tone="proof">Na primeira vez, o Gradle baixa dependências e prepara o runtime. Espere o servidor terminar de iniciar antes de criar o pacote abaixo. No Windows, use o comando equivalente mostrado no bloco.</DocCallout>
      </section>

      <section className="doc-section numbered-steps">
        <div className="doc-section-label"><span>01.2</span> Crie os dois arquivos</div>
        <article className="doc-step"><div className="step-no">01</div><div><h2>Crie a pasta do seu mod</h2><p>Dentro da pasta do repositório, crie <code>run/mods-lua/meu_mod/</code>. O nome da pasta e o campo <code>id</code> abaixo devem falar do mesmo mod; use minúsculas, números e sublinhado no id.</p></div><FolderTree size={22} /></article>
        <article className="doc-step"><div className="step-no">02</div><div><h2>Salve o manifesto</h2><p>Crie <code>run/mods-lua/meu_mod/mod.json</code> e cole o conteúdo abaixo. Ele diz qual arquivo Lua abrir e qual função chamar quando o servidor termina de iniciar.</p></div><FileCode2 size={22} /></article>
        <DocCode language="run/mods-lua/meu_mod/mod.json">{firstManifest}</DocCode>
        <article className="doc-step"><div className="step-no">03</div><div><h2>Salve o primeiro código Lua</h2><p>Na mesma pasta, crie <code>main.lua</code>. A função escreve apenas uma linha no log: este é o menor sinal de que o manifesto encontrou e executou o seu código.</p></div><TerminalSquare size={22} /></article>
        <DocCode language="run/mods-lua/meu_mod/main.lua">{firstLua}</DocCode>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>01.3</span> Rode e confira</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Pare e inicie <code>./gradlew runServer</code> novamente. Agora procure uma linha com <code>Meu primeiro mod carregou.</code>.</p>
          <div className="doc-copy"><p>Se a linha aparecer, sua pasta, o JSON, o entrypoint e o callback estão conectados. Não pule esta prova: ela torna qualquer problema futuro menor e mais fácil de localizar.</p><p>Se o mod não carregar, leia o erro citado no log. O loader recusa somente o pacote com erro e explica se foi id, chave, tipo de valor ou arquivo ausente.</p></div>
        </div>
        <DocCallout title="Primeira vitória confirmada" tone="proof">Você acabou de criar um mod que o MineLoader encontrou e executou. Só agora escolha o que ele vai fazer no jogo.</DocCallout>
      </section>

      <section className="doc-section first-next-section">
        <div className="doc-section-label"><span>01.4</span> Escolha uma primeira mecânica</div>
        <div className="first-next-grid">
          <Link href="/docs/tutoriais/bloco"><span>T1 · SEM LUA NOVO</span><h2>Quero colocar um bloco</h2><p>Comece com um bloco de id e nome. Depois adicione física, render e loot.</p></Link>
          <Link href="/docs/tutoriais/item"><span>T2 · SEM LUA NOVO</span><h2>Quero registrar um item</h2><p>Faça um item básico antes de tentar comida, efeitos ou combustível.</p></Link>
          <Link href="/docs/tutoriais/lua"><span>T4 · PRÓXIMO CÓDIGO</span><h2>Quero reagir a um comando</h2><p>Crie uma resposta curta para um jogador e entenda contextos Lua.</p></Link>
        </div>
        <p className="first-next-note">Menus/UI vêm depois que o <code>main.lua</code> já estiver claro. O tutorial de UI parte desse mesmo pacote e abre uma grade sem mexer no inventário.</p>
      </section>
    </DocsShell>
  );
}

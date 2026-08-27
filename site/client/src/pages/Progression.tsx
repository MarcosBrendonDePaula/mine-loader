/**
 * Planta de Mineração: trilha de maturidade após a primeira execução. Ela evita
 * apresentar capabilities e composição antes de a pessoa ter um mod visível.
 */
import { Braces, Cable, Layers3, PanelsTopLeft, ShieldCheck, TestTube2 } from "lucide-react";
import { Link } from "wouter";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";

const requirementSample = `{
  "permissions": ["world.read", "player.read"],
  "requires": {
    "domains": { "world": "1.0.0" },
    "capabilities": {
      "world.block_state.read": "1.0.0",
      "player.effects.read": "1.0.0"
    }
  }
}`;

const importSample = `-- main.lua
local mensagens = mod.import("lib/mensagens.lua")

function on_server_started(ctx)
  mensagens.aviso(ctx, "Mod carregado")
end

return { on_server_started = on_server_started }`;

const stages = [
  { no: "01", title: "Primeira prova", icon: TestTube2, text: "Você já viu seu mod escrever no log. Mantenha essa versão pequena e funcionando antes de adicionar conteúdo." },
  { no: "02", title: "Uma mecânica", icon: Layers3, text: "Escolha um único tutorial: bloco ou item. Registre conteúdo pelo manifesto sem misturar lógica nova." },
  { no: "03", title: "Uma reação", icon: Braces, text: "Quando o conteúdo estiver estável, acrescente um comando ou evento Lua com uma ação fácil de conferir." },
  { no: "04", title: "Uma interação", icon: PanelsTopLeft, text: "Use menu/UI para uma grade simples. Só depois conecte inventário, compra ou estado persistente." },
  { no: "05", title: "Contrato", icon: ShieldCheck, text: "Peça permissões e capabilities apenas quando sua próxima chamada realmente precisar delas." },
];

export default function Progression() {
  return (
    <DocsShell
      index="02"
      eyebrow="Como progredir"
      title={<>Faça uma coisa.<br /><em>Confirme. Depois cresça.</em></>}
      summary="Esta página começa depois de Primeiros passos. A regra é simples: cada avanço deve gerar um resultado que você consegue conferir antes de adicionar a próxima camada."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>02.1</span> Um caminho que não atropela</div>
        <div className="progress-track">
          {stages.map((stage, index) => {
            const Icon = stage.icon;
            return <article className="progress-stage" key={stage.no}><div className="progress-marker"><span>{stage.no}</span><Icon size={20} /></div><h2>{stage.title}</h2><p>{stage.text}</p>{index < stages.length - 1 && <span className="progress-arrow">→</span>}</article>;
          })}
        </div>
        <DocCallout title="Se ainda não viu a primeira mensagem no log" tone="warning">Volte para <Link href="/docs/primeiros-passos">Primeiros passos</Link>. Capabilities, bibliotecas e menus não ajudam a descobrir por que a pasta ou o manifesto básico ainda não carregou.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>02.2</span> O que fazer agora</div>
        <div className="first-next-grid">
          <Link href="/docs/tutoriais/bloco"><span>T1 · MANIFESTO</span><h2>Bloco</h2><p>Para uma peça sólida no mundo. Comece com id e nome; aprofunde material e loot depois.</p></Link>
          <Link href="/docs/tutoriais/item"><span>T2 · MANIFESTO</span><h2>Item</h2><p>Para ingrediente ou coleção. Deixe comida e combustível para a segunda execução.</p></Link>
          <Link href="/docs/tutoriais/lua"><span>T4 · COMPORTAMENTO</span><h2>Lua</h2><p>Para comando e regras. Comece por uma resposta simples antes de usar eventos canceláveis.</p></Link>
        </div>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>02.3</span> Peça contrato somente quando aparecer a necessidade</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Permissão é a autorização do seu pacote. Capability é a versão do contrato que o runtime precisa oferecer.</p>
          <div className="doc-copy"><p>Você não precisa copiar este bloco no primeiro mod. Acrescente-o quando o código for ler mundo, acessar jogador ou usar uma API que o tutorial marca como capability.</p><p>As versões em <code>requires</code> pertencem ao MineLoader, não à versão do Minecraft. O runtime verifica isso antes de abrir o Lua.</p></div>
        </div>
        <DocCode language="mod.json — exemplo para quando precisar">{requirementSample}</DocCode>
        <DocCallout title="Sem fallback silencioso" tone="proof">Quando uma capability não existe, o loader recusa o pacote antes de executar seu código. Isso é melhor que criar um mod que funciona em uma versão e falha sem explicação em outra.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>02.4</span> Divida o arquivo só quando ele pedir</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Um único <code>main.lua</code> é o lugar certo para aprender. Quando ele ficar difícil de ler, mova uma responsabilidade por vez.</p>
          <div className="doc-copy"><p>Use <code>mod.import</code> para arquivos do próprio pacote. Use <code>mod.require</code> apenas quando outro mod publicar uma biblioteca e ela estiver declarada em <code>dependencies</code>.</p><p>Comece dividindo textos, tabelas ou funções repetidas. Não crie uma árvore de arquivos antes de existir uma segunda responsabilidade real.</p></div>
        </div>
        <DocCode language="main.lua">{importSample}</DocCode>
        <DocTable>
          <thead><tr><th>Quando você precisa…</th><th>Use</th><th>Resultado</th></tr></thead>
          <tbody>
            <tr><td>Reaproveitar uma função do seu mod</td><td><code>mod.import("lib/x.lua")</code></td><td>O arquivo permanece preso ao pacote e ciclos são recusados.</td></tr>
            <tr><td>Consumir uma biblioteca de outro mod</td><td><code>mod.require("outro_mod")</code></td><td>A dependency declara a ordem e a API pública é resolvida sob demanda.</td></tr>
            <tr><td>Separar blocos, itens ou recursos no manifesto</td><td><code>$import</code></td><td>O JSON fica menor sem perder uma raiz declarativa clara.</td></tr>
          </tbody>
        </DocTable>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>02.5</span> Antes de compartilhar</div>
        <div className="maturity-grid">
          <div><span>REPRODUZÍVEL</span><strong>Você consegue iniciar o runtime e ver o mesmo resultado do começo ao fim.</strong></div>
          <div><span>PEQUENO</span><strong>Cada mudança adiciona uma coisa que você sabe testar: item, bloco, comando ou menu.</strong></div>
          <div><span>EXPLÍCITO</span><strong>Permissões e capabilities só aparecem quando uma chamada realmente usa aquela superfície.</strong></div>
          <div><span>REALISTA</span><strong>Teste primeiro na combinação de Minecraft e plataforma onde você pretende jogar ou distribuir.</strong></div>
        </div>
      </section>
    </DocsShell>
  );
}

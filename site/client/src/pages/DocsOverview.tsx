/**
 * Planta de Mineração: mapa inicial do manual em cartões de evidência, para
 * orientar o modder pelo percurso antes de expor a referência detalhada.
 */
import { ArrowDownRight, BookOpen, Braces, Compass, FileJson, Hammer, Wrench } from "lucide-react";
import { Link } from "wouter";
import { DocsShell } from "@/components/DocsShell";

const sections = [
  { href: "/docs/primeiros-passos", index: "01", icon: Compass, title: "Primeiros passos", text: "Crie uma pasta, escreva um mod.json e carregue o menor bloco possível sem Java, Lua ou build.", tag: "COMECE AQUI" },
  { href: "/docs/progredir", index: "02", icon: Hammer, title: "Como progredir", text: "Saiba quando sair de conteúdo declarativo, quando pedir capability e quando dividir a lógica em módulos.", tag: "PERCURSO" },
  { href: "/docs/apis", index: "03", icon: BookOpen, title: "Lista de APIs", text: "Navegue por registro, mundo, jogador, eventos, comandos, cliente e tarefas sem abrir classes de plataforma.", tag: "CATÁLOGO" },
  { href: "/docs/manifesto", index: "04", icon: FileJson, title: "Declarar um mod", text: "Entenda schema, id, lado, permissões, dependencies, requires, recursos e as regras que o core valida.", tag: "REFERÊNCIA" },
];

export default function DocsOverview() {
  return (
    <DocsShell
      index="00"
      eyebrow="Mapa de documentação"
      title={<>Um mod portátil começa<br />com <em>uma boa declaração.</em></>}
      summary="Este guia separa o que é intenção do mod do que é detalhe de plataforma. Comece pequeno, declare só o que precisa e use a matriz como critério de evolução."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>00.1</span> A ideia central</div>
        <div className="doc-two-col">
          <p className="doc-lead">No MineLoader, o manifesto descreve conteúdo e requisitos; Lua descreve comportamento; os bridges lidam com Fabric, NeoForge e mappings.</p>
          <div className="doc-copy"><p>Um mod não precisa saber qual classe nativa cria uma comida, publica um comando ou lê um slot. Ele declara uma intenção estável e o runtime confirma se consegue oferecê-la.</p><p>Não escondemos limites. Uma capability inexistente em qualquer runtime é recusada ou fica fora do contrato até ter uma tradução verificável.</p></div>
        </div>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>00.2</span> Escolha seu próximo passo</div>
        <div className="doc-route-grid">
          {sections.map((section) => {
            const Icon = section.icon;
            return (
              <Link className="doc-route-card" key={section.href} href={section.href}>
                <div className="route-meta"><span>{section.index}</span><Icon size={21} strokeWidth={1.65} /></div>
                <h2>{section.title}</h2>
                <p>{section.text}</p>
                <footer><span>{section.tag}</span><ArrowDownRight size={18} /></footer>
              </Link>
            );
          })}
        </div>
      </section>

      <section className="doc-section doc-matrix-card">
        <div className="matrix-card-label"><Braces size={19} /> REGRA DE ENTRADA</div>
        <h2>Uma superfície só é comum quando<br /><em>atravessa quatro runtimes.</em></h2>
        <p>O contrato atual é mantido em Fabric 1.21.1, Fabric 1.21.4, NeoForge 1.21.1 e NeoForge 1.21.4. Para cada lote, o core, os bridges e a matriz de GameTests precisam concordar.</p>
        <div className="matrix-card-stats"><span><strong>4</strong> bridges</span><span><strong>26/26</strong> GameTests por runtime</span><span><strong>1</strong> contrato por capability</span></div>
      </section>

      <section className="doc-section tutorial-link-panel">
        <div><span className="tutorial-panel-kicker"><Wrench size={15} /> GUIAS MÃO NA MASSA</span><h2>Prefere começar fazendo?</h2><p>Os tutoriais juntam manifesto, código e critérios de progresso para bloco, item, UI e Lua.</p></div>
        <Link href="/docs/tutoriais">Abrir tutoriais <ArrowDownRight size={18} /></Link>
      </section>
    </DocsShell>
  );
}

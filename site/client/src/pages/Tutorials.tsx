/**
 * Planta de Mineração: índice de aprendizagem tratado como bancada de trabalho,
 * guiando de conteúdo estático a comportamento Lua com etapas bem delimitadas.
 */
import { ArrowDownRight, Blocks, Box, Code2, PanelsTopLeft, RefreshCw } from "lucide-react";
import { Link } from "wouter";
import { DocCallout, DocsShell } from "@/components/DocsShell";
import { TutorialSourceState } from "@/components/TutorialSourceState";
import { TUTORIALS_INDEX_URL, useTutorialIndex } from "@/lib/tutorialData";

const iconByEvidence = { block_geometry: Blocks, item_registry: Box, menu_grid: PanelsTopLeft, lua_flow: Code2 };

export default function Tutorials() {
  const { data, loading, error, refresh } = useTutorialIndex();
  if (!data) return <DocsShell index="T0" eyebrow="Tutoriais práticos" title={<>Fonte de guias<br /><em>em verificação.</em></>} summary="O índice dos tutoriais é publicado em JSON na branch main e validado no navegador."><TutorialSourceState loading={loading} error={error} onRefresh={refresh} sourceUrl={TUTORIALS_INDEX_URL} /></DocsShell>;
  return (
    <DocsShell
      index={data.page.index}
      eyebrow={data.page.eyebrow}
      title={<>{data.page.title.split("|", 2)[0]}<br /><em>{data.page.title.split("|", 2)[1]}</em></>}
      summary={data.page.summary}
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>{data.page.intro.label}</span> {data.page.intro.title}</div>
        <div className="doc-two-col">
          <p className="doc-lead">{data.page.intro.lead}</p>
          <div className="doc-copy">{data.page.intro.paragraphs.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}</div>
        </div>
      </section>

      <section className="doc-section">
        <div className="tutorial-grid">
          {data.items.map((tutorial) => {
            const Icon = iconByEvidence[tutorial.evidence];
            return (
              <Link key={tutorial.id} href={`/docs/tutoriais/${tutorial.id}`} className="tutorial-card">
                <div className="tutorial-card-top"><span>{tutorial.index}</span><Icon size={25} strokeWidth={1.5} /></div>
                <div><h2>{tutorial.title}</h2><p>{tutorial.description}</p></div>
                <footer><span>{tutorial.minutes} min</span><span>ABRIR GUIA <ArrowDownRight size={17} /></span></footer>
              </Link>
            );
          })}
        </div>
      </section>

      <section className="doc-section">
        <DocCallout title={data.page.callout.title} tone="proof">{data.page.callout.text}</DocCallout>
        <button className="tutorial-refresh-link" type="button" onClick={() => void refresh()} disabled={loading}><RefreshCw className={loading ? "spin" : ""} size={14} /> Atualizar índice publicado</button>
      </section>
    </DocsShell>
  );
}

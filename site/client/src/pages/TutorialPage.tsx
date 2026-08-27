/**
 * Planta de Mineração: a página é uma prancha documental construída da fonte
 * JSON canónica, para que editar a main atualize a publicação sem novo deploy.
 */
import { Blocks, Box, Code2, PanelsTopLeft, RefreshCw } from "lucide-react";
import { useRoute } from "wouter";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";
import { TutorialEvidence } from "@/components/TutorialEvidence";
import { TutorialSourceState } from "@/components/TutorialSourceState";
import { tutorialRawUrl, useTutorialDocument } from "@/lib/tutorialData";

const iconByName = { blocks: Blocks, box: Box, panels: PanelsTopLeft, code: Code2 };

function EditorialTitle({ value }: { value: string }) {
  const [first, second] = value.split("|", 2);
  return <>{first}{second ? <><br /><em>{second}</em></> : null}</>;
}

function ContractStrip({ document }: { document: NonNullable<ReturnType<typeof useTutorialDocument>["data"]> }) {
  return <div className="tutorial-contract-strip"><div><span>PERMISSÕES</span><strong>{document.contracts.permissions.length ? document.contracts.permissions.join(" · ") : "nenhuma"}</strong></div><div><span>CAPABILITIES</span><strong>{document.contracts.capabilities.join(" · ")}</strong></div><div><span>LIMITES</span><strong>{document.contracts.limits.join(" · ")}</strong></div></div>;
}

export default function TutorialPage() {
  const [, params] = useRoute("/docs/tutoriais/:id");
  const id = params?.id ?? "";
  const { data, loading, error, refresh } = useTutorialDocument(id);
  if (!data) return <DocsShell index="T?" eyebrow="Tutoriais publicados" title={<>Fonte em<br /><em>verificação.</em></>} summary="O site lê o documento JSON canónico na branch main antes de mostrar o guia."><TutorialSourceState loading={loading} error={error} onRefresh={refresh} sourceUrl={id ? tutorialRawUrl(`docs/tutorials/${id}.json`) : undefined} /></DocsShell>;
  const Icon = iconByName[data.outcome.icon];
  return <DocsShell index={data.index} eyebrow={data.eyebrow} title={<EditorialTitle value={data.title} />} summary={data.summary}>
    <section className="doc-section doc-opening">
      <div className="doc-section-label"><span>{data.index}.1</span> Resultado</div>
      <div className="tutorial-outcome"><Icon size={32} /><div><strong>{data.outcome.title}</strong><p>{data.outcome.text}</p></div><span>{data.outcome.label}</span></div>
      <TutorialEvidence evidence={data.evidence} />
    </section>
    <ContractStrip document={data} />
    {data.sections.map((section) => <section className="doc-section" key={section.id}>
      <div className="doc-section-label"><span>{section.id}</span> {section.title}</div>
      {section.layout === "split" && <div className="doc-two-col align-start"><p className="doc-lead">{section.lead}</p><div className="doc-copy">{section.paragraphs?.map((paragraph) => <p key={paragraph}>{paragraph}</p>)}</div></div>}
      {section.code && <DocCode language={section.code.language}>{section.code.value}</DocCode>}
      {section.table && <DocTable><thead><tr>{section.table.headers.map((header) => <th key={header}>{header}</th>)}</tr></thead><tbody>{section.table.rows.map((row, rowIndex) => <tr key={`${section.id}-${rowIndex}`}>{row.map((cell, cellIndex) => <td key={`${cell}-${cellIndex}`}>{cell}</td>)}</tr>)}</tbody></DocTable>}
      {section.callout && <DocCallout title={section.callout.title} tone={section.callout.tone}>{section.callout.text}</DocCallout>}
    </section>)}
    <section className="tutorial-document-foot"><div><span>FONTE CANÓNICA</span><strong>{data.sources.join(" · ")}</strong></div><button type="button" onClick={() => void refresh()} disabled={loading}><RefreshCw className={loading ? "spin" : ""} size={14} /> Atualizar fonte</button></section>
  </DocsShell>;
}

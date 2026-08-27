/**
 * Planta de Mineração: página de evidência conectada, projetada para revelar
 * diferenças entre bridges em vez de escondê-las sob uma marca verde genérica.
 */
import { CompatibilityMatrix } from "@/components/GitHubLive";
import { DocCallout, DocsShell } from "@/components/DocsShell";

export default function Compatibility() {
  return (
    <DocsShell
      index="05"
      eyebrow="Compatibilidade ao vivo"
      title={<>A página lê a prova<br /><em>onde ela é versionada.</em></>}
      summary="A matriz deste site vem de um JSON canónico publicado no repositório MineLoader. Ela mostra suporte, verificação manual, degradação e recusa explicitamente."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>05.1</span> Fonte auditável</div>
        <div className="doc-two-col">
          <p className="doc-lead">Compatibilidade não deve depender de copiar tabelas entre páginas ou de interpretar Markdown no navegador.</p>
          <div className="doc-copy"><p>O arquivo <code>docs/compatibility.json</code> é a fonte estruturada para sites e ferramentas. Ele vive junto da matriz humana e deve ser alterado na mesma mudança que altera um bridge ou uma evidência.</p><p>Como o navegador consulta a branch <code>main</code>, uma atualização publicada chega à página na próxima consulta. O botão abaixo força uma nova leitura.</p></div>
        </div>
      </section>
      <CompatibilityMatrix />
      <section className="doc-section">
        <DocCallout title="Leitura honesta da matriz" tone="warning">“Verificado” não significa que todos os aspectos client-side foram visualmente inspecionados. A tabela conserva estados como manual, degradado e recusado para que um mod não dependa de uma promessa maior que a evidência.</DocCallout>
      </section>
    </DocsShell>
  );
}

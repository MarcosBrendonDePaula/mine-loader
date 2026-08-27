/**
 * Planta de Mineração: indicadores vivos do GitHub tratados como evidência,
 * nunca como decoração. O erro é visível e a fonte permanece auditável.
 */
import { useState } from "react";
import { AlertTriangle, Check, ChevronDown, CircleDot, ExternalLink, GitBranch, GitFork, Github, RefreshCw, Star } from "lucide-react";
import { formatGitHubDate, REPOSITORY_URL, statusLabel, type CompatibilityStatus, type Runtime, useGitHubData } from "@/lib/githubData";

const statusClass: Record<CompatibilityStatus, string> = {
  supported: "status-supported",
  manual_verification: "status-manual",
  degraded: "status-degraded",
  blocked: "status-blocked",
};

function DataState({ loading, error }: { loading: boolean; error?: string }) {
  if (loading) return <span className="live-state is-loading"><i />Sincronizando a main…</span>;
  if (error) return <span className="live-state is-error"><AlertTriangle size={13} />Fonte indisponível</span>;
  return <span className="live-state is-ready"><Check size={13} />Dados da main</span>;
}

function familyFor(version: string): string {
  const [major = version, minor] = version.split(".");
  return minor ? `${major}.${minor}.*` : `${major}.*`;
}

export function RuntimeTree({ runtimes }: { runtimes: Runtime[] }) {
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const platforms = Array.from(new Set(runtimes.map((runtime) => runtime.platform)));
  const isExpanded = (node: string) => expanded[node] ?? true;
  const toggle = (node: string) => setExpanded((nodes) => ({ ...nodes, [node]: !(nodes[node] ?? true) }));

  return (
    <div className="runtime-tree" aria-label="Árvore de runtimes mantidos">
      <div className="runtime-tree-root"><GitBranch size={16} /><span>MINELOADER</span><small>{runtimes.length} runtimes</small></div>
      {platforms.map((platform, platformIndex) => {
        const platformNode = `platform:${platform}`;
        const platformRuntimes = runtimes.filter((runtime) => runtime.platform === platform);
        const families = Array.from(new Set(platformRuntimes.map((runtime) => familyFor(runtime.minecraft))));
        return (
          <div className="tree-platform" key={platformNode}>
            <button type="button" className="tree-node tree-platform-node" onClick={() => toggle(platformNode)} aria-expanded={isExpanded(platformNode)}>
              <ChevronDown className={isExpanded(platformNode) ? "" : "is-collapsed"} size={15} /><i className="tree-junction" />
              <span>{platform}</span><small>{platformRuntimes.length} releases</small><b>0{platformIndex + 1}</b>
            </button>
            {isExpanded(platformNode) && <div className="tree-branch">
              {families.map((family) => {
                const familyNode = `${platformNode}:${family}`;
                const familyRuntimes = platformRuntimes.filter((runtime) => familyFor(runtime.minecraft) === family);
                return (
                  <div className="tree-family" key={familyNode}>
                    <button type="button" className="tree-node tree-family-node" onClick={() => toggle(familyNode)} aria-expanded={isExpanded(familyNode)}>
                      <ChevronDown className={isExpanded(familyNode) ? "" : "is-collapsed"} size={14} /><i className="tree-junction" />
                      <code>{family}</code><small>{familyRuntimes.length} versões</small>
                    </button>
                    {isExpanded(familyNode) && <div className="tree-leaves">
                      {familyRuntimes.map((runtime) => (
                        <div className="tree-leaf" key={runtime.id}>
                          <i className="tree-junction" /><span className={`tree-maturity ${runtime.maturity}`}>{runtime.maturity === "baseline" ? "base" : "exp."}</span>
                          <strong>{runtime.minecraft}</strong><span className="tree-test"><Check size={12} />{runtime.tests.passed}/{runtime.tests.total}</span>
                        </div>
                      ))}
                    </div>}
                  </div>
                );
              })}
            </div>}
          </div>
        );
      })}
    </div>
  );
}

export function RepositoryPulse() {
  const { data, loading, error } = useGitHubData();
  const matrix = data?.compatibility;
  const repository = data?.repository;
  const sameTestCount = matrix?.runtimes.every((runtime) => runtime.tests.passed === matrix.runtimes[0]?.tests.passed && runtime.tests.total === matrix.runtimes[0]?.tests.total);
  const firstRuntime = matrix?.runtimes[0];

  return (
    <section className="proof-ribbon live-proof" aria-label="Indicadores públicos do MineLoader">
      <div><strong>{sameTestCount && firstRuntime ? `${firstRuntime.tests.passed}/${firstRuntime.tests.total}` : "—"}</strong><span>GameTests<br />por runtime</span></div>
      <div><strong>{matrix?.runtimes.length ?? "—"}</strong><span>combinações<br />publicadas</span></div>
      <div><strong>{repository ? repository.open_issues_count : "—"}</strong><span>issues abertas<br />no GitHub</span></div>
      <div className="proof-live-cell"><DataState loading={loading} error={error} /><span>main · {repository?.default_branch ?? "aguardando"}<br />push: {formatGitHubDate(repository?.pushed_at)}</span></div>
    </section>
  );
}

export function RuntimeCards() {
  const { data, loading, error } = useGitHubData();
  const runtimes = data?.compatibility?.runtimes;
  if (!runtimes) {
    return <div className="runtime-cards live-runtime-empty"><p>{loading ? "Lendo a matriz publicada…" : "A matriz não pôde ser carregada agora."}</p>{error && <small>{error}</small>}</div>;
  }

  return (
    <div className="runtime-cards live-runtime-cards">
      {runtimes.map((runtime, index) => (
        <article className="runtime-card" key={runtime.id}>
          <span className="runtime-order">0{index + 1}</span>
          <div><h3>{runtime.platform}</h3><p>{runtime.minecraft}</p></div>
          <span className="verified"><Check size={13} />{runtime.tests.passed}/{runtime.tests.total}</span>
          <small>{runtime.maturity === "baseline" ? "baseline" : "experimental"}</small>
        </article>
      ))}
    </div>
  );
}

export function CompatibilityMatrix() {
  const { data, loading, error, refresh } = useGitHubData();
  const matrix = data?.compatibility;
  const repository = data?.repository;

  return (
    <section className="live-matrix" aria-labelledby="live-matrix-title">
      <header className="live-matrix-head">
        <div>
          <div className="live-source"><CircleDot size={14} /><span>FONTE: main/docs/compatibility.json</span></div>
          <h2 id="live-matrix-title">Matriz <em>lida do repositório.</em></h2>
          <p>O site consulta o JSON publicado na branch <code>main</code>. Alterações no arquivo ficam disponíveis para a próxima consulta, sem editar esta página.</p>
        </div>
        <div className="live-actions">
          <DataState loading={loading} error={error} />
          <button type="button" className="refresh-button" onClick={() => void refresh()} disabled={loading}><RefreshCw size={14} className={loading ? "spin" : ""} />Atualizar</button>
        </div>
      </header>

      <div className="repo-facts">
        <a href={REPOSITORY_URL} target="_blank" rel="noreferrer"><Github size={17} /><span>REPOSITÓRIO</span><strong>MineLoader</strong><ExternalLink size={13} /></a>
        <div><Star size={17} /><span>ESTRELAS</span><strong>{repository?.stargazers_count ?? "—"}</strong></div>
        <div><GitFork size={17} /><span>FORKS</span><strong>{repository?.forks_count ?? "—"}</strong></div>
        <div><CircleDot size={17} /><span>ÚLTIMO PUSH</span><strong>{formatGitHubDate(repository?.pushed_at)}</strong></div>
      </div>

      {matrix ? (
        <>
          <RuntimeTree runtimes={matrix.runtimes} />
          <div className="live-table-wrap">
            <table className="live-table">
              <thead><tr><th>Capability</th>{matrix.runtimes.map((runtime) => <th key={runtime.id}>{runtime.platform}<small>{runtime.minecraft}</small></th>)}</tr></thead>
              <tbody>
                {matrix.capabilities.map((capability) => (
                  <tr key={capability.id}>
                    <td><strong>{capability.label}</strong><code>{capability.id}</code></td>
                    {matrix.runtimes.map((runtime) => {
                      const entry = capability.entries[runtime.id];
                      return <td key={runtime.id}>{entry ? <span className={`compat-status ${statusClass[entry.status]}`} title={entry.evidence}><i />{statusLabel(entry.status)}<small>{entry.evidence}</small></span> : <span className="compat-status status-blocked"><i />Ausente</span>}</td>;
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <footer className="live-matrix-foot"><p><strong>Escopo de validação:</strong> {matrix.verification.scope}</p><code>{matrix.verification.command}</code><span>atualizado no JSON: {matrix.updated_at}</span></footer>
        </>
      ) : (
        <div className="live-matrix-fallback"><AlertTriangle size={20} /><div><strong>Matriz indisponível nesta consulta.</strong><p>{error ?? "Aguardando a resposta da fonte pública."} O site não inventa estados de compatibilidade quando a fonte não responde.</p></div></div>
      )}
    </section>
  );
}

/** Planta de Mineração: estado explícito para fontes públicas, sem fallback de conteúdo inventado. */
import { ExternalLink, RefreshCw, TriangleAlert } from "lucide-react";

export function TutorialSourceState({ loading, error, onRefresh, sourceUrl }: { loading: boolean; error?: string; onRefresh: () => Promise<void>; sourceUrl?: string }) {
  if (!loading && !error) return null;
  return <section className="tutorial-source-state" aria-live="polite"><div>{loading ? <RefreshCw className="spin" size={19} /> : <TriangleAlert size={19} />}<div><strong>{loading ? "Lendo o tutorial publicado" : "Não foi possível ler o tutorial"}</strong><p>{loading ? "A fonte JSON da branch main está sendo validada antes de renderizar o conteúdo." : `${error} Nenhum texto local foi exibido como substituto.`}</p></div></div><div className="tutorial-source-actions"><button type="button" onClick={() => void onRefresh()} disabled={loading}><RefreshCw size={14} /> Atualizar</button>{sourceUrl && <a href={sourceUrl} target="_blank" rel="noreferrer">Ver JSON <ExternalLink size={13} /></a>}</div></section>;
}

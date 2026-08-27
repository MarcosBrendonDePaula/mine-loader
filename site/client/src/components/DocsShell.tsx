/**
 * Planta de Mineração: estrutura documental como prancha técnica navegável,
 * com rótulos de contrato, trilha lateral e foco em leitura longa e legível.
 */
import { useState, type ReactNode } from "react";
import { ArrowUpRight, Github, Menu, X } from "lucide-react";
import { Link, useLocation } from "wouter";
import { BrandMark } from "@/components/BrandMark";

const GITHUB = "https://github.com/MarcosBrendonDePaula/mine-loader";

const documentationRoutes = [
  { href: "/docs", label: "Visão geral", code: "00" },
  { href: "/docs/primeiros-passos", label: "Primeiros passos", code: "01" },
  { href: "/docs/progredir", label: "Como progredir", code: "02" },
  { href: "/docs/apis", label: "Lista de APIs", code: "03" },
  { href: "/docs/manifesto", label: "Declarar um mod", code: "04" },
  { href: "/docs/compatibilidade", label: "Compatibilidade ao vivo", code: "05" },
];

const tutorialRoutes = [
  { href: "/docs/tutoriais", label: "Visão dos tutoriais", code: "T0" },
  { href: "/docs/tutoriais/bloco", label: "Criar um bloco", code: "T1" },
  { href: "/docs/tutoriais/item", label: "Criar um item", code: "T2" },
  { href: "/docs/tutoriais/ui", label: "Criar uma UI", code: "T3" },
  { href: "/docs/tutoriais/lua", label: "Criar código Lua", code: "T4" },
];

type DocsShellProps = {
  index: string;
  eyebrow: string;
  title: ReactNode;
  summary: string;
  children: ReactNode;
};

export function DocsShell({ index, eyebrow, title, summary, children }: DocsShellProps) {
  const [location] = useLocation();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  return (
    <div className="docs-shell">
      <a className="skip-link" href="#doc-conteudo">Pular para o conteúdo</a>
      <header className="docs-topbar">
        <Link className="docs-brand" href="/" aria-label="MineLoader — página inicial">
          <BrandMark />
          <span className="brand-word">MINE<span className="brand-accent">L</span><span className="open-o">O</span><span className="brand-accent">ADER</span></span>
          <span className="docs-word">DOCS</span>
        </Link>
        <div className="docs-top-actions">
          <Link className="docs-home-link" href="/">Voltar ao projeto</Link>
          <a className="docs-github" href={GITHUB} target="_blank" rel="noreferrer"><Github size={15} /> GitHub <ArrowUpRight size={13} /></a>
          <button
            className="docs-menu-toggle"
            type="button"
            onClick={() => setMobileNavOpen((open) => !open)}
            aria-expanded={mobileNavOpen}
            aria-controls="docs-navigation"
          >
            {mobileNavOpen ? <X size={18} /> : <Menu size={18} />}
            <span>{mobileNavOpen ? "Fechar" : "Seções"}</span>
          </button>
        </div>
      </header>

      <div className="docs-frame">
        <aside id="docs-navigation" className={`docs-sidebar ${mobileNavOpen ? "is-open" : ""}`} aria-label="Navegação da documentação">
          <div className="sidebar-intro"><span>DOCUMENTAÇÃO</span><strong>Contrato<br />versão 1</strong></div>
          <div className="sidebar-scroll-status"><span>↓</span> NAVEGAÇÃO ROLÁVEL</div>
          <nav>
            {documentationRoutes.map((route) => {
              const active = location === route.href;
              return (
                <Link key={route.href} href={route.href} className={`docs-nav-link ${active ? "is-active" : ""}`} onClick={() => setMobileNavOpen(false)}>
                  <span>{route.code}</span>{route.label}
                </Link>
              );
            })}
          </nav>
          <div className="docs-nav-heading">TUTORIAIS</div>
          <nav className="tutorial-nav-group">
            {tutorialRoutes.map((route) => {
              const active = location === route.href;
              return (
                <Link key={route.href} href={route.href} className={`docs-nav-link ${active ? "is-active" : ""}`} onClick={() => setMobileNavOpen(false)}>
                  <span>{route.code}</span>{route.label}
                </Link>
              );
            })}
          </nav>
          <div className="sidebar-signature" aria-label="Assinatura MineLoader">
            <BrandMark />
            <div><span>MINE<span>L</span><b>O</b><span>ADER</span></span><small>MOD CONTRACTS</small></div>
          </div>
          <div className="sidebar-proof"><span className="signal-dot" />MATRIZ COMUM<br /><strong>FABRIC + NEOFORGE</strong></div>
        </aside>

        <main id="doc-conteudo" className="docs-main">
          <div className="doc-hero">
            <div className="doc-hero-grid" />
            <div className="doc-hero-code">SEC // {index}</div>
            <div className="doc-eyebrow"><span>{index}</span>{eyebrow}</div>
            <h1>{title}</h1>
            <p>{summary}</p>
            <div className="doc-hero-rule"><i /><span>CONTRATO COMUM · 4 RUNTIMES</span><i /></div>
          </div>
          <div className="doc-content">{children}</div>
        </main>
      </div>
    </div>
  );
}

export function DocCode({ language, children }: { language: string; children: string }) {
  return (
    <div className="doc-code">
      <div className="doc-code-head"><span className="code-lamp" /><span>{language}</span><span>VALIDAR ANTES DE CARREGAR</span></div>
      <pre><code>{children}</code></pre>
    </div>
  );
}

export function DocCallout({ title, children, tone = "default" }: { title: string; children: ReactNode; tone?: "default" | "warning" | "proof" }) {
  return (
    <aside className={`doc-callout doc-callout-${tone}`}>
      <span className="callout-code">NOTE // {tone === "proof" ? "VERIFIED" : tone === "warning" ? "ATTENTION" : "CONTRACT"}</span>
      <div><strong>{title}</strong><p>{children}</p></div>
    </aside>
  );
}

export function DocTable({ children }: { children: ReactNode }) {
  return <div className="doc-table-wrap"><table className="doc-table">{children}</table></div>;
}

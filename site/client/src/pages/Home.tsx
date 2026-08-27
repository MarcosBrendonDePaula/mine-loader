/**
 * Planta de Mineração: landing editorial assimétrica que transforma números,
 * contratos e bridges em evidência visual de portabilidade do MineLoader.
 */
import { useState } from "react";
import { Link } from "wouter";
import { RepositoryPulse, RuntimeCards } from "@/components/GitHubLive";
import {
  ArrowDownRight,
  ArrowUpRight,
  Braces,
  Check,
  Code2,
  FileJson,
  Github,
  Hammer,
  Menu,
  PackageCheck,
  ShieldCheck,
  Sparkles,
  Workflow,
  X,
} from "lucide-react";
import { BrandMark } from "@/components/BrandMark";
import { siteAsset } from "@/lib/siteAsset";

const GITHUB = "https://github.com/MarcosBrendonDePaula/mine-loader";
const DOCS = `${GITHUB}/tree/main/docs`;

const codeSamples = {
  manifesto: {
    label: "mod.json",
    body: `{
  "schema": 1,
  "id": "land_claims",
  "entrypoint": "main.lua",
  "requires": {
    "capabilities": {
      "events.action.authorization": "1.0.0"
    }
  }
}`,
  },
  lua: {
    label: "main.lua",
    body: `mod.on("action_attempt", function(ctx)
  if ctx.action == "block.break"
    and ctx.target.id == "minecraft:obsidian" then
    return false
  end
end)`,
  },
};

const capabilities = [
  {
    icon: FileJson,
    index: "01",
    title: "Registo declarativo",
    text: "Blocos, itens, comida, combustível, receitas, loot, tags e entidades partem de um manifesto versionado.",
    tag: "registry.*",
  },
  {
    icon: Workflow,
    index: "02",
    title: "Mundo e jogador",
    text: "Tempo, clima, explosão, raio, estados de bloco, inventário e snapshots seguros chegam sem mappings.",
    tag: "world · player",
  },
  {
    icon: ShieldCheck,
    index: "03",
    title: "Ações autorizáveis",
    text: "Claims e proteção podem vetar quebra, colocação e uso de bloco com um contrato global e fail-closed.",
    tag: "events.action.*",
  },
  {
    icon: Code2,
    index: "04",
    title: "Lógica em Lua",
    text: "Eventos, comandos, hotkeys e bibliotecas entre mods usam Lua em sandbox e dados simples na fronteira.",
    tag: "lua · contract",
  },
];

function ExternalLink({ href, children, className = "" }: { href: string; children: React.ReactNode; className?: string }) {
  return (
    <a className={className} href={href} target="_blank" rel="noreferrer">
      {children}
      <ArrowUpRight aria-hidden="true" size={15} strokeWidth={2.25} />
    </a>
  );
}

export default function Home() {
  const [activeSample, setActiveSample] = useState<keyof typeof codeSamples>("manifesto");
  const [menuOpen, setMenuOpen] = useState(false);

  const closeMenu = () => setMenuOpen(false);

  return (
    <div className="site-shell">
      <a className="skip-link" href="#conteudo">Pular para o conteúdo</a>

      <header className="site-header">
        <a className="brand-lockup" href="#topo" onClick={closeMenu} aria-label="MineLoader — início">
          <BrandMark />
          <span className="brand-word">MINE<span className="brand-accent">L</span><span className="open-o">O</span><span className="brand-accent">ADER</span></span>
        </a>

        <button
          type="button"
          className="nav-toggle"
          aria-expanded={menuOpen}
          aria-controls="navegacao-principal"
          onClick={() => setMenuOpen((open) => !open)}
        >
          {menuOpen ? <X size={20} /> : <Menu size={20} />}
          <span>{menuOpen ? "Fechar" : "Menu"}</span>
        </button>

        <nav id="navegacao-principal" className={`site-nav ${menuOpen ? "is-open" : ""}`} aria-label="Navegação principal">
          <a href="#proposta" onClick={closeMenu}>Proposta</a>
          <a href="#contrato" onClick={closeMenu}>Contrato</a>
          <a href="#matriz" onClick={closeMenu}>Matriz</a>
          <Link href="/docs" className="nav-docs" onClick={closeMenu}>Documentação <ArrowDownRight aria-hidden="true" size={15} strokeWidth={2.25} /></Link>
          <ExternalLink href={GITHUB} className="nav-github"><Github size={16} /> GitHub</ExternalLink>
        </nav>
      </header>

      <main id="conteudo">
        <section id="topo" className="hero-section" aria-labelledby="hero-title">
          <div className="hero-grid" />
          <div className="hero-copy">
            <div className="eyebrow reveal"><span className="signal-dot" />Minecraft Java · contrato estável</div>
            <h1 id="hero-title" className="reveal reveal-1">Escreve uma vez.<br /><em>Mantém o contrato.</em></h1>
            <p className="hero-description reveal reveal-2">
              O MineLoader é um modloader declarativo para Minecraft Java. O mod usa JSON, Lua e recursos; os bridges absorvem Fabric, NeoForge e as mudanças de versão.
            </p>
            <div className="hero-actions reveal reveal-3">
              <Link href="/docs" className="button button-primary">Ler a documentação <ArrowDownRight aria-hidden="true" size={15} strokeWidth={2.25} /></Link>
              <a href="#contrato" className="button button-quiet">Ver como funciona <ArrowDownRight size={17} /></a>
            </div>
          </div>

          <figure className="hero-figure reveal reveal-2">
            <div className="hero-figure-label">A mesma intenção · quatro traduções</div>
            <img src={siteAsset("mineloader-hero-basalt-bridge.jpg")} alt="Estrutura voxelizada de basalto e cobre que simboliza um contrato de software atravessando diferentes bridges." />
            <figcaption><span>BRIDGE // 01</span><span>CONTRATO COMUM</span></figcaption>
          </figure>

          <div className="hero-coordinate hero-coordinate-a">X 021 / Y 026</div>
          <div className="hero-coordinate hero-coordinate-b">RUNTIME MATRIX →</div>
        </section>

        <RepositoryPulse />

        <section id="proposta" className="manifesto-section section-wrap" aria-labelledby="proposta-title">
          <div className="section-intro">
            <div className="section-kicker"><span>01</span> A proposta</div>
            <h2 id="proposta-title">Menos código preso à versão.<br /><em>Mais mod que sobrevive.</em></h2>
          </div>
          <div className="manifesto-body">
            <p className="big-copy">Minecraft muda. Mappings mudam. APIs de plataforma mudam. O contrato do teu mod não precisa mudar junto.</p>
            <div className="manifesto-notes">
              <p>O core define uma linguagem própria de capabilities, permissões, snapshots e limites. Cada bridge traduz essa linguagem para o runtime em execução.</p>
              <p>O resultado não é esconder Fabric ou NeoForge; é evitar que o autor precise carregar essas decisões em cada mod.</p>
            </div>
          </div>
          <div className="annotation-row">
            <span>CORE</span><i /><span>SEM CLASSES MINECRAFT</span><i /><span>SEM MAPPINGS</span><i /><span>SEM OBJETOS VIVOS EM LUA</span>
          </div>
        </section>

        <section id="contrato" className="contract-section section-wrap" aria-labelledby="contrato-title">
          <div className="contract-header">
            <div>
              <div className="section-kicker"><span>02</span> O contrato</div>
              <h2 id="contrato-title">Declara o que o mod<br /><em>precisa. Só isso.</em></h2>
            </div>
            <p>Capabilities negociam a superfície mínima. Permissões protegem operações. O manifesto deixa ambas visíveis antes da execução.</p>
          </div>

          <div className="contract-layout">
            <div className="code-station">
              <div className="code-tabs" role="tablist" aria-label="Exemplos de código">
                {(Object.keys(codeSamples) as Array<keyof typeof codeSamples>).map((key) => (
                  <button
                    type="button"
                    key={key}
                    role="tab"
                    aria-selected={activeSample === key}
                    className={activeSample === key ? "is-active" : ""}
                    onClick={() => setActiveSample(key)}
                  >
                    <span className="tab-dot" />{codeSamples[key].label}
                  </button>
                ))}
              </div>
              <pre aria-live="polite"><code>{codeSamples[activeSample].body}</code></pre>
              <div className="code-footer"><span><Check size={14} /> Validado no core</span><span>schema: 1</span></div>
            </div>
            <aside className="contract-visual">
              <img src={siteAsset("mineloader-contract-layers-v2.jpg")} alt="Camadas voxelizadas de papel mineral, cobre e grafite representando manifesto, core e bridges." />
              <div className="visual-caption"><span>MANIFESTO</span><span>→</span><span>CORE</span><span>→</span><span>BRIDGE</span></div>
            </aside>
          </div>
        </section>

        <section className="capabilities-section section-wrap" aria-labelledby="capabilities-title">
          <div className="capabilities-heading">
            <div className="section-kicker"><span>03</span> Superfície útil</div>
            <h2 id="capabilities-title">Constrói mecânicas.<br /><em>Não adapta mappings.</em></h2>
            <ExternalLink href={`${DOCS}/API_ESTAVEL.md`} className="text-link">Consultar API estável</ExternalLink>
          </div>
          <div className="capability-list">
            {capabilities.map((capability) => {
              const Icon = capability.icon;
              return (
                <article className="capability-card" key={capability.title}>
                  <div className="capability-meta"><span>{capability.index}</span><Icon size={20} strokeWidth={1.75} /></div>
                  <h3>{capability.title}</h3>
                  <p>{capability.text}</p>
                  <span className="capability-tag">{capability.tag}</span>
                </article>
              );
            })}
          </div>
        </section>

        <section id="matriz" className="matrix-section" aria-labelledby="matriz-title">
          <div className="matrix-grid" />
          <div className="section-wrap matrix-inner">
            <div className="matrix-topline">
              <div>
                <div className="section-kicker on-dark"><span>04</span> Matriz viva</div>
                <h2 id="matriz-title">O bridge muda.<br /><em>O mod não.</em></h2>
              </div>
              <p>A compatibilidade não é uma promessa de marketing. Cada runtime tem build, testes e GameTests próprios antes de um contrato ser considerado comum.</p>
            </div>

            <div className="runtime-layout">
              <RuntimeCards />
              <figure className="matrix-visual">
                <img src={siteAsset("mineloader-runtime-assembly-v2.jpg")} alt="Quatro estações voxelizadas conectadas a um cubo de contrato central." />
                <figcaption><span>FONTE: main/docs/compatibility.json</span><Link href="/docs/compatibilidade">VER MATRIZ AO VIVO <ArrowDownRight size={13} /></Link></figcaption>
              </figure>
            </div>
          </div>
        </section>

        <section className="evidence-section section-wrap" aria-labelledby="evidence-title">
          <div className="evidence-head">
            <div className="section-kicker"><span>05</span> Evidência, não slogan</div>
            <h2 id="evidence-title">Uma capability só entra<br /><em>quando atravessa a matriz.</em></h2>
          </div>
          <div className="evidence-grid">
            <article className="evidence-feature">
              <span className="feature-no">A</span>
              <PackageCheck size={28} strokeWidth={1.5} />
              <h3>Contrato explícito</h3>
              <p>Domains e capabilities com versão mínima permitem que um mod declare seu requisito sem amarrar-se à versão do Minecraft.</p>
            </article>
            <article className="evidence-feature offset">
              <span className="feature-no">B</span>
              <Hammer size={28} strokeWidth={1.5} />
              <h3>Quatro bridges</h3>
              <p>Fabric e NeoForge recebem traduções próprias, enquanto o core permanece livre de imports de plataforma.</p>
            </article>
            <article className="evidence-feature">
              <span className="feature-no">C</span>
              <Braces size={28} strokeWidth={1.5} />
              <h3>Lua em dados simples</h3>
              <p>Scripts trabalham com tabelas, escalares e IDs — nunca com referências vivas que quebram entre mappings.</p>
            </article>
          </div>
        </section>

        <section id="começar" className="closing-section section-wrap" aria-labelledby="closing-title">
          <div className="closing-copy">
            <div className="section-kicker on-dark"><span>06</span> Começar agora</div>
            <h2 id="closing-title">A próxima versão<br /><em>não deveria refazer o teu mod.</em></h2>
            <p>Explora a especificação, os exemplos executáveis e a matriz de compatibilidade no repositório.</p>
            <div className="closing-actions">
              <ExternalLink href={GITHUB} className="button button-copper"><Github size={17} /> Abrir repositório</ExternalLink>
              <Link href="/docs/primeiros-passos" className="button button-light">Criar primeiro mod <ArrowDownRight size={17} /></Link>
            </div>
          </div>
          <figure className="closing-visual">
            <img src={siteAsset("mineloader-build-beacon-v2.jpg")} alt="Farol voxelizado em basalto, cobre e verde musgo sobre uma prancha técnica." />
            <figcaption><Sparkles size={15} /> Build once · bridge often</figcaption>
          </figure>
        </section>
      </main>

      <footer className="site-footer">
        <div className="footer-brand"><BrandMark /><span>MINE<span className="brand-accent">L</span><span className="open-o">O</span><span className="brand-accent">ADER</span></span></div>
        <p>Contrato declarativo para Minecraft Java.</p>
        <div className="footer-links"><ExternalLink href={GITHUB}>GitHub</ExternalLink><Link href="/docs">Docs</Link></div>
      </footer>
    </div>
  );
}

/**
 * Planta de Mineração: percurso de maturidade do mod organizado como uma linha
 * de fabricação, tornando visível quando adicionar contratos, permissões e testes.
 */
import { ArrowDown, Braces, Cable, Layers3, ShieldCheck, TestTube2 } from "lucide-react";
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
local ui = mod.import("lib/ui.lua")

mod.on("player_joined", function(ctx)
  ui.aviso(ctx, "Bem-vindo")
end)`;

const stages = [
  { no: "01", title: "Conteúdo", icon: Layers3, text: "Registre um bloco, item ou receita sem depender de lógica dinâmica. O manifesto é suficiente para começar." },
  { no: "02", title: "Comportamento", icon: Braces, text: "Adicione entrypoint Lua e eventos. Mantenha callbacks curtos, determinísticos e focados na intenção do mod." },
  { no: "03", title: "Contrato", icon: ShieldCheck, text: "Quando usar uma superfície específica, declare permissions e requires. A carga passa a explicar o que o mod exige." },
  { no: "04", title: "Composição", icon: Cable, text: "Divida arquivos com mod.import, declare dependencies para bibliotecas de outros mods e recuse ciclos cedo." },
  { no: "05", title: "Matriz", icon: TestTube2, text: "Teste o conteúdo e valide cada capability nos quatro bridges antes de afirmar que o mod é portátil." },
];

export default function Progression() {
  return (
    <DocsShell
      index="02"
      eyebrow="Como progredir"
      title={<>Cresça o mod sem<br /><em>crescer a dependência.</em></>}
      summary="A progressão saudável não é expor mais APIs nativas. É adicionar superfícies pequenas, declaradas e verificáveis quando a mecânica realmente precisa delas."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>02.1</span> Da ideia ao contrato</div>
        <div className="progress-track">
          {stages.map((stage, index) => {
            const Icon = stage.icon;
            return (
              <article className="progress-stage" key={stage.no}>
                <div className="progress-marker"><span>{stage.no}</span><Icon size={20} /></div>
                <h2>{stage.title}</h2><p>{stage.text}</p>
                {index < stages.length - 1 && <ArrowDown className="progress-arrow" size={18} />}
              </article>
            );
          })}
        </div>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>02.2</span> Peça só o contrato necessário</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Permissões dizem o que um mod pode fazer. Capabilities dizem o que o runtime precisa saber oferecer.</p>
          <div className="doc-copy"><p>Os dois conceitos são complementares. Declarar <code>world.block_state.read</code> garante que o contrato existe; declarar <code>world.read</code> autoriza a leitura quando o script executar.</p><p>As versões em <code>requires</code> pertencem ao MineLoader, não ao Minecraft. O mesmo requisito pode ser satisfeito por bridges distintos.</p></div>
        </div>
        <DocCode language="mod.json">{requirementSample}</DocCode>
        <DocCallout title="Sem fallback silencioso" tone="proof">Se uma capability não é entregue pelo runtime, o loader recusa o pacote antes de registrar conteúdo ou executar Lua. Não há aproximação oculta.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>02.3</span> Organize antes de duplicar</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Use <code>mod.import</code> para dividir o próprio mod. Use <code>mod.require</code> somente para consumir uma biblioteca de outro mod declarada em <code>dependencies</code>.</p>
          <div className="doc-copy"><p>Imports permanecem presos à pasta do pacote e executam uma vez. Dependências controlam a ordem de carga e a resolução dinâmica, mantendo uma cadeia de erro legível caso exista ciclo.</p></div>
        </div>
        <DocCode language="main.lua">{importSample}</DocCode>
        <DocTable>
          <thead><tr><th>Ferramenta</th><th>Alcance</th><th>Quando usar</th></tr></thead>
          <tbody>
            <tr><td><code>mod.import("lib/x.lua")</code></td><td>Arquivo do próprio mod</td><td>Extrair UI, regras, tabelas e utilitários internos.</td></tr>
            <tr><td><code>mod.require("outro_mod")</code></td><td>API pública de outro mod</td><td>Reutilizar uma biblioteca declarada em <code>dependencies</code>.</td></tr>
            <tr><td><code>$import</code></td><td>Parte do manifesto</td><td>Separar blocos, itens, recursos e dados declarativos.</td></tr>
          </tbody>
        </DocTable>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>02.4</span> Checklist de maturidade</div>
        <div className="maturity-grid">
          <div><span>DECLARAÇÃO</span><strong>O manifest descreve tudo que deve existir antes da carga.</strong></div>
          <div><span>FRONTEIRA</span><strong>Lua recebe IDs, tabelas e escalares; nunca objetos da plataforma.</strong></div>
          <div><span>PRIVILÉGIO</span><strong>Cada operação de impacto declara a permissão correspondente.</strong></div>
          <div><span>COMPATIBILIDADE</span><strong>Capabilities e domains expõem o requisito verificável do mod.</strong></div>
          <div><span>VALIDAÇÃO</span><strong>O mod é testado onde será usado, e não apenas em um runtime preferido.</strong></div>
        </div>
      </section>
    </DocsShell>
  );
}

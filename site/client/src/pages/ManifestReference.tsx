/**
 * Planta de Mineração: referência do manifesto como ficha de engenharia,
 * conectando cada chave a um efeito de carga, permission ou capability.
 */
import { FileJson, Link2, ShieldCheck } from "lucide-react";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";

const completeManifest = `{
  "schema": 1,
  "id": "land_claims",
  "name": "Land Claims",
  "version": "0.1.0",
  "description": "Protege blocos por regra Lua.",
  "entrypoint": "main.lua",
  "permissions": ["chat.send"],
  "requires": {
    "capabilities": {
      "events.action.authorization": "1.0.0"
    }
  }
}`;

const foodManifest = `{
  "items": [{
    "id": "racao",
    "name": "Ração",
    "food": {
      "nutrition": 6,
      "saturation": 0.8,
      "always_edible": true,
      "consume_seconds": 2.5,
      "effects": [{
        "id": "minecraft:speed",
        "duration": 100,
        "amplifier": 1,
        "chance": 0.75
      }]
    },
    "fuel_burn_time": 400
  }]
}`;

export default function ManifestReference() {
  return (
    <DocsShell
      index="04"
      eyebrow="Declarar um mod"
      title={<>O <em>mod.json</em> é a planta<br />do que vai existir.</>}
      summary="O manifesto é validado antes da carga. Ele define a identidade, o lado de execução, as permissões, os requisitos de contrato e todo conteúdo que deve ser registrado."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>04.1</span> Uma declaração completa</div>
        <div className="doc-two-col">
          <p className="doc-lead">Comece pela identidade. Depois acrescente somente as permissões e capabilities que o mod realmente consome.</p>
          <div className="doc-copy"><p>O exemplo abaixo é o formato usado pelo exemplo executável de proteção. A capability permite registrar o callback global; a permission de chat só existe porque o mod decide comunicar o veto ao jogador.</p><p>O loader recusa chaves desconhecidas ou dados incompatíveis em vez de carregar uma configuração parcialmente entendida.</p></div>
        </div>
        <DocCode language="mod.json">{completeManifest}</DocCode>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>04.2</span> Campos da raiz</div>
        <DocTable>
          <thead><tr><th>Campo</th><th>Obrigatório</th><th>Função</th></tr></thead>
          <tbody>
            <tr><td><code>schema</code></td><td>Sim</td><td>Versão do formato estrutural do manifesto.</td></tr>
            <tr><td><code>id</code></td><td>Sim</td><td>Identificador minúsculo do mod; também determina a pasta do pacote.</td></tr>
            <tr><td><code>name</code> e <code>version</code></td><td>Sim</td><td>Nome público e versão do pacote.</td></tr>
            <tr><td><code>entrypoint</code></td><td>Não</td><td>Arquivo Lua principal para eventos, comandos e lógica dinâmica.</td></tr>
            <tr><td><code>side</code></td><td>Não</td><td><code>server</code> ou <code>both</code>; ausência permite ao loader deduzir pelo conteúdo declarado.</td></tr>
            <tr><td><code>permissions</code></td><td>Não</td><td>Operações que o mod está autorizado a executar em runtime.</td></tr>
            <tr><td><code>requires</code></td><td>Não</td><td>Domains e capabilities mínimos a serem satisfeitos pelo runtime.</td></tr>
            <tr><td><code>dependencies</code></td><td>Não</td><td>Mods que precisam carregar antes e podem ser consumidos por <code>mod.require</code>.</td></tr>
          </tbody>
        </DocTable>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>04.3</span> Requirements não são dependências</div>
        <div className="manifest-comparison">
          <article><div><ShieldCheck size={22} /><span>RUNTIME</span></div><h2><code>requires</code></h2><p>Negocia a API já oferecida pela instalação. Declara domains e capabilities com uma versão mínima do contrato MineLoader.</p><code>events.action.authorization: 1.0.0</code></article>
          <article><div><Link2 size={22} /><span>OUTRO MOD</span></div><h2><code>dependencies</code></h2><p>Controla ordem de carga e permite importar a API pública de outro pacote por <code>mod.require()</code>.</p><code>ui_lib: 1.0.0</code></article>
        </div>
        <DocCallout title="Não existe OR em requires" tone="warning">Todos os domains e capabilities declarados são obrigatórios no formato atual. Se o mod puder seguir caminhos alternativos, exija apenas a superfície comum ou evolua o contrato primeiro.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>04.4</span> Um item com contrato avançado</div>
        <div className="doc-two-col align-start">
          <p className="doc-lead">Comida e combustível também são dados de manifesto. A bridge traduz os componentes da versão em uso.</p>
          <div className="doc-copy"><p>Para garantir a superfície, declare <code>registry.item.food</code>, <code>registry.item.food.effects</code> e <code>registry.item.fuel</code> em <code>requires.capabilities</code> conforme usar cada uma.</p><p>A saturação é um modificador; duração de efeito é medida em ticks; duração do consumo é medida em segundos.</p></div>
        </div>
        <DocCode language="mod.json">{foodManifest}</DocCode>
        <DocTable>
          <thead><tr><th>Campo</th><th>Limite</th><th>Observação</th></tr></thead>
          <tbody>
            <tr><td><code>nutrition</code></td><td>0–20</td><td>Pontos de fome.</td></tr>
            <tr><td><code>saturation</code></td><td>0–4</td><td>Modificador de saturação, não o valor final devolvido pelo jogo.</td></tr>
            <tr><td><code>consume_seconds</code></td><td>0.05–30</td><td>Duração de consumo completo.</td></tr>
            <tr><td><code>effects</code></td><td>até 8</td><td>Cada entrada usa id completo e duração de 1 a 120000 ticks.</td></tr>
            <tr><td><code>fuel_burn_time</code></td><td>0–32767</td><td>Tempo em ticks; zero não é combustível.</td></tr>
          </tbody>
        </DocTable>
      </section>

      <section className="doc-section">
        <DocCallout title="Valide antes de depender" tone="proof">O core valida ids, formatos, limites, dependências e capabilities antes de registrar conteúdo ou rodar o entrypoint. A especificação completa continua versionada no repositório do projeto.</DocCallout>
      </section>
    </DocsShell>
  );
}

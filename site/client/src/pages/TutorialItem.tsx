/**
 * Planta de Mineração: tutorial de item com escalada do objeto simples para
 * comida e combustível, sem misturar componentes internos de cada versão.
 */
import { Apple, Box, Flame } from "lucide-react";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";

const simpleItem = `{
  "schema": 1,
  "id": "fragmentos",
  "name": "Fragmentos Lunares",
  "version": "0.1.0",
  "items": [{
    "id": "fragmento_lunar",
    "name": "Fragmento Lunar",
    "max_stack_size": 64,
    "rarity": "rare",
    "fire_resistant": false,
    "texture": "@fragmento_lunar"
  }]
}`;

const foodItem = `{
  "items": [{
    "id": "racao_lunar",
    "name": "Ração Lunar",
    "texture": "@racao_lunar",
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
  }],
  "requires": {
    "capabilities": {
      "registry.item.food": "1.0.0",
      "registry.item.food.effects": "1.0.0",
      "registry.item.fuel": "1.0.0"
    }
  }
}`;

export default function TutorialItem() {
  return (
    <DocsShell
      index="T2"
      eyebrow="Tutorial · Criar um item"
      title={<>Comece pelo item.<br /><em>Evolua pelo contrato.</em></>}
      summary="Itens simples são declarativos. Para comida, efeitos e combustível, o manifesto acrescenta apenas os campos comuns que os quatro bridges conseguem traduzir."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>T2.1</span> Primeiro item</div>
        <div className="tutorial-outcome"><Box size={32} /><div><strong>Você terá um item na aba criativa do mod</strong><p>Identidade, empilhamento, raridade, resistência ao fogo e textura bastam para uma peça colecionável ou ingrediente de receita.</p></div><span>REGISTRY // ITEM</span></div>
        <div className="capability-proof-strip"><span>CONTRATO BASE</span><strong>registry.item</strong><i /><span>VALOR NORMALIZADO</span><strong>stack · raridade · textura</strong></div>
        <DocCode language="mod.json">{simpleItem}</DocCode>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T2.2</span> Transforme-o em comida</div>
        <div className="doc-two-col align-start"><p className="doc-lead">Comida e combustível podem coexistir no mesmo item. Cada bridge escolhe os componentes da sua versão.</p><div className="doc-copy"><p>O campo <code>saturation</code> é um modificador, não o valor final de saturação do jogo. Efeitos são aplicados quando o consumo termina e usam duração em ticks.</p><p>Como essa é uma superfície estendida, declare as três capabilities usadas pelo exemplo no mesmo manifesto.</p></div></div>
        <div className="item-registry-evidence" aria-label="Matriz que relaciona dados de item e capabilities declaradas">
          <div className="blueprint-heading"><span>REGISTRY // CAPABILITY</span><i /><span>DECLARADO</span></div>
          <div className="registry-row"><span>ITEM</span><b>identity + texture + stack</b><em>BASE</em></div>
          <div className="registry-row"><span>FOOD</span><b>nutrition + saturation</b><em>registry.item.food</em></div>
          <div className="registry-row"><span>EFFECTS</span><b>id + duração + chance</b><em>registry.item.food.effects</em></div>
          <div className="registry-row"><span>FUEL</span><b>fuel_burn_time</b><em>registry.item.fuel</em></div>
        </div>
        <DocCode language="mod.json">{foodItem}</DocCode>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T2.3</span> Limites que importam</div>
        <DocTable>
          <thead><tr><th>Campo</th><th>Regra</th><th>Motivo</th></tr></thead>
          <tbody>
            <tr><td><code>nutrition</code></td><td>inteiro de 0 a 20</td><td>Reproduz o limite comum da declaração de alimento.</td></tr>
            <tr><td><code>saturation</code></td><td>número finito de 0 a 4</td><td>É o modificador usado pelo jogo para calcular saturação efetiva.</td></tr>
            <tr><td><code>consume_seconds</code></td><td>0.05 a 30</td><td>Define a duração completa de consumo em todos os bridges.</td></tr>
            <tr><td><code>effects</code></td><td>até 8 entradas</td><td>Ids completos; duração de 1 a 120000; amplificador de 0 a 255.</td></tr>
            <tr><td><code>fuel_burn_time</code></td><td>0 a 32767 ticks</td><td>Zero não torna o item combustível.</td></tr>
          </tbody>
        </DocTable>
        <DocCallout title="Não misture papéis incompatíveis" tone="warning">Comida não combina com ferramenta, armadura ou <code>max_damage</code>. Combustível não combina com ferramenta ou armadura. O core recusa essas combinações antes do registro.</DocCallout>
      </section>

      <section className="doc-section tutorial-next-card">
        <Apple size={25} /><Flame size={25} /><div><strong>Próximo passo: receite o item</strong><p>Use uma receita declarativa para fabricar a ração e uma tag para incluí-la em sistemas de outros mods. O item continua sendo só dados de manifesto.</p></div>
      </section>
    </DocsShell>
  );
}

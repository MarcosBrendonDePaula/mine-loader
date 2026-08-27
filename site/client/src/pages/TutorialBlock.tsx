/**
 * Planta de Mineração: tutorial de bloco como uma ficha de registro, com a
 * declaração mínima primeiro e extensões de estado e comportamento depois.
 */
import { Blocks, Hammer, ShieldCheck } from "lucide-react";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";

const manifest = `{
  "schema": 1,
  "id": "pedra_lunar",
  "name": "Pedra Lunar",
  "version": "0.1.0",
  "blocks": [{
    "id": "pedra_lunar",
    "name": "Pedra Lunar",
    "type": "generic",
    "material": {
      "map_color": "gray",
      "sound": "stone",
      "solid": true,
      "opaque": true
    },
    "settings": {
      "hardness": 3.0,
      "resistance": 6.0,
      "requires_tool": true
    },
    "shape": { "collision": "full_cube" },
    "render": {
      "model": "cube_all",
      "texture": "@pedra_lunar"
    },
    "item": { "register": true }
  }]
}`;

const behavior = `-- scripts/pedra_lunar/on_use.lua
return function(ctx)
  ctx.player.send_message(
    "Você tocou a pedra em " .. ctx.block.x .. ", " .. ctx.block.y
  )
end`;

export default function TutorialBlock() {
  return (
    <DocsShell
      index="T1"
      eyebrow="Tutorial · Criar um bloco"
      title={<>Um bloco começa como<br /><em>uma declaração de mundo.</em></>}
      summary="Material, física, forma, render e drop pertencem ao manifesto. Assim o bridge consegue registrá-los no momento correto, antes de o jogo congelar seu registry."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>T1.1</span> Resultado</div>
        <div className="tutorial-outcome"><Blocks size={32} /><div><strong>Você terá um bloco cúbico colocável</strong><p>Ele terá som de pedra, exigirá ferramenta para ser minerado e ganhará automaticamente um BlockItem porque <code>item.register</code> está ativo.</p></div><span>ESTRUTURA // 01</span></div>
        <div className="blueprint-object blueprint-block" aria-label="Diagrama de um bloco cúbico, com seis faces, colisão cheia e coordenadas de referência">
          <div className="blueprint-heading"><span>VOXEL // GEOMETRIA</span><i /> <span>1 × 1 × 1</span></div>
          <div className="block-isometric"><i className="block-top" /><i className="block-left" /><i className="block-right" /><b>X+ / Y+ / Z+</b></div>
          <div className="block-measures"><span>COLLISION: FULL_CUBE</span><span>OUTLINE: FULL_CUBE</span><span>MODEL: CUBE_ALL</span></div>
        </div>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T1.2</span> Declare o bloco mínimo</div>
        <div className="doc-two-col align-start"><p className="doc-lead">Salve esta raiz como <code>mod.json</code> dentro de uma pasta de mod.</p><div className="doc-copy"><p><code>type: generic</code> é o tipo atualmente suportado para blocos declarativos. Não use <code>type</code> ou <code>base</code> para tentar criar escadas, slabs ou portas: essas famílias ainda não têm um contrato publicado.</p><p>O alias <code>@pedra_lunar</code> aponta para um recurso definido na seção <code>resources</code> do manifesto completo.</p></div></div>
        <DocCode language="mod.json">{manifest}</DocCode>
        <DocCallout title="Declare somente valores que têm tradução" tone="warning">`hardness` mede o tempo de quebra; `resistance` influencia explosões; `requires_tool` mantém a regra de ferramenta. O formato não aceita propriedades nativas arbitrárias de BlockState.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T1.3</span> Escolha a física e o loot</div>
        <DocTable>
          <thead><tr><th>Área</th><th>Comece com</th><th>Expanda quando precisar</th></tr></thead>
          <tbody>
            <tr><td><code>material</code></td><td>cor de mapa, som, sólido e opaco</td><td>inflamabilidade, propagação, instrumento e comportamento de pistão</td></tr>
            <tr><td><code>settings</code></td><td>hardness, resistance, requires_tool</td><td>luz, escorregamento, ticks, offset e bounds dinâmicos</td></tr>
            <tr><td><code>shape</code></td><td><code>full_cube</code></td><td>caixas declaradas de colisão, contorno e visual</td></tr>
            <tr><td><code>loot</code></td><td>ausente: comportamento padrão</td><td>item, quantidade, tabela ou drops de outro bloco</td></tr>
            <tr><td><code>state</code></td><td>ausente: estado simples</td><td>propriedades próprias e default declarado</td></tr>
          </tbody>
        </DocTable>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T1.4</span> Reagir a um clique</div>
        <div className="doc-two-col align-start"><p className="doc-lead">Comportamento específico vai em um arquivo Lua e é referenciado pelo bloco.</p><div className="doc-copy"><p>Adicione <code>{'"behavior": {"on_use": "scripts/pedra_lunar/on_use.lua"}'}</code> à definição do bloco e declare <code>chat.send</code> em <code>permissions</code> se for enviar mensagem.</p><p>O callback recebe o contexto do bloco em valores simples: id, posição e variante. Ele não recebe a instância Java do bloco.</p></div></div>
        <DocCode language="scripts/pedra_lunar/on_use.lua">{behavior}</DocCode>
        <DocCallout title="Próxima extensão" tone="proof">Depois de validar o bloco, acrescente <code>tags</code> para mineração, <code>recipes</code> para fabricação ou <code>state.properties</code> se ele precisar mudar de variante.</DocCallout>
      </section>
    </DocsShell>
  );
}

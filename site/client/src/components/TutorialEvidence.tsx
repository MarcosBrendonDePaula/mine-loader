/**
 * Planta de Mineração: cada assunto mostra uma evidência técnica própria,
 * configurada pela fonte JSON e sem transformar código em único protagonista.
 */
import { Pointer } from "lucide-react";
import type { TutorialDocument } from "@/lib/tutorialData";

export function TutorialEvidence({ evidence }: { evidence: TutorialDocument["evidence"] }) {
  const heading = <div className="blueprint-heading"><span>{evidence.label}</span><i /><span>{evidence.meta}</span></div>;
  if (evidence.kind === "block_geometry") return <div className="blueprint-object blueprint-block" aria-label="Diagrama de um bloco cúbico declarativo">{heading}<div className="block-isometric"><i className="block-top" /><i className="block-left" /><i className="block-right" /><b>X+ / Y+ / Z+</b></div><div className="block-measures">{evidence.items?.map((item) => <span key={item}>{item}</span>)}</div></div>;
  if (evidence.kind === "item_registry") return <div className="item-registry-evidence" aria-label="Matriz de registro e capabilities do item">{heading}{evidence.rows?.map(([kind, value, capability]) => <div className="registry-row" key={kind}><span>{kind}</span><b>{value}</b><em>{capability}</em></div>)}</div>;
  if (evidence.kind === "menu_grid") return <div className="menu-grid-evidence" aria-label="Grade de menu vanilla com nove slots">{heading}<div className="menu-slots">{Array.from({ length: 9 }, (_, slot) => <span className={slot === evidence.selected_slot ? "is-selected" : ""} key={slot}>{slot}</span>)}</div><div className="menu-evidence-foot"><Pointer size={14} /> clique → <code>ctx.menu.slot</code> → callback Lua</div></div>;
  return <div className="lua-flow-evidence" aria-label="Fluxo entre bridge, dados simples e código Lua">{heading}<div className="lua-flow-stages">{evidence.stages?.flatMap(([label, detail], index) => [<span key={label}>{label}<br /><b>{detail}</b></span>, index < (evidence.stages?.length ?? 0) - 1 ? <i key={`${label}-arrow`}>→</i> : null])}</div></div>;
}

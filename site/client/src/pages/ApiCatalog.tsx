/**
 * Planta de Mineração: catálogo por domínio, organizado como cartões de matriz
 * para mostrar a API útil sem confundir contrato estável com APIs internas.
 */
import { Box, CloudSun, Command, Crosshair, Gamepad2, PackageCheck, ShieldCheck, UserRound } from "lucide-react";
import { DocCallout, DocsShell, DocTable } from "@/components/DocsShell";

const domains = [
  { code: "REG", title: "Registro declarativo", icon: PackageCheck, statement: "Conteúdo que deve existir antes de o jogo carregar.", rows: [["Blocos e itens", "blocks[], items[]", "registry.*"], ["Comida e combustível", "items[].food, fuel_burn_time", "registry.item.food.*"], ["Receitas, loot e tags", "recipes, loot, tags", "registry.*"]] },
  { code: "WRL", title: "Mundo", icon: CloudSun, statement: "Consultas e mutações server-side com permissões e limites próprios.", rows: [["Bloco e estado", "block_state(), set_block_state()", "world.read / world.write"], ["Tempo, clima e regras", "time_of_day(), weather(), game_rule()", "world.read / world.write"], ["Efeitos físicos", "explode(), strike_lightning(), drop_item()", "world.explode / world.lightning / entity.spawn"]] },
  { code: "PLY", title: "Jogador", icon: UserRound, statement: "Snapshots portáveis e operações explícitas de inventário e estado.", rows: [["Estado e movimento", "health(), food(), movement(), effects()", "player.read"], ["Equipamento e slots", "equipment(), inventory_slot()", "player.equipment.read / player.inventory.slot"], ["Mutação", "set_health(), give_experience(), set_inventory_slot()", "player.modify / player.inventory"]] },
  { code: "EVT", title: "Eventos e proteção", icon: ShieldCheck, statement: "Callbacks globais e específicos, sem expor o barramento nativo da plataforma.", rows: [["Quebra global", "mod.on(\"block_broken\", fn)", "events.block.break"], ["Autorização", "mod.on(\"action_attempt\", fn)", "events.action.authorization"], ["Entidades", "entity_spawned, entity_damaged, entity_died", "events.*"]] },
  { code: "CMD", title: "Comandos e tarefas", icon: Command, statement: "Interação de servidor com estrutura declarada e execução controlada.", rows: [["Comandos", "commands + mod.command()", "server.command.register"], ["Autocomplete", "arguments tipados e sugestões", "server.command.schema"], ["Agendamento", "mod.after(), mod.every(), mod.cancel()", "scheduler.every"]] },
  { code: "CLT", title: "Cliente mediado", icon: Gamepad2, statement: "Input e visual via contrato serializado, com lógica Lua mantida no servidor.", rows: [["Hotkeys", "keybinds + mod.keybind()", "client.input.register"], ["Câmeras", "cameras + mod.camera()", "client.camera.virtual"], ["Mapa", "map.server_cells / client_camera", "map.*"]] },
];

export default function ApiCatalog() {
  return (
    <DocsShell
      index="03"
      eyebrow="Catálogo de APIs"
      title={<>A superfície do jogo,<br /><em>sem a superfície da plataforma.</em></>}
      summary="A API estável é um vocabulário de gameplay. Ela seleciona operações úteis, versiona suas capacidades e deixa mappings, classes e objetos nativos dentro de cada bridge."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>03.1</span> Como ler este catálogo</div>
        <div className="doc-two-col">
          <p className="doc-lead">Uma função, uma permission e uma capability não são sinônimos. Juntas, elas descrevem uma operação portável.</p>
          <div className="doc-copy"><p>A função é o que Lua chama. A permission é a autorização do mod. A capability é o requisito que o runtime precisa entregar. Algumas declarações de registro não exigem uma permission nova porque elas acontecem durante a carga declarativa.</p><p>Os nomes abaixo representam contratos comuns aos quatro runtimes atualmente mantidos.</p></div>
        </div>
        <div className="api-legend"><span><i className="legend-mark legend-copper" />AÇÃO OU REGISTRO</span><span><i className="legend-mark legend-moss" />CONTRATO VERIFICADO</span><span><i className="legend-mark legend-ink" />PERMISSÃO EXPLÍCITA</span></div>
      </section>

      <section className="doc-section api-catalog-grid">
        {domains.map((domain) => {
          const Icon = domain.icon;
          return (
            <article className="api-domain" key={domain.code}>
              <header><span>{domain.code} // 1.0.0</span><Icon size={24} strokeWidth={1.55} /></header>
              <h2>{domain.title}</h2>
              <p>{domain.statement}</p>
              <DocTable>
                <thead><tr><th>Superfície</th><th>Entrada</th><th>Requisito</th></tr></thead>
                <tbody>{domain.rows.map((row) => <tr key={row[0]}><td>{row[0]}</td><td><code>{row[1]}</code></td><td><code>{row[2]}</code></td></tr>)}</tbody>
              </DocTable>
            </article>
          );
        })}
      </section>

      <section className="doc-section">
        <DocCallout title="O catálogo é fechado de propósito" tone="warning">A API não aceita um mapa livre de chamadas nativas, NBT, data components, ItemStack, BlockState ou outros objetos que mudem de forma entre versões. Uma necessidade nova deve nascer como um contrato próprio e testável.</DocCallout>
        <DocCallout title="Sobre os testes" tone="proof">A matriz atual passa 26/26 GameTests obrigatórios em cada runtime. Esses testes confirmam a integração server-side dos contratos; não provam pixels, FPS, animação visual ou UX completa no cliente.</DocCallout>
      </section>
    </DocsShell>
  );
}

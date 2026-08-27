/**
 * Planta de Mineração: tutorial de UI baseado em menus vanilla, explicando a
 * fronteira intencional entre uma grade interativa e um HUD customizado.
 */
import { Command, PanelsTopLeft, Pointer } from "lucide-react";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";

const manifest = `{
  "schema": 1,
  "id": "meu_painel",
  "name": "Meu Painel",
  "version": "0.1.0",
  "entrypoint": "main.lua",
  "permissions": [
    "player.menu",
    "server.command.register"
  ]
}`;

const menuCode = `local function desenhar(ctx)
  return {
    { item = "minecraft:book", label = "Ler regras" },
    { item = "minecraft:barrier", label = "Fechar" }
  }
end

mod.menu("guia", function(ctx)
  if ctx.menu.slot == 1 then
    ctx.player.close_menu()
    return
  end
  ctx.player.update_menu(desenhar(ctx))
end)

mod.command("guia", function(ctx)
  if ctx.player then
    ctx.player.open_menu("guia", "Guia do Mod", 1, desenhar(ctx))
  end
end)`;

export default function TutorialUI() {
  return (
    <DocsShell
      index="T3"
      eyebrow="Tutorial · Criar uma UI"
      title={<>Uma UI útil começa<br /><em>com uma grade de itens.</em></>}
      summary="Menus usam a tela de container nativa do Minecraft. Cada slot é uma célula visual e um botão; os cliques voltam ao Lua como dados simples."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>T3.1</span> O modelo de interação</div>
        <div className="tutorial-outcome"><PanelsTopLeft size={32} /><div><strong>Você terá um comando que abre uma janela</strong><p>O menu tem uma linha, recebe uma lista de itens e retorna <code>ctx.menu.slot</code> quando a pessoa clica. Nada é retirado dos slots.</p></div><span>UI // VANILLA</span></div>
        <div className="menu-grid-evidence" aria-label="Representação de uma grade de menu de uma linha e nove slots, com o segundo slot selecionado">
          <div className="blueprint-heading"><span>CONTAINER // 9 × 1</span><i /><span>EVENTO: SLOT 1</span></div>
          <div className="menu-slots">{Array.from({ length: 9 }, (_, index) => <span className={index === 1 ? "is-selected" : ""} key={index}>{index}</span>)}</div>
          <div className="menu-evidence-foot"><Pointer size={14} /> clique → <code>ctx.menu.slot</code> → callback Lua</div>
        </div>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T3.2</span> Declare o acesso</div>
        <div className="doc-two-col align-start"><p className="doc-lead">Abrir ou atualizar uma janela exige <code>player.menu</code>. Registrar o atalho <code>/mod guia</code> exige <code>server.command.register</code>.</p><div className="doc-copy"><p>Essas permissões pertencem ao pacote, não ao jogador. O callback precisa confirmar <code>ctx.player</code> porque um comando também pode vir do console.</p><p>Um menu simples não requer capability extra: ele usa o contrato básico de menus declarado pelo runtime.</p></div></div>
        <DocCode language="mod.json">{manifest}</DocCode>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T3.3</span> Registre e abra o menu</div>
        <DocCode language="main.lua">{menuCode}</DocCode>
        <DocCallout title="Slots começam em zero" tone="warning">No exemplo, o segundo item da tabela Lua é lido como <code>ctx.menu.slot == 1</code>. Essa diferença evita que a UI esconda o índice nativo da janela.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T3.4</span> Regras da grade</div>
        <DocTable>
          <thead><tr><th>Operação</th><th>O que faz</th><th>Quando usar</th></tr></thead>
          <tbody>
            <tr><td><code>mod.menu(id, fn)</code></td><td>Registra o callback da janela uma vez.</td><td>Na carga do <code>main.lua</code>.</td></tr>
            <tr><td><code>open_menu(id, título, linhas, itens)</code></td><td>Abre uma grade de 9 × linhas.</td><td>Ao reagir a comando, clique ou outro evento de jogador.</td></tr>
            <tr><td><code>update_menu(itens)</code></td><td>Redesenha e mantém a janela aberta.</td><td>Depois de compra, seleção, paginação ou mudança de saldo.</td></tr>
            <tr><td><code>close_menu()</code></td><td>Fecha a janela atual.</td><td>Ao clicar em “Fechar” ou completar um fluxo.</td></tr>
          </tbody>
        </DocTable>
        <DocCallout title="Limite intencional" tone="proof">Menus funcionam em clientes vanilla porque reutilizam a tela de container. <code>open_screen</code> pertence à superfície client-side e depende de suporte no bridge/cliente; consulte a API e a matriz antes de depender dela. Não tente transformar este menu em uma UI arbitrária.</DocCallout>
      </section>
    </DocsShell>
  );
}

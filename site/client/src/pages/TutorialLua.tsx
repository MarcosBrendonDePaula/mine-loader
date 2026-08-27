/**
 * Planta de Mineração: tutorial de comportamento Lua centrado em eventos e
 * comandos, mantendo a separação entre lógica declarada e bridge de plataforma.
 */
import { Code2, ShieldCheck, TerminalSquare } from "lucide-react";
import { DocCallout, DocCode, DocsShell, DocTable } from "@/components/DocsShell";

const manifest = `{
  "schema": 1,
  "id": "meu_codigo",
  "name": "Meu Código Lua",
  "version": "0.1.0",
  "entrypoint": "main.lua",
  "permissions": [
    "chat.send",
    "player.read",
    "server.command.register"
  ]
}`;

const code = `mod.command("onde", function(ctx)
  if ctx.player == nil then
    ctx.log.info("Este comando precisa de jogador.")
    return
  end

  local alvo = ctx.player.looking_at()
  if alvo == nil then
    ctx.player.send_message("Olhe para um bloco.")
    return
  end

  ctx.player.send_message(
    "Alvo: " .. alvo.x .. ", " .. alvo.y .. ", " .. alvo.z
  )
end)`;

const authorization = `-- Requer events.action.authorization: 1.0.0
mod.on("action_attempt", function(ctx)
  if ctx.action == "block.break"
    and ctx.target.id == "minecraft:obsidian" then
    return false -- cancela antes da mudança no mundo
  end
end)`;

export default function TutorialLua() {
  return (
    <DocsShell
      index="T4"
      eyebrow="Tutorial · Criar código Lua"
      title={<>Escreva regras.<br /><em>Não persiga mappings.</em></>}
      summary="Lua controla comportamento: eventos, comandos, tarefas e menus. Contextos são snapshots com tabelas, IDs e escalares; o core nunca entrega referências Java vivas ao script."
    >
      <section className="doc-section doc-opening">
        <div className="doc-section-label"><span>T4.1</span> Estrutura mínima</div>
        <div className="tutorial-outcome"><Code2 size={32} /><div><strong>Você terá o comando <code>/mod onde</code></strong><p>Ele só roda para um jogador, pergunta onde ele está mirando e devolve coordenadas usando as APIs seguras do loader.</p></div><span>LUA // SANDBOX</span></div>
        <div className="lua-flow-evidence" aria-label="Fluxo de evento, contexto normalizado, sandbox Lua e resultado devolvido ao bridge">
          <div className="blueprint-heading"><span>EVENT FLOW // DADOS SIMPLES</span><i /><span>CORE AGNÓSTICO</span></div>
          <div className="lua-flow-stages"><span>EVENTO<br /><b>bridge</b></span><i>→</i><span>CTX<br /><b>tabelas</b></span><i>→</i><span>LUA<br /><b>regra</b></span><i>→</i><span>RETORNO<br /><b>allow/block</b></span></div>
        </div>
        <DocCode language="mod.json">{manifest}</DocCode>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T4.2</span> Escreva a regra</div>
        <DocCode language="main.lua">{code}</DocCode>
        <div className="doc-two-col align-start"><p className="doc-lead">O código não precisa descobrir se está em Fabric ou NeoForge. <code>looking_at()</code> devolve a mesma tabela do contrato nas quatro combinações.</p><div className="doc-copy"><p>Quando a chamada nasce de um comando, clique ou join, <code>ctx.player</code> representa quem acionou a regra. Fora desse contexto ele pode ser <code>nil</code>, então confira antes de tocar APIs pessoais.</p><p>Use <code>ctx.log</code> para diagnóstico de mod; use mensagens para retorno ao jogador e declare <code>chat.send</code>.</p></div></div>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T4.3</span> Eventos canceláveis</div>
        <div className="doc-two-col align-start"><p className="doc-lead">Alguns eventos deixam o script vetar a operação devolvendo <code>false</code>. A autorização global é o exemplo para claims.</p><div className="doc-copy"><p>Ela precisa de <code>events.action.authorization: 1.0.0</code> em <code>requires.capabilities</code>. Erros nesse tipo de handler fecham a ação por segurança, então mantenha a regra objetiva.</p></div></div>
        <DocCode language="main.lua">{authorization}</DocCode>
        <DocCallout title="Não cancele por acidente" tone="warning">`nil`, `true` e outros retornos deixam a ação seguir. Somente devolva <code>false</code> quando a regra decidiu bloquear. O MVP cobre quebra, colocação e uso iniciado por jogador; há GameTest do dispatcher/exemplo, não uma simulação nativa completa de cada interação física.</DocCallout>
      </section>

      <section className="doc-section">
        <div className="doc-section-label"><span>T4.4</span> Escolha a ferramenta certa</div>
        <DocTable>
          <thead><tr><th>Você quer…</th><th>Use</th><th>Observação</th></tr></thead>
          <tbody>
            <tr><td>Responder a acontecimento global</td><td><code>mod.on(evento, fn)</code></td><td>Exemplo: autorização, quebra ou entidade.</td></tr>
            <tr><td>Adicionar um comando</td><td><code>mod.command(nome, fn)</code></td><td>Publicado como <code>/mod nome</code>.</td></tr>
            <tr><td>Executar depois</td><td><code>mod.after(ticks, fn)</code></td><td>Execução única, sem criar loop em <code>tick</code>.</td></tr>
            <tr><td>Repetir até encerrar</td><td><code>mod.every(ticks, fn)</code></td><td>Devolva <code>false</code> para interromper a tarefa.</td></tr>
            <tr><td>Dividir o código</td><td><code>mod.import("lib/x.lua")</code></td><td>Permanece preso à pasta do próprio mod.</td></tr>
          </tbody>
        </DocTable>
        <DocCallout title="Regra de fronteira" tone="proof">Não use <code>require</code>, <code>dofile</code> ou <code>loadfile</code>. O sandbox expõe <code>mod.import</code>, que mantém o caminho preso ao pacote e detecta ciclos de importação.</DocCallout>
      </section>
    </DocsShell>
  );
}

-- Estender o bestiario de outro mod acontece no manifesto, e nao aqui.
--
-- O par que escreve -- um register_entity chamado por script -- nao existe de proposito: no Fabric
-- o Lua carrega antes de o jogo congelar os registros, e no NeoForge depois. A funcao valeria numa
-- plataforma e falharia sempre na outra, e um mod assim passa nos testes de quem o escreveu e some
-- para metade de quem o usa.
--
-- O que existe aqui e a leitura, que e honesta em qualquer momento: descobrir o que os outros mods
-- declararam para saber de que herdar.

local function on_loader_ready(ctx)
  local declaradas = ctx.server.declared_entities()

  for _, id in ipairs(declaradas) do
    local definicao = ctx.server.entity_definition(id)
    if definicao then
      ctx.log.info(string.format(
        "%s (%s) descende de %s, com %s de vida",
        definicao.name or id, id, definicao.base or "?", tostring(definicao.health or "padrao")))
    end
  end

  -- A especie do jogo nao e declarada por mod nenhum, e a diferenca importa: "nao existe" e
  -- "existe e nao e daqui" levam a decisoes diferentes em quem monta um bestiario sobre o outro.
  if ctx.server.entity_definition("minecraft:zombie") ~= nil then
    ctx.log.warn("ERRO: uma especie do jogo apareceu como declarada")
  end
end

return {
  on_loader_ready = on_loader_ready
}

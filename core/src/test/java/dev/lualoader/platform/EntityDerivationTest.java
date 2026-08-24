package dev.lualoader.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Espécies que descendem de espécies declaradas por outros mods.
 *
 * <p>É o registro externo que funciona nas duas plataformas: um mod estende o bestiário de outro na
 * carga, e não em tempo de execução. Registrar em execução seria um recurso só do Fabric — lá o Lua
 * carrega antes de o jogo congelar os registros, e no NeoForge depois —, e um recurso de uma
 * plataforma só quebra a promessa de o mesmo mod rodar nas duas.
 *
 * <p>Tudo aqui roda sem Minecraft, porque a pergunta não é sobre Minecraft: ordenar por dependência
 * e recusar um ciclo é aritmética sobre texto.
 */
class EntityDerivationTest {

    private static final Set<String> BASES_DO_JOGO =
            Set.of("minecraft:zombie", "minecraft:iron_golem", "minecraft:wolf");

    private static EntityDefinition especie(String id, String base) {
        EntityDefinition definition = new EntityDefinition();
        definition.id = id;
        definition.name = id;
        definition.base = base;
        return definition;
    }

    private static EntityDerivation.Result resolve(Map<String, List<EntityDefinition>> mods) {
        return EntityDerivation.resolve(mods, BASES_DO_JOGO::contains);
    }

    @Test
    void especieDoJogoNaoTemPaiDeclarado() {
        var result = resolve(Map.of("conteudo",
                List.of(especie("guardiao", "minecraft:iron_golem"))));

        assertTrue(result.rejected().isEmpty(), () -> "recusas: " + result.rejected());
        assertEquals(List.of("conteudo:guardiao"), EntityDerivation.idsOf(result.ordered()));
        assertNull(result.ordered().get(0).parent());
    }

    @Test
    void oPaiEntraAntesDoFilhoMesmoDeclaradoDepois() {
        // O mod de dificuldade e descoberto primeiro, e mesmo assim o guardiao registra antes.
        // Sem a ordenacao o adaptador procuraria uma base que ainda nao existe e recusaria uma
        // especie perfeitamente declarada.
        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "dificuldade", List.of(especie("elite", "conteudo:guardiao")),
                "conteudo", List.of(especie("guardiao", "minecraft:iron_golem")))));

        assertTrue(result.rejected().isEmpty(), () -> "recusas: " + result.rejected());
        assertEquals(List.of("conteudo:guardiao", "dificuldade:elite"),
                EntityDerivation.idsOf(result.ordered()));
        assertEquals("conteudo:guardiao", result.ordered().get(1).parent());
    }

    @Test
    void aBaseEfetivaEADoAncestralDoJogo() {
        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "conteudo", List.of(especie("guardiao", "minecraft:iron_golem")),
                "dificuldade", List.of(especie("elite", "conteudo:guardiao")))));

        // O adaptador precisa de uma especie do jogo para achar modelo e comportamento; entregar
        // "conteudo:guardiao" ali faria a busca falhar em algo que esta certo.
        assertEquals("minecraft:iron_golem", result.ordered().get(1).definition().base);
    }

    @Test
    void oQueOFilhoNaoDeclaraVemDoPai() {
        EntityDefinition pai = especie("guardiao", "minecraft:iron_golem");
        pai.defaults = new EntitySpec();
        pai.defaults.health = 60.0;
        pai.defaults.glowing = true;
        pai.defaults.attributes = new java.util.LinkedHashMap<>(
                Map.of("minecraft:generic.attack_damage", 9.0));
        pai.loot.drops.add(drop("crystal:shard"));

        EntityDefinition filho = especie("elite", "conteudo:guardiao");
        filho.defaults = new EntitySpec();
        filho.defaults.health = 120.0;
        filho.defaults.attributes = new java.util.LinkedHashMap<>(
                Map.of("minecraft:generic.movement_speed", 0.4));
        filho.loot.drops.add(drop("crystal:core"));

        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "conteudo", List.of(pai), "dificuldade", List.of(filho))));
        EntityDefinition elite = result.ordered().get(1).definition();

        assertEquals(120.0, elite.defaults.health, "o filho vence no que declarou");
        assertEquals(Boolean.TRUE, elite.defaults.glowing, "e herda o que nao declarou");

        // Por chave, e nao em bloco: declarar so a velocidade nao deveria apagar o dano do pai --
        // e o que deixa um mod de dificuldade ter tres linhas em vez de repetir tudo.
        assertEquals(9.0, elite.defaults.attributesOrEmpty().get("minecraft:generic.attack_damage"));
        assertEquals(0.4, elite.defaults.attributesOrEmpty().get("minecraft:generic.movement_speed"));

        // Drops somam: sao coisas independentes, e o filho acrescentar uma nao quer dizer que ele
        // desistiu da que o pai dava.
        assertEquals(List.of("crystal:shard", "crystal:core"),
                elite.loot.drops.stream().map(d -> d.item).toList());
    }

    @Test
    void oOvoNaoEHerdado() {
        EntityDefinition pai = especie("guardiao", "minecraft:iron_golem");
        pai.spawnEgg = new EntityDefinition.SpawnEggDefinition();

        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "conteudo", List.of(pai),
                "dificuldade", List.of(especie("elite", "conteudo:guardiao")))));

        // Herdar criaria um ovo por descendente, todos com a mesma cor e nomes parecidos --
        // indistinguiveis na aba do criativo.
        assertNull(result.ordered().get(1).definition().spawnEgg);
    }

    @Test
    void heredadeCircularERecusadaComOCaminho() {
        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "a", List.of(especie("um", "b:dois")),
                "b", List.of(especie("dois", "a:um")))));

        assertTrue(result.ordered().isEmpty(), "nenhuma especie de um ciclo pode registrar");
        assertTrue(result.rejected().stream().anyMatch(r -> r.reason().contains("circular")),
                () -> "recusas: " + result.rejected());
        // O caminho entra na mensagem: quem escreveu precisa ver onde a volta fecha.
        assertTrue(result.rejected().stream().anyMatch(r -> r.reason().contains("->")),
                () -> "recusas: " + result.rejected());
    }

    @Test
    void baseDesconhecidaERecusadaSemDerrubarOResto() {
        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "conteudo", List.of(especie("guardiao", "minecraft:iron_golem")),
                "quebrado", List.of(especie("fantasma", "minecraft:allay")))));

        // Um bestiario torto nao leva os outros junto: o mod ao lado continua valendo.
        assertEquals(List.of("conteudo:guardiao"), EntityDerivation.idsOf(result.ordered()));
        assertEquals(1, result.rejected().size());
        assertTrue(result.rejected().get(0).reason().contains("minecraft:allay"));
    }

    @Test
    void oFilhoDeUmaBaseRecusadaCaiJunto() {
        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "quebrado", List.of(especie("fantasma", "minecraft:allay")),
                "dificuldade", List.of(especie("elite", "quebrado:fantasma")))));

        assertTrue(result.ordered().isEmpty());
        assertEquals(2, result.rejected().size(), "os dois sao ditos, cada um com o seu motivo");
    }

    @Test
    void doisFilhosDoMesmoPaiNaoSaoUmCiclo() {
        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "conteudo", List.of(especie("guardiao", "minecraft:iron_golem")),
                "dificuldade", List.of(especie("elite", "conteudo:guardiao"),
                        especie("veterano", "conteudo:guardiao")))));

        // Uma marca unica de "ja visitei" acusaria o segundo irmao como ciclo. O que detecta ciclo
        // e o caminho da descida, e nao o conjunto do que ja foi visto.
        assertTrue(result.rejected().isEmpty(), () -> "recusas: " + result.rejected());
        assertEquals(3, result.ordered().size());
    }

    @Test
    void aCorrenteDeTresGeracoesOrdenaDaRaizParaAFolha() {
        var result = resolve(new java.util.LinkedHashMap<>(Map.of(
                "c", List.of(especie("neto", "b:filho")),
                "b", List.of(especie("filho", "a:avo")),
                "a", List.of(especie("avo", "minecraft:zombie")))));

        assertEquals(List.of("a:avo", "b:filho", "c:neto"),
                EntityDerivation.idsOf(result.ordered()));
        assertEquals("minecraft:zombie", result.ordered().get(2).definition().base);
    }

    @Test
    void variantesDoMesmoModHerdamDoProprioTronco() {
        // O caso de quem quer tres guardioes sem repetir o bestiario: o tronco e declarado uma vez
        // e cada variante muda so o que a distingue. E "gerar por JSON" no sentido que importa --
        // sem laco, mas tambem sem copiar o bloco inteiro tres vezes.
        EntityDefinition tronco = especie("guardiao", "minecraft:iron_golem");
        tronco.fireImmune = true;
        tronco.defaults = new EntitySpec();
        tronco.defaults.health = 40.0;
        tronco.defaults.glowing = true;
        tronco.loot.drops.add(drop("minecraft:iron_ingot"));

        EntityDefinition prata = especie("guardiao_prata", "bestiario:guardiao");
        prata.defaults = new EntitySpec();
        prata.defaults.health = 80.0;

        EntityDefinition ouro = especie("guardiao_ouro", "bestiario:guardiao");
        ouro.defaults = new EntitySpec();
        ouro.defaults.health = 160.0;

        var result = resolve(Map.of("bestiario", List.of(prata, ouro, tronco)));
        assertTrue(result.rejected().isEmpty(), () -> "recusas: " + result.rejected());

        // O tronco entra antes das variantes, mesmo declarado depois delas na lista.
        assertEquals("bestiario:guardiao", EntityDerivation.idsOf(result.ordered()).get(0));
        assertEquals(3, result.ordered().size());

        var porId = new java.util.HashMap<String, EntityDefinition>();
        for (var resolved : result.ordered()) porId.put(resolved.id(), resolved.definition());

        // Cada uma muda a vida e herda o resto: imunidade a fogo, brilho, saque e a base do jogo.
        assertEquals(80.0, porId.get("bestiario:guardiao_prata").defaults.health);
        assertEquals(160.0, porId.get("bestiario:guardiao_ouro").defaults.health);
        for (String id : List.of("bestiario:guardiao_prata", "bestiario:guardiao_ouro")) {
            EntityDefinition variante = porId.get(id);
            assertEquals("minecraft:iron_golem", variante.base, id + " deveria herdar a base");
            assertTrue(variante.fireImmune, id + " deveria herdar a imunidade a fogo");
            assertEquals(Boolean.TRUE, variante.defaults.glowing);
            assertEquals(List.of("minecraft:iron_ingot"),
                    variante.loot.drops.stream().map(d -> d.item).toList());
        }
    }

    private static EntityDefinition.EntityDropDefinition drop(String item) {
        var drop = new EntityDefinition.EntityDropDefinition();
        drop.item = item;
        return drop;
    }
}

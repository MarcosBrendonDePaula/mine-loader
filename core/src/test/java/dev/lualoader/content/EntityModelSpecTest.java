package dev.lualoader.content;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A geometria declarada de uma espécie, verificada sem abrir o jogo.
 *
 * <p>Toda recusa aqui existe porque a alternativa é invisível: uma caixa sem tamanho, um pivô com
 * dois números ou um osso com nome que a base não anima produzem, cada um, uma peça que não
 * aparece. O bicho sai sem um braço, o log fica limpo, e quem desenhou o modelo procura o erro no
 * lugar errado.
 */
class EntityModelSpecTest {

    private static final String GOLEM = """
            {
              "texture_size": [128, 128],
              "bones": {
                "head": {
                  "pivot": [0, -7, -2],
                  "cubes": [ { "from": [-4, -12, -5.5], "size": [8, 10, 8], "uv": [0, 0] } ]
                },
                "body": {
                  "pivot": [0, -7, 0],
                  "cubes": [
                    { "from": [-9, -2, -6], "size": [18, 12, 11], "uv": [0, 40] },
                    { "from": [-4.5, 10, -3], "size": [9, 5, 6], "uv": [0, 70], "inflate": 0.5 }
                  ]
                },
                "right_arm": {
                  "pivot": [0, -7, 0],
                  "cubes": [ { "from": [-13, -2.5, -3], "size": [4, 30, 6], "uv": [60, 21] } ]
                }
              }
            }
            """;

    @Test
    void aGeometriaDeclaradaEEntendida() {
        EntityModelSpec spec = EntityModelSpec.parse(GOLEM);

        assertEquals(128, spec.textureWidth);
        assertEquals(128, spec.textureHeight);
        assertEquals(3, spec.bones.size());

        EntityModelSpec.Bone body = spec.bones.get("body");
        assertEquals(-7.0f, body.pivotY());
        assertEquals(2, body.cubes().size());

        EntityModelSpec.Cube capa = body.cubes().get(1);
        // inflate cresce a caixa sem mover o pivo: e como se faz uma camada externa que nao briga
        // com a de baixo.
        assertEquals(0.5f, capa.inflate());
        assertEquals(0, capa.uvX());
        assertEquals(70, capa.uvY());
    }

    @Test
    void oTamanhoDeTexturaTemPadrao() {
        EntityModelSpec spec = EntityModelSpec.parse("""
                { "bones": { "head": { "cubes": [ { "from": [0,0,0], "size": [8,8,8] } ] } } }
                """);
        assertEquals(64, spec.textureWidth);
        assertEquals(64, spec.textureHeight);

        // Sem pivo declarado a peca gira em torno da origem, que e o que o formato do jogo faz.
        assertEquals(0.0f, spec.bones.get("head").pivotY());
    }

    // ------------------------------------------------------------------ o que e recusado

    @Test
    void modeloSemOssoERecusado() {
        var erro = assertThrows(EntityModelSpec.InvalidModelException.class,
                () -> EntityModelSpec.parse("{ \"bones\": {} }"));
        assertTrue(erro.getMessage().contains("sem osso"));
    }

    @Test
    void ossoSemCaixaERecusado() {
        // Um osso vazio nao desenha nada. Aceitar em silencio faria a peca sumir sem motivo.
        var erro = assertThrows(EntityModelSpec.InvalidModelException.class,
                () -> EntityModelSpec.parse("{ \"bones\": { \"head\": { \"cubes\": [] } } }"));
        assertTrue(erro.getMessage().contains("head"));
    }

    @Test
    void pivoComNumerosDeMenosERecusado() {
        var erro = assertThrows(EntityModelSpec.InvalidModelException.class,
                () -> EntityModelSpec.parse("""
                        { "bones": { "head": { "pivot": [0, 24],
                          "cubes": [ { "from": [0,0,0], "size": [8,8,8] } ] } } }
                        """));
        // A mensagem diz qual campo e de qual osso: um "lista invalida" mandaria procurar em tudo.
        assertTrue(erro.getMessage().contains("pivot"), erro.getMessage());
        assertTrue(erro.getMessage().contains("head"), erro.getMessage());
    }

    @Test
    void caixaSemTamanhoERecusada() {
        var erro = assertThrows(EntityModelSpec.InvalidModelException.class,
                () -> EntityModelSpec.parse("""
                        { "bones": { "head": { "cubes": [ { "from": [0,0,0] } ] } } }
                        """));
        assertTrue(erro.getMessage().contains("size"), erro.getMessage());
    }

    @Test
    void tamanhoNegativoERecusado() {
        assertThrows(EntityModelSpec.InvalidModelException.class,
                () -> EntityModelSpec.parse("""
                        { "bones": { "head": { "cubes": [
                          { "from": [0,0,0], "size": [8,-4,8] } ] } } }
                        """));
    }

    @Test
    void jsonQuebradoERecusadoComoModelo() {
        assertThrows(EntityModelSpec.InvalidModelException.class,
                () -> EntityModelSpec.parse("isto nao e json"));
    }

    // ------------------------------------------------------------------ nomes de osso

    @Test
    void ossoQueABaseAnimaNaoEReclamado() {
        EntityModelSpec spec = EntityModelSpec.parse(GOLEM);
        assertTrue(spec.unknownBones("minecraft:iron_golem").isEmpty(),
                () -> "veio " + spec.unknownBones("minecraft:iron_golem"));
    }

    @Test
    void ossoComNomeQueABaseNaoConheceEDenunciado() {
        EntityModelSpec spec = EntityModelSpec.parse("""
                { "bones": {
                    "head": { "cubes": [ { "from": [0,0,0], "size": [8,8,8] } ] },
                    "arm":  { "cubes": [ { "from": [0,0,0], "size": [4,4,4] } ] }
                } }
                """);

        // "arm" e um erro classico: a base espera right_arm e left_arm. A peca nao daria erro
        // nenhum no jogo -- ela so nao apareceria.
        assertEquals(List.of("arm"), spec.unknownBones("minecraft:iron_golem"));
        assertFalse(spec.unknownBones("minecraft:iron_golem").contains("head"));
    }

    @Test
    void umaBaseSemListaConhecidaNaoAcusaNada() {
        EntityModelSpec spec = EntityModelSpec.parse(GOLEM);
        // Melhor calar que acusar errado: uma base que o loader ainda nao mapeou mandaria quem
        // escreveu o mod renomear ossos que estao certos.
        assertTrue(spec.unknownBones("minecraft:allay").isEmpty());
    }
}

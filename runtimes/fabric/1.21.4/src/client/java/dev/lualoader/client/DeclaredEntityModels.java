package dev.lualoader.client;

import dev.lualoader.content.EntityModelSpec;
import net.minecraft.client.model.Dilation;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.model.TexturedModelData;

import java.util.Map;

/**
 * Monta a geometria declarada como um {@code ModelPart} do jogo.
 *
 * <p><b>A raiz montada aqui é entregue à classe de modelo da própria base.</b> Um
 * {@code ZombieEntityModel} recebe uma raiz e procura os filhos por nome para girá-los a cada
 * quadro; se a geometria declarada trouxer os mesmos nomes, a animação do zumbi passa a mover as
 * caixas novas sem saber que elas mudaram.
 *
 * <p>É o que entrega forma própria sem escrever uma linha de animação. O preço é o vocabulário
 * fechado de nomes de osso, e ele é conferido no núcleo — um nome que a base não conhece não dá
 * erro: a peça só não aparece.
 */
public final class DeclaredEntityModels {
    private DeclaredEntityModels() {
    }

    /**
     * Converte a geometria declarada no dado de modelo que o jogo consome.
     *
     * <p>Todos os ossos são filhos diretos da raiz, e não uma hierarquia: as classes de modelo do
     * jogo buscam por caminho raso ({@code root.getChild("head")}), e aninhar um osso dentro de
     * outro faria a busca não encontrar justamente as peças que a base quer animar.
     */
    public static TexturedModelData build(EntityModelSpec spec) {
        ModelData data = new ModelData();
        ModelPartData root = data.getRoot();

        for (Map.Entry<String, EntityModelSpec.Bone> entry : spec.bones.entrySet()) {
            EntityModelSpec.Bone bone = entry.getValue();
            ModelPartBuilder builder = ModelPartBuilder.create();

            for (EntityModelSpec.Cube cube : bone.cubes()) {
                // O uv vem antes das medidas porque o construtor do jogo e sequencial: chamar uv
                // depois de cuboid aplicaria o recorte a proxima caixa, e nao a esta.
                builder = builder.uv(cube.uvX(), cube.uvY());
                if (cube.mirror()) builder = builder.mirrored();

                builder = builder.cuboid(
                        cube.fromX(), cube.fromY(), cube.fromZ(),
                        cube.sizeX(), cube.sizeY(), cube.sizeZ(),
                        new Dilation(cube.inflate()));

                // Espelhar vale so para a caixa que pediu: deixar ligado inverteria em silencio
                // todas as seguintes, e o defeito apareceria como textura trocada de lado.
                if (cube.mirror()) builder = builder.mirrored(false);
            }

            root.addChild(entry.getKey(), builder,
                    ModelTransform.pivot(bone.pivotX(), bone.pivotY(), bone.pivotZ()));
        }

        return TexturedModelData.of(data, spec.textureWidth, spec.textureHeight);
    }

    /** A raiz pronta para entregar ao construtor da classe de modelo da base. */
    public static ModelPart rootOf(EntityModelSpec spec) {
        return build(spec).createModel();
    }
}

package dev.lualoader.neoforge.client;

import dev.lualoader.content.EntityModelSpec;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;

import java.util.Map;

/**
 * Monta a geometria declarada como um {@code ModelPart} do jogo, no NeoForge.
 *
 * <p>O par de {@code DeclaredEntityModels} do Fabric, e existe porque as duas plataformas usam
 * nomes diferentes para as mesmas classes do jogo. O que não pode divergir é o resultado: o mesmo
 * JSON de ossos precisa produzir a mesma criatura dos dois lados.
 *
 * <p>A raiz montada aqui é entregue à classe de modelo da própria base, que procura os filhos por
 * nome para girá-los a cada quadro. Trocando só a raiz, a animação da base move as caixas novas.
 */
public final class NeoForgeDeclaredModels {
    private NeoForgeDeclaredModels() {
    }

    /**
     * Converte a geometria declarada no dado de modelo que o jogo consome.
     *
     * <p>Todos os ossos são filhos diretos da raiz, e não uma hierarquia: as classes de modelo do
     * jogo buscam por caminho raso, e aninhar um osso dentro de outro faria a busca não encontrar
     * justamente as peças que a base quer animar.
     */
    public static ModelPart rootOf(EntityModelSpec spec) {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        for (Map.Entry<String, EntityModelSpec.Bone> entry : spec.bones.entrySet()) {
            EntityModelSpec.Bone bone = entry.getValue();
            CubeListBuilder builder = CubeListBuilder.create();

            for (EntityModelSpec.Cube cube : bone.cubes()) {
                // O uv vem antes das medidas porque o construtor do jogo e sequencial: chamar
                // depois aplicaria o recorte a proxima caixa, e nao a esta.
                builder = builder.texOffs(cube.uvX(), cube.uvY());
                if (cube.mirror()) builder = builder.mirror();

                builder = builder.addBox(
                        cube.fromX(), cube.fromY(), cube.fromZ(),
                        cube.sizeX(), cube.sizeY(), cube.sizeZ(),
                        new CubeDeformation(cube.inflate()));

                // Espelhar vale so para a caixa que pediu: deixar ligado inverteria em silencio
                // todas as seguintes, e o defeito apareceria como textura trocada de lado.
                if (cube.mirror()) builder = builder.mirror(false);
            }

            root.addOrReplaceChild(entry.getKey(), builder,
                    PartPose.offset(bone.pivotX(), bone.pivotY(), bone.pivotZ()));
        }

        return LayerDefinition.create(mesh, spec.textureWidth, spec.textureHeight)
                .bakeRoot();
    }
}

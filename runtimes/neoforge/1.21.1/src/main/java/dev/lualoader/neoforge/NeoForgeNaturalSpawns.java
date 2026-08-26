package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.EntityDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacementTypes;
import net.minecraft.world.entity.monster.Monster;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.List;

/**
 * Diz ao jogo onde as espécies declaradas aceitam nascer, no NeoForge.
 *
 * <p><b>Só metade do trabalho mora aqui, e é a metade invisível.</b> A outra — em que biomas a
 * espécie entra como candidata — vem de um modificador de bioma escrito no data pack pelo núcleo,
 * porque é assim que esta plataforma acrescenta spawn: por dado, e não por chamada. O Fabric faz o
 * contrário, e é por isso que os dois adaptadores não compartilham este arquivo.
 *
 * <p>Esquecer qualquer uma das duas metades dá exatamente o mesmo sintoma: nada nasce, e nenhum log
 * reclama. Um tipo sem regra de posicionamento é tratado como inelegível em silêncio.
 */
public final class NeoForgeNaturalSpawns {
    private NeoForgeNaturalSpawns() {
    }

    /**
     * Registra a regra de posicionamento de cada espécie que pediu nascimento natural.
     *
     * <p>Precisa acontecer na fase de preparação, depois dos registros e antes de qualquer mundo:
     * a tabela de posicionamento é lida quando o motor de spawn roda, e escrever nela com o mundo
     * no ar não alcança os pedaços já carregados.
     */
    public static void register(RegisterSpawnPlacementsEvent event, Logger logger,
                                NeoForgeEntityRegistrar registrar,
                                List<ModLoader.LoadedMod> mods) {
        for (ModLoader.LoadedMod mod : mods) {
            if (mod.manifest().entities == null) continue;

            for (EntityDefinition entity : mod.manifest().entities) {
                if (entity == null || entity.spawn == null) continue;

                ResourceLocation id =
                        ResourceLocation.fromNamespaceAndPath(mod.manifest().id, entity.id);
                EntityType<?> type = registrar.registeredEntities().get(id);
                if (type == null) continue;

                try {
                    apply(event, logger, id, type, entity.spawn);
                } catch (RuntimeException error) {
                    // Uma regra recusada nao leva o resto junto: sem esta guarda, uma unica especie
                    // mal declarada derruba a carga do loader inteiro, e o sintoma nao e "essa
                    // especie nao nasce", e "nenhum mod carregou".
                    logger.error("Nascimento natural de {} recusado: {}", id, error.getMessage());
                }
            }
        }
    }

    private static void apply(RegisterSpawnPlacementsEvent event, Logger logger,
                              ResourceLocation id, EntityType<?> type,
                              EntityDefinition.SpawnDefinition spawn) {
        // MISC nao nasce sozinho no jogo: e a categoria do barco e do quadro. Um golem declarado
        // herda MISC da base, entao esta e a armadilha mais provavel de quem declara o primeiro
        // spawn -- e a mensagem precisa dizer o que fazer, e nao so recusar.
        if (type.getCategory() == MobCategory.MISC) {
            throw new IllegalStateException("a categoria misc nao nasce sozinha no jogo;"
                    + " declare category como monster, creature ou ambient para "
                    + id + " nascer naturalmente");
        }

        // Pelo evento, e nao por SpawnPlacements: a tabela do jogo e privada nesta plataforma, e
        // o NeoForge publica este evento justamente como o caminho para mexer nela.
        event.register(cast(type), SpawnPlacementTypes.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                (entityType, level, reason, position, random) -> {
                    int light = level.getBrightness(LightLayer.BLOCK, position);
                    if (light < spawn.minLight || light > spawn.maxLight) return false;
                    if (spawn.minY != null && position.getY() < spawn.minY) return false;
                    if (spawn.maxY != null && position.getY() > spawn.maxY) return false;

                    // Delega o resto ao jogo: chao solido e espaco livre sao regras que o jogador
                    // ja conhece, e reimplementa-las aqui divergiria do resto do mundo.
                    return Monster.checkAnyLightMonsterSpawnRules(
                            cast(type), level, reason, position, random);
                },
                RegisterSpawnPlacementsEvent.Operation.REPLACE);

        logger.info("Especie {} passa a nascer em {} bioma(s), peso {}",
                id, spawn.biomes.size(), spawn.weight);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Mob> EntityType<T> cast(EntityType<?> type) {
        return (EntityType<T>) type;
    }
}

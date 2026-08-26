package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.EntityDefinition;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import org.slf4j.Logger;

import java.util.List;

/**
 * Faz as espécies declaradas nascerem sozinhas no mundo.
 *
 * <p>Até aqui uma espécie só chegava ao mundo por comando, por ovo ou por script: ela existia e
 * ninguém a encontrava jogando. É a diferença entre um mod que se experimenta no criativo e um que
 * muda a partida.
 *
 * <p><b>Duas coisas precisam acontecer, e esquecer uma delas dá o mesmo sintoma — nada nasce.</b> A
 * espécie entra na lista de candidatas do bioma, e o jogo precisa saber onde ela aceita nascer. Sem
 * a segunda, o Minecraft nem chega a sortear: um tipo sem regra de posicionamento é tratado como
 * inelegível, em silêncio.
 */
public final class NaturalSpawns {
    private NaturalSpawns() {
    }

    /**
     * Declara o nascimento natural de tudo que pediu.
     *
     * <p>Roda depois do registro dos tipos, porque precisa deles: um modificador que aponta para um
     * tipo que ainda não existe seria descartado quando o bioma fosse montado.
     */
    public static void register(Logger logger, EntityRegistrar registrar,
                                List<ModLoader.LoadedMod> mods) {
        for (ModLoader.LoadedMod mod : mods) {
            if (mod.manifest().entities == null) continue;

            for (EntityDefinition entity : mod.manifest().entities) {
                if (entity == null || entity.spawn == null) continue;

                Identifier id = Identifier.of(mod.manifest().id, entity.id);
                EntityType<?> type = registrar.registeredEntities().get(id);
                if (type == null) continue;

                try {
                    apply(logger, id, type, entity.spawn);
                } catch (RuntimeException error) {
                    // Uma regra recusada nao leva o resto junto. Sem esta guarda, uma unica
                    // especie mal declarada derrubava a inicializacao do loader inteiro -- e o
                    // sintoma nao era "essa especie nao nasce", era "nenhum mod carregou".
                    logger.error("Nascimento natural de {} recusado: {}", id, error.getMessage());
                }
            }
        }
    }

    private static void apply(Logger logger, Identifier id, EntityType<?> type,
                              EntityDefinition.SpawnDefinition spawn) {
        SpawnGroup group = type.getSpawnGroup();

        // MISC nao nasce sozinho no jogo: e a categoria do barco e do quadro, e o proprio motor de
        // spawn a substitui por porco. Um golem declarado herda MISC da base, entao esta e a
        // armadilha mais provavel de quem declara o primeiro spawn -- e ela precisa dizer o que
        // fazer, e nao so recusar.
        if (group == SpawnGroup.MISC) {
            throw new IllegalStateException("a categoria misc nao nasce sozinha no jogo;"
                    + " declare category como monster, creature ou ambient para "
                    + id + " nascer naturalmente");
        }

        // A regra de posicionamento: sem ela o jogo trata o tipo como inelegivel e nao sorteia.
        // O predicado do jogo ja confere bloco solido, espaco livre e luz de bloco -- a faixa
        // declarada e conferida por cima disso, e nao no lugar.
        SpawnRestriction.register(cast(type), net.minecraft.entity.SpawnLocationTypes.ON_GROUND,
                Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                (entityType, world, reason, position, random) -> {
                    int light = world.getLightLevel(net.minecraft.world.LightType.BLOCK, position);
                    if (light < spawn.minLight || light > spawn.maxLight) return false;
                    if (spawn.minY != null && position.getY() < spawn.minY) return false;
                    if (spawn.maxY != null && position.getY() > spawn.maxY) return false;

                    // Delega o resto ao jogo: monstro precisa de chao solido e de escuridao, e
                    // reimplementar isso aqui divergiria do que o jogador espera do mundo.
                    return HostileEntity.canSpawnIgnoreLightLevel(cast(type), world, reason,
                            position, random);
                });

        for (String biome : spawn.biomes) {
            BiomeModifications.addSpawn(selector(biome), group, type,
                    spawn.weight, spawn.minGroup, spawn.maxGroup);
        }
        logger.info("Especie {} passa a nascer em {} bioma(s), peso {}",
                id, spawn.biomes.size(), spawn.weight);
    }

    /**
     * Traduz o bioma declarado no seletor do jogo.
     *
     * <p>Uma tag ({@code #minecraft:is_forest}) alcança um conjunto que cresce com o jogo e com os
     * outros mods; um id alcança um bioma só. Aceitar os dois é o que evita listar quarenta biomas
     * de floresta à mão — e envelhecer a cada versão nova.
     */
    private static java.util.function.Predicate<net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext>
            selector(String biome) {
        if (biome.startsWith("#")) {
            return BiomeSelectors.tag(TagKey.of(RegistryKeys.BIOME,
                    Identifier.of(biome.substring(1))));
        }
        return BiomeSelectors.includeByKey(
                RegistryKey.of(RegistryKeys.BIOME, Identifier.of(biome)));
    }

    @SuppressWarnings("unchecked")
    private static <T extends MobEntity> EntityType<T> cast(EntityType<?> type) {
        return (EntityType<T>) type;
    }
}

package dev.lualoader.gametest;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.minecraft.DeclarativeBlock;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Testes de integração executados dentro de um servidor Minecraft real.
 *
 * <p>Cobrem o que o módulo `core` não alcança: o registro efetivo do bloco declarativo e a
 * aplicação da variante visual em um mundo carregado. A lógica de decisão do mod é testada
 * em JUnit no `core`; aqui verificamos que o adaptador Fabric aplica o resultado no jogo.
 */
public class BlockInteractionGameTest implements FabricGameTest {
    private static final Identifier RUBY_BLOCK = Identifier.of("hello_lua", "ruby_block");

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void declarativeBlockIsRegistered(TestContext context) {
        Block block = LuaLoaderMod.blockRegistrar().get(RUBY_BLOCK);
        if (block == null) {
            throw new AssertionError("bloco declarativo não foi registrado: " + RUBY_BLOCK);
        }
        if (!(block instanceof DeclarativeBlock)) {
            throw new AssertionError("bloco registrado não é DeclarativeBlock: " + block);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void bridgeAppliesVariantInLoadedWorld(TestContext context) {
        Block block = LuaLoaderMod.blockRegistrar().get(RUBY_BLOCK);
        if (!(block instanceof DeclarativeBlock declarativeBlock)) {
            throw new AssertionError("bloco declarativo indisponível para o teste");
        }

        BlockPos relative = new BlockPos(1, 1, 1);
        context.setBlockState(relative, declarativeBlock.getDefaultState());

        BlockState placed = context.getBlockState(relative);
        if (placed.get(DeclarativeBlock.LUA_VARIANT) != 0) {
            throw new AssertionError("variante inicial deveria ser 0");
        }

        // Mesma operação que o Lua dispara via GameBridge ao receber um clique.
        BlockPos absolute = context.getAbsolutePos(relative);
        context.getWorld().setBlockState(
                absolute,
                placed.with(DeclarativeBlock.LUA_VARIANT, 1),
                3
        );

        BlockState updated = context.getBlockState(relative);
        if (updated.get(DeclarativeBlock.LUA_VARIANT) != 1) {
            throw new AssertionError("variante deveria ter mudado para 1, veio "
                    + updated.get(DeclarativeBlock.LUA_VARIANT));
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void dynamicHardnessIsApplied(TestContext context) {
        Block block = LuaLoaderMod.blockRegistrar().get(RUBY_BLOCK);
        if (!(block instanceof DeclarativeBlock declarativeBlock)) {
            throw new AssertionError("bloco declarativo indisponível para o teste");
        }

        float original = declarativeBlock.getHardness();
        try {
            declarativeBlock.setDynamicProperty("hardness", 9.0f);
            if (declarativeBlock.getHardness() != 9.0f) {
                throw new AssertionError("dureza dinâmica não foi aplicada");
            }
        } finally {
            declarativeBlock.setDynamicProperty("hardness", original);
        }
        context.complete();
    }

    /**
     * As propriedades físicas declaradas chegaram mesmo ao bloco registrado.
     *
     * <p>É o par do teste homônimo do adaptador NeoForge, e existe pela mesma razão: enquanto
     * ninguém comparava manifesto com bloco, um campo podia deixar de ser lido sem que nada
     * acusasse — o bloco existia, aparecia e podia ser quebrado.
     *
     * <p>A comparação é contra o manifesto, e não contra números escritos aqui. Isso só funciona
     * porque {@code hello_lua:bloco_de_prova} declara valores <em>diferentes</em> dos padrões do
     * jogo: quando os exemplos declaravam só os padrões, este teste passava mesmo com o adaptador
     * ignorando o manifesto inteiro. Um teste que não consegue falhar não é verificação.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void declaredSettingsReachTheRegisteredBlock(TestContext context) {
        int checked = 0;

        for (var mod : LuaLoaderMod.loadedMods()) {
            var manifest = mod.manifest();
            if (manifest.blocks == null) continue;

            for (var definition : manifest.blocks) {
                if (definition == null || definition.id == null) continue;

                Identifier id = Identifier.of(manifest.id, definition.id);
                Block block = LuaLoaderMod.blockRegistrar().get(id);
                if (block == null) continue;

                var values = definition.settings == null
                        ? new dev.lualoader.manifest.ModManifest.SettingsDefinition()
                        : definition.settings;

                require(id, "random_ticks",
                        values.randomTicks, block.getDefaultState().hasRandomTicks());
                require(id, "slipperiness", values.slipperiness, block.getSlipperiness());
                require(id, "velocity_multiplier",
                        values.velocityMultiplier, block.getVelocityMultiplier());
                require(id, "jump_velocity_multiplier",
                        values.jumpVelocityMultiplier, block.getJumpVelocityMultiplier());
                checked++;
            }
        }

        if (checked == 0) {
            throw new AssertionError("nenhum bloco declarado foi conferido; os exemplos chegaram?");
        }
        context.complete();
    }

    private static void require(Identifier id, String field, boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError(id + ": " + field + " declared " + expected + ", got " + actual);
        }
    }

    private static void require(Identifier id, String field, float expected, float actual) {
        if (Math.abs(expected - actual) > 0.0001f) {
            throw new AssertionError(id + ": " + field + " declared " + expected + ", got " + actual);
        }
    }
}

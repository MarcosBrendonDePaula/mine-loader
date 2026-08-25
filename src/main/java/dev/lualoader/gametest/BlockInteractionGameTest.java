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

    /**
     * Um cano cresce braço quando ganha vizinho, e o perde quando o vizinho some.
     *
     * <p>Era a lacuna mais estruturante que a migração do Logistic Pipes achou: {@code shape} era
     * declarado uma vez e valia para todos os estados, e uma rede de canos ficava sendo peças
     * soltas encostadas.
     *
     * <p>O que se pergunta é a propriedade de estado, e não o desenho — o desenho só o
     * {@code runClient} mostra. Mas a propriedade é o que o desenho lê, e é ela que também decide a
     * colisão: se ela estiver errada, as duas ficam.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void oCanoConectaComOVizinho(TestContext context) {
        var cano = net.minecraft.registry.Registries.BLOCK.get(
                net.minecraft.util.Identifier.of("logistica", "cano"));
        if (cano == null || cano == net.minecraft.block.Blocks.AIR) {
            throw new AssertionError("o cano do exemplo nao foi registrado");
        }

        BlockPos primeiro = new BlockPos(1, 1, 1);
        BlockPos segundo = new BlockPos(1, 1, 2);

        // Colocado sozinho, nasce sem braco nenhum: desenhar bracos sem vizinho daria a impressao
        // de rede onde nao ha nenhuma.
        context.setBlockState(primeiro, cano.getDefaultState());
        if (conectado(context, primeiro, "south")) {
            throw new AssertionError("um cano sozinho nao deveria estar conectado");
        }

        // O vizinho chega, e o jogo avisa aquele lado.
        context.setBlockState(segundo, cano.getDefaultState());
        if (!conectado(context, primeiro, "south")) {
            throw new AssertionError("o cano deveria ter conectado ao vizinho ao sul");
        }
        if (!conectado(context, segundo, "north")) {
            throw new AssertionError("a conexao precisa valer nos dois sentidos");
        }

        // E os lados sem vizinho continuam desligados -- um bloco que conecta para todo lado seria
        // igualmente errado, e passaria despercebido se so se conferisse o lado ligado.
        if (conectado(context, primeiro, "north") || conectado(context, primeiro, "up")) {
            throw new AssertionError("lados sem vizinho nao deveriam conectar");
        }

        // Some o vizinho, some o braco.
        context.setBlockState(segundo, net.minecraft.block.Blocks.AIR.getDefaultState());
        if (conectado(context, primeiro, "south")) {
            throw new AssertionError("o cano deveria ter perdido a conexao");
        }
        context.complete();
    }

    /**
     * O tique agendado entra na fila do jogo e chega de volta ao bloco.
     *
     * <p>O que se verifica é a fila, e não um efeito no mundo: o efeito depende do que o script faz
     * quando acorda, e isso o núcleo já prova. O que só o jogo mostra é se o pedido virou uma
     * entrada real na fila e se ela é consumida no prazo — um agendamento que ficasse na fila para
     * sempre seria um item parado no meio do cano, sem nada no log.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void oTiqueAgendadoEntraNaFilaDoJogo(TestContext context) {
        var cano = net.minecraft.registry.Registries.BLOCK.get(
                Identifier.of("logistica", "cano"));
        if (cano == null || cano == net.minecraft.block.Blocks.AIR) {
            throw new AssertionError("o cano do exemplo nao foi registrado");
        }

        BlockPos relativa = new BlockPos(1, 1, 1);
        context.setBlockState(relativa, cano.getDefaultState());
        BlockPos absoluta = context.getAbsolutePos(relativa);

        var world = context.getWorld();
        var bridge = LuaLoaderMod.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");

        bridge.setCurrentWorld(world);
        try {
            bridge.scheduleBlockTick(absoluta.getX(), absoluta.getY(), absoluta.getZ(), 4);

            if (!world.getBlockTickScheduler().isQueued(absoluta, cano)) {
                throw new AssertionError("o pedido nao virou entrada na fila do jogo");
            }

            // Agendar num bloco do jogo tem que ser recusado: a fila aceitaria, e o tique iria
            // para o metodo do bloco vanilla -- o pedido pareceria aceito e nada chegaria ao script.
            BlockPos pedra = context.getAbsolutePos(new BlockPos(2, 1, 1));
            context.setBlockState(new BlockPos(2, 1, 1), net.minecraft.block.Blocks.STONE.getDefaultState());
            boolean recusou = false;
            try {
                bridge.scheduleBlockTick(pedra.getX(), pedra.getY(), pedra.getZ(), 4);
            } catch (dev.lualoader.platform.BridgeException expected) {
                recusou = true;
            }
            if (!recusou) throw new AssertionError("agendar em bloco vanilla deveria ser recusado");
        } finally {
            bridge.setCurrentWorld(null);
        }

        // Cinco tiques depois de um prazo de quatro, a fila precisa estar limpa: se continuar la, o
        // tique nunca foi entregue.
        context.runAtTick(5, () -> {
            if (world.getBlockTickScheduler().isQueued(absoluta, cano)) {
                throw new AssertionError("o tique agendado nunca foi entregue");
            }
            context.complete();
        });
    }

    /** Le a propriedade booleana daquele lado, no estado que esta no mundo. */
    private static boolean conectado(TestContext context, BlockPos relative, String side) {
        var state = context.getWorld().getBlockState(context.getAbsolutePos(relative));

        for (var property : state.getProperties()) {
            if (property.getName().equals(side)
                    && property instanceof net.minecraft.state.property.BooleanProperty booleano) {
                return state.get(booleano);
            }
        }
        throw new AssertionError("o cano nao tem a propriedade " + side
                + "; propriedades: " + state.getProperties());
    }
}

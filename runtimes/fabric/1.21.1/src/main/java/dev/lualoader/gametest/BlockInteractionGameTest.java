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
    private static final Identifier RATION = Identifier.of("hello_lua", "racao");

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
    public void bridgeReadsRedstoneSignalInLoadedWorld(TestContext context) {
        var world = context.getWorld();
        BlockPos source = context.getAbsolutePos(new BlockPos(1, 1, 1));
        BlockPos target = context.getAbsolutePos(new BlockPos(2, 1, 1));
        world.setBlockState(source, net.minecraft.block.Blocks.REDSTONE_BLOCK.getDefaultState(), 3);

        var bridge = LuaLoaderMod.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentWorld(world);
        try {
            int signal = bridge.redstoneSignal(target.getX(), target.getY(), target.getZ());
            if (signal != 15) {
                throw new AssertionError("o bloco deveria receber sinal 15, recebeu " + signal);
            }
        } finally {
            bridge.setCurrentWorld(null);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void bridgeReadsAndWritesBlockStateInLoadedWorld(TestContext context) {
        var world = context.getWorld();
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = context.getAbsolutePos(relative);
        world.setBlockState(absolute, net.minecraft.block.Blocks.OAK_DOOR.getDefaultState(), 3);

        var bridge = LuaLoaderMod.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentWorld(world);
        try {
            var snapshot = bridge.blockState(absolute.getX(), absolute.getY(), absolute.getZ());
            if (!"minecraft:oak_door".equals(snapshot.id)
                    || !"false".equals(snapshot.properties.get("open"))
                    || !snapshot.properties.containsKey("facing")) {
                throw new AssertionError("snapshot inesperado: " + snapshot.id + " " + snapshot.properties);
            }

            if (!bridge.setBlockState(absolute.getX(), absolute.getY(), absolute.getZ(),
                    java.util.Map.of("open", "true", "facing", "south"))) {
                throw new AssertionError("set_block_state deveria alterar a porta");
            }

            BlockState updated = world.getBlockState(absolute);
            if (!updated.get(net.minecraft.state.property.Properties.OPEN)
                    || updated.get(net.minecraft.state.property.Properties.HORIZONTAL_FACING)
                    != net.minecraft.util.math.Direction.SOUTH) {
                throw new AssertionError("estado escrito nao chegou ao mundo: " + updated);
            }
        } finally {
            bridge.setCurrentWorld(null);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void bridgeReadsAndWritesWhitelistedGameRule(TestContext context) {
        var world = context.getWorld();
        var bridge = LuaLoaderMod.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentWorld(world);
        String original = bridge.gameRule("do_weather_cycle");
        try {
            bridge.setGameRule("do_weather_cycle", "false");
            if (!"false".equals(bridge.gameRule("do_weather_cycle"))) {
                throw new AssertionError("a Game Rule deveria ter sido alterada");
            }
        } finally {
            bridge.setGameRule("do_weather_cycle", original);
            bridge.setCurrentWorld(null);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void bridgeExecutesSafeWorldEffects(TestContext context) {
        var world = context.getWorld();
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = context.getAbsolutePos(relative);
        world.setBlockState(absolute, net.minecraft.block.Blocks.STONE.getDefaultState(), 3);

        var bridge = LuaLoaderMod.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentWorld(world);
        try {
            bridge.explode(absolute.getX() + 0.5, absolute.getY() + 0.5,
                    absolute.getZ() + 0.5, 0.5f, false);
            if (world.getBlockState(absolute).isAir()) {
                throw new AssertionError("explosao sem breakBlocks nao deveria destruir o bloco");
            }
            bridge.strikeLightning(absolute.getX() + 0.5, absolute.getY() + 1.0,
                    absolute.getZ() + 0.5);
        } finally {
            bridge.setCurrentWorld(null);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void declarativeFoodAndFuelReachTheGame(TestContext context) {
        var item = net.minecraft.registry.Registries.ITEM.get(RATION);
        if (item == null || item == net.minecraft.item.Items.AIR) {
            throw new AssertionError("item de ração não foi registrado: " + RATION);
        }
        var food = new net.minecraft.item.ItemStack(item)
                .get(net.minecraft.component.DataComponentTypes.FOOD);
        if (food == null || food.nutrition() != 6
                || Math.abs(food.saturation() - (6 * 0.8f * 2.0f)) > 0.0001f
                || !food.canAlwaysEat()
                || Math.abs(food.eatSeconds() - 2.5f) > 0.0001f
                || food.effects().size() != 1) {
            throw new AssertionError("comida declarativa inesperada: " + food);
        }
        var effectEntry = food.effects().getFirst();
        var effect = effectEntry.effect();
        if (!effect.getEffectType().equals(net.minecraft.entity.effect.StatusEffects.SPEED)
                || effect.getDuration() != 100
                || effect.getAmplifier() != 1
                || Math.abs(effectEntry.probability() - 0.75f) > 0.0001f
                || !effect.isAmbient()
                || effect.shouldShowParticles()) {
            throw new AssertionError("efeito de comida inesperado: " + effectEntry);
        }

        var bridge = LuaLoaderMod.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentWorld(context.getWorld());
        try {
            if (bridge.fuelBurnTime(RATION.toString()) != 400) {
                throw new AssertionError("tempo de combustível inesperado para " + RATION);
            }
        } finally {
            bridge.setCurrentWorld(null);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void declarativeFoodEffectsReachTheGame(TestContext context) {
        var item = net.minecraft.registry.Registries.ITEM.get(RATION);
        var food = new net.minecraft.item.ItemStack(item)
                .get(net.minecraft.component.DataComponentTypes.FOOD);
        if (food == null || Math.abs(food.eatSeconds() - 2.5f) > 0.0001f
                || food.effects().size() != 1) {
            throw new AssertionError("efeitos/duração da comida inesperados: " + food);
        }
        var entry = food.effects().getFirst();
        var effect = entry.effect();
        if (!effect.getEffectType().equals(net.minecraft.entity.effect.StatusEffects.SPEED)
                || effect.getDuration() != 100
                || effect.getAmplifier() != 1
                || Math.abs(entry.probability() - 0.75f) > 0.0001f
                || !effect.isAmbient()
                || effect.shouldShowParticles()) {
            throw new AssertionError("efeito pós-consumo inesperado: " + entry);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void bridgeReadsAndWritesDifficulty(TestContext context) {
        var bridge = LuaLoaderMod.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        String original = bridge.difficulty();
        try {
            bridge.setDifficulty(original);
            if (!original.equals(bridge.difficulty())) {
                throw new AssertionError("a dificuldade deveria permanecer " + original);
            }
        } finally {
            bridge.setDifficulty(original);
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
                net.minecraft.util.Identifier.of("tubos", "tubo"));
        if (cano == null || cano == net.minecraft.block.Blocks.AIR) {
            throw new AssertionError("o tubo do exemplo nao foi registrado");
        }

        BlockPos primeiro = new BlockPos(1, 1, 1);
        BlockPos segundo = new BlockPos(1, 1, 2);

        // Colocado sozinho, nasce sem braco nenhum: desenhar bracos sem vizinho daria a impressao
        // de rede onde nao ha nenhuma.
        context.setBlockState(primeiro, cano.getDefaultState());
        if (conectado(context, primeiro, "south")) {
            throw new AssertionError("um cano sozinho nao deveria estar conectado");
        }

        // E ele guarda dados na posicao ao mesmo tempo. As duas coisas juntas nao eram possiveis:
        // o registrador escolhia uma, e um cano que pedisse block_data perdia a conexao inteira sem
        // aviso. O exemplo de logistica precisa das duas -- a carga em viagem mora no cano.
        if (context.getWorld().getBlockEntity(context.getAbsolutePos(primeiro)) == null) {
            throw new AssertionError("o cano declara block_data e deveria ter entidade de bloco");
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
                Identifier.of("tubos", "tubo"));
        if (cano == null || cano == net.minecraft.block.Blocks.AIR) {
            throw new AssertionError("o tubo do exemplo nao foi registrado");
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

    /**
     * Le a ligacao daquele lado, no estado que esta no mundo.
     *
     * <p>A propriedade tem tres valores, e nao dois: um lado pode estar livre, ligado a um bloco da
     * lista, ou ligado a um inventario. Aqui interessa so se ha ligacao -- o teste do braco proprio
     * do inventario e do desenho, e vive no montador do pacote.
     */
    @SuppressWarnings("unchecked")
    private static boolean conectado(TestContext context, BlockPos relative, String side) {
        var state = context.getWorld().getBlockState(context.getAbsolutePos(relative));

        for (var property : state.getProperties()) {
            if (property.getName().equals(side) && property.getType() == String.class) {
                String valor = state.get(
                        (net.minecraft.state.property.Property<String>) property);
                return !dev.lualoader.content.BlockShapes.LINK_NONE.equals(valor);
            }
        }
        throw new AssertionError("o cano nao tem a propriedade " + side
                + "; propriedades: " + state.getProperties());
    }
}

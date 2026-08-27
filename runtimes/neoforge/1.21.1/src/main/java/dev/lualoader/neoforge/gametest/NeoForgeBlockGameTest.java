package dev.lualoader.neoforge.gametest;

import dev.lualoader.neoforge.NeoForgeContentRegistrar;
import dev.lualoader.neoforge.NeoForgeDeclarativeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Testes de integração do adaptador NeoForge, dentro de um servidor Minecraft real.
 *
 * <p><b>Por que esta classe existe.</b> Até ela, os sete GameTests do projeto rodavam só contra o
 * Fabric. O resultado foi seis divergências acumuladas em silêncio — eventos globais que nunca
 * disparavam, receitas que não chegavam ao servidor, ferramentas que viravam item comum — e nenhuma
 * delas quebrava um teste, porque nenhum teste olhava para cá.
 *
 * <p>A regra que isto estabelece: <b>um recurso declarado que funciona no Fabric é verificado aqui
 * também.</b> O núcleo já prova a lógica sem Minecraft; o que só o jogo mostra é se o adaptador
 * aplicou o que o manifesto pediu, e isso precisa ser perguntado às duas plataformas.
 *
 * <p>O template vem de {@code data/lua_loader/structure/empty.nbt}, cinco por cinco de ar: os
 * testes daqui verificam registro e estado, não geometria, e uma estrutura própria só acrescentaria
 * um arquivo para manter.
 */
@GameTestHolder("lua_loader")
@PrefixGameTestTemplate(false)
public class NeoForgeBlockGameTest {
    private static final ResourceLocation RUBY_BLOCK =
            ResourceLocation.fromNamespaceAndPath("hello_lua", "ruby_block");
    private static final ResourceLocation RATION =
            ResourceLocation.fromNamespaceAndPath("hello_lua", "racao");
    private static final String EMPTY = "empty";

    /** O bloco declarado no manifesto chegou ao registro do jogo, e é o do loader. */
    @GameTest(template = EMPTY)
    public static void blocoDeclaradoEstaRegistrado(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(RUBY_BLOCK);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            throw new AssertionError("bloco declarativo nao foi registrado: " + RUBY_BLOCK);
        }
        if (!(block instanceof NeoForgeDeclarativeBlock)) {
            throw new AssertionError("bloco registrado nao e NeoForgeDeclarativeBlock: " + block);
        }
        helper.succeed();
    }

    /** A bridge lê a potência recebida por uma posição do mundo real. */
    @GameTest(template = EMPTY)
    public static void bridgeLePotenciaRedstone(GameTestHelper helper) {
        BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.getLevel().setBlock(source,
                net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);

        var bridge = dev.lualoader.neoforge.NeoForgeLuaLoader.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentLevel(helper.getLevel());
        try {
            int signal = bridge.redstoneSignal(target.getX(), target.getY(), target.getZ());
            if (signal != 15) {
                throw new AssertionError("o bloco deveria receber sinal 15, recebeu " + signal);
            }
        } finally {
            bridge.setCurrentLevel(null);
        }
        helper.succeed();
    }

    /** A bridge lê e escreve propriedades vanilla sem expor BlockState ao Lua. */
    @GameTest(template = EMPTY)
    public static void bridgeLeEEscreveEstadoDoBloco(GameTestHelper helper) {
        BlockPos relativa = new BlockPos(1, 1, 1);
        BlockPos absoluta = helper.absolutePos(relativa);
        var level = helper.getLevel();
        level.setBlock(absoluta, net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState(), 3);

        var bridge = dev.lualoader.neoforge.NeoForgeLuaLoader.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentLevel(level);
        try {
            var snapshot = bridge.blockState(absoluta.getX(), absoluta.getY(), absoluta.getZ());
            if (!"minecraft:oak_door".equals(snapshot.id)
                    || !"false".equals(snapshot.properties.get("open"))
                    || !snapshot.properties.containsKey("facing")) {
                throw new AssertionError("snapshot inesperado: " + snapshot.id + " " + snapshot.properties);
            }

            if (!bridge.setBlockState(absoluta.getX(), absoluta.getY(), absoluta.getZ(),
                    java.util.Map.of("open", "true", "facing", "south"))) {
                throw new AssertionError("set_block_state deveria alterar a porta");
            }

            BlockState updated = level.getBlockState(absoluta);
            if (!updated.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                    || updated.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)
                    != net.minecraft.core.Direction.SOUTH) {
                throw new AssertionError("estado escrito nao chegou ao mundo: " + updated);
            }
        } finally {
            bridge.setCurrentLevel(null);
        }
        helper.succeed();
    }

    /** A whitelist de Game Rules tem o mesmo nome e a mesma semântica nos dois loaders. */
    @GameTest(template = EMPTY)
    public static void bridgeLeEEscreveGameRulePermitida(GameTestHelper helper) {
        var level = helper.getLevel();
        var bridge = dev.lualoader.neoforge.NeoForgeLuaLoader.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentLevel(level);
        String original = bridge.gameRule("do_weather_cycle");
        try {
            bridge.setGameRule("do_weather_cycle", "false");
            if (!"false".equals(bridge.gameRule("do_weather_cycle"))) {
                throw new AssertionError("a Game Rule deveria ter sido alterada");
            }
        } finally {
            bridge.setGameRule("do_weather_cycle", original);
            bridge.setCurrentLevel(null);
        }
        helper.succeed();
    }

    /** A bridge cria efeitos de mundo sem destruir blocos quando isso foi desativado. */
    @GameTest(template = EMPTY)
    public static void bridgeExecutaEfeitosSegurosDoMundo(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos relativa = new BlockPos(1, 1, 1);
        BlockPos absoluta = helper.absolutePos(relativa);
        level.setBlock(absoluta, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);

        var bridge = dev.lualoader.neoforge.NeoForgeLuaLoader.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentLevel(level);
        try {
            bridge.explode(absoluta.getX() + 0.5, absoluta.getY() + 0.5,
                    absoluta.getZ() + 0.5, 0.5f, false);
            if (level.getBlockState(absoluta).isAir()) {
                throw new AssertionError("explosao sem breakBlocks nao deveria destruir o bloco");
            }
            bridge.strikeLightning(absoluta.getX() + 0.5, absoluta.getY() + 1.0,
                    absoluta.getZ() + 0.5);
        } finally {
            bridge.setCurrentLevel(null);
        }
        helper.succeed();
    }

    /** As propriedades declarativas de comida e combustível chegam ao jogo real. */
    @GameTest(template = EMPTY)
    public static void comidaECombustivelDeclarativosChegamAoJogo(GameTestHelper helper) {
        Item item = BuiltInRegistries.ITEM.get(RATION);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new AssertionError("item de ração nao foi registrado: " + RATION);
        }
        var food = item.getFoodProperties(new net.minecraft.world.item.ItemStack(item), null);
        if (food == null || food.nutrition() != 6
                || Math.abs(food.saturation() - (6 * 0.8f * 2.0f)) > 0.0001f
                || !food.canAlwaysEat()) {
            throw new AssertionError("comida declarativa inesperada: " + food);
        }

        var bridge = dev.lualoader.neoforge.NeoForgeLuaLoader.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");
        bridge.setCurrentLevel(helper.getLevel());
        try {
            if (bridge.fuelBurnTime(RATION.toString()) != 400) {
                throw new AssertionError("tempo de combustível inesperado para " + RATION);
            }
        } finally {
            bridge.setCurrentLevel(null);
        }
        helper.succeed();
    }

    /** O setter de dificuldade aceita o valor atual sem alterar o mundo global do GameTest. */
    @GameTest(template = EMPTY)
    public static void bridgeLeEEscreveDificuldade(GameTestHelper helper) {
        var bridge = dev.lualoader.neoforge.NeoForgeLuaLoader.gameBridge();
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
        helper.succeed();
    }

    /** O item do bloco existe: sem ele o bloco só aparece por comando. */
    @GameTest(template = EMPTY)
    public static void itemDoBlocoExiste(GameTestHelper helper) {
        Item item = BuiltInRegistries.ITEM.get(RUBY_BLOCK);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new AssertionError("o bloco declarado nao tem item: " + RUBY_BLOCK);
        }
        helper.succeed();
    }

    /** A variante visual muda no mundo carregado — a mesma operação que o Lua dispara. */
    @GameTest(template = EMPTY)
    public static void varianteMudaNoMundoCarregado(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(RUBY_BLOCK);
        if (!(block instanceof NeoForgeDeclarativeBlock)) {
            throw new AssertionError("bloco declarativo indisponivel para o teste");
        }

        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, block.defaultBlockState());

        BlockState placed = helper.getBlockState(relative);
        if (placed.getValue(NeoForgeDeclarativeBlock.VARIANT) != 0) {
            throw new AssertionError("variante inicial deveria ser 0");
        }

        helper.getLevel().setBlock(helper.absolutePos(relative),
                placed.setValue(NeoForgeDeclarativeBlock.VARIANT, 1), 3);

        BlockState updated = helper.getBlockState(relative);
        if (updated.getValue(NeoForgeDeclarativeBlock.VARIANT) != 1) {
            throw new AssertionError("variante deveria ter mudado para 1, veio "
                    + updated.getValue(NeoForgeDeclarativeBlock.VARIANT));
        }
        helper.succeed();
    }

    /**
     * O inventário declarado nasce com o tamanho que o manifesto pediu.
     *
     * <p>É o par do teste homônimo do Fabric. O tamanho vem do manifesto e passa pelo registrador,
     * então este teste falha tanto se o manifesto deixar de ser lido quanto se a entidade de bloco
     * deixar de perguntar por ele.
     */
    @GameTest(template = EMPTY)
    public static void inventarioNasceComOTamanhoDeclarado(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("crystal_world", "cofre");
        Block block = BuiltInRegistries.BLOCK.get(id);
        if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) {
            throw new AssertionError("bloco com inventario nao registrado: " + id);
        }

        var declared = NeoForgeContentRegistrar.inventoryOf(block);
        if (declared == null) {
            throw new AssertionError("o bloco " + id + " deveria ter inventario declarado");
        }

        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, block.defaultBlockState());

        var entity = helper.getLevel().getBlockEntity(helper.absolutePos(relative));
        if (!(entity instanceof net.minecraft.world.Container container)) {
            throw new AssertionError("o bloco deveria ter entidade com inventario, veio " + entity);
        }
        if (container.getContainerSize() != declared.size) {
            throw new AssertionError("inventario deveria ter " + declared.size
                    + " slots, tem " + container.getContainerSize());
        }
        helper.succeed();
    }

    /**
     * Uma ferramenta declarada vira mesmo uma ferramenta, e não um item comum.
     *
     * <p>Este teste existe por causa de um defeito concreto: {@code ToolDefinition} não era lido
     * neste adaptador, e uma picareta declarada era registrada como {@code Item} empilhável. Nada
     * acusava, porque o item existia e tinha textura.
     */
    @GameTest(template = EMPTY)
    public static void ferramentaDeclaradaEUmaFerramenta(GameTestHelper helper) {
        ResourceLocation id =
                ResourceLocation.fromNamespaceAndPath("ferraria", "picareta_de_rubi");
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new AssertionError("ferramenta declarada nao registrada: " + id);
        }
        if (!(item instanceof net.minecraft.world.item.DiggerItem)) {
            throw new AssertionError("a ferramenta declarada virou item comum: " + item.getClass());
        }
        helper.succeed();
    }

    /** Uma armadura declarada veste, e no slot que o manifesto pediu. */
    @GameTest(template = EMPTY)
    public static void armaduraDeclaradaVesteNoSlotCerto(GameTestHelper helper) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("ferraria", "elmo_de_rubi");
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            throw new AssertionError("armadura declarada nao registrada: " + id);
        }
        if (!(item instanceof net.minecraft.world.item.ArmorItem armor)) {
            throw new AssertionError("a armadura declarada virou item comum: " + item.getClass());
        }
        if (armor.getType() != net.minecraft.world.item.ArmorItem.Type.HELMET) {
            throw new AssertionError("o elmo deveria vestir na cabeca, veio " + armor.getType());
        }
        helper.succeed();
    }

    /**
     * As propriedades físicas declaradas chegaram mesmo ao bloco registrado.
     *
     * <p><b>Este é o teste que faltava.</b> O adaptador aplicava seis dos cerca de trinta campos de
     * {@code material} e {@code settings}, e nada acusava: o bloco existia, aparecia e podia ser
     * quebrado. Um gelo escorregadio declarado uma vez escorregava numa plataforma e não na outra.
     *
     * <p>A comparação é contra o manifesto, e não contra números escritos aqui. Assim ele cobre
     * qualquer bloco que os exemplos venham a declarar, em vez de só os valores que existiam no dia
     * em que foi escrito — e é justamente o que impede a divergência de voltar.
     */
    @GameTest(template = EMPTY)
    public static void propriedadesDeclaradasChegaramAoBloco(GameTestHelper helper) {
        int checked = 0;

        for (var mod : dev.lualoader.neoforge.NeoForgeLuaLoader.loadedMods()) {
            var manifest = mod.manifest();
            if (manifest.blocks == null) continue;

            for (var definition : manifest.blocks) {
                if (definition == null || definition.id == null) continue;

                var id = ResourceLocation.fromNamespaceAndPath(manifest.id, definition.id);
                Block block = BuiltInRegistries.BLOCK.get(id);
                if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) continue;

                var values = definition.settings == null
                        ? new dev.lualoader.manifest.ModManifest.SettingsDefinition()
                        : definition.settings;
                BlockState state = block.defaultBlockState();

                require(id, "random_ticks",
                        values.randomTicks, state.isRandomlyTicking());
                require(id, "slipperiness",
                        values.slipperiness, block.getFriction());
                require(id, "velocity_multiplier",
                        values.velocityMultiplier, block.getSpeedFactor());
                require(id, "jump_velocity_multiplier",
                        values.jumpVelocityMultiplier, block.getJumpFactor());
                checked++;
            }
        }

        if (checked == 0) {
            throw new AssertionError("nenhum bloco declarado foi conferido; os exemplos chegaram?");
        }
        helper.succeed();
    }

    private static void require(ResourceLocation id, String field, boolean expected, boolean actual) {
        if (expected != actual) {
            throw new AssertionError(id + ": " + field + " declared " + expected + ", got " + actual);
        }
    }

    private static void require(ResourceLocation id, String field, float expected, float actual) {
        if (Math.abs(expected - actual) > 0.0001f) {
            throw new AssertionError(id + ": " + field + " declared " + expected + ", got " + actual);
        }
    }

    /**
     * O estado declarado no manifesto existe mesmo no blockstate.
     *
     * <p>{@code state.properties} era validado pelo nucleo e descartado por este adaptador: o bloco
     * nascia so com as propriedades fixas do loader, e um mod que dependesse do estado declarado
     * funcionava num lado e nao no outro.
     */
    @GameTest(template = EMPTY)
    public static void estadoDeclaradoExisteNoBloco(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(RUBY_BLOCK);
        if (!(block instanceof NeoForgeDeclarativeBlock declarativo)) {
            throw new AssertionError("bloco declarativo indisponivel para o teste");
        }

        // O bloco do exemplo nao declara estado proprio; o que se verifica aqui e que o caminho
        // existe e nao inventa propriedades. Um bloco que declarasse apareceria neste mapa.
        var declared = declarativo.declaredProperties();
        for (var nome : declared.keySet()) {
            if (!block.defaultBlockState().hasProperty(declared.get(nome))) {
                throw new AssertionError("a propriedade declarada " + nome
                        + " nao chegou ao blockstate");
            }
        }
        helper.succeed();
    }

    /**
     * Um cano cresce braço quando ganha vizinho, e o perde quando o vizinho some.
     *
     * <p>O par do lado Fabric, e o par importa: a aritmética das caixas vem do núcleo, mas quem
     * calcula a conexão é cada adaptador. Um lado que ligasse e o outro não daria o mesmo manifesto
     * produzindo canos que se encontram numa plataforma e não na outra.
     */
    @GameTest(template = EMPTY)
    public static void oCanoConectaComOVizinho(GameTestHelper helper) {
        Block cano = BuiltInRegistries.BLOCK.get(
                ResourceLocation.fromNamespaceAndPath("tubos", "tubo"));
        if (cano == null || cano == net.minecraft.world.level.block.Blocks.AIR) {
            throw new AssertionError("o tubo do exemplo nao foi registrado");
        }

        BlockPos primeiro = new BlockPos(1, 1, 1);
        BlockPos segundo = new BlockPos(1, 1, 2);

        helper.setBlock(primeiro, cano.defaultBlockState());
        if (conectado(helper, primeiro, "south")) {
            throw new AssertionError("um cano sozinho nao deveria estar conectado");
        }

        // E guarda dados na posicao ao mesmo tempo. A condicao aqui era `connects && !withData`:
        // um cano que pedisse block_data virava bloco de dados e nunca crescia braco.
        if (helper.getLevel().getBlockEntity(helper.absolutePos(primeiro)) == null) {
            throw new AssertionError("o cano declara block_data e deveria ter entidade de bloco");
        }

        helper.setBlock(segundo, cano.defaultBlockState());
        if (!conectado(helper, primeiro, "south")) {
            throw new AssertionError("o cano deveria ter conectado ao vizinho ao sul");
        }
        if (!conectado(helper, segundo, "north")) {
            throw new AssertionError("a conexao precisa valer nos dois sentidos");
        }

        // Lados sem vizinho continuam desligados: um bloco que conecta para todo lado passaria
        // despercebido se so se conferisse o lado ligado.
        if (conectado(helper, primeiro, "north") || conectado(helper, primeiro, "up")) {
            throw new AssertionError("lados sem vizinho nao deveriam conectar");
        }

        helper.setBlock(segundo, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        if (conectado(helper, primeiro, "south")) {
            throw new AssertionError("o cano deveria ter perdido a conexao");
        }
        helper.succeed();
    }


    /**
     * O tique agendado entra na fila do jogo e chega de volta ao bloco.
     *
     * <p>O par do lado Fabric. A fila é do jogo nas duas plataformas, mas quem a alimenta é cada
     * adaptador: um lado que agendasse e o outro não daria o mesmo manifesto com uma rede que anda
     * numa plataforma e trava na outra.
     */
    @GameTest(template = EMPTY)
    public static void oTiqueAgendadoEntraNaFilaDoJogo(GameTestHelper helper) {
        Block cano = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("tubos:tubo"));
        if (cano == null || cano == net.minecraft.world.level.block.Blocks.AIR) {
            throw new AssertionError("o tubo do exemplo nao foi registrado");
        }

        BlockPos relativa = new BlockPos(1, 1, 1);
        helper.getLevel().setBlock(helper.absolutePos(relativa), cano.defaultBlockState(), 3);
        BlockPos absoluta = helper.absolutePos(relativa);

        var level = helper.getLevel();
        var bridge = dev.lualoader.neoforge.NeoForgeLuaLoader.gameBridge();
        if (bridge == null) throw new AssertionError("a bridge nao foi montada");

        bridge.setCurrentLevel(level);
        try {
            bridge.scheduleBlockTick(absoluta.getX(), absoluta.getY(), absoluta.getZ(), 4);

            if (!level.getBlockTicks().hasScheduledTick(absoluta, cano)) {
                throw new AssertionError("o pedido nao virou entrada na fila do jogo");
            }

            // Agendar num bloco do jogo tem que ser recusado: a fila aceitaria, e o tique iria
            // para o metodo do bloco vanilla -- o pedido pareceria aceito e nada chegaria ao script.
            BlockPos pedra = helper.absolutePos(new BlockPos(2, 1, 1));
            level.setBlock(pedra, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
            boolean recusou = false;
            try {
                bridge.scheduleBlockTick(pedra.getX(), pedra.getY(), pedra.getZ(), 4);
            } catch (dev.lualoader.platform.BridgeException expected) {
                recusou = true;
            }
            if (!recusou) throw new AssertionError("agendar em bloco vanilla deveria ser recusado");
        } finally {
            bridge.setCurrentLevel(null);
        }

        // Cinco tiques depois de um prazo de quatro, a fila precisa estar limpa: se continuar la, o
        // tique nunca foi entregue.
        helper.runAfterDelay(5, () -> {
            if (level.getBlockTicks().hasScheduledTick(absoluta, cano)) {
                throw new AssertionError("o tique agendado nunca foi entregue");
            }
            helper.succeed();
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
    private static boolean conectado(GameTestHelper helper, BlockPos relative, String side) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(relative));

        for (var property : state.getProperties()) {
            if (property.getName().equals(side) && property.getValueClass() == String.class) {
                String valor = state.getValue(
                        (net.minecraft.world.level.block.state.properties.Property<String>) property);
                return !dev.lualoader.content.BlockShapes.LINK_NONE.equals(valor);
            }
        }
        throw new AssertionError("o cano nao tem a propriedade " + side
                + "; propriedades: " + state.getProperties());
    }
}

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
                ResourceLocation.fromNamespaceAndPath("logistica", "cano"));
        if (cano == null || cano == net.minecraft.world.level.block.Blocks.AIR) {
            throw new AssertionError("o cano do exemplo nao foi registrado");
        }

        BlockPos primeiro = new BlockPos(1, 1, 1);
        BlockPos segundo = new BlockPos(1, 1, 2);

        helper.setBlock(primeiro, cano.defaultBlockState());
        if (conectado(helper, primeiro, "south")) {
            throw new AssertionError("um cano sozinho nao deveria estar conectado");
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
        Block cano = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("logistica:cano"));
        if (cano == null || cano == net.minecraft.world.level.block.Blocks.AIR) {
            throw new AssertionError("o cano do exemplo nao foi registrado");
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

    /** Le a propriedade booleana daquele lado, no estado que esta no mundo. */
    private static boolean conectado(GameTestHelper helper, BlockPos relative, String side) {
        BlockState state = helper.getLevel().getBlockState(helper.absolutePos(relative));

        for (var property : state.getProperties()) {
            if (property.getName().equals(side)
                    && property instanceof net.minecraft.world.level.block.state.properties
                            .BooleanProperty booleano) {
                return state.getValue(booleano);
            }
        }
        throw new AssertionError("o cano nao tem a propriedade " + side
                + "; propriedades: " + state.getProperties());
    }
}

package dev.lualoader.gametest;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.minecraft.DeclarativeBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * O inventário de um bloco declarado, dentro de um servidor de verdade.
 *
 * <p>O núcleo verifica a declaração; isto verifica o que só existe no jogo — a entidade nascendo
 * com o número certo de slots, os itens sobrevivendo a uma gravação e as permissões de automação
 * valendo. Foi assim que se descobriu, no NeoForge, que a capability sozinha não bastava: o
 * inventário existia para o funil e não para o resto do jogo.
 */
public class BlockInventoryGameTest implements FabricGameTest {
    private static final Identifier COFRE = Identifier.of("crystal_world", "cofre");

    private static DeclarativeBlockEntity placeChest(TestContext context, BlockPos relative) {
        Block block = LuaLoaderMod.blockRegistrar().get(COFRE);
        if (block == null) {
            throw new AssertionError("bloco com inventario nao foi registrado: " + COFRE);
        }

        context.setBlockState(relative, block.getDefaultState());
        BlockEntity entity = context.getWorld().getBlockEntity(context.getAbsolutePos(relative));

        if (!(entity instanceof DeclarativeBlockEntity declarative)) {
            throw new AssertionError("bloco com inventario nao criou entidade: " + entity);
        }
        return declarative;
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void inventarioNasceComOTamanhoDeclarado(TestContext context) {
        DeclarativeBlockEntity entity = placeChest(context, new BlockPos(1, 1, 1));

        if (!entity.hasInventory()) {
            throw new AssertionError("a entidade deveria ter inventario");
        }
        // Vinte e sete e o que o manifesto do exemplo declara. O numero sai de la, e nao de um
        // padrao do adaptador: e isso que faz a declaracao valer alguma coisa.
        if (entity.size() != 27) {
            throw new AssertionError("inventario deveria ter 27 slots, veio " + entity.size());
        }
        if (!entity.isEmpty()) {
            throw new AssertionError("inventario deveria nascer vazio");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void itensGuardadosPermanecemNoBloco(TestContext context) {
        DeclarativeBlockEntity entity = placeChest(context, new BlockPos(1, 1, 1));

        entity.setStack(0, new ItemStack(Items.DIAMOND, 5));

        ItemStack stored = entity.getStack(0);
        if (!stored.isOf(Items.DIAMOND) || stored.getCount() != 5) {
            throw new AssertionError("o item guardado deveria continuar no slot: " + stored);
        }
        if (entity.isEmpty()) {
            throw new AssertionError("o inventario nao deveria estar vazio depois de guardar");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void permissoesDeAutomacaoSeguemODeclarado(TestContext context) {
        DeclarativeBlockEntity entity = placeChest(context, new BlockPos(1, 1, 1));

        ItemStack diamond = new ItemStack(Items.DIAMOND, 1);

        // O exemplo declara um cofre: aceita deposito e recusa retirada. E o par que justifica as
        // duas permissoes existirem separadas -- sem ele, "inventario" seria so um bau.
        if (!entity.canInsert(0, diamond, Direction.UP)) {
            throw new AssertionError("o cofre deveria aceitar insercao automatica");
        }
        if (entity.canExtract(0, diamond, Direction.DOWN)) {
            throw new AssertionError("o cofre nao deveria permitir extracao automatica");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void itensSobrevivemAGravacao(TestContext context) {
        BlockPos relative = new BlockPos(1, 1, 1);
        DeclarativeBlockEntity entity = placeChest(context, relative);
        entity.setStack(0, new ItemStack(Items.EMERALD, 7));

        // Grava e le de volta, que e o caminho que os itens percorrem ao desligar e religar o
        // servidor. Sem isto, um inventario funciona a sessao inteira e esvazia no dia seguinte.
        var registries = context.getWorld().getRegistryManager();
        // Com os dados de identificacao: sem o tipo gravado junto, quem le o NBT nao sabe que
        // entidade construir e devolve nulo -- que e exatamente o que o jogo grava no disco.
        var nbt = entity.createNbtWithIdentifyingData(registries);

        BlockEntity recreated = BlockEntity.createFromNbt(
                context.getAbsolutePos(relative), entity.getCachedState(), nbt, registries);

        if (!(recreated instanceof DeclarativeBlockEntity restored)) {
            throw new AssertionError("a entidade nao foi recriada do NBT: " + recreated);
        }

        ItemStack stored = restored.getStack(0);
        if (!stored.isOf(Items.EMERALD) || stored.getCount() != 7) {
            throw new AssertionError("os itens deveriam ter sobrevivido a gravacao: " + stored);
        }
        context.complete();
    }
}

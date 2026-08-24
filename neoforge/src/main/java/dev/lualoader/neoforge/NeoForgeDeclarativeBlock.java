package dev.lualoader.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Bloco declarado por manifesto, com a variante visual que o resource pack espera.
 *
 * <p>O pack gerado descreve o bloco por variante — {@code lua_variant=0} até {@code lua_variant=15}
 * — e um bloco sem essa propriedade não casa com nenhuma delas: o jogo procura a variante sem
 * propriedades, não encontra, e desenha o cubo de textura ausente.
 *
 * <p>A faixa é fixa em 16 porque é o que o montador do pack sempre escreve, mesmo quando o mod
 * declara uma textura só — as variantes sobrando apontam todas para o primeiro modelo. Uma faixa
 * que acompanhasse a contagem declarada teria dois problemas: não casaria com o blockstate gerado,
 * e com uma textura só produziria uma propriedade de um valor único, que o jogo recusa.
 */
public class NeoForgeDeclarativeBlock extends Block {
    /** Quantas variantes o pack descreve, e portanto quantas o bloco precisa aceitar. */
    public static final int VARIANT_COUNT = 16;

    /** A variante visual, na mesma faixa que o resource pack gera. */
    public static final IntegerProperty VARIANT =
            IntegerProperty.create("lua_variant", 0, VARIANT_COUNT - 1);

    public NeoForgeDeclarativeBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(VARIANT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(VARIANT);
    }
}

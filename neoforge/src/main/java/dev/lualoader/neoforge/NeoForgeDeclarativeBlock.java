package dev.lualoader.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Bloco declarado por manifesto, com a variante visual que o resource pack espera.
 *
 * <p>O pack gerado descreve o bloco por variante — {@code lua_variant=0}, {@code lua_variant=1} — e
 * um bloco sem essa propriedade não casa com nenhuma delas: o jogo procura a variante sem
 * propriedades, não encontra, e desenha o cubo de textura ausente. Declarar a propriedade é o que
 * liga o bloco registrado ao desenho gerado.
 *
 * <p>A propriedade existe mesmo quando o manifesto declara uma variante só, porque o pack sempre
 * escreve o blockstate na forma com variante — e um formato só é mais simples de gerar e de ler que
 * dois.
 */
public class NeoForgeDeclarativeBlock extends Block {
    /** Quantas variantes o jogo aceita por bloco. */
    public static final int MAX_VARIANTS = 16;

    /**
     * A propriedade de variante, uma por contagem possível.
     *
     * <p>Precisa ser criada antes do construtor rodar, porque {@code createBlockStateDefinition} é
     * chamado de dentro dele — e um campo de instância ainda não existe nesse momento. Guardar uma
     * propriedade pronta por contagem resolve sem depender de estado temporário.
     */
    private static final IntegerProperty[] VARIANTS = new IntegerProperty[MAX_VARIANTS + 1];

    static {
        for (int count = 1; count <= MAX_VARIANTS; count++) {
            VARIANTS[count] = IntegerProperty.create("lua_variant", 0, count - 1);
        }
    }

    private final int variantCount;

    /** A propriedade em construção, lida por {@code createBlockStateDefinition}. */
    private static final ThreadLocal<Integer> BUILDING = ThreadLocal.withInitial(() -> 1);

    public static Block create(BlockBehaviour.Properties properties, int variantCount) {
        int count = Math.max(1, Math.min(MAX_VARIANTS, variantCount));
        BUILDING.set(count);
        try {
            return new NeoForgeDeclarativeBlock(properties, count);
        } finally {
            BUILDING.remove();
        }
    }

    protected NeoForgeDeclarativeBlock(BlockBehaviour.Properties properties, int variantCount) {
        super(properties);
        this.variantCount = variantCount;
        registerDefaultState(getStateDefinition().any().setValue(variant(variantCount), 0));
    }

    /** A propriedade de variante para uma contagem. */
    public static IntegerProperty variant(int count) {
        return VARIANTS[Math.max(1, Math.min(MAX_VARIANTS, count))];
    }

    /** Quantas variantes este bloco tem. */
    public int variantCount() {
        return variantCount;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        // Roda de dentro do construtor de Block, antes de o campo de instância existir: por isso a
        // contagem vem do valor em construção, e não de this.variantCount.
        builder.add(variant(BUILDING.get()));
    }
}

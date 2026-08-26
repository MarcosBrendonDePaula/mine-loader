package dev.lualoader.neoforge;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.slf4j.Logger;

import java.util.List;

/**
 * Registra o tipo de bloco com dados.
 *
 * <p>O Minecraft exige que um {@code BlockEntityType} conheça, no registro, todos os blocos que
 * aceita. Como os blocos do loader nascem do manifesto, o tipo só pode ser criado depois que todos
 * estiverem registrados — daí este passo separado.
 */
public final class NeoForgeBlockEntities {
    private static volatile BlockEntityType<NeoForgeDeclarativeBlockEntity> type;

    private NeoForgeBlockEntities() {
    }

    /** Tipo em uso, necessário para construir a entidade. */
    public static BlockEntityType<NeoForgeDeclarativeBlockEntity> type() {
        if (type == null) {
            throw new IllegalStateException("tipo de bloco com dados ainda nao registrado");
        }
        return type;
    }

    public static boolean isRegistered() {
        return type != null;
    }

    /** Cria o tipo cobrindo os blocos que declararam guardar dados ou itens. */
    public static BlockEntityType<NeoForgeDeclarativeBlockEntity> create(Logger logger,
                                                                        List<Block> blocks) {
        if (blocks.isEmpty()) return null;

        type = NeoForgeDeclarativeBlockEntity.createType(blocks.toArray(new Block[0]));
        logger.info("Tipo de bloco com dados registrado para {} bloco(s)", blocks.size());
        return type;
    }
}

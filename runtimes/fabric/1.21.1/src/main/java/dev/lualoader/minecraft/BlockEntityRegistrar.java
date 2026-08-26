package dev.lualoader.minecraft;

import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.util.List;

/**
 * Registra o tipo de bloco com dados.
 *
 * <p>O Minecraft exige que um {@code BlockEntityType} conheça, no registro, todos os blocos que
 * aceita. Como os blocos do loader nascem do manifesto, o tipo só pode ser criado depois que todos
 * estiverem registrados — daí este passo separado, chamado ao fim da carga.
 */
public final class BlockEntityRegistrar {
    private static volatile BlockEntityType<DeclarativeBlockEntity> type;

    private BlockEntityRegistrar() {
    }

    /** Tipo em uso, necessário para construir a entidade. */
    public static BlockEntityType<DeclarativeBlockEntity> type() {
        if (type == null) {
            throw new IllegalStateException("tipo de bloco com dados ainda nao registrado");
        }
        return type;
    }

    public static boolean isRegistered() {
        return type != null;
    }

    /** Cria o tipo cobrindo os blocos que declararam guardar dados. */
    public static void register(Logger logger, List<Block> blocks) {
        if (blocks.isEmpty()) return;

        type = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                Identifier.of("lua_loader", "declarative_data"),
                DeclarativeBlockEntity.createType(blocks.toArray(new Block[0])));

        logger.info("Tipo de dados registrado para {} bloco(s)", blocks.size());
    }
}

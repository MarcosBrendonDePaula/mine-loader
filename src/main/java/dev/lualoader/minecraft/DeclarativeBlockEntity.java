package dev.lualoader.minecraft;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;

/**
 * Guarda dados de um bloco declarativo naquela posição do mundo.
 *
 * <p>Sem isto, um mod só tinha estado por mod: uma máquina, um baú customizado ou um bloco que
 * lembra quem o colocou não tinham onde guardar nada. O conteúdo é mantido como JSON em texto,
 * porque é o mesmo formato já usado no estado do mod e sobrevive a mudanças no manifesto sem exigir
 * um esquema NBT por bloco.
 */
public class DeclarativeBlockEntity extends BlockEntity {
    private static final String DATA_KEY = "lua_data";

    private String data = "{}";

    public DeclarativeBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistrar.type(), pos, state);
    }

    /** Conteúdo atual, como texto JSON. */
    public String data() {
        return data;
    }

    /** Substitui o conteúdo e marca o bloco para ser gravado com o mundo. */
    public void setData(String json) {
        this.data = json == null || json.isBlank() ? "{}" : json;
        markDirty();
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.putString(DATA_KEY, data);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        data = nbt.contains(DATA_KEY) ? nbt.getString(DATA_KEY) : "{}";
    }

    /** Tipo registrado uma vez, cobrindo todos os blocos declarativos com dados. */
    public static BlockEntityType<DeclarativeBlockEntity> createType(net.minecraft.block.Block[] blocks) {
        return BlockEntityType.Builder.create(DeclarativeBlockEntity::new, blocks).build();
    }
}

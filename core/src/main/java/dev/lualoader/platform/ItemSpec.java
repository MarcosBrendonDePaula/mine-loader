package dev.lualoader.platform;

import java.util.List;
import java.util.Map;

/**
 * O que um mod pode declarar sobre o item que está entregando.
 *
 * <p>Mesma razão do {@link EntitySpec} para não aceitar NBT cru: o formato interno de um item mudou
 * de forma para componentes em 1.20.5, e um mod que escrevesse a forma antiga pararia de funcionar
 * sem ter mudado uma linha. Aqui o mod diz o que quer — um nome, um encantamento — e o adaptador
 * sabe como aquela versão guarda isso.
 *
 * <p>{@code null} significa "não declarado". Um item sem nada declarado é o item comum.
 *
 * @param enchantments encantamento por identificador e nível, como {@code minecraft:sharpness -> 5}
 * @param lore         linhas de descrição abaixo do nome, na ordem
 */
public record ItemSpec(
        String name,
        List<String> lore,
        Integer damage,
        Boolean unbreakable,
        Map<String, Integer> enchantments,
        Integer customModelData) {

    /** Nada declarado: o item comum. */
    public static final ItemSpec EMPTY = new ItemSpec(null, null, null, null, null, null);

    /** Se não há nada a aplicar, para o adaptador poder pular o trabalho. */
    public boolean isEmpty() {
        return name == null && (lore == null || lore.isEmpty()) && damage == null
                && unbreakable == null && (enchantments == null || enchantments.isEmpty())
                && customModelData == null;
    }
}

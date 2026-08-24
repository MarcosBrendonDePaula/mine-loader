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
 */
public final class ItemSpec {
    /** Nada declarado: o item comum. */
    public static final ItemSpec EMPTY = new ItemSpec();

    // ------------------------------------------------------------------ aparência
    public String name;
    /** Linhas de descrição abaixo do nome, na ordem. */
    public List<String> lore;
    /** Cor de armadura de couro, como {@code 0xRRGGBB}. */
    public Integer color;
    /** Aponta o cliente para um modelo alternativo declarado no resource pack. */
    public Integer customModelData;

    // ------------------------------------------------------------------ durabilidade
    public Integer damage;
    public Boolean unbreakable;

    // ------------------------------------------------------------------ efeito
    /** Encantamento por identificador e nível, como {@code minecraft:sharpness -> 5}. */
    public Map<String, Integer> enchantments;
    /**
     * Modificadores de atributo por identificador, como {@code minecraft:generic.attack_damage}.
     *
     * <p>É o que faz uma espada dar mais dano do que a espada base. Um mapa pelo mesmo motivo dos
     * atributos de entidade: o jogo tem dezenas e ganha novos, e uma lista fixa envelheceria.
     */
    public Map<String, Double> attributes;

    // ------------------------------------------------------------------ comportamento
    /** Esconde o item da tela de morte e impede que caia. */
    public Boolean keepOnDeath;
    /** Impede o jogador de largar o item. */
    public Boolean noDrop;

    /** Se não há nada a aplicar, para o adaptador poder pular o trabalho. */
    public boolean isEmpty() {
        return name == null && (lore == null || lore.isEmpty()) && color == null
                && customModelData == null && damage == null && unbreakable == null
                && (enchantments == null || enchantments.isEmpty())
                && (attributes == null || attributes.isEmpty())
                && keepOnDeath == null && noDrop == null;
    }

    /** Os encantamentos declarados, nunca nulo. */
    public Map<String, Integer> enchantmentsOrEmpty() {
        return enchantments == null ? Map.of() : enchantments;
    }

    /** Os modificadores declarados, nunca nulo. */
    public Map<String, Double> attributesOrEmpty() {
        return attributes == null ? Map.of() : attributes;
    }

    /** As linhas de descrição, nunca nulo. */
    public List<String> loreOrEmpty() {
        return lore == null ? List.of() : lore;
    }
}

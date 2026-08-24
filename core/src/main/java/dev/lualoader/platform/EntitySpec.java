package dev.lualoader.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O que um mod pode declarar sobre a entidade que está criando.
 *
 * <p>Um vocabulário fechado, e não NBT cru. NBT é a estrutura de dados do Minecraft, e os campos
 * dentro dela mudam entre versões — atributos foram renomeados, e o que hoje é um componente já foi
 * uma etiqueta solta. Deixar o mod escrever NBT amarraria cada mod a uma versão do jogo, que é
 * exatamente o que a camada de plataforma existe para evitar.
 *
 * <p>Cada campo aqui é uma pergunta que qualquer versão do jogo responde. Traduzir "domado" para o
 * que aquela versão chama de domado é trabalho do adaptador, e é onde deve ficar.
 *
 * <p>{@code null} em qualquer campo significa "não declarado": o jogo decide, como faria sem o mod.
 * É diferente de declarar o valor padrão, porque um {@code false} declarado impede o jogo de
 * escolher outra coisa.
 *
 * <p>Campos públicos, e não um record: com quase vinte deles, um construtor posicional viraria uma
 * fila de nulos em que trocar dois de lugar não daria erro de compilação.
 */
public final class EntitySpec {
    /** Nada declarado: o jogo decide tudo. */
    public static final EntitySpec EMPTY = new EntitySpec();

    // ------------------------------------------------------------------ identidade
    public String name;
    public Boolean nameVisible;

    // ------------------------------------------------------------------ natureza
    public Boolean tame;
    public Boolean baby;
    public Boolean persistent;
    public Boolean noAi;
    /** Cor do cavalo, tipo do gato, padrão do axolote. O nome depende da espécie. */
    public String variant;

    // ------------------------------------------------------------------ corpo
    public Double health;
    /**
     * Atributos por identificador, como {@code minecraft:generic.movement_speed}.
     *
     * <p>Um mapa em vez de um campo por atributo: o jogo tem dezenas e ganha novos a cada versão, e
     * uma lista fixa aqui envelheceria a cada uma delas. Um identificador desconhecido é ignorado,
     * pelo mesmo motivo que um campo que a entidade não suporta é.
     */
    public Map<String, Double> attributes;
    public List<EffectSpec> effects;
    /** Equipamento por espaço: {@code main_hand}, {@code off_hand}, {@code head}… */
    public Map<String, EquipmentSpec> equipment;

    // ------------------------------------------------------------------ estado
    public Boolean invulnerable;
    public Boolean silent;
    public Boolean noGravity;
    public Boolean glowing;
    public Integer fireTicks;
    public Integer frozenTicks;

    // ------------------------------------------------------------------ orientação
    public Float yaw;
    public Float pitch;

    /** Um efeito de poção sobre a entidade. */
    public static final class EffectSpec {
        public String id;
        /** Em ticks; 20 por segundo. Sem declarar, trinta segundos. */
        public Integer duration;
        /** Nível a partir de zero, como o jogo conta: zero é o nível I. */
        public Integer amplifier;
        public Boolean ambient;
        public Boolean showParticles;

        public EffectSpec(String id, Integer duration, Integer amplifier,
                          Boolean ambient, Boolean showParticles) {
            this.id = id;
            this.duration = duration;
            this.amplifier = amplifier;
            this.ambient = ambient;
            this.showParticles = showParticles;
        }
    }

    /**
     * Uma peça de equipamento.
     *
     * <p>Carrega um {@link ItemSpec} porque a armadura do chefe da masmorra é um item como
     * qualquer outro: pode ter nome, encantamento e ser inquebrável.
     */
    public static final class EquipmentSpec {
        public String item;
        public ItemSpec data;
        /** Chance de cair quando a entidade morre, de 0 a 1. Sem declarar, a do jogo. */
        public Float dropChance;

        public EquipmentSpec(String item, ItemSpec data, Float dropChance) {
            this.item = item;
            this.data = data;
            this.dropChance = dropChance;
        }
    }

    /** Se não há nada a aplicar, para o adaptador poder pular o trabalho. */
    public boolean isEmpty() {
        return name == null && nameVisible == null && tame == null && baby == null
                && persistent == null && noAi == null && variant == null
                && health == null && isBlank(attributes) && (effects == null || effects.isEmpty())
                && isBlank(equipment)
                && invulnerable == null && silent == null && noGravity == null && glowing == null
                && fireTicks == null && frozenTicks == null
                && yaw == null && pitch == null;
    }

    private static boolean isBlank(Map<String, ?> map) {
        return map == null || map.isEmpty();
    }

    /** Os atributos declarados, nunca nulo, para quem itera não precisar checar. */
    public Map<String, Double> attributesOrEmpty() {
        return attributes == null ? Map.of() : attributes;
    }

    /** Os efeitos declarados, nunca nulo. */
    public List<EffectSpec> effectsOrEmpty() {
        return effects == null ? List.of() : effects;
    }

    /** O equipamento declarado, nunca nulo. */
    public Map<String, EquipmentSpec> equipmentOrEmpty() {
        return equipment == null ? new LinkedHashMap<>() : equipment;
    }
}

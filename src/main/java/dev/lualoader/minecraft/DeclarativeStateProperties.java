package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converte as propriedades de estado declaradas no manifesto em {@link Property} do Minecraft.
 *
 * <p>Antes desta classe, {@code state.properties} era validado e descartado: o bloco recebia
 * apenas as propriedades fixas do loader. Agora cada propriedade declarada existe de fato no
 * blockstate.
 *
 * <p>As propriedades são construídas uma única vez por bloco, no momento do registro, porque o
 * Minecraft congela o {@code StateManager} depois disso.
 */
public final class DeclarativeStateProperties {
    private final Map<String, Property<?>> properties;
    private final Map<String, String> defaults;

    /**
     * Se este bloco recebe a propriedade de variante.
     *
     * <p>Fica separado do mapa porque a propriedade e do loader, e nao declarada pelo manifesto: o
     * bloco base a acrescenta no {@code appendProperties}, e o mapa carrega so o que veio do JSON.
     */
    private final boolean variant;

    /** Se este bloco recebe a propriedade de luminosidade. */
    private final boolean luminance;

    private DeclarativeStateProperties(Map<String, Property<?>> properties,
                                       Map<String, String> defaults,
                                       boolean variant, boolean luminance) {
        this.properties = properties;
        this.defaults = defaults;
        this.variant = variant;
        this.luminance = luminance;
    }

    /** Se este bloco recebe a propriedade de variante. */
    public boolean hasVariant() {
        return variant;
    }

    /** Se este bloco recebe a propriedade de luminosidade. */
    public boolean hasLuminance() {
        return luminance;
    }

    public static DeclarativeStateProperties from(ModManifest.BlockDefinition definition) {
        Map<String, Property<?>> built = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();

        if (definition.state != null && definition.state.properties != null) {
            for (ModManifest.StatePropertyDefinition property : definition.state.properties) {
                if (property == null || property.name == null) continue;
                Property<?> created = create(property);
                if (created != null) built.put(property.name, created);
            }
        }
        // A direcao entra como propriedade tambem, e nao como caso a parte: o bloco ja sabe montar
        // o estado a partir deste mapa, e um caminho paralelo so para facing daria duas formas de
        // registrar a mesma coisa.
        Property<?> facing = facingProperty(definition);
        if (facing != null) built.put("facing", facing);

        // Um bloco que conecta ganha uma propriedade de tres valores por lado: livre, ligado a um
        // bloco da lista, ou ligado a um inventario.
        //
        // Tres e nao dois porque o braco nao e o mesmo: o cano do mod original encaixa num bau com
        // uma ponta diferente da que encaixa em outro cano, e um booleano nao tem como dizer qual
        // desenhar. Sao 729 estados por bloco, contra os 4096 de dois booleanos por lado.
        if (connects(definition)) {
            for (String side : dev.lualoader.content.BlockShapes.SIDES) {
                built.put(side, NamedProperty.of(side,
                        dev.lualoader.content.BlockShapes.LINK_VALUES));
            }
        }

        if (definition.state != null && definition.state.defaults != null) {
            defaults.putAll(definition.state.defaults);
        }
        return new DeclarativeStateProperties(built, defaults,
                dev.lualoader.content.BlockShapes.needsVariant(definition),
                dev.lualoader.content.BlockShapes.needsLuminance(definition));
    }

    /** Se o bloco declara nucleo e a quem se conectar. A regra mora no nucleo. */
    public static boolean connects(ModManifest.BlockDefinition definition) {
        return dev.lualoader.content.BlockShapes.connects(definition);
    }

    /**
     * A propriedade de direcao, quando o bloco declara que gira.
     *
     * <p>{@code horizontal} e {@code player} usam os quatro lados -- a diferenca entre eles esta em
     * *como* a direcao e escolhida na hora de colocar, e nao em quais valores existem. {@code all}
     * inclui cima e baixo, como o observador do jogo.
     */
    private static Property<?> facingProperty(ModManifest.BlockDefinition definition) {
        if (definition.placement == null || definition.placement.facing == null) return null;

        return switch (definition.placement.facing.trim().toLowerCase(Locale.ROOT)) {
            case "horizontal", "player" -> net.minecraft.state.property.Properties.HORIZONTAL_FACING;
            case "all" -> net.minecraft.state.property.Properties.FACING;
            default -> null;
        };
    }

    private static Property<?> create(ModManifest.StatePropertyDefinition property) {
        String type = property.type == null ? "string" : property.type.trim().toLowerCase(Locale.ROOT);
        List<String> values = property.values == null ? List.of() : property.values;

        switch (type) {
            case "bool", "boolean" -> {
                return BooleanProperty.of(property.name);
            }
            case "int", "integer" -> {
                int min = Integer.MAX_VALUE;
                int max = Integer.MIN_VALUE;
                for (String value : values) {
                    try {
                        int parsed = Integer.parseInt(value.trim());
                        min = Math.min(min, parsed);
                        max = Math.max(max, parsed);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
                if (min > max) return null;
                return IntProperty.of(property.name, min, max);
            }
            default -> {
                List<String> named = new ArrayList<>();
                for (String value : values) {
                    if (value != null && !value.isBlank()) named.add(value.trim());
                }
                if (named.isEmpty()) return null;
                return NamedProperty.of(property.name, named);
            }
        }
    }

    public Map<String, Property<?>> properties() {
        return properties;
    }

    public Map<String, String> defaults() {
        return defaults;
    }

    public boolean isEmpty() {
        return properties.isEmpty();
    }
}

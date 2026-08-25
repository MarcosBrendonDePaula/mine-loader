package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converte as propriedades de estado declaradas no manifesto em {@link Property} do Minecraft.
 *
 * <p>É o par de {@code DeclarativeStateProperties} do adaptador Fabric, e a conversão é deliberadamente
 * a mesma: tipo, faixa e valor padrão saem do mesmo manifesto, e um bloco que declara
 * {@code state.properties} precisa nascer com as mesmas propriedades nas duas plataformas. Enquanto
 * esta classe não existiu, o campo era validado no núcleo e descartado aqui — o bloco recebia
 * apenas as propriedades fixas do loader, e um mod que dependesse do estado declarado funcionava
 * num lado e não no outro.
 *
 * <p>As propriedades são construídas uma única vez por bloco, no momento do registro, porque o
 * Minecraft congela a definição de estado depois disso.
 */
public final class NeoForgeStateProperties {
    private final Map<String, Property<?>> properties;
    private final Map<String, String> defaults;

    private NeoForgeStateProperties(Map<String, Property<?>> properties,
                                    Map<String, String> defaults) {
        this.properties = properties;
        this.defaults = defaults;
    }

    public static NeoForgeStateProperties from(ModManifest.BlockDefinition definition) {
        Map<String, Property<?>> built = new LinkedHashMap<>();
        Map<String, String> defaults = new LinkedHashMap<>();

        if (definition.state != null && definition.state.properties != null) {
            for (ModManifest.StatePropertyDefinition property : definition.state.properties) {
                if (property == null || property.name == null) continue;
                Property<?> created = create(property);
                if (created != null) built.put(property.name, created);
            }
        }
        if (definition.state != null && definition.state.defaults != null) {
            defaults.putAll(definition.state.defaults);
        }

        // A direcao entra como propriedade tambem, e nao como caso a parte: o bloco ja sabe montar
        // o estado a partir deste mapa.
        Property<?> facing = facingProperty(definition);
        if (facing != null) {
            built.put("facing", facing);
        }

        // Um bloco que conecta ganha uma propriedade booleana por lado -- o mesmo que cerca e muro
        // fazem. A lista de lados vem do nucleo: os dois adaptadores precisam concordar sobre qual
        // propriedade e qual direcao.
        if (connects(definition)) {
            for (String side : dev.lualoader.content.BlockShapes.SIDES) {
                built.put(side, BooleanProperty.create(side));
            }
        }
        return new NeoForgeStateProperties(built, defaults);
    }

    /** Se o bloco declara nucleo e a quem se conectar. */
    public static boolean connects(ModManifest.BlockDefinition definition) {
        return dev.lualoader.content.BlockShapes.connects(definition);
    }

    /**
     * A propriedade de direcao, quando o bloco declara que gira.
     *
     * <p>{@code horizontal} e {@code player} usam os quatro lados; a diferenca entre eles esta em
     * como a direcao e escolhida ao colocar, e nao em quais valores existem.
     */
    private static Property<?> facingProperty(ModManifest.BlockDefinition definition) {
        if (definition.placement == null || definition.placement.facing == null) return null;

        return switch (definition.placement.facing.trim().toLowerCase(Locale.ROOT)) {
            case "horizontal", "player" ->
                    net.minecraft.world.level.block.state.properties.BlockStateProperties
                            .HORIZONTAL_FACING;
            case "all" ->
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
            default -> null;
        };
    }

    private static Property<?> create(ModManifest.StatePropertyDefinition property) {
        String type = property.type == null
                ? "string"
                : property.type.trim().toLowerCase(Locale.ROOT);
        List<String> values = property.values == null ? List.of() : property.values;

        switch (type) {
            case "bool", "boolean" -> {
                return BooleanProperty.create(property.name);
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
                return IntegerProperty.create(property.name, min, max);
            }
            default -> {
                List<String> named = new ArrayList<>();
                for (String value : values) {
                    if (value != null && !value.isBlank()) named.add(value.trim());
                }
                if (named.isEmpty()) return null;
                return NeoForgeNamedProperty.of(property.name, named);
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

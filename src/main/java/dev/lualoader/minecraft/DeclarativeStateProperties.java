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

    private DeclarativeStateProperties(Map<String, Property<?>> properties, Map<String, String> defaults) {
        this.properties = properties;
        this.defaults = defaults;
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
        if (definition.state != null && definition.state.defaults != null) {
            defaults.putAll(definition.state.defaults);
        }
        return new DeclarativeStateProperties(built, defaults);
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

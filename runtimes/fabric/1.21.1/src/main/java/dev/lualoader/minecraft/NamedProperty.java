package dev.lualoader.minecraft;

import net.minecraft.state.property.Property;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Propriedade de blockstate cujos valores são nomes declarados no manifesto.
 *
 * <p>{@code EnumProperty} exige um enum Java, que não existe para valores vindos de JSON, e
 * {@code StringIdentifiable} sozinho não satisfaz aquele contrato. Esta propriedade guarda os
 * valores como texto, preservando exatamente os nomes que o criador escreveu no manifesto.
 */
public final class NamedProperty extends Property<String> {
    private final List<String> values;

    private NamedProperty(String name, List<String> values) {
        super(name, String.class);
        this.values = List.copyOf(values);
    }

    public static NamedProperty of(String name, List<String> values) {
        return new NamedProperty(name, values);
    }

    @Override
    public Collection<String> getValues() {
        return values;
    }

    @Override
    public String name(String value) {
        return value;
    }

    @Override
    public Optional<String> parse(String name) {
        return values.contains(name) ? Optional.of(name) : Optional.empty();
    }
}

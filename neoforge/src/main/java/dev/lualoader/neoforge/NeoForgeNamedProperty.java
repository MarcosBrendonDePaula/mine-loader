package dev.lualoader.neoforge;

import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Propriedade de blockstate cujos valores são nomes declarados no manifesto.
 *
 * <p>{@code EnumProperty} exige um enum Java, que não existe para valores vindos de JSON, e a
 * interface de nome serializável sozinha não satisfaz aquele contrato. Esta propriedade guarda os
 * valores como texto, preservando exatamente os nomes que o criador escreveu no manifesto.
 *
 * <p>É o par de {@code NamedProperty} do adaptador Fabric. Os dois precisam aceitar os mesmos
 * nomes: o blockstate gerado pelo pack é o mesmo arquivo nas duas plataformas.
 */
public final class NeoForgeNamedProperty extends Property<String> {
    private final List<String> values;

    private NeoForgeNamedProperty(String name, List<String> values) {
        super(name, String.class);
        this.values = List.copyOf(values);
    }

    public static NeoForgeNamedProperty of(String name, List<String> values) {
        return new NeoForgeNamedProperty(name, values);
    }

    @Override
    public Collection<String> getPossibleValues() {
        return values;
    }

    @Override
    public String getName(String value) {
        return value;
    }

    @Override
    public Optional<String> getValue(String name) {
        return values.contains(name) ? Optional.of(name) : Optional.empty();
    }
}

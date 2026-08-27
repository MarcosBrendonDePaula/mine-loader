package dev.lualoader.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/**
 * Perfil declarativo das APIs que um runtime entrega ao mod.
 *
 * <p>As versões aqui são versões do contrato do MineLoader, não versões do Minecraft. Um bridge pode
 * mudar a tradução interna sem quebrar o mod enquanto continuar a satisfazer a mesma versão de domínio
 * ou capability.
 */
public final class RuntimeContract {
    private static final Map<String, String> STANDARD_DOMAINS;
    private static final Map<String, String> STANDARD_CAPABILITIES;

    static {
        Map<String, String> domains = new LinkedHashMap<>();
        domains.put("core", "1.0.0");
        domains.put("world", "1.0.0");
        domains.put("player", "1.0.0");
        domains.put("entity", "1.0.0");
        domains.put("inventory", "1.0.0");
        domains.put("registry", "1.0.0");
        domains.put("events", "1.0.0");
        domains.put("scheduler", "1.0.0");
        domains.put("ui", "1.0.0");
        domains.put("client", "1.0.0");
        domains.put("resources", "1.0.0");
        STANDARD_DOMAINS = Map.copyOf(domains);

        Map<String, String> capabilities = new LinkedHashMap<>();
        capabilities.put("world.block_state.read", "1.0.0");
        capabilities.put("world.block_state.write", "1.0.0");
        capabilities.put("world.game_rule.read", "1.0.0");
        capabilities.put("world.game_rule.write", "1.0.0");
        capabilities.put("world.difficulty.read", "1.0.0");
        capabilities.put("world.difficulty.write", "1.0.0");
        capabilities.put("world.weather.read", "1.0.0");
        capabilities.put("world.weather.write", "1.0.0");
        capabilities.put("world.time.read", "1.0.0");
        capabilities.put("world.time.write", "1.0.0");
        capabilities.put("world.redstone.read", "1.0.0");
        capabilities.put("world.biome.read", "1.0.0");
        capabilities.put("world.light.read", "1.0.0");
        capabilities.put("player.looking_at.read", "1.0.0");
        capabilities.put("player.data.read", "1.0.0");
        capabilities.put("player.data.write", "1.0.0");
        capabilities.put("entity.read", "1.0.0");
        capabilities.put("entity.spawn", "1.0.0");
        capabilities.put("entity.modify", "1.0.0");
        capabilities.put("inventory.block.read", "1.0.0");
        capabilities.put("inventory.block.write", "1.0.0");
        capabilities.put("registry.query", "1.0.0");
        capabilities.put("events.lifecycle", "1.0.0");
        capabilities.put("scheduler.after", "1.0.0");
        capabilities.put("scheduler.block", "1.0.0");
        capabilities.put("server.command.schema", "1.0.0");
        capabilities.put("ui.menu", "1.0.0");
        capabilities.put("ui.screen", "1.0.0");
        capabilities.put("client.input.keybind", "1.0.0");
        capabilities.put("resources.pack", "1.0.0");
        STANDARD_CAPABILITIES = Map.copyOf(capabilities);
    }

    private final String runtimeId;
    private final Map<String, String> domains;
    private final Map<String, String> capabilities;

    public RuntimeContract(String runtimeId, Map<String, String> domains,
                           Map<String, String> capabilities) {
        this.runtimeId = Objects.requireNonNull(runtimeId, "runtimeId");
        this.domains = Map.copyOf(Objects.requireNonNull(domains, "domains"));
        this.capabilities = Map.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
    }

    /** Perfil comum actualmente entregue pelos quatro runtimes mantidos. */
    public static RuntimeContract standard() {
        return new RuntimeContract("standard", STANDARD_DOMAINS, STANDARD_CAPABILITIES);
    }

    /** Perfil comum identificado pela combinação de loader e Minecraft. */
    public static RuntimeContract forRuntime(String loader, String minecraftVersion) {
        String normalizedLoader = Objects.requireNonNull(loader, "loader").trim()
                .toLowerCase(Locale.ROOT);
        String normalizedVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion").trim();
        if (normalizedLoader.isEmpty() || normalizedVersion.isEmpty()) {
            throw new IllegalArgumentException("loader e minecraftVersion sao obrigatorios");
        }
        return new RuntimeContract(normalizedLoader + "/" + normalizedVersion,
                STANDARD_DOMAINS, STANDARD_CAPABILITIES);
    }

    public String runtimeId() {
        return runtimeId;
    }

    public Map<String, String> domains() {
        return domains;
    }

    public Map<String, String> capabilities() {
        return capabilities;
    }

    public String domainVersion(String id) {
        return domains.get(id);
    }

    public String capabilityVersion(String id) {
        return capabilities.get(id);
    }

    public boolean satisfiesDomain(String id, String minimum) {
        return domainVersion(id) != null && ModDependencies.satisfies(domainVersion(id), minimum);
    }

    public boolean satisfiesCapability(String id, String minimum) {
        return capabilityVersion(id) != null && ModDependencies.satisfies(capabilityVersion(id), minimum);
    }
}

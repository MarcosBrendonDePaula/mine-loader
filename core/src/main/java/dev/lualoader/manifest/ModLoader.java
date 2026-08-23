package dev.lualoader.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Descobre e valida os mods-lua/<id>/mod.json. */
public final class ModLoader {
    private static final Pattern MOD_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");
    private static final Pattern LUA_FILE = Pattern.compile("^[^/\\\\][^:]*\\.lua$");
    private static final Set<String> RARITIES = Set.of("common", "uncommon", "rare", "epic");
    private static final Set<String> EVENTS = Set.of(
            "loader_ready", "server_started", "server_stopped", "player_joined", "tick",
            "block_used", "block_attacked"
    );

    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();
    private final Logger logger;

    public ModLoader(Logger logger) {
        this.logger = logger;
    }

    public List<LoadedMod> discover(Path root) throws IOException {
        Files.createDirectories(root);
        List<LoadedMod> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        try (var directories = Files.list(root)) {
            for (Path directory : directories.sorted().toList()) {
                if (!Files.isDirectory(directory)) continue;

                Path manifestPath = directory.resolve("mod.json");
                if (!Files.isRegularFile(manifestPath)) {
                    logger.warn("Ignorando {}: mod.json não encontrado", directory);
                    continue;
                }

                try {
                    ModManifest manifest = readManifest(manifestPath, directory);
                    validate(manifest, directory, ids);
                    if (!manifest.enabled) {
                        logger.info("Mod desabilitado: {}", manifest.id);
                        continue;
                    }
                    ids.add(manifest.id);
                    result.add(new LoadedMod(directory, manifest));
                } catch (IOException | RuntimeException error) {
                    // IOException inclui manifesto ilegivel e import quebrado. Sem este catch,
                    // um unico mod defeituoso impediria a carga de todos os outros.
                    logger.error("Falha ao carregar mod em {}: {}", directory, error.getMessage());
                }
            }
        }

        return List.copyOf(result);
    }

    private ModManifest readManifest(Path path, Path modRoot) throws IOException {
        try {
            // Os imports sao resolvidos antes da conversao, entao o resto do loader nao
            // precisa saber que o manifesto pode estar dividido em varios arquivos.
            var resolved = new ManifestImports(modRoot).readResolved(path);
            ModManifest manifest = gson.fromJson(resolved, ModManifest.class);
            if (manifest == null) throw new JsonParseException("manifesto vazio");
            return manifest;
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("JSON inválido: " + error.getMessage(), error);
        }
    }

    private void validate(ModManifest manifest, Path directory, Set<String> ids) {
        require(manifest.schema == 1, "schema deve ser 1");
        require(manifest.id != null && MOD_ID.matcher(manifest.id).matches(), "id inválido");
        require(manifest.name != null && !manifest.name.isBlank(), "name é obrigatório");
        require(manifest.version != null && !manifest.version.isBlank(), "version é obrigatória");
        require(manifest.entrypoint != null && LUA_FILE.matcher(manifest.entrypoint).matches(), "entrypoint Lua inválido");
        require(directory.getFileName().toString().equals(manifest.id), "o nome da pasta deve ser igual ao id");
        require(!ids.contains(manifest.id), "id duplicado: " + manifest.id);

        Path root = directory.toAbsolutePath().normalize();
        Path entrypoint = directory.resolve(manifest.entrypoint).toAbsolutePath().normalize();
        require(entrypoint.startsWith(root), "entrypoint sai da pasta do mod");
        require(Files.isRegularFile(entrypoint), "entrypoint não encontrado: " + manifest.entrypoint);

        if (manifest.permissions != null) {
            Set<String> knownPermissions = Set.of("chat.send", "player.read", "server.read", "server.command.register", "world.read", "world.write");
            for (String permission : manifest.permissions) {
                require(knownPermissions.contains(permission), "permissão desconhecida: " + permission);
            }
        }
        if (manifest.events != null) {
            for (String event : manifest.events.keySet()) {
                require(EVENTS.contains(event), "evento desconhecido: " + event);
            }
        }

        validateItems(manifest);
        validateStructures(manifest);
        validateCreativeTab(manifest);

        Set<String> blockIds = new HashSet<>();
        if (manifest.blocks != null) {
            for (ModManifest.BlockDefinition block : manifest.blocks) {
                require(block != null && block.id != null && MOD_ID.matcher(block.id).matches(), "id de bloco inválido");
                require(block.name != null && !block.name.isBlank(), "name de bloco é obrigatório");
                require(blockIds.add(block.id), "bloco duplicado no mod: " + block.id);
                validateBlock(block);
            }
        }
    }

    private void validateItems(ModManifest manifest) {
        if (manifest.items == null) return;
        Set<String> itemIds = new HashSet<>();
        for (ModManifest.ItemEntryDefinition item : manifest.items) {
            require(item != null && item.id != null && MOD_ID.matcher(item.id).matches(), "id de item invalido");
            require(item.name != null && !item.name.isBlank(), "name de item e obrigatorio");
            require(itemIds.add(item.id), "item duplicado no mod: " + item.id);
            require(item.maxStackSize >= 1 && item.maxStackSize <= 64, "max_stack_size de item deve estar entre 1 e 64");
            require(item.maxDamage >= 0, "max_damage de item nao pode ser negativo");
            require(item.maxDamage == 0 || item.maxStackSize == 1,
                    "item com durabilidade precisa de max_stack_size igual a 1: " + item.id);
            require(RARITIES.contains(rarityOf(item.rarity)), "rarity de item desconhecida: " + item.rarity);
        }
    }

    private void validateStructures(ModManifest manifest) {
        if (manifest.structures == null) return;
        Set<String> structureIds = new HashSet<>();

        for (ModManifest.StructureDefinition structure : manifest.structures) {
            require(structure != null && structure.id != null && MOD_ID.matcher(structure.id).matches(),
                    "id de estrutura invalido");
            require(structureIds.add(structure.id), "estrutura duplicada no mod: " + structure.id);
            require(structure.origin == null || Set.of("bottom_center", "corner").contains(structure.origin),
                    "origin de estrutura desconhecida: " + structure.origin);
            require(structure.layers != null && !structure.layers.isEmpty(),
                    "estrutura sem camadas: " + structure.id);
            require(structure.palette != null && !structure.palette.isEmpty(),
                    "estrutura sem paleta: " + structure.id);

            for (Map.Entry<String, String> entry : structure.palette.entrySet()) {
                require(entry.getKey() != null && entry.getKey().length() == 1,
                        "simbolo de paleta precisa ter exatamente um caractere: " + entry.getKey());
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                int separator = value.indexOf(':');
                require(separator > 0 && separator < value.length() - 1,
                        "bloco da paleta precisa do formato mod:bloco: " + value);
            }

            // Todo simbolo desenhado precisa existir na paleta, senao o posicionamento falharia
            // so na hora de construir, longe da causa.
            for (List<String> layer : structure.layers) {
                require(layer != null && !layer.isEmpty(), "camada vazia na estrutura " + structure.id);
                for (String row : layer) {
                    require(row != null, "linha nula na estrutura " + structure.id);
                    for (int index = 0; index < row.length(); index++) {
                        String symbol = String.valueOf(row.charAt(index));
                        require(structure.palette.containsKey(symbol),
                                "simbolo fora da paleta na estrutura " + structure.id + ": " + symbol);
                    }
                }
            }
        }
    }

    private void validateCreativeTab(ModManifest manifest) {
        ModManifest.CreativeTabDefinition tab = manifest.creativeTab;
        if (tab == null) return;
        require(tab.id != null && MOD_ID.matcher(tab.id).matches(), "id de creative_tab invalido");
        require(tab.name == null || !tab.name.isBlank(), "name de creative_tab nao pode ser vazio");
        if (tab.icon != null && !tab.icon.isBlank()) {
            int separator = tab.icon.indexOf(':');
            require(separator > 0 && separator < tab.icon.length() - 1,
                    "icon de creative_tab precisa do formato mod:item: " + tab.icon);
        }
    }

    private static String rarityOf(String value) {
        return value == null || value.isBlank() ? "common" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void validateBlock(ModManifest.BlockDefinition block) {
        if (block.settings != null) {
            require(block.settings.hardness >= 0 && block.settings.hardness <= 100, "hardness de bloco fora do intervalo");
            require(block.settings.resistance >= 0 && block.settings.resistance <= 100, "resistance de bloco fora do intervalo");
            require(block.settings.luminance >= 0 && block.settings.luminance <= 15, "luminance deve estar entre 0 e 15");
            require(block.settings.slipperiness >= 0 && block.settings.slipperiness <= 10, "slipperiness fora do intervalo");
            require(block.settings.velocityMultiplier >= 0 && block.settings.velocityMultiplier <= 10, "velocityMultiplier fora do intervalo");
            require(block.settings.jumpVelocityMultiplier >= 0 && block.settings.jumpVelocityMultiplier <= 10, "jumpVelocityMultiplier fora do intervalo");
        }
        if (block.item != null) {
            require(RARITIES.contains(rarityOf(block.item.rarity)),
                    "rarity de item de bloco desconhecida: " + block.item.rarity);
        }
        if (block.state != null && block.state.properties != null) {
            Set<String> propertyNames = new HashSet<>();
            for (ModManifest.StatePropertyDefinition property : block.state.properties) {
                require(property != null && property.name != null && property.name.matches("^[a-z][a-z0-9_]{0,31}$"), "nome de estado inválido");
                require(propertyNames.add(property.name), "propriedade de estado duplicada: " + property.name);
                require(property.values != null && !property.values.isEmpty(), "estado sem valores: " + property.name);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record LoadedMod(Path directory, ModManifest manifest) {
    }
}

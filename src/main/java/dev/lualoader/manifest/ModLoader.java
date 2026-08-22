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
import java.util.Set;
import java.util.regex.Pattern;

/** Descobre e valida os mods-lua/<id>/mod.json. */
public final class ModLoader {
    private static final Pattern MOD_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");
    private static final Pattern LUA_FILE = Pattern.compile("^[^/\\\\][^:]*\\.lua$");
    private static final Set<String> EVENTS = Set.of(
            "loader_ready", "server_started", "server_stopped", "player_joined", "tick"
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
                    ModManifest manifest = readManifest(manifestPath);
                    validate(manifest, directory, ids);
                    if (!manifest.enabled) {
                        logger.info("Mod desabilitado: {}", manifest.id);
                        continue;
                    }
                    ids.add(manifest.id);
                    result.add(new LoadedMod(directory, manifest));
                } catch (RuntimeException error) {
                    logger.error("Falha ao validar mod em {}: {}", directory, error.getMessage());
                }
            }
        }

        return List.copyOf(result);
    }

    private ModManifest readManifest(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            ModManifest manifest = gson.fromJson(reader, ModManifest.class);
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
            Set<String> knownPermissions = Set.of("chat.send", "player.read", "server.read", "server.command.register", "world.write");
            for (String permission : manifest.permissions) {
                require(knownPermissions.contains(permission), "permissão desconhecida: " + permission);
            }
        }
        if (manifest.events != null) {
            for (String event : manifest.events.keySet()) {
                require(EVENTS.contains(event), "evento desconhecido: " + event);
            }
        }

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

    private void validateBlock(ModManifest.BlockDefinition block) {
        if (block.settings != null) {
            require(block.settings.hardness >= 0 && block.settings.hardness <= 100, "hardness de bloco fora do intervalo");
            require(block.settings.resistance >= 0 && block.settings.resistance <= 100, "resistance de bloco fora do intervalo");
            require(block.settings.luminance >= 0 && block.settings.luminance <= 15, "luminance deve estar entre 0 e 15");
            require(block.settings.slipperiness >= 0 && block.settings.slipperiness <= 10, "slipperiness fora do intervalo");
            require(block.settings.velocityMultiplier >= 0 && block.settings.velocityMultiplier <= 10, "velocityMultiplier fora do intervalo");
            require(block.settings.jumpVelocityMultiplier >= 0 && block.settings.jumpVelocityMultiplier <= 10, "jumpVelocityMultiplier fora do intervalo");
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

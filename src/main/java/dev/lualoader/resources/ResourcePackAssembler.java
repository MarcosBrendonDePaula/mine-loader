package dev.lualoader.resources;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gera um resource pack do loader a partir das declarações de bloco. */
public final class ResourcePackAssembler {
    private final Logger logger;
    private final RemoteResourceManager remoteResources;

    public ResourcePackAssembler(Logger logger, Path cacheDirectory) throws IOException {
        this.logger = logger;
        this.remoteResources = new RemoteResourceManager(cacheDirectory);
    }

    public void assemble(List<ModLoader.LoadedMod> mods, Path generatedRoot) throws IOException {
        deleteContents(generatedRoot);
        Files.createDirectories(generatedRoot);

        for (ModLoader.LoadedMod mod : mods) {
            if (mod.manifest().blocks == null) continue;
            for (ModManifest.BlockDefinition block : mod.manifest().blocks) {
                assembleBlock(mod, block, generatedRoot);
            }
        }
    }

    private void assembleBlock(ModLoader.LoadedMod mod,
                               ModManifest.BlockDefinition block,
                               Path generatedRoot) throws IOException {
        String namespace = mod.manifest().id;
        String blockId = block.id;
        Map<String, ModManifest.TextureDefinition> variants = new LinkedHashMap<>();

        if (block.render != null && block.render.variantTextures != null) {
            variants.putAll(block.render.variantTextures);
        }
        if (variants.isEmpty()) {
            variants.put("0", block.render == null ? null : block.render.texture);
        }

        Map<Integer, String> models = new LinkedHashMap<>();
        for (Map.Entry<String, ModManifest.TextureDefinition> entry : variants.entrySet()) {
            int variant = parseVariant(entry.getKey());
            if (variant < 0) {
                logger.warn("Variante ignorada em {}: {}", namespace + ":" + blockId, entry.getKey());
                continue;
            }
            String suffix = "_v" + variant;
            Path blockTexture = generatedRoot.resolve("assets").resolve(namespace)
                    .resolve("textures/block").resolve(blockId + suffix + ".png");
            Files.createDirectories(blockTexture.getParent());

            String textureReference = fallbackTexture(block);
            ModManifest.TextureDefinition definition = entry.getValue();
            if (definition != null) {
                try {
                    RemoteResourceManager.ResolvedTexture resolved = remoteResources.resolveTexture(
                            mod.directory(), definition);
                    copyAsPng(resolved.path(), blockTexture);
                    textureReference = namespace + ":block/" + blockId + suffix;
                    logger.info("Textura {} preparada para {} variante {}",
                            resolved.remote() ? "remota" : "local", namespace + ":" + blockId, variant);
                } catch (IOException error) {
                    logger.warn("Textura da variante {} indisponível; usando fallback {}: {}",
                            variant, definition.fallback, error.getMessage());
                }
            }

            String modelId = namespace + ":block/" + blockId + suffix;
            write(generatedRoot.resolve("assets").resolve(namespace)
                            .resolve("models/block").resolve(blockId + suffix + ".json"),
                    "{\n  \"parent\": \"minecraft:block/cube_all\",\n  \"textures\": {\"all\": \"" + textureReference + "\"}\n}\n");
            models.put(variant, modelId);
        }

        if (models.isEmpty()) {
            models.put(0, "minecraft:block/stone");
        }
        String fallbackModel = models.getOrDefault(0, models.values().iterator().next());
        StringBuilder blockstate = new StringBuilder("{\n  \"variants\": {\n");
        for (int variant = 0; variant <= 15; variant++) {
            if (variant > 0) blockstate.append(",\n");
            blockstate.append("    \"lua_variant=").append(variant).append("\": {\"model\": \"")
                    .append(models.getOrDefault(variant, fallbackModel)).append("\"}");
        }
        blockstate.append("\n  }\n}\n");
        write(generatedRoot.resolve("assets").resolve(namespace)
                        .resolve("blockstates").resolve(blockId + ".json"), blockstate.toString());

        if (block.item == null || block.item.register) {
            write(generatedRoot.resolve("assets").resolve(namespace)
                            .resolve("models/item").resolve(blockId + ".json"),
                    "{\n  \"parent\": \"" + fallbackModel + "\"\n}\n");
        }
    }

    private static int parseVariant(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && parsed <= 15 ? parsed : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static String fallbackTexture(ModManifest.BlockDefinition block) {
        if (block.render != null && block.render.texture != null
                && block.render.texture.fallback != null
                && !block.render.texture.fallback.isBlank()) {
            return block.render.texture.fallback;
        }
        return "minecraft:block/stone";
    }

    private static void copyAsPng(Path input, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        BufferedImage image;
        try (var stream = Files.newInputStream(input)) {
            image = ImageIO.read(stream);
        }
        if (image == null) throw new IOException("recurso não é uma imagem");
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("não foi possível codificar PNG");
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void deleteContents(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) Files.deleteIfExists(path);
            }
        }
    }
}

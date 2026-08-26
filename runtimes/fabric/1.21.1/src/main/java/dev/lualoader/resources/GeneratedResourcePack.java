package dev.lualoader.resources;

import net.minecraft.resource.InputSupplier;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.metadata.ResourceMetadataReader;
import net.minecraft.registry.VersionedIdentifier;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;

/** ResourcePack somente leitura baseado em um diretório gerado pelo loader. */
public final class GeneratedResourcePack implements ResourcePack {
    private final Path root;
    private final ResourcePackInfo info;

    public GeneratedResourcePack(Path root, ResourcePackInfo info) {
        this.root = root.toAbsolutePath().normalize();
        this.info = info;
    }

    @Override
    public InputSupplier<InputStream> openRoot(String... path) {
        Path resolved = safeResolve(root, path);
        return Files.isRegularFile(resolved) ? InputSupplier.create(resolved) : null;
    }

    @Override
    public InputSupplier<InputStream> open(ResourceType type, Identifier id) {
        Path resolved = safeResolve(root, type.getDirectory(), id.getNamespace(), id.getPath());
        return Files.isRegularFile(resolved) ? InputSupplier.create(resolved) : null;
    }

    @Override
    public void findResources(ResourceType type, String namespace, String prefix, ResultConsumer consumer) {
        Path base = safeResolve(root, type.getDirectory(), namespace, prefix);
        if (!Files.isDirectory(base)) return;

        try (var files = Files.walk(base)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                Path relative = base.relativize(file);
                String path = relative.toString().replace((char) 92, '/');
                String resourcePath = prefix.isBlank() ? path : prefix + "/" + path;
                consumer.accept(Identifier.of(namespace, resourcePath),
                        InputSupplier.create(file));
            });
        } catch (IOException ignored) {
            // Um pack virtual não deve derrubar o reload inteiro por uma pasta removida durante a varredura.
        }
    }

    @Override
    public Set<String> getNamespaces(ResourceType type) {
        Path directory = safeResolve(root, type.getDirectory());
        if (!Files.isDirectory(directory)) return Set.of();
        Set<String> namespaces = new HashSet<>();
        try (var children = Files.list(directory)) {
            children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(GeneratedResourcePack::validNamespace)
                    .forEach(namespaces::add);
        } catch (IOException ignored) {
            return Set.of();
        }
        return Set.copyOf(namespaces);
    }

    @Override
    public <T> T parseMetadata(ResourceMetadataReader<T> metaReader) {
        return null;
    }

    @Override
    public ResourcePackInfo getInfo() {
        return info;
    }

    @Override
    public void close() {
        // O pack usa apenas arquivos e não mantém handles abertos.
    }

    private static Path safeResolve(Path base, String... parts) {
        Path resolved = base;
        for (String part : parts) {
            if (part == null || part.isBlank() || part.contains("..") || part.contains(":") || part.contains("\\")) {
                return base.resolve("__invalid__");
            }
            resolved = resolved.resolve(part);
        }
        resolved = resolved.normalize();
        return resolved.startsWith(base) ? resolved : base.resolve("__invalid__");
    }

    private static boolean validNamespace(String namespace) {
        return namespace.matches("[a-z0-9_.-]+");
    }
}

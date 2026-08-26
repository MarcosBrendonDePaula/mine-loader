package dev.lualoader.resources;

import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackPosition;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourcePackCompatibility;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Adiciona o pack gerado pelo loader à lista de resource packs disponíveis. */
public final class GeneratedResourcePackProvider implements ResourcePackProvider {
    public static final String PACK_ID = "lua_loader_generated";
    private static volatile Path root;

    public static void setRoot(Path generatedRoot) {
        root = generatedRoot == null ? null : generatedRoot.toAbsolutePath().normalize();
    }

    /**
     * Onde o pacote gerado foi escrito, ou {@code null} se nada foi montado.
     *
     * <p>O cliente precisa ler a geometria de uma especie antes da primeira carga de recursos, para
     * montar o desenhista. Pedir o arquivo ao gerenciador de recursos nessa altura nao devolveria
     * nada -- e a especie sairia com a forma da base sem ninguem saber por que.
     */
    public static Path root() {
        return root;
    }

    @Override
    public void register(Consumer<ResourcePackProfile> consumer) {
        Path currentRoot = root;
        if (currentRoot == null || !Files.isDirectory(currentRoot)) return;

        ResourcePackInfo info = new ResourcePackInfo(
                PACK_ID,
                Text.literal("Lua Loader — recursos gerados"),
                ResourcePackSource.BUILTIN,
                Optional.empty()
        );
        ResourcePackProfile.PackFactory factory = new ResourcePackProfile.PackFactory() {
            @Override
            public net.minecraft.resource.ResourcePack open(ResourcePackInfo packInfo) {
                return new GeneratedResourcePack(currentRoot, packInfo);
            }

            @Override
            public net.minecraft.resource.ResourcePack openWithOverlays(
                    ResourcePackInfo packInfo,
                    ResourcePackProfile.Metadata metadata) {
                return new GeneratedResourcePack(currentRoot, packInfo);
            }
        };
        ResourcePackProfile.Metadata metadata = new ResourcePackProfile.Metadata(
                Text.literal("Texturas baixadas ou geradas pelo Minecraft Lua Loader"),
                ResourcePackCompatibility.COMPATIBLE,
                FeatureSet.empty(),
                List.of()
        );
        ResourcePackPosition position = new ResourcePackPosition(
                true,
                ResourcePackProfile.InsertionPosition.TOP,
                true
        );
        consumer.accept(new ResourcePackProfile(info, factory, metadata, position));
    }
}

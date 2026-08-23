package dev.lualoader.manifest;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Relata campos que o manifesto aceita mas que o loader ainda não aplica.
 *
 * <p>Sem este relatório, um criador podia declarar {@code placement} ou {@code behavior} e
 * concluir que o loader os respeitava, já que a validação passava em silêncio. O contrato só
 * é honesto se aquilo que não é implementado for dito em voz alta.
 *
 * <p>Conforme cada campo passa a ser implementado, ele sai desta lista.
 */
public final class ManifestDiagnostics {
    private final Logger logger;

    public ManifestDiagnostics(Logger logger) {
        this.logger = logger;
    }

    public void report(ModManifest manifest) {
        List<String> ignored = collectIgnored(manifest);
        if (ignored.isEmpty()) return;

        logger.warn("Mod {} declara {} campo(s) que o loader ainda nao aplica:", manifest.id, ignored.size());
        for (String field : ignored) {
            logger.warn("  - {}", field);
        }
    }

    /** Lista, legível e ordenada, dos campos declarados que não têm efeito. */
    public List<String> collectIgnored(ModManifest manifest) {
        List<String> ignored = new ArrayList<>();
        if (manifest.blocks == null) return ignored;

        for (ModManifest.BlockDefinition block : manifest.blocks) {
            if (block == null) continue;
            String prefix = "blocks[" + block.id + "].";

            if (block.type != null && !block.type.isBlank() && !"generic".equals(block.type)) {
                ignored.add(prefix + "type: apenas 'generic' e implementado");
            }
            if (block.base != null && !block.base.isBlank()) {
                ignored.add(prefix + "base");
            }
            if (block.behavior != null) {
                addIfPresent(ignored, prefix + "behavior.on_place", block.behavior.onPlace);
                addIfPresent(ignored, prefix + "behavior.on_break", block.behavior.onBreak);
                addIfPresent(ignored, prefix + "behavior.on_use", block.behavior.onUse);
                addIfPresent(ignored, prefix + "behavior.on_random_tick", block.behavior.onRandomTick);
                addIfPresent(ignored, prefix + "behavior.on_neighbor_update", block.behavior.onNeighborUpdate);
            }
            if (block.placement != null) {
                ModManifest.PlacementDefinition placement = block.placement;
                if (placement.canReplace) ignored.add(prefix + "placement.can_replace");
                if (!placement.canPlaceAt) ignored.add(prefix + "placement.can_place_at");
                if (placement.facing != null && !placement.facing.isBlank()
                        && !"none".equals(placement.facing)) {
                    ignored.add(prefix + "placement.facing");
                }
                if (placement.waterloggable) ignored.add(prefix + "placement.waterloggable");
                if (placement.rotateWithPlayer) ignored.add(prefix + "placement.rotate_with_player");
            }
            if (block.shape != null) {
                addIfCustom(ignored, prefix + "shape.collision", block.shape.collision);
                addIfCustom(ignored, prefix + "shape.outline", block.shape.outline);
                addIfCustom(ignored, prefix + "shape.visual", block.shape.visual);
            }
            if (block.render != null) {
                ModManifest.RenderDefinition render = block.render;
                if (render.model != null && !render.model.isBlank() && !"cube_all".equals(render.model)) {
                    ignored.add(prefix + "render.model: apenas 'cube_all' e implementado");
                }
                if (render.renderLayer != null && !render.renderLayer.isBlank()
                        && !"solid".equals(render.renderLayer)) {
                    ignored.add(prefix + "render.render_layer");
                }
                if (render.translucent) ignored.add(prefix + "render.translucent");
                if (render.cutout) ignored.add(prefix + "render.cutout");
                if (render.emissive) ignored.add(prefix + "render.emissive");
                addIfPresent(ignored, prefix + "render.tint", render.tint);
            }
        }
        return ignored;
    }

    private static void addIfPresent(List<String> ignored, String field, String value) {
        if (value != null && !value.isBlank()) ignored.add(field);
    }

    private static void addIfCustom(List<String> ignored, String field, String value) {
        if (value != null && !value.isBlank() && !"full_cube".equals(value)) ignored.add(field);
    }
}

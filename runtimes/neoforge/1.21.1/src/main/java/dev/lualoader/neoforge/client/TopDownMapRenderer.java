package dev.lualoader.neoforge.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lualoader.camera.CameraProtocol;
import dev.lualoader.ui.ScreenModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captura aérea de baixa resolução para o elemento de mapa.
 *
 * <p>Não cria uma segunda passagem do WorldRenderer. Rasteriza apenas a superfície que o cliente
 * já consegue consultar numa NativeImage pequena e faz upload no intervalo declarado pela câmera.
 * O ID lógico é a chave do estado; o ResourceLocation é privado e derivado pelo bridge.
 */
public final class TopDownMapRenderer {
    private static final String IMPLICIT_CAMERA = "__element_topdown";
    private static final Map<String, TextureState> STATES = new LinkedHashMap<>();

    private TopDownMapRenderer() {
    }

    public static void draw(GuiGraphics graphics, ScreenModel.Element element, int x, int y) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.player == null) return;

        CameraProtocol.Camera camera = CameraClient.get(element.mapCamera());
        if ("client_camera".equals(element.mapRender()) && camera == null) return;

        String logicalId = camera == null ? IMPLICIT_CAMERA : camera.qualifiedId();
        int size = effective(element.mapResolution(), camera == null ? 96 : camera.resolution(), 16, 192);
        int radius = effective(element.mapRadius(), camera == null ? 48 : camera.radius(), 8, 96);
        int interval = effective(element.mapUpdateTicks(), camera == null ? 5 : camera.updateTicks(), 1, 40);
        TextureState state = STATES.computeIfAbsent(logicalId, ignored -> new TextureState());
        ResourceLocation textureId = textureId(logicalId);
        ensureTexture(client, state, textureId, size);

        int centerX = floor(client.player.getX());
        int centerZ = floor(client.player.getZ());
        long now = level.getGameTime();
        boolean changed = level != state.level || centerX != state.centerX || centerZ != state.centerZ
                || radius != state.radius || size != state.size || now - state.lastUpdate >= interval;
        if (changed) {
            rebuild(client, level, state, centerX, centerZ, radius);
            state.level = level;
            state.centerX = centerX;
            state.centerZ = centerZ;
            state.radius = radius;
            state.size = size;
            state.lastUpdate = now;
        }

        graphics.blit(textureId, x, y, Math.max(1, element.w()), Math.max(1, element.h()),
                0, 0, size, size, size, size);
    }

    public static void clear() {
        synchronized (STATES) {
            Minecraft client = Minecraft.getInstance();
            for (TextureState state : STATES.values()) {
                if (state.texture != null) state.texture.close();
            }
            STATES.clear();
        }
    }

    private static void ensureTexture(Minecraft client, TextureState state,
                                      ResourceLocation textureId, int size) {
        if (state.texture != null && state.size == size) return;
        if (state.texture != null) state.texture.close();
        state.texture = new DynamicTexture(size, size, false);
        state.image = state.texture.getPixels();
        state.size = size;
        state.level = null;
        state.lastUpdate = Long.MIN_VALUE;
        client.getTextureManager().register(textureId, state.texture);
    }

    private static void rebuild(Minecraft client, ClientLevel level, TextureState state,
                                int centerX, int centerZ, int radius) {
        if (state.image == null) return;
        Map<Long, Integer> heights = new HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int pixelY = 0; pixelY < state.size; pixelY++) {
            for (int pixelX = 0; pixelX < state.size; pixelX++) {
                int worldX = centerX + (int) Math.floor(((pixelX + 0.5) / state.size - 0.5) * radius * 2);
                int worldZ = centerZ + (int) Math.floor(((pixelY + 0.5) / state.size - 0.5) * radius * 2);
                int surfaceY = surfaceY(level, worldX, worldZ);
                heights.putIfAbsent(key(worldX, worldZ), surfaceY);
                pos.set(worldX, surfaceY, worldZ);

                BlockState block = level.getBlockState(pos);
                int color = client.getBlockColors().getColor(block, level, pos, 0);
                if (color == -1) {
                    MapColor mapColor = block.getMapColor(level, pos);
                    color = mapColor == MapColor.NONE
                            ? 0x30343A : mapColor.calculateRGBColor(MapColor.Brightness.NORMAL) & 0xFFFFFF;
                }
                if (block.isAir()) color = 0x30343A;
                int neighbourY = heights.computeIfAbsent(key(worldX, worldZ - 1),
                        ignored -> surfaceY(level, worldX, worldZ - 1));
                color = shade(color, clamp(surfaceY - neighbourY, -2, 2));
                state.image.setPixelRGBA(pixelX, pixelY, color | 0xFF000000);
            }
        }
        state.texture.upload();
    }

    private static ResourceLocation textureId(String logicalId) {
        String safe = logicalId.replace(':', '_');
        return ResourceLocation.fromNamespaceAndPath("lua_loader", "camera/" + safe);
    }

    private static int surfaceY(ClientLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
    }

    private static int shade(int color, int step) {
        double factor = step > 0 ? 1.08 : step < 0 ? 0.88 : 1.0;
        int red = clamp((int) (((color >> 16) & 0xFF) * factor), 0, 255);
        int green = clamp((int) (((color >> 8) & 0xFF) * factor), 0, 255);
        int blue = clamp((int) ((color & 0xFF) * factor), 0, 255);
        return (red << 16) | (green << 8) | blue;
    }

    private static int effective(int requested, int fallback, int minimum, int maximum) {
        return clamp(requested <= 0 ? fallback : requested, minimum, maximum);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static final class TextureState {
        private DynamicTexture texture;
        private NativeImage image;
        private ClientLevel level;
        private int size;
        private int centerX;
        private int centerZ;
        private int radius;
        private long lastUpdate = Long.MIN_VALUE;
    }
}

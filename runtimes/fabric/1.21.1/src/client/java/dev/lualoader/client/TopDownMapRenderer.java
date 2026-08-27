package dev.lualoader.client;

import dev.lualoader.camera.CameraProtocol;
import dev.lualoader.ui.ScreenModel;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Captura aérea de baixa resolução para o elemento de mapa.
 *
 * <p>Não cria uma segunda passagem do WorldRenderer. Em vez disso, rasteriza a superfície dos chunks
 * que o cliente já recebeu numa NativeImage pequena e faz upload apenas quando o jogador se move ou
 * expira o intervalo da câmera. O resultado visual é de uma câmera ortográfica, sem levar tipos
 * client-side para o core.
 */
public final class TopDownMapRenderer {
    private static final String IMPLICIT_CAMERA = "__element_topdown";
    private static final Map<String, TextureState> STATES = new LinkedHashMap<>();

    private TopDownMapRenderer() {
    }

    public static void draw(DrawContext context, ScreenModel.Element element, int x, int y) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        if (world == null || client.player == null) return;

        CameraProtocol.Camera camera = CameraClient.get(element.mapCamera());
        if (element.mapRender().equals("client_camera") && camera == null) return;

        String logicalId = camera == null ? IMPLICIT_CAMERA : camera.qualifiedId();
        int size = effective(element.mapResolution(), camera == null ? 96 : camera.resolution(), 16, 192);
        int radius = effective(element.mapRadius(), camera == null ? 48 : camera.radius(), 8, 96);
        int interval = effective(element.mapUpdateTicks(), camera == null ? 5 : camera.updateTicks(), 1, 40);
        TextureState state = STATES.computeIfAbsent(logicalId, ignored -> new TextureState());
        Identifier textureId = textureId(logicalId);
        ensureTexture(client, state, textureId, size);

        int centerX = floor(client.player.getX());
        int centerZ = floor(client.player.getZ());
        long now = world.getTime();
        boolean changed = world != state.world || centerX != state.centerX || centerZ != state.centerZ
                || radius != state.radius || size != state.size || now - state.lastUpdate >= interval;
        if (changed) {
            rebuild(client, world, state, centerX, centerZ, radius);
            state.world = world;
            state.centerX = centerX;
            state.centerZ = centerZ;
            state.radius = radius;
            state.size = size;
            state.lastUpdate = now;
        }

        context.drawTexture(textureId, x, y, 0, 0,
                Math.max(1, element.w()), Math.max(1, element.h()), size, size);
    }

    public static void clear() {
        synchronized (STATES) {
            for (TextureState state : STATES.values()) {
                if (state.texture != null) state.texture.close();
            }
            STATES.clear();
        }
    }

    private static void ensureTexture(MinecraftClient client, TextureState state,
                                      Identifier textureId, int size) {
        if (state.texture != null && state.size == size) return;
        if (state.texture != null) state.texture.close();
        state.texture = new NativeImageBackedTexture(size, size, false);
        state.image = state.texture.getImage();
        state.size = size;
        state.world = null;
        state.lastUpdate = Long.MIN_VALUE;
        client.getTextureManager().registerTexture(textureId, state.texture);
    }

    private static void rebuild(MinecraftClient client, ClientWorld world, TextureState state,
                                int centerX, int centerZ, int radius) {
        if (state.image == null) return;
        Map<Long, Integer> heights = new HashMap<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int pixelY = 0; pixelY < state.size; pixelY++) {
            for (int pixelX = 0; pixelX < state.size; pixelX++) {
                int worldX = centerX + (int) Math.floor(((pixelX + 0.5) / state.size - 0.5) * radius * 2);
                int worldZ = centerZ + (int) Math.floor(((pixelY + 0.5) / state.size - 0.5) * radius * 2);
                int surfaceY = surfaceY(world, worldX, worldZ);
                heights.putIfAbsent(key(worldX, worldZ), surfaceY);
                pos.set(worldX, surfaceY, worldZ);

                BlockState block = world.getBlockState(pos);
                int color = client.getBlockColors().getColor(block, world, pos, 0);
                if (color == -1) color = block.getBlock().getDefaultMapColor().color;
                if (block.isAir()) color = 0x30343A;
                int neighbourY = heights.computeIfAbsent(key(worldX, worldZ - 1),
                        ignored -> surfaceY(world, worldX, worldZ - 1));
                color = shade(color, Math.max(-2, Math.min(2, surfaceY - neighbourY)));
                state.image.setColor(pixelX, pixelY, color | 0xFF000000);
            }
        }
        state.texture.upload();
    }

    private static Identifier textureId(String logicalId) {
        String safe = logicalId.replace(':', '_');
        return Identifier.of("lua_loader", "camera/" + safe);
    }

    private static int surfaceY(ClientWorld world, int x, int z) {
        return world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
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
        private NativeImageBackedTexture texture;
        private NativeImage image;
        private ClientWorld world;
        private int size;
        private int centerX;
        private int centerZ;
        private int radius;
        private long lastUpdate = Long.MIN_VALUE;
    }
}

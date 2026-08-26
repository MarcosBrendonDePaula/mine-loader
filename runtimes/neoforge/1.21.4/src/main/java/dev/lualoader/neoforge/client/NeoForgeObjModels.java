package dev.lualoader.neoforge.client;

import dev.lualoader.neoforge.NeoForgeLuaLoader;
import net.neoforged.bus.api.IEventBus;

/**
 * Ponto de integração de modelos OBJ do runtime NeoForge.
 *
 * <p>A API de geometria customizada mudou no NeoForge/Minecraft 1.21.4. O bridge principal pode
 * compilar e carregar mods sem ela, mas a capacidade OBJ fica explicitamente desativada neste
 * primeiro porte até ser reimplementada sobre o sistema de modelos atual. Isso evita fingir
 * paridade visual enquanto a implementação ainda não foi validada.
 */
public final class NeoForgeObjModels {
    private NeoForgeObjModels() {
    }

    public static void install(IEventBus modBus) {
        NeoForgeLuaLoader.LOGGER.warn(
                "Suporte a modelos OBJ está temporariamente desativado no runtime NeoForge 1.21.4");
    }
}

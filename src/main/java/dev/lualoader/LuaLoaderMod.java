package dev.lualoader;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.minecraft.BlockRegistrar;
import dev.lualoader.resources.GeneratedResourcePackProvider;
import dev.lualoader.resources.ResourcePackAssembler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class LuaLoaderMod implements ModInitializer {
    public static final String MOD_ID = "lua_loader";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static List<ModLoader.LoadedMod> loadedMods = List.of();
    private static BlockRegistrar blockRegistrar;
    private static LuaRuntime luaRuntime;
    private static ResourcePackAssembler resourcePackAssembler;

    @Override
    public void onInitialize() {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        Path modsDirectory = gameDirectory.resolve("mods-lua");
        Path generatedPack = gameDirectory.resolve("lua-loader/generated-pack");
        Path resourceCache = gameDirectory.resolve("lua-loader/cache");
        ModLoader manifestLoader = new ModLoader(LOGGER);
        blockRegistrar = new BlockRegistrar(LOGGER);
        luaRuntime = new LuaRuntime(LOGGER);

        try {
            resourcePackAssembler = new ResourcePackAssembler(LOGGER, resourceCache);
            loadedMods = manifestLoader.discover(modsDirectory);
            resourcePackAssembler.assemble(loadedMods, generatedPack);
            GeneratedResourcePackProvider.setRoot(generatedPack);

            for (ModLoader.LoadedMod mod : loadedMods) {
                blockRegistrar.register(mod.manifest());
            }
            for (ModLoader.LoadedMod mod : loadedMods) {
                try {
                    luaRuntime.load(mod);
                } catch (IOException | RuntimeException error) {
                    LOGGER.error("Falha ao carregar Lua do mod {}", mod.manifest().id, error);
                }
            }
            LOGGER.info("Minecraft Lua Loader inicializado: {} mod(s), {} bloco(s)",
                    loadedMods.size(), blockRegistrar.registeredBlocks().size());
            luaRuntime.triggerAll("loader_ready", null, null);
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Não foi possível inicializar os mods Lua", error);
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LuaLoaderCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> luaRuntime.triggerAll("server_started", null, server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> luaRuntime.triggerAll("server_stopped", null, server));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                luaRuntime.triggerAll("player_joined", handler.player, server));
        ServerTickEvents.END_SERVER_TICK.register(server -> luaRuntime.triggerAll("tick", null, server));
    }

    public static List<ModLoader.LoadedMod> loadedMods() {
        return loadedMods;
    }

    public static BlockRegistrar blockRegistrar() {
        return blockRegistrar;
    }

    public static LuaRuntime luaRuntime() {
        return luaRuntime;
    }

    public static Path generatedPackRoot() {
        return FabricLoader.getInstance().getGameDir().resolve("lua-loader/generated-pack");
    }
}

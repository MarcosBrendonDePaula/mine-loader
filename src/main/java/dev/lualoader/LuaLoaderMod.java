package dev.lualoader;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ManifestDiagnostics;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.minecraft.BlockInteractionEvents;
import dev.lualoader.minecraft.BlockRegistrar;
import dev.lualoader.minecraft.ContentRegistrar;
import dev.lualoader.minecraft.FabricGameBridge;
import dev.lualoader.minecraft.FabricPlayerHandle;
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
    private static FabricGameBridge gameBridge;
    private static ContentRegistrar contentRegistrar;

    @Override
    public void onInitialize() {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        Path modsDirectory = gameDirectory.resolve("mods-lua");
        Path generatedPack = gameDirectory.resolve("lua-loader/generated-pack");
        Path resourceCache = gameDirectory.resolve("lua-loader/cache");
        ModLoader manifestLoader = new ModLoader(LOGGER, resourceCache.resolve("imports"));
        blockRegistrar = new BlockRegistrar(LOGGER);
        contentRegistrar = new ContentRegistrar(LOGGER);
        luaRuntime = new LuaRuntime(LOGGER, resourceCache.resolve("scripts"),
                gameDirectory.resolve("lua-loader/state"));
        gameBridge = new FabricGameBridge(blockRegistrar);
        luaRuntime.attach(gameBridge);

        try {
            resourcePackAssembler = new ResourcePackAssembler(LOGGER, resourceCache);
            loadedMods = manifestLoader.discover(modsDirectory);
            resourcePackAssembler.assemble(loadedMods, generatedPack);
            GeneratedResourcePackProvider.setRoot(generatedPack);

            ManifestDiagnostics diagnostics = new ManifestDiagnostics(LOGGER);
            for (ModLoader.LoadedMod mod : loadedMods) {
                diagnostics.report(mod.manifest());
                blockRegistrar.register(mod.manifest());
                contentRegistrar.registerItems(mod.manifest());
            }
            // O tipo de dados precisa conhecer todos os blocos, entao vem depois do registro deles.
            dev.lualoader.minecraft.BlockEntityRegistrar.register(LOGGER, blockRegistrar.dataBlocks());

            // A aba criativa so pode ser montada depois que blocos e itens existem no registry.
            for (ModLoader.LoadedMod mod : loadedMods) {
                contentRegistrar.registerCreativeTab(mod.manifest(),
                        blockRegistrar.blockItems(mod.manifest().id));
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
            luaRuntime.triggerAll("loader_ready", null);
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Não foi possível inicializar os mods Lua", error);
        }

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LuaLoaderCommands.register(dispatcher));
        new BlockInteractionEvents(luaRuntime, blockRegistrar).register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            gameBridge.setServer(server);
            luaRuntime.triggerAll("server_started", null);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            luaRuntime.triggerAll("server_stopped", null);
            // O estado e gravado depois do evento, para o mod poder ajusta-lo antes de sair.
            luaRuntime.saveAllStates();
            gameBridge.setServer(null);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                luaRuntime.triggerAll("player_joined", new FabricPlayerHandle(handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                luaRuntime.triggerAll("player_left", new FabricPlayerHandle(handler.player)));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // O agendador avanca antes do evento, para uma tarefa marcada neste tick rodar aqui.
            luaRuntime.advanceScheduler();
            luaRuntime.triggerAll("tick", null);
        });
    }

    public static List<ModLoader.LoadedMod> loadedMods() {
        return loadedMods;
    }

    public static ContentRegistrar contentRegistrar() {
        return contentRegistrar;
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

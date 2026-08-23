package dev.lualoader.neoforge;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Ponto de entrada do adaptador NeoForge.
 *
 * <p>Este arquivo existe para provar uma afirmação que o projeto faz desde o início: o núcleo não
 * conhece plataforma. Nada em {@code core} muda para rodar aqui — o mesmo manifesto, o mesmo runtime
 * Lua, os mesmos contratos. O que muda é só quem responde por eles.
 *
 * <p>O adaptador Fabric continua sendo o completo. Este cobre o caminho central — descobrir mods,
 * carregar scripts, executar eventos e alcançar o mundo — e deixa claro no log o que ainda não
 * implementa, em vez de falhar em silêncio.
 */
@Mod(NeoForgeLuaLoader.ID)
public class NeoForgeLuaLoader {
    public static final String ID = "lua_loader";
    public static final Logger LOGGER = LoggerFactory.getLogger("lua_loader/neoforge");

    private static LuaRuntime runtime;
    private static NeoForgeGameBridge bridge;
    private static List<ModLoader.LoadedMod> loadedMods = List.of();

    public NeoForgeLuaLoader(IEventBus modBus) {
        // A carga acontece em ServerAboutToStart, e nao em ServerStarted: a arvore de comandos e
        // montada entre os dois, e um mod carregado depois dela teria o comando declarado e nao
        // publicado -- que foi o que aconteceu na primeira tentativa.
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onServerStopping);


        LOGGER.info("Adaptador NeoForge do Lua Loader carregado");
    }

    /** Runtime em uso, ou {@code null} antes de o servidor iniciar. */
    public static LuaRuntime luaRuntime() {
        return runtime;
    }

    /** Mods descobertos na ultima carga. */
    public static List<ModLoader.LoadedMod> loadedMods() {
        return loadedMods;
    }

    private static void onServerAboutToStart(
            net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) {
        Path gameDirectory = event.getServer().getServerDirectory();
        Path modsDirectory = gameDirectory.resolve("mods-lua");
        Path state = gameDirectory.resolve("lua-loader").resolve("state");
        Path cache = gameDirectory.resolve("lua-loader").resolve("cache");

        bridge = new NeoForgeGameBridge();
        bridge.setServer(event.getServer());

        runtime = new LuaRuntime(LOGGER, cache, state);
        runtime.attach(bridge);

        try {
            if (!Files.isDirectory(modsDirectory)) {
                Files.createDirectories(modsDirectory);
                LOGGER.info("Pasta de mods criada em {}", modsDirectory);
                return;
            }

            List<ModLoader.LoadedMod> mods =
                    new ModLoader(LOGGER).discover(modsDirectory);
            loadedMods = List.copyOf(mods);

            int loaded = 0;
            for (ModLoader.LoadedMod mod : mods) {
                try {
                    runtime.load(mod);
                    loaded++;
                } catch (IOException | RuntimeException error) {
                    // Um mod quebrado nao pode impedir os outros de carregar: e a mesma regra do
                    // adaptador Fabric, e vem do nucleo, nao da plataforma.
                    LOGGER.error("Falha ao carregar o mod {}: {}",
                            mod.manifest().id, error.getMessage());
                }
            }
            LOGGER.info("Lua Loader no NeoForge: {} de {} mod(s) carregado(s)", loaded, mods.size());

            // Os comandos sao registrados aqui, e nao por RegisterCommandsEvent: aquele evento
            // acontece durante a carga dos datapacks, antes de existir runtime para consultar --
            // um mod declarava o comando e nada era publicado. Registrar direto no dispatcher,
            // depois de carregar, acontece na ordem certa e antes de qualquer jogador entrar.
            NeoForgeCommands.register(event.getServer().getCommands().getDispatcher());
        } catch (IOException error) {
            LOGGER.error("Falha ao descobrir mods em {}: {}", modsDirectory, error.getMessage());
        }
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (runtime != null) {
            runtime.saveAllStates();
            runtime = null;
        }
        bridge = null;
    }
}

package dev.lualoader;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ManifestDiagnostics;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.minecraft.BlockInteractionEvents;
import dev.lualoader.minecraft.BlockRegistrar;
import dev.lualoader.minecraft.ContentRegistrar;
import dev.lualoader.minecraft.EntityRegistrar;
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

    /** As especies declaradas. Publico para o cliente conferir a cobertura. */
    private static EntityRegistrar entityRegistrar;
    private static dev.lualoader.install.ModInstaller modInstaller;
    private static dev.lualoader.install.InstallPolicy installPolicy;

    /** Servidor no ar, necessario para republicar a arvore de comandos apos uma instalacao. */
    private static net.minecraft.server.MinecraftServer currentServer;

    @Override
    public void onInitialize() {
        Path gameDirectory = FabricLoader.getInstance().getGameDir();
        Path modsDirectory = gameDirectory.resolve("mods-lua");
        Path generatedPack = gameDirectory.resolve("lua-loader/generated-pack");
        Path resourceCache = gameDirectory.resolve("lua-loader/cache");
        ModLoader manifestLoader = new ModLoader(LOGGER, resourceCache.resolve("imports"));
        blockRegistrar = new BlockRegistrar(LOGGER);
        contentRegistrar = new ContentRegistrar(LOGGER);
        entityRegistrar = new EntityRegistrar(LOGGER);
        luaRuntime = new LuaRuntime(LOGGER, resourceCache.resolve("scripts"),
                gameDirectory.resolve("lua-loader/state"));
        gameBridge = new FabricGameBridge(blockRegistrar);
        luaRuntime.attach(gameBridge);

        // O instalador precisa da pasta de mods, e a chave de o que ele pode fazer sozinho fica em
        // disco: e uma decisao do servidor, e reiniciar nao pode religar o que alguem desligou.
        installPolicy = new dev.lualoader.install.InstallPolicy(
                LOGGER, gameDirectory.resolve("lua-loader/instalacao.json"));
        modInstaller = new dev.lualoader.install.ModInstaller(LOGGER, modsDirectory);
        luaRuntime.attachInstaller(modInstaller, installPolicy);

        try {
            resourcePackAssembler = new ResourcePackAssembler(LOGGER, resourceCache);
            loadedMods = manifestLoader.discover(modsDirectory);

            // As dependencias declaradas sao buscadas antes de qualquer registro: um mod que
            // depende de outro precisa que o outro exista quando o conteudo for para o jogo.
            var dependencies = new dev.lualoader.install.DependencyInstaller(
                    LOGGER, modInstaller, installPolicy).resolve(loadedMods);
            if (dependencies.changedAnything()) {
                loadedMods = manifestLoader.discover(modsDirectory);
            }
            // A fase de registro vem antes de montar o pacote, e nao depois: o que um script
            // declara entra no manifesto em memoria, e a partir dali precisa passar pelo montador
            // como qualquer outra especie. Montar antes deixava o ovo gerado sem icone -- defeito
            // que nenhum teste de servidor pega, porque o item existe e funciona.
            new dev.lualoader.lua.RegistrationRuntime(LOGGER, resourceCache.resolve("registro"))
                    .runAll(loadedMods);

            resourcePackAssembler.assemble(loadedMods, generatedPack);
            GeneratedResourcePackProvider.setRoot(generatedPack);

            ManifestDiagnostics diagnostics = new ManifestDiagnostics(LOGGER);
            for (ModLoader.LoadedMod mod : loadedMods) {
                diagnostics.report(mod.manifest());
                blockRegistrar.register(mod.manifest());
                contentRegistrar.registerItems(mod.manifest());
            }

            // As especies vem depois do laco, e nao dentro dele: uma pode descender da especie
            // declarada por outro mod, e a ordem de descoberta nao e a ordem de heranca.
            entityRegistrar.registerAll(loadedMods);

            // O nascimento natural vem depois do registro dos tipos, porque precisa deles: um
            // modificador que aponta para um tipo inexistente e descartado quando o bioma e
            // montado, sem erro nenhum.
            dev.lualoader.minecraft.NaturalSpawns.register(LOGGER, entityRegistrar, loadedMods);

            // O tipo de dados precisa conhecer todos os blocos, entao vem depois do registro deles.
            dev.lualoader.minecraft.BlockEntityRegistrar.register(LOGGER, blockRegistrar.dataBlocks());

            // A inflamabilidade tambem: ela e registrada por bloco ja existente, nao por settings.
            for (ModLoader.LoadedMod mod : loadedMods) {
                registerFlammability(mod.manifest());
            }

            // A aba criativa so pode ser montada depois que blocos e itens existem no registry.
            for (ModLoader.LoadedMod mod : loadedMods) {
                java.util.List<net.minecraft.util.Identifier> tabContents =
                        new java.util.ArrayList<>(blockRegistrar.blockItems(mod.manifest().id));
                // Sem o ovo na aba, uma especie declarada so chega ao mundo por comando -- e o
                // criativo e como quase todo mod e experimentado primeiro.
                tabContents.addAll(entityRegistrar.spawnEggs(mod.manifest().id));
                contentRegistrar.registerCreativeTab(mod.manifest(), tabContents);
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

            // A partir daqui o jogo congela os registros. Fechar aqui faz um registro tardio ser
            // recusado com o motivo, em vez de aceito e perdido.
            entityRegistrar.close();
            luaRuntime.triggerAll("loader_ready", null);
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Não foi possível inicializar os mods Lua", error);
        }

        // As cargas precisam existir antes de qualquer envio, e o receptor antes do primeiro clique.
        dev.lualoader.network.ScreenNetwork.registerPayloads();
        dev.lualoader.network.ScreenNetwork.registerServerReceiver();
        dev.lualoader.network.ScreenNetwork.registerClientInfoReceiver();
        dev.lualoader.network.ScreenNetwork.registerClientEventReceiver();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LuaLoaderCommands.register(dispatcher));

        // Um mod instalado com o servidor no ar registra o comando no runtime, e a arvore que o
        // jogo publica ja foi montada. Sem republicar, o comando existe e ninguem consegue digitar.
        luaRuntime.onCommandsChanged(() -> {
            if (currentServer == null) return;
            LuaLoaderCommands.register(currentServer.getCommandManager().getDispatcher());
            // O cliente guarda a propria copia da arvore; sem reenviar, ele recusa o comando antes
            // mesmo de mandar ao servidor.
            for (var player : currentServer.getPlayerManager().getPlayerList()) {
                currentServer.getCommandManager().sendCommandTree(player);
            }
        });
        // O que a especie declara como padrao vale ao nascer, e nao ao registrar. Vem por evento
        // porque o tipo e construido pelo jogo, sem passar pelo loader.
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_LOAD.register(
                (entity, world) -> entityRegistrar.applyDeclaredDefaults(entity));

        // Os eventos de criatura valem para o mundo inteiro, e nao so para o que o loader
        // declarou: e o que permite um mod de combate reagir ao zumbi do jogo.
        new dev.lualoader.minecraft.EntityEvents(luaRuntime).register();

        new BlockInteractionEvents(luaRuntime, blockRegistrar).register();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            gameBridge.setServer(server);
            luaRuntime.triggerAll("server_started", null);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            luaRuntime.triggerAll("server_stopped", null);
            // O estado e gravado depois do evento, para o mod poder ajusta-lo antes de sair.
            luaRuntime.saveAllStates();
            gameBridge.setServer(null);
            currentServer = null;
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

    /**
     * Ensina o fogo do jogo a alcancar e consumir os blocos declarados.
     *
     * <p>Nao entra em {@code BlockSettingsFactory} porque nao e uma propriedade do bloco: o fogo
     * guarda dois mapas proprios, e o bloco precisa ja existir no registro para entrar neles.
     *
     * <p>A ordem dos dois numeros e a mesma da chamada equivalente no NeoForge -- propagacao
     * primeiro, inflamabilidade depois. Troca-los produziria um bloco que se comporta ao contrario
     * numa plataforma e certo na outra, que e o tipo de divergencia mais dificil de perceber.
     */
    private static void registerFlammability(dev.lualoader.manifest.ModManifest manifest) {
        for (var entry : dev.lualoader.content.Flammability.declaredIn(manifest)) {
            var id = net.minecraft.util.Identifier.tryParse(entry.blockId());
            if (id == null || !net.minecraft.registry.Registries.BLOCK.containsId(id)) continue;

            net.fabricmc.fabric.api.registry.FlammableBlockRegistry.getDefaultInstance()
                    .add(net.minecraft.registry.Registries.BLOCK.get(id),
                            entry.burnSpread(), entry.flammability());
        }
    }

    public static List<ModLoader.LoadedMod> loadedMods() {
        return loadedMods;
    }

    /** Adaptador de plataforma em uso, necessario para publicar a dimensao do evento. */
    public static FabricGameBridge gameBridge() {
        return gameBridge;
    }

    /** A chave que diz o que o loader pode instalar sozinho. */
    public static dev.lualoader.install.InstallPolicy installPolicy() {
        return installPolicy;
    }

    /** O instalador de mods, usado pelos comandos. */
    public static dev.lualoader.install.ModInstaller modInstaller() {
        return modInstaller;
    }

    public static ContentRegistrar contentRegistrar() {
        return contentRegistrar;
    }

    public static EntityRegistrar entityRegistrar() {
        return entityRegistrar;
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

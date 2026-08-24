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
    private static NeoForgeContentRegistrar content;

    public NeoForgeLuaLoader(IEventBus modBus) {
        // A descoberta acontece aqui, e nao quando o servidor sobe: o registro do jogo fecha
        // durante a inicializacao, e um bloco declarado depois disso simplesmente nao existe. O
        // runtime Lua vem bem depois, e nao precisa existir para o conteudo estar registrado.
        content = new NeoForgeContentRegistrar(LOGGER, modBus);
        registerDeclaredContent();

        // O pack gerado precisa existir antes de o jogo montar a lista de recursos. No Fabric isso
        // exige um mixin no gerenciador de packs; aqui ha um evento proprio para acrescentar
        // fontes, e e por ele que o conteudo declarado ganha textura, modelo e nome traduzido.
        modBus.addListener(NeoForgeLuaLoader::onAddPackFinders);

        // O inventario declarado so existe para a automacao quando publicado como capability: e
        // por ela que funil e tubo procuram, e sem isto o bloco tem itens que nenhuma maquina ve.
        modBus.addListener((net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) -> {
            if (!NeoForgeBlockEntities.isRegistered()) return;
            event.registerBlockEntity(
                    net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                    NeoForgeBlockEntities.type(),
                    (entity, side) -> entity.handler(side));
        });

        // O canal de telas precisa existir antes de qualquer jogador entrar: e por ele que o
        // cliente se anuncia, e e a presenca dele que supports_screens responde.
        modBus.addListener((net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) ->
                dev.lualoader.neoforge.network.NeoForgeScreenNetwork.register(event, LOGGER));

        // A carga acontece em ServerAboutToStart, e nao em ServerStarted: a arvore de comandos e
        // montada entre os dois, e um mod carregado depois dela teria o comando declarado e nao
        // publicado -- que foi o que aconteceu na primeira tentativa.
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onServerStopping);

        // As interacoes sao ligadas agora, mas leem o runtime por fornecedor: ele so nasce quando o
        // servidor sobe, e ate la os ouvintes simplesmente nao tem o que disparar. Sem isto o
        // conteudo declarado aparece no jogo e nao reage a nada -- clicar nao faz nada.
        new NeoForgeInteractionEvents(() -> runtime, () -> bridge, content)
                .register(NeoForge.EVENT_BUS);


        // O lado cliente so existe no cliente: no servidor dedicado as classes de renderizacao
        // nem sao carregadas, e nomea-las fora deste guarda derrubaria o servidor na inicializacao.
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            dev.lualoader.neoforge.client.NeoForgeLuaLoaderClient.install(modBus);
            dev.lualoader.neoforge.client.NeoForgeGameScreenOverlay.register();
        }

        LOGGER.info("Adaptador NeoForge do Lua Loader carregado");
    }

    /**
     * Descobre os mods e registra o conteudo declarado nos manifestos.
     *
     * <p>Le a pasta de mods a partir do diretorio do jogo, que nesta altura ja e conhecido, ainda
     * que nenhum servidor exista. Falhar aqui nao derruba o jogo: um manifesto quebrado vira erro
     * no log e os outros mods seguem.
     */
    private static void registerDeclaredContent() {
        Path modsDirectory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("mods-lua");

        try {
            if (!Files.isDirectory(modsDirectory)) {
                Files.createDirectories(modsDirectory);
                LOGGER.info("Pasta de mods criada em {}", modsDirectory);
                return;
            }

            loadedMods = List.copyOf(new ModLoader(LOGGER).discover(modsDirectory));
            for (ModLoader.LoadedMod mod : loadedMods) {
                try {
                    content.declare(mod.manifest());
                } catch (RuntimeException error) {
                    LOGGER.error("Falha ao registrar o conteudo de {}: {}",
                            mod.manifest().id, error.getMessage());
                }
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Falha ao descobrir mods em {}: {}", modsDirectory, error.getMessage());
        }
    }

    /**
     * Monta o resource pack com as texturas, modelos e traducoes do conteudo declarado.
     *
     * <p>Sem ele, um bloco registrado existe no jogo e aparece sem textura e com o nome cru do
     * identificador: funcional e invisivel. O montador vem do nucleo e e o mesmo do adaptador
     * Fabric -- o que muda e so como cada plataforma acrescenta a fonte de recursos.
     */
    private static void onAddPackFinders(
            net.neoforged.neoforge.event.AddPackFindersEvent event) {
        if (event.getPackType() != net.minecraft.server.packs.PackType.CLIENT_RESOURCES) return;
        if (loadedMods.isEmpty()) return;

        Path gameDirectory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        Path generated = gameDirectory.resolve("lua-loader").resolve("generated-pack");
        Path cache = gameDirectory.resolve("lua-loader").resolve("cache");

        try {
            new dev.lualoader.resources.ResourcePackAssembler(LOGGER, cache)
                    .assemble(loadedMods, generated);

            var local = new net.minecraft.server.packs.PackLocationInfo(
                    "lua_loader_generated",
                    net.minecraft.network.chat.Component.literal("Recursos dos mods Lua"),
                    net.minecraft.server.packs.repository.PackSource.BUILT_IN,
                    java.util.Optional.empty());

            // O fornecedor tem dois metodos e por isso nao e uma lambda: um abre o pack sozinho e
            // o outro abre com os metadados ja lidos. Os dois devolvem a mesma pasta gerada.
            var recursos = new net.minecraft.server.packs.repository.Pack.ResourcesSupplier() {
                @Override
                public net.minecraft.server.packs.PackResources openPrimary(
                        net.minecraft.server.packs.PackLocationInfo info) {
                    return new net.minecraft.server.packs.PathPackResources(info, generated);
                }

                @Override
                public net.minecraft.server.packs.PackResources openFull(
                        net.minecraft.server.packs.PackLocationInfo info,
                        net.minecraft.server.packs.repository.Pack.Metadata metadata) {
                    return new net.minecraft.server.packs.PathPackResources(info, generated);
                }
            };

            // Ligado por padrao e no topo: o pack existe para o conteudo declarado aparecer, e
            // deixa-lo desligado ou abaixo dos outros anularia a razao de ele existir.
            var selecao = new net.minecraft.server.packs.PackSelectionConfig(
                    true, net.minecraft.server.packs.repository.Pack.Position.TOP, false);

            var pack = net.minecraft.server.packs.repository.Pack.readMetaAndCreate(
                    local, recursos,
                    net.minecraft.server.packs.PackType.CLIENT_RESOURCES, selecao);

            if (pack == null) {
                LOGGER.error("O resource pack gerado nao pode ser lido; conteudo ficara sem textura");
                return;
            }
            event.addRepositorySource(consumer -> consumer.accept(pack));

            LOGGER.info("Resource pack dos mods Lua montado em {}", generated);
        } catch (IOException | RuntimeException error) {
            // Sem o pack o jogo sobe: os blocos ficam sem textura, o que e ruim mas jogavel.
            LOGGER.error("Falha ao montar o resource pack: {}", error.getMessage());
        }
    }

    /** Blocos registrados a partir dos manifestos. */
    public static List<String> registeredBlocks() {
        return content == null ? List.of() : content.registeredBlocks();
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
        Path state = gameDirectory.resolve("lua-loader").resolve("state");
        Path cache = gameDirectory.resolve("lua-loader").resolve("cache");

        bridge = new NeoForgeGameBridge();
        bridge.setServer(event.getServer());

        runtime = new LuaRuntime(LOGGER, cache, state);
        runtime.attach(bridge);

        // Os mods ja foram descobertos no construtor, quando o conteudo precisou ser registrado.
        // Redescobrir aqui leria o disco de novo e poderia divergir do que esta no jogo.
        int loaded = 0;
        for (ModLoader.LoadedMod mod : loadedMods) {
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
        LOGGER.info("Lua Loader no NeoForge: {} de {} mod(s) carregado(s)", loaded, loadedMods.size());

        // Os comandos sao registrados aqui, e nao por RegisterCommandsEvent: aquele evento
        // acontece durante a carga dos datapacks, antes de existir runtime para consultar --
        // um mod declarava o comando e nada era publicado. Registrar direto no dispatcher,
        // depois de carregar, acontece na ordem certa e antes de qualquer jogador entrar.
        NeoForgeCommands.register(event.getServer().getCommands().getDispatcher());
        }

    private static void onServerStopping(ServerStoppingEvent event) {
        if (runtime != null) {
            runtime.saveAllStates();
            runtime = null;
        }
        bridge = null;
    }
}

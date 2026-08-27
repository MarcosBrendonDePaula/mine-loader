package dev.lualoader.neoforge;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.RuntimeContract;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
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

    /** As especies declaradas. Publico para o cliente conferir a cobertura. */
    private static NeoForgeEntityRegistrar entities;
    private static dev.lualoader.install.ModInstaller modInstaller;
    private static dev.lualoader.install.InstallPolicy installPolicy;

    /** Servidor no ar, necessario para republicar a arvore de comandos apos uma instalacao. */
    private static net.minecraft.server.MinecraftServer currentServer;

    public NeoForgeLuaLoader(IEventBus modBus) {
        // A descoberta acontece aqui, e nao quando o servidor sobe: o registro do jogo fecha
        // durante a inicializacao, e um bloco declarado depois disso simplesmente nao existe. O
        // runtime Lua vem bem depois, e nao precisa existir para o conteudo estar registrado.
        content = new NeoForgeContentRegistrar(LOGGER, modBus);
        entities = new NeoForgeEntityRegistrar(LOGGER, modBus);
        registerDeclaredContent();

        // O pack gerado precisa existir antes de o jogo montar a lista de recursos. No Fabric isso
        // exige um mixin no gerenciador de packs; aqui ha um evento proprio para acrescentar
        // fontes, e e por ele que o conteudo declarado ganha textura, modelo e nome traduzido.
        modBus.addListener(NeoForgeLuaLoader::onAddPackFinders);

        // A inflamabilidade e registrada depois que os blocos existem, e nao junto das settings: o
        // fogo guarda dois mapas proprios, indexados pelo bloco ja registrado.
        modBus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent event) ->
                event.enqueueWork(NeoForgeLuaLoader::registerFlammability));

        // O posicionamento do nascimento natural: a outra metade -- em que biomas a especie entra
        // como candidata -- vem do modificador escrito no data pack. Esquecer qualquer uma das duas
        // da o mesmo sintoma: nada nasce, e nenhum log reclama.
        modBus.addListener((RegisterSpawnPlacementsEvent event) ->
                NeoForgeNaturalSpawns.register(event, LOGGER, entities, loadedMods));

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

        // Os eventos globais -- os que nao sao de bloco, item ou menu -- passam todos por
        // triggerAll. Enquanto este bloco nao existiu, um mod que reagia a entrada de jogador
        // simplesmente nao reagia no NeoForge, sem erro e sem aviso, e a matriz de
        // compatibilidade afirmava o contrario.
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onServerStarted);
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onPlayerJoined);
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onPlayerLeft);
        NeoForge.EVENT_BUS.addListener(NeoForgeLuaLoader::onServerTick);

        // As interacoes sao ligadas agora, mas leem o runtime por fornecedor: ele so nasce quando o
        // servidor sobe, e ate la os ouvintes simplesmente nao tem o que disparar. Sem isto o
        // conteudo declarado aparece no jogo e nao reage a nada -- clicar nao faz nada.
        new NeoForgeInteractionEvents(() -> runtime, () -> bridge, content)
                .register(NeoForge.EVENT_BUS);

        // Os eventos de criatura valem para o mundo inteiro, e nao so para o que o loader
        // declarou: e o que permite um mod de combate reagir ao zumbi do jogo.
        new NeoForgeEntityEvents(() -> runtime).register(NeoForge.EVENT_BUS);

        // O que a especie declara como padrao vale ao nascer, e nao ao registrar: o tipo e
        // construido pelo jogo, sem passar pelo loader.
        NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) ->
                        entities.applyDeclaredDefaults(event.getEntity()));


        // O lado cliente so existe no cliente: no servidor dedicado as classes de renderizacao
        // nem sao carregadas, e nomea-las fora deste guarda derrubaria o servidor na inicializacao.
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            dev.lualoader.neoforge.client.NeoForgeLuaLoaderClient.install(modBus);
            // Sem desenhista a especie existe e nao aparece: o log fica verde e so quem
            // esta olhando percebe que nao ha nada onde o servidor diz haver um bicho.
            dev.lualoader.neoforge.client.NeoForgeEntityRenderers.install(modBus);

            // Um bloco com malha declarada aparece como o cubo de reserva ate este leitor existir,
            // e o mesmo manifesto desenharia diferente em cada plataforma -- que e o que a matriz
            // de compatibilidade existe para nao deixar acontecer em silencio.
            dev.lualoader.neoforge.client.NeoForgeObjModels.install(modBus);
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

        // O instalador e a chave nascem antes de qualquer coisa, e fora do try: uma pasta de mods
        // vazia ainda precisa de instalador -- e justamente nela que a primeira instalacao cai.
        Path gameDirectory = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
        installPolicy = new dev.lualoader.install.InstallPolicy(
                LOGGER, gameDirectory.resolve("lua-loader").resolve("instalacao.json"));
        modInstaller = new dev.lualoader.install.ModInstaller(LOGGER, modsDirectory);

        try {
            if (!Files.isDirectory(modsDirectory)) {
                Files.createDirectories(modsDirectory);
                LOGGER.info("Pasta de mods criada em {}", modsDirectory);
                return;
            }

            loadedMods = List.copyOf(new ModLoader(LOGGER, null, RuntimeContract.forRuntime("neoforge", "1.21.1")).discover(modsDirectory));

            var dependencies = new dev.lualoader.install.DependencyInstaller(
                    LOGGER, modInstaller, installPolicy).resolve(loadedMods);
            if (dependencies.changedAnything()) {
                loadedMods = List.copyOf(new ModLoader(LOGGER, null, RuntimeContract.forRuntime("neoforge", "1.21.1")).discover(modsDirectory));
            }
            for (ModLoader.LoadedMod mod : loadedMods) {
                try {
                    content.declare(mod.manifest());
                    entities.declare(mod.manifest());
                } catch (RuntimeException error) {
                    LOGGER.error("Falha ao registrar o conteudo de {}: {}",
                            mod.manifest().id, error.getMessage());
                }
            }

            // A fase de registro roda aqui, e nao com o servidor: o RegisterEvent ainda nao
            // disparou, entao ha o que registrar. E o momento que faltava a esta plataforma, e o
            // que faz register.entity valer nas duas em vez de so no Fabric.
            new dev.lualoader.lua.RegistrationRuntime(
                    LOGGER, gameDirectory.resolve("lua-loader/cache/registro")).runAll(loadedMods);
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
     *
     * <p><b>Os dois tipos de pack entram por aqui.</b> O montador escreve textura e modelo em
     * {@code assets/}, mas tambem receita em {@code data/<ns>/recipe/} e loot em
     * {@code data/<ns>/loot_table/blocks/}. Enquanto este metodo aceitava so
     * {@code CLIENT_RESOURCES}, a metade de dados nunca era montada: uma receita declarada no
     * manifesto simplesmente nao existia no servidor NeoForge, e o bloco nao largava nada ao
     * quebrar. No Fabric o mixin cobre os dois desde o inicio, o que escondeu a diferenca.
     */
    private static void onAddPackFinders(
            net.neoforged.neoforge.event.AddPackFindersEvent event) {
        net.minecraft.server.packs.PackType tipo = event.getPackType();
        if (tipo != net.minecraft.server.packs.PackType.CLIENT_RESOURCES
                && tipo != net.minecraft.server.packs.PackType.SERVER_DATA) return;
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
                    local, recursos, tipo, selecao);

            if (pack == null) {
                LOGGER.error("O pack gerado ({}) nao pode ser lido; conteudo ficara incompleto", tipo);
                return;
            }
            event.addRepositorySource(consumer -> consumer.accept(pack));

            LOGGER.info("Pack dos mods Lua montado em {} para {}", generated, tipo);
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

    /**
     * Ensina o fogo do jogo a alcancar e consumir os blocos declarados.
     *
     * <p>Nao entra em {@code settingsOf} porque nao e uma propriedade do bloco: o fogo guarda dois
     * mapas proprios, e o bloco precisa ja existir no registro para entrar neles.
     *
     * <p>A ordem dos dois numeros e a mesma da chamada equivalente no Fabric -- propagacao
     * primeiro, inflamabilidade depois. Troca-los produziria um bloco que se comporta ao contrario
     * numa plataforma e certo na outra, que e o tipo de divergencia mais dificil de perceber.
     */
    private static void registerFlammability() {
        if (!(net.minecraft.world.level.block.Blocks.FIRE
                instanceof net.minecraft.world.level.block.FireBlock fire)) {
            return;
        }

        for (ModLoader.LoadedMod mod : loadedMods) {
            for (var entry : dev.lualoader.content.Flammability.declaredIn(mod.manifest())) {
                var id = net.minecraft.resources.ResourceLocation.tryParse(entry.blockId());
                if (id == null) continue;

                var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
                if (block == null || block == net.minecraft.world.level.block.Blocks.AIR) continue;

                fire.setFlammable(block, entry.burnSpread(), entry.flammability());
            }
        }
    }

    /** A chave que diz o que o loader pode instalar sozinho. */
    public static dev.lualoader.install.InstallPolicy installPolicy() {
        return installPolicy;
    }

    /** O instalador de mods, usado pelos comandos. */
    public static dev.lualoader.install.ModInstaller modInstaller() {
        return modInstaller;
    }

    /** Adaptador de plataforma em uso, necessario para publicar a dimensao do evento. */
    public static NeoForgeGameBridge gameBridge() {
        return bridge;
    }

    /** O registrador de conteudo, que sabe quantas variantes cada bloco declara. */
    public static NeoForgeEntityRegistrar entityRegistrar() {
        return entities;
    }

    public static NeoForgeContentRegistrar contentRegistrar() {
        return content;
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
        runtime.attachInstaller(modInstaller, installPolicy);
        runtime.registerAvailableMods(loadedMods);

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
        currentServer = event.getServer();
        NeoForgeCommands.register(event.getServer().getCommands().getDispatcher());

        // Um mod instalado com o servidor no ar registra o comando no runtime, e a arvore que o
        // jogo publica ja foi montada. Sem republicar, o comando existe e ninguem consegue digitar.
        runtime.onCommandsChanged(() -> {
            if (currentServer == null) return;
            NeoForgeCommands.register(currentServer.getCommands().getDispatcher());
            // O cliente guarda a propria copia da arvore; sem reenviar, ele recusa o comando antes
            // mesmo de mandar ao servidor.
            for (var player : currentServer.getPlayerList().getPlayers()) {
                currentServer.getCommands().sendCommands(player);
            }
        });
        runtime.onKeybindsChanged(() -> {
            if (currentServer == null) return;
            for (var player : currentServer.getPlayerList().getPlayers()) {
                dev.lualoader.neoforge.network.NeoForgeScreenNetwork.sendKeybinds(player);
            }
        });

        // O loader esta pronto quando os scripts carregaram e os comandos existem. No Fabric este
        // evento sai da inicializacao do mod; aqui o runtime so nasce com o servidor, entao o
        // ponto equivalente e este -- e nao a inicializacao, onde ainda nao haveria o que avisar.
        runtime.triggerAll("loader_ready", null);
    }

    private static void onServerStarted(
            net.neoforged.neoforge.event.server.ServerStartedEvent event) {
        if (runtime != null) runtime.triggerAll("server_started", null);
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        currentServer = null;
        if (runtime != null) {
            // O estado e gravado depois do evento, para o mod poder ajusta-lo antes de sair.
            runtime.triggerAll("server_stopped", null);
            runtime.saveAllStates();
            runtime = null;
        }
        bridge = null;
    }

    private static void onPlayerJoined(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (runtime == null) return;
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        dev.lualoader.neoforge.network.NeoForgeScreenNetwork.sendKeybinds(player);
        runtime.triggerAll("player_joined", new NeoForgePlayerHandle(player));
    }

    private static void onPlayerLeft(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (runtime == null) return;
        if (!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        runtime.triggerAll("player_left", new NeoForgePlayerHandle(player));
    }

    private static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
        if (runtime == null) return;
        // O agendador avanca antes do evento, para uma tarefa marcada neste tick rodar aqui.
        // Sem esta chamada o relogio interno nunca anda: mod.after aceitava a tarefa e nunca a
        // executava, e o autosave periodico de estado tambem nao acontecia.
        runtime.advanceScheduler();
        runtime.triggerAll("tick", null);
    }
}

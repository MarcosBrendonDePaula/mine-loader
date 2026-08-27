package dev.lualoader.lua;

import dev.lualoader.camera.CameraProtocol;
import dev.lualoader.command.CommandSchema;
import dev.lualoader.input.KeybindProtocol;
import dev.lualoader.manifest.LoaderEvents;
import dev.lualoader.manifest.ManifestImports;
import dev.lualoader.manifest.ModDependencies;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import dev.lualoader.platform.BlockEventData;
import dev.lualoader.platform.EntityEventData;
import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.ItemEventData;
import dev.lualoader.platform.EntitySpec;
import dev.lualoader.platform.GameBridge;
import dev.lualoader.platform.ItemSpec;
import dev.lualoader.platform.PlayerHandle;
import dev.lualoader.structure.StructurePlacer;
import dev.lualoader.ui.ScreenBuilder;
import dev.lualoader.ui.ScreenProtocol;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime Lua por mod. O script recebe apenas a API construída nesta classe. */
public final class LuaRuntime {
    /**
     * Maior volume aceito em uma unica chamada de {@code fill}.
     *
     * <p>Existe para que um erro de script nao peca bilhoes de blocos e trave a thread do
     * servidor. Equivale a um cubo de 32 blocos de lado.
     */
    private static final int MAX_FILL_VOLUME = 32_768;

    /** Limite de coordenada aceito, para evitar posicoes absurdas vindas do script. */
    private static final int MAX_COORDINATE = 30_000_000;

    /** Teto de tarefas agendadas simultaneas, para um laco de agendamento nao consumir a memoria. */
    private static final int MAX_SCHEDULED = 4_096;

    /** Sequência interna para IDs de tarefas, monotónica durante a vida do runtime. */
    private long nextTaskSequence;

    /** Tarefa recorrente que pediu cancelamento durante o próprio callback. */
    private String runningTaskId;
    private final Set<String> cancelledRunningTasks = new java.util.HashSet<>();

    /** Teto de modulos por mod, para uma cadeia de imports nao crescer sem limite. */
    private static final int MAX_MODULES = 128;

    /**
     * Tempo maximo de um callback, em milissegundos.
     *
     * <p>Um tick do servidor dura 50 ms; o limite fica bem abaixo disso para que um script lento
     * atrase, mas nao pare o jogo. Passar do limite interrompe apenas aquele callback.
     */
    private static final long CALLBACK_LIMIT_MILLIS = 20;

    /**
     * Intervalo do salvamento automatico, em ticks.
     *
     * <p>Ate aqui o estado so era gravado ao desligar: uma queda do servidor perdia tudo que os
     * mods acumularam desde o inicio. Cinco minutos limita a perda sem gravar a cada tick.
     */
    private static final int AUTOSAVE_TICKS = 6_000;

    /** Fonte unica dos nomes de evento, compartilhada com a validacao do manifesto. */
    private static final Set<String> EVENTS = LoaderEvents.ALL;

    private final Logger logger;
    private final Map<String, LoadedScript> scripts = new LinkedHashMap<>();

    /** Mods descobertos pelo bootstrap, disponíveis para resolução sob demanda. */
    private final Map<String, ModLoader.LoadedMod> availableMods = new LinkedHashMap<>();

    /** Cadeia activa de entrypoints a serem compilados por require(); vive na thread do servidor. */
    private final Deque<String> resolvingMods = new ArrayDeque<>();

    /** Profundidade de compilação; evita republicar a árvore a cada declaração do entrypoint. */
    private int compilationDepth;

    /**
     * Estado compartilhado por mod, exposto como {@code mod.state} e {@code ctx.state}.
     *
     * <p>Vive fora do ambiente Lua para sobreviver a uma recarga: alterar um script durante o
     * desenvolvimento nao deve apagar o que o mod acumulou. Cada mod enxerga apenas a propria
     * tabela.
     */
    private final Map<String, LuaTable> states = new LinkedHashMap<>();

    /**
     * Dados persistentes por jogador, separados por mod.
     *
     * <p>A chave externa é o UUID e o conteúdo é uma tabela Lua limitada aos tipos serializáveis pelo
     * {@link StateStore}. O UUID não é exposto como nome de arquivo ao script; ele só organiza o
     * estado internamente e permite que o mesmo jogador conserve progresso entre sessões.
     */
    private final Map<String, Map<String, LuaTable>> playerStates = new LinkedHashMap<>();

    /**
     * Tarefas agendadas por {@code mod.after}, ordenadas por tick de disparo.
     *
     * <p>Sem isto, qualquer coisa com duracao precisava contar ticks a mao dentro do evento
     * {@code tick}, que e global e caro. O relogio avanca uma vez por tick do servidor.
     */
    private final java.util.List<ScheduledTask> scheduled = new java.util.ArrayList<>();

    /**
     * Comandos registrados por mod, por nome.
     *
     * <p>O adaptador consulta este mapa ao montar a arvore de comandos do jogo, e chama de volta
     * quando o comando e executado.
     */
    private final Map<String, RegisteredCommand> commands = new LinkedHashMap<>();

    /** Keybinds registrados por mod, indexados pelo id qualificado mod:id. */
    private final Map<String, RegisteredKeybind> keybinds = new LinkedHashMap<>();

    /** Câmeras lógicas publicadas ao cliente, indexadas pelo id qualificado mod:id. */
    private final Map<String, RegisteredCamera> cameras = new LinkedHashMap<>();

    /**
     * Callbacks de janela, por identificador de menu.
     *
     * <p>E o que permite acoplar logica a uma interface: o mod desenha o estado como itens, recebe
     * o clique com o indice do slot e decide o que fazer, sem precisar de uma tela desenhada.
     */
    private final Map<String, RegisteredMenu> menus = new LinkedHashMap<>();

    /** Callbacks de tela desenhada, por identificador. */
    private final Map<String, RegisteredMenu> screens = new LinkedHashMap<>();

    /**
     * Processos declarados pelos mods, por identificador.
     *
     * <p>O livro de receitas do jogo só conhece as receitas do jogo. Uma mecânica inventada por um
     * mod — dar trigo a uma vaca e receber leite, moer minério, curtir couro — não existe lá, e por
     * isso seria invisível a qualquer catálogo. Este registro é o lugar onde ela passa a existir.
     *
     * <p>É global de propósito: um catálogo é um mod que lista o que os outros declararam, e não
     * teria como fazer isso se cada mod só enxergasse os próprios processos.
     */
    private final Map<String, RegisteredProcess> processes = new LinkedHashMap<>();
    private long currentTick;
    private final Path remoteCache;
    private final StateStore stateStore;
    private GameBridge bridge = GameBridge.DETACHED;

    /** Runtime apenas local, sem persistencia: usado em validacao offline e testes. */
    public LuaRuntime(Logger logger) {
        this(logger, null, null);
    }

    /**
     * @param remoteCache   diretorio para scripts baixados; {@code null} recusa script remoto
     * @param stateDirectory onde gravar o estado dos mods; {@code null} mantem tudo em memoria
     */
    public LuaRuntime(Logger logger, Path remoteCache, Path stateDirectory) {
        this.logger = logger;
        this.remoteCache = remoteCache;
        this.stateStore = new StateStore(logger, stateDirectory);
    }

    /**
     * Instalador e a chave que diz se um mod pode usa-lo.
     *
     * <p>Os dois vem do bootstrap e podem ser nulos: um runtime de teste ou de validacao offline
     * nao instala nada, e a API recusa com o motivo em vez de estourar.
     */
    private dev.lualoader.install.ModInstaller installer;
    private dev.lualoader.install.InstallPolicy installPolicy;

    /** Previas ja mostradas, para o segundo passo gravar o que foi lido e nao uma busca nova. */
    private final Map<String, dev.lualoader.install.ModInstaller.Preview> pendingInstalls =
            new LinkedHashMap<>();

    /**
     * O que a plataforma precisa refazer quando um mod entra depois da carga.
     *
     * <p>Os nomes de comando sao literais na arvore do jogo, montada uma vez na inicializacao: um
     * mod instalado agora tem o comando registrado no runtime e invisivel para quem digita. Quem
     * sabe reconstruir aquela arvore e o adaptador, e nao o nucleo.
     */
    private Runnable commandRefresh;

    /** Como o adaptador republica o catálogo de hotkeys quando um mod entra ou recarrega. */
    private Runnable keybindRefresh;

    /** Como o adaptador republica o catálogo de câmeras quando um mod entra ou recarrega. */
    private Runnable cameraRefresh;

    /** Liga o instalador do bootstrap. Sem ele, a API de instalacao recusa. */
    public void attachInstaller(dev.lualoader.install.ModInstaller installer,
                                dev.lualoader.install.InstallPolicy policy) {
        this.installer = installer;
        this.installPolicy = policy;
    }

    /** Como a plataforma republica os comandos quando um mod entra fora da inicializacao. */
    public void onCommandsChanged(Runnable refresh) {
        this.commandRefresh = refresh;
    }

    /** Como a plataforma republica as hotkeys declaradas aos clientes online. */
    public void onKeybindsChanged(Runnable refresh) {
        this.keybindRefresh = refresh;
    }

    /** Como a plataforma republica as câmeras quando um mod entra ou recarrega. */
    public void onCamerasChanged(Runnable refresh) {
        this.cameraRefresh = refresh;
    }

    /**
     * Carrega um mod que acabou de chegar ao disco, sem reiniciar.
     *
     * <p>Vale para o que vive no runtime -- script, evento, comando, menu, tela. <b>Nao vale para
     * bloco e item</b>: o registro do Minecraft fecha durante a inicializacao, e conteudo declarado
     * depois disso simplesmente nao existe. Quem chama precisa ter conferido isso antes; aqui a
     * carga e feita de qualquer forma, porque um mod com conteudo ainda tem scripts que funcionam.
     *
     * @return {@code false} quando o mod nao foi encontrado ou o script nao compilou
     */
    public boolean loadInstalled(java.nio.file.Path modDirectory) {
        java.nio.file.Path root = modDirectory.getParent();
        if (root == null) return false;

        String modId = modDirectory.getFileName().toString();
        try {
            List<ModLoader.LoadedMod> discovered = new ModLoader(logger).discover(root);
            registerAvailableMods(discovered);
            for (ModLoader.LoadedMod found : discovered) {
                if (!found.manifest().id.equals(modId)) continue;

                // A substituição é transaccional dentro de load(): o mod antigo só solta seus
                // registros depois que o novo entrypoint passa pela compilação inteira.
                load(found);
                if (commandRefresh != null) commandRefresh.run();
                if (keybindRefresh != null) keybindRefresh.run();
                if (cameraRefresh != null) cameraRefresh.run();
                return true;
            }
            logger.error("Mod {} instalado mas nao encontrado na pasta de mods", modId);
            return false;
        } catch (IOException | RuntimeException error) {
            logger.error("Mod {} instalado, mas o script nao carregou: {}", modId,
                    error.getMessage());
            return false;
        }
    }

    /** A chave de instalacao em uso, ou {@code null} quando o bootstrap nao ligou nenhuma. */
    public dev.lualoader.install.InstallPolicy installPolicy() {
        return installPolicy;
    }

    /** Conecta o adaptador de plataforma. Chamado pelo bootstrap antes de disparar eventos. */
    public void attach(GameBridge bridge) {
        this.bridge = bridge == null ? GameBridge.DETACHED : bridge;
    }

    /**
     * Regista os mods descobertos pelo bootstrap para que {@code mod.require()} possa carregá-los sob
     * demanda. A lista é substituída a cada descoberta, de modo que instalação e recarga vejam o
     * mesmo catálogo que o loader viu no disco.
     */
    public void registerAvailableMods(List<ModLoader.LoadedMod> mods) {
        availableMods.clear();
        if (mods == null) return;
        for (ModLoader.LoadedMod mod : mods) {
            if (mod == null || mod.manifest() == null || mod.manifest().id == null) continue;
            availableMods.put(mod.manifest().id, mod);
        }
    }

    public void load(ModLoader.LoadedMod mod) throws IOException {
        if (mod == null || mod.manifest() == null || mod.manifest().id == null) {
            throw new IOException("mod invalido para carga Lua");
        }
        availableMods.put(mod.manifest().id, mod);
        compileAndInstall(mod, true);
    }

    /** Compila e instala um mod, mantendo a cadeia activa para detectar ciclos dinâmicos. */
    private void compileAndInstall(ModLoader.LoadedMod mod, boolean replaceExisting)
            throws IOException {
        String id = mod.manifest().id;
        if (resolvingMods.contains(id)) {
            throw new IOException("dependencia circular dinamica: " + resolutionPath(id));
        }
        if (!replaceExisting && scripts.containsKey(id)) return;
        availableMods.put(id, mod);

        RegistrationSnapshot previous = detachRegistrations(id);
        resolvingMods.addLast(id);
        compilationDepth++;
        try {
            LoadedScript script = compile(mod);
            scripts.put(id, script);
            logger.info("Script Lua carregado: {}", id);
        } catch (IOException | RuntimeException error) {
            restoreRegistrations(previous);
            throw error;
        } finally {
            compilationDepth--;
            if (!resolvingMods.isEmpty() && id.equals(resolvingMods.peekLast())) {
                resolvingMods.removeLast();
            } else {
                resolvingMods.remove(id);
            }
        }
    }

    /**
     * Inicia uma substituição isolando todos os registos do mod.
     *
     * <p>O snapshot inteiro preserva a ordem dos mapas no rollback. Em sucesso, o entrypoint novo
     * já ocupou os mapas vazios e os registos antigos não reaparecem.
     */
    private RegistrationSnapshot detachRegistrations(String modId) {
        RegistrationSnapshot snapshot = new RegistrationSnapshot(
                new LinkedHashMap<>(commands), new LinkedHashMap<>(keybinds),
                new LinkedHashMap<>(cameras), new LinkedHashMap<>(menus),
                new LinkedHashMap<>(screens),
                new LinkedHashMap<>(processes), new ArrayList<>(scheduled));
        commands.entrySet().removeIf(entry -> entry.getValue().modId().equals(modId));
        keybinds.entrySet().removeIf(entry -> entry.getValue().modId().equals(modId));
        cameras.entrySet().removeIf(entry -> entry.getValue().modId().equals(modId));
        menus.entrySet().removeIf(entry -> entry.getValue().modId().equals(modId));
        screens.entrySet().removeIf(entry -> entry.getValue().modId().equals(modId));
        processes.entrySet().removeIf(entry -> entry.getValue().modId().equals(modId));
        scheduled.removeIf(task -> task.modId().equals(modId));
        return snapshot;
    }

    private void restoreRegistrations(RegistrationSnapshot snapshot) {
        commands.clear();
        commands.putAll(snapshot.commands());
        keybinds.clear();
        keybinds.putAll(snapshot.keybinds());
        cameras.clear();
        cameras.putAll(snapshot.cameras());
        menus.clear();
        menus.putAll(snapshot.menus());
        screens.clear();
        screens.putAll(snapshot.screens());
        processes.clear();
        processes.putAll(snapshot.processes());
        scheduled.clear();
        scheduled.addAll(snapshot.scheduled());
    }

    /** Resolve uma dependency declarada, compilando-a apenas quando ainda não está carregada. */
    private LoadedScript resolveDependency(ModLoader.LoadedMod requester, String dependencyId)
            throws IOException {
        if (requester.manifest().dependencies == null
                || !requester.manifest().dependencies.containsKey(dependencyId)) {
            throw new IOException("mod " + dependencyId
                    + " precisa estar declarado em dependencies para ser usado");
        }

        if (resolvingMods.contains(dependencyId)) {
            throw new IOException("dependencia circular dinamica: " + resolutionPath(dependencyId));
        }

        ModLoader.LoadedMod dependency = availableMods.get(dependencyId);
        LoadedScript loaded = scripts.get(dependencyId);
        if (dependency == null && loaded == null) {
            throw new IOException("mod " + dependencyId
                    + " nao esta disponivel para resolucao dinamica");
        }

        ModManifest dependencyManifest = dependency != null
                ? dependency.manifest()
                : loaded.mod().manifest();
        String minimum = requester.manifest().dependencies.get(dependencyId);
        if (!ModDependencies.satisfies(dependencyManifest.version, minimum)) {
            throw new IOException("mod " + requester.manifest().id + " exige " + dependencyId
                    + " na versao " + minimum + ", mas ha " + dependencyManifest.version);
        }

        if (loaded == null) {
            compileAndInstall(dependency, false);
            loaded = scripts.get(dependencyId);
        }
        if (loaded == null) {
            throw new IOException("mod " + dependencyId + " nao foi carregado");
        }
        return loaded;
    }

    private String resolutionPath(String repeated) {
        List<String> chain = new ArrayList<>(resolvingMods);
        int start = chain.indexOf(repeated);
        if (start > 0) chain = new ArrayList<>(chain.subList(start, chain.size()));
        chain.add(repeated);
        return String.join(" -> ", chain);
    }

    /**
     * Avanca o relogio do agendador e executa o que venceu.
     *
     * <p>Chamado uma vez por tick do servidor, antes do evento {@code tick}.
     */
    public void advanceScheduler() {
        currentTick++;

        // O estado passa a ser gravado periodicamente, e nao apenas no desligamento.
        if (stateStore.isEnabled() && currentTick % AUTOSAVE_TICKS == 0) {
            saveAllStates();
        }
        if (scheduled.isEmpty()) return;

        java.util.List<ScheduledTask> due = new java.util.ArrayList<>();
        scheduled.removeIf(task -> {
            if (task.dueTick() > currentTick) return false;
            due.add(task);
            return true;
        });

        for (ScheduledTask task : due) {
            LoadedScript script = scripts.get(task.modId());
            if (script == null) continue;
            boolean repeat = task.intervalTicks() > 0;
            LuaValue result = LuaValue.NIL;
            runningTaskId = task.id();
            try {
                script.budget().start();
                // **Confere que ele ainda esta no servidor.** Um jogador que saiu deixa um handle
                // que nao fala com ninguem, e uma tela atualizada para quem nao esta la seria, na
                // melhor das hipoteses, trabalho perdido.
                PlayerHandle dono = task.player();
                if (dono != null && !bridge.onlinePlayers().contains(dono.name())) dono = null;
                result = task.callback().call(context(script.mod(), dono, null));
                // Apenas false para uma tarefa recorrente a interrompe; qualquer outro retorno,
                // inclusive nil, mantém o comportamento natural de um callback sem retorno.
                if (task.intervalTicks() > 0 && result.isboolean() && !result.toboolean()) {
                    repeat = false;
                }
            } catch (LuaError error) {
                repeat = false;
                logger.error("Erro Lua em tarefa agendada do mod {}: {}", task.modId(), error.getMessage());
            } catch (BridgeException error) {
                repeat = false;
                logger.error("Erro de plataforma em tarefa agendada do mod {}: {}",
                        task.modId(), error.getMessage());
            } catch (RuntimeException error) {
                repeat = false;
                logger.error("Erro Java em tarefa agendada do mod {}", task.modId(), error);
            } finally {
                script.budget().stop();
                runningTaskId = null;
            }

            boolean cancelled = cancelledRunningTasks.remove(task.id());
            if (repeat && !cancelled && scheduled.size() < MAX_SCHEDULED) {
                scheduled.add(new ScheduledTask(task.id(), task.modId(),
                        currentTick + task.intervalTicks(), task.intervalTicks(),
                        task.callback(), task.player()));
            }
        }
    }

    /**
     * Entrega um clique de janela ao mod dono.
     *
     * @param slot   indice do slot clicado na grade do mod
     * @param button 0 para o botao esquerdo, 1 para o direito
     * @param itemId item exibido no slot no momento do clique
     */
    public void triggerMenuClick(String modId, String menuId, int slot, int button,
                                 String itemId, PlayerHandle player) {
        RegisteredMenu menu = menus.get(menuId);
        if (menu == null || !menu.modId().equals(modId)) return;

        LoadedScript script = scripts.get(menu.modId());
        if (script == null) return;

        try {
            script.budget().start();
            LuaTable context = context(script.mod(), player, null);

            LuaTable menuApi = new LuaTable();
            menuApi.set("id", LuaValue.valueOf(menuId));
            menuApi.set("slot", LuaValue.valueOf(slot));
            menuApi.set("button", LuaValue.valueOf(button));
            menuApi.set("item", LuaValue.valueOf(itemId));
            context.set("menu", menuApi);

            menu.callback().call(context);
        } catch (LuaError error) {
            logger.error("Erro Lua no menu {} do mod {}: {}", menuId, menu.modId(), error.getMessage());
        } catch (BridgeException error) {
            logger.error("Erro de plataforma no menu {} do mod {}: {}",
                    menuId, menu.modId(), error.getMessage());
        } catch (RuntimeException error) {
            logger.error("Erro Java no menu {} do mod {}", menuId, menu.modId(), error);
        } finally {
            script.budget().stop();
        }
    }

    /**
     * Entrega ao mod dono um evento vindo de uma tela desenhada.
     *
     * <p>A acao chega do cliente e por isso e conferida contra o vocabulario fechado do protocolo:
     * o cliente nao dita quais acoes existem.
     */
    public void triggerScreenEvent(String screenId, String elementId, String action,
                                   String value, PlayerHandle player) {
        if (!ScreenProtocol.ACTIONS.contains(action)) {
            logger.warn("Acao de tela desconhecida ignorada: {}", action);
            return;
        }

        RegisteredMenu screen = screens.get(screenId);
        if (screen == null) return;

        LoadedScript script = scripts.get(screen.modId());
        if (script == null) return;

        try {
            script.budget().start();
            LuaTable context = context(script.mod(), player, null);

            LuaTable ui = new LuaTable();
            ui.set("screen", LuaValue.valueOf(screenId));
            ui.set("element", LuaValue.valueOf(elementId == null ? "" : elementId));
            ui.set("action", LuaValue.valueOf(action));
            ui.set("value", LuaValue.valueOf(value == null ? "" : value));
            context.set("ui", ui);

            screen.callback().call(context);
        } catch (LuaError error) {
            logger.error("Erro Lua na tela {} do mod {}: {}", screenId, screen.modId(), error.getMessage());
        } catch (BridgeException error) {
            logger.error("Erro de plataforma na tela {} do mod {}: {}",
                    screenId, screen.modId(), error.getMessage());
        } catch (RuntimeException error) {
            logger.error("Erro Java na tela {} do mod {}", screenId, screen.modId(), error);
        } finally {
            script.budget().stop();
        }
    }

    /** Definições de hotkey que têm callback e podem ser publicadas ao cliente. */
    public List<KeybindProtocol.Binding> keybindDefinitions() {
        return keybinds.values().stream().map(RegisteredKeybind::binding).toList();
    }

    /** Definições de câmera que podem ser publicadas ao cliente. */
    public List<CameraProtocol.Camera> cameraDefinitions() {
        return cameras.values().stream().map(RegisteredCamera::camera).toList();
    }

    /** Entrega ao mod o evento de uma hotkey já validada pelo catálogo publicado. */
    public boolean triggerKeybind(String qualifiedId, PlayerHandle player) {
        RegisteredKeybind binding = keybinds.get(qualifiedId);
        if (binding == null) return false;

        LoadedScript script = scripts.get(binding.modId());
        if (script == null) return false;

        try {
            script.budget().start();
            LuaTable context = context(script.mod(), player, null);
            LuaTable api = new LuaTable();
            api.set("id", LuaValue.valueOf(binding.binding().id()));
            api.set("key", LuaValue.valueOf(binding.binding().key()));
            api.set("category", LuaValue.valueOf(binding.binding().category()));
            api.set("action", LuaValue.valueOf("pressed"));
            api.set("mod", LuaValue.valueOf(binding.binding().modId()));
            api.set("modifiers", toLuaList(binding.binding().modifiers()));
            context.set("keybind", api);
            binding.callback().call(context);
        } catch (LuaError error) {
            logger.error("Erro Lua na hotkey {} do mod {}: {}", binding.binding().id(),
                    binding.modId(), error.getMessage());
            reportToPlayer(player, script.mod(), "keybind", error.getMessage());
        } catch (BridgeException error) {
            logger.error("Erro de plataforma na hotkey {} do mod {}: {}", binding.binding().id(),
                    binding.modId(), error.getMessage());
            reportToPlayer(player, script.mod(), "keybind", error.getMessage());
        } catch (RuntimeException error) {
            logger.error("Erro Java na hotkey {} do mod {}", binding.binding().id(),
                    binding.modId(), error);
        } finally {
            script.budget().stop();
        }
        return true;
    }

    /** Nomes de comando registrados pelos mods. */
    public java.util.Set<String> commandNames() {
        return java.util.Set.copyOf(commands.keySet());
    }

    /** Schema estruturado de um comando, ou {@code null} para o formato legado livre. */
    public CommandSchema commandSchema(String name) {
        RegisteredCommand command = commands.get(name);
        return command == null ? null : command.schema();
    }

    /**
     * Executa um comando legado, preservando o contrato de texto livre existente.
     */
    public boolean runCommand(String name, PlayerHandle player, String arguments) {
        return runCommand(name, player, arguments, null, Map.of());
    }

    /**
     * Executa um comando estruturado com tokens e argumentos já validados pelo bridge.
     *
     * @param arguments texto digitado depois do nome do comando
     * @param wordList tokens do caminho estruturado, ou {@code null} no modo legado
     * @param values argumentos nomeados validados pelo bridge
     * @return {@code false} quando o comando nao existe
     */
    public boolean runCommand(String name, PlayerHandle player, String arguments,
                              List<String> wordList, Map<String, String> values) {
        RegisteredCommand command = commands.get(name);
        if (command == null) return false;

        LoadedScript script = scripts.get(command.modId());
        if (script == null) return false;

        try {
            script.budget().start();
            LuaTable context = context(script.mod(), player, null);

            String text = arguments == null ? "" : arguments.trim();
            context.set("args", LuaValue.valueOf(text));

            // O formato legado separa o texto no próprio core. O bridge estruturado entrega os
            // tokens já resolvidos para preservar argumentos string com espaços.
            List<String> suppliedWords = wordList;
            if (suppliedWords == null) {
                suppliedWords = text.isEmpty() ? List.of() : List.of(text.split("\\s+"));
            }
            LuaTable argv = new LuaTable();
            String subcommand = suppliedWords.isEmpty() ? "" : suppliedWords.get(0);
            for (int index = 0; index < suppliedWords.size(); index++) {
                argv.set(index + 1, LuaValue.valueOf(suppliedWords.get(index)));
            }
            context.set("argv", argv);
            context.set("subcommand", LuaValue.valueOf(subcommand));

            LuaTable commandApi = new LuaTable();
            commandApi.set("name", LuaValue.valueOf(name));
            commandApi.set("structured", LuaValue.valueOf(command.schema() != null));
            commandApi.set("arguments", toLuaMap(values, command.schema()));
            commandApi.set("path", toLuaList(suppliedWords));
            context.set("command", commandApi);

            command.callback().call(context);
        } catch (LuaError error) {
            logger.error("Erro Lua no comando {} do mod {}: {}", name, command.modId(), error.getMessage());
        } catch (BridgeException error) {
            logger.error("Erro de plataforma no comando {} do mod {}: {}",
                    name, command.modId(), error.getMessage());
        } catch (RuntimeException error) {
            logger.error("Erro Java no comando {} do mod {}", name, command.modId(), error);
        } finally {
            script.budget().stop();
        }
        return true;
    }

    private String scheduleTask(String modId, int dueInTicks, int intervalTicks,
                                 LuaFunction callback, PlayerHandle player) {
        if (scheduled.size() >= MAX_SCHEDULED) {
            throw new LuaError("limite de " + MAX_SCHEDULED + " tarefas agendadas atingido");
        }
        String id = modId + ":task-" + (++nextTaskSequence);
        scheduled.add(new ScheduledTask(id, modId, currentTick + dueInTicks,
                intervalTicks, callback, player));
        return id;
    }

    /** Quantidade de tarefas ainda pendentes, usada em diagnostico e testes. */
    public int pendingTasks() {
        return scheduled.size();
    }

    /** Grava em disco o estado de todos os mods. Chamado quando o servidor para. */
    public void saveAllStates() {
        if (!stateStore.isEnabled()) return;
        for (Map.Entry<String, LuaTable> entry : states.entrySet()) {
            stateStore.save(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Map<String, LuaTable>> entry : playerStates.entrySet()) {
            LuaTable allPlayers = new LuaTable();
            for (Map.Entry<String, LuaTable> player : entry.getValue().entrySet()) {
                allPlayers.set(player.getKey(), player.getValue());
            }
            stateStore.saveScoped(entry.getKey(), "players", allPlayers);
        }
        logger.info("Estado de {} mod(s) gravado", states.size());
    }

    /** Grava o estado de um mod especifico. */
    public void saveState(String modId) {
        LuaTable state = states.get(modId);
        if (state != null) stateStore.save(modId, state);

        Map<String, LuaTable> byPlayer = playerStates.get(modId);
        if (byPlayer != null) {
            LuaTable allPlayers = new LuaTable();
            for (Map.Entry<String, LuaTable> player : byPlayer.entrySet()) {
                allPlayers.set(player.getKey(), player.getValue());
            }
            stateStore.saveScoped(modId, "players", allPlayers);
        }
    }

    /**
     * Le o manifesto do disco de novo, para a recarga alcancar o {@code mod.json}.
     *
     * <p>Recompilar so o Lua deixava metade da recarga de fora: acrescentar uma permissao, um evento
     * ou um script por bloco no manifesto nao surtia efeito, e nada avisava -- o comando dizia
     * "recarregado" e o mod continuava com as regras antigas.
     *
     * <p>Conteudo declarado -- bloco, item, aba criativa -- continua exigindo reinicio, porque ja
     * foi registrado no jogo e o registro nao aceita troca em execucao. O que muda aqui sao as
     * regras que o runtime consulta a cada chamada.
     *
     * <p>Um manifesto que passou a ser invalido nao derruba a recarga: o mod segue com o anterior,
     * que e melhor que ficar sem nenhum.
     */
    private ModLoader.LoadedMod rereadManifest(ModLoader.LoadedMod current) {
        Path directory = current.directory().toAbsolutePath().normalize();
        Path parent = directory.getParent();
        if (parent == null) return current;

        try {
            for (ModLoader.LoadedMod found : new ModLoader(logger).discover(parent)) {
                if (found.manifest().id.equals(current.manifest().id)) return found;
            }
        } catch (IOException | RuntimeException error) {
            logger.warn("Manifesto de {} nao pode ser relido, mantendo o anterior: {}",
                    current.manifest().id, error.getMessage());
        }
        return current;
    }

    /** Descarta o estado acumulado por um mod. Usado quando o mod e removido, nao em recarga. */
    public void forgetState(String modId) {
        states.remove(modId);
        playerStates.remove(modId);
    }

    public boolean reload(String modId) throws IOException {
        LoadedScript previous = scripts.get(modId);
        if (previous == null) return false;

        int discarded = (int) scheduled.stream()
                .filter(task -> task.modId().equals(modId)).count();
        compileAndInstall(rereadManifest(previous.mod()), true);

        logger.info("Script Lua recarregado: {}{}", modId,
                discarded == 0 ? "" : " (" + discarded + " tarefa(s) pendente(s) descartada(s))");
        if (commandRefresh != null) commandRefresh.run();
        if (keybindRefresh != null) keybindRefresh.run();
        if (cameraRefresh != null) cameraRefresh.run();
        return true;
    }

    public int reloadAll() {
        int count = 0;
        for (String id : ListCopy.ids(scripts)) {
            try {
                if (reload(id)) count++;
            } catch (IOException | RuntimeException error) {
                logger.error("Falha ao recarregar script Lua {}", id, error);
            }
        }
        return count;
    }

    public boolean hasEvent(String event) {
        return scripts.values().stream().anyMatch(script -> script.callbacks().containsKey(event));
    }

    /**
     * Entrega aos mods um fato que nasceu no cliente.
     *
     * <p>O nome do evento e o da tela sao conferidos contra os conjuntos fechados do nucleo antes
     * de qualquer script ver o valor: o que chega pela rede vem da maquina de quem joga, e um
     * script nao deveria precisar desconfiar do proprio contexto.
     *
     * <p>Um nome fora da lista e descartado em silencio, e nao vira erro. Um cliente mais novo que
     * o servidor relataria fatos que este ainda nao conhece, e derrubar a conexao por isso seria
     * transformar diferenca de versao em falha.
     */
    public void triggerClientEvent(String event, String target, PlayerHandle player) {
        if (!dev.lualoader.manifest.LoaderEvents.CLIENT.contains(event)) {
            logger.warn("Evento de cliente desconhecido ignorado: {}", event);
            return;
        }
        String screen = target == null ? "" : target;
        if (!screen.isBlank() && !ScreenProtocol.TARGETS.contains(screen)) {
            logger.warn("Tela desconhecida em {} ignorada: {}", event, screen);
            return;
        }

        for (LoadedScript script : List.copyOf(scripts.values())) {
            LuaFunction callback = script.callbacks().get(event);
            if (callback == null) continue;

            try {
                script.budget().start();
                LuaTable context = context(script.mod(), player, null);

                LuaTable client = new LuaTable();
                client.set("screen", LuaValue.valueOf(screen));
                context.set("client", client);

                callback.call(context);
            } catch (LuaError error) {
                logger.error("Erro Lua em {} do mod {}: {}", event, script.mod().manifest().id,
                        error.getMessage());
            } catch (BridgeException error) {
                logger.error("Erro de plataforma em {} do mod {}: {}", event,
                        script.mod().manifest().id, error.getMessage());
            } catch (RuntimeException error) {
                logger.error("Erro Java em {} do mod {}", event, script.mod().manifest().id, error);
            } finally {
                script.budget().stop();
            }
        }
    }

    public void triggerAll(String event, PlayerHandle player) {
        trigger(event, player, null);
    }

    /**
     * Dispara um evento originado por uma criatura do mundo.
     *
     * <p>Chega a todo mod que o declarou, e nao so ao dono de um conteudo: a criatura pode ser do
     * jogo, e amarrar o evento a um mod deixaria de fora justamente o mod de combate, que e quem
     * mais precisa dele.
     *
     * @return {@code true} se algum script pediu para cancelar a acao padrao
     */
    public boolean triggerEntity(String event, PlayerHandle player, EntityEventData entity) {
        if (!EVENTS.contains(event)) return false;
        boolean cancelled = false;

        for (LoadedScript script : List.copyOf(scripts.values())) {
            LuaFunction callback = script.callbacks().get(event);
            if (callback == null) continue;
            try {
                script.budget().start();
                LuaValue result = callback.call(contextWithEntity(script.mod(), player, entity));
                if (result.isboolean() && !result.toboolean()) {
                    cancelled = true;
                }
            } catch (LuaError error) {
                logger.error("Erro Lua no mod {} durante {}: {}",
                        script.mod().manifest().id, event, error.getMessage());
                reportToPlayer(player, script.mod(), event, error.getMessage());
            } catch (BridgeException error) {
                logger.error("Erro de plataforma no mod {} durante {}: {}",
                        script.mod().manifest().id, event, error.getMessage());
                reportToPlayer(player, script.mod(), event, error.getMessage());
            } catch (RuntimeException error) {
                logger.error("Erro Java na ponte Lua do mod {} durante {}",
                        script.mod().manifest().id, event, error);
            } finally {
                script.budget().stop();
            }
        }
        return cancelled;
    }

    /**
     * O contexto de sempre, mais {@code ctx.entity}.
     *
     * <p>Os campos sao valores, e nao funcoes: o adaptador ja resolveu tudo antes de disparar,
     * porque no instante da morte perguntar a vida ao jogo responderia zero.
     */
    private LuaTable contextWithEntity(ModLoader.LoadedMod mod, PlayerHandle player,
                                       EntityEventData entity) {
        LuaTable context = context(mod, player, null);
        if (entity == null) return context;

        LuaTable api = new LuaTable();
        api.set("uuid", LuaValue.valueOf(entity.uuid()));
        api.set("id", LuaValue.valueOf(entity.entityId()));
        api.set("x", LuaValue.valueOf(entity.x()));
        api.set("y", LuaValue.valueOf(entity.y()));
        api.set("z", LuaValue.valueOf(entity.z()));
        api.set("health", LuaValue.valueOf(entity.health()));
        api.set("max_health", LuaValue.valueOf(entity.maxHealth()));
        if (entity.name() != null) api.set("name", LuaValue.valueOf(entity.name()));
        api.set("amount", LuaValue.valueOf(entity.amount()));

        // A origem so aparece quando houve uma. Um bicho que morreu de queda nao tem quem o matou,
        // e um campo vazio ali faria o script tratar "ninguem" como um nome.
        if (entity.sourceId() != null) api.set("source", LuaValue.valueOf(entity.sourceId()));
        if (entity.sourceUuid() != null) {
            api.set("source_uuid", LuaValue.valueOf(entity.sourceUuid()));
        }
        if (entity.sourceName() != null) {
            api.set("source_name", LuaValue.valueOf(entity.sourceName()));
        }
        context.set("entity", api);
        return context;
    }

    /**
     * Dispara um evento originado por uma interação com um bloco declarativo.
     *
     * @return {@code true} se algum script pediu para cancelar a ação padrão do jogo
     */
    public boolean triggerBlock(String event, PlayerHandle player, BlockEventData block) {
        return trigger(event, player, block);
    }

    /**
     * Executa os callbacks e informa se a ação padrão deve ser cancelada.
     *
     * <p>Um callback cancela devolvendo {@code false}. Devolver {@code nil}, nada ou qualquer outro
     * valor deixa o jogo seguir normalmente, para que um script que apenas observa nao precise se
     * preocupar com o retorno.
     */
    /**
     * Dispara um evento originado por um item declarado.
     *
     * @return {@code true} se algum script pediu para cancelar a acao padrao
     */
    public boolean triggerItem(String event, PlayerHandle player, ItemEventData item) {
        if (!EVENTS.contains(event)) return false;
        boolean cancelled = false;

        for (LoadedScript script : List.copyOf(scripts.values())) {
            // Assim como nos blocos, o evento pertence ao mod que declarou o item.
            if (!ownsId(script.mod(), item.itemId())) continue;

            LuaFunction callback = script.itemHandlers()
                    .getOrDefault(item.itemId(), Map.of())
                    .get(itemHandlerName(event));
            if (callback == null) callback = script.callbacks().get(event);
            if (callback == null) continue;

            try {
                script.budget().start();
                LuaValue result = callback.call(itemContext(script.mod(), player, item));
                if (result.isboolean() && !result.toboolean()) cancelled = true;
            } catch (LuaError error) {
                logger.error("Erro Lua no mod {} durante {}: {}",
                        script.mod().manifest().id, event, error.getMessage());
                reportToPlayer(player, script.mod(), event, error.getMessage());
            } catch (BridgeException error) {
                logger.error("Erro de plataforma no mod {} durante {}: {}",
                        script.mod().manifest().id, event, error.getMessage());
                reportToPlayer(player, script.mod(), event, error.getMessage());
            } catch (RuntimeException error) {
                logger.error("Erro Java na ponte Lua do mod {} durante {}",
                        script.mod().manifest().id, event, error);
            } finally {
                script.budget().stop();
            }
        }
        return cancelled;
    }

    private static String itemHandlerName(String event) {
        return switch (event) {
            case "item_used" -> "on_use";
            case "item_used_on_block" -> "on_use_on_block";
            default -> "";
        };
    }

    /** Contexto de um evento de item: {@code ctx.item} descreve o que foi usado e sobre o que. */
    private LuaTable itemContext(ModLoader.LoadedMod mod, PlayerHandle player, ItemEventData item) {
        LuaTable context = context(mod, player, null);

        LuaTable itemApi = new LuaTable();
        itemApi.set("id", LuaValue.valueOf(item.itemId()));
        if (item.targetBlock() != null) {
            itemApi.set("target_block", LuaValue.valueOf(item.targetBlock()));
        } else {
            itemApi.set("target_block", LuaValue.NIL);
        }
        if (item.hasPosition()) {
            itemApi.set("x", LuaValue.valueOf(item.x()));
            itemApi.set("y", LuaValue.valueOf(item.y()));
            itemApi.set("z", LuaValue.valueOf(item.z()));
        }
        context.set("item", itemApi);
        return context;
    }

    private boolean trigger(String event, PlayerHandle player, BlockEventData block) {
        if (!EVENTS.contains(event)) return false;
        boolean cancelled = false;
        for (LoadedScript script : List.copyOf(scripts.values())) {
            // Um evento de bloco pertence ao mod que declarou o bloco. Sem esta checagem,
            // qualquer mod receberia interacoes com o conteudo de todos os outros.
            if (block != null && !ownsBlock(script.mod(), block)) continue;

            // A logica declarada no manifesto para aquele bloco tem prioridade: quando o JSON diz
            // qual codigo responde por aquele bloco, o callback global do mod nao e chamado.
            LuaFunction callback = blockHandler(script, event, block);
            if (callback == null) callback = script.callbacks().get(event);
            if (callback == null) continue;
            try {
                script.budget().start();
                LuaValue result = callback.call(context(script.mod(), player, block));
                if (result.isboolean() && !result.toboolean()) {
                    cancelled = true;
                }
            } catch (LuaError error) {
                logger.error("Erro Lua no mod {} durante {}: {}", script.mod().manifest().id, event, error.getMessage());
                reportToPlayer(player, script.mod(), event, error.getMessage());
            } catch (BridgeException error) {
                logger.error("Erro de plataforma no mod {} durante {}: {}", script.mod().manifest().id, event, error.getMessage());
                reportToPlayer(player, script.mod(), event, error.getMessage());
            } catch (RuntimeException error) {
                logger.error("Erro Java na ponte Lua do mod {} durante {}", script.mod().manifest().id, event, error);
            } finally {
                script.budget().stop();
            }
        }
        return cancelled;
    }

    private LoadedScript compile(ModLoader.LoadedMod mod) throws IOException {
        ExecutionBudget budget = new ExecutionBudget(CALLBACK_LIMIT_MILLIS);
        Globals globals = restrictedGlobals(budget);

        // Modulos ja carregados nesta compilacao, para um arquivo importado duas vezes rodar
        // uma vez so, como se espera de um sistema de modulos.
        Map<String, LuaValue> modules = new LinkedHashMap<>();
        java.util.Deque<String> loading = new java.util.ArrayDeque<>();
        Map<String, LuaFunction> callbacks = new LinkedHashMap<>();
        LuaTable modApi = createLogApi(mod.manifest().id);
        modApi.set("on", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue eventValue, LuaValue callbackValue) {
                String event = eventValue.tojstring();
                if (!EVENTS.contains(event)) {
                    throw new LuaError("evento desconhecido: " + event);
                }
                if (!callbackValue.isfunction()) {
                    throw new LuaError("callback de " + event + " precisa ser função");
                }
                callbacks.put(event, (LuaFunction) callbackValue);
                return LuaValue.NIL;
            }
        });
        // Tabela compartilhada por todos os scripts deste mod, preservada entre recargas.
        LuaTable state = states.computeIfAbsent(mod.manifest().id, key -> stateStore.load(key));
        modApi.set("state", state);

        registerManifestCameras(mod.manifest());
        registerManifestCommands(mod.manifest());

        // API de servidor com as permissoes deste mod, independente de quem chamar.
        modApi.set("server", serverApiFor(mod));

        modApi.set("import", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                String path = value.tojstring();
                if (!path.toLowerCase(java.util.Locale.ROOT).endsWith(".lua")) {
                    throw new LuaError("import exige um caminho .lua: " + path);
                }

                // Ja carregado: devolve o mesmo valor, sem executar de novo.
                LuaValue ready = modules.get(path);
                if (ready != null) return ready;

                if (loading.contains(path)) {
                    throw new LuaError("import circular em " + path
                            + " (cadeia: " + String.join(" -> ", loading) + ")");
                }
                if (modules.size() >= MAX_MODULES) {
                    throw new LuaError("limite de " + MAX_MODULES + " modulos por mod atingido");
                }

                loading.push(path);
                try {
                    LuaValue result = loadModule(mod, globals, path);
                    modules.put(path, result);
                    return result;
                } catch (IOException error) {
                    throw new LuaError(error.getMessage());
                } finally {
                    loading.pop();
                }
            }
        });

        modApi.set("menu", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.menu");
                if (args.narg() < 2) throw new LuaError("menu exige um id e uma funcao");

                String name = args.arg(1).tojstring();
                if (!name.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("id de menu invalido: " + name);
                }
                if (!args.arg(2).isfunction()) throw new LuaError("menu exige uma funcao");

                // O id publicado inclui o mod, para dois mods poderem usar o mesmo nome curto.
                String qualified = mod.manifest().id + ":" + name;
                menus.put(qualified, new RegisteredMenu(mod.manifest().id, (LuaFunction) args.arg(2)));
                return LuaValue.valueOf(qualified);
            }
        });

        modApi.set("screen", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.menu");
                if (args.narg() < 2) throw new LuaError("screen exige um id e uma funcao");

                String name = args.arg(1).tojstring();
                if (!name.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("id de tela invalido: " + name);
                }
                if (!args.arg(2).isfunction()) throw new LuaError("screen exige uma funcao");

                String qualified = mod.manifest().id + ":" + name;
                screens.put(qualified, new RegisteredMenu(mod.manifest().id, (LuaFunction) args.arg(2)));
                return LuaValue.valueOf(qualified);
            }
        });

        modApi.set("process", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                if (args.narg() < 2 || !args.arg(2).istable()) {
                    throw new LuaError("process exige um id e uma tabela de descricao");
                }

                String name = args.arg(1).tojstring();
                if (!name.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("id de processo invalido: " + name);
                }

                LuaTable definition = (LuaTable) args.arg(2);
                String qualified = mod.manifest().id + ":" + name;
                processes.put(qualified,
                        readProcess(mod.manifest().id, qualified, definition));
                return LuaValue.valueOf(qualified);
            }
        });

        modApi.set("command", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.command.register");
                if (args.narg() < 2) throw new LuaError("command exige nome e uma funcao");

                String name = args.arg(1).tojstring();
                if (!name.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("nome de comando invalido: " + name);
                }

                LuaFunction callback;
                CommandSchema schema = null;
                if (args.arg(2).istable()) {
                    if (args.narg() < 3 || !args.arg(3).isfunction()) {
                        throw new LuaError("command com schema exige uma funcao no terceiro argumento");
                    }
                    if (mod.manifest().requires == null
                            || mod.manifest().requires.capabilities == null
                            || !mod.manifest().requires.capabilities.containsKey("server.command.schema")) {
                        throw new LuaError("command com schema exige requires.capabilities.server.command.schema");
                    }
                    schema = readCommandSchema((LuaTable) args.arg(2));
                    callback = (LuaFunction) args.arg(3);
                } else {
                    if (!args.arg(2).isfunction()) throw new LuaError("command exige uma funcao");
                    callback = (LuaFunction) args.arg(2);
                }

                RegisteredCommand existing = commands.get(name);
                if (existing != null && !existing.modId().equals(mod.manifest().id)) {
                    throw new LuaError("comando " + name + " ja registrado pelo mod " + existing.modId());
                }
                if (existing != null && existing.schema() != null) {
                    if (schema != null && !existing.schema().equals(schema)) {
                        throw new LuaError("schema do comando " + name
                                + " difere do manifesto ou de outra declaracao");
                    }
                    if (schema == null) schema = existing.schema();
                }
                commands.put(name, new RegisteredCommand(mod.manifest().id, schema, callback));
                refreshCommandsIfRuntimeMutation();
                return LuaValue.NIL;
            }
        });

        modApi.set("command_extend", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.command.register");
                if (args.narg() < 2 || !args.arg(2).istable()) {
                    throw new LuaError("command_extend exige nome e um schema");
                }
                if (mod.manifest().requires == null
                        || mod.manifest().requires.capabilities == null
                        || !mod.manifest().requires.capabilities.containsKey("server.command.schema")) {
                    throw new LuaError("command_extend exige requires.capabilities.server.command.schema");
                }

                String name = args.arg(1).tojstring();
                if (!name.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("nome de comando invalido: " + name);
                }
                RegisteredCommand existing = commands.get(name);
                if (existing == null || existing.schema() == null
                        || !existing.modId().equals(mod.manifest().id)) {
                    throw new LuaError("command_extend exige um comando estruturado do proprio mod: " + name);
                }

                CommandSchema extension = readCommandSchema((LuaTable) args.arg(2));
                CommandSchema merged;
                try {
                    merged = existing.schema().merge(extension);
                } catch (IllegalArgumentException error) {
                    throw new LuaError("extensao do comando " + name + " invalida: " + error.getMessage());
                }
                commands.put(name, new RegisteredCommand(existing.modId(), merged, existing.callback()));
                refreshCommandsIfRuntimeMutation();
                return LuaValue.NIL;
            }
        });

        modApi.set("camera", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue idValue, LuaValue definitionValue) {
                requirePermission(mod.manifest(), "client.camera.register");
                if (mod.manifest().requires == null
                        || mod.manifest().requires.capabilities == null
                        || !mod.manifest().requires.capabilities.containsKey("client.camera.virtual")) {
                    throw new LuaError("camera exige requires.capabilities.client.camera.virtual");
                }
                if (!definitionValue.istable()) {
                    throw new LuaError("camera exige id e uma tabela de definição");
                }

                String id = idValue.tojstring();
                if (!id.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("id de câmera inválido: " + id);
                }
                CameraProtocol.Camera camera;
                try {
                    camera = cameraFromLua(mod.manifest().id, id, (LuaTable) definitionValue);
                } catch (IllegalArgumentException error) {
                    throw new LuaError("câmera " + id + " inválida: " + error.getMessage());
                }

                String qualified = camera.qualifiedId();
                RegisteredCamera existing = cameras.get(qualified);
                if (existing != null && !existing.modId().equals(mod.manifest().id)) {
                    throw new LuaError("câmera " + id + " já registrada pelo mod " + existing.modId());
                }
                if (existing != null && !existing.camera().equals(camera)) {
                    throw new LuaError("câmera " + id
                            + " diverge da definição já registrada no manifesto ou no Lua");
                }
                if (existing == null) {
                    cameras.put(qualified, new RegisteredCamera(mod.manifest().id, camera));
                    refreshCamerasIfRuntimeMutation();
                }
                return LuaValue.valueOf(qualified);
            }
        });

        modApi.set("keybind", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "client.input.register");
                if (args.narg() < 2) throw new LuaError("keybind exige id e uma funcao");

                String name = args.arg(1).tojstring();
                if (!name.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("id de keybind invalido: " + name);
                }
                if (!args.arg(2).isfunction()) throw new LuaError("keybind exige uma funcao");

                ModManifest.KeybindDefinition definition = null;
                if (mod.manifest().keybinds != null) {
                    for (ModManifest.KeybindDefinition candidate : mod.manifest().keybinds) {
                        if (candidate != null && name.equals(candidate.id)) {
                            definition = candidate;
                            break;
                        }
                    }
                }
                if (definition == null) {
                    throw new LuaError("keybind " + name + " nao foi declarada no manifesto");
                }

                String category = definition.category == null ? "keybinds" : definition.category;
                KeybindProtocol.Binding binding = new KeybindProtocol.Binding(
                        mod.manifest().id, name, definition.key, category, definition.modifiers);
                String qualified = binding.qualifiedId();
                RegisteredKeybind existing = keybinds.get(qualified);
                if (existing != null && !existing.modId().equals(mod.manifest().id)) {
                    throw new LuaError("keybind " + name + " ja registrada pelo mod " + existing.modId());
                }
                keybinds.put(qualified,
                        new RegisteredKeybind(mod.manifest().id, binding, (LuaFunction) args.arg(2)));
                return LuaValue.valueOf(qualified);
            }
        });

                modApi.set("after", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                if (args.narg() < 2) throw new LuaError("after exige ticks e uma funcao");
                int ticks = args.arg(1).checkint();
                if (ticks < 0 || ticks > 1_728_000) {
                    // 1.728.000 ticks equivalem a um dia real: alem disso e quase certamente erro.
                    throw new LuaError("ticks fora do intervalo permitido: " + ticks);
                }
                if (!args.arg(2).isfunction()) throw new LuaError("after exige uma funcao");
                scheduleTask(mod.manifest().id, ticks, 0, (LuaFunction) args.arg(2), actingPlayer);
                return LuaValue.NIL;
            }
        });
        modApi.set("every", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requireCapability(mod.manifest(), "scheduler.every");
                if (args.narg() < 2) throw new LuaError("every exige ticks e uma funcao");
                int ticks = args.arg(1).checkint();
                if (ticks < 1 || ticks > 1_728_000) {
                    throw new LuaError("intervalo fora do intervalo permitido: " + ticks);
                }
                if (!args.arg(2).isfunction()) throw new LuaError("every exige uma funcao");
                String id = scheduleTask(mod.manifest().id, ticks, ticks,
                        (LuaFunction) args.arg(2), actingPlayer);
                return LuaValue.valueOf(id);
            }
        });
        modApi.set("cancel", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                String id = value.checkjstring();
                String prefix = mod.manifest().id + ":task-";
                if (!id.startsWith(prefix)) return LuaValue.FALSE;
                if (id.equals(runningTaskId)) {
                    cancelledRunningTasks.add(id);
                    return LuaValue.TRUE;
                }
                boolean removed = scheduled.removeIf(task -> task.id().equals(id)
                        && task.modId().equals(mod.manifest().id));
                return LuaValue.valueOf(removed);
            }
        });


        modApi.set("require", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                String dependencyId = value.tojstring();
                try {
                    LoadedScript dependency = resolveDependency(mod, dependencyId);
                    if (dependency.exports() == null) {
                        throw new IOException("mod " + dependencyId + " nao exporta nada");
                    }
                    return dependency.exports();
                } catch (IOException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });

        globals.set("mod", modApi);

        LuaTable exported = null;

        // O entrypoint e opcional: um mod pode declarar apenas scripts por bloco no manifesto.
        if (mod.manifest().entrypoint != null && !mod.manifest().entrypoint.isBlank()) {
            // Os dois lados precisam ser absolutos antes de comparar. Resolver a partir do
            // diretorio como veio deixava um caminho relativo comparado com um absoluto, e a
            // comparacao falhava sempre -- so que o adaptador Fabric passa o diretorio ja absoluto
            // e escondia isso. Uma plataforma que passe relativo tranca todos os mods.
            Path root = mod.directory().toAbsolutePath().normalize();
            Path entrypoint = root.resolve(mod.manifest().entrypoint).normalize();
            if (!entrypoint.startsWith(root)) {
                throw new IOException("entrypoint Lua sai da pasta do mod");
            }

            // O script principal pode vir da base remota, como o modulo e o comportamento de bloco
            // ja vinham. Enquanto so ele exigia arquivo local, um mod publicado na web podia ter
            // tudo remoto menos o proprio comeco -- e a instalacao por um mod.json de poucas linhas
            // ficava a um arquivo de distancia de funcionar.
            String source;
            if (Files.isRegularFile(entrypoint)) {
                source = Files.readString(entrypoint, StandardCharsets.UTF_8);
            } else {
                byte[] bytes = new ManifestImports(mod.directory(), remoteCache)
                        .withRemoteBase(mod.manifest().remoteBase)
                        .readRelative(mod.manifest().entrypoint);
                if (bytes == null) {
                    throw new IOException("entrypoint nao encontrado: " + mod.manifest().entrypoint);
                }
                source = new String(bytes, StandardCharsets.UTF_8);
                logger.info("Entrypoint {} do mod {} veio da base remota",
                        mod.manifest().entrypoint, mod.manifest().id);
            }

            try {
                LuaValue chunk = globals.load(source, mod.manifest().id + "/" + mod.manifest().entrypoint);
                LuaValue returned = chunk.call();
                if (returned.istable()) {
                    exported = (LuaTable) returned;
                    for (Map.Entry<String, String> entry : mod.manifest().events.entrySet()) {
                        LuaValue callback = exported.get(entry.getValue());
                        if (callback.isfunction()) callbacks.put(entry.getKey(), (LuaFunction) callback);
                    }
                }
            } catch (LuaError error) {
                throw new IOException("erro ao executar Lua: " + error.getMessage(), error);
            }
        }

        for (Map.Entry<String, RegisteredCommand> entry : commands.entrySet()) {
            RegisteredCommand command = entry.getValue();
            if (command.modId().equals(mod.manifest().id)
                    && command.schema() != null && command.callback() == null) {
                throw new IOException("comando " + entry.getKey()
                        + " foi declarado no manifesto, mas nao recebeu callback Lua");
            }
        }

        return new LoadedScript(mod, Map.copyOf(callbacks),
                loadBlockHandlers(mod, globals, exported),
                loadItemHandlers(mod, globals, exported), exported, budget);
    }

    private void refreshCommandsIfRuntimeMutation() {
        if (compilationDepth == 0 && commandRefresh != null) commandRefresh.run();
    }

    private void refreshCamerasIfRuntimeMutation() {
        if (compilationDepth == 0 && cameraRefresh != null) cameraRefresh.run();
    }

    private static CameraProtocol.Camera cameraFromLua(String modId, String id, LuaTable source) {
        return new CameraProtocol.Camera(modId, id,
                cameraText(source.get("projection"), "orthographic"),
                cameraText(source.get("source"), "world"),
                cameraText(source.get("anchor"), "player"),
                cameraText(source.get("orientation"), "north"),
                cameraInt(source.get("resolution"), 96, 16, CameraProtocol.MAX_RESOLUTION),
                cameraInt(source.get("radius"), 48, 8, CameraProtocol.MAX_RADIUS),
                cameraInt(source.get("update_ticks"), 5, 1, CameraProtocol.MAX_UPDATE_TICKS),
                cameraText(source.get("output"), "texture"));
    }

    private static String cameraText(LuaValue value, String fallback) {
        return value == null || value.isnil() ? fallback : value.tojstring().trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int cameraInt(LuaValue value, int fallback, int minimum, int maximum) {
        int number = value == null || value.isnil() ? fallback : value.checkint();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("valor fora do limite [" + minimum + ", " + maximum + "]");
        }
        return number;
    }

    private void registerManifestCameras(ModManifest manifest) {
        if (manifest.cameras == null || manifest.cameras.isEmpty()) return;
        for (Map.Entry<String, ModManifest.CameraDefinition> entry : manifest.cameras.entrySet()) {
            String id = entry.getKey();
            ModManifest.CameraDefinition definition = entry.getValue();
            if (definition == null) {
                throw new IllegalArgumentException("câmera " + id + " inválida: definição nula");
            }
            CameraProtocol.Camera camera;
            try {
                camera = new CameraProtocol.Camera(manifest.id, id,
                        cameraValue(definition.projection, "orthographic"),
                        cameraValue(definition.source, "world"),
                        cameraValue(definition.anchor, "player"),
                        cameraValue(definition.orientation, "north"),
                        definition.resolution, definition.radius,
                        definition.updateTicks, cameraValue(definition.output, "texture"));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("câmera " + id
                        + " inválida: " + error.getMessage(), error);
            }
            String qualified = camera.qualifiedId();
            RegisteredCamera existing = cameras.get(qualified);
            if (existing != null && !existing.modId().equals(manifest.id)) {
                throw new IllegalArgumentException("câmera " + id
                        + " já registrada pelo mod " + existing.modId());
            }
            if (existing != null && !existing.camera().equals(camera)) {
                throw new IllegalArgumentException("câmera " + id
                        + " diverge da definição já registrada no manifesto ou no Lua");
            }
            if (existing == null) {
                cameras.put(qualified, new RegisteredCamera(manifest.id, camera));
            }
        }
    }

    private static String cameraValue(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private void registerManifestCommands(ModManifest manifest) {
        if (manifest.commands == null || manifest.commands.isEmpty()) return;
        for (Map.Entry<String, ModManifest.CommandDefinition> entry : manifest.commands.entrySet()) {
            String name = entry.getKey();
            try {
                CommandSchema schema = CommandSchema.fromManifest(entry.getValue());
                RegisteredCommand existing = commands.get(name);
                if (existing != null && !existing.modId().equals(manifest.id)) {
                    throw new IllegalArgumentException("comando " + name
                            + " ja registrado pelo mod " + existing.modId());
                }
                commands.put(name, new RegisteredCommand(manifest.id, schema, null));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("schema do comando " + name
                        + " invalido: " + error.getMessage(), error);
            }
        }
    }

    /**
     * Carrega a logica declarada por bloco no manifesto.
     *
     * <p>O JSON e o indice: cada bloco aponta qual codigo responde a cada evento. Um valor
     * terminado em {@code .lua} e um arquivo proprio, que deve devolver uma funcao; qualquer
     * outro texto e o nome de uma funcao exportada pelo entrypoint.
     *
     * @return handlers por identificador completo do bloco e nome do evento
     */
    private Map<String, Map<String, LuaFunction>> loadBlockHandlers(ModLoader.LoadedMod mod,
                                                                    Globals globals,
                                                                    LuaTable exported) throws IOException {
        Map<String, Map<String, LuaFunction>> handlers = new LinkedHashMap<>();
        if (mod.manifest().blocks == null) return handlers;

        Path root = mod.directory().toAbsolutePath().normalize();

        for (ModManifest.BlockDefinition block : mod.manifest().blocks) {
            if (block == null || block.id == null || block.behavior == null) continue;
            String blockId = mod.manifest().id + ":" + block.id;

            for (Map.Entry<String, String> entry : ModLoader.behaviorHandlers(block.behavior).entrySet()) {
                String event = entry.getKey();
                String reference = entry.getValue();
                if (reference == null || reference.isBlank()) continue;

                LuaFunction function;
                String lower = reference.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    function = loadHandlerRemote(mod, globals, reference, block.behaviorSha256);
                } else if (lower.endsWith(".lua")) {
                    function = loadHandlerFile(mod, globals, root, reference);
                } else {
                    if (exported == null) {
                        logger.warn("Bloco {} aponta a funcao {} para {}, mas o mod nao exporta nada",
                                blockId, reference, event);
                        continue;
                    }
                    LuaValue candidate = exported.get(reference);
                    if (!candidate.isfunction()) {
                        logger.warn("Bloco {} aponta {} para {}, mas o entrypoint nao exporta essa funcao",
                                blockId, reference, event);
                        continue;
                    }
                    function = (LuaFunction) candidate;
                }

                handlers.computeIfAbsent(blockId, key -> new LinkedHashMap<>()).put(event, function);
                logger.info("Bloco {} associou {} a {}", blockId, event, reference);
            }
        }
        return handlers;
    }

    /**
     * Compila um script de comportamento vindo da rede.
     *
     * <p>Isto e execucao de codigo baixado: quem controla o endereco decide o que roda no
     * servidor, dentro dos limites da API Lua. Sem {@code behavior_sha256} o script e buscado a
     * cada carga, entao o mod acompanha o que foi publicado; com o hash, fica fixo na versao
     * declarada. A carga registra em aviso qual endereco foi usado, para que a origem do codigo
     * nunca seja invisivel ao administrador.
     */
    private LuaFunction loadHandlerRemote(ModLoader.LoadedMod mod,
                                          Globals globals,
                                          String url,
                                          String expectedHash) throws IOException {
        if (remoteCache == null) {
            throw new IOException("script remoto desabilitado neste contexto: " + url);
        }

        byte[] bytes = new ManifestImports(mod.directory(), remoteCache).fetchRemote(url, expectedHash);
        String source = new String(bytes, StandardCharsets.UTF_8);

        logger.warn("Mod {} executa codigo remoto de {}{}", mod.manifest().id, url,
                expectedHash == null || expectedHash.isBlank() ? " (sem hash fixo)" : " (fixado por hash)");

        try {
            LuaValue chunk = globals.load(source, mod.manifest().id + "@" + url);
            LuaValue returned = chunk.call();
            if (!returned.isfunction()) {
                throw new IOException("script remoto precisa devolver uma funcao: " + url);
            }
            return (LuaFunction) returned;
        } catch (LuaError error) {
            throw new IOException("erro no script remoto " + url + ": " + error.getMessage(), error);
        }
    }

    /** Carrega a logica declarada por item, do mesmo modo que a dos blocos. */
    private Map<String, Map<String, LuaFunction>> loadItemHandlers(ModLoader.LoadedMod mod,
                                                                   Globals globals,
                                                                   LuaTable exported) throws IOException {
        Map<String, Map<String, LuaFunction>> handlers = new LinkedHashMap<>();
        if (mod.manifest().items == null) return handlers;

        Path root = mod.directory().toAbsolutePath().normalize();

        for (ModManifest.ItemEntryDefinition item : mod.manifest().items) {
            if (item == null || item.id == null || item.behavior == null) continue;
            String itemId = mod.manifest().id + ":" + item.id;

            Map<String, String> declared = new LinkedHashMap<>();
            if (item.behavior.onUse != null) declared.put("on_use", item.behavior.onUse);
            if (item.behavior.onUseOnBlock != null) declared.put("on_use_on_block", item.behavior.onUseOnBlock);

            for (Map.Entry<String, String> entry : declared.entrySet()) {
                String reference = entry.getValue();
                if (reference == null || reference.isBlank()) continue;

                LuaFunction function = resolveHandler(mod, globals, exported, root, reference,
                        item.behaviorSha256, itemId, entry.getKey());
                if (function == null) continue;

                handlers.computeIfAbsent(itemId, key -> new LinkedHashMap<>()).put(entry.getKey(), function);
                logger.info("Item {} associou {} a {}", itemId, entry.getKey(), reference);
            }
        }
        return handlers;
    }

    /** Resolve um handler declarado, seja arquivo, URL ou funcao exportada. */
    private LuaFunction resolveHandler(ModLoader.LoadedMod mod,
                                       Globals globals,
                                       LuaTable exported,
                                       Path root,
                                       String reference,
                                       String expectedHash,
                                       String ownerId,
                                       String event) throws IOException {
        String lower = reference.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return loadHandlerRemote(mod, globals, reference, expectedHash);
        }
        if (lower.endsWith(".lua")) {
            return loadHandlerFile(mod, globals, root, reference);
        }
        if (exported == null) {
            logger.warn("{} aponta a funcao {} para {}, mas o mod nao exporta nada",
                    ownerId, reference, event);
            return null;
        }
        LuaValue candidate = exported.get(reference);
        if (!candidate.isfunction()) {
            logger.warn("{} aponta {} para {}, mas o entrypoint nao exporta essa funcao",
                    ownerId, reference, event);
            return null;
        }
        return (LuaFunction) candidate;
    }

    /**
     * Carrega um arquivo Lua do proprio mod como modulo.
     *
     * <p>O {@code require} padrao do Lua fica fora do ambiente, porque procuraria arquivos em
     * qualquer lugar da maquina. Este import resolve o caminho dentro da pasta do mod, ou sob a
     * base remota quando ela existe, e compartilha os mesmos globais: um modulo enxerga
     * {@code mod.state} e a API do loader como qualquer outro script daquele mod.
     *
     * @return o valor devolvido pelo arquivo, ou {@code true} quando ele nao devolve nada
     */
    private LuaValue loadModule(ModLoader.LoadedMod mod, Globals globals, String path)
            throws IOException {
        Path root = mod.directory().toAbsolutePath().normalize();
        Path file = root.resolve(path).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("modulo sai da pasta do mod: " + path);
        }

        String textRenderer;
        if (Files.isRegularFile(file)) {
            textRenderer = Files.readString(file, StandardCharsets.UTF_8);
        } else {
            byte[] bytes = new ManifestImports(mod.directory(), remoteCache)
                    .withRemoteBase(mod.manifest().remoteBase)
                    .readRelative(path);
            if (bytes == null) throw new IOException("modulo nao encontrado: " + path);
            textRenderer = new String(bytes, StandardCharsets.UTF_8);
        }

        try {
            LuaValue chunk = globals.load(textRenderer, mod.manifest().id + "/" + path);
            LuaValue returned = chunk.call();
            // Um modulo que nao devolve nada ainda precisa marcar presenca no cache.
            return returned.isnil() ? LuaValue.TRUE : returned;
        } catch (LuaError error) {
            throw new IOException("erro no modulo " + path + ": " + error.getMessage(), error);
        }
    }

    /** Compila um arquivo de comportamento, que precisa devolver uma funcao. */
    private LuaFunction loadHandlerFile(ModLoader.LoadedMod mod,
                                        Globals globals,
                                        Path root,
                                        String reference) throws IOException {
        // Resolve a partir da raiz absoluta, e nao do diretorio como veio: comparar um caminho
        // relativo com um absoluto reprova sempre.
        Path script = root.resolve(reference).normalize();
        if (!script.startsWith(root)) {
            throw new IOException("script de comportamento sai da pasta do mod: " + reference);
        }

        String source;
        if (Files.isRegularFile(script)) {
            source = Files.readString(script, StandardCharsets.UTF_8);
        } else {
            // Um mod publicado na web declara caminhos relativos que so existem la; a base do
            // manifesto e o que permite instala-lo com um mod.json de poucas linhas.
            byte[] bytes = new ManifestImports(mod.directory(), remoteCache)
                    .withRemoteBase(mod.manifest().remoteBase)
                    .readRelative(reference);
            if (bytes == null) {
                throw new IOException("script de comportamento nao encontrado: " + reference);
            }
            source = new String(bytes, StandardCharsets.UTF_8);
            logger.info("Script {} do mod {} veio da base remota", reference, mod.manifest().id);
        }

        try {
            LuaValue chunk = globals.load(source, mod.manifest().id + "/" + reference);
            LuaValue returned = chunk.call();
            if (!returned.isfunction()) {
                throw new IOException("script de comportamento precisa devolver uma funcao: " + reference);
            }
            return (LuaFunction) returned;
        } catch (LuaError error) {
            throw new IOException("erro no script " + reference + ": " + error.getMessage(), error);
        }
    }

    /**
     * Conta a quem esta jogando que o script daquele mod falhou.
     *
     * <p><b>Por que isto existe.</b> Um erro de Lua num callback e registrado e nao propagado --
     * o que impede um mod quebrado de derrubar o jogo, e e a regra certa. Mas quem esta jogando so
     * ve o clique nao fazer nada: sem mensagem, um defeito de uma linha vira uma investigacao de
     * hipoteses. Aconteceu nesta sessao com uma cor no formato errado, que derrubava a tela inteira
     * e nao aparecia em lugar nenhum dentro do jogo.
     *
     * <p>A mensagem vai so para quem causou a acao, e nao para todo mundo: e a pessoa que clicou
     * quem precisa saber que o clique falhou.
     */
    private void reportToPlayer(PlayerHandle player, ModLoader.LoadedMod mod,
                                String event, String message) {
        if (player == null) return;

        try {
            player.sendMessage("[" + mod.manifest().id + "] falhou em " + event
                    + ": " + (message == null ? "erro sem mensagem" : message));
        } catch (RuntimeException ignored) {
            // Avisar sobre a falha nao pode virar outra falha. Se a plataforma nao aceita a
            // mensagem agora -- jogador saindo, conexao fechando --, o log ja tem o registro.
        }
    }

    /**
     * O sandbox do loader: tudo que nega {@code io}, {@code os} e afins.
     *
     * <p>Visivel ao pacote porque a fase de registro roda script com as mesmas restricoes. Uma
     * segunda copia da lista de negados envelheceria separada, e a fase que ficasse para tras seria
     * justamente a que executa antes de qualquer coisa existir.
     */
    static Globals restrictedGlobals(ExecutionBudget budget) {
        Globals globals = JsePlatform.standardGlobals();

        // A biblioteca de depuracao e carregada apenas para instalar o gancho de instrucoes; a
        // tabela debug e removida logo em seguida, entao o script nao a alcanca.
        globals.load(budget);

        String[] denied = {"io", "os", "package", "debug", "luajava", "require", "dofile", "loadfile", "load", "loadstring"};
        for (String name : denied) globals.set(name, LuaValue.NIL);
        return globals;
    }

    private LuaTable createLogApi(String modId) {
        LuaTable log = new LuaTable();
        log.set("info", logFunction(modId, false));
        log.set("warn", logFunction(modId, true));
        LuaTable api = new LuaTable();
        api.set("log", log);
        return api;
    }

    private OneArgFunction logFunction(String modId, boolean warning) {
        return new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                if (warning) logger.warn("[{}] {}", modId, value.tojstring());
                else logger.info("[{}] {}", modId, value.tojstring());
                return LuaValue.NIL;
            }
        };
    }

    /**
     * Procura a logica declarada no manifesto para o bloco do evento.
     *
     * <p>Os nomes de evento do manifesto sao os do conteudo ({@code on_use}), enquanto o runtime
     * usa os do loader ({@code block_used}); esta traducao mantem os dois vocabularios estaveis.
     */
    private static LuaFunction blockHandler(LoadedScript script, String event, BlockEventData block) {
        if (block == null) return null;
        Map<String, LuaFunction> handlers = script.blockHandlers().get(block.blockId());
        if (handlers == null) return null;

        LuaFunction handler = switch (event) {
            case "block_used" -> handlers.get("on_use");
            // on_break e o nome antigo de on_attack: descrevia bater no bloco, nao quebra-lo.
            case "block_attacked" -> handlers.getOrDefault("on_attack", handlers.get("on_break"));
            case "block_placed" -> handlers.get("on_placed");
            case "block_broken" -> handlers.get("on_broken");
            case "block_random_tick" -> handlers.get("on_random_tick");
            case "block_neighbor_update" -> handlers.get("on_neighbor_update");
            case "block_scheduled" -> handlers.get("on_scheduled");
            default -> null;
        };
        return handler;
    }

    /** Indica se o bloco do evento pertence ao mod, comparando o namespace com o id do mod. */
    private static boolean ownsBlock(ModLoader.LoadedMod mod, BlockEventData block) {
        return ownsId(mod, block.blockId());
    }

    /** Indica se o identificador pertence ao mod, comparando o namespace com o id do mod. */
    private static boolean ownsId(ModLoader.LoadedMod mod, String fullId) {
        int separator = fullId.indexOf(':');
        if (separator <= 0) return false;
        return fullId.substring(0, separator).equals(mod.manifest().id);
    }

    /**
     * Constroi a API de servidor amarrada a um mod.
     *
     * <p>As permissoes verificadas aqui sao sempre as do mod passado como argumento. E isso que faz
     * uma biblioteca rodar com os proprios poderes: ela usa {@code mod.server}, criado com o
     * manifesto dela, em vez do {@code ctx.server} de quem a chamou.
     */
    private LuaTable serverApiFor(ModLoader.LoadedMod mod) {
        LuaTable serverApi = new LuaTable();
        serverApi.set("broadcast", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "chat.send");
                bridge.broadcast(value.tojstring());
                return LuaValue.NIL;
            }
        });
        serverApi.set("players", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "server.read");
                LuaTable list = new LuaTable();
                int index = 1;
                for (String name : bridge.onlinePlayers()) list.set(index++, LuaValue.valueOf(name));
                return list;
            }
        });
        serverApi.set("time_of_day", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "server.read");
                return LuaValue.valueOf(bridge.timeOfDay());
            }
        });
        serverApi.set("world_name", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "server.read");
                return LuaValue.valueOf(bridge.worldName());
            }
        });
        serverApi.set("game_rule", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "world.read");
                return LuaValue.valueOf(bridge.gameRule(requireRuleName(value)));
            }
        });
        serverApi.set("set_game_rule", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue name, LuaValue value) {
                requirePermission(mod.manifest(), "world.write");
                bridge.setGameRule(requireRuleName(name), requireRuleValue(value));
                return LuaValue.NIL;
            }
        });
        serverApi.set("difficulty", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "world.read");
                return LuaValue.valueOf(bridge.difficulty());
            }
        });
        serverApi.set("set_difficulty", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "world.write");
                String difficulty = value.tojstring().toLowerCase(java.util.Locale.ROOT);
                if (!Set.of("peaceful", "easy", "normal", "hard").contains(difficulty)) {
                    throw new LuaError("dificuldade deve ser peaceful, easy, normal ou hard");
                }
                bridge.setDifficulty(difficulty);
                return LuaValue.NIL;
            }
        });
        serverApi.set("items", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");

                return registryQuery(args, bridge::registeredItems);
            }
        });
        serverApi.set("set_time_of_day", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "world.write");
                bridge.setTimeOfDay(value.checklong());
                return LuaValue.NIL;
            }
        });
        serverApi.set("weather", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "world.read");
                return LuaValue.valueOf(bridge.weather());
            }
        });
        serverApi.set("set_weather", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                String weather = args.arg(1).tojstring();
                if (!Set.of("clear", "rain", "thunder").contains(weather)) {
                    throw new LuaError("clima deve ser clear, rain ou thunder; veio " + weather);
                }
                bridge.setWeather(weather, args.narg() < 2 ? 0 : args.arg(2).toint());
                return LuaValue.NIL;
            }
        });
        serverApi.set("top_y", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 2) throw new LuaError("top_y exige x e z");
                return LuaValue.valueOf(bridge.topY(
                        requireCoordinate(args.arg(1)), requireCoordinate(args.arg(2))));
            }
        });
        serverApi.set("break_block", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 3) throw new LuaError("break_block exige x, y e z");
                // Solta o que o bloco dropa por padrao: e o que "quebrar" significa no jogo, e
                // apagar em silencio seria a escolha surpreendente.
                boolean drop = args.narg() < 4 || args.arg(4).toboolean();
                return LuaValue.valueOf(bridge.breakBlock(
                        requireCoordinate(args.arg(1)), requireCoordinate(args.arg(2)),
                        requireCoordinate(args.arg(3)), drop));
            }
        });
        serverApi.set("heal_entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.modify");
                if (args.narg() < 2) throw new LuaError("heal_entity exige uuid e quantidade");
                float amount = (float) args.arg(2).checkdouble();
                if (amount < 0) throw new LuaError("a cura nao pode ser negativa");
                return LuaValue.valueOf(bridge.healEntity(args.arg(1).tojstring(), amount));
            }
        });
        // O bioma numa posicao.
        //
        // Sem isto, um mod que gera algo condicionalmente nao tinha como perguntar onde esta: um
        // altar que so faz sentido no deserto precisava adivinhar pela altura ou pelo bloco de
        // baixo, e as duas coisas mentem -- areia tambem existe em praia, e altura nao diz bioma.
        serverApi.set("biome_at", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 3) throw new LuaError("biome_at exige x, y e z");
                return LuaValue.valueOf(bridge.biomeAt(
                        args.arg(1).checkint(), args.arg(2).checkint(), args.arg(3).checkint()));
            }
        });
        // A luz numa posicao, separada por origem.
        //
        // Devolve as duas, e nao so o total, porque a distincao e a que decide se um monstro nasce
        // ali: o jogo olha a luz de bloco. Um lugar iluminado so pelo sol tem quinze de total ao
        // meio-dia e continua sendo escuro a noite, e um mod que olhasse o total erraria todo dia.
        serverApi.set("light_at", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 3) throw new LuaError("light_at exige x, y e z");

                int x = args.arg(1).checkint();
                int y = args.arg(2).checkint();
                int z = args.arg(3).checkint();

                int block = bridge.lightAt(x, y, z, false);
                int sky = bridge.lightAt(x, y, z, true);

                LuaTable light = new LuaTable();
                light.set("block", LuaValue.valueOf(block));
                light.set("sky", LuaValue.valueOf(sky));
                light.set("total", LuaValue.valueOf(Math.max(block, sky)));
                // A pergunta que quase todo mod faz depois de ler a luz, respondida uma vez so em
                // vez de reimplementada com o limiar errado em cada mod.
                light.set("dark_enough_for_monster", LuaValue.valueOf(block == 0));
                return light;
            }
        });
        serverApi.set("teleport_entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.modify");
                if (args.narg() < 4) {
                    throw new LuaError("teleport_entity exige uuid, x, y e z");
                }
                return LuaValue.valueOf(bridge.teleportEntity(args.arg(1).tojstring(),
                        args.arg(2).checkdouble(), args.arg(3).checkdouble(),
                        args.arg(4).checkdouble()));
            }
        });
        serverApi.set("push_entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.modify");
                if (args.narg() < 4) {
                    throw new LuaError("push_entity exige uuid e o empurrao em x, y e z");
                }
                return LuaValue.valueOf(bridge.pushEntity(args.arg(1).tojstring(),
                        args.arg(2).checkdouble(), args.arg(3).checkdouble(),
                        args.arg(4).checkdouble()));
            }
        });
        serverApi.set("apply_to_entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.modify");
                if (args.narg() < 2) throw new LuaError("apply_to_entity exige uuid e dados");
                return LuaValue.valueOf(bridge.applyToEntity(
                        args.arg(1).tojstring(), readEntitySpec(args.arg(2))));
            }
        });
        serverApi.set("entity_info", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "entity.read");
                String line = bridge.entityInfo(value.tojstring());
                if (line == null) return LuaValue.NIL;

                String[] parts = line.split(";", 8);
                if (parts.length < 7) return LuaValue.NIL;

                LuaTable info = new LuaTable();
                info.set("uuid", LuaValue.valueOf(parts[0]));
                info.set("type", LuaValue.valueOf(parts[1]));
                info.set("x", LuaValue.valueOf(Double.parseDouble(parts[2])));
                info.set("y", LuaValue.valueOf(Double.parseDouble(parts[3])));
                info.set("z", LuaValue.valueOf(Double.parseDouble(parts[4])));
                info.set("health", LuaValue.valueOf(Double.parseDouble(parts[5])));
                info.set("max_health", LuaValue.valueOf(Double.parseDouble(parts[6])));
                if (parts.length > 7 && !parts[7].isEmpty()) {
                    info.set("name", LuaValue.valueOf(parts[7]));
                }
                return info;
            }
        });
        serverApi.set("blocks", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                return registryQuery(args, bridge::registeredBlocks);
            }
        });
        serverApi.set("entity_types", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                return registryQuery(args, bridge::registeredEntities);
            }
        });
        // As especies que os mods declararam, e nao as milhares do jogo.
        //
        // E a metade de leitura do bestiario compartilhado: um mod consegue descobrir o que outro
        // declarou, e a partir dai estender aquilo no proprio manifesto. Sem ela, "registrar bicho
        // de fora" so serviria a quem ja sabia de cor o id do bicho do vizinho.
        //
        // <b>Nao existe o par que escreve.</b> Registrar especie em tempo de execucao funcionaria
        // no Fabric, onde o Lua carrega antes de o jogo congelar os registros, e falharia sempre no
        // NeoForge, onde ele carrega depois. Uma funcao que so vale numa plataforma e pior que
        // funcao nenhuma: o mod passa nos testes de quem escreveu e some para metade de quem usa.
        // O caminho que vale nas duas e declarar em "entities", com "base" apontando para a
        // especie do outro mod.
        serverApi.set("declared_entities", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.read");

                LuaTable list = new LuaTable();
                int index = 1;
                for (String id : bridge.declaredEntities()) {
                    list.set(index++, LuaValue.valueOf(id));
                }
                return list;
            }
        });
        // O que foi declarado para uma especie deste loader.
        //
        // Devolve nil para o que nao e daqui -- inclusive para uma especie do jogo. A diferenca
        // importa: "nao existe" e "existe e nao e declarada" levam a decisoes diferentes em quem
        // esta montando um bestiario em cima do de outro mod.
        serverApi.set("entity_definition", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.read");

                String id = args.arg(1).tojstring();
                dev.lualoader.platform.EntityDefinition definition = bridge.declaredEntity(id);
                if (definition == null) return LuaValue.NIL;

                LuaTable table = new LuaTable();
                table.set("id", LuaValue.valueOf(id));
                if (definition.name != null) {
                    table.set("name", LuaValue.valueOf(definition.name));
                }
                if (definition.base != null) {
                    table.set("base", LuaValue.valueOf(definition.base));
                }
                if (definition.category != null) {
                    table.set("category", LuaValue.valueOf(definition.category));
                }
                table.set("width", LuaValue.valueOf(definition.width));
                table.set("height", LuaValue.valueOf(definition.height));
                table.set("fire_immune", LuaValue.valueOf(definition.fireImmune));
                if (definition.defaults != null && definition.defaults.health != null) {
                    table.set("health", LuaValue.valueOf(definition.defaults.health));
                }
                if (definition.texture != null) {
                    table.set("texture", LuaValue.valueOf(definition.texture));
                }
                if (definition.model != null) {
                    table.set("model", LuaValue.valueOf(definition.model));
                }

                // A regra de nascimento e devolvida inteira, e nao so um "nasce sozinho": um mod
                // que monta um guia do bestiario precisa dizer onde procurar, e nao so que da.
                if (definition.spawn != null) {
                    LuaTable spawn = new LuaTable();
                    LuaTable biomes = new LuaTable();
                    int biomeIndex = 1;
                    for (String biome : definition.spawn.biomes) {
                        biomes.set(biomeIndex++, LuaValue.valueOf(biome));
                    }
                    spawn.set("biomes", biomes);
                    spawn.set("weight", LuaValue.valueOf(definition.spawn.weight));
                    spawn.set("min_group", LuaValue.valueOf(definition.spawn.minGroup));
                    spawn.set("max_group", LuaValue.valueOf(definition.spawn.maxGroup));
                    spawn.set("min_light", LuaValue.valueOf(definition.spawn.minLight));
                    spawn.set("max_light", LuaValue.valueOf(definition.spawn.maxLight));
                    if (definition.spawn.minY != null) {
                        spawn.set("min_y", LuaValue.valueOf(definition.spawn.minY));
                    }
                    if (definition.spawn.maxY != null) {
                        spawn.set("max_y", LuaValue.valueOf(definition.spawn.maxY));
                    }
                    table.set("spawn", spawn);
                }

                // Quantas metas e alvos, e nao a lista inteira: um mod que monta um guia quer
                // dizer "tem comportamento proprio", e reproduzir o vocabulario aqui daria uma
                // segunda copia dele para envelhecer junto.
                if (definition.ai != null) {
                    LuaTable ai = new LuaTable();
                    ai.set("clear", LuaValue.valueOf(definition.ai.clear));
                    ai.set("goals", LuaValue.valueOf(definition.ai.goals.size()));
                    ai.set("targets", LuaValue.valueOf(definition.ai.targets.size()));
                    table.set("ai", ai);
                }
                return table;
            }
        });
        // A lista dos mods do loader.
        //
        // O gerenciador de mods da plataforma -- o Mod Menu do Fabric, a lista do NeoForge --
        // enxerga um mod so: o proprio loader. Os mods Lua vivem dentro dele e sao invisiveis la,
        // entao quem joga nao tem como saber o que esta instalado, em que versao, nem por que algo
        // parou de funcionar. Esta funcao e o que permite a um mod montar essa lista.
        //
        // Devolve o manifesto, e nao o estado interno do runtime: e a mesma informacao que quem
        // escreveu o mod declarou, e nao um retrato de como o loader a interpretou.
        serverApi.set("mods", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");

                LuaTable list = new LuaTable();
                int index = 1;
                for (LoadedScript script : List.copyOf(scripts.values())) {
                    ModManifest manifest = script.mod().manifest();

                    LuaTable entry = new LuaTable();
                    entry.set("id", LuaValue.valueOf(manifest.id));
                    entry.set("name", LuaValue.valueOf(
                            manifest.name == null ? manifest.id : manifest.name));
                    entry.set("version", LuaValue.valueOf(
                            manifest.version == null ? "" : manifest.version));
                    entry.set("description", LuaValue.valueOf(
                            manifest.description == null ? "" : manifest.description));
                    entry.set("enabled", LuaValue.valueOf(manifest.enabled));
                    // Quem precisa ter o mod: "server" atravessa a rede como dados e funciona para
                    // quem entrou sem baixar nada; "both" registra conteudo e exige instalacao dos
                    // dois lados.
                    entry.set("side", LuaValue.valueOf(manifest.effectiveSide()));
                    entry.set("requires_client", LuaValue.valueOf(manifest.requiresClient()));

                    entry.set("authors", toLuaList(manifest.authors));
                    entry.set("permissions", toLuaList(manifest.permissions));

                    // As contagens, e nao as listas: um mod de catalogo ja pode perguntar o resto
                    // por blocks() e items(), e uma tela precisa de um numero para caber na linha.
                    entry.set("blocks", LuaValue.valueOf(
                            manifest.blocks == null ? 0 : manifest.blocks.size()));
                    entry.set("items", LuaValue.valueOf(
                            manifest.items == null ? 0 : manifest.items.size()));
                    entry.set("recipes", LuaValue.valueOf(
                            manifest.recipes == null ? 0 : manifest.recipes.size()));
                    entry.set("events", LuaValue.valueOf(
                            manifest.events == null ? 0 : manifest.events.size()));

                    list.set(index++, entry);
                }
                return list;
            }
        });

        serverApi.set("processes", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");

                String produces = null;
                String uses = null;
                String station = null;
                int limit = 64;

                if (args.arg(1).istable()) {
                    LuaTable options = (LuaTable) args.arg(1);
                    if (!options.get("produces").isnil()) produces = options.get("produces").tojstring();
                    if (!options.get("uses").isnil()) uses = options.get("uses").tojstring();
                    if (!options.get("by").isnil()) station = options.get("by").tojstring();
                    if (!options.get("limit").isnil()) {
                        limit = options.get("limit").checkint();
                        if (limit < 1 || limit > 512) throw new LuaError("limit deve estar entre 1 e 512");
                    }
                } else if (!args.arg(1).isnil()) {
                    throw new LuaError("processes aceita uma tabela de filtros");
                }

                LuaTable list = new LuaTable();
                int index = 1;
                for (RegisteredProcess process : processes.values()) {
                    if (index > limit) break;
                    if (produces != null && !process.outputItem().equals(produces)) continue;
                    if (station != null && !station.equals(process.station())) continue;
                    if (uses != null && !process.inputs().contains(uses)) continue;

                    list.set(index++, process.toLua());
                }
                return list;
            }
        });

        serverApi.set("capabilities_at", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 3) throw new LuaError("capabilities_at exige x, y e z");

                LuaTable list = new LuaTable();
                int index = 1;
                for (String capability : bridge.capabilitiesAt(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3)))) {
                    list.set(index++, LuaValue.valueOf(capability));
                }
                return list;
            }
        });
        serverApi.set("container_at", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.containers");
                if (args.narg() < 3) throw new LuaError("container_at exige x, y e z");

                LuaTable list = new LuaTable();
                int index = 1;
                for (String line : bridge.containerAt(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3)))) {
                    String[] parts = line.split(";");
                    if (parts.length < 3) continue;

                    LuaTable entry = new LuaTable();
                    entry.set("slot", LuaValue.valueOf(Integer.parseInt(parts[0])));
                    entry.set("item", LuaValue.valueOf(parts[1]));
                    entry.set("count", LuaValue.valueOf(Integer.parseInt(parts[2])));
                    list.set(index++, entry);
                }
                return list;
            }
        });
        // Onde cada slot daquela maquina aparece na tela DELA.
        // Uma fornalha desenha os tres slots em L; um moedor de outro mod desenha do jeito dele.
        // Listar em fileira funciona e nao se parece com nada -- o jogador reconhece a maquina pela
        // forma, e uma tela de configuracao sem a forma dela obriga a contar slots.
        //
        // Vazia quando o bloco nao tem menu proprio, e ai quem chamou desenha como puder.
        serverApi.set("container_slot_layout", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.containers");
                if (args.narg() < 3) throw new LuaError("container_slot_layout exige x, y e z");

                LuaTable lista = new LuaTable();
                int indice = 1;
                for (String linha : bridge.containerSlotLayout(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3)))) {

                    String[] partes = linha.split(";", 3);
                    if (partes.length < 3) continue;

                    LuaTable entrada = new LuaTable();
                    entrada.set("slot", LuaValue.valueOf(Integer.parseInt(partes[0])));
                    entrada.set("x", LuaValue.valueOf(Integer.parseInt(partes[1])));
                    entrada.set("y", LuaValue.valueOf(Integer.parseInt(partes[2])));
                    lista.set(indice++, entrada);
                }
                return lista;
            }
        });
        // Quantos slots aquela maquina tem, contando os vazios.
        //
        // `container_at` devolve so o que tem item: uma fornalha com a saida vazia parece ter dois
        // slots, e o terceiro -- justamente o que interessa -- e invisivel. Sem este numero, um mod
        // nao tem como oferecer "estes sao os slots desta maquina, diga qual e entrada e qual e
        // saida", que e o unico jeito honesto de falar com uma maquina que o loader nao conhece.
        serverApi.set("container_size", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.containers");
                if (args.narg() < 3) throw new LuaError("container_size exige x, y e z");

                return LuaValue.valueOf(bridge.containerSize(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3))));
            }
        });
        // Desenha um slot, trocando o que estiver la. `insert_into` acrescenta e respeita o portao
        // de maquina; isto substitui e passa por cima dele, que e o que um inventario fantasma
        // precisa -- ele recusa funil e cano justamente para ninguem apagar o desenho.
        serverApi.set("set_slot", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.containers");
                if (args.narg() < 4) {
                    throw new LuaError("set_slot exige x, y, z e slot");
                }

                // Item vazio ou quantidade zero limpam o slot: e o gesto de apagar, e exigir uma
                // operacao separada para isso daria duas formas de dizer a mesma coisa.
                String item = args.narg() >= 5 && !args.arg(5).isnil()
                        ? requireIdentifier(args.arg(5).tojstring())
                        : "";
                int count = args.narg() >= 6 && !args.arg(6).isnil() ? args.arg(6).checkint() : 1;

                bridge.setSlot(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3)),
                        args.arg(4).checkint(), item, count);
                return LuaValue.NIL;
            }
        });
        serverApi.set("insert_into", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.containers");
                if (args.narg() < 5) {
                    throw new LuaError("insert_into exige x, y, z, item e quantidade");
                }
                // O sexto argumento e o slot, opcional: sem ele o item vai para qualquer lugar
                // do inventario, que e como a operacao sempre funcionou. Com ele, o script
                // enderreca o slot que container_at ja numerava e nao dava para alcancar.
                int slot = args.narg() >= 6 && !args.arg(6).isnil()
                        ? args.arg(6).checkint()
                        : -1;
                if (slot < -1) throw new LuaError("insert_into: slot nao pode ser negativo");

                return LuaValue.valueOf(bridge.insertIntoSlot(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3)),
                        slot,
                        requireIdentifier(args.arg(4).tojstring()),
                        requireCount(args.arg(5))));
            }
        });
        serverApi.set("extract_from", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.containers");
                if (args.narg() < 5) {
                    throw new LuaError("extract_from exige x, y, z, item e quantidade");
                }
                // O sexto argumento e o slot, opcional: sem ele o item vai para qualquer lugar
                // do inventario, que e como a operacao sempre funcionou. Com ele, o script
                // enderreca o slot que container_at ja numerava e nao dava para alcancar.
                int slot = args.narg() >= 6 && !args.arg(6).isnil()
                        ? args.arg(6).checkint()
                        : -1;
                if (slot < -1) throw new LuaError("extract_from: slot nao pode ser negativo");

                return LuaValue.valueOf(bridge.extractFromSlot(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3)),
                        slot,
                        requireIdentifier(args.arg(4).tojstring()),
                        requireCount(args.arg(5))));
            }
        });
        serverApi.set("drops_of", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                if (args.narg() < 1) throw new LuaError("drops_of exige um bloco");
                return stringList(bridge.dropsOf(
                        requireIdentifier(args.arg(1).tojstring()), recipeLimit(args.arg(2))));
            }
        });
        serverApi.set("dropped_by", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                if (args.narg() < 1) throw new LuaError("dropped_by exige um item");
                return stringList(bridge.droppedBy(
                        requireIdentifier(args.arg(1).tojstring()), recipeLimit(args.arg(2))));
            }
        });
        serverApi.set("recipes_for", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                if (args.narg() < 1) throw new LuaError("recipes_for exige um item");
                return recipeList(bridge.recipesFor(
                        requireIdentifier(args.arg(1).tojstring()), recipeLimit(args.arg(2))));
            }
        });
        serverApi.set("recipes_using", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                if (args.narg() < 1) throw new LuaError("recipes_using exige um item");
                return recipeList(bridge.recipesUsing(
                        requireIdentifier(args.arg(1).tojstring()), recipeLimit(args.arg(2))));
            }
        });
        // O que sai de um arranjo de nove slots -- a pergunta que o jogador faz ao montar na
        // bancada. As outras duas operacoes de receita respondem pelo resultado ("como faco X?"), e
        // nenhuma delas serve a um cano de fabricacao, que tem o padrao e quer saber o produto.
        serverApi.set("crafting_result", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                LuaValue tabela = args.arg(1);
                if (!tabela.istable()) throw new LuaError("crafting_result exige uma tabela de itens");

                java.util.List<String> slots = new java.util.ArrayList<>(9);
                for (int slot = 1; slot <= 9; slot++) {
                    LuaValue valor = tabela.get(slot);
                    // Nil e cadeia vazia sao a mesma coisa: a posicao vazia. Exigir a distincao
                    // faria toda tela ter que preencher os nove buracos com "".
                    slots.add(valor.isnil() ? "" : valor.tojstring().trim());
                }

                String resultado = bridge.craftingResult(slots);
                if (resultado == null) return LuaValue.NIL;

                int corte = resultado.lastIndexOf(';');
                LuaTable saida = new LuaTable();
                saida.set("item", LuaValue.valueOf(resultado.substring(0, corte)));
                saida.set("count", LuaValue.valueOf(Integer.parseInt(resultado.substring(corte + 1))));
                return saida;
            }
        });
        // Por quantos tiques um item queima. Quem responde e o jogo, com o mapa que a fornalha
        // usa -- inclusive o combustivel que outro mod registrou. Uma tabela escrita no mod
        // nasceria errada no primeiro modpack.
        serverApi.set("fuel_burn_time", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.read");
                if (args.narg() < 1) throw new LuaError("fuel_burn_time exige o id de um item");
                String item = requireIdentifier(args.arg(1).tojstring());
                return LuaValue.valueOf(bridge.fuelBurnTime(item));
            }
        });
        serverApi.set("spawn_entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.spawn");
                if (args.narg() < 4) throw new LuaError("spawn_entity exige id, x, y e z");
                String id = requireIdentifier(args.arg(1).tojstring());
                return LuaValue.valueOf(bridge.spawnEntity(id,
                        requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)),
                        requireCoordinate(args.arg(4)),
                        readEntitySpec(args.arg(5))));
            }
        });
        serverApi.set("drop_item", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.spawn");
                if (args.narg() < 5) {
                    throw new LuaError("drop_item exige item, x, y, z e quantidade");
                }
                String id = requireIdentifier(args.arg(1).tojstring());
                int count = args.arg(5).checkint();
                if (count < 1 || count > 4096) {
                    throw new LuaError("quantidade de drop_item deve estar entre 1 e 4096");
                }
                return LuaValue.valueOf(bridge.dropItem(id,
                        requireCoordinateDouble(args.arg(2)), requireCoordinateDouble(args.arg(3)),
                        requireCoordinateDouble(args.arg(4)), count));
            }
        });
        serverApi.set("entities_near", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.read");
                if (args.narg() < 4) throw new LuaError("entities_near exige x, y, z e raio");
                double radius = args.arg(4).checkdouble();
                if (radius <= 0 || radius > 64) throw new LuaError("raio deve estar entre 0 e 64");

                LuaTable list = new LuaTable();
                int index = 1;
                for (String line : bridge.entitiesNear(requireCoordinate(args.arg(1)),
                        requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)), radius)) {
                    String[] parts = line.split(";");
                    if (parts.length < 5) continue;
                    LuaTable entity = new LuaTable();
                    entity.set("uuid", LuaValue.valueOf(parts[0]));
                    entity.set("type", LuaValue.valueOf(parts[1]));
                    entity.set("x", LuaValue.valueOf(Integer.parseInt(parts[2])));
                    entity.set("y", LuaValue.valueOf(Integer.parseInt(parts[3])));
                    entity.set("z", LuaValue.valueOf(Integer.parseInt(parts[4])));
                    list.set(index++, entity);
                }
                return list;
            }
        });
        serverApi.set("remove_entity", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "entity.modify");
                return LuaValue.valueOf(bridge.removeEntity(value.tojstring()));
            }
        });
        serverApi.set("damage_entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.modify");
                if (args.narg() < 2) throw new LuaError("damage_entity exige uuid e quantidade");
                float amount = (float) args.arg(2).checkdouble();
                if (amount <= 0 || amount > 1024) throw new LuaError("dano deve estar entre 0 e 1024");
                return LuaValue.valueOf(bridge.damageEntity(args.arg(1).tojstring(), amount));
            }
        });
        serverApi.set("get_block_data", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 3) throw new LuaError("get_block_data exige x, y e z");
                String json = bridge.getBlockData(requireCoordinate(args.arg(1)),
                        requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)));
                return stateStore.fromJsonText(json);
            }
        });
        serverApi.set("set_block_data", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 4 || !args.arg(4).istable()) {
                    throw new LuaError("set_block_data exige x, y, z e uma tabela");
                }
                String json = stateStore.toJsonText(mod.manifest().id, (LuaTable) args.arg(4));
                bridge.setBlockData(requireCoordinate(args.arg(1)), requireCoordinate(args.arg(2)),
                        requireCoordinate(args.arg(3)), json);
                return LuaValue.NIL;
            }
        });
        serverApi.set("play_sound", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 4) throw new LuaError("play_sound exige id, x, y e z");
                String id = requireIdentifier(args.arg(1).tojstring());
                float volume = args.narg() >= 5 ? (float) args.arg(5).checkdouble() : 1.0f;
                float pitch = args.narg() >= 6 ? (float) args.arg(6).checkdouble() : 1.0f;
                if (volume < 0 || volume > 10) throw new LuaError("volume deve estar entre 0 e 10");
                if (pitch < 0.5 || pitch > 2.0) throw new LuaError("pitch deve estar entre 0.5 e 2.0");

                // O setimo argumento e a categoria, opcional: e o que permite ao jogador baixar
                // o volume dos sons do mod sem silenciar o jogo inteiro.
                String category = args.narg() >= 7 && !args.arg(7).isnil()
                        ? args.arg(7).tojstring()
                        : null;
                if (category != null
                        && !dev.lualoader.platform.GameBridge.SOUND_CATEGORIES.contains(category)) {
                    throw new LuaError("categoria de som desconhecida: " + category
                            + "; conhecidas: " + dev.lualoader.platform.GameBridge.SOUND_CATEGORIES);
                }

                bridge.playSound(id, requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)),
                        requireCoordinate(args.arg(4)), volume, pitch, category);
                return LuaValue.NIL;
            }
        });
        serverApi.set("spawn_particles", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 4) throw new LuaError("spawn_particles exige id, x, y e z");
                String id = requireIdentifier(args.arg(1).tojstring());
                int count = args.narg() >= 5 ? args.arg(5).checkint() : 8;
                double spread = args.narg() >= 6 ? args.arg(6).checkdouble() : 0.5;
                if (count < 1 || count > 512) throw new LuaError("count deve estar entre 1 e 512");
                if (spread < 0 || spread > 16) throw new LuaError("spread deve estar entre 0 e 16");

                // O setimo argumento e a velocidade, opcional: sem ele a particula so aparece,
                // que e como a operacao sempre funcionou. Com ele, ela e lancada.
                double speed = args.narg() >= 7 ? args.arg(7).checkdouble() : 0.0;
                if (speed < 0 || speed > 4) throw new LuaError("speed deve estar entre 0 e 4");

                bridge.spawnParticles(id, requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)),
                        requireCoordinate(args.arg(4)), count, spread, speed);
                return LuaValue.NIL;
            }
        });
        serverApi.set("get_block", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 3) throw new LuaError("get_block exige x, y e z");
                int x = requireCoordinate(args.arg(1));
                int y = requireCoordinate(args.arg(2));
                int z = requireCoordinate(args.arg(3));
                return LuaValue.valueOf(bridge.getBlock(x, y, z));
            }
        });
        serverApi.set("block_state", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 3) throw new LuaError("block_state exige x, y e z");
                var snapshot = bridge.blockState(
                        requireCoordinate(args.arg(1)),
                        requireCoordinate(args.arg(2)),
                        requireCoordinate(args.arg(3)));
                LuaTable result = new LuaTable();
                result.set("id", LuaValue.valueOf(snapshot.id));
                LuaTable properties = new LuaTable();
                for (var property : snapshot.properties.entrySet()) {
                    properties.set(property.getKey(), LuaValue.valueOf(property.getValue()));
                }
                result.set("properties", properties);
                return result;
            }
        });
        serverApi.set("set_block_state", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 4 || !args.arg(4).istable()) {
                    throw new LuaError("set_block_state exige x, y, z e uma tabela de propriedades");
                }
                LuaTable properties = args.arg(4).checktable();
                Map<String, String> values = new LinkedHashMap<>();
                for (LuaValue key : properties.keys()) {
                    if (!key.isstring() || !properties.get(key).isstring()) {
                        throw new LuaError("propriedades de bloco precisam mapear texto para texto");
                    }
                    values.put(key.tojstring(), properties.get(key).tojstring());
                }
                return LuaValue.valueOf(bridge.setBlockState(
                        requireCoordinate(args.arg(1)),
                        requireCoordinate(args.arg(2)),
                        requireCoordinate(args.arg(3)), values));
            }
        });
        serverApi.set("set_block", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 4) throw new LuaError("set_block exige id, x, y e z");
                String id = requireIdentifier(args.arg(1).tojstring());
                int x = requireCoordinate(args.arg(2));
                int y = requireCoordinate(args.arg(3));
                int z = requireCoordinate(args.arg(4));
                bridge.setBlock(id, x, y, z);
                return LuaValue.NIL;
            }
        });
        serverApi.set("redstone_signal", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.read");
                if (args.narg() < 3) {
                    throw new LuaError("redstone_signal exige x, y e z");
                }
                return LuaValue.valueOf(bridge.redstoneSignal(
                        requireCoordinate(args.arg(1)),
                        requireCoordinate(args.arg(2)),
                        requireCoordinate(args.arg(3))));
            }
        });
        serverApi.set("schedule_block", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 4) {
                    throw new LuaError("schedule_block exige x, y, z e o numero de tiques");
                }
                int x = requireCoordinate(args.arg(1));
                int y = requireCoordinate(args.arg(2));
                int z = requireCoordinate(args.arg(3));

                int ticks = args.arg(4).checkint();
                // Zero seria "no proximo tique", mas o jogo trata prazo nao positivo como agora
                // mesmo -- e agendar de dentro do proprio tique daria recursao sem folga.
                if (ticks < 1) throw new LuaError("schedule_block exige pelo menos 1 tique");
                // Um dia de jogo. O limite existe porque a fila e gravada com o chunk: um prazo
                // absurdo fica no arquivo do mundo esperando um bloco que ninguem lembra por que
                // agendou.
                if (ticks > 24000) throw new LuaError("schedule_block aceita no maximo 24000 tiques");

                bridge.scheduleBlockTick(x, y, z, ticks);
                return LuaValue.NIL;
            }
        });
        serverApi.set("fill", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 7) {
                    throw new LuaError("fill exige id, x1, y1, z1, x2, y2 e z2");
                }
                String id = requireIdentifier(args.arg(1).tojstring());
                int x1 = requireCoordinate(args.arg(2));
                int y1 = requireCoordinate(args.arg(3));
                int z1 = requireCoordinate(args.arg(4));
                int x2 = requireCoordinate(args.arg(5));
                int y2 = requireCoordinate(args.arg(6));
                int z2 = requireCoordinate(args.arg(7));

                long volume = (Math.abs((long) x2 - x1) + 1)
                        * (Math.abs((long) y2 - y1) + 1)
                        * (Math.abs((long) z2 - z1) + 1);
                if (volume > MAX_FILL_VOLUME) {
                    throw new LuaError("fill excede o limite de " + MAX_FILL_VOLUME
                            + " blocos; pedido: " + volume);
                }
                return LuaValue.valueOf(bridge.fillBlocks(id, x1, y1, z1, x2, y2, z2));
            }
        });
        serverApi.set("place_structure", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 4) throw new LuaError("place_structure exige id, x, y e z");

                String structureId = args.arg(1).tojstring();
                ModManifest.StructureDefinition structure = findStructure(mod.manifest(), structureId);
                if (structure == null) {
                    throw new LuaError("estrutura nao declarada no manifesto: " + structureId);
                }

                int x = requireCoordinate(args.arg(2));
                int y = requireCoordinate(args.arg(3));
                int z = requireCoordinate(args.arg(4));

                try {
                    // O quinto argumento e o giro, opcional: em quartos de volta, para uma
                    // masmorra nao nascer sempre virada para o mesmo lado.
                    int turns = args.narg() >= 5 && !args.arg(5).isnil()
                            ? args.arg(5).checkint()
                            : 0;

                    StructurePlacer.Placement placement =
                            new StructurePlacer(bridge).place(structure, x, y, z, turns);
                    return LuaValue.valueOf(placement.placed());
                } catch (IllegalArgumentException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });
        serverApi.set("set_block_variant", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 5) throw new LuaError("set_block_variant exige id, x, y, z e variant");
                String id = requireIdentifier(args.arg(1).tojstring());
                int variant = args.arg(5).checkint();
                if (variant < 0 || variant > 15) throw new LuaError("variant deve estar entre 0 e 15");
                bridge.setBlockVariant(id, args.arg(2).checkint(), args.arg(3).checkint(), args.arg(4).checkint(), variant);
                return LuaValue.NIL;
            }
        });
        serverApi.set("set_block_property", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 3) throw new LuaError("set_block_property exige id, nome e valor");
                String id = requireIdentifier(args.arg(1).tojstring());
                float value = (float) args.arg(3).checkdouble();
                if (value < 0 || value > 100) throw new LuaError("valor físico fora do intervalo 0..100");
                bridge.setBlockProperty(id, args.arg(2).tojstring(), value);
                return LuaValue.NIL;
            }
        });
        serverApi.set("set_block_luminance", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 5) throw new LuaError("set_block_luminance exige id, x, y, z e valor");
                String id = requireIdentifier(args.arg(1).tojstring());
                int luminance = args.arg(5).checkint();
                if (luminance < 0 || luminance > 15) throw new LuaError("luminosidade deve estar entre 0 e 15");
                bridge.setBlockLuminance(id, args.arg(2).checkint(), args.arg(3).checkint(), args.arg(4).checkint(), luminance);
                return LuaValue.NIL;
            }
        });
        return serverApi;
    }

    /**
     * Constroi a API de jogador amarrada a um mod.
     *
     * <p>Ler dados do jogador exige {@code player.read} e mexer no inventario exige
     * {@code player.inventory}: sao poderes diferentes, e ate agora {@code player.read} era uma
     * permissao que nao protegia nada.
     */
    /**
     * Os lados na ordem em que o jogo os numera.
     *
     * <p>O contrato devolve numero porque e o que as duas plataformas tem em comum; o Lua recebe
     * nome, porque quem escreve o mod compara com "up", e nao com 1.
     */
    private static final String[] SIDE_NAMES = {"down", "up", "north", "south", "west", "east"};

    private LuaTable playerDataFor(ModLoader.LoadedMod mod, PlayerHandle player) {
        Map<String, LuaTable> byPlayer = playerStates.computeIfAbsent(mod.manifest().id, id -> {
            Map<String, LuaTable> loaded = new LinkedHashMap<>();
            LuaTable stored = stateStore.loadScoped(id, "players");
            for (LuaValue key : stored.keys()) {
                LuaValue value = stored.get(key);
                if (value.istable()) loaded.put(key.tojstring(), (LuaTable) value);
            }
            return loaded;
        });
        return byPlayer.computeIfAbsent(player.uuid(), ignored -> new LuaTable());
    }

    private LuaTable playerDataApiFor(ModLoader.LoadedMod mod, PlayerHandle player) {
        LuaTable data = playerDataFor(mod, player);
        LuaTable api = new LuaTable();
        api.set("get", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.read");
                String key = requireDataKey(args.arg(1));
                LuaValue value = data.get(key);
                if (value.isnil() && args.narg() >= 2) return args.arg(2);
                return value;
            }
        });
        api.set("has", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.read");
                return LuaValue.valueOf(!data.get(requireDataKey(value)).isnil());
            }
        });
        api.set("set", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue keyValue, LuaValue value) {
                requirePermission(mod.manifest(), "player.modify");
                String key = requireDataKey(keyValue);
                ensurePersistable(value, 0);
                data.set(key, value);
                return value;
            }
        });
        api.set("remove", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.modify");
                String key = requireDataKey(value);
                LuaValue previous = data.get(key);
                data.set(key, LuaValue.NIL);
                return LuaValue.valueOf(!previous.isnil());
            }
        });
        return api;
    }

    private static String requireRuleName(LuaValue value) {
        if (value == null || !value.isstring()) {
            throw new LuaError("nome de regra precisa ser texto");
        }
        String name = value.tojstring().toLowerCase(java.util.Locale.ROOT);
        if (!name.matches("[a-z0-9_]{1,64}")) {
            throw new LuaError("nome de regra invalido: " + name);
        }
        return name;
    }

    private static String requireRuleValue(LuaValue value) {
        if (value == null || value.isnil() || value.istable() || value.isfunction()) {
            throw new LuaError("valor de regra precisa ser texto, numero ou booleano");
        }
        return value.tojstring();
    }

    private static String requireDataKey(LuaValue value) {
        if (value == null || !value.isstring()) {
            throw new LuaError("chave de player.data precisa ser texto");
        }
        String key = value.tojstring();
        if (!key.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new LuaError("chave de player.data invalida: " + key);
        }
        return key;
    }

    private static void ensurePersistable(LuaValue value, int depth) {
        if (value.isnil() || value.isboolean() || value.isnumber() || value.isstring()) return;
        if (!value.istable()) {
            throw new LuaError("player.data aceita apenas texto, numero, booleano ou tabela");
        }
        if (depth >= 32) throw new LuaError("player.data excede 32 niveis de profundidade");
        LuaTable table = value.checktable();
        for (LuaValue key : table.keys()) {
            if (!key.isstring() && !key.isnumber()) {
                throw new LuaError("chaves de player.data precisam ser texto ou numero");
            }
            ensurePersistable(table.get(key), depth + 1);
        }
    }

    private LuaTable playerApiFor(ModLoader.LoadedMod mod, PlayerHandle player) {
        LuaTable playerApi = new LuaTable();
        playerApi.set("name", LuaValue.valueOf(player.name()));
        playerApi.set("uuid", LuaValue.valueOf(player.uuid()));
        playerApi.set("data", playerDataApiFor(mod, player));

        playerApi.set("send_message", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "chat.send");
                player.sendMessage(value.tojstring());
                return LuaValue.NIL;
            }
        });
        playerApi.set("send_action_bar", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "chat.send");
                player.sendActionBar(value.tojstring());
                return LuaValue.NIL;
            }
        });
        playerApi.set("held_item", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                return LuaValue.valueOf(player.heldItem());
            }
        });
        playerApi.set("count_item", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.read");
                return LuaValue.valueOf(player.countItem(requireIdentifier(value.tojstring())));
            }
        });
        playerApi.set("position", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                int[] position = player.position();
                LuaTable table = new LuaTable();
                table.set("x", LuaValue.valueOf(position[0]));
                table.set("y", LuaValue.valueOf(position[1]));
                table.set("z", LuaValue.valueOf(position[2]));
                return table;
            }
        });
        playerApi.set("looking_at", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.read");

                // Cinco blocos e o alcance de construcao do jogo. Um mod que queira mirar mais
                // longe diz quanto, e o teto existe para um script nao varrer o mundo inteiro
                // procurando o primeiro bloco de uma linha.
                double alcance = args.narg() >= 1 ? args.arg(1).checkdouble() : 5.0;
                if (alcance <= 0) throw new LuaError("looking_at exige um alcance positivo");
                if (alcance > 64) throw new LuaError("looking_at aceita no maximo 64 blocos");

                int[] alvo = player.lookingAt(alcance);

                // Nil, e nao uma posicao qualquer: olhar para o ceu e uma resposta legitima, e
                // devolver zero faria o mod agir sobre a origem do mundo sem ninguem pedir.
                if (alvo == null) return LuaValue.NIL;

                LuaTable table = new LuaTable();
                table.set("x", LuaValue.valueOf(alvo[0]));
                table.set("y", LuaValue.valueOf(alvo[1]));
                table.set("z", LuaValue.valueOf(alvo[2]));
                table.set("side", LuaValue.valueOf(SIDE_NAMES[alvo[3] % SIDE_NAMES.length]));
                return table;
            }
        });
        playerApi.set("health", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                float[] health = player.health();
                LuaTable table = new LuaTable();
                table.set("current", LuaValue.valueOf(health[0]));
                table.set("max", LuaValue.valueOf(health[1]));
                return table;
            }
        });
        playerApi.set("give_item", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.inventory");
                String id = requireIdentifier(args.arg(1).tojstring());
                int count = requireCount(args.arg(2));
                // Devolve quantos nao couberam no inventario e cairam no chao.
                return LuaValue.valueOf(player.giveItem(id, count, readItemSpec(args.arg(3))));
            }
        });
        playerApi.set("take_item", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.inventory");
                String id = requireIdentifier(args.arg(1).tojstring());
                int count = requireCount(args.arg(2));
                return LuaValue.valueOf(player.takeItem(id, count));
            }
        });
        playerApi.set("set_health", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.modify");
                double health = value.checkdouble();
                if (health < 0) throw new LuaError("a vida nao pode ser negativa");
                player.setHealth((float) health);
                return LuaValue.NIL;
            }
        });
        playerApi.set("food", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                float[] food = player.food();
                LuaTable table = new LuaTable();
                table.set("level", LuaValue.valueOf(food[0]));
                table.set("saturation", LuaValue.valueOf(food[1]));
                return table;
            }
        });
        playerApi.set("set_food", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.modify");
                int level = args.arg(1).checkint();
                if (level < 0 || level > 20) throw new LuaError("fome deve estar entre 0 e 20");
                float saturation = args.narg() < 2 ? 5f : (float) args.arg(2).checkdouble();
                player.setFood(level, saturation);
                return LuaValue.NIL;
            }
        });
        playerApi.set("experience", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                float[] experience = player.experience();
                LuaTable table = new LuaTable();
                table.set("level", LuaValue.valueOf((int) experience[0]));
                table.set("progress", LuaValue.valueOf(experience[1]));
                return table;
            }
        });
        playerApi.set("give_experience", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.modify");
                player.giveExperienceLevels(value.checkint());
                return LuaValue.NIL;
            }
        });
        playerApi.set("game_mode", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                return LuaValue.valueOf(player.gameMode());
            }
        });
        playerApi.set("set_game_mode", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.modify");
                String mode = value.tojstring();
                if (!Set.of("survival", "creative", "adventure", "spectator").contains(mode)) {
                    throw new LuaError("modo de jogo desconhecido: " + mode);
                }
                player.setGameMode(mode);
                return LuaValue.NIL;
            }
        });
        playerApi.set("dimension", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                return LuaValue.valueOf(player.dimension());
            }
        });
        playerApi.set("effects", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                return toLuaEffects(player.activeEffects());
            }
        });
        playerApi.set("movement", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.read");
                return toLuaMovement(player.movement());
            }
        });
        playerApi.set("apply_effect", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.modify");
                String id = requireIdentifier(args.arg(1).tojstring());

                // Trinta segundos quando nao declarado, como no efeito de entidade: um efeito de
                // zero ticks seria descartado no mesmo tique.
                int duration = args.narg() < 2 ? 600 : args.arg(2).checkint();
                if (duration < 0) throw new LuaError("a duracao nao pode ser negativa");

                player.applyEffect(id, duration, args.narg() < 3 ? 0 : args.arg(3).checkint());
                return LuaValue.NIL;
            }
        });
        playerApi.set("clear_effects", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.modify");
                player.clearEffects();
                return LuaValue.NIL;
            }
        });
        playerApi.set("show_title", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "chat.send");
                String title = args.arg(1).isnil() ? "" : args.arg(1).tojstring();
                String subtitle = args.arg(2).isnil() ? "" : args.arg(2).tojstring();

                // Negativos deixam o jogo escolher os tempos, que e o que se quer na maioria das
                // vezes -- declarar tres numeros para um aviso simples seria ruido.
                player.showTitle(title, subtitle,
                        args.narg() < 3 ? -1 : args.arg(3).checkint(),
                        args.narg() < 4 ? -1 : args.arg(4).checkint(),
                        args.narg() < 5 ? -1 : args.arg(5).checkint());
                return LuaValue.NIL;
            }
        });
        playerApi.set("play_sound_to", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "chat.send");
                String id = requireIdentifier(args.arg(1).tojstring());
                float volume = args.narg() < 2 ? 1f : (float) args.arg(2).checkdouble();
                float pitch = args.narg() < 3 ? 1f : (float) args.arg(3).checkdouble();
                player.playSoundTo(id, volume, pitch);
                return LuaValue.NIL;
            }
        });
        playerApi.set("inventory", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.inventory");

                LuaTable list = new LuaTable();
                int index = 1;
                for (String line : player.inventory()) {
                    String[] parts = line.split(";", 3);
                    if (parts.length < 3) continue;

                    LuaTable entry = new LuaTable();
                    entry.set("slot", LuaValue.valueOf(Integer.parseInt(parts[0])));
                    entry.set("item", LuaValue.valueOf(parts[1]));
                    entry.set("count", LuaValue.valueOf(Integer.parseInt(parts[2])));
                    list.set(index++, entry);
                }
                return list;
            }
        });
        playerApi.set("clear_inventory", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.inventory");
                player.clearInventory();
                return LuaValue.NIL;
            }
        });
        // Abre a janela declarada de um bloco, como se o jogador tivesse clicado nele.
        //
        // Ate aqui, desligar `open_on_use` para o script decidir o que o clique faz custava perder
        // a janela de vez -- nao havia como abri-la. Isso amarrava a ordem das telas a uma decisao
        // do manifesto: um bloco cujo clique deve abrir configuracao, com os itens atras de um
        // botao, nao era exprimivel.
        playerApi.set("open_block_inventory", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.menu");
                if (args.narg() < 3) {
                    throw new LuaError("open_block_inventory exige x, y e z");
                }
                return LuaValue.valueOf(player.openBlockInventory(
                        (int) requireCoordinate(args.arg(1)),
                        (int) requireCoordinate(args.arg(2)),
                        (int) requireCoordinate(args.arg(3))));
            }
        });
        playerApi.set("open_menu", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.menu");
                if (args.narg() < 4 || !args.arg(4).istable()) {
                    throw new LuaError("open_menu exige id, titulo, linhas e uma lista de itens");
                }
                int rows = args.arg(3).checkint();
                if (rows < 1 || rows > 6) throw new LuaError("linhas deve estar entre 1 e 6");

                String menuId = qualifiedMenuId(mod, args.arg(1).tojstring());
                player.openMenu(menuId, args.arg(2).tojstring(), rows,
                        menuItems((LuaTable) args.arg(4)));
                return LuaValue.NIL;
            }
        });
        playerApi.set("update_menu", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.menu");
                if (!value.istable()) throw new LuaError("update_menu exige uma lista de itens");
                // Redesenhar sem fechar e o que permite uma janela reagir ao proprio clique.
                return LuaValue.valueOf(player.updateMenu(menuItems((LuaTable) value)));
            }
        });
        playerApi.set("open_menu_id", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.menu");
                String open = player.openMenuId();
                return open == null ? LuaValue.NIL : LuaValue.valueOf(open);
            }
        });
        playerApi.set("supports_screens", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.menu");
                return LuaValue.valueOf(player.supportsScreens());
            }
        });
        // O diagnostico de uma tela: onde cada elemento vai parar, e o que esta errado com isso.
        //
        // Existe porque o log responde a pergunta errada quando uma tela sai torta -- ele diz que a
        // descricao foi enviada, e foi. A conta que vira posicao e o que ninguem ve, e e la que os
        // defeitos moram. Reproduz a matematica do cliente usando o mesmo codigo do nucleo, entao
        // nao ha copia para divergir.
        playerApi.set("dump_screen", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.menu");
                if (!value.istable()) throw new LuaError("dump_screen exige uma tabela de tela");

                int[] size = player.screenSize();
                int width = size != null && size.length == 2 && size[0] > 0 ? size[0] : 427;
                int height = size != null && size.length == 2 && size[1] > 0 ? size[1] : 240;

                try {
                    String relatorio = dev.lualoader.ui.ScreenDump.of(
                            ScreenBuilder.screen((LuaTable) value), width, height);

                    // Vai para o log e volta como texto: o log serve para ler depois, e o retorno
                    // permite ao mod mostrar na propria tela.
                    for (String line : relatorio.split(java.util.regex.Pattern.quote(System.lineSeparator()) + "|\n")) {
                        logger.info("[{}] {}", mod.manifest().id, line);
                    }
                    return LuaValue.valueOf(relatorio);
                } catch (ScreenBuilder.InvalidScreenException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });

        playerApi.set("open_screen", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.menu");
                if (args.narg() < 2 || !args.arg(2).istable()) {
                    throw new LuaError("open_screen exige um id e uma tabela de tela");
                }
                String screenId = qualifiedMenuId(mod, args.arg(1).tojstring());
                try {
                    return LuaValue.valueOf(
                            player.openScreen(screenId, ScreenBuilder.screen((LuaTable) args.arg(2))));
                } catch (ScreenBuilder.InvalidScreenException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });
        playerApi.set("update_screen", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.menu");
                if (!value.istable()) throw new LuaError("update_screen exige uma tabela de tela");
                try {
                    return LuaValue.valueOf(player.updateScreen(ScreenBuilder.screen((LuaTable) value)));
                } catch (ScreenBuilder.InvalidScreenException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });
        playerApi.set("close_screen", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.menu");
                player.closeScreen();
                return LuaValue.NIL;
            }
        });
        playerApi.set("set_hud", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.menu");
                if (!value.istable()) throw new LuaError("set_hud exige uma lista de elementos");
                try {
                    // Devolve se o HUD chegou ao cliente, como open_screen e set_overlay ja
                    // faziam. Antes era a unica das tres a nao responder nada, e um mod nao tinha
                    // como saber que desenhou para um cliente que nao existe.
                    return LuaValue.valueOf(player.setHud(ScreenBuilder.hud((LuaTable) value)));
                } catch (ScreenBuilder.InvalidScreenException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });
        playerApi.set("screen_size", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.menu");
                int[] size = player.screenSize();
                // Sem informacao, devolve nil: o mod escolhe um padrao em vez de receber um numero
                // inventado, que seria pior porque pareceria confiavel.
                if (size == null) return LuaValue.NIL;

                LuaTable table = new LuaTable();
                table.set("width", LuaValue.valueOf(size[0]));
                table.set("height", LuaValue.valueOf(size[1]));
                return table;
            }
        });
        playerApi.set("set_overlay", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.menu");
                if (args.narg() < 2 || !args.arg(2).istable()) {
                    throw new LuaError("set_overlay exige um id e uma tabela de sobreposicao");
                }
                // O id e qualificado igual ao de uma tela, e por isso o evento de um botao dentro
                // da sobreposicao volta para o callback registrado por mod.screen com o mesmo nome.
                String overlayId = qualifiedMenuId(mod, args.arg(1).tojstring());
                try {
                    return LuaValue.valueOf(player.setOverlay(overlayId,
                            ScreenBuilder.overlay((LuaTable) args.arg(2))));
                } catch (ScreenBuilder.InvalidScreenException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });
        playerApi.set("clear_overlay", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "player.menu");
                return LuaValue.valueOf(player.clearOverlay(qualifiedMenuId(mod, value.tojstring())));
            }
        });
        playerApi.set("close_menu", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                requirePermission(mod.manifest(), "player.menu");
                player.closeMenu();
                return LuaValue.NIL;
            }
        });
        playerApi.set("teleport", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.move");
                if (args.narg() < 3) throw new LuaError("teleport exige x, y e z");
                player.teleport(
                        requireCoordinate(args.arg(1)),
                        requireCoordinate(args.arg(2)),
                        requireCoordinate(args.arg(3)));
                return LuaValue.NIL;
            }
        });
        return playerApi;
    }

    /** Prefixa o id do menu com o mod, para dois mods poderem usar o mesmo nome curto. */
    /**
     * Teto de receitas devolvidas.
     *
     * <p>Consultar receitas custa uma varredura do livro inteiro, porque o jogo nao indexa por
     * item. Um teto pequeno por padrao empurra o mod a perguntar pelo que vai mostrar agora.
     */
    private static int recipeLimit(LuaValue value) {
        if (value.isnil()) return 16;
        int limit = value.checkint();
        if (limit < 1 || limit > 256) throw new LuaError("limite deve estar entre 1 e 256");
        return limit;
    }

    private static LuaTable stringList(java.util.List<String> values) {
        LuaTable list = new LuaTable();
        int index = 1;
        for (String value : values) list.set(index++, LuaValue.valueOf(value));
        return list;
    }

    /** Converte a descricao JSON de cada receita na tabela que o script recebe. */
    private static LuaTable recipeList(java.util.List<String> descriptions) {
        LuaTable list = new LuaTable();
        int index = 1;

        for (String description : descriptions) {
            com.google.gson.JsonObject json;
            try {
                json = com.google.gson.JsonParser.parseString(description).getAsJsonObject();
            } catch (RuntimeException error) {
                continue;
            }

            LuaTable recipe = new LuaTable();
            recipe.set("id", LuaValue.valueOf(json.get("id").getAsString()));
            recipe.set("type", LuaValue.valueOf(json.get("type").getAsString()));
            recipe.set("width", LuaValue.valueOf(json.get("width").getAsInt()));
            recipe.set("height", LuaValue.valueOf(json.get("height").getAsInt()));

            com.google.gson.JsonObject output = json.getAsJsonObject("output");
            LuaTable result = new LuaTable();
            result.set("item", LuaValue.valueOf(output.get("item").getAsString()));
            result.set("count", LuaValue.valueOf(output.get("count").getAsInt()));
            recipe.set("output", result);

            // Cada posicao da receita traz os itens que servem ali: uma tag como "qualquer tabua"
            // chega como a lista das tabuas, e nao como o nome da tag, para o mod poder desenhar.
            LuaTable ingredients = new LuaTable();
            int position = 1;
            for (com.google.gson.JsonElement entry : json.getAsJsonArray("ingredients")) {
                LuaTable alternatives = new LuaTable();
                int alternative = 1;
                for (com.google.gson.JsonElement item : entry.getAsJsonArray()) {
                    alternatives.set(alternative++, LuaValue.valueOf(item.getAsString()));
                }
                ingredients.set(position++, alternatives);
            }
            recipe.set("ingredients", ingredients);

            list.set(index++, recipe);
        }
        return list;
    }

    private static String qualifiedMenuId(ModLoader.LoadedMod mod, String name) {
        return name.contains(":") ? name : mod.manifest().id + ":" + name;
    }

    /**
     * Converte a lista Lua em linhas {@code item;quantidade;rotulo}.
     *
     * <p>Aceita tanto um texto simples quanto uma tabela com {@code item}, {@code count} e
     * {@code label}, para o caso comum ficar curto sem impedir o caso completo.
     */
    private static java.util.List<String> menuItems(LuaTable list) {
        java.util.List<String> items = new java.util.ArrayList<>();
        for (int index = 1; index <= list.length(); index++) {
            LuaValue entry = list.get(index);
            if (entry.istable()) {
                LuaValue id = entry.get("item");
                LuaValue count = entry.get("count");
                LuaValue label = entry.get("label");
                items.add(id.tojstring()
                        + ";" + (count.isnumber() ? count.toint() : 1)
                        + ";" + (label.isnil() ? "" : label.tojstring()));
            } else if (!entry.isnil()) {
                items.add(entry.tojstring() + ";1;");
            } else {
                items.add("");
            }
        }
        return items;
    }

    /** Quantidade de itens aceita em uma operacao de inventario. */
    private static int requireCount(LuaValue value) {
        int count = value.isnil() ? 1 : value.checkint();
        if (count < 1 || count > 1024) {
            throw new LuaError("quantidade precisa estar entre 1 e 1024: " + count);
        }
        return count;
    }

    /**
     * A API de instalacao de mods, acoplada ao jogador que disparou a acao.
     *
     * <p>Existe para um caso concreto: um mod publicado em pedacos -- um nucleo mais modulos
     * opcionais -- que quer oferecer dentro do jogo a lista do que existe, para quem joga escolher o
     * que instalar. Sem ela, escolher um modulo significa sair do jogo, achar o arquivo e reiniciar.
     *
     * <p>Tres portas, e todas precisam estar abertas:
     *
     * <ul>
     *   <li>o mod declarou a permissao {@code server.install};</li>
     *   <li>quem administra o servidor liberou a instalacao pela API;</li>
     *   <li>quem esta agindo e operador.</li>
     * </ul>
     *
     * <p>A permissao sozinha nao basta de proposito. Um mod declara as proprias permissoes, entao
     * ela diz "este mod pretende instalar outros" -- e e uma informacao util, que aparece na tela do
     * gerenciador --, mas nao autoriza nada. Quem autoriza e o servidor, e o operador.
     */
    private void installApiFor(ModLoader.LoadedMod mod, PlayerHandle player, LuaTable serverApi) {
        serverApi.set("install_allowed", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(installer != null
                        && installPolicy != null
                        && installPolicy.allowApiInstall()
                        && player != null
                        && player.isOperator());
            }
        });

        // O interruptor, e quem pode mexer nele. Fica na API para a tela do gerenciador poder
        // liga-lo sem ninguem sair do jogo -- que e o ponto de ter um instalador dentro do jogo.
        // Mudar a chave nao exige a permissao server.install: exige ser operador, que e mais forte.
        serverApi.set("install_api_enabled", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(installPolicy != null && installPolicy.allowApiInstall());
            }
        });

        serverApi.set("set_install_api", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue enabled) {
                if (installPolicy == null) throw new LuaError("este servidor nao instala mods");
                if (player == null || !player.isOperator()) {
                    throw new LuaError("so um operador muda essa chave");
                }

                installPolicy.setAllowApiInstall(enabled.toboolean());
                logger.info("Instalacao pela API {} por {}",
                        enabled.toboolean() ? "liberada" : "bloqueada", player.name());
                return LuaValue.valueOf(installPolicy.allowApiInstall());
            }
        });

        serverApi.set("is_operator", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(player != null && player.isOperator());
            }
        });

        serverApi.set("install_preview", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue url) {
                requireInstallAllowed(mod, player);
                try {
                    var preview = installer.preview(url.tojstring());
                    pendingInstalls.put(preview.id(), preview);

                    LuaTable table = new LuaTable();
                    table.set("id", LuaValue.valueOf(preview.id()));
                    table.set("name", LuaValue.valueOf(preview.name()));
                    table.set("version", LuaValue.valueOf(preview.version()));
                    table.set("description", LuaValue.valueOf(preview.description()));
                    table.set("authors", toLuaList(preview.authors()));
                    table.set("permissions", toLuaList(preview.permissions()));
                    table.set("blocks", LuaValue.valueOf(preview.blocks()));
                    table.set("items", LuaValue.valueOf(preview.items()));
                    table.set("replaces", LuaValue.valueOf(preview.replacesExisting()));
                    // Conteudo declarado so existe no jogo depois de reiniciar: o registro do
                    // Minecraft fecha na inicializacao. Dizer isto aqui e o que permite a tela
                    // avisar antes, em vez de quem instalou procurar um bloco que nao aparece.
                    table.set("needs_restart",
                            LuaValue.valueOf(preview.blocks() > 0 || preview.items() > 0));
                    return table;
                } catch (java.io.IOException error) {
                    throw new LuaError("nao foi possivel baixar: " + error.getMessage());
                } catch (dev.lualoader.install.ModInstaller.InstallException error) {
                    throw new LuaError(error.getMessage());
                }
            }
        });

        serverApi.set("install_confirm", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue modId) {
                requireInstallAllowed(mod, player);

                String id = modId.tojstring();
                var preview = pendingInstalls.remove(id);
                // Sem previa nao ha instalacao: e o que garante que alguem viu as permissoes antes
                // de o codigo entrar. Confirmar direto por endereco pularia justamente essa leitura.
                if (preview == null) {
                    throw new LuaError("nenhuma previa pendente para " + id
                            + "; chame install_preview antes");
                }

                try {
                    java.nio.file.Path directory = installer.install(preview);
                    logger.info("Mod {} instalado pela API, a pedido de {}", id,
                            player == null ? "?" : player.name());

                    // O que vive no runtime entra agora; o que precisa do registro do jogo nao tem
                    // como entrar. Carregar assim mesmo um mod com conteudo e melhor que nao
                    // carregar: os scripts dele passam a valer, e so os blocos ficam para depois.
                    boolean active = loadInstalled(directory);
                    boolean needsRestart = preview.blocks() > 0 || preview.items() > 0;

                    LuaTable result = new LuaTable();
                    result.set("installed", LuaValue.TRUE);
                    result.set("active", LuaValue.valueOf(active));
                    result.set("needs_restart", LuaValue.valueOf(needsRestart));
                    return result;
                } catch (java.io.IOException error) {
                    throw new LuaError("nao foi possivel gravar: " + error.getMessage());
                }
            }
        });

        serverApi.set("uninstall", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue modId) {
                requireInstallAllowed(mod, player);

                String id = modId.tojstring();
                // Um mod nao se desinstala: o script sairia do disco no meio da propria execucao, e
                // o que aconteceria depois disso depende de detalhe de implementacao.
                if (id.equals(mod.manifest().id)) {
                    throw new LuaError("um mod nao pode desinstalar a si mesmo");
                }

                try {
                    return LuaValue.valueOf(installer.uninstall(id));
                } catch (java.io.IOException error) {
                    throw new LuaError("nao foi possivel remover: " + error.getMessage());
                }
            }
        });
    }

    private void requireInstallAllowed(ModLoader.LoadedMod mod, PlayerHandle player) {
        requirePermission(mod.manifest(), "server.install");

        if (installer == null || installPolicy == null) {
            throw new LuaError("este servidor nao instala mods");
        }
        if (!installPolicy.allowApiInstall()) {
            throw new LuaError("a instalacao pela API dos mods esta desligada neste servidor");
        }
        if (player == null || !player.isOperator()) {
            throw new LuaError("so um operador pode instalar mods");
        }
    }

    /**
     * O jogador do callback que esta correndo agora, ou {@code null}.
     *
     * <p>Existe para o {@code mod.after} saber a quem a tarefa pertence sem que o script precise
     * dizer. E preenchido ao montar o contexto, que e por onde todo callback passa.
     */
    private PlayerHandle actingPlayer;

    private LuaTable context(ModLoader.LoadedMod mod, PlayerHandle player, BlockEventData block) {
        this.actingPlayer = player;
        LuaTable context = createLogApi(mod.manifest().id);
        context.set("time", LuaValue.valueOf(System.currentTimeMillis()));
        // O mesmo estado alcancado por mod.state, para o callback nao precisar do global.
        context.set("state", states.computeIfAbsent(mod.manifest().id, key -> stateStore.load(key)));

        LuaTable serverApi = serverApiFor(mod);
        // As funcoes de instalacao entram aqui, e nao em serverApiFor, porque precisam saber quem
        // esta agindo: sem jogador nao ha nivel de operador para conferir, e instalar codigo e a
        // unica operacao do loader que exige isso.
        installApiFor(mod, player, serverApi);
        context.set("server", serverApi);

        if (block != null) {
            LuaTable blockApi = new LuaTable();
            blockApi.set("id", LuaValue.valueOf(block.blockId()));
            blockApi.set("x", LuaValue.valueOf(block.x()));
            blockApi.set("y", LuaValue.valueOf(block.y()));
            blockApi.set("z", LuaValue.valueOf(block.z()));
            blockApi.set("variant", LuaValue.valueOf(block.variant()));
            blockApi.set("variant_count", LuaValue.valueOf(block.variantCount()));
            context.set("block", blockApi);
        } else {
            context.set("block", LuaValue.NIL);
        }

        if (player != null) {
            context.set("player", playerApiFor(mod, player));
        } else {
            context.set("player", LuaValue.NIL);
        }
        return context;
    }

    /** Procura uma estrutura declarada pelo proprio mod. Um mod nao alcanca estruturas alheias. */
    private static ModManifest.StructureDefinition findStructure(ModManifest manifest, String id) {
        if (manifest.structures == null) return null;
        for (ModManifest.StructureDefinition structure : manifest.structures) {
            if (structure != null && id.equals(structure.id)) return structure;
        }
        return null;
    }

    /** Valida uma coordenada vinda do script, recusando valores fora do mundo. */
    private static int requireCoordinate(LuaValue value) {
        int coordinate = value.checkint();
        if (coordinate < -MAX_COORDINATE || coordinate > MAX_COORDINATE) {
            throw new LuaError("coordenada fora do intervalo permitido: " + coordinate);
        }
        return coordinate;
    }

    private static double requireCoordinateDouble(LuaValue value) {
        double coordinate = value.checkdouble();
        if (!Double.isFinite(coordinate) || coordinate < -MAX_COORDINATE || coordinate > MAX_COORDINATE) {
            throw new LuaError("coordenada fora do intervalo permitido: " + coordinate);
        }
        return coordinate;
    }

    /**
     * Lê o que o script declarou sobre uma entidade.
     *
     * <p>Campo ausente vira {@code null}, e não o valor padrão: significam coisas diferentes. Um
     * {@code false} declarado impede o jogo de escolher outra coisa; ausente deixa o jogo decidir,
     * como faria sem o mod.
     */
    static EntitySpec readEntitySpec(LuaValue value) {
        if (value == null || !value.istable()) return EntitySpec.EMPTY;
        LuaTable table = value.checktable();

        EntitySpec spec = new EntitySpec();
        spec.name = optionalText(table, "name");
        spec.nameVisible = optionalBoolean(table, "name_visible");

        spec.tame = optionalBoolean(table, "tame");
        spec.baby = optionalBoolean(table, "baby");
        spec.persistent = optionalBoolean(table, "persistent");
        spec.noAi = optionalBoolean(table, "no_ai");
        spec.variant = optionalText(table, "variant");

        spec.health = optionalNumber(table, "health");
        spec.attributes = readAttributes(table.get("attributes"));
        spec.effects = readEffects(table.get("effects"));
        spec.equipment = readEquipment(table.get("equipment"));

        spec.invulnerable = optionalBoolean(table, "invulnerable");
        spec.silent = optionalBoolean(table, "silent");
        spec.noGravity = optionalBoolean(table, "no_gravity");
        spec.glowing = optionalBoolean(table, "glowing");
        spec.fireTicks = optionalTicks(table, "fire_ticks");
        spec.frozenTicks = optionalTicks(table, "frozen_ticks");

        Double yaw = optionalNumber(table, "yaw");
        Double pitch = optionalNumber(table, "pitch");
        spec.yaw = yaw == null ? null : yaw.floatValue();
        // O jogo recorta a inclinacao a noventa graus para cada lado; um valor fora disso viraria
        // uma cabeca torcida ao contrario, e o script veria a declaracao virar outra coisa.
        spec.pitch = pitch == null ? null : (float) Math.max(-90.0, Math.min(90.0, pitch));

        return spec;
    }

    /** Atributos por identificador. Um mapa, porque o jogo tem dezenas e ganha novos. */
    private static Map<String, Double> readAttributes(LuaValue value) {
        if (!value.istable()) return null;

        Map<String, Double> attributes = new LinkedHashMap<>();
        LuaTable table = value.checktable();
        for (LuaValue key : table.keys()) {
            attributes.put(requireIdentifier(key.tojstring()), table.get(key).todouble());
        }
        return attributes.isEmpty() ? null : Map.copyOf(attributes);
    }

    /** Efeitos de pocao, cada um com duracao e nivel. */
    private static List<EntitySpec.EffectSpec> readEffects(LuaValue value) {
        if (!value.istable()) return null;

        List<EntitySpec.EffectSpec> effects = new ArrayList<>();
        LuaTable list = value.checktable();

        for (int index = 1; index <= list.length(); index++) {
            LuaValue entry = list.get(index);
            if (!entry.istable()) continue;
            LuaTable effect = entry.checktable();

            String id = optionalText(effect, "id");
            if (id == null) throw new LuaError("efeito sem id");

            effects.add(new EntitySpec.EffectSpec(
                    requireIdentifier(id),
                    optionalTicks(effect, "duration"),
                    optionalInteger(effect, "amplifier"),
                    optionalBoolean(effect, "ambient"),
                    optionalBoolean(effect, "show_particles")));
        }
        return effects.isEmpty() ? null : List.copyOf(effects);
    }

    /**
     * Equipamento por espaco do corpo.
     *
     * <p>Aceita o item sozinho ou o item com dados, porque a maioria das pecas nao precisa de mais
     * que o identificador -- obrigar uma tabela em toda peca cansaria o caso comum para servir ao
     * raro.
     */
    private static Map<String, EntitySpec.EquipmentSpec> readEquipment(LuaValue value) {
        if (!value.istable()) return null;

        Map<String, EntitySpec.EquipmentSpec> equipment = new LinkedHashMap<>();
        LuaTable table = value.checktable();

        for (LuaValue key : table.keys()) {
            String slot = key.tojstring();
            LuaValue entry = table.get(key);

            if (entry.isstring()) {
                equipment.put(slot, new EntitySpec.EquipmentSpec(
                        requireIdentifier(entry.tojstring()), ItemSpec.EMPTY, null));
                continue;
            }
            if (!entry.istable()) continue;

            LuaTable piece = entry.checktable();
            String item = optionalText(piece, "item");
            if (item == null) throw new LuaError("equipamento em " + slot + " sem item");

            Double chance = optionalNumber(piece, "drop_chance");
            equipment.put(slot, new EntitySpec.EquipmentSpec(
                    requireIdentifier(item),
                    readItemSpec(entry),
                    chance == null ? null : (float) Math.max(0.0, Math.min(1.0, chance))));
        }
        return equipment.isEmpty() ? null : Map.copyOf(equipment);
    }

    /** Lê o que o script declarou sobre um item. */
    private static ItemSpec readItemSpec(LuaValue value) {
        if (value == null || !value.istable()) return ItemSpec.EMPTY;
        LuaTable table = value.checktable();

        ItemSpec spec = new ItemSpec();
        spec.name = optionalText(table, "name");
        spec.color = optionalInteger(table, "color");
        spec.customModelData = optionalInteger(table, "custom_model_data");
        spec.unbreakable = optionalBoolean(table, "unbreakable");
        spec.keepOnDeath = optionalBoolean(table, "keep_on_death");
        spec.noDrop = optionalBoolean(table, "no_drop");
        spec.attributes = readAttributes(table.get("attributes"));

        List<String> lore = new ArrayList<>();
        LuaValue lines = table.get("lore");
        if (lines.istable()) {
            for (int index = 1; index <= lines.length(); index++) {
                LuaValue line = lines.get(index);
                if (!line.isnil()) lore.add(line.tojstring());
            }
        }
        spec.lore = lore.isEmpty() ? null : List.copyOf(lore);

        Map<String, Integer> enchantments = new LinkedHashMap<>();
        LuaValue declared = table.get("enchantments");
        if (declared.istable()) {
            LuaTable levels = declared.checktable();
            for (LuaValue key : levels.keys()) {
                // O identificador e conferido aqui, e nao no adaptador: um encantamento escrito
                // errado deve virar erro para quem escreveu o script, com o nome na mensagem.
                String id = requireIdentifier(key.tojstring());
                int level = levels.get(key).toint();
                // Nivel zero nao e um encantamento fraco: e a ausencia dele.
                if (level > 0) enchantments.put(id, level);
            }
        }
        spec.enchantments = enchantments.isEmpty() ? null : Map.copyOf(enchantments);

        Integer damage = optionalInteger(table, "damage");
        if (damage != null && damage < 0) throw new LuaError("damage nao pode ser negativo");
        spec.damage = damage;

        return spec;
    }

    private static String optionalText(LuaTable table, String field) {
        LuaValue value = table.get(field);
        return value.isnil() ? null : value.tojstring();
    }

    private static Boolean optionalBoolean(LuaTable table, String field) {
        LuaValue value = table.get(field);
        return value.isnil() ? null : value.toboolean();
    }

    private static Double optionalNumber(LuaTable table, String field) {
        LuaValue value = table.get(field);
        return value.isnil() ? null : value.todouble();
    }

    private static Integer optionalInteger(LuaTable table, String field) {
        LuaValue value = table.get(field);
        return value.isnil() ? null : value.toint();
    }

    /** Duracao em ticks, recusando valor negativo em vez de deixar virar zero no adaptador. */
    private static Integer optionalTicks(LuaTable table, String field) {
        Integer ticks = optionalInteger(table, field);
        if (ticks != null && ticks < 0) throw new LuaError(field + " nao pode ser negativo");
        return ticks;
    }

    /**
     * O que uma consulta ao registro do jogo tem em comum.
     *
     * <p>Sem filtro, o registro inteiro passaria para o Lua de uma vez -- sao milhares de itens. O
     * teto existe para que um catalogo seja paginado de proposito, e nao por acidente.
     *
     * <p>Compartilhado entre itens, blocos e tipos de entidade: sao a mesma pergunta sobre listas
     * diferentes, e tres copias divergiriam no primeiro ajuste.
     */
    private interface RegistryQuery {
        java.util.List<String> apply(String namespace, String contains, int limit);
    }

    private static Varargs registryQuery(Varargs args, RegistryQuery query) {
        LuaValue filter = args.arg(1);
        String namespace = null;
        String contains = null;
        int limit = 256;

        if (filter.istable()) {
            LuaTable options = (LuaTable) filter;
            if (!options.get("namespace").isnil()) namespace = options.get("namespace").tojstring();
            if (!options.get("contains").isnil()) contains = options.get("contains").tojstring();
            if (!options.get("limit").isnil()) {
                limit = options.get("limit").checkint();
                if (limit < 1 || limit > 4096) throw new LuaError("limit deve estar entre 1 e 4096");
            }
        } else if (!filter.isnil()) {
            throw new LuaError("a consulta aceita uma tabela de filtros");
        }

        LuaTable list = new LuaTable();
        int index = 1;
        for (String id : query.apply(namespace, contains, limit)) {
            list.set(index++, LuaValue.valueOf(id));
        }
        return list;
    }

    private static String requireIdentifier(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new LuaError("identificador inválido: " + value);
        }
        return value;
    }

    private static void requirePermission(ModManifest manifest, String permission) {
        if (manifest.permissions == null || !manifest.permissions.contains(permission)) {
            throw new LuaError("permissão ausente: " + permission);
        }
    }

    private static void requireCapability(ModManifest manifest, String capability) {
        if (manifest.requires == null || manifest.requires.capabilities == null
                || !manifest.requires.capabilities.containsKey(capability)) {
            throw new LuaError("capability ausente em requires.capabilities: " + capability);
        }
    }

    /**
     * @param exports tabela devolvida pelo entrypoint, que e a API publica do mod para
     *                {@code mod.require}
     */
    /** Janela registrada por um mod. */
    /**
     * Um processo declarado por um mod: o que entra, o que sai e quem executa.
     *
     * <p>Deliberadamente próximo do formato de uma receita do jogo, para um catálogo poder desenhar
     * os dois com o mesmo código em vez de manter dois desenhos paralelos.
     */
    private record RegisteredProcess(String modId, String id, String title,
                                     java.util.List<String> inputs, String outputItem,
                                     int outputCount, double chance, String station) {
        LuaTable toLua() {
            LuaTable table = new LuaTable();
            table.set("id", LuaValue.valueOf(id));
            table.set("title", LuaValue.valueOf(title));
            table.set("by", station == null ? LuaValue.NIL : LuaValue.valueOf(station));

            LuaTable list = new LuaTable();
            int index = 1;
            for (String input : inputs) list.set(index++, LuaValue.valueOf(input));
            table.set("inputs", list);

            LuaTable output = new LuaTable();
            output.set("item", LuaValue.valueOf(outputItem));
            output.set("count", LuaValue.valueOf(outputCount));
            output.set("chance", LuaValue.valueOf(chance));
            table.set("output", output);
            return table;
        }
    }

    /** Teto de entradas de um processo, pelo mesmo motivo que uma receita do jogo tem nove. */
    private static final int MAX_PROCESS_INPUTS = 27;

    private static RegisteredProcess readProcess(String modId, String id, LuaTable definition) {
        LuaValue title = definition.get("title");
        LuaValue output = definition.get("output");
        if (!output.istable()) {
            throw new LuaError("processo " + id + " precisa de uma tabela em output");
        }

        LuaTable outputTable = (LuaTable) output;
        LuaValue item = outputTable.get("item");
        if (item.isnil() || item.tojstring().isBlank()) {
            throw new LuaError("processo " + id + " precisa de output.item");
        }

        java.util.List<String> inputs = new java.util.ArrayList<>();
        LuaValue declared = definition.get("inputs");
        if (declared.istable()) {
            LuaTable table = (LuaTable) declared;
            int total = table.length();
            if (total > MAX_PROCESS_INPUTS) {
                throw new LuaError("processo " + id + " tem " + total
                        + " entradas, acima do limite de " + MAX_PROCESS_INPUTS);
            }
            for (int index = 1; index <= total; index++) {
                LuaValue entry = table.get(index);
                if (entry.isnil()) continue;
                inputs.add(requireIdentifier(entry.tojstring()));
            }
        }

        double chance = outputTable.get("chance").isnil() ? 1.0 : outputTable.get("chance").todouble();
        if (Double.isNaN(chance) || chance <= 0 || chance > 1) {
            throw new LuaError("chance do processo " + id + " deve estar entre 0 e 1");
        }

        int count = outputTable.get("count").isnil() ? 1 : outputTable.get("count").checkint();
        if (count < 1 || count > 64) {
            throw new LuaError("count do processo " + id + " deve estar entre 1 e 64");
        }

        LuaValue station = definition.get("by");
        return new RegisteredProcess(modId, id,
                title.isnil() ? id : title.tojstring(),
                java.util.List.copyOf(inputs),
                requireIdentifier(item.tojstring()),
                count, chance,
                station.isnil() ? null : requireIdentifier(station.tojstring()));
    }

    private static CommandSchema readCommandSchema(LuaTable table) {
        if (table.length() == 0) {
            throw new LuaError("schema de comando nao pode ser vazio");
        }

        List<CommandSchema.Node> roots = new ArrayList<>();
        for (int index = 1; index <= table.length(); index++) {
            LuaValue entry = table.get(index);
            if (entry.isnil()) throw new LuaError("schema de comando nao pode ter lacunas");
            roots.add(readCommandNode(entry.checktable(), 0));
        }
        try {
            return new CommandSchema(roots);
        } catch (IllegalArgumentException error) {
            throw new LuaError("schema de comando invalido: " + error.getMessage());
        }
    }

    private static CommandSchema.Node readCommandNode(LuaTable table, int depth) {
        if (depth > CommandSchema.MAX_DEPTH) {
            throw new LuaError("schema de comando excede a profundidade " + CommandSchema.MAX_DEPTH);
        }

        LuaValue literalValue = table.get("literal");
        LuaValue argumentValue = table.get("argument");
        boolean hasLiteral = !literalValue.isnil();
        boolean hasArgument = !argumentValue.isnil();
        if (hasLiteral == hasArgument) {
            throw new LuaError("cada no deve declarar literal ou argument");
        }

        List<CommandSchema.Node> children = new ArrayList<>();
        LuaValue childValue = table.get("children");
        if (!childValue.isnil()) {
            LuaTable childTable = childValue.checktable();
            for (int index = 1; index <= childTable.length(); index++) {
                LuaValue child = childTable.get(index);
                if (child.isnil()) throw new LuaError("children de comando nao pode ter lacunas");
                children.add(readCommandNode(child.checktable(), depth + 1));
            }
        }

        LuaValue executableValue = table.get("executes");
        boolean executable = executableValue.isnil() ? children.isEmpty() : executableValue.checkboolean();
        try {
            if (hasLiteral) {
                return CommandSchema.Node.literal(literalValue.checkjstring(), executable, children);
            }
            return CommandSchema.Node.argument(readCommandArgument(argumentValue.checktable()), executable, children);
        } catch (IllegalArgumentException error) {
            throw new LuaError("no de comando invalido: " + error.getMessage());
        }
    }

    private static CommandSchema.Argument readCommandArgument(LuaTable table) {
        String name = table.get("name").checkjstring();
        String type = table.get("type").isnil() ? "word" : table.get("type").checkjstring();
        Double min = optionalNumber(table, "min");
        Double max = optionalNumber(table, "max");

        List<String> suggestions = new ArrayList<>();
        LuaValue suggestionValue = table.get("suggestions");
        if (!suggestionValue.isnil()) {
            LuaTable suggestionTable = suggestionValue.checktable();
            if (suggestionTable.length() > CommandSchema.MAX_SUGGESTIONS) {
                throw new LuaError("argumento " + name + " excede o limite de sugestões");
            }
            for (int index = 1; index <= suggestionTable.length(); index++) {
                suggestions.add(suggestionTable.get(index).checkjstring());
            }
        }
        return new CommandSchema.Argument(name, type, min, max, suggestions);
    }

    private static LuaTable toLuaMap(Map<String, String> values, CommandSchema schema) {
        LuaTable table = new LuaTable();
        if (values == null) return table;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            LuaValue value = LuaValue.valueOf(entry.getValue());
            if (schema != null) {
                CommandSchema.Argument definition = schema.argument(entry.getKey());
                if (definition != null) {
                    try {
                        value = switch (definition.type()) {
                            case "integer" -> LuaValue.valueOf(Integer.parseInt(entry.getValue()));
                            case "double" -> LuaValue.valueOf(Double.parseDouble(entry.getValue()));
                            case "boolean" -> LuaValue.valueOf(Boolean.parseBoolean(entry.getValue()));
                            default -> value;
                        };
                    } catch (NumberFormatException ignored) {
                        // O bridge já validou o tipo; manter o texto é mais seguro que falhar o callback.
                    }
                }
            }
            table.set(entry.getKey(), value);
        }
        return table;
    }

    private record RegistrationSnapshot(Map<String, RegisteredCommand> commands,
                                         Map<String, RegisteredKeybind> keybinds,
                                         Map<String, RegisteredCamera> cameras,
                                         Map<String, RegisteredMenu> menus,
                                         Map<String, RegisteredMenu> screens,
                                         Map<String, RegisteredProcess> processes,
                                         List<ScheduledTask> scheduled) {
    }

    private record RegisteredMenu(String modId, LuaFunction callback) {
    }

    /** Comando registrado por um mod, legado ou com árvore estruturada. */
    private record RegisteredCommand(String modId, CommandSchema schema, LuaFunction callback) {
    }

    /** Hotkey declarada no manifesto e ligada a um callback Lua do mesmo mod. */
    private record RegisteredKeybind(String modId, KeybindProtocol.Binding binding,
                                     LuaFunction callback) {
    }

    /** Câmera lógica declarada ou criada pelo Lua e publicada ao cliente. */
    private record RegisteredCamera(String modId, CameraProtocol.Camera camera) {
    }

    /**
     * Tarefa agendada por {@code mod.after}.
     *
     * <p><b>Ela lembra de quem a agendou.</b> Uma tarefa criada dentro de um evento de jogador --
     * um clique de tela, um comando -- continua o que aquele jogador comecou, e sem o jogador de
     * volta ela nao consegue falar com ele: {@code ctx.player} chegava nulo, e um script que
     * atualiza a propria tela desistia na primeira volta. Sem erro nenhum no log, porque desistir
     * e o comportamento correto de quem checa antes de usar.
     *
     * <p>{@code null} quando ninguem a agendou -- um tique de mundo, o arranque do servidor --, e
     * ai {@code ctx.player} continua nulo, como sempre foi.
     */
    private record ScheduledTask(String id, String modId, long dueTick, int intervalTicks,
                                 LuaFunction callback, PlayerHandle player) {
    }

    private static LuaTable toLuaEffects(java.util.List<PlayerHandle.ActiveEffect> effects) {
        LuaTable list = new LuaTable();
        if (effects == null) return list;
        int index = 1;
        for (PlayerHandle.ActiveEffect effect : effects) {
            LuaTable value = new LuaTable();
            value.set("id", LuaValue.valueOf(effect.id()));
            value.set("duration", LuaValue.valueOf(effect.duration()));
            value.set("amplifier", LuaValue.valueOf(effect.amplifier()));
            value.set("ambient", LuaValue.valueOf(effect.ambient()));
            value.set("show_particles", LuaValue.valueOf(effect.showParticles()));
            list.set(index++, value);
        }
        return list;
    }

    private static LuaTable toLuaMovement(PlayerHandle.Movement movement) {
        LuaTable value = new LuaTable();
        if (movement == null) return value;
        LuaTable velocity = new LuaTable();
        velocity.set("x", LuaValue.valueOf(movement.velocityX()));
        velocity.set("y", LuaValue.valueOf(movement.velocityY()));
        velocity.set("z", LuaValue.valueOf(movement.velocityZ()));
        value.set("velocity", velocity);
        value.set("on_ground", LuaValue.valueOf(movement.onGround()));
        value.set("sneaking", LuaValue.valueOf(movement.sneaking()));
        value.set("sprinting", LuaValue.valueOf(movement.sprinting()));
        value.set("swimming", LuaValue.valueOf(movement.swimming()));
        value.set("flying", LuaValue.valueOf(movement.flying()));
        value.set("gliding", LuaValue.valueOf(movement.gliding()));
        return value;
    }

    /** Uma lista de textos como tabela Lua indexada a partir de um. */
    private static LuaTable toLuaList(java.util.List<String> values) {
        LuaTable table = new LuaTable();
        if (values == null) return table;

        int index = 1;
        for (String value : values) {
            if (value != null) table.set(index++, LuaValue.valueOf(value));
        }
        return table;
    }

    private record LoadedScript(ModLoader.LoadedMod mod,
                                Map<String, LuaFunction> callbacks,
                                Map<String, Map<String, LuaFunction>> blockHandlers,
                                Map<String, Map<String, LuaFunction>> itemHandlers,
                                LuaTable exports,
                                ExecutionBudget budget) {
    }

    private static final class ListCopy {
        private ListCopy() {
        }

        static java.util.List<String> ids(Map<String, LoadedScript> scripts) {
            return java.util.List.copyOf(scripts.keySet());
        }
    }
}

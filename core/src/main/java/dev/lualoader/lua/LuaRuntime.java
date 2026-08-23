package dev.lualoader.lua;

import dev.lualoader.manifest.LoaderEvents;
import dev.lualoader.manifest.ManifestImports;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import dev.lualoader.platform.BlockEventData;
import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.ItemEventData;
import dev.lualoader.platform.GameBridge;
import dev.lualoader.platform.PlayerHandle;
import dev.lualoader.structure.StructurePlacer;
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
import java.util.LinkedHashMap;
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

    /**
     * Estado compartilhado por mod, exposto como {@code mod.state} e {@code ctx.state}.
     *
     * <p>Vive fora do ambiente Lua para sobreviver a uma recarga: alterar um script durante o
     * desenvolvimento nao deve apagar o que o mod acumulou. Cada mod enxerga apenas a propria
     * tabela.
     */
    private final Map<String, LuaTable> states = new LinkedHashMap<>();

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

    /** Conecta o adaptador de plataforma. Chamado pelo bootstrap antes de disparar eventos. */
    public void attach(GameBridge bridge) {
        this.bridge = bridge == null ? GameBridge.DETACHED : bridge;
    }

    public void load(ModLoader.LoadedMod mod) throws IOException {
        LoadedScript script = compile(mod);
        scripts.put(mod.manifest().id, script);
        logger.info("Script Lua carregado: {}", mod.manifest().id);
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

        java.util.List<ScheduledTask> vencidas = new java.util.ArrayList<>();
        scheduled.removeIf(task -> {
            if (task.dueTick() > currentTick) return false;
            vencidas.add(task);
            return true;
        });

        for (ScheduledTask task : vencidas) {
            LoadedScript script = scripts.get(task.modId());
            if (script == null) continue;
            try {
                script.budget().start();
                task.callback().call(context(script.mod(), null, null));
            } catch (LuaError error) {
                logger.error("Erro Lua em tarefa agendada do mod {}: {}", task.modId(), error.getMessage());
            } catch (BridgeException error) {
                logger.error("Erro de plataforma em tarefa agendada do mod {}: {}",
                        task.modId(), error.getMessage());
            } catch (RuntimeException error) {
                logger.error("Erro Java em tarefa agendada do mod {}", task.modId(), error);
            } finally {
                script.budget().stop();
            }
        }
    }

    /** Nomes de comando registrados pelos mods. */
    public java.util.Set<String> commandNames() {
        return java.util.Set.copyOf(commands.keySet());
    }

    /**
     * Executa um comando registrado por um mod.
     *
     * @param arguments texto digitado depois do nome do comando
     * @return {@code false} quando o comando nao existe
     */
    public boolean runCommand(String name, PlayerHandle player, String arguments) {
        RegisteredCommand command = commands.get(name);
        if (command == null) return false;

        LoadedScript script = scripts.get(command.modId());
        if (script == null) return false;

        try {
            script.budget().start();
            LuaTable context = context(script.mod(), player, null);
            context.set("args", LuaValue.valueOf(arguments == null ? "" : arguments));
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
        logger.info("Estado de {} mod(s) gravado", states.size());
    }

    /** Grava o estado de um mod especifico. */
    public void saveState(String modId) {
        LuaTable state = states.get(modId);
        if (state != null) stateStore.save(modId, state);
    }

    /** Descarta o estado acumulado por um mod. Usado quando o mod e removido, nao em recarga. */
    public void forgetState(String modId) {
        states.remove(modId);
    }

    public boolean reload(String modId) throws IOException {
        LoadedScript previous = scripts.get(modId);
        if (previous == null) return false;

        // O ambiente antigo e descartado, entao o que aponta para ele precisa sair junto: uma
        // tarefa agendada ou um comando do script anterior chamaria uma funcao orfa.
        int descartadas = 0;
        for (var iterator = scheduled.iterator(); iterator.hasNext(); ) {
            if (iterator.next().modId().equals(modId)) {
                iterator.remove();
                descartadas++;
            }
        }
        commands.entrySet().removeIf(entry -> entry.getValue().modId().equals(modId));

        LoadedScript replacement = compile(previous.mod());
        scripts.put(modId, replacement);

        logger.info("Script Lua recarregado: {}{}", modId,
                descartadas == 0 ? "" : " (" + descartadas + " tarefa(s) pendente(s) descartada(s))");
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

    public void triggerAll(String event, PlayerHandle player) {
        trigger(event, player, null);
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

        for (LoadedScript script : scripts.values()) {
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
            } catch (BridgeException error) {
                logger.error("Erro de plataforma no mod {} durante {}: {}",
                        script.mod().manifest().id, event, error.getMessage());
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
        for (LoadedScript script : scripts.values()) {
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
            } catch (BridgeException error) {
                logger.error("Erro de plataforma no mod {} durante {}: {}", script.mod().manifest().id, event, error.getMessage());
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

        // API de servidor com as permissoes deste mod, independente de quem chamar.
        modApi.set("server", serverApiFor(mod));

        modApi.set("command", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "server.command.register");
                if (args.narg() < 2) throw new LuaError("command exige nome e uma funcao");

                String name = args.arg(1).tojstring();
                if (!name.matches("^[a-z][a-z0-9_-]{0,31}$")) {
                    throw new LuaError("nome de comando invalido: " + name);
                }
                if (!args.arg(2).isfunction()) throw new LuaError("command exige uma funcao");

                RegisteredCommand existente = commands.get(name);
                if (existente != null && !existente.modId().equals(mod.manifest().id)) {
                    throw new LuaError("comando " + name + " ja registrado pelo mod " + existente.modId());
                }
                commands.put(name, new RegisteredCommand(mod.manifest().id, (LuaFunction) args.arg(2)));
                return LuaValue.NIL;
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

                if (scheduled.size() >= MAX_SCHEDULED) {
                    throw new LuaError("limite de " + MAX_SCHEDULED + " tarefas agendadas atingido");
                }
                scheduled.add(new ScheduledTask(mod.manifest().id, currentTick + ticks,
                        (LuaFunction) args.arg(2)));
                return LuaValue.NIL;
            }
        });

        modApi.set("require", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                String dependencyId = value.tojstring();
                if (mod.manifest().dependencies == null
                        || !mod.manifest().dependencies.containsKey(dependencyId)) {
                    throw new LuaError("mod " + dependencyId
                            + " precisa estar declarado em dependencies para ser usado");
                }
                LoadedScript dependency = scripts.get(dependencyId);
                if (dependency == null) {
                    throw new LuaError("mod " + dependencyId + " ainda nao foi carregado");
                }
                if (dependency.exports() == null) {
                    throw new LuaError("mod " + dependencyId + " nao exporta nada");
                }
                return dependency.exports();
            }
        });

        globals.set("mod", modApi);

        LuaTable exported = null;

        // O entrypoint e opcional: um mod pode declarar apenas scripts por bloco no manifesto.
        if (mod.manifest().entrypoint != null && !mod.manifest().entrypoint.isBlank()) {
            Path entrypoint = mod.directory().resolve(mod.manifest().entrypoint).normalize();
            if (!entrypoint.startsWith(mod.directory().toAbsolutePath().normalize())) {
                throw new IOException("entrypoint Lua sai da pasta do mod");
            }

            try (Reader reader = Files.newBufferedReader(entrypoint, StandardCharsets.UTF_8)) {
                LuaValue chunk = globals.load(reader, mod.manifest().id + "/" + mod.manifest().entrypoint);
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

        return new LoadedScript(mod, Map.copyOf(callbacks),
                loadBlockHandlers(mod, globals, exported),
                loadItemHandlers(mod, globals, exported), exported, budget);
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

    /** Compila um arquivo de comportamento, que precisa devolver uma funcao. */
    private LuaFunction loadHandlerFile(ModLoader.LoadedMod mod,
                                        Globals globals,
                                        Path root,
                                        String reference) throws IOException {
        Path script = mod.directory().resolve(reference).normalize();
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

    private Globals restrictedGlobals(ExecutionBudget budget) {
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
                LuaTable lista = new LuaTable();
                int indice = 1;
                for (String nome : bridge.onlinePlayers()) lista.set(indice++, LuaValue.valueOf(nome));
                return lista;
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
        serverApi.set("spawn_entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.spawn");
                if (args.narg() < 4) throw new LuaError("spawn_entity exige id, x, y e z");
                String id = requireIdentifier(args.arg(1).tojstring());
                return LuaValue.valueOf(bridge.spawnEntity(id,
                        requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)),
                        requireCoordinate(args.arg(4))));
            }
        });
        serverApi.set("entities_near", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "entity.read");
                if (args.narg() < 4) throw new LuaError("entities_near exige x, y, z e raio");
                double radius = args.arg(4).checkdouble();
                if (radius <= 0 || radius > 64) throw new LuaError("raio deve estar entre 0 e 64");

                LuaTable lista = new LuaTable();
                int indice = 1;
                for (String linha : bridge.entitiesNear(requireCoordinate(args.arg(1)),
                        requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)), radius)) {
                    String[] partes = linha.split(";");
                    if (partes.length < 5) continue;
                    LuaTable entidade = new LuaTable();
                    entidade.set("uuid", LuaValue.valueOf(partes[0]));
                    entidade.set("type", LuaValue.valueOf(partes[1]));
                    entidade.set("x", LuaValue.valueOf(Integer.parseInt(partes[2])));
                    entidade.set("y", LuaValue.valueOf(Integer.parseInt(partes[3])));
                    entidade.set("z", LuaValue.valueOf(Integer.parseInt(partes[4])));
                    lista.set(indice++, entidade);
                }
                return lista;
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

                bridge.playSound(id, requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)),
                        requireCoordinate(args.arg(4)), volume, pitch);
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

                bridge.spawnParticles(id, requireCoordinate(args.arg(2)), requireCoordinate(args.arg(3)),
                        requireCoordinate(args.arg(4)), count, spread);
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
                    StructurePlacer.Placement placement =
                            new StructurePlacer(bridge).place(structure, x, y, z);
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
    private LuaTable playerApiFor(ModLoader.LoadedMod mod, PlayerHandle player) {
        LuaTable playerApi = new LuaTable();
        playerApi.set("name", LuaValue.valueOf(player.name()));
        playerApi.set("uuid", LuaValue.valueOf(player.uuid()));

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
                return LuaValue.valueOf(player.giveItem(id, count));
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
        playerApi.set("open_menu", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "player.menu");
                if (args.narg() < 3 || !args.arg(3).istable()) {
                    throw new LuaError("open_menu exige titulo, linhas e uma lista de itens");
                }
                int rows = args.arg(2).checkint();
                if (rows < 1 || rows > 6) throw new LuaError("linhas deve estar entre 1 e 6");

                LuaTable lista = (LuaTable) args.arg(3);
                java.util.List<String> itens = new java.util.ArrayList<>();
                for (int indice = 1; indice <= lista.length(); indice++) {
                    LuaValue entrada = lista.get(indice);
                    if (entrada.istable()) {
                        LuaValue id = entrada.get("item");
                        LuaValue count = entrada.get("count");
                        itens.add(id.tojstring() + ";" + (count.isnumber() ? count.toint() : 1));
                    } else if (!entrada.isnil()) {
                        itens.add(entrada.tojstring() + ";1");
                    } else {
                        itens.add("");
                    }
                }
                player.openMenu(args.arg(1).tojstring(), rows, itens);
                return LuaValue.NIL;
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

    /** Quantidade de itens aceita em uma operacao de inventario. */
    private static int requireCount(LuaValue value) {
        int count = value.isnil() ? 1 : value.checkint();
        if (count < 1 || count > 1024) {
            throw new LuaError("quantidade precisa estar entre 1 e 1024: " + count);
        }
        return count;
    }

    private LuaTable context(ModLoader.LoadedMod mod, PlayerHandle player, BlockEventData block) {
        LuaTable context = createLogApi(mod.manifest().id);
        context.set("time", LuaValue.valueOf(System.currentTimeMillis()));
        // O mesmo estado alcancado por mod.state, para o callback nao precisar do global.
        context.set("state", states.computeIfAbsent(mod.manifest().id, key -> stateStore.load(key)));

        LuaTable serverApi = serverApiFor(mod);
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

    /**
     * @param exports tabela devolvida pelo entrypoint, que e a API publica do mod para
     *                {@code mod.require}
     */
    /** Comando registrado por um mod. */
    private record RegisteredCommand(String modId, LuaFunction callback) {
    }

    /** Tarefa agendada por {@code mod.after}. */
    private record ScheduledTask(String modId, long dueTick, LuaFunction callback) {
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

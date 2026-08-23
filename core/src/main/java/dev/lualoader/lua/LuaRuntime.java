package dev.lualoader.lua;

import dev.lualoader.manifest.ManifestImports;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import dev.lualoader.platform.BlockEventData;
import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
import dev.lualoader.platform.PlayerHandle;
import dev.lualoader.structure.StructurePlacer;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaFunction;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;
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

    private static final Set<String> EVENTS = Set.of(
            "loader_ready", "server_started", "server_stopped", "player_joined", "player_left",
            "tick", "mod_reloaded",
            "block_used", "block_attacked", "block_placed", "block_broken",
            "block_random_tick", "block_neighbor_update",
            "item_used", "item_used_on_block"
    );

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
    private final Path remoteCache;
    private GameBridge bridge = GameBridge.DETACHED;

    /** Runtime apenas local: scripts remotos serao recusados. */
    public LuaRuntime(Logger logger) {
        this(logger, null);
    }

    /**
     * @param remoteCache diretorio para scripts baixados; {@code null} recusa script remoto
     */
    public LuaRuntime(Logger logger, Path remoteCache) {
        this.logger = logger;
        this.remoteCache = remoteCache;
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

    /** Descarta o estado acumulado por um mod. Usado quando o mod e removido, nao em recarga. */
    public void forgetState(String modId) {
        states.remove(modId);
    }

    public boolean reload(String modId) throws IOException {
        LoadedScript previous = scripts.get(modId);
        if (previous == null) return false;
        LoadedScript replacement = compile(previous.mod());
        scripts.put(modId, replacement);
        logger.info("Script Lua recarregado: {}", modId);
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
            }
        }
        return cancelled;
    }

    private LoadedScript compile(ModLoader.LoadedMod mod) throws IOException {
        Globals globals = restrictedGlobals();
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
        LuaTable state = states.computeIfAbsent(mod.manifest().id, key -> new LuaTable());
        modApi.set("state", state);

        // API de servidor com as permissoes deste mod, independente de quem chamar.
        modApi.set("server", serverApiFor(mod));

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
                loadBlockHandlers(mod, globals, exported), exported);
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

    /** Compila um arquivo de comportamento, que precisa devolver uma funcao. */
    private LuaFunction loadHandlerFile(ModLoader.LoadedMod mod,
                                        Globals globals,
                                        Path root,
                                        String reference) throws IOException {
        Path script = mod.directory().resolve(reference).normalize();
        if (!script.startsWith(root)) {
            throw new IOException("script de comportamento sai da pasta do mod: " + reference);
        }

        try (Reader reader = Files.newBufferedReader(script, StandardCharsets.UTF_8)) {
            LuaValue chunk = globals.load(reader, mod.manifest().id + "/" + reference);
            LuaValue returned = chunk.call();
            if (!returned.isfunction()) {
                throw new IOException("script de comportamento precisa devolver uma funcao: " + reference);
            }
            return (LuaFunction) returned;
        } catch (LuaError error) {
            throw new IOException("erro no script " + reference + ": " + error.getMessage(), error);
        }
    }

    private Globals restrictedGlobals() {
        Globals globals = JsePlatform.standardGlobals();
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
        String blockId = block.blockId();
        int separator = blockId.indexOf(':');
        if (separator <= 0) return false;
        return blockId.substring(0, separator).equals(mod.manifest().id);
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

    private LuaTable context(ModLoader.LoadedMod mod, PlayerHandle player, BlockEventData block) {
        LuaTable context = createLogApi(mod.manifest().id);
        context.set("time", LuaValue.valueOf(System.currentTimeMillis()));
        // O mesmo estado alcancado por mod.state, para o callback nao precisar do global.
        context.set("state", states.computeIfAbsent(mod.manifest().id, key -> new LuaTable()));

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
            context.set("player", playerApi);
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
    private record LoadedScript(ModLoader.LoadedMod mod,
                                Map<String, LuaFunction> callbacks,
                                Map<String, Map<String, LuaFunction>> blockHandlers,
                                LuaTable exports) {
    }

    private static final class ListCopy {
        private ListCopy() {
        }

        static java.util.List<String> ids(Map<String, LoadedScript> scripts) {
            return java.util.List.copyOf(scripts.keySet());
        }
    }
}

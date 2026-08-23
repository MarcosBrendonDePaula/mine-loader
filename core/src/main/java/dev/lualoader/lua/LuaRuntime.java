package dev.lualoader.lua;

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
            "loader_ready", "server_started", "server_stopped", "player_joined", "tick",
            "block_used", "block_attacked"
    );

    private final Logger logger;
    private final Map<String, LoadedScript> scripts = new LinkedHashMap<>();
    private GameBridge bridge = GameBridge.DETACHED;

    public LuaRuntime(Logger logger) {
        this.logger = logger;
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

    /** Dispara um evento originado por uma interação com um bloco declarativo. */
    public void triggerBlock(String event, PlayerHandle player, BlockEventData block) {
        trigger(event, player, block);
    }

    private void trigger(String event, PlayerHandle player, BlockEventData block) {
        if (!EVENTS.contains(event)) return;
        for (LoadedScript script : scripts.values()) {
            LuaFunction callback = script.callbacks().get(event);
            if (callback == null) continue;
            // Um evento de bloco pertence ao mod que declarou o bloco. Sem esta checagem,
            // qualquer mod receberia interacoes com o conteudo de todos os outros.
            if (block != null && !ownsBlock(script.mod(), block)) continue;
            try {
                callback.call(context(script.mod(), player, block));
            } catch (LuaError error) {
                logger.error("Erro Lua no mod {} durante {}: {}", script.mod().manifest().id, event, error.getMessage());
            } catch (BridgeException error) {
                logger.error("Erro de plataforma no mod {} durante {}: {}", script.mod().manifest().id, event, error.getMessage());
            } catch (RuntimeException error) {
                logger.error("Erro Java na ponte Lua do mod {} durante {}", script.mod().manifest().id, event, error);
            }
        }
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
        globals.set("mod", modApi);

        Path entrypoint = mod.directory().resolve(mod.manifest().entrypoint).normalize();
        if (!entrypoint.startsWith(mod.directory().toAbsolutePath().normalize())) {
            throw new IOException("entrypoint Lua sai da pasta do mod");
        }

        try (Reader reader = Files.newBufferedReader(entrypoint, StandardCharsets.UTF_8)) {
            LuaValue chunk = globals.load(reader, mod.manifest().id + "/" + mod.manifest().entrypoint);
            LuaValue returned = chunk.call();
            if (returned.istable()) {
                LuaTable table = (LuaTable) returned;
                for (Map.Entry<String, String> entry : mod.manifest().events.entrySet()) {
                    LuaValue callback = table.get(entry.getValue());
                    if (callback.isfunction()) callbacks.put(entry.getKey(), (LuaFunction) callback);
                }
            }
        } catch (LuaError error) {
            throw new IOException("erro ao executar Lua: " + error.getMessage(), error);
        }

        return new LoadedScript(mod, Map.copyOf(callbacks));
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

    /** Indica se o bloco do evento pertence ao mod, comparando o namespace com o id do mod. */
    private static boolean ownsBlock(ModLoader.LoadedMod mod, BlockEventData block) {
        String blockId = block.blockId();
        int separator = blockId.indexOf(':');
        if (separator <= 0) return false;
        return blockId.substring(0, separator).equals(mod.manifest().id);
    }

    private LuaTable context(ModLoader.LoadedMod mod, PlayerHandle player, BlockEventData block) {
        LuaTable context = createLogApi(mod.manifest().id);
        context.set("time", LuaValue.valueOf(System.currentTimeMillis()));

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

    private record LoadedScript(ModLoader.LoadedMod mod, Map<String, LuaFunction> callbacks) {
    }

    private static final class ListCopy {
        private ListCopy() {
        }

        static java.util.List<String> ids(Map<String, LoadedScript> scripts) {
            return java.util.List.copyOf(scripts.keySet());
        }
    }
}

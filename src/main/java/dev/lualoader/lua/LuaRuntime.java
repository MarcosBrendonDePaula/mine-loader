package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import dev.lualoader.LuaLoaderMod;
import dev.lualoader.minecraft.DeclarativeBlock;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
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
    private static final Set<String> EVENTS = Set.of(
            "loader_ready", "server_started", "server_stopped", "player_joined", "tick"
    );

    private final Logger logger;
    private final Map<String, LoadedScript> scripts = new LinkedHashMap<>();

    public LuaRuntime(Logger logger) {
        this.logger = logger;
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

    public void triggerAll(String event, ServerPlayerEntity player, MinecraftServer server) {
        if (!EVENTS.contains(event)) return;
        for (LoadedScript script : scripts.values()) {
            LuaFunction callback = script.callbacks().get(event);
            if (callback == null) continue;
            try {
                callback.call(context(script.mod(), player, server));
            } catch (LuaError error) {
                logger.error("Erro Lua no mod {} durante {}: {}", script.mod().manifest().id, event, error.getMessage());
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

    private LuaTable context(ModLoader.LoadedMod mod, ServerPlayerEntity player, MinecraftServer server) {
        LuaTable context = createLogApi(mod.manifest().id);
        context.set("time", LuaValue.valueOf(System.currentTimeMillis()));

        LuaTable serverApi = new LuaTable();
        serverApi.set("broadcast", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                requirePermission(mod.manifest(), "chat.send");
                if (server != null) server.getPlayerManager().broadcast(Text.literal(value.tojstring()), false);
                return LuaValue.NIL;
            }
        });
        serverApi.set("set_block_variant", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (server == null || LuaLoaderMod.blockRegistrar() == null || args.narg() < 5) {
                    throw new LuaError("set_block_variant exige id, x, y, z e variant");
                }
                Identifier id = parseIdentifier(args.arg(1).tojstring());
                var block = LuaLoaderMod.blockRegistrar().get(id);
                if (!(block instanceof DeclarativeBlock declarativeBlock)) {
                    throw new LuaError("bloco não é declarativo ou não foi encontrado: " + id);
                }
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                int variant = args.arg(5).checkint();
                if (variant < 0 || variant > 15) throw new LuaError("variant deve estar entre 0 e 15");
                BlockPos pos = new BlockPos(x, y, z);
                server.getOverworld().setBlockState(
                        pos,
                        declarativeBlock.getDefaultState().with(DeclarativeBlock.LUA_VARIANT, variant),
                        3
                );
                return LuaValue.NIL;
            }
        });
        serverApi.set("set_block_property", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (args.narg() < 3 || LuaLoaderMod.blockRegistrar() == null) {
                    throw new LuaError("set_block_property exige id, nome e valor");
                }
                Identifier id = parseIdentifier(args.arg(1).tojstring());
                var block = LuaLoaderMod.blockRegistrar().get(id);
                if (!(block instanceof DeclarativeBlock declarativeBlock)) {
                    throw new LuaError("bloco não é declarativo ou não foi encontrado: " + id);
                }
                float value = (float) args.arg(3).checkdouble();
                if (value < 0 || value > 100) throw new LuaError("valor físico fora do intervalo 0..100");
                try {
                    declarativeBlock.setDynamicProperty(args.arg(2).tojstring(), value);
                } catch (IllegalArgumentException error) {
                    throw new LuaError(error.getMessage());
                }
                return LuaValue.NIL;
            }
        });
        serverApi.set("set_block_luminance", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requirePermission(mod.manifest(), "world.write");
                if (server == null || LuaLoaderMod.blockRegistrar() == null || args.narg() < 5) {
                    throw new LuaError("set_block_luminance exige id, x, y, z e valor");
                }
                Identifier id = parseIdentifier(args.arg(1).tojstring());
                var block = LuaLoaderMod.blockRegistrar().get(id);
                if (!(block instanceof DeclarativeBlock declarativeBlock)) {
                    throw new LuaError("bloco não é declarativo ou não foi encontrado: " + id);
                }
                int x = args.arg(2).checkint();
                int y = args.arg(3).checkint();
                int z = args.arg(4).checkint();
                int luminance = args.arg(5).checkint();
                if (luminance < 0 || luminance > 15) throw new LuaError("luminosidade deve estar entre 0 e 15");
                BlockPos pos = new BlockPos(x, y, z);
                var current = server.getOverworld().getBlockState(pos);
                var state = current.isOf(declarativeBlock)
                        ? current.with(DeclarativeBlock.LUA_LUMINANCE, luminance)
                        : declarativeBlock.getDefaultState().with(DeclarativeBlock.LUA_LUMINANCE, luminance);
                server.getOverworld().setBlockState(pos, state, 3);
                return LuaValue.NIL;
            }
        });
        context.set("server", serverApi);

        if (player != null) {
            LuaTable playerApi = new LuaTable();
            playerApi.set("name", LuaValue.valueOf(player.getName().getString()));
            playerApi.set("uuid", LuaValue.valueOf(player.getUuidAsString()));
            playerApi.set("send_message", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue value) {
                    requirePermission(mod.manifest(), "chat.send");
                    player.sendMessage(Text.literal(value.tojstring()), false);
                    return LuaValue.NIL;
                }
            });
            context.set("player", playerApi);
        } else {
            context.set("player", LuaValue.NIL);
        }
        return context;
    }

    private static Identifier parseIdentifier(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new LuaError("identificador inválido: " + value);
        }
        return Identifier.of(value.substring(0, separator), value.substring(separator + 1));
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

package dev.lualoader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.IOException;

public final class LuaLoaderCommands {
    private LuaLoaderCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("lua")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(CommandManager.literal("blocks")
                        .executes(context -> blocks(context.getSource())))
                .then(CommandManager.literal("reload")
                        .executes(context -> reloadAll(context.getSource()))
                        .then(CommandManager.argument("mod_id", StringArgumentType.word())
                                .executes(context -> reloadOne(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "mod_id"))))));
    }

    private static int list(ServerCommandSource source) {
        if (LuaLoaderMod.loadedMods().isEmpty()) {
            source.sendFeedback(() -> Text.literal("Nenhum mod Lua carregado."), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Mods Lua carregados: " + LuaLoaderMod.loadedMods().size()), false);
        LuaLoaderMod.loadedMods().forEach(mod -> source.sendFeedback(
                () -> Text.literal("- " + mod.manifest().id + " " + mod.manifest().version), false));
        return LuaLoaderMod.loadedMods().size();
    }

    private static int blocks(ServerCommandSource source) {
        int count = LuaLoaderMod.blockRegistrar() == null
                ? 0
                : LuaLoaderMod.blockRegistrar().registeredBlocks().size();
        source.sendFeedback(() -> Text.literal("Blocos Lua registrados: " + count), false);
        return count;
    }

    private static int reloadAll(ServerCommandSource source) {
        if (LuaLoaderMod.luaRuntime() == null) return 0;
        int count = LuaLoaderMod.luaRuntime().reloadAll();
        source.sendFeedback(() -> Text.literal("Scripts Lua recarregados: " + count), false);
        return count;
    }

    private static int reloadOne(ServerCommandSource source, String modId) {
        try {
            if (LuaLoaderMod.luaRuntime() != null && LuaLoaderMod.luaRuntime().reload(modId)) {
                source.sendFeedback(() -> Text.literal("Script recarregado: " + modId), false);
                return 1;
            }
            source.sendError(Text.literal("Mod Lua não encontrado: " + modId));
            return 0;
        } catch (IOException | RuntimeException error) {
            source.sendError(Text.literal("Falha ao recarregar " + modId + ": " + error.getMessage()));
            return 0;
        }
    }
}

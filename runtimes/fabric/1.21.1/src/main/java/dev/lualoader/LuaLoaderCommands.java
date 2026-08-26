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
        registerModCommands(dispatcher);

        dispatcher.register(CommandManager.literal("lua")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(CommandManager.literal("blocks")
                        .executes(context -> blocks(context.getSource())))
                .then(CommandManager.literal("commands")
                        .executes(context -> commands(context.getSource())))
                .then(CommandManager.literal("reload")
                        .executes(context -> reloadAll(context.getSource()))
                        .then(CommandManager.argument("mod_id", StringArgumentType.word())
                                .executes(context -> reloadOne(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "mod_id"))))));
    }

    /**
     * Publica os comandos registrados pelos mods sob {@code /mod <nome> [argumentos]}.
     *
     * <p>Ficam sob um prefixo proprio para nao colidirem com comandos do jogo nem entre si, e
     * porque a arvore de comandos e montada uma vez, antes de o servidor aceitar jogadores.
     */
    private static void registerModCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null || runtime.commandNames().isEmpty()) return;

        var root = CommandManager.literal("mod");
        for (String name : runtime.commandNames()) {
            root = root.then(CommandManager.literal(name)
                    .executes(context -> runModCommand(context.getSource(), name, ""))
                    .then(CommandManager.argument("args", StringArgumentType.greedyString())
                            .executes(context -> runModCommand(context.getSource(), name,
                                    StringArgumentType.getString(context, "args")))));
        }
        dispatcher.register(root);
        LuaLoaderMod.LOGGER.info("Comandos de mod publicados: {}", runtime.commandNames());
    }

    private static int runModCommand(ServerCommandSource source, String name, String arguments) {
        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null) return 0;

        var player = source.getPlayer();
        boolean exists = runtime.runCommand(name,
                player == null ? null : new dev.lualoader.minecraft.FabricPlayerHandle(player),
                arguments);

        if (!exists) {
            source.sendError(Text.literal("Comando de mod desconhecido: " + name));
            return 0;
        }
        return 1;
    }

    /**
     * Lista os comandos publicados pelos mods.
     *
     * <p>Sem isto nao havia como descobrir o que existe: os comandos ficam sob {@code /mod}, e
     * quem nao leu o codigo do mod nao tem como adivinhar o nome.
     */
    private static int commands(ServerCommandSource source) {
        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null || runtime.commandNames().isEmpty()) {
            source.sendFeedback(() -> Text.literal("Nenhum mod registrou comandos."), false);
            return 0;
        }

        var names = new java.util.ArrayList<>(runtime.commandNames());
        java.util.Collections.sort(names);

        source.sendFeedback(() -> Text.literal("Comandos de mod (" + names.size() + "):"), false);
        for (String name : names) {
            source.sendFeedback(() -> Text.literal("  /mod " + name), false);
        }
        return names.size();
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

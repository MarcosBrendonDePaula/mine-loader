package dev.lualoader;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.lualoader.command.CommandSchema;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            CommandSchema schema = runtime.commandSchema(name);
            var command = CommandManager.literal(name);
            if (schema == null) {
                command.executes(context -> runModCommand(context.getSource(), name, ""))
                        .then(CommandManager.argument("args", StringArgumentType.greedyString())
                                .executes(context -> runModCommand(context.getSource(), name,
                                        StringArgumentType.getString(context, "args"))));
            } else {
                for (CommandSchema.Node node : schema.roots()) {
                    command.then(buildNode(node, name));
                }
            }
            root = root.then(command);
        }
        dispatcher.register(root);
        LuaLoaderMod.LOGGER.info("Comandos de mod publicados: {}", runtime.commandNames());
    }

    private static ArgumentBuilder<ServerCommandSource, ?> buildNode(CommandSchema.Node node, String name) {
        ArgumentBuilder<ServerCommandSource, ?> builder;
        if (node.literal() != null) {
            LiteralArgumentBuilder<ServerCommandSource> literal = CommandManager.literal(node.literal());
            builder = literal;
        } else {
            CommandSchema.Argument argument = node.argument();
            RequiredArgumentBuilder<ServerCommandSource, ?> required =
                    CommandManager.argument(argument.name(), argumentType(argument));
            if (!argument.suggestions().isEmpty()) {
                required.suggests((context, suggestions) -> {
                    for (String value : argument.suggestions()) suggestions.suggest(value);
                    return suggestions.buildFuture();
                });
            }
            builder = required;
        }
        for (CommandSchema.Node child : node.children()) {
            builder.then(buildNode(child, name));
        }
        if (node.executable()) {
            builder.executes(context -> runStructuredCommand(context.getSource(), name, context));
        }
        return builder;
    }

    private static ArgumentType<?> argumentType(CommandSchema.Argument argument) {
        return switch (argument.type()) {
            case "word" -> StringArgumentType.word();
            case "string" -> StringArgumentType.string();
            case "greedy_string" -> StringArgumentType.greedyString();
            case "integer" -> IntegerArgumentType.integer(integerMin(argument), integerMax(argument));
            case "double" -> DoubleArgumentType.doubleArg(doubleMin(argument), doubleMax(argument));
            case "boolean" -> BoolArgumentType.bool();
            default -> throw new IllegalArgumentException("tipo de argumento não suportado: " + argument.type());
        };
    }

    private static int integerMin(CommandSchema.Argument argument) {
        return argument.min() == null ? Integer.MIN_VALUE : (int) Math.ceil(argument.min());
    }

    private static int integerMax(CommandSchema.Argument argument) {
        return argument.max() == null ? Integer.MAX_VALUE : (int) Math.floor(argument.max());
    }

    private static double doubleMin(CommandSchema.Argument argument) {
        return argument.min() == null ? -Double.MAX_VALUE : argument.min();
    }

    private static double doubleMax(CommandSchema.Argument argument) {
        return argument.max() == null ? Double.MAX_VALUE : argument.max();
    }

    private static int runStructuredCommand(ServerCommandSource source, String name,
                                            CommandContext<ServerCommandSource> context) {
        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null) return 0;

        String arguments = commandTail(context.getInput(), name);
        List<String> words = arguments.isEmpty() ? List.of() : List.of(arguments.split("\\s+"));
        Map<String, String> values = new LinkedHashMap<>();
        CommandSchema schema = runtime.commandSchema(name);
        if (schema != null) {
            for (String key : schema.argumentNames()) {
                try {
                    values.put(key, String.valueOf(context.getArgument(key, Object.class)));
                } catch (IllegalArgumentException ignored) {
                    // O argumento pertence a uma ramificação que não foi escolhida.
                }
            }
        }

        var player = source.getPlayer();
        boolean exists = runtime.runCommand(name,
                player == null ? null : new dev.lualoader.minecraft.FabricPlayerHandle(player),
                arguments, words, values);
        if (!exists) {
            source.sendError(Text.literal("Comando de mod desconhecido: " + name));
            return 0;
        }
        return 1;
    }

    private static String commandTail(String input, String name) {
        String value = input == null ? "" : input.trim();
        if (value.startsWith("/")) value = value.substring(1);
        String prefix = "mod " + name;
        if (value.equals(prefix)) return "";
        if (value.startsWith(prefix + " ")) return value.substring(prefix.length()).trim();
        return "";
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

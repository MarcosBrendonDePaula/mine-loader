package dev.lualoader.neoforge;

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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publica os comandos do loader e dos mods na árvore de comandos do jogo.
 *
 * <p>O runtime guarda o que {@code mod.command} declarou, mas guardar não publica: sem esta ponte,
 * {@code /mod} e {@code /lua} simplesmente não existem, e um mod que registrou um comando parece
 * ter carregado sem ter serventia. Foi o que aconteceu — o adaptador subia, dizia "9 de 9 mods
 * carregados", e nenhum comando respondia.
 *
 * <p>Mesma forma do adaptador Fabric, com a API desta plataforma: os comandos de mod ficam sob um
 * prefixo próprio, para não colidirem com os do jogo nem entre si.
 */
public final class NeoForgeCommands {
    private NeoForgeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerModCommands(dispatcher);

        dispatcher.register(Commands.literal("lua")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource())))
                .then(Commands.literal("commands")
                        .executes(context -> commands(context.getSource())))
                .then(Commands.literal("reload")
                        .executes(context -> reloadAll(context.getSource()))
                        .then(Commands.argument("mod_id", StringArgumentType.word())
                                .executes(context -> reloadOne(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "mod_id"))))));
    }

    private static void registerModCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null || runtime.commandNames().isEmpty()) return;

        var root = Commands.literal("mod");
        for (String name : runtime.commandNames()) {
            CommandSchema schema = runtime.commandSchema(name);
            var command = Commands.literal(name);
            if (schema == null) {
                command.executes(context -> runModCommand(context.getSource(), name, ""))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
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
        NeoForgeLuaLoader.LOGGER.info("Comandos de mod publicados: {}", runtime.commandNames());
    }

    private static ArgumentBuilder<CommandSourceStack, ?> buildNode(CommandSchema.Node node, String name) {
        ArgumentBuilder<CommandSourceStack, ?> builder;
        if (node.literal() != null) {
            LiteralArgumentBuilder<CommandSourceStack> literal = Commands.literal(node.literal());
            builder = literal;
        } else {
            CommandSchema.Argument argument = node.argument();
            RequiredArgumentBuilder<CommandSourceStack, ?> required =
                    Commands.argument(argument.name(), argumentType(argument));
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

    private static int runStructuredCommand(CommandSourceStack source, String name,
                                            CommandContext<CommandSourceStack> context) {
        var runtime = NeoForgeLuaLoader.luaRuntime();
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
                player == null ? null : new NeoForgePlayerHandle(player),
                arguments, words, values);
        if (!exists) {
            source.sendFailure(Component.literal("Comando de mod desconhecido: " + name));
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

    private static int runModCommand(CommandSourceStack source, String name, String arguments) {
        var runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null) return 0;

        // Pelo console nao ha jogador, e o comando precisa rodar mesmo assim: e o que permite
        // verificar um mod sem ninguem no jogo.
        var player = source.getPlayer();
        boolean exists = runtime.runCommand(name,
                player == null ? null : new NeoForgePlayerHandle(player),
                arguments);

        if (!exists) {
            source.sendFailure(Component.literal("Comando de mod desconhecido: " + name));
            return 0;
        }
        return 1;
    }

    private static int commands(CommandSourceStack source) {
        var runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null || runtime.commandNames().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Nenhum mod registrou comandos."), false);
            return 0;
        }

        var names = new java.util.ArrayList<>(runtime.commandNames());
        java.util.Collections.sort(names);

        source.sendSuccess(() -> Component.literal("Comandos de mod (" + names.size() + "):"), false);
        for (String name : names) {
            source.sendSuccess(() -> Component.literal("  /mod " + name), false);
        }
        return names.size();
    }

    private static int list(CommandSourceStack source) {
        var mods = NeoForgeLuaLoader.loadedMods();
        if (mods.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Nenhum mod Lua carregado."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Mods Lua carregados: " + mods.size()), false);
        for (var mod : mods) {
            source.sendSuccess(() -> Component.literal(
                    "- " + mod.manifest().id + " " + mod.manifest().version), false);
        }
        return mods.size();
    }

    private static int reloadAll(CommandSourceStack source) {
        var runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null) return 0;

        // reloadAll ja trata a falha de cada mod por dentro e devolve quantos deram certo: um mod
        // quebrado nao impede os outros de recarregar.
        int count = runtime.reloadAll();
        source.sendSuccess(() -> Component.literal("Scripts Lua recarregados: " + count), true);
        return count;
    }

    private static int reloadOne(CommandSourceStack source, String modId) {
        var runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null) return 0;

        try {
            if (runtime.reload(modId)) {
                source.sendSuccess(() -> Component.literal("Recarregado: " + modId), true);
                return 1;
            }
            source.sendFailure(Component.literal("Mod nao encontrado: " + modId));
        } catch (IOException error) {
            source.sendFailure(Component.literal("Falha ao recarregar " + modId + ": "
                    + error.getMessage()));
        }
        return 0;
    }
}

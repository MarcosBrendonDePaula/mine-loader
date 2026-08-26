package dev.lualoader.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.io.IOException;

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
            root = root.then(Commands.literal(name)
                    .executes(context -> runModCommand(context.getSource(), name, ""))
                    .then(Commands.argument("args", StringArgumentType.greedyString())
                            .executes(context -> runModCommand(context.getSource(), name,
                                    StringArgumentType.getString(context, "args")))));
        }

        dispatcher.register(root);
        NeoForgeLuaLoader.LOGGER.info("Comandos de mod publicados: {}", runtime.commandNames());
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

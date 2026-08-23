package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.TestBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Módulos Lua dentro de um mod.
 *
 * <p>O {@code require} padrão fica fora do ambiente, porque procuraria arquivos em qualquer lugar da
 * máquina. Sem um substituto, um mod não conseguia ter um {@code utils.lua} próprio: cada script era
 * compilado isolado, e a única forma de compartilhar código era duplicá-lo.
 */
class ModuleImportTest {

    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }
    }

    private void write(Path dir, String nome, String conteudo) throws IOException {
        Path arquivo = dir.resolve(nome);
        Files.createDirectories(arquivo.getParent());
        Files.writeString(arquivo, conteudo, StandardCharsets.UTF_8);
    }

    private ModLoader.LoadedMod writeMod(Path root, String main) throws IOException {
        Path dir = root.resolve("mod_lib");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "mod_lib",
                  "name": "Mod Lib",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), main, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0);
    }

    private LuaRuntime runtime(RecordingBridge bridge) {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        return runtime;
    }

    @Test
    void moduleIsLoadedAndReturnsItsValue(@TempDir Path root) throws IOException {
        Path dir = root.resolve("mod_lib");
        Files.createDirectories(dir);
        write(dir, "lib/mat.lua", """
                local M = {}
                function M.dobro(n) return n * 2 end
                return M
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(writeMod(root, """
                local mat = mod.import("lib/mat.lua")

                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("dobro de 21 = " .. mat.dobro(21))
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertEquals(List.of("dobro de 21 = 42"), bridge.calls);
    }

    @Test
    void moduleRunsOnlyOnceEvenIfImportedTwice(@TempDir Path root) throws IOException {
        Path dir = root.resolve("mod_lib");
        Files.createDirectories(dir);
        // Se rodasse duas vezes, o contador chegaria a 2.
        write(dir, "lib/contador.lua", """
                mod.state.execucoes = (mod.state.execucoes or 0) + 1
                return { valor = mod.state.execucoes }
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(writeMod(root, """
                local a = mod.import("lib/contador.lua")
                local b = mod.import("lib/contador.lua")

                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("execucoes=" .. mod.state.execucoes
                        .. " mesmo=" .. tostring(a == b))
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertEquals(List.of("execucoes=1 mesmo=true"), bridge.calls);
    }

    @Test
    void moduleSeesTheModApi(@TempDir Path root) throws IOException {
        Path dir = root.resolve("mod_lib");
        Files.createDirectories(dir);
        // Um modulo compartilha os globais do mod: enxerga mod.state e a API do loader.
        write(dir, "lib/estado.lua", """
                mod.state.marcado = "pelo modulo"
                return true
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(writeMod(root, """
                mod.import("lib/estado.lua")

                mod.on("server_started", function(ctx)
                    ctx.server.broadcast(ctx.state.marcado)
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertEquals(List.of("pelo modulo"), bridge.calls);
    }

    @Test
    void moduleCanImportAnother(@TempDir Path root) throws IOException {
        Path dir = root.resolve("mod_lib");
        Files.createDirectories(dir);
        write(dir, "lib/base.lua", "return { nome = \"base\" }\n");
        write(dir, "lib/topo.lua", """
                local base = mod.import("lib/base.lua")
                return { descricao = "topo sobre " .. base.nome }
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(writeMod(root, """
                local topo = mod.import("lib/topo.lua")

                mod.on("server_started", function(ctx)
                    ctx.server.broadcast(topo.descricao)
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertEquals(List.of("topo sobre base"), bridge.calls);
    }

    @Test
    void circularImportIsRefused(@TempDir Path root) throws IOException {
        Path dir = root.resolve("mod_lib");
        Files.createDirectories(dir);
        write(dir, "lib/a.lua", "local b = mod.import(\"lib/b.lua\")\nreturn {}\n");
        write(dir, "lib/b.lua", "local a = mod.import(\"lib/a.lua\")\nreturn {}\n");

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        // Um ciclo nao pode virar recursao infinita durante a carga.
        var mod = writeMod(root, "mod.import(\"lib/a.lua\")\nreturn {}\n");
        assertThrows(IOException.class, () -> runtime.load(mod));
    }

    @Test
    void moduleCannotEscapeTheModFolder(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("segredo.lua"), "return { chave = 1 }\n", StandardCharsets.UTF_8);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);
        var mod = writeMod(root, "mod.import(\"../segredo.lua\")\nreturn {}\n");

        assertThrows(IOException.class, () -> runtime.load(mod),
                "um modulo fora da pasta do mod nao pode ser carregado");
    }

    @Test
    void nonLuaPathIsRefused(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);
        var mod = writeMod(root, "mod.import(\"lib/config.json\")\nreturn {}\n");

        assertThrows(IOException.class, () -> runtime.load(mod));
    }

    @Test
    void standardRequireStaysOutOfReach(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        // O require do Lua procuraria arquivos em qualquer lugar da maquina.
        runtime.load(writeMod(root, """
                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("require=" .. tostring(require)
                        .. " dofile=" .. tostring(dofile)
                        .. " loadfile=" .. tostring(loadfile))
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertTrue(bridge.calls.get(0).contains("require=nil"), bridge.calls.toString());
        assertTrue(bridge.calls.get(0).contains("dofile=nil"), bridge.calls.toString());
        assertTrue(bridge.calls.get(0).contains("loadfile=nil"), bridge.calls.toString());
    }
}

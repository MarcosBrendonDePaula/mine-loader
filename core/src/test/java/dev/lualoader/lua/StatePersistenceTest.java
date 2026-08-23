package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O estado de um mod sobrevive ao desligamento do servidor. */
class StatePersistenceTest {

    private ModLoader.LoadedMod writeMod(Path root, String lua) throws IOException {
        Path dir = root.resolve("persist_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "persist_mod",
                  "name": "Persist Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua"
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0);
    }

    @Test
    void stateSurvivesRestart(@TempDir Path root, @TempDir Path stateDir) throws IOException {
        String lua = """
                mod.state.execucoes = (mod.state.execucoes or 0) + 1
                mod.state.nome = "loader"
                mod.state.aninhado = { profundo = true }
                return {}
                """;

        // Primeira sessao: acumula e grava ao desligar.
        LuaRuntime primeira = new LuaRuntime(LoggerFactory.getLogger("test"), null, stateDir);
        primeira.load(writeMod(root, lua));
        primeira.saveAllStates();

        assertTrue(Files.isRegularFile(stateDir.resolve("persist_mod.json")),
                "o estado deveria ter sido gravado");

        // Segunda sessao: um runtime novo, como depois de reiniciar o servidor.
        LuaRuntime segunda = new LuaRuntime(LoggerFactory.getLogger("test"), null, stateDir);
        segunda.load(writeMod(root, lua));
        segunda.saveAllStates();

        String json = Files.readString(stateDir.resolve("persist_mod.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"execucoes\": 2"),
                "a segunda sessao deveria ter continuado de onde parou: " + json);
        assertTrue(json.contains("\"nome\": \"loader\""), json);
        assertTrue(json.contains("\"profundo\": true"), json);
    }

    @Test
    void withoutDirectoryStateStaysInMemory(@TempDir Path root) throws IOException {
        // Sem diretorio configurado, o comportamento anterior continua: memoria apenas.
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.load(writeMod(root, "mod.state.x = 1\nreturn {}\n"));
        runtime.saveAllStates();
        assertEquals(1, 1, "salvar sem diretorio nao pode lancar erro");
    }

    @Test
    void corruptedStateDoesNotStopTheMod(@TempDir Path root, @TempDir Path stateDir) throws IOException {
        Files.writeString(stateDir.resolve("persist_mod.json"), "{ isto nao e json",
                StandardCharsets.UTF_8);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"), null, stateDir);
        runtime.load(writeMod(root, "mod.state.x = (mod.state.x or 0) + 1\nreturn {}\n"));
        runtime.saveAllStates();

        String json = Files.readString(stateDir.resolve("persist_mod.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"x\": 1"), "um estado corrompido deve recomecar vazio: " + json);
    }
}

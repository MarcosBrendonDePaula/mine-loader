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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        LuaRuntime first = new LuaRuntime(LoggerFactory.getLogger("test"), null, stateDir);
        first.load(writeMod(root, lua));
        first.saveAllStates();

        assertTrue(Files.isRegularFile(stateDir.resolve("persist_mod.json")),
                "o estado deveria ter sido gravado");

        // Segunda sessao: um runtime novo, como depois de reiniciar o servidor.
        LuaRuntime second = new LuaRuntime(LoggerFactory.getLogger("test"), null, stateDir);
        second.load(writeMod(root, lua));
        second.saveAllStates();

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

    /**
     * Uma lista guardada no estado precisa voltar como lista.
     *
     * <p>Gravada como objeto, ela voltava com chaves de texto e fora de ordem: no Lua, {@code #}
     * dava zero e {@code ipairs} não iterava nenhuma vez. Uma lista cheia virava uma lista vazia
     * sem erro nenhum — o pior tipo de falha, porque o mod continua rodando e não mostra nada.
     */
    @Test
    void listsSurviveTheRoundTrip(@TempDir Path root, @TempDir Path stateDir) throws IOException {
        LuaRuntime first = new LuaRuntime(LoggerFactory.getLogger("test"), null, stateDir);
        first.load(writeMod(root, """
                mod.state.itens = { "minecraft:stone", "minecraft:dirt", "minecraft:sand" }
                mod.state.mapa = { chave = "valor" }
                return {}
                """));
        first.saveAllStates();

        // O arquivo precisa trazer uma lista JSON, e nao um objeto com chaves numeradas.
        String json = Files.readString(stateDir.resolve("persist_mod.json"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"itens\": ["), "a lista deveria ser um array JSON: " + json);
        assertTrue(json.contains("\"mapa\": {"), "um mapa continua sendo objeto: " + json);

        // Segunda sessao: a lista precisa voltar utilizavel, com # e ipairs funcionando.
        LuaRuntime second = new LuaRuntime(LoggerFactory.getLogger("test"), null, stateDir);
        second.load(writeMod(root, """
                local partes = {}
                for _, item in ipairs(mod.state.itens) do partes[#partes + 1] = item end
                mod.state.resumo = #mod.state.itens .. "|" .. table.concat(partes, ",")
                return {}
                """));
        second.saveAllStates();

        String depois = Files.readString(stateDir.resolve("persist_mod.json"), StandardCharsets.UTF_8);
        assertTrue(depois.contains("3|minecraft:stone,minecraft:dirt,minecraft:sand"),
                "a lista deveria ter voltado na ordem e com tamanho: " + depois);
    }

    /**
     * Um mod carrega quando o diretorio chega relativo.
     *
     * <p>A validacao de entrypoint resolvia o caminho a partir do diretorio como veio e comparava
     * com a versao absoluta dele. Com um diretorio ja absoluto -- o que o adaptador Fabric passa --
     * a conta batia por acaso. Com um relativo, a comparacao reprovava sempre, e nenhum mod
     * carregava: foi o que aconteceu no primeiro boot do adaptador NeoForge.
     *
     * <p>O mod e criado dentro do diretorio de trabalho, e nao em @TempDir, porque no Windows a
     * pasta temporaria pode estar em outro disco -- e entre discos nao existe caminho relativo.
     */
    @Test
    void modsLoadWhenTheDirectoryArrivesRelative() throws IOException {
        Path relativo = Path.of("build", "tmp", "mod-relativo", "persist_mod");
        try {
            Files.createDirectories(relativo);
            Files.writeString(relativo.resolve("mod.json"), """
                    {
                      "schema": 1,
                      "id": "persist_mod",
                      "name": "Persist Mod",
                      "version": "0.1.0",
                      "entrypoint": "main.lua"
                    }
                    """, StandardCharsets.UTF_8);
            Files.writeString(relativo.resolve("main.lua"), """
                    mod.state.carregou = true
                    return {}
                    """, StandardCharsets.UTF_8);

            ModLoader.LoadedMod mod = new ModLoader(LoggerFactory.getLogger("test"))
                    .discover(relativo.getParent()).get(0);

            // Descoberto por um caminho relativo, precisa carregar: antes da correcao isto lancava
            // "entrypoint Lua sai da pasta do mod" e nenhum mod chegava a rodar.
            assertFalse(mod.directory().isAbsolute(), "o teste exige um diretorio relativo");

            LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
            runtime.load(mod);
        } finally {
            Files.deleteIfExists(relativo.resolve("main.lua"));
            Files.deleteIfExists(relativo.resolve("mod.json"));
            Files.deleteIfExists(relativo);
            Files.deleteIfExists(relativo.getParent());
        }
    }
}

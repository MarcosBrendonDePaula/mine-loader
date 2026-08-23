package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.BlockEventData;
import dev.lualoader.platform.TestBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Um script que não termina não pode parar o servidor.
 *
 * <p>Este é o teste que separa "rodo meus próprios mods" de "aceito mods de terceiros": sem o
 * limite, um laço infinito em qualquer mod prende a thread principal do jogo para sempre.
 */
class ExecutionBudgetTest {

    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }
    }

    private ModLoader.LoadedMod writeMod(Path root, String lua) throws IOException {
        Path dir = root.resolve("budget_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "budget_mod",
                  "name": "Budget Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0);
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void infiniteLoopIsInterrupted(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, """
                mod.on("block_used", function(ctx)
                    while true do end
                end)
                """));

        long start = System.nanoTime();
        runtime.triggerBlock("block_used", null, new BlockEventData("budget_mod:b", 0, 0, 0, 0, 1));
        long elapsed = (System.nanoTime() - start) / 1_000_000L;

        // O tempo exato varia com a maquina; o que importa e ter terminado, e rapido.
        assertTrue(elapsed < 5_000,
                "o laco infinito deveria ter sido cortado, mas levou " + elapsed + " ms");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void oneSlowModDoesNotBlockTheNextCallback(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, """
                local voltas = 0

                mod.on("block_used", function(ctx)
                    voltas = voltas + 1
                    if voltas == 1 then
                        while true do end
                    end
                    ctx.server.broadcast("segunda chamada rodou")
                end)
                """));

        var event = new BlockEventData("budget_mod:b", 0, 0, 0, 0, 1);
        runtime.triggerBlock("block_used", null, event);
        runtime.triggerBlock("block_used", null, event);

        assertEquals(List.of("segunda chamada rodou"), bridge.calls,
                "o limite vale por callback: o seguinte precisa rodar normalmente");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void normalScriptIsNotAffected(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // O trabalho e pequeno de proposito. Uma versao anterior deste teste usava vinte mil
        // iteracoes e passava aqui, mas era interrompida na maquina do CI, mais lenta: o teste
        // media a velocidade da maquina em vez do comportamento do limite.
        runtime.load(writeMod(root, """
                mod.on("block_used", function(ctx)
                    local soma = 0
                    for i = 1, 500 do
                        soma = soma + i
                    end
                    ctx.server.broadcast("soma " .. soma)
                end)
                """));

        runtime.triggerBlock("block_used", null, new BlockEventData("budget_mod:b", 0, 0, 0, 0, 1));

        assertEquals(List.of("soma 125250"), bridge.calls,
                "um script comum precisa terminar sem ser cortado");
    }
}

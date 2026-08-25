package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.BlockEventData;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O tique agendado por posição: {@code schedule_block} de um lado, {@code on_scheduled} do outro.
 *
 * <p>Era a lacuna que mais aparecia no mod migrado. Sem ela, um item saía de um baú e aparecia no
 * outro sem viagem nenhuma — a rede funcionava e não se parecia com a do original.
 *
 * <p>O que se verifica aqui é o par completo: o script consegue pedir, o pedido chega à plataforma
 * com o prazo certo, e o evento de volta encontra a função declarada no manifesto. Testar só um dos
 * lados deixaria passar o caso pior — o script agenda, e nunca é chamado de volta.
 */
class ScheduledTickTest {

    /** Guarda o que foi agendado em vez de disparar: o dublê não pode adivinhar o relógio. */
    private static final class Bridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add("broadcast:" + message);
        }
    }

    private ModLoader.LoadedMod writeMod(Path root, String permissions, String behavior, String lua)
            throws IOException {
        Path dir = root.resolve("canos");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "canos",
                  "name": "Canos",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s],
                  "blocks": [{"id": "cano", "name": "Cano", "behavior": %s}]
                }
                """.formatted(permissions, behavior), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);

        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size(), "o mod do teste deveria ter carregado");
        return mods.get(0);
    }

    private static final String SEM_BEHAVIOR = "{}";

    @Test
    void oScriptAgendaEOPrazoChegaNaPlataforma(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"world.write\"", SEM_BEHAVIOR, """
                mod.on("server_started", function(ctx)
                    ctx.server.schedule_block(10, 64, -3, 20)
                end)
                """));

        runtime.triggerAll("server_started", null);

        // Posicao e prazo, exatamente como pedidos. Um prazo que se perdesse no caminho daria uma
        // rede que anda na velocidade errada -- e nada no log diria por que.
        assertEquals(List.of("10,64,-3,20"), bridge.scheduledTicks);
    }

    @Test
    void semPermissaoDeEscritaOPedidoNaoChegaAoJogo(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // O manifesto declara so chat.send: agendar mexe no mundo, e nao pode passar por fora.
        runtime.load(writeMod(root, "\"chat.send\"", SEM_BEHAVIOR, """
                mod.on("server_started", function(ctx)
                    ctx.server.schedule_block(1, 2, 3, 5)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertTrue(bridge.scheduledTicks.isEmpty(),
                "agendar sem world.write deveria parar antes da plataforma");
    }

    @Test
    void prazoForaDaFaixaERecusadoAntesDaPlataforma(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"world.write\"", SEM_BEHAVIOR, """
                mod.on("server_started", function(ctx)
                    ctx.server.schedule_block(1, 2, 3, 0)
                    ctx.server.schedule_block(1, 2, 3, -5)
                    ctx.server.schedule_block(1, 2, 3, 24001)
                end)
                """));

        runtime.triggerAll("server_started", null);

        // Zero e negativo o jogo trataria como "agora", o que de dentro do proprio tique e recursao
        // sem folga; e um prazo enorme fica gravado no chunk esperando um bloco que ninguem lembra.
        assertTrue(bridge.scheduledTicks.isEmpty(),
                "nenhum dos tres prazos deveria ter chegado: " + bridge.scheduledTicks);
    }

    @Test
    void oTiqueDeVoltaEncontraAFuncaoDeclarada(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"chat.send\"",
                "{\"on_scheduled\": \"acordou\"}", """
                local function acordou(ctx)
                    ctx.server.broadcast("acordei em " .. ctx.block.x .. "," .. ctx.block.z)
                end
                return { acordou = acordou }
                """));

        runtime.triggerBlock("block_scheduled", null,
                new BlockEventData("canos:cano", 7, 64, 9, 0, 1));

        assertEquals(List.of("broadcast:acordei em 7,9"), bridge.calls);
    }

    @Test
    void semOnScheduledDeclaradoOTiqueNaoFazNada(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // on_random_tick nao e on_scheduled: um evento cair no gancho do outro faria o bloco reagir
        // ao relogio do jogo achando que reage ao proprio pedido.
        runtime.load(writeMod(root, "\"chat.send\"",
                "{\"on_random_tick\": \"outro\"}", """
                local function outro(ctx)
                    ctx.server.broadcast("nao era para eu ser chamado")
                end
                return { outro = outro }
                """));

        runtime.triggerBlock("block_scheduled", null,
                new BlockEventData("canos:cano", 7, 64, 9, 0, 1));

        assertTrue(bridge.calls.isEmpty(), "o tique agendado achou o gancho errado: " + bridge.calls);
    }

    @Test
    void oCicloCompletoAndaUmPassoPorTique(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // O caso do cano: cada tique anda um bloco e agenda o proximo. E assim que se pede uma
        // repeticao -- o loader nao repete sozinho, senao desligar viraria problema dele.
        runtime.load(writeMod(root, "\"chat.send\", \"world.write\"",
                "{\"on_scheduled\": \"andar\"}", """
                local function andar(ctx)
                    local x = ctx.block.x + 1
                    ctx.server.broadcast("passei por " .. x)
                    if x < 3 then
                        ctx.server.schedule_block(x, ctx.block.y, ctx.block.z, 4)
                    end
                end
                return { andar = andar }
                """));

        for (int x = 0; x < 3; x++) {
            runtime.triggerBlock("block_scheduled", null,
                    new BlockEventData("canos:cano", x, 64, 0, 0, 1));
        }

        assertEquals(List.of("broadcast:passei por 1", "broadcast:passei por 2",
                "broadcast:passei por 3"), bridge.calls);
        // Dois agendamentos, e nao tres: o ultimo passo chegou ao destino e parou.
        assertEquals(List.of("1,64,0,4", "2,64,0,4"), bridge.scheduledTicks);
    }
}

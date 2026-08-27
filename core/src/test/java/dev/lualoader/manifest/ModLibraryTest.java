package dev.lualoader.manifest;

import dev.lualoader.lua.LuaRuntime;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Um mod usado como biblioteca por outro: ordem de carga, exportação e permissões. */
class ModLibraryTest {

    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add("broadcast:" + message);
        }

        @Override
        public void setBlock(String blockId, int x, int y, int z) {
            calls.add("set:" + blockId);
        }

    }

    private void writeMod(Path root, String id, String permissions, String dependencies, String lua)
            throws IOException {
        writeModWithRequirements(root, id, permissions, dependencies, "{}", lua);
    }

    private void writeModWithRequirements(Path root, String id, String permissions,
                                          String dependencies, String requirements, String lua)
            throws IOException {
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "%s",
                  "name": "%s",
                  "version": "1.0.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s],
                  "dependencies": {%s},
                  "requires": %s
                }
                """.formatted(id, id, permissions, dependencies, requirements), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
    }

    private List<ModLoader.LoadedMod> discover(Path root) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root);
    }

    private List<ModLoader.LoadedMod> discover(Path root, RuntimeContract contract) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test"), null, contract).discover(root);
    }

    @Test
    void satisfiedCapabilityAndDomainRequirementsAllowTheMod(@TempDir Path root) throws IOException {
        writeModWithRequirements(root, "contract_app", "", "",
                """
                {
                  "domains": {"world": "1.0.0", "player": "1.0.0"},
                  "capabilities": {
                    "world.block_state.read": "1.0.0",
                    "player.looking_at.read": "1.0.0"
                  }
                }
                """, "return {}\n");

        assertEquals(List.of("contract_app"),
                discover(root).stream().map(mod -> mod.manifest().id).toList());
    }

    @Test
    void missingCapabilityRejectsOnlyTheDependentMod(@TempDir Path root) throws IOException {
        writeMod(root, "survivor", "", "", "return {}\n");
        writeModWithRequirements(root, "contract_app", "", "",
                "{\"capabilities\": {\"world.not_available.read\": \"1.0.0\"}}",
                "return {}\n");

        assertEquals(List.of("survivor"),
                discover(root).stream().map(mod -> mod.manifest().id).toList());
    }

    @Test
    void insufficientDomainVersionRejectsTheMod(@TempDir Path root) throws IOException {
        writeModWithRequirements(root, "contract_app", "", "",
                "{\"domains\": {\"world\": \"2.0.0\"}}", "return {}\n");
        RuntimeContract profile = new RuntimeContract("test/limited",
                java.util.Map.of("world", "1.4.0"), java.util.Map.of());

        assertTrue(discover(root, profile).isEmpty());
    }

    @Test
    void malformedRequirementIsRejectedBeforeRuntimeNegotiation(@TempDir Path root) throws IOException {
        writeModWithRequirements(root, "contract_app", "", "",
                "{\"capabilities\": {\"world..read\": \"latest\"}}", "return {}\n");

        assertTrue(discover(root).isEmpty());
    }

    @Test
    void malformedContractVersionIsRejected(@TempDir Path root) throws IOException {
        writeModWithRequirements(root, "contract_app", "", "",
                "{\"domains\": {\"world\": \"latest\"}}", "return {}\n");

        assertTrue(discover(root).isEmpty());
    }

    @Test
    void modDependencyAndRuntimeRequirementRemainSeparate(@TempDir Path root) throws IOException {
        writeMod(root, "library_provider", "", "", "return { value = 7 }\n");
        writeModWithRequirements(root, "contract_app", "", "\"library_provider\": \"1.0.0\"",
                "{\"capabilities\": {\"world.block_state.read\": \"1.0.0\"}}",
                "local lib = mod.require(\"library_provider\")\nreturn lib\n");

        List<ModLoader.LoadedMod> mods = discover(root);
        assertEquals(List.of("library_provider", "contract_app"),
                mods.stream().map(mod -> mod.manifest().id).toList());
    }

    @Test
    void documentedRequirementExamplesLoadTogether() throws IOException {
        Path examples = Path.of("..", "docs", "examples");
        List<String> ids = discover(examples).stream()
                .map(mod -> mod.manifest().id)
                .toList();

        assertEquals(List.of("capability_consumer", "domain_consumer",
                        "library_provider", "full_consumer"), ids);
    }

    @Test
    void dependencyLoadsBeforeWhoNeedsIt(@TempDir Path root) throws IOException {
        // "zeta_lib" viria depois de "alpha_app" em ordem alfabetica; a dependencia inverte isso.
        writeMod(root, "zeta_lib", "", "", "return {}\n");
        writeMod(root, "alpha_app", "", "\"zeta_lib\": \"1.0.0\"", "return {}\n");

        List<String> order = discover(root).stream().map(mod -> mod.manifest().id).toList();
        assertEquals(List.of("zeta_lib", "alpha_app"), order);
    }

    @Test
    void libraryFunctionIsReachableThroughRequire(@TempDir Path root) throws IOException {
        writeMod(root, "ui_lib", "\"chat.send\"", "", """
                local function titulo(texto)
                    return "[ " .. texto .. " ]"
                end
                return { titulo = titulo }
                """);
        writeMod(root, "app_mod", "\"chat.send\"", "\"ui_lib\": \"1.0.0\"", """
                local ui = mod.require("ui_lib")

                mod.on("server_started", function(ctx)
                    ctx.server.broadcast(ui.titulo("bem-vindo"))
                end)
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        for (ModLoader.LoadedMod mod : discover(root)) runtime.load(mod);
        runtime.triggerAll("server_started", null);

        assertEquals(List.of("broadcast:[ bem-vindo ]"), bridge.calls);
    }

    @Test
    void libraryRunsWithItsOwnPermissions(@TempDir Path root) throws IOException {
        // A lib tem chat.send; quem chama, nao. Usando mod.server, a lib roda com os proprios
        // poderes, que foi a politica escolhida para este loader.
        writeMod(root, "ui_lib", "\"chat.send\"", "", """
                local function anunciar(texto)
                    mod.server.broadcast(texto)
                end
                return { anunciar = anunciar }
                """);
        writeMod(root, "app_mod", "", "\"ui_lib\": \"1.0.0\"", """
                local ui = mod.require("ui_lib")

                mod.on("server_started", function(ctx)
                    ui.anunciar("vindo da lib")
                end)
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        for (ModLoader.LoadedMod mod : discover(root)) runtime.load(mod);
        runtime.triggerAll("server_started", null);

        assertEquals(List.of("broadcast:vindo da lib"), bridge.calls,
                "a lib deve conseguir agir com a propria permissao");
    }

    @Test
    void callerPermissionsStillApplyToItsOwnContext(@TempDir Path root) throws IOException {
        // O outro lado da politica: ctx.server continua sendo do mod que recebeu o evento, entao
        // um mod sem chat.send nao ganha a permissao so por ter uma lib instalada.
        writeMod(root, "ui_lib", "\"chat.send\"", "", "return { nada = function() end }\n");
        writeMod(root, "app_mod", "", "\"ui_lib\": \"1.0.0\"", """
                local ui = mod.require("ui_lib")

                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("nao deveria passar")
                end)
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        for (ModLoader.LoadedMod mod : discover(root)) runtime.load(mod);
        runtime.triggerAll("server_started", null);

        assertTrue(bridge.calls.isEmpty(), "ctx.server usa as permissoes de quem recebeu o evento");
    }

    @Test
    void requireDemandsDeclaredDependency(@TempDir Path root) throws IOException {
        writeMod(root, "ui_lib", "", "", "return { titulo = function() return \"x\" end }\n");
        // Nao declara a dependencia, mas tenta usar a lib assim mesmo.
        writeMod(root, "app_mod", "\"chat.send\"", "", """
                mod.on("server_started", function(ctx)
                    local ui = mod.require("ui_lib")
                    ctx.server.broadcast(ui.titulo())
                end)
                """);

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        for (ModLoader.LoadedMod mod : discover(root)) runtime.load(mod);
        runtime.triggerAll("server_started", null);

        assertTrue(bridge.calls.isEmpty(), "usar uma lib exige declara-la em dependencies");
    }

    @Test
    void missingDependencyRejectsOnlyTheDependentMod(@TempDir Path root) throws IOException {
        writeMod(root, "solo_mod", "", "", "return {}\n");
        writeMod(root, "app_mod", "", "\"nao_existe\": \"1.0.0\"", "return {}\n");

        List<String> ids = discover(root).stream().map(mod -> mod.manifest().id).toList();
        assertEquals(List.of("solo_mod"), ids, "o mod sem dependencia satisfeita fica de fora");
    }

    @Test
    void insufficientVersionIsRejected(@TempDir Path root) throws IOException {
        writeMod(root, "ui_lib", "", "", "return {}\n");
        writeMod(root, "app_mod", "", "\"ui_lib\": \"2.0.0\"", "return {}\n");

        List<String> ids = discover(root).stream().map(mod -> mod.manifest().id).toList();
        assertEquals(List.of("ui_lib"), ids, "versao menor que a exigida nao satisfaz");
    }

    @Test
    void circularDependencyIsRejected(@TempDir Path root) throws IOException {
        writeMod(root, "mod_a", "", "\"mod_b\": \"1.0.0\"", "return {}\n");
        writeMod(root, "mod_b", "", "\"mod_a\": \"1.0.0\"", "return {}\n");

        assertTrue(discover(root).isEmpty(), "um ciclo nao pode carregar nenhum dos lados");
    }

    @Test
    void versionComparisonHandlesCommonFormats() {
        assertTrue(ModDependencies.satisfies("1.2.3", "1.2.3"));
        assertTrue(ModDependencies.satisfies("1.3.0", "1.2.9"));
        assertTrue(ModDependencies.satisfies("2.0.0", "1.9.9"));
        assertTrue(ModDependencies.satisfies("1.2.0-beta", "1.2.0"));
        assertTrue(ModDependencies.satisfies("1.0.0", ""));
        assertFalse(ModDependencies.satisfies("1.2.3", "1.2.4"));
        assertFalse(ModDependencies.satisfies("0.9.0", "1.0.0"));
    }
}

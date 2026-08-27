package dev.lualoader.camera;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraTest {
    @Test
    void protocolRoundTripQualifiesIdsAndAcceptsLongModIds() {
        String longModId = "a".repeat(CameraProtocol.MAX_MOD_ID_LENGTH);
        CameraProtocol.Camera camera = new CameraProtocol.Camera(
                longModId, "view", "orthographic", "world", "player", "north",
                64, 32, 5, "texture");

        assertEquals(longModId + ":view", camera.qualifiedId());
        CameraProtocol.Camera numericMod = new CameraProtocol.Camera(
                "2mod", "view", "orthographic", "world", "player", "north",
                64, 32, 5, "texture");
        assertEquals("2mod:view", numericMod.qualifiedId());
        assertEquals(List.of(camera), CameraProtocol.decode(CameraProtocol.encode(List.of(camera))));
        assertThrows(IllegalArgumentException.class, () -> CameraProtocol.decode(
                "[{\"modId\":\"test\",\"id\":\"view\",\"projection\":\"orthographic\","
                        + "\"source\":\"world\",\"anchor\":\"player\",\"orientation\":\"north\","
                        + "\"resolution\":64,\"radius\":32,\"updateTicks\":5,\"output\":\"texture\"},"
                        + "{\"modId\":\"test\",\"id\":\"view\",\"projection\":\"orthographic\","
                        + "\"source\":\"world\",\"anchor\":\"player\",\"orientation\":\"north\","
                        + "\"resolution\":64,\"radius\":32,\"updateTicks\":5,\"output\":\"texture\"}]"));
    }

    @Test
    void staticCameraIsValidatedRegisteredAndPublished(@TempDir Path root) throws IOException {
        ModLoader.LoadedMod mod = writeMod(root, "return {}", """
                "cameras": {
                  "minimap": {
                    "projection": "orthographic",
                    "source": "world",
                    "anchor": "player",
                    "orientation": "north",
                    "resolution": 64,
                    "radius": 32,
                    "update_ticks": 5,
                    "output": "texture"
                  }
                }
                """);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("camera-test"));
        runtime.load(mod);

        assertEquals(List.of("test_mod:minimap"),
                runtime.cameraDefinitions().stream().map(CameraProtocol.Camera::qualifiedId).toList());
        assertEquals(runtime.cameraDefinitions(),
                CameraProtocol.decode(CameraProtocol.encode(runtime.cameraDefinitions())));
    }

    @Test
    void luaCameraCanBeRegisteredAfterCompilationAndRefreshesClients(@TempDir Path root)
            throws IOException {
        ModLoader.LoadedMod mod = writeMod(root, """
                mod.after(0, function()
                    mod.camera("minimap", { resolution = 64, radius = 32, update_ticks = 5 })
                end)
                """, "");
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("camera-test"));
        AtomicInteger refreshes = new AtomicInteger();
        runtime.onCamerasChanged(refreshes::incrementAndGet);
        runtime.load(mod);

        assertTrue(runtime.cameraDefinitions().isEmpty());
        runtime.advanceScheduler();

        assertEquals(1, refreshes.get());
        assertEquals("test_mod:minimap", runtime.cameraDefinitions().get(0).qualifiedId());
    }

    @Test
    void dynamicCameraWithoutPermissionOrCapabilityIsRejected(@TempDir Path root) throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "test_mod",
                  "name": "Test Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [],
                  "requires": { "capabilities": { "client.camera.virtual": "1.0.0" } }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"),
                "mod.camera(\"minimap\", { resolution = 64 })", StandardCharsets.UTF_8);

        ModLoader.LoadedMod mod = new ModLoader(LoggerFactory.getLogger("camera-test"))
                .discover(root).get(0);
        assertThrows(IOException.class, () -> new LuaRuntime(LoggerFactory.getLogger("camera-test")).load(mod));
    }

    @Test
    void manifestAndLuaCameraMustBeIdentical(@TempDir Path root) throws IOException {
        ModLoader.LoadedMod mod = writeMod(root, """
                mod.camera("minimap", { resolution = 65, radius = 32, update_ticks = 5 })
                """, """
                "cameras": { "minimap": {
                  "resolution": 64, "radius": 32, "update_ticks": 5
                } }
                """);

        assertThrows(IOException.class,
                () -> new LuaRuntime(LoggerFactory.getLogger("camera-test")).load(mod));
    }

    @Test
    void failedReloadRestoresThePreviousCamera(@TempDir Path root) throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest("""
                "cameras": {
                  "minimap": { "resolution": 64, "radius": 32, "update_ticks": 5 }
                }
                """, ""), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);
        ModLoader loader = new ModLoader(LoggerFactory.getLogger("camera-test"));
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("camera-test"));
        runtime.load(loader.discover(root).get(0));
        assertEquals(64, runtime.cameraDefinitions().get(0).resolution());

        Files.writeString(dir.resolve("main.lua"),
                "mod.camera(\"minimap\", { resolution = 65, radius = 32, update_ticks = 5 })",
                StandardCharsets.UTF_8);
        ModLoader.LoadedMod broken = loader.discover(root).get(0);
        assertThrows(IOException.class, () -> runtime.load(broken));
        assertEquals(64, runtime.cameraDefinitions().get(0).resolution());
    }

    private ModLoader.LoadedMod writeMod(Path root, String lua, String cameraJson)
            throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest(cameraJson, ""), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        List<ModLoader.LoadedMod> mods = new ModLoader(LoggerFactory.getLogger("camera-test"))
                .discover(root);
        assertEquals(1, mods.size());
        return mods.get(0);
    }

    private String manifest(String cameraJson, String ignored) {
        return """
                {
                  "schema": 1,
                  "id": "test_mod",
                  "name": "Test Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["client.camera.register"],
                  "requires": { "capabilities": { "client.camera.virtual": "1.0.0" } },
                  %s
                }
                """.formatted(cameraJson == null || cameraJson.isBlank() ? "\"cameras\": {}" : cameraJson);
    }
}

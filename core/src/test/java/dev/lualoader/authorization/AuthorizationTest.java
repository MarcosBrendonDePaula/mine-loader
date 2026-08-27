package dev.lualoader.authorization;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationTest {
    private ModLoader.LoadedMod writeMod(Path root, String id, String lua,
                                         boolean capability) throws IOException {
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        String requires = capability
                ? "\"requires\": {\"domains\": {}, \"capabilities\": {\"events.action.authorization\": \"1.0.0\"}}"
                : "\"requires\": {\"domains\": {}, \"capabilities\": {}}";
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  %s
                }
                """.formatted(id, id, requires), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).stream()
                .filter(found -> id.equals(found.manifest().id))
                .findFirst()
                .orElseThrow();
    }

    private LuaRuntime runtimeWith(Path root, ModLoader.LoadedMod... mods) throws IOException {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        for (ModLoader.LoadedMod mod : mods) runtime.load(mod);
        return runtime;
    }

    @Test
    void snapshotIsExposedAsPortableScalars(@TempDir Path root) throws IOException {
        var mod = writeMod(root, "authorizer", """
                mod.on("action_attempt", function(ctx)
                    return ctx.action == "block.use"
                        and ctx.dimension == "minecraft:overworld"
                        and ctx.x == 4 and ctx.y == 5 and ctx.z == 6
                        and ctx.target.id == "minecraft:chest"
                        and ctx.actor.uuid == "00000000-0000-0000-0000-000000000001"
                        and ctx.actor.name == "Alex"
                        and ctx.source == "player"
                        and ctx.face == "north"
                end)
                """, true);
        LuaRuntime runtime = runtimeWith(root, mod);
        assertFalse(runtime.triggerAuthorization(new AuthorizationEventData(
                AuthorizationActions.BLOCK_USE, "minecraft:overworld", 4, 5, 6,
                "minecraft:chest", "00000000-0000-0000-0000-000000000001",
                "Alex", "player", "north"), null));
    }

    @Test
    void anyExplicitVetoWinsAcrossMods(@TempDir Path root) throws IOException {
        var observer = writeMod(root, "observer", """
                mod.on("action_attempt", function(ctx)
                    return nil
                end)
                """, true);
        var veto = writeMod(root, "veto", """
                mod.on("action_attempt", function(ctx)
                    return false
                end)
                """, true);
        LuaRuntime runtime = runtimeWith(root, observer, veto);
        assertTrue(runtime.triggerAuthorization(new AuthorizationEventData(
                AuthorizationActions.BLOCK_BREAK, "minecraft:overworld", 0, 64, 0,
                "minecraft:stone", null, null, "player", null), null));
    }

    @Test
    void authorizationErrorsFailClosed(@TempDir Path root) throws IOException {
        var broken = writeMod(root, "broken_auth", """
                mod.on("action_attempt", function(ctx)
                    error("policy quebrada")
                end)
                """, true);
        LuaRuntime runtime = runtimeWith(root, broken);
        assertTrue(runtime.triggerAuthorization(new AuthorizationEventData(
                AuthorizationActions.BLOCK_PLACE, "minecraft:overworld", 1, 65, 1,
                "minecraft:stone", null, null, "player", "up"), null));
    }

    @Test
    void actionAttemptRequiresCapability(@TempDir Path root) throws IOException {
        var missing = writeMod(root, "missing_cap", """
                mod.on("action_attempt", function(ctx)
                    return false
                end)
                """, false);
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        assertThrows(IOException.class, () -> runtime.load(missing));
    }

    @Test
    void actionVocabularyAndDtoRejectInvalidData() {
        assertTrue(AuthorizationActions.isKnown("block.break"));
        assertFalse(AuthorizationActions.isKnown("container.open"));
        assertThrows(IllegalArgumentException.class, () -> new AuthorizationEventData(
                "container.open", "minecraft:overworld", 0, 0, 0,
                null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new AuthorizationEventData(
                AuthorizationActions.BLOCK_USE, null, 0, 0, 0,
                null, null, null, null, null));
    }
}

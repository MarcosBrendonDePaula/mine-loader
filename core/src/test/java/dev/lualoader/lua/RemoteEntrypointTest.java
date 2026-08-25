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
 * O script principal vindo da base remota, e não do disco.
 *
 * <p>Era o único pedaço de um mod que ainda exigia arquivo local. Módulo, comportamento de bloco,
 * textura, modelo e {@code $import} já caíam na base remota quando o arquivo não existia — só o
 * começo do mod não. Um mod publicado na web podia ter tudo remoto menos o próprio começo, e a
 * instalação por um {@code mod.json} de poucas linhas ficava a um arquivo de distância.
 *
 * <p><b>Sem rede.</b> O cache é pré-populado, como nos outros testes de conteúdo remoto do
 * repositório: o que se verifica é a decisão do loader — procurar na base quando não achou no disco
 * —, e não a biblioteca de HTTP.
 */
class RemoteEntrypointTest {

    private static final class Bridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }
    }

    private static String sha256Hex(String content) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    /** Deixa no cache o que a base remota responderia para aquele endereço. */
    private static void publicar(Path cache, String url, String conteudo) throws Exception {
        Files.createDirectories(cache);
        Files.writeString(cache.resolve(sha256Hex(url) + ".latest"), conteudo,
                StandardCharsets.UTF_8);
    }

    private static final String BASE = "https://exemplo.invalido/logistica/";

    /** Um mod sem nenhum .lua no disco: só o manifesto. */
    private Path escreverManifesto(Path root, String extra) throws IOException {
        Path dir = root.resolve("remoto");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "remoto",
                  "name": "Remoto",
                  "version": "0.1.0",
                  "remote_base": "%s",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send"],
                  "events": { "server_started": "on_started" }
                  %s
                }
                """.formatted(BASE, extra), StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void oScriptPrincipalVemDaBaseRemota(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        publicar(cache, BASE + "main.lua", """
                local function on_started(ctx)
                    ctx.server.broadcast("vim da rede")
                end
                return { on_started = on_started }
                """);

        escreverManifesto(root, "");

        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"), cache, null);
        runtime.attach(bridge);

        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root);
        assertEquals(1, mods.size(), "um mod sem .lua no disco deveria carregar com base remota");

        runtime.load(mods.get(0));
        runtime.triggerAll("server_started", null);

        assertEquals(List.of("vim da rede"), bridge.calls);
    }

    @Test
    void oArquivoLocalGanhaDaBaseRemota(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        publicar(cache, BASE + "main.lua", """
                local function on_started(ctx) ctx.server.broadcast("da rede") end
                return { on_started = on_started }
                """);

        Path dir = escreverManifesto(root, "");
        // Existindo no disco, e ele que vale: buscar na rede quando ha copia local faria um mod
        // instalado se comportar diferente do que esta na pasta, e depurar isso seria horrivel.
        Files.writeString(dir.resolve("main.lua"), """
                local function on_started(ctx) ctx.server.broadcast("do disco") end
                return { on_started = on_started }
                """, StandardCharsets.UTF_8);

        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"), cache, null);
        runtime.attach(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root).get(0));
        runtime.triggerAll("server_started", null);

        assertEquals(List.of("do disco"), bridge.calls);
    }

    @Test
    void semBaseRemotaOEntrypointAusenteContinuaSendoRecusado(@TempDir Path root)
            throws IOException {
        Path dir = root.resolve("remoto");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "remoto",
                  "name": "Remoto",
                  "version": "0.1.0",
                  "entrypoint": "main.lua"
                }
                """, StandardCharsets.UTF_8);

        // Sem base declarada nao ha onde procurar, e aceitar daria um mod que carrega e nao faz
        // nada -- o modo de falhar mais caro, e o mesmo que o manifesto sem entrypoint ja custou.
        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertTrue(mods.isEmpty(), "entrypoint ausente sem base remota deveria ser recusado");
    }

    @Test
    void aBaseRemotaTambemAlcancaOsModulos(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        publicar(cache, BASE + "main.lua", """
                local ajuda = mod.import("lib/ajuda.lua")
                local function on_started(ctx) ctx.server.broadcast(ajuda.frase()) end
                return { on_started = on_started }
                """);
        publicar(cache, BASE + "lib/ajuda.lua", """
                return { frase = function() return "o modulo tambem veio" end }
                """);

        escreverManifesto(root, "");

        Bridge bridge = new Bridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"), cache, null);
        runtime.attach(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root).get(0));
        runtime.triggerAll("server_started", null);

        // O mod inteiro veio da rede: script principal e biblioteca. Se so um dos dois soubesse
        // buscar, um mod publicado teria que trazer metade dos arquivos na instalacao.
        assertEquals(List.of("o modulo tambem veio"), bridge.calls);
    }

    @Test
    void semCacheRemotoLigadoOModNaoCarregaEmSilencio(@TempDir Path root) throws Exception {
        Path cache = root.resolve("cache");
        publicar(cache, BASE + "main.lua", "return {}\n");
        escreverManifesto(root, "");

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(new Bridge());
        // Sem cache ligado no runtime nao ha de onde ler, e isso precisa falhar alto: um mod que
        // carregasse vazio pareceria instalado e nao faria nada.
        var mod = new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root).get(0);
        assertThrows(IOException.class, () -> runtime.load(mod));
    }
}

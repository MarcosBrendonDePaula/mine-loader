package dev.lualoader.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quem precisa ter o mod instalado, e por quê.
 *
 * <p>A pergunta nasce de quem entra num servidor. O Lua roda só no servidor e a tela vai como
 * dados, então um mod de comando, evento ou tela funciona para quem entrou sem ter baixado nada.
 * Um bloco declarado, não: ele precisa estar registrado dos dois lados, ou a sincronização de
 * registro do jogo recusa a conexão — com uma mensagem que não se parece nem um pouco com a causa.
 */
class ModSideTest {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("test");

    private static ModManifest load(Path root, String id, String json) throws IOException {
        Path directory = root.resolve(id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("mod.json"), json);

        List<ModLoader.LoadedMod> found = new ModLoader(LOGGER).discover(root);
        return found.isEmpty() ? null : found.get(0).manifest();
    }

    @Test
    void umModSemConteudoNaoExigeNadaDeQuemEntra(@TempDir Path root) throws IOException {
        ModManifest manifest = load(root, "so_script", """
                {
                  "schema": 1,
                  "id": "so_script",
                  "name": "So Script",
                  "version": "1.0.0"
                }
                """);

        assertEquals("server", manifest.effectiveSide());
        assertFalse(manifest.requiresClient(),
                "comando, evento e tela atravessam a rede como dados");
    }

    @Test
    void umModComBlocoExigeInstalacaoDosDoisLados(@TempDir Path root) throws IOException {
        ModManifest manifest = load(root, "com_bloco", """
                {
                  "schema": 1,
                  "id": "com_bloco",
                  "name": "Com Bloco",
                  "version": "1.0.0",
                  "blocks": [{"id": "pedra", "name": "Pedra"}]
                }
                """);

        assertEquals("both", manifest.effectiveSide());
        assertTrue(manifest.requiresClient(), "bloco declarado precisa existir nos dois registros");
    }

    @Test
    void umModComItemTambemExige(@TempDir Path root) throws IOException {
        ModManifest manifest = load(root, "com_item", """
                {
                  "schema": 1,
                  "id": "com_item",
                  "name": "Com Item",
                  "version": "1.0.0",
                  "items": [{"id": "gema", "name": "Gema"}]
                }
                """);

        assertTrue(manifest.requiresClient());
    }

    @Test
    void oManifestoPodeDizerExplicitamente(@TempDir Path root) throws IOException {
        // Declarar "both" sem conteudo e legitimo: o mod pode saber de um motivo que o manifesto
        // ainda nao expressa. O contrario -- dizer "server" tendo conteudo -- e que nao vale.
        ModManifest manifest = load(root, "declarado", """
                {
                  "schema": 1,
                  "id": "declarado",
                  "name": "Declarado",
                  "version": "1.0.0",
                  "side": "both"
                }
                """);

        assertEquals("both", manifest.effectiveSide());
    }

    @Test
    void dizerServidorTendoConteudoERecusado(@TempDir Path root) throws IOException {
        // Seria uma promessa que quebra so na hora de alguem entrar, e o erro do jogo nao aponta
        // para o manifesto. Recusar na carga poe a mensagem perto da causa.
        ModManifest manifest = load(root, "mentiroso", """
                {
                  "schema": 1,
                  "id": "mentiroso",
                  "name": "Mentiroso",
                  "version": "1.0.0",
                  "side": "server",
                  "blocks": [{"id": "pedra", "name": "Pedra"}]
                }
                """);

        assertEquals(null, manifest, "o mod deveria ter sido recusado na carga");
    }

    @Test
    void clienteNaoEUmLadoValido(@TempDir Path root) throws IOException {
        // Nenhum script roda no cliente, entao o valor nao teria efeito. Aceitar e ignorar seria a
        // mesma armadilha dos campos declarados e nunca lidos.
        ModManifest manifest = load(root, "so_cliente", """
                {
                  "schema": 1,
                  "id": "so_cliente",
                  "name": "So Cliente",
                  "version": "1.0.0",
                  "side": "client"
                }
                """);

        assertEquals(null, manifest, "'client' deveria ser recusado enquanto nao significar nada");
    }

    @Test
    void osExemplosDoRepositorioSeClassificamSozinhos() throws IOException {
        // Nenhum exemplo declara side, e todos precisam cair no lado certo pela deducao -- e o que
        // garante que o campo novo nao obrigou ninguem a editar manifesto.
        List<ModLoader.LoadedMod> mods = new ModLoader(LOGGER).discover(Path.of("..", "examples"));
        assertFalse(mods.isEmpty(), "os exemplos precisam carregar");

        for (ModLoader.LoadedMod mod : mods) {
            ModManifest manifest = mod.manifest();
            boolean hasContent = (manifest.blocks != null && !manifest.blocks.isEmpty())
                    || (manifest.items != null && !manifest.items.isEmpty());

            assertEquals(hasContent, manifest.requiresClient(),
                    manifest.id + " foi classificado no lado errado");
        }
    }
}

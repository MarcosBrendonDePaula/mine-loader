package dev.lualoader.manifest;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O catálogo: tudo que existe na pasta de mods, inclusive o que a carga descarta.
 *
 * <p>{@code discover} devolve só o que vai rodar, e isso é certo para carregar e errado para uma
 * lista. Uma tela que mostrasse só o que carregou nunca deixaria alguém reativar o que desativou,
 * nem descobrir por que aquele mod sumiu — ele simplesmente não estaria lá, como se nunca tivesse
 * sido copiado.
 */
class ModCatalogTest {

    private ModLoader loader() {
        return new ModLoader(LoggerFactory.getLogger("test"));
    }

    private void writeMod(Path root, String id, boolean enabled) throws IOException {
        Path dir = root.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "%s",
                  "name": "Mod %s",
                  "version": "1.2.3",
                  "entrypoint": "main.lua",
                  "enabled": %s
                }
                """.formatted(id, id, enabled), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);
    }

    @Test
    void oCatalogoEnxergaOQueACargaDescarta(@TempDir Path root) throws IOException {
        writeMod(root, "ligado", true);
        writeMod(root, "desligado", false);

        // discover pula o desativado, e e o comportamento certo dele.
        assertEquals(1, loader().discover(root).size());

        // O catalogo ve os dois, e diz em que pe cada um esta.
        List<ModLoader.CatalogEntry> catalog = loader().catalog(root);
        assertEquals(2, catalog.size());

        var desligado = catalog.stream()
                .filter(entry -> entry.id().equals("desligado")).findFirst().orElseThrow();
        assertEquals(ModLoader.State.DISABLED, desligado.state());
        assertNotNull(desligado.manifest(), "um mod desligado ainda tem manifesto legivel");
        assertEquals("Mod desligado", desligado.manifest().name);
    }

    @Test
    void umModQuebradoApareceComOMotivo(@TempDir Path root) throws IOException {
        Path dir = root.resolve("torto");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), "isto nao e json", StandardCharsets.UTF_8);

        var entry = loader().catalog(root).get(0);

        // Aparece na lista com o nome da pasta: e o unico jeito de quem esta olhando descobrir que
        // aquele mod existe e esta quebrado, em vez de concluir que nunca foi copiado.
        assertEquals("torto", entry.id());
        assertEquals(ModLoader.State.BROKEN, entry.state());
        assertNull(entry.manifest());
        assertNotNull(entry.reason(), "a recusa precisa dizer por que");
    }

    @Test
    void pastaSemManifestoNaoEntra(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("uma_pasta_qualquer"));

        // Sem mod.json nao e um mod: pode ser cache, backup ou lixo que alguem deixou ali.
        assertTrue(loader().catalog(root).isEmpty());
    }

    // ------------------------------------------------------------------ ligar e desligar

    @Test
    void desligarFazACargaPularOMod(@TempDir Path root) throws IOException {
        writeMod(root, "alvo", true);
        assertEquals(1, loader().discover(root).size());

        assertTrue(loader().setEnabled(root.resolve("alvo"), false));

        // O efeito e o que importa, e nao o texto do arquivo: a proxima carga precisa pular.
        assertTrue(loader().discover(root).isEmpty());
        assertEquals(ModLoader.State.DISABLED, loader().catalog(root).get(0).state());
    }

    @Test
    void ligarDeVolta(@TempDir Path root) throws IOException {
        writeMod(root, "alvo", false);
        assertTrue(loader().discover(root).isEmpty());

        assertTrue(loader().setEnabled(root.resolve("alvo"), true));
        assertEquals(1, loader().discover(root).size());
    }

    @Test
    void oRestoDoManifestoSobrevive(@TempDir Path root) throws IOException {
        Path dir = root.resolve("rico");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "rico",
                  "name": "Mod Rico",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "world.read"],
                  "items": [ { "id": "rubi", "name": "Rubi", "rarity": "rare" } ]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);

        loader().setEnabled(dir, false);
        loader().setEnabled(dir, true);

        // Duas escritas seguidas, e nada se perde: a arvore inteira e reescrita, e nao uma linha
        // costurada a mao. Uma substituicao por texto quebraria num manifesto que nao traz
        // "enabled", ou que o traz dentro de outro objeto.
        ModManifest manifest = loader().discover(root).get(0).manifest();
        assertEquals("Mod Rico", manifest.name);
        assertEquals(List.of("chat.send", "world.read"), manifest.permissions);
        assertEquals(1, manifest.items.size());
        assertEquals("rubi", manifest.items.get(0).id);
        assertEquals("rare", manifest.items.get(0).rarity);
    }

    @Test
    void oImportSobreviveAoLigaDesliga(@TempDir Path root) throws IOException {
        Path dir = root.resolve("dividido");
        Files.createDirectories(dir.resolve("parts"));
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "dividido",
                  "name": "Dividido",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "items": { "$import": "parts/items.json" }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("parts/items.json"), """
                [ { "id": "rubi", "name": "Rubi" } ]
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);

        loader().setEnabled(dir, false);

        // O arquivo e lido cru, antes de qualquer resolucao, entao o $import continua sendo um
        // import -- e nao vira o conteudo inlinado, que congelaria a divisao em arquivos.
        String raw = Files.readString(dir.resolve("mod.json"), StandardCharsets.UTF_8);
        assertTrue(raw.contains("$import"), "o import deveria ter sobrevivido: " + raw);

        loader().setEnabled(dir, true);
        assertEquals(1, loader().discover(root).get(0).manifest().items.size());
    }

    @Test
    void desligarOQueNaoExisteNaoQuebra(@TempDir Path root) throws IOException {
        assertFalse(loader().setEnabled(root.resolve("fantasma"), false));
    }
}

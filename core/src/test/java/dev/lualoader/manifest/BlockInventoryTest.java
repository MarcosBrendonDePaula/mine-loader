package dev.lualoader.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O inventário declarado de um bloco, verificado sem abrir o jogo.
 *
 * <p>É o que separa um bloco decorativo de uma máquina, e um erro na declaração precisa virar
 * mensagem para quem escreveu o mod — não um baú que abre com o número errado de fileiras e ninguém
 * entende por quê.
 */
class BlockInventoryTest {

    private Path writeMod(Path root, String manifest) throws IOException {
        Path dir = root.resolve("inventory_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
        return dir;
    }

    private List<ModLoader.LoadedMod> discover(Path root) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root);
    }

    /**
     * Guarda o que foi registrado como erro.
     *
     * <p>Um manifesto invalido nao derruba a carga -- e logado e o mod fica de fora, para um mod
     * quebrado nao levar os outros junto. Entao a mensagem <em>e</em> o produto da validacao, e e
     * o que estes testes precisam ler.
     */
    private static final class CapturingLogger extends LegacyAbstractLogger {
        final List<String> errors = new ArrayList<>();

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String message,
                                                   Object[] arguments, Throwable throwable) {
            if (level != Level.ERROR) return;

            // Substituicao literal, e nao por expressao regular: o texto de um erro carrega
            // caminho do Windows e cifrao, que uma regex leria como escape e comeria.
            StringBuilder rendered = new StringBuilder(message);
            for (Object argument : arguments == null ? new Object[0] : arguments) {
                int slot = rendered.indexOf("{}");
                if (slot < 0) break;
                rendered.replace(slot, slot + 2, String.valueOf(argument));
            }
            errors.add(rendered.toString());
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isWarnEnabled() {
            return false;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }
    }

    /** Carrega esperando recusa, e devolve o que foi dito sobre ela. */
    private List<String> discoverExpectingRefusal(Path root) throws IOException {
        CapturingLogger logger = new CapturingLogger();
        List<ModLoader.LoadedMod> mods = new ModLoader(logger).discover(root);

        assertTrue(mods.isEmpty(), "o mod invalido nao deveria ter sido carregado");
        assertFalse(logger.errors.isEmpty(), "a recusa deveria ter sido registrada");
        return logger.errors;
    }

    private static String manifestWith(String inventoryJson) {
        return """
                {
                  "schema": 1,
                  "id": "inventory_mod",
                  "name": "Inventory Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [
                    {
                      "id": "crate",
                      "name": "Crate"%s
                    }
                  ]
                }
                """.formatted(inventoryJson.isEmpty() ? "" : ",\n      " + inventoryJson);
    }

    @Test
    void blocoSemInventarioNaoDeclaraNada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith(""));

        List<ModLoader.LoadedMod> mods = discover(dir);
        assertEquals(1, mods.size());

        ModManifest.BlockDefinition block = mods.get(0).manifest().blocks.get(0);
        // Nulo, e nao um inventario de tamanho zero: a diferenca entre "nao guarda itens" e
        // "guarda itens, mas nenhum" decide se o bloco ganha uma entidade e uma janela.
        assertNull(block.inventory, "bloco sem declaracao nao deveria ter inventario");
    }

    @Test
    void camposDoInventarioSaoLidos(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                "inventory": {
                        "size": 27,
                        "title": "Cofre",
                        "open_on_use": false,
                        "allow_insert": true,
                        "allow_extract": false,
                        "drop_on_break": false
                      }"""));

        ModManifest.InventoryDefinition inventory =
                discover(dir).get(0).manifest().blocks.get(0).inventory;
        assertNotNull(inventory);

        assertEquals(27, inventory.size);
        assertEquals("Cofre", inventory.title);
        assertFalse(inventory.openOnUse);
        assertTrue(inventory.allowInsert);
        // O par que justifica as permissoes existirem: aceita deposito, recusa retirada. E um
        // cofre, e nao ha como dizer isso sem separar os dois lados.
        assertFalse(inventory.allowExtract);
        assertFalse(inventory.dropOnBreak);
    }

    @Test
    void inventarioSemCamposUsaOsPadroes(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("\"inventory\": {}"));

        ModManifest.InventoryDefinition inventory =
                discover(dir).get(0).manifest().blocks.get(0).inventory;
        assertNotNull(inventory);

        // Um bau grande, aberto no clique, aceitando automacao dos dois lados: o comportamento que
        // alguem espera ao escrever "inventory": {} e nada mais.
        assertEquals(27, inventory.size);
        assertTrue(inventory.openOnUse);
        assertTrue(inventory.allowInsert);
        assertTrue(inventory.allowExtract);
        assertTrue(inventory.dropOnBreak);
    }

    @Test
    void tamanhoForaDaFaixaERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("\"inventory\": {\"size\": 90}"));

        // Noventa slots nao cabem na janela do jogo: seriam slots que ninguem alcanca.
        List<String> errors = discoverExpectingRefusal(dir);
        assertTrue(errors.get(0).contains("inventory.size"),
                "a mensagem deveria nomear o campo: " + errors.get(0));
    }

    @Test
    void tamanhoZeroERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("\"inventory\": {\"size\": 0}"));

        discoverExpectingRefusal(dir);
    }

    @Test
    void tamanhoQueNaoFechaFileiraERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("\"inventory\": {\"size\": 10}"));

        // Dez slots dariam uma fileira e um slot solto. A janela desenha fileiras de nove, e o
        // resto ficaria fora dela -- um item guardado ali sumiria de vista.
        List<String> errors = discoverExpectingRefusal(dir);
        assertTrue(errors.get(0).contains("multiplo de 9"),
                "a mensagem deveria explicar a fileira: " + errors.get(0));
    }
}

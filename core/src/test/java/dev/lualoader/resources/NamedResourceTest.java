package dev.lualoader.resources;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
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
 * Recursos nomeados e as referências a eles.
 *
 * <p>Antes cada recurso era declarado onde era usado, e dez blocos com a mesma textura repetiam a
 * declaração dez vezes. O que estes testes guardam não é só o reuso: é que uma referência quebrada
 * falhe na carga, com o nome do recurso na mensagem. Descoberta em jogo, ela aparece como um cubo
 * roxo, que não diz nome nenhum.
 */
class NamedResourceTest {

    private Path writeMod(Path root, String body) throws IOException {
        Path dir = root.resolve("res_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "res_mod",
                  "name": "Resource Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  %s
                }
                """.formatted(body), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
        return dir;
    }

    private List<ModLoader.LoadedMod> discover(Path root) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root);
    }

    /** Guarda o que foi registrado como erro; um manifesto invalido e logado, e nao lancado. */
    private static final class CapturingLogger extends LegacyAbstractLogger {
        final List<String> errors = new ArrayList<>();

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String message,
                                                   Object[] arguments, Throwable throwable) {
            if (level != Level.ERROR) return;
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

    private List<String> discoverExpectingRefusal(Path root) throws IOException {
        CapturingLogger logger = new CapturingLogger();
        List<ModLoader.LoadedMod> mods = new ModLoader(logger).discover(root);

        assertTrue(mods.isEmpty(), "o mod invalido nao deveria ter sido carregado");
        assertFalse(logger.errors.isEmpty(), "a recusa deveria ter sido registrada");
        return logger.errors;
    }

    // ------------------------------------------------------------------ leitura

    @Test
    void recursoDeclaradoEhLido(@TempDir Path dir) throws IOException {
        writeMod(dir, """
                "resources": {
                    "cristal": {"type": "image", "from": "assets/cristal.png", "sha256": "abc"}
                  }""");

        ModManifest manifest = discover(dir).get(0).manifest();
        ModManifest.ResourceDefinition resource = manifest.resources.get("cristal");

        assertNotNull(resource);
        assertEquals("image", resource.type);
        assertEquals("assets/cristal.png", resource.from);
        assertEquals("abc", resource.sha256);
    }

    @Test
    void texturaPodeSerEscritaComoReferencia(@TempDir Path dir) throws IOException {
        // A forma curta e a que se quer usar dez vezes seguidas. Sem o adaptador de leitura, uma
        // string onde o campo espera objeto faria o Gson recusar o manifesto inteiro.
        writeMod(dir, """
                "resources": {"cristal": {"type": "image", "from": "assets/cristal.png"}},
                  "blocks": [{"id": "altar", "name": "Altar",
                    "render": {"texture": "@cristal"}}]""");

        ModManifest manifest = discover(dir).get(0).manifest();
        ModManifest.TextureDefinition texture = manifest.blocks.get(0).render.texture;

        assertEquals("cristal", texture.ref);
    }

    @Test
    void formaInlineContinuaValendo(@TempDir Path dir) throws IOException {
        // A convivencia nao e cortesia: e o que permite a mudanca entrar sem quebrar nenhum
        // manifesto existente.
        writeMod(dir, """
                "blocks": [{"id": "altar", "name": "Altar",
                    "render": {"texture": {"source": "local", "path": "assets/x.png"}}}]""");

        ModManifest.TextureDefinition texture =
                discover(dir).get(0).manifest().blocks.get(0).render.texture;

        assertNull(texture.ref, "sem arroba nao ha referencia");
        assertEquals("assets/x.png", texture.path);
    }

    // ------------------------------------------------------------------ resolucao

    @Test
    void referenciaViraDeclaracaoCompleta(@TempDir Path dir) throws IOException {
        writeMod(dir, """
                "resources": {
                    "cristal": {"type": "image", "from": "assets/cristal.png", "sha256": "abc"}
                  },
                  "blocks": [{"id": "altar", "name": "Altar",
                    "render": {"texture": "@cristal"}}]""");

        ModManifest manifest = discover(dir).get(0).manifest();
        ModManifest.TextureDefinition resolved = new ResourceCatalog(manifest)
                .resolveTexture(manifest.blocks.get(0).render.texture);

        assertEquals("local", resolved.source);
        assertEquals("assets/cristal.png", resolved.path);
        assertEquals("abc", resolved.sha256);
    }

    @Test
    void origemHttpViraRemota(@TempDir Path dir) throws IOException {
        // Um campo so para as duas origens: o prefixo ja diz qual e, e dois campos permitiriam
        // declarar ambos e deixar a duvida sobre qual vale.
        writeMod(dir, """
                "resources": {
                    "cristal": {"type": "image", "from": "https://exemplo.test/c.png"}
                  },
                  "blocks": [{"id": "altar", "name": "Altar",
                    "render": {"texture": "@cristal"}}]""");

        ModManifest manifest = discover(dir).get(0).manifest();
        ModManifest.TextureDefinition resolved = new ResourceCatalog(manifest)
                .resolveTexture(manifest.blocks.get(0).render.texture);

        assertEquals("remote", resolved.source);
        assertEquals("https://exemplo.test/c.png", resolved.url);
        assertNull(resolved.path);
    }

    @Test
    void doisBlocosCompartilhamUmRecurso(@TempDir Path dir) throws IOException {
        // O ponto todo do recurso nomeado: declarar uma vez e usar em quantos lugares for preciso.
        writeMod(dir, """
                "resources": {"cristal": {"type": "image", "from": "assets/c.png"}},
                  "blocks": [
                    {"id": "altar", "name": "Altar", "render": {"texture": "@cristal"}},
                    {"id": "pilar", "name": "Pilar", "render": {"texture": "@cristal"}}]""");

        ModManifest manifest = discover(dir).get(0).manifest();
        ResourceCatalog catalog = new ResourceCatalog(manifest);

        assertEquals("assets/c.png",
                catalog.resolveTexture(manifest.blocks.get(0).render.texture).path);
        assertEquals("assets/c.png",
                catalog.resolveTexture(manifest.blocks.get(1).render.texture).path);
    }

    @Test
    void aSecaoDeRecursosPodeVirDeOutroArquivo(@TempDir Path dir) throws IOException {
        // Import ja valia para blocos e estruturas, e vale aqui pelo mesmo motivo: um mod com
        // muitos recursos produz um mod.json grande demais para ler. Nao precisou de codigo novo
        // -- o resolvedor de import roda antes da leitura, entao qualquer objeto do manifesto
        // pode vir de fora.
        Path mod = writeMod(dir, """
                "resources": {"$import": "recursos.json"},
                  "blocks": [{"id": "altar", "name": "Altar",
                    "render": {"texture": "@cristal"}}]""");

        Files.writeString(mod.resolve("recursos.json"), """
                {
                  "cristal": {"type": "image", "from": "assets/cristal.png"},
                  "batida": {"type": "sound", "from": "assets/batida.ogg"}
                }
                """, StandardCharsets.UTF_8);

        ModManifest manifest = discover(dir).get(0).manifest();
        assertEquals(2, manifest.resources.size());
        assertEquals("assets/cristal.png", manifest.resources.get("cristal").from);

        // E a referencia continua sendo conferida, mesmo vindo de outro arquivo.
        assertEquals("assets/cristal.png", new ResourceCatalog(manifest)
                .resolveTexture(manifest.blocks.get(0).render.texture).path);
    }

    @Test
    void itemComTexturaPorReferenciaChegaAoPack(@TempDir Path dir, @TempDir Path out)
            throws IOException {
        // Resolver certo nao basta: o montador tambem precisa perguntar as coisas na ordem certa.
        // A condicao que decidia copiar a textura olhava para o caminho, que numa referencia so
        // existe depois de resolver -- entao o item passava direto e ficava sem textura nenhuma,
        // sem erro no log. Os testes de resolucao passavam; o pack e que saia errado.
        Path mod = writeMod(dir, """
                "resources": {
                    "rubi": {"type": "image", "from": "assets/rubi.png",
                             "fallback": "minecraft:item/redstone"}
                  },
                  "items": [{"id": "rubi", "name": "Rubi", "texture": "@rubi"}]""");

        Files.createDirectories(mod.resolve("assets"));
        Files.write(mod.resolve("assets/rubi.png"), PIXEL_PNG);

        List<ModLoader.LoadedMod> mods = discover(dir);
        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));

        Path texture = out.resolve("pack/assets/res_mod/textures/item/rubi.png");
        assertTrue(Files.isRegularFile(texture),
                "a textura do item deveria ter sido copiada para " + texture);

        // E o modelo aponta para ela, e nao para o fallback.
        String model = Files.readString(
                out.resolve("pack/assets/res_mod/models/item/rubi.json"), StandardCharsets.UTF_8);
        assertTrue(model.contains("res_mod:item/rubi"),
                "o modelo deveria apontar para a textura copiada: " + model);
    }

    @Test
    void itemUsaOFallbackDoRecursoQuandoATexturaFalta(@TempDir Path dir, @TempDir Path out)
            throws IOException {
        // O fallback tambem vinha do lugar errado: numa referencia ele mora no recurso, e a leitura
        // direta do campo caia no padrao generico em vez do declarado.
        writeMod(dir, """
                "resources": {
                    "rubi": {"type": "image", "from": "assets/nao_existe.png",
                             "fallback": "minecraft:item/redstone"}
                  },
                  "items": [{"id": "rubi", "name": "Rubi", "texture": "@rubi"}]""");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(discover(dir), out.resolve("pack"));

        String model = Files.readString(
                out.resolve("pack/assets/res_mod/models/item/rubi.json"), StandardCharsets.UTF_8);
        assertTrue(model.contains("minecraft:item/redstone"),
                "deveria usar o fallback declarado no recurso: " + model);
    }

    /** Um PNG de um pixel, para os testes que precisam de uma imagem valida no disco. */
    private static final byte[] PIXEL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    // ------------------------------------------------------------------ recusas

    @Test
    void referenciaInexistenteFalhaNaCarga(@TempDir Path dir) throws IOException {
        writeMod(dir, """
                "resources": {"cristal": {"type": "image", "from": "assets/c.png"}},
                  "blocks": [{"id": "altar", "name": "Altar",
                    "render": {"texture": "@nao_existe"}}]""");

        List<String> errors = discoverExpectingRefusal(dir);
        assertTrue(errors.get(0).contains("nao_existe"),
                "a mensagem deveria nomear a referencia quebrada: " + errors.get(0));
        // E dizer o que existe, senao quem le fica adivinhando o nome certo.
        assertTrue(errors.get(0).contains("cristal"),
                "a mensagem deveria listar os recursos declarados: " + errors.get(0));
    }

    @Test
    void referenciaDeTipoErradoFalhaNaCarga(@TempDir Path dir) throws IOException {
        // Uma textura apontando para um som falharia bem mais adiante, com uma mensagem sobre
        // bytes invalidos que nao diz o que fazer.
        writeMod(dir, """
                "resources": {"batida": {"type": "sound", "from": "assets/b.ogg"}},
                  "blocks": [{"id": "altar", "name": "Altar",
                    "render": {"texture": "@batida"}}]""");

        List<String> errors = discoverExpectingRefusal(dir);
        assertTrue(errors.get(0).contains("sound") && errors.get(0).contains("image"),
                "a mensagem deveria dizer os dois tipos: " + errors.get(0));
    }

    @Test
    void tipoDesconhecidoEhRecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, """
                "resources": {"x": {"type": "video", "from": "assets/x.mp4"}}""");

        List<String> errors = discoverExpectingRefusal(dir);
        assertTrue(errors.get(0).contains("video"), errors.get(0));
    }

    @Test
    void recursoSemOrigemEhRecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, """
                "resources": {"x": {"type": "image"}}""");

        List<String> errors = discoverExpectingRefusal(dir);
        assertTrue(errors.get(0).contains("from"), errors.get(0));
    }

    @Test
    void caminhoQueSaiDaPastaEhRecusado(@TempDir Path dir) throws IOException {
        // A mesma regra que ja vale para entrypoint e textura inline: um recurso nao alcanca o
        // disco fora do mod.
        writeMod(dir, """
                "resources": {"x": {"type": "image", "from": "../../etc/senha"}}""");

        List<String> errors = discoverExpectingRefusal(dir);
        assertTrue(errors.get(0).contains("sai da pasta"), errors.get(0));
    }
}

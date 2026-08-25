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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pastas de mod fora da pasta do jogo, declaradas no ambiente.
 *
 * <p>Existe para desenvolver um mod que mora em outro repositório sem copiar nada para dentro de
 * {@code mods-lua}. Copiar era o que se fazia, e custou caro: a cópia envelhecia e o servidor rodava
 * contra um script velho <b>dizendo que passou</b> — o pior resultado possível, porque parece
 * verificação.
 */
class ExtraModDirectoryTest {

    /** Escreve um mod mínimo, e devolve a pasta dele. */
    private Path escreverMod(Path onde, String id, String frase) throws IOException {
        Path dir = onde.resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send"]
                }
                """.formatted(id, id), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"),
                "return { frase = function() return \"" + frase + "\" end }\n",
                StandardCharsets.UTF_8);
        return dir;
    }

    private List<ModLoader.LoadedMod> descobrir(Path jogo, Path... extras) throws IOException {
        String valor = String.join(java.io.File.pathSeparator,
                java.util.Arrays.stream(extras).map(Path::toString).toList());
        String anterior = System.getProperty(ModLoader.EXTRA_DIRS_PROPERTY);
        System.setProperty(ModLoader.EXTRA_DIRS_PROPERTY, valor);
        try {
            return new ModLoader(LoggerFactory.getLogger("test")).discover(jogo);
        } finally {
            if (anterior == null) System.clearProperty(ModLoader.EXTRA_DIRS_PROPERTY);
            else System.setProperty(ModLoader.EXTRA_DIRS_PROPERTY, anterior);
        }
    }

    @Test
    void umModDeOutraPastaCarregaJuntoComOsDoJogo(@TempDir Path root) throws IOException {
        Path jogo = root.resolve("mods-lua");
        Files.createDirectories(jogo);
        escreverMod(jogo, "do_jogo", "a");

        Path fora = root.resolve("outro_repositorio");
        Files.createDirectories(fora);
        escreverMod(fora, "de_fora", "b");

        List<String> ids = descobrir(jogo, fora).stream().map(m -> m.manifest().id).sorted().toList();
        assertEquals(List.of("de_fora", "do_jogo"), ids);
    }

    @Test
    void aPastaExtraPodeApontarDiretoParaOMod(@TempDir Path root) throws IOException {
        Path jogo = root.resolve("mods-lua");
        Files.createDirectories(jogo);

        Path fora = root.resolve("repo");
        Files.createDirectories(fora);
        Path mod = escreverMod(fora, "direto", "b");

        // Apontar direto para a pasta do mod é o gesto natural de quem tem o mod num repositório
        // próprio: lá ele é a raiz, e não um item de uma lista. Exigir a pasta-mãe faria o caminho
        // óbvio falhar em silêncio, achando que a pasta não tem mod nenhum.
        List<ModLoader.LoadedMod> mods = descobrir(jogo, mod);
        assertEquals(1, mods.size(), "deveria ter achado o mod apontado direto");
        assertEquals("direto", mods.get(0).manifest().id);
    }

    @Test
    void aPastaExtraGanhaDaCopiaAntigaNaPastaDoJogo(@TempDir Path root) throws IOException {
        Path jogo = root.resolve("mods-lua");
        Files.createDirectories(jogo);
        Path copia = escreverMod(jogo, "logistica", "copia velha");

        Path fora = root.resolve("logistica-lua");
        Files.createDirectories(fora);
        Path viva = escreverMod(fora, "logistica", "versao viva");

        // Quem aponta uma pasta extra está trabalhando naquele mod. Se a cópia da pasta do jogo
        // ganhasse, o desenvolvimento não teria efeito nenhum e nada diria por quê -- que é
        // exatamente o defeito que esta funcionalidade existe para não deixar acontecer.
        List<ModLoader.LoadedMod> mods = descobrir(jogo, fora);
        assertEquals(1, mods.size(), "o id repetido nao deveria virar dois mods");
        assertEquals(viva, mods.get(0).directory(),
                "a versao da pasta extra deveria ter ganhado de " + copia);
    }

    @Test
    void semNadaDeclaradoOComportamentoNaoMuda(@TempDir Path root) throws IOException {
        Path jogo = root.resolve("mods-lua");
        Files.createDirectories(jogo);
        escreverMod(jogo, "sozinho", "a");

        String anterior = System.getProperty(ModLoader.EXTRA_DIRS_PROPERTY);
        System.clearProperty(ModLoader.EXTRA_DIRS_PROPERTY);
        try {
            List<ModLoader.LoadedMod> mods =
                    new ModLoader(LoggerFactory.getLogger("test")).discover(jogo);
            assertEquals(1, mods.size());
            assertEquals("sozinho", mods.get(0).manifest().id);
        } finally {
            if (anterior != null) System.setProperty(ModLoader.EXTRA_DIRS_PROPERTY, anterior);
        }
    }

    @Test
    void umaPastaQueNaoExisteNaoDerrubaOResto(@TempDir Path root) throws IOException {
        Path jogo = root.resolve("mods-lua");
        Files.createDirectories(jogo);
        escreverMod(jogo, "do_jogo", "a");

        // Um caminho errado no ambiente é comum -- outra máquina, outro clone. Ele merece um aviso
        // no log, e não derrubar os mods que estão certos.
        List<ModLoader.LoadedMod> mods = descobrir(jogo, root.resolve("nao_existe"));
        assertEquals(1, mods.size());
        assertEquals("do_jogo", mods.get(0).manifest().id);
    }

    @Test
    void variasPastasValemNaOrdemEscrita(@TempDir Path root) throws IOException {
        Path jogo = root.resolve("mods-lua");
        Files.createDirectories(jogo);

        Path primeira = root.resolve("um");
        Path segunda = root.resolve("dois");
        Files.createDirectories(primeira);
        Files.createDirectories(segunda);
        Path vence = escreverMod(primeira, "repetido", "primeira");
        escreverMod(segunda, "repetido", "segunda");
        escreverMod(segunda, "so_na_segunda", "x");

        List<ModLoader.LoadedMod> mods = descobrir(jogo, primeira, segunda);
        assertEquals(2, mods.size());

        ModLoader.LoadedMod repetido = mods.stream()
                .filter(m -> m.manifest().id.equals("repetido")).findFirst().orElseThrow();
        assertEquals(vence, repetido.directory(), "a primeira pasta declarada deveria vencer");

        assertTrue(mods.stream().anyMatch(m -> m.manifest().id.equals("so_na_segunda")),
                "a segunda pasta ainda contribui com o que so existe nela");
    }
}

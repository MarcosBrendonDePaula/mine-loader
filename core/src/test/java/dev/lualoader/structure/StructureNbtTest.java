package dev.lualoader.structure;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leitura de arquivos de estrutura do jogo.
 *
 * <p>Os arquivos aqui são escritos byte a byte, no mesmo formato que o bloco de estrutura grava.
 * Um {@code .nbt} pronto seria mais realista, mas um binário opaco no repositório não diz o que
 * está sendo testado — e quando falhasse, não haveria como saber se o defeito é do leitor ou do
 * arquivo.
 */
class StructureNbtTest {

    // ------------------------------------------------------------------ escrita de NBT para teste

    private static final int TAG_END = 0;
    private static final int TAG_INT = 3;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;

    /** Escreve um arquivo de estrutura com o mesmo formato do bloco de estrutura. */
    private static byte[] structureFile(int width, int height, int depth,
                                        List<String> palette, int[][] blocks, boolean gzip)
            throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(TAG_COMPOUND);
            out.writeUTF("");

            // size: lista de tres inteiros
            out.writeByte(TAG_LIST);
            out.writeUTF("size");
            out.writeByte(TAG_INT);
            out.writeInt(3);
            out.writeInt(width);
            out.writeInt(height);
            out.writeInt(depth);

            // palette: lista de compounds com Name
            out.writeByte(TAG_LIST);
            out.writeUTF("palette");
            out.writeByte(TAG_COMPOUND);
            out.writeInt(palette.size());
            for (String name : palette) {
                out.writeByte(TAG_STRING);
                out.writeUTF("Name");
                out.writeUTF(name);
                out.writeByte(TAG_END);
            }

            // blocks: lista de compounds com pos e state
            out.writeByte(TAG_LIST);
            out.writeUTF("blocks");
            out.writeByte(TAG_COMPOUND);
            out.writeInt(blocks.length);
            for (int[] block : blocks) {
                out.writeByte(TAG_LIST);
                out.writeUTF("pos");
                out.writeByte(TAG_INT);
                out.writeInt(3);
                out.writeInt(block[0]);
                out.writeInt(block[1]);
                out.writeInt(block[2]);

                out.writeByte(TAG_INT);
                out.writeUTF("state");
                out.writeInt(block[3]);

                out.writeByte(TAG_END);
            }

            out.writeByte(TAG_END);
        }

        if (!gzip) return raw.toByteArray();

        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(compressed)) {
            zip.write(raw.toByteArray());
        }
        return compressed.toByteArray();
    }

    // ------------------------------------------------------------------ leitura

    @Test
    void leUmaEstruturaComprimida() throws IOException {
        // O bloco de estrutura grava com gzip; e o caso normal.
        byte[] file = structureFile(2, 1, 2,
                List.of("minecraft:stone", "minecraft:glass"),
                new int[][]{{0, 0, 0, 0}, {1, 0, 0, 1}, {0, 0, 1, 1}, {1, 0, 1, 0}},
                true);

        ModManifest.StructureDefinition structure = StructureNbt.read(file, "torre");

        assertEquals("torre", structure.id);
        // O bloco de estrutura grava a partir do canto minimo, e nao do centro.
        assertEquals("corner", structure.origin);
        assertEquals(1, structure.layers.size());
        assertEquals(2, structure.layers.get(0).size());
    }

    @Test
    void leUmaEstruturaSemCompressao() throws IOException {
        // Ferramentas de edicao as vezes gravam cru. Olhar os dois primeiros bytes evita obrigar
        // quem usa a saber qual dos dois tem em maos.
        byte[] file = structureFile(1, 1, 1, List.of("minecraft:stone"),
                new int[][]{{0, 0, 0, 0}}, false);

        assertEquals(1, StructureNbt.read(file, "pedra").layers.size());
    }

    @Test
    void aPaletaLigaSimboloAoBloco() throws IOException {
        byte[] file = structureFile(2, 1, 1,
                List.of("minecraft:stone", "minecraft:glass"),
                new int[][]{{0, 0, 0, 0}, {1, 0, 0, 1}},
                true);

        ModManifest.StructureDefinition structure = StructureNbt.read(file, "parede");
        String row = structure.layers.get(0).get(0);

        assertEquals(2, row.length());
        // Cada simbolo da linha precisa existir na paleta e apontar para o bloco certo.
        assertEquals("minecraft:stone", structure.palette.get(String.valueOf(row.charAt(0))));
        assertEquals("minecraft:glass", structure.palette.get(String.valueOf(row.charAt(1))));
    }

    @Test
    void arNaoSobrescreveOQueJaExiste() throws IOException {
        // O bloco de estrutura captura o ar dentro da area. Coloca-lo apagaria o que estava no
        // mundo -- uma estrutura posicionada num morro abriria um buraco em volta.
        byte[] file = structureFile(2, 1, 1,
                List.of("minecraft:stone", "minecraft:air"),
                new int[][]{{0, 0, 0, 0}, {1, 0, 0, 1}},
                true);

        ModManifest.StructureDefinition structure = StructureNbt.read(file, "meia_parede");
        String row = structure.layers.get(0).get(0);

        assertEquals(' ', row.charAt(1), "a posicao do ar deveria ficar transparente");
        assertEquals("", structure.palette.get(" "), "o espaco preserva o que ja existe");
    }

    @Test
    void posicaoNaoPreenchidaFicaTransparente() throws IOException {
        // Um arquivo pode nao listar toda posicao da caixa. O que falta e preservado, e nao
        // preenchido com o primeiro bloco da paleta.
        byte[] file = structureFile(3, 1, 1, List.of("minecraft:stone"),
                new int[][]{{1, 0, 0, 0}}, true);

        String row = StructureNbt.read(file, "pilar").layers.get(0).get(0);
        assertEquals(' ', row.charAt(0));
        assertEquals(' ', row.charAt(2));
    }

    @Test
    void tamanhoInvalidoEhRecusado() throws IOException {
        byte[] file = structureFile(0, 0, 0, List.of("minecraft:stone"), new int[][]{}, true);

        IOException error = assertThrows(IOException.class,
                () -> StructureNbt.read(file, "vazia"));
        assertTrue(error.getMessage().contains("tamanho"), error.getMessage());
    }

    @Test
    void arquivoQueNaoEhNbtEhRecusado() {
        byte[] lixo = "isto nao e um arquivo de estrutura".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> StructureNbt.read(lixo, "lixo"));
    }

    // ------------------------------------------------------------------ arquivos do jogo

    /** Le um arquivo de estrutura do proprio Minecraft, guardado nos recursos de teste. */
    private static byte[] gameStructure(String name) throws IOException {
        try (var stream = StructureNbtTest.class.getResourceAsStream("/structures/" + name)) {
            if (stream == null) throw new IOException("faltou o recurso de teste " + name);
            return stream.readAllBytes();
        }
    }

    @Test
    void leOIglooDoProprioJogo() throws IOException {
        // Os testes acima usam arquivos que este teste escreveu, e por isso so provam que o leitor
        // entende o que ele mesmo produz. Este vem do jar do Minecraft: e a diferenca entre ler o
        // formato e ler a minha ideia do formato.
        ModManifest.StructureDefinition igloo = StructureNbt.read(gameStructure("igloo_top.nbt"), "igloo");

        assertTrue(igloo.layers.size() > 1, "o igloo tem mais de uma camada");
        assertTrue(igloo.palette.size() > 2, "o igloo usa varios blocos: " + igloo.palette);

        // Neve e o material do igloo; se o leitor errasse a paleta, isto nao apareceria.
        assertTrue(igloo.palette.containsValue("minecraft:snow_block"),
                "faltou o bloco de neve na paleta: " + igloo.palette.values());
    }

    @Test
    void leOPisoDaCidadeDoFimComEstadosDeBloco() throws IOException {
        // Este arquivo traz blocos com propriedades de estado -- purpur pillar tem eixo. E o caso
        // que confirma que descartar as propriedades nao quebra a leitura da paleta.
        ModManifest.StructureDefinition floor =
                StructureNbt.read(gameStructure("end_city_base_floor.nbt"), "piso");

        assertTrue(floor.palette.containsValue("minecraft:purpur_block"),
                "faltou purpur na paleta: " + floor.palette.values());

        // Toda linha tem o mesmo comprimento, senao o posicionador desenharia torto.
        int largura = floor.layers.get(0).get(0).length();
        for (List<String> camada : floor.layers) {
            for (String linha : camada) {
                assertEquals(largura, linha.length(), "linha de comprimento diferente");
            }
        }
    }

    @Test
    void umaEstruturaDoJogoPassaPelaValidacaoDoManifesto(@TempDir Path root) throws IOException {
        // A traducao so vale se o resultado for aceito como se tivesse sido escrito a mao.
        Path dir = root.resolve("nbt_mod");
        Files.createDirectories(dir.resolve("structures"));
        Files.write(dir.resolve("structures/igloo.nbt"), gameStructure("igloo_top.nbt"));

        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "nbt_mod",
                  "name": "NBT Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "structures": [{"id": "igloo", "from": "structures/igloo.nbt"}]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);

        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size(), "uma estrutura do jogo deveria carregar sem ajuste");
    }

    // ------------------------------------------------------------------ pelo manifesto

    @Test
    void oManifestoCarregaAEstruturaDoArquivo(@TempDir Path root) throws IOException {
        Path dir = root.resolve("nbt_mod");
        Files.createDirectories(dir.resolve("structures"));

        Files.write(dir.resolve("structures/torre.nbt"), structureFile(2, 2, 1,
                List.of("minecraft:stone"),
                new int[][]{{0, 0, 0, 0}, {1, 0, 0, 0}, {0, 1, 0, 0}, {1, 1, 0, 0}},
                true));

        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "nbt_mod",
                  "name": "NBT Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "structures": [
                    {"id": "torre", "name": "Torre", "from": "structures/torre.nbt"}
                  ]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);

        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size(), "o mod deveria ter carregado");

        ModManifest.StructureDefinition structure = mods.get(0).manifest().structures.get(0);
        // Depois da traducao a estrutura e indistinguivel de uma escrita a mao, e por isso passa
        // pela mesma validacao -- simbolo fora da paleta, camada vazia, tudo.
        assertEquals("torre", structure.id);
        assertEquals("Torre", structure.name, "o nome do manifesto deveria vencer o do arquivo");
        assertEquals(2, structure.layers.size(), "duas camadas de altura");
    }

    @Test
    void arquivoDeEstruturaFaltandoRecusaOMod(@TempDir Path root) throws IOException {
        Path dir = root.resolve("nbt_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "nbt_mod",
                  "name": "NBT Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "structures": [{"id": "torre", "from": "structures/nao_existe.nbt"}]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);

        // Recusar na carga, e nao quando alguem tentar construir: la o erro apareceria longe da
        // causa, e talvez so em producao.
        assertTrue(new ModLoader(LoggerFactory.getLogger("test")).discover(root).isEmpty());
    }
}

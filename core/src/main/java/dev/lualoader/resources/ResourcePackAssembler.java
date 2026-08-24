package dev.lualoader.resources;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.content.BlockShapes;
import dev.lualoader.manifest.ModManifest;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Gera um resource pack do loader a partir das declarações de bloco. */
public final class ResourcePackAssembler {
    private static final String NEWLINE = System.lineSeparator();
    private static final String QUOTE = String.valueOf((char) 34);
    private static final String BACKSLASH = String.valueOf((char) 92);

    private final Logger logger;
    private final RemoteResourceManager remoteResources;

    public ResourcePackAssembler(Logger logger, Path cacheDirectory) throws IOException {
        this.logger = logger;
        this.remoteResources = new RemoteResourceManager(cacheDirectory);
    }

    /**
     * Versao de formato do pack, para a versao de Minecraft alvo.
     *
     * <p>Um pack sem formato declarado nao e reconhecido como pack: o jogo le os arquivos que
     * consegue e nao carrega textura nenhuma, o que aparece como o cubo roxo e preto de recurso
     * ausente.
     */
    private static final int PACK_FORMAT = 34;

    /**
     * Escreve o {@code pack.mcmeta} na raiz.
     *
     * <p>O adaptador Fabric funcionava sem o arquivo porque fornece a metadata em codigo. Um
     * adaptador que aponte para a pasta -- como o NeoForge faz -- precisa encontra-la escrita, e
     * gerar aqui serve aos dois sem cada um repetir a mesma decisao.
     */
    private void writePackMetadata(Path generatedRoot) throws IOException {
        String conteudo = "{" + NEWLINE
                + "  " + QUOTE + "pack" + QUOTE + ": {" + NEWLINE
                + "    " + QUOTE + "pack_format" + QUOTE + ": " + PACK_FORMAT + "," + NEWLINE
                + "    " + QUOTE + "description" + QUOTE + ": "
                + QUOTE + "Recursos gerados pelos mods Lua" + QUOTE + NEWLINE
                + "  }" + NEWLINE
                + "}" + NEWLINE;

        Files.writeString(generatedRoot.resolve("pack.mcmeta"), conteudo,
                java.nio.charset.StandardCharsets.UTF_8);
    }

    public void assemble(List<ModLoader.LoadedMod> mods, Path generatedRoot) throws IOException {
        deleteContents(generatedRoot);
        Files.createDirectories(generatedRoot);
        writePackMetadata(generatedRoot);

        // Tags de vários mods podem apontar para a mesma tag vanilla; são acumuladas e
        // escritas uma única vez no fim.
        Map<String, Set<String>> tagEntries = new LinkedHashMap<>();

        for (ModLoader.LoadedMod mod : mods) {
            if (mod.manifest().blocks == null) continue;
            for (ModManifest.BlockDefinition block : mod.manifest().blocks) {
                assembleBlock(mod, block, generatedRoot);
                assembleBlockLoot(mod, block, generatedRoot);
                collectBlockTags(mod, block, tagEntries);
            }
        }

        for (ModLoader.LoadedMod mod : mods) {
            if (mod.manifest().items == null) continue;
            for (ModManifest.ItemEntryDefinition item : mod.manifest().items) {
                assembleItem(mod, item, generatedRoot);
            }
        }

        for (ModLoader.LoadedMod mod : mods) {
            assembleLanguage(mod, generatedRoot);
            assembleRecipes(mod, generatedRoot);
        }

        writeTags(tagEntries, generatedRoot);
    }

    /**
     * Gera as receitas declaradas, dentro do data pack virtual.
     *
     * <p>Sem receitas, todo item novo so chega ao jogador por comando, drop de bloco ou script.
     */
    private void assembleRecipes(ModLoader.LoadedMod mod, Path generatedRoot) throws IOException {
        if (mod.manifest().recipes == null) return;
        String namespace = mod.manifest().id;

        for (ModManifest.RecipeDefinition recipe : mod.manifest().recipes) {
            if (recipe == null || recipe.id == null || recipe.result == null) continue;

            String type = recipe.type == null ? "shaped" : recipe.type.trim().toLowerCase(java.util.Locale.ROOT);
            String json = switch (type) {
                case "shapeless" -> shapelessRecipe(recipe);
                case "shaped" -> shapedRecipe(recipe);
                default -> null;
            };
            if (json == null) {
                logger.warn("Receita {} do mod {} tem tipo desconhecido: {}", recipe.id, namespace, recipe.type);
                continue;
            }

            write(generatedRoot.resolve("data").resolve(namespace).resolve("recipe")
                    .resolve(recipe.id + ".json"), json);
            logger.info("Receita {}:{} gerada ({})", namespace, recipe.id, type);
        }
    }

    private String shapedRecipe(ModManifest.RecipeDefinition recipe) {
        List<String> lines = new ArrayList<>();
        for (String line : recipe.pattern) lines.add(quote(line));

        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> entry : recipe.key.entrySet()) {
            keys.add("    " + quote(entry.getKey()) + ": {" + quote("item") + ": "
                    + quote(entry.getValue()) + "}");
        }

        return "{" + NEWLINE
                + "  " + quote("type") + ": " + quote("minecraft:crafting_shaped") + "," + NEWLINE
                + groupLine(recipe)
                + "  " + quote("pattern") + ": [" + String.join(", ", lines) + "]," + NEWLINE
                + "  " + quote("key") + ": {" + NEWLINE + String.join("," + NEWLINE, keys) + NEWLINE
                + "  }," + NEWLINE
                + resultLine(recipe) + NEWLINE
                + "}" + NEWLINE;
    }

    private String shapelessRecipe(ModManifest.RecipeDefinition recipe) {
        List<String> items = new ArrayList<>();
        for (String ingredient : recipe.ingredients) {
            items.add("{" + quote("item") + ": " + quote(ingredient) + "}");
        }

        return "{" + NEWLINE
                + "  " + quote("type") + ": " + quote("minecraft:crafting_shapeless") + "," + NEWLINE
                + groupLine(recipe)
                + "  " + quote("ingredients") + ": [" + String.join(", ", items) + "]," + NEWLINE
                + resultLine(recipe) + NEWLINE
                + "}" + NEWLINE;
    }

    private String groupLine(ModManifest.RecipeDefinition recipe) {
        if (recipe.group == null || recipe.group.isBlank()) return "";
        return "  " + quote("group") + ": " + quote(recipe.group) + "," + NEWLINE;
    }

    private String resultLine(ModManifest.RecipeDefinition recipe) {
        return "  " + quote("result") + ": {" + quote("id") + ": " + quote(recipe.result)
                + ", " + quote("count") + ": " + Math.max(1, recipe.count) + "}";
    }

    /**
     * Gera o arquivo de traducao do mod a partir dos nomes declarados no manifesto.
     *
     * <p>Sem ele o jogo exibe a chave crua, como {@code block.hello_lua.ruby_block}, em vez do
     * nome escrito pelo criador. O arquivo e gravado em {@code en_us}, que o Minecraft usa como
     * idioma de fallback para qualquer idioma selecionado.
     */
    private void assembleLanguage(ModLoader.LoadedMod mod, Path generatedRoot) throws IOException {
        String namespace = mod.manifest().id;
        Map<String, String> entries = new LinkedHashMap<>();

        if (mod.manifest().blocks != null) {
            for (ModManifest.BlockDefinition block : mod.manifest().blocks) {
                if (block == null || block.id == null || block.name == null) continue;
                entries.put("block." + namespace + "." + block.id, block.name);
                if (block.item == null || block.item.register) {
                    // O item do bloco usa a chave de bloco; nada extra a declarar.
                    entries.putIfAbsent("item." + namespace + "." + block.id, block.name);
                }
            }
        }
        if (mod.manifest().items != null) {
            for (ModManifest.ItemEntryDefinition item : mod.manifest().items) {
                if (item == null || item.id == null || item.name == null) continue;
                entries.put("item." + namespace + "." + item.id, item.name);
            }
        }
        ModManifest.CreativeTabDefinition tab = mod.manifest().creativeTab;
        if (tab != null && tab.register) {
            String tabId = tab.id == null ? "main" : tab.id;
            String title = tab.name == null || tab.name.isBlank() ? mod.manifest().name : tab.name;
            if (title != null) entries.put("itemGroup." + namespace + "." + tabId, title);
        }

        if (entries.isEmpty()) return;

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            lines.add("  " + quote(entry.getKey()) + ": " + quote(entry.getValue()));
        }
        write(generatedRoot.resolve("assets").resolve(namespace).resolve("lang/en_us.json"),
                "{" + NEWLINE + String.join("," + NEWLINE, lines) + NEWLINE + "}" + NEWLINE);
        logger.info("Traducoes geradas para {}: {} chave(s)", namespace, entries.size());
    }

    /** Escapa um texto para uso como string JSON. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(QUOTE);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 34) {
                out.append(BACKSLASH).append(QUOTE);
            } else if (character == 92) {
                out.append(BACKSLASH).append(BACKSLASH);
            } else if (character == 10) {
                out.append(BACKSLASH).append("n");
            } else if (character == 13) {
                out.append(BACKSLASH).append("r");
            } else if (character == 9) {
                out.append(BACKSLASH).append("t");
            } else if (character < 0x20) {
                out.append(BACKSLASH).append(String.format("u%04x", (int) character));
            } else {
                out.append(character);
            }
        }
        return out.append(QUOTE).toString();
    }

    /** Gera textura e modelo de um item declarado que nao pertence a um bloco. */
    private void assembleItem(ModLoader.LoadedMod mod,
                              ModManifest.ItemEntryDefinition item,
                              Path generatedRoot) throws IOException {
        String namespace = mod.manifest().id;

        // A referencia e resolvida antes de qualquer decisao. As perguntas abaixo sao sobre onde a
        // textura esta e qual e o fallback, e numa referencia as duas respostas so existem depois
        // disto -- perguntar antes fazia o item passar direto e ficar sem textura nenhuma.
        ModManifest.TextureDefinition texture =
                new ResourceCatalog(mod.manifest()).resolveTexture(item.texture);

        String textureReference = texture != null && texture.fallback != null
                && !texture.fallback.isBlank()
                ? texture.fallback
                : "minecraft:item/stick";

        boolean hasSource = texture != null
                && ((texture.path != null && !texture.path.isBlank())
                || (texture.url != null && !texture.url.isBlank()));

        if (hasSource) {
            Path target = generatedRoot.resolve("assets").resolve(namespace)
                    .resolve("textures/item").resolve(item.id + ".png");
            try {
                RemoteResourceManager.ResolvedTexture resolved =
                        remoteResources.resolveTexture(mod.directory(), texture,
                                mod.manifest().remoteBase);
                copyAsPng(resolved.path(), target);
                textureReference = namespace + ":item/" + item.id;
                logger.info("Textura {} preparada para item {}",
                        resolved.remote() ? "remota" : "local", namespace + ":" + item.id);
            } catch (IOException error) {
                logger.warn("Textura do item {} indisponivel; usando fallback {}: {}",
                        namespace + ":" + item.id, textureReference, error.getMessage());
            }
        }

        write(generatedRoot.resolve("assets").resolve(namespace)
                        .resolve("models/item").resolve(item.id + ".json"),
                """
                        {
                          "parent": "minecraft:item/generated",
                          "textures": {"layer0": "%s"}
                        }
                        """.formatted(textureReference));
    }

    /** Gera a loot table declarada em {@code loot}, dentro do data pack virtual. */
    private void assembleBlockLoot(ModLoader.LoadedMod mod,
                                   ModManifest.BlockDefinition block,
                                   Path generatedRoot) throws IOException {
        String namespace = mod.manifest().id;
        String blockId = namespace + ":" + block.id;
        ModManifest.LootDefinition loot = block.loot == null ? new ModManifest.LootDefinition() : block.loot;
        String mode = loot.mode == null ? "self" : loot.mode.trim().toLowerCase(java.util.Locale.ROOT);

        // Uma tabela externa e responsabilidade de quem a declarou; o loader nao a gera.
        if ("table".equals(mode)) {
            if (loot.table == null || loot.table.isBlank()) {
                logger.warn("Bloco {} declara loot.mode=table sem loot.table; nada sera dropado", blockId);
            }
            return;
        }

        String dropped;
        switch (mode) {
            case "none" -> dropped = null;
            case "item" -> {
                if (loot.item == null || loot.item.isBlank()) {
                    logger.warn("Bloco {} declara loot.mode=item sem loot.item; usando o proprio bloco", blockId);
                    dropped = blockId;
                } else {
                    dropped = loot.item;
                }
            }
            case "self" -> dropped = blockId;
            default -> {
                logger.warn("Bloco {} declara loot.mode desconhecido: {}; usando self", blockId, loot.mode);
                dropped = blockId;
            }
        }

        Path table = generatedRoot.resolve("data").resolve(namespace)
                .resolve("loot_table/blocks").resolve(block.id + ".json");

        if (dropped == null) {
            write(table, """
                    {
                      "type": "minecraft:block",
                      "pools": []
                    }
                    """);
            return;
        }

        int count = Math.max(1, loot.count);
        String countFunction = count > 1
                ? """
                              ,
                              "functions": [
                                {"function": "minecraft:set_count", "count": %d}
                              ]""".formatted(count)
                : "";

        write(table, """
                {
                  "type": "minecraft:block",
                  "pools": [
                    {
                      "rolls": 1,
                      "entries": [
                        {
                          "type": "minecraft:item",
                          "name": "%s"
                        }
                      ],
                      "conditions": [
                        {"condition": "minecraft:survives_explosion"}
                      ]%s
                    }
                  ]
                }
                """.formatted(dropped, countFunction));
    }

    /** Acumula as tags declaradas no bloco, validando o formato do identificador. */
    private void collectBlockTags(ModLoader.LoadedMod mod,
                                  ModManifest.BlockDefinition block,
                                  Map<String, Set<String>> tagEntries) {
        if (block.tags == null || block.tags.isEmpty()) return;
        String blockId = mod.manifest().id + ":" + block.id;

        for (String tag : block.tags) {
            if (tag == null || tag.isBlank()) continue;
            String normalized = tag.trim();
            int separator = normalized.indexOf(':');
            if (separator <= 0 || separator == normalized.length() - 1) {
                logger.warn("Tag ignorada em {}: identificador invalido {}", blockId, tag);
                continue;
            }
            tagEntries.computeIfAbsent(normalized, key -> new TreeSet<>()).add(blockId);
        }
    }

    /** Escreve cada tag de bloco acumulada como um arquivo do data pack. */
    private void writeTags(Map<String, Set<String>> tagEntries, Path generatedRoot) throws IOException {
        for (Map.Entry<String, Set<String>> entry : tagEntries.entrySet()) {
            String tag = entry.getKey();
            int separator = tag.indexOf(':');
            String namespace = tag.substring(0, separator);
            String path = tag.substring(separator + 1);

            List<String> quoted = new ArrayList<>();
            for (String value : entry.getValue()) quoted.add("\"" + value + "\"");

            // "replace": false preserva o conteúdo vanilla da tag.
            write(generatedRoot.resolve("data").resolve(namespace).resolve("tags/block")
                            .resolve(path + ".json"),
                    """
                            {
                              "replace": false,
                              "values": [
                                %s
                              ]
                            }
                            """.formatted(String.join(",\n    ", quoted)));
            logger.info("Tag {} recebeu {} bloco(s) do loader", tag, entry.getValue().size());
        }
    }

    private void assembleBlock(ModLoader.LoadedMod mod,
                               ModManifest.BlockDefinition block,
                               Path generatedRoot) throws IOException {
        String namespace = mod.manifest().id;
        String blockId = block.id;
        Map<String, ModManifest.TextureDefinition> variants = new LinkedHashMap<>();

        if (block.render != null && block.render.variantTextures != null) {
            variants.putAll(block.render.variantTextures);
        }
        if (variants.isEmpty()) {
            variants.put("0", block.render == null ? null : block.render.texture);
        }

        Map<Integer, String> models = new LinkedHashMap<>();
        for (Map.Entry<String, ModManifest.TextureDefinition> entry : variants.entrySet()) {
            int variant = parseVariant(entry.getKey());
            if (variant < 0) {
                logger.warn("Variante ignorada em {}: {}", namespace + ":" + blockId, entry.getKey());
                continue;
            }
            String suffix = "_v" + variant;
            Path blockTexture = generatedRoot.resolve("assets").resolve(namespace)
                    .resolve("textures/block").resolve(blockId + suffix + ".png");
            Files.createDirectories(blockTexture.getParent());

            String textureReference = fallbackTexture(block);
            ModManifest.TextureDefinition definition = entry.getValue();
            if (definition != null) {
                try {
                    // A referencia vira declaracao completa aqui: daqui para baixo o resolvedor
                    // nao precisa saber que recursos nomeados existem.
                    RemoteResourceManager.ResolvedTexture resolved = remoteResources.resolveTexture(
                            mod.directory(),
                            new ResourceCatalog(mod.manifest()).resolveTexture(definition),
                            mod.manifest().remoteBase);
                    copyAsPng(resolved.path(), blockTexture);
                    textureReference = namespace + ":block/" + blockId + suffix;
                    logger.info("Textura {} preparada para {} variante {}",
                            resolved.remote() ? "remota" : "local", namespace + ":" + blockId, variant);
                } catch (IOException error) {
                    logger.warn("Textura da variante {} indisponível; usando fallback {}: {}",
                            variant, definition.fallback, error.getMessage());
                }
            }

            String modelId = namespace + ":block/" + blockId + suffix;
            write(generatedRoot.resolve("assets").resolve(namespace)
                            .resolve("models/block").resolve(blockId + suffix + ".json"),
                    blockModel(block, textureReference));
            models.put(variant, modelId);
        }

        if (models.isEmpty()) {
            models.put(0, "minecraft:block/stone");
        }
        String fallbackModel = models.getOrDefault(0, models.values().iterator().next());
        StringBuilder blockstate = new StringBuilder("{\n  \"variants\": {\n");
        for (int variant = 0; variant <= 15; variant++) {
            if (variant > 0) blockstate.append(",\n");
            blockstate.append("    \"lua_variant=").append(variant).append("\": {\"model\": \"")
                    .append(models.getOrDefault(variant, fallbackModel)).append("\"}");
        }
        blockstate.append("\n  }\n}\n");
        write(generatedRoot.resolve("assets").resolve(namespace)
                        .resolve("blockstates").resolve(blockId + ".json"), blockstate.toString());

        if (block.item == null || block.item.register) {
            write(generatedRoot.resolve("assets").resolve(namespace)
                            .resolve("models/item").resolve(blockId + ".json"),
                    "{\n  \"parent\": \"" + fallbackModel + "\"\n}\n");
        }
    }

    private static int parseVariant(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && parsed <= 15 ? parsed : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    /**
     * O modelo desenhado de um bloco, conforme a forma declarada.
     *
     * <p>Antes isto era sempre {@code cube_all}, e a forma declarada só mudava a colisão. O
     * resultado era um bloco incoerente: uma laje com colisão de laje e aparência de cubo inteiro,
     * em que o jogador via um bloco cheio e atravessava a metade de cima.
     *
     * <p>A forma visual sai de {@code shape.visual} quando declarada, e do contorno quando não —
     * quem declarou só {@code outline} quis dizer qual é a silhueta do bloco, e desenhar diferente
     * disso seria a mesma incoerência de antes com outro nome.
     */
    private String blockModel(ModManifest.BlockDefinition block, String texture) {
        List<BlockShapes.Box> boxes = shapeOf(block);

        // O cubo inteiro continua usando o modelo pronto do jogo: desenha-lo por caixas daria o
        // mesmo desenho, mais caro de montar e sem o sombreamento de face que o cube_all traz.
        if (BlockShapes.isFullCube(boxes)) {
            return "{\n  \"parent\": \"minecraft:block/cube_all\",\n"
                    + "  \"textures\": {\"all\": \"" + texture + "\"}\n}\n";
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        // Sem o parent de bloco a peca perderia a iluminacao ambiente e ficaria chapada.
        json.append("  \"parent\": \"minecraft:block/block\",\n");
        json.append("  \"textures\": {\n");
        json.append("    \"all\": \"").append(texture).append("\",\n");
        // O particle diz de que textura sai a poeira ao quebrar e ao andar por cima; sem ele o jogo
        // usa a textura de bloco ausente e a poeira sai roxa.
        json.append("    \"particle\": \"").append(texture).append("\"\n");
        json.append("  },\n");
        json.append("  \"elements\": [\n");

        for (int index = 0; index < boxes.size(); index++) {
            BlockShapes.Box box = boxes.get(index);
            if (index > 0) json.append(",\n");
            json.append("    {\n");
            json.append("      \"from\": [").append(number(box.fromX())).append(", ")
                    .append(number(box.fromY())).append(", ").append(number(box.fromZ())).append("],\n");
            json.append("      \"to\": [").append(number(box.toX())).append(", ")
                    .append(number(box.toY())).append(", ").append(number(box.toZ())).append("],\n");
            json.append("      \"faces\": {\n");

            String[] faces = {"down", "up", "north", "south", "west", "east"};
            // Uma face so pode ser cortada pelo vizinho quando encosta na borda do bloco. Numa
            // caixa interna -- o pe de uma mesa, que vai de 2 a 14 -- o cullface faria a face
            // sumir assim que houvesse um bloco ao lado, e a peca ficaria oca sem motivo aparente.
            boolean[] onEdge = {
                    box.fromY() == 0, box.toY() == 16,
                    box.fromZ() == 0, box.toZ() == 16,
                    box.fromX() == 0, box.toX() == 16};

            for (int face = 0; face < faces.length; face++) {
                if (face > 0) json.append(",\n");
                // uv ausente faz o jogo derivar do tamanho da caixa, que e o que se quer: a textura
                // acompanha a peca em vez de esticar.
                json.append("        \"").append(faces[face]).append("\": {\"texture\": \"#all\"");
                if (onEdge[face]) {
                    json.append(", \"cullface\": \"").append(faces[face]).append("\"");
                }
                json.append("}");
            }
            json.append("\n      }\n    }");
        }

        json.append("\n  ]\n}\n");
        return json.toString();
    }

    /** As caixas da forma declarada, com aviso quando o nome não existe. */
    private List<BlockShapes.Box> shapeOf(ModManifest.BlockDefinition block) {
        if (block.shape == null) return BlockShapes.FULL_CUBE;

        // Caixas proprias ganham do nome: quem as escreveu foi especifico de proposito.
        List<BlockShapes.Box> declaredBoxes = BlockShapes.fromNumbers(block.shape.boxes);
        if (declaredBoxes != null) return declaredBoxes;

        // Sem visual declarado, a silhueta e a do contorno -- e nao o cubo inteiro. Um bloco que
        // declara ser uma mesa para andar em cima precisa parecer uma mesa.
        String declared = block.shape.visual != null && !block.shape.visual.isBlank()
                ? block.shape.visual
                : block.shape.outline;

        List<BlockShapes.Box> boxes = BlockShapes.byName(declared);
        if (boxes == null) {
            logger.warn("Forma visual desconhecida em {}: {}; usando cubo inteiro. Conhecidas: {}",
                    block.id, declared, BlockShapes.names());
            return BlockShapes.FULL_CUBE;
        }
        return boxes;
    }

    /** Escreve um número sem o {@code .0} que o JSON de modelo não espera. */
    private static String number(double value) {
        return value == Math.floor(value)
                ? String.valueOf((int) value)
                : String.valueOf(value);
    }

    private static String fallbackTexture(ModManifest.BlockDefinition block) {
        if (block.render != null && block.render.texture != null
                && block.render.texture.fallback != null
                && !block.render.texture.fallback.isBlank()) {
            return block.render.texture.fallback;
        }
        return "minecraft:block/stone";
    }

    private static void copyAsPng(Path input, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        BufferedImage image;
        try (var stream = Files.newInputStream(input)) {
            image = ImageIO.read(stream);
        }
        if (image == null) throw new IOException("recurso não é uma imagem");
        if (!ImageIO.write(image, "png", output.toFile())) {
            throw new IOException("não foi possível codificar PNG");
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static void deleteContents(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(root)) Files.deleteIfExists(path);
            }
        }
    }
}

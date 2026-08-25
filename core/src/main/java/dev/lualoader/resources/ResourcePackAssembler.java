package dev.lualoader.resources;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.content.BlockShapes;
import dev.lualoader.manifest.ModManifest;
import dev.lualoader.content.EntityModelSpec;
import dev.lualoader.platform.EntityDefinition;
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

    /**
     * Onde guardar o que for baixado.
     *
     * <p>Guardado alem do {@link RemoteResourceManager} porque nem todo recurso remoto passa por
     * ele: um modelo de entidade e texto, e nao imagem, e chega pelo mesmo leitor de imports que
     * resolve {@code $import} -- que precisa do caminho, nao do gerenciador.
     */
    private final Path remoteCache;

    public ResourcePackAssembler(Logger logger, Path cacheDirectory) throws IOException {
        this.logger = logger;
        this.remoteResources = new RemoteResourceManager(cacheDirectory);
        this.remoteCache = cacheDirectory;
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
        Map<String, Set<String>> itemTagEntries = new LinkedHashMap<>();

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
                collectTags(mod.manifest().id + ":" + item.id, item.tags, itemTagEntries);
            }
        }

        Map<String, Set<String>> entityTagEntries = new LinkedHashMap<>();
        for (ModLoader.LoadedMod mod : mods) {
            if (mod.manifest().entities == null) continue;
            for (EntityDefinition entity : mod.manifest().entities) {
                if (entity == null || entity.id == null) continue;
                assembleEntityLoot(mod, entity, generatedRoot);
                assembleEntityTexture(mod, entity, generatedRoot);
                assembleEntityModel(mod, entity, generatedRoot);
                assembleBiomeModifier(mod, entity, generatedRoot);
                assembleSpawnEggModel(mod, entity, generatedRoot);
                collectTags(mod.manifest().id + ":" + entity.id, entity.tags, entityTagEntries);
            }
        }

        for (ModLoader.LoadedMod mod : mods) {
            assembleLanguage(mod, generatedRoot);
            assembleRecipes(mod, generatedRoot);
        }

        writeTags(tagEntries, generatedRoot, "block");
        writeTags(itemTagEntries, generatedRoot, "item");

        // A pasta do jogo e "entity_type", e nao "entity": escrever no plural errado produz um
        // arquivo que o jogo simplesmente nao le, e a tag some sem erro nenhum.
        writeTags(entityTagEntries, generatedRoot, "entity_type");
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
                // As quatro receitas de queima sao o mesmo formato com outro tipo e outro tempo
                // padrao: separa-las em metodos daria quatro copias da mesma coisa.
                case "smelting" -> cookingRecipe(recipe, "minecraft:smelting", 200);
                case "blasting" -> cookingRecipe(recipe, "minecraft:blasting", 100);
                case "smoking" -> cookingRecipe(recipe, "minecraft:smoking", 100);
                case "campfire", "campfire_cooking" ->
                        cookingRecipe(recipe, "minecraft:campfire_cooking", 600);
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

    /**
     * Uma receita de queima: fornalha, alto-forno, defumador ou fogueira.
     *
     * <p>Ate existir, o loader lia receitas de fornalha do jogo por {@code recipes_for} e nao
     * conseguia declarar uma -- a documentacao dizia que conseguia. Um mod de minerio ficava sem o
     * passo mais obvio dele: fundir o bruto em lingote.
     *
     * <p>O tempo e a experiencia tem padrao por tipo porque sao o que quase ninguem quer declarar,
     * e errar para mais faz a fornalha parecer travada. Quem precisa, declara.
     */
    private String cookingRecipe(ModManifest.RecipeDefinition recipe, String type, int defaultTime) {
        // O insumo pode vir como ingrediente unico ou pela chave do padrao, porque as duas formas
        // ja aparecem no manifesto e obrigar uma delas seria uma regra a mais para decorar.
        String ingredient = null;
        if (recipe.ingredients != null && !recipe.ingredients.isEmpty()) {
            ingredient = recipe.ingredients.get(0);
        } else if (recipe.key != null && !recipe.key.isEmpty()) {
            ingredient = recipe.key.values().iterator().next();
        }
        if (ingredient == null) return null;

        int time = recipe.cookingTime > 0 ? recipe.cookingTime : defaultTime;

        return "{" + NEWLINE
                + "  " + quote("type") + ": " + quote(type) + "," + NEWLINE
                + groupLine(recipe)
                + "  " + quote("ingredient") + ": {" + quote("item") + ": " + quote(ingredient)
                + "}," + NEWLINE
                + "  " + quote("experience") + ": " + recipe.experience + "," + NEWLINE
                + "  " + quote("cookingtime") + ": " + time + "," + NEWLINE
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
    /**
     * Gera a tabela de saque de uma especie declarada.
     *
     * <p><b>Sem este arquivo a especie cai sem nada.</b> Um tipo de entidade procura a tabela com o
     * proprio id, e nao a da base: um zumbi declarado por um mod nasceria com o modelo, a IA e a
     * vida do zumbi, e nao deixaria a carne podre que qualquer jogador espera. O silencio ali e
     * pior que um erro, porque parece decisao de quem fez o mod.
     *
     * <p>A tabela da base entra como uma entrada do tipo {@code loot_table}, e nao copiada: copiar
     * congelaria os drops na versao do jogo em que o mod foi escrito, e uma mudanca no zumbi
     * deixaria de valer para tudo que descende dele.
     */
    private void assembleEntityLoot(ModLoader.LoadedMod mod, EntityDefinition entity,
                                    Path generatedRoot) throws IOException {
        if (entity == null || entity.id == null) return;
        String namespace = mod.manifest().id;

        List<String> pools = new ArrayList<>();

        // Uma tabela declarada substitui a da base; nenhuma declarada herda a da base.
        String inherited = entity.loot != null && entity.loot.table != null
                && !entity.loot.table.isBlank()
                ? entity.loot.table
                : lootTableOf(entity.base);
        if (inherited != null) {
            pools.add("""
                        {
                          "rolls": 1,
                          "entries": [
                            {
                              "type": "minecraft:loot_table",
                              "value": %s
                            }
                          ]
                        }""".formatted(quote(inherited)));
        }

        if (entity.loot != null) {
            for (EntityDefinition.EntityDropDefinition drop : entity.loot.drops) {
                if (drop == null || drop.item == null) continue;
                pools.add(dropPool(drop));
            }
        }

        Path table = generatedRoot.resolve("data").resolve(namespace)
                .resolve("loot_table/entities").resolve(entity.id + ".json");

        write(table, """
                {
                  "type": "minecraft:entity",
                  "pools": [
                %s
                  ]
                }
                """.formatted(String.join("," + NEWLINE, pools)));
    }

    /**
     * Gera os modificadores de bioma que fazem a especie nascer sozinha.
     *
     * <p>Escrito no data pack porque e assim que o NeoForge acrescenta spawn a um bioma: por dado,
     * e nao por chamada. O Fabric faz o mesmo por API, e por isso este arquivo e inofensivo la --
     * um data pack que o adaptador nao le nao atrapalha ninguem, e manter os dois caminhos no mesmo
     * montador evita que a regra declarada valha em um lado so.
     *
     * <p><b>Um arquivo por bioma, e nao uma lista.</b> O formato aceita uma string solta -- que
     * pode ser um id ou uma tag -- ou uma lista, mas <b>tag dentro de lista nao e valida</b>. Um
     * unico {@code "#minecraft:is_mountain"} numa lista derruba a carga de registros inteira do
     * jogo, com uma mensagem que fala de "falha ao interpretar" e nao diz qual campo. Um arquivo
     * por bioma custa alguns bytes e vale nos dois casos.
     *
     * <p>As condicoes finas -- faixa de luz e de altura -- <b>nao cabem aqui</b>: o formato do
     * modificador so diz bioma, peso e tamanho de grupo. Elas sao conferidas pelo adaptador no
     * momento em que o jogo pergunta se aquela posicao serve.
     */
    private void assembleBiomeModifier(ModLoader.LoadedMod mod, EntityDefinition entity,
                                       Path generatedRoot) throws IOException {
        var spawn = entity.spawn;
        if (spawn == null || spawn.biomes == null || spawn.biomes.isEmpty()) return;

        String namespace = mod.manifest().id;
        int index = 0;

        for (String biome : spawn.biomes) {
            String name = spawn.biomes.size() == 1
                    ? entity.id
                    : entity.id + "_" + index;
            index++;

            write(generatedRoot.resolve("data").resolve(namespace)
                            .resolve("neoforge/biome_modifier").resolve(name + ".json"),
                    """
                    {
                      "type": "neoforge:add_spawns",
                      "biomes": %s,
                      "spawners": {
                        "type": %s,
                        "weight": %d,
                        "minCount": %d,
                        "maxCount": %d
                      }
                    }
                    """.formatted(quote(biome), quote(namespace + ":" + entity.id),
                            spawn.weight, spawn.minGroup, spawn.maxGroup));
        }
    }

    /**
     * Copia a pele declarada da especie para o pacote.
     *
     * <p>Sem textura declarada, nada e escrito e a criatura usa a da base -- e o que faz um
     * guardiao declarado sair identico a um golem de ferro. E o padrao certo, e quase nunca o que
     * se quer no fim: o cliente so tem como desenhar outra coisa se este arquivo existir.
     *
     * <p>Uma textura que falha e avisada e a especie continua: perder o mod inteiro por causa de um
     * PNG seria pior que a criatura sair com a pele da base.
     */
    private void assembleEntityTexture(ModLoader.LoadedMod mod, EntityDefinition entity,
                                       Path generatedRoot) {
        if (entity.texture == null || entity.texture.isBlank()) return;
        String namespace = mod.manifest().id;

        // A referencia e resolvida antes de qualquer decisao: num "@nome" o caminho e a origem so
        // existem depois disto.
        ModManifest.TextureDefinition declared = new ModManifest.TextureDefinition();
        String reference = entity.texture.trim();
        if (reference.startsWith("@")) {
            declared.ref = reference.substring(1);
        } else if (reference.toLowerCase(java.util.Locale.ROOT).startsWith("http://")
                || reference.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            declared.source = "remote";
            declared.url = reference;
        } else {
            declared.path = reference;
        }

        ModManifest.TextureDefinition texture =
                new ResourceCatalog(mod.manifest()).resolveTexture(declared);

        Path target = generatedRoot.resolve("assets").resolve(namespace)
                .resolve("textures/entity").resolve(entity.id + ".png");
        try {
            RemoteResourceManager.ResolvedTexture resolved = remoteResources.resolveTexture(
                    mod.directory(), texture, mod.manifest().remoteBase);
            copyAsPng(resolved.path(), target);
            logger.info("Textura {} preparada para entidade {}",
                    resolved.remote() ? "remota" : "local", namespace + ":" + entity.id);
        } catch (IOException error) {
            logger.warn("Textura da entidade {} indisponivel; a especie usara a pele da base: {}",
                    namespace + ":" + entity.id, error.getMessage());
        }
    }

    /**
     * Copia a geometria declarada da especie para o pacote, depois de conferi-la.
     *
     * <p>O modelo e lido aqui, e nao so copiado, para o erro chegar a quem escreveu o mod. Um osso
     * com nome que a base nao anima nao produz erro nenhum no jogo: a peca simplesmente nao
     * aparece, e o bicho sai sem um braco. Avisar na carga e a diferenca entre "sumiu um braco" e
     * "voce escreveu arm onde a base espera right_arm".
     *
     * <p>Um modelo invalido e recusado e a especie fica com a forma da base -- que e feio, mas
     * visivel e explicado. Escrever o arquivo torto daria uma criatura deformada sem uma linha de
     * log.
     */
    private void assembleEntityModel(ModLoader.LoadedMod mod, EntityDefinition entity,
                                     Path generatedRoot) {
        if (entity.model == null || entity.model.isBlank()) return;
        String namespace = mod.manifest().id;
        String id = namespace + ":" + entity.id;

        try {
            String json = readModelSource(mod, entity.model.trim());
            EntityModelSpec spec = EntityModelSpec.parse(json);

            List<String> unknown = spec.unknownBones(entity.base);
            if (!unknown.isEmpty()) {
                logger.warn("Modelo de {} declara osso(s) que {} nao anima: {}."
                                + " Essas pecas nao vao aparecer. A base espera: {}",
                        id, entity.base, unknown,
                        EntityModelSpec.BONES_BY_BASE.getOrDefault(entity.base, List.of()));
            }

            write(generatedRoot.resolve("assets").resolve(namespace)
                    .resolve("models/entity").resolve(entity.id + ".json"), json);
            logger.info("Modelo preparado para entidade {}: {} osso(s)", id, spec.bones.size());
        } catch (IOException | RuntimeException error) {
            // A origem entra no aviso porque a excecao nem sempre a traz: uma falha de rede chega
            // com mensagem nula, e "recusado: null" nao diz a ninguem o que procurar.
            logger.warn("Modelo da entidade {} recusado ({}); a especie usara a forma da base: {}",
                    id, entity.model, describe(error));
        }
    }

    /**
     * O motivo de uma falha, mesmo quando ela vem muda.
     *
     * <p>Excecao de rede costuma chegar sem mensagem, e um log que diz "recusado: null" gasta uma
     * linha para nao informar nada. O nome da classe ao menos separa "host nao existe" de "arquivo
     * malformado".
     */
    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    /** As mesmas tres origens de sempre: recurso declarado, arquivo do mod ou URL. */
    private String readModelSource(ModLoader.LoadedMod mod, String reference) throws IOException {
        ResourceCatalog catalog = new ResourceCatalog(mod.manifest());

        if (reference.startsWith("@")) {
            ModManifest.ResourceDefinition resource =
                    catalog.require(reference.substring(1), "model");
            return new String(remoteResources.resolveBytes(mod.directory(), resource,
                    mod.manifest().remoteBase), StandardCharsets.UTF_8);
        }

        // URL direta, como textura e script de registro ja aceitavam. Sem este ramo o endereco
        // caia em readRelative e era tratado como caminho de arquivo: a falha vinha como "modelo
        // nao encontrado", mandando procurar em disco um arquivo que mora na web.
        String lower = reference.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // Anunciado antes de buscar, e nao depois. Se a busca travar ou falhar, o log ja diz
            // que endereco o loader foi procurar -- o contrario deixaria uma pausa inexplicada na
            // carga e, quando falhasse, nenhuma pista de para onde ele tinha ido.
            logger.info("Mod {} carrega modelo de entidade de {}",
                    mod.manifest().id, reference);
            byte[] remote = new dev.lualoader.manifest.ManifestImports(
                    mod.directory(), remoteCache).fetchRemote(reference, null);
            return new String(remote, StandardCharsets.UTF_8);
        }

        // O cache de verdade, e nao nulo: com nulo o leitor recusa qualquer busca remota, e um
        // modelo resolvido por remote_base falharia dizendo que o acesso estava desligado.
        // O cache de verdade, e nao nulo: com nulo o leitor recusa qualquer busca remota, e um
        // modelo resolvido por remote_base falharia dizendo que o acesso estava desligado.
        byte[] bytes = new dev.lualoader.manifest.ManifestImports(mod.directory(), remoteCache)
                .withRemoteBase(mod.manifest().remoteBase)
                .readRelative(reference);
        if (bytes == null) throw new IOException("modelo nao encontrado: " + reference);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Gera o modelo do ovo de criacao.
     *
     * <p><b>Sem este arquivo o ovo e o cubo roxo e preto.</b> Um item registrado sem modelo nao da
     * erro no servidor: ele existe, entra na aba do criativo e funciona ao ser usado -- e so quem
     * esta olhando a tela descobre que ele nao tem aparencia. Foi assim que este defeito apareceu,
     * com toda a bateria de testes verde.
     *
     * <p>O pai e o molde do jogo, e nao um desenho proprio: e ele que pinta as duas cores
     * declaradas sobre a casca e as manchas. Desenhar um ovo aqui daria um item que ignora
     * {@code primary_color} e {@code secondary_color} e sai igual para toda especie.
     */
    private void assembleSpawnEggModel(ModLoader.LoadedMod mod, EntityDefinition entity,
                                       Path generatedRoot) throws IOException {
        EntityDefinition.SpawnEggDefinition egg = entity.spawnEgg;
        if (egg == null || !egg.register) return;

        String eggId = egg.id == null || egg.id.isBlank()
                ? entity.id + "_spawn_egg"
                : egg.id;

        write(generatedRoot.resolve("assets").resolve(mod.manifest().id)
                        .resolve("models/item").resolve(eggId + ".json"),
                """
                {
                  "parent": "minecraft:item/template_spawn_egg"
                }
                """);
    }

    /**
     * A tabela do jogo para uma especie, deduzida do id.
     *
     * <p>O jogo nomeia a tabela de um bicho pelo proprio id, entao {@code minecraft:zombie} guarda
     * os drops em {@code minecraft:entities/zombie}. Deduzir e melhor que uma lista aqui: uma
     * lista precisaria de uma linha por especie do jogo e envelheceria a cada versao nova.
     */
    private static String lootTableOf(String baseId) {
        if (baseId == null) return null;
        int separator = baseId.indexOf(':');
        if (separator <= 0) return null;
        return baseId.substring(0, separator) + ":entities/" + baseId.substring(separator + 1);
    }

    /** Um item declarado que cai alem do que a base ja derruba. */
    private String dropPool(EntityDefinition.EntityDropDefinition drop) {
        List<String> conditions = new ArrayList<>();
        if (drop.chance < 1.0f) {
            conditions.add("""
                                {"condition": "minecraft:random_chance", "chance": %s}"""
                    .formatted(number(drop.chance)));
        }
        if (drop.requiresPlayerKill) {
            // A condicao do jogo e sobre quem matou, e nao sobre o bicho: sem ela, um drop de
            // jogador cairia tambem quando o bicho morresse de queda ou de fogo.
            conditions.add("""
                                {"condition": "minecraft:killed_by_player"}""");
        }

        String functions = drop.min == 1 && drop.max == 1
                ? ""
                : """
                            ,
                              "functions": [
                                {
                                  "function": "minecraft:set_count",
                                  "count": {"min": %s, "max": %s}
                                }
                              ]""".formatted(number(drop.min), number(drop.max));

        String conditionBlock = conditions.isEmpty()
                ? ""
                : """
                            ,
                          "conditions": [
                %s
                          ]""".formatted(String.join("," + NEWLINE, conditions));

        return """
                    {
                      "rolls": 1,
                      "entries": [
                        {
                          "type": "minecraft:item",
                          "name": %s%s
                        }
                      ]%s
                    }""".formatted(quote(drop.item), functions, conditionBlock);
    }

    /** Numero em forma estavel: sem notacao cientifica e sem o separador decimal do sistema. */
    private static String number(float value) {
        if (value == Math.rint(value) && !Float.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value)
                .stripTrailingZeros().toPlainString();
    }

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
        if (mod.manifest().entities != null) {
            for (EntityDefinition entity : mod.manifest().entities) {
                if (entity == null || entity.id == null || entity.name == null) continue;
                entries.put("entity." + namespace + "." + entity.id, entity.name);

                // O ovo e um item, com chave propria. Sem ela o criativo mostra a chave crua.
                EntityDefinition.SpawnEggDefinition egg = entity.spawnEgg;
                if (egg == null || !egg.register) continue;
                String eggId = egg.id == null || egg.id.isBlank()
                        ? entity.id + "_spawn_egg"
                        : egg.id;
                String eggName = egg.name == null || egg.name.isBlank()
                        ? entity.name + " Spawn Egg"
                        : egg.name;
                entries.put("item." + namespace + "." + eggId, eggName);
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
        collectTags(mod.manifest().id + ":" + block.id, block.tags, tagEntries);
    }

    /**
     * Acumula as tags de um conteudo declarado, seja bloco ou item.
     *
     * <p>O mesmo metodo para os dois porque a validacao e identica -- o que muda e so a pasta em
     * que o arquivo cai, e isso quem decide e {@link #writeTags}. Enquanto so existia a versao de
     * bloco, um item declarado nao entrava em nenhuma tag: nem numa do jogo, como
     * {@code minecraft:planks}, nem numa propria para servir de ingrediente generico numa receita.
     */
    private void collectTags(String contentId, List<String> tags,
                             Map<String, Set<String>> tagEntries) {
        if (tags == null || tags.isEmpty()) return;

        for (String tag : tags) {
            if (tag == null || tag.isBlank()) continue;
            String normalized = tag.trim();
            int separator = normalized.indexOf(':');
            if (separator <= 0 || separator == normalized.length() - 1) {
                logger.warn("Tag ignorada em {}: identificador invalido {}", contentId, tag);
                continue;
            }
            tagEntries.computeIfAbsent(normalized, key -> new TreeSet<>()).add(contentId);
        }
    }

    /** Escreve cada tag acumulada como um arquivo do data pack, na pasta do tipo dela. */
    private void writeTags(Map<String, Set<String>> tagEntries, Path generatedRoot, String kind)
            throws IOException {
        for (Map.Entry<String, Set<String>> entry : tagEntries.entrySet()) {
            String tag = entry.getKey();
            int separator = tag.indexOf(':');
            String namespace = tag.substring(0, separator);
            String path = tag.substring(separator + 1);

            List<String> quoted = new ArrayList<>();
            for (String value : entry.getValue()) quoted.add("\"" + value + "\"");

            // "replace": false preserva o conteúdo vanilla da tag.
            write(generatedRoot.resolve("data").resolve(namespace).resolve("tags/" + kind)
                            .resolve(path + ".json"),
                    """
                            {
                              "replace": false,
                              "values": [
                                %s
                              ]
                            }
                            """.formatted(String.join(",\n    ", quoted)));
            logger.info("Tag {} recebeu {} {}(s) do loader", tag, entry.getValue().size(), kind);
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
            variants.put("0", singleTexture(block));
        }

        // Um modelo declarado vence a geracao por forma: quem desenhou o bloco foi especifico, e
        // gerar por cima seria ignorar o desenho. Todas as variantes apontam para ele, porque a
        // variante troca textura e o modelo declarado ja traz as suas.
        DeclaredModel declaredModel = assembleDeclaredModel(mod, block, generatedRoot);
        if (declaredModel != null) {
            // Um modelo declarado vence a forma por estado, e o aviso importa: um cano com desenho
            // proprio e `connects_to` teria dois desenhos disputando o mesmo bloco, e o multipart
            // simplesmente ignoraria o modelo -- o mod pareceria nao ter sido aplicado.
            if (dev.lualoader.content.BlockShapes.connects(block)) {
                logger.warn("Bloco {} declara modelo proprio e tambem shape.connects_to;"
                                + " o modelo vence e os bracos por conexao nao serao desenhados",
                        namespace + ":" + blockId);
            }
            // Quem escreve as pecas ja escreveu o blockstate multipart que escolhe entre elas.
            // Escrever de novo aqui trocava esse multipart por variantes, e o bloco voltava a
            // desenhar o catalogo inteiro -- o cano com as seis conexoes sempre abertas.
            if (!declaredModel.blockstateEscrito()) {
                writeDeclaredBlockstate(generatedRoot, namespace, blockId, block, declaredModel.id());
            }
            return;
        }

        Map<Integer, String> models = new LinkedHashMap<>();
        // A textura de cada variante, e nao so o modelo. Um bloco que conecta desenha caixas
        // proprias e precisa da textura em si: passar o modelo no lugar dela escreve um nome que o
        // jogo procura no atlas e nao acha, e o bloco sai roxo.
        Map<Integer, String> textures = new LinkedHashMap<>();
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
            textures.put(variant, textureReference);
        }

        if (models.isEmpty()) {
            models.put(0, "minecraft:block/stone");
            textures.put(0, "minecraft:block/stone");
        }
        writeBlockstate(generatedRoot, namespace, blockId, block, models, textures);
    }

    /**
     * A textura unica de um bloco, aceitando as duas formas de declarar.
     *
     * <p>{@code render.texture} e a forma direta. {@code render.textures} nasceu para modelo
     * declarado -- um desenho do Blockbench nomeia as proprias texturas --, mas quem escreve o mod
     * usa o mesmo mapa com a chave {@code all} sem declarar modelo, porque {@code all} e o nome que
     * o proprio jogo usa em {@code cube_all}. Isso era aceito e ignorado: o bloco caia no fallback
     * generico, e nada no log dizia por que. Aceitar as duas custa este metodo.
     *
     * <p>Com mais de uma entrada e sem modelo declarado nao ha o que escolher -- o mapa so faz
     * sentido junto com o desenho que nomeia as partes --, entao vale a primeira e o resto fica
     * para o modelo declarado usar.
     */
    private static ModManifest.TextureDefinition singleTexture(ModManifest.BlockDefinition block) {
        if (block.render == null) return null;

        ModManifest.TextureDefinition direta = block.render.texture;
        boolean declarada = direta != null
                && ((direta.path != null && !direta.path.isBlank())
                    || (direta.url != null && !direta.url.isBlank())
                    || (direta.ref != null && !direta.ref.isBlank()));
        if (declarada) return direta;

        if (block.render.textures != null && !block.render.textures.isEmpty()) {
            ModManifest.TextureDefinition all = block.render.textures.get("all");
            return all != null ? all : block.render.textures.values().iterator().next();
        }
        return direta;
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
     * Escreve o blockstate e o modelo de item de um bloco.
     *
     * <p>Um metodo so porque os dois caminhos -- modelo gerado pela forma e modelo declarado pelo
     * mod -- terminam aqui. Enquanto cada um escrevia o seu, a chance de divergirem num ajuste
     * futuro era so questao de tempo.
     */
    /**
     * As direcoes que o blockstate precisa cobrir.
     *
     * <p>{@code null} quando o bloco nao gira. A diferenca entre uma entrada com a propriedade e
     * uma sem ela nao e cosmetica: escrever {@code facing} num bloco que nao tem essa propriedade
     * faz o jogo nao achar variante nenhuma, e o resultado e o cubo roxo e preto de modelo ausente.
     */
    private static java.util.List<String> facingsOf(ModManifest.BlockDefinition block) {
        String declared = block.placement == null || block.placement.facing == null
                ? "none"
                : block.placement.facing.trim().toLowerCase(java.util.Locale.ROOT);

        return switch (declared) {
            case "horizontal", "player" -> java.util.List.of("north", "south", "west", "east");
            case "all" -> java.util.List.of("north", "south", "west", "east", "up", "down");
            default -> java.util.Collections.singletonList(null);
        };
    }

    /**
     * Gira o modelo para a direcao da variante.
     *
     * <p>Um modelo so, girado. Desenhar seis daria seis arquivos para manter em sincronia, e o
     * primeiro ajuste esqueceria um deles. Norte e a referencia do formato do jogo, entao nao leva
     * rotacao nenhuma.
     */
    private static void appendRotation(StringBuilder out, String facing) {
        if (facing == null) return;

        switch (facing) {
            case "south" -> out.append(", \"y\": 180");
            case "west" -> out.append(", \"y\": 270");
            case "east" -> out.append(", \"y\": 90");
            case "up" -> out.append(", \"x\": 270");
            case "down" -> out.append(", \"x\": 90");
            default -> {
            }
        }
    }

    /** Se o bloco declara nucleo e a quem se conectar. A regra mora no nucleo. */
    private static boolean connects(ModManifest.BlockDefinition block) {
        return dev.lualoader.content.BlockShapes.connects(block);
    }

    /**
     * Escreve o blockstate de um bloco que conecta, no formato multipart.
     *
     * <p>Uma peca sem condicao -- o nucleo, sempre desenhado -- e uma peca por lado, cada uma
     * condicionada aquela propriedade. <b>Sete pecas, e nao sessenta e quatro variantes.</b>
     *
     * <p>O braco e um modelo so, girado. Desenhar seis daria seis arquivos, e o primeiro ajuste
     * esqueceria um deles.
     */
    private void writeConnectedBlockstate(Path generatedRoot, String namespace, String blockId,
                                          ModManifest.BlockDefinition block, String texture)
            throws IOException {
        String coreModel = namespace + ":block/" + blockId + "_core";
        String armModel = namespace + ":block/" + blockId + "_arm";

        var coreBoxes = dev.lualoader.content.BlockShapes.coreBoxes(block.shape);
        var armBoxes = dev.lualoader.content.BlockShapes.armBoxes(block.shape);

        writeBoxModel(generatedRoot, namespace, blockId + "_core", coreBoxes, texture);
        boolean hasArm = !armBoxes.isEmpty();
        if (hasArm) {
            writeBoxModel(generatedRoot, namespace, blockId + "_arm", armBoxes, texture);
        }

        StringBuilder parts = new StringBuilder();
        parts.append("    { \"apply\": { \"model\": \"").append(coreModel).append("\" } }");

        if (hasArm) {
            for (String side : dev.lualoader.content.BlockShapes.SIDES) {
                parts.append(",").append(NEWLINE)
                        .append("    { \"when\": { \"").append(side).append("\": \"true\" },")
                        .append(" \"apply\": { \"model\": \"").append(armModel).append("\"");
                appendArmRotation(parts, side);
                parts.append(" } }");
            }
        }

        write(generatedRoot.resolve("assets").resolve(namespace)
                        .resolve("blockstates").resolve(blockId + ".json"),
                "{" + NEWLINE + "  \"multipart\": [" + NEWLINE + parts + NEWLINE
                        + "  ]" + NEWLINE + "}" + NEWLINE);

        if (block.item == null || block.item.register) {
            write(generatedRoot.resolve("assets").resolve(namespace)
                            .resolve("models/item").resolve(blockId + ".json"),
                    "{" + NEWLINE + "  \"parent\": \"" + coreModel + "\"" + NEWLINE + "}" + NEWLINE);
        }
    }

    /**
     * A rotacao de cada braco, no formato do blockstate.
     *
     * <p>{@code uvlock} mantem a textura de pe quando a peca gira -- sem ele, o braco de cima sai
     * com a textura deitada, e o defeito so aparece olhando de perto.
     */
    private static void appendArmRotation(StringBuilder out, String side) {
        switch (side) {
            case "south" -> out.append(", \"y\": 180, \"uvlock\": true");
            case "west" -> out.append(", \"y\": 270, \"uvlock\": true");
            case "east" -> out.append(", \"y\": 90, \"uvlock\": true");
            case "up" -> out.append(", \"x\": 270, \"uvlock\": true");
            case "down" -> out.append(", \"x\": 90, \"uvlock\": true");
            default -> {
            }
        }
    }

    /** Um modelo de uma caixa so, com a mesma textura nas seis faces. */
    /**
     * Escreve um modelo com uma caixa por elemento, todas com a mesma textura.
     *
     * <p>Varias caixas, e nao uma: o cano do mod que motivou isto tem placas nas faces alem do
     * miolo. Um elemento por caixa e o formato que o jogo entende, e a alternativa -- um modelo por
     * caixa, combinados no blockstate -- multiplicaria os arquivos sem ganhar nada.
     */
    private void writeBoxModel(Path generatedRoot, String namespace, String name,
                               java.util.List<dev.lualoader.content.BlockShapes.Box> boxes,
                               String texture) throws IOException {
        String[] lados = {"north", "south", "east", "west", "up", "down"};
        StringBuilder faces = new StringBuilder();
        for (int index = 0; index < lados.length; index++) {
            if (index > 0) faces.append(",").append(NEWLINE);
            faces.append("        \"").append(lados[index])
                    .append("\": { \"texture\": \"#all\" }");
        }

        StringBuilder elementos = new StringBuilder();
        boolean primeiro = true;
        for (var box : boxes) {
            if (box == null) continue;
            if (!primeiro) elementos.append(",").append(NEWLINE);
            primeiro = false;

            elementos.append("    { \"from\": [")
                    .append(number((float) box.fromX())).append(", ")
                    .append(number((float) box.fromY())).append(", ")
                    .append(number((float) box.fromZ())).append("],").append(NEWLINE)
                    .append("      \"to\": [")
                    .append(number((float) box.toX())).append(", ")
                    .append(number((float) box.toY())).append(", ")
                    .append(number((float) box.toZ())).append("],").append(NEWLINE)
                    .append("      \"faces\": {").append(NEWLINE).append(faces).append(NEWLINE)
                    .append("      } }");
        }

        write(generatedRoot.resolve("assets").resolve(namespace)
                        .resolve("models/block").resolve(name + ".json"),
                "{" + NEWLINE
                        + "  \"textures\": { \"all\": \"" + texture + "\","
                        + " \"particle\": \"" + texture + "\" }," + NEWLINE
                        + "  \"elements\": [" + NEWLINE + elementos + NEWLINE
                        + "  ]" + NEWLINE + "}" + NEWLINE);
    }

    /**
     * O blockstate de um bloco com modelo proprio: uma variante por direcao, e nada de multipart.
     *
     * <p>Separado do caminho comum porque a pergunta ali e "este bloco conecta?", e aqui a resposta
     * ja foi decidida: o modelo declarado vence. Reusar o outro caminho significaria uma condicao a
     * mais dentro dele, e foi assim que o modelo declarado passou a ser ignorado em silencio.
     */
    private void writeDeclaredBlockstate(Path generatedRoot, String namespace, String blockId,
                                         ModManifest.BlockDefinition block, String model)
            throws IOException {
        java.util.List<String> facings = facingsOf(block);

        StringBuilder blockstate = new StringBuilder("{" + NEWLINE + "  \"variants\": {" + NEWLINE);
        boolean primeira = true;
        for (int variant = 0; variant <= 15; variant++) {
            for (String facing : facings) {
                if (!primeira) blockstate.append(",").append(NEWLINE);
                primeira = false;

                blockstate.append("    \"lua_variant=").append(variant);
                if (facing != null) blockstate.append(",facing=").append(facing);
                blockstate.append("\": {\"model\": \"").append(model).append("\"");
                appendRotation(blockstate, facing);
                blockstate.append("}");
            }
        }
        blockstate.append(NEWLINE).append("  }").append(NEWLINE).append("}").append(NEWLINE);

        write(generatedRoot.resolve("assets").resolve(namespace)
                .resolve("blockstates").resolve(blockId + ".json"), blockstate.toString());

        // So escreve o item se ninguem ja escreveu: o caminho do OBJ escreve o dele proprio, e
        // sobrescrever aqui devolveria o pai que faz o cliente nao abrir.
        Path item = generatedRoot.resolve("assets").resolve(namespace)
                .resolve("models/item").resolve(blockId + ".json");
        if ((block.item == null || block.item.register) && !Files.exists(item)) {
            write(item, "{" + NEWLINE + "  \"parent\": \"" + model + "\"" + NEWLINE + "}" + NEWLINE);
        }
    }

    private void writeBlockstate(Path generatedRoot, String namespace, String blockId,
                                 ModManifest.BlockDefinition block,
                                 Map<Integer, String> models,
                                 Map<Integer, String> textures) throws IOException {
        String fallbackModel = models.getOrDefault(0, models.values().iterator().next());

        // Um bloco que conecta nao usa variantes: cada lado ligado acrescenta uma peca, e listar
        // as sessenta e quatro combinacoes daria sessenta e quatro entradas para manter em
        // sincronia. O formato multipart do jogo existe exatamente para isso.
        if (connects(block)) {
            // A textura, e nao o modelo. Enquanto era o modelo, o nucleo e o braco nomeavam uma
            // textura que nao existe: todo bloco que conecta saia roxo, em qualquer manifesto.
            String textura = textures.isEmpty()
                    ? fallbackTexture(block)
                    : textures.getOrDefault(0, textures.values().iterator().next());
            writeConnectedBlockstate(generatedRoot, namespace, blockId, block, textura);
            return;
        }

        // As direcoes que o bloco assume, ou uma so quando ele nao gira. Cada combinacao de
        // variante visual e direcao vira uma entrada: o modelo e o mesmo, girado.
        java.util.List<String> facings = facingsOf(block);

        StringBuilder blockstate = new StringBuilder("{\n  \"variants\": {\n");
        boolean primeira = true;
        for (int variant = 0; variant <= 15; variant++) {
            for (String facing : facings) {
                if (!primeira) blockstate.append(",\n");
                primeira = false;

                blockstate.append("    \"lua_variant=").append(variant);
                if (facing != null) blockstate.append(",facing=").append(facing);
                blockstate.append("\": {\"model\": \"")
                        .append(models.getOrDefault(variant, fallbackModel)).append("\"");
                appendRotation(blockstate, facing);
                blockstate.append("}");
            }
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

    /**
     * Copia um modelo declarado como recurso e liga as texturas que ele nomeia.
     *
     * <p>Um modelo do Blockbench nomeia as próprias texturas — {@code tampo}, {@code pe} — e o
     * manifesto diz qual recurso corresponde a cada nome. Este método faz as duas metades: copia
     * cada imagem para o pack e reescreve o nome no modelo para apontar para ela.
     *
     * <p>É por isso que o mapeamento é declarado e não deduzido. Adivinhar pela ordem, ou exigir
     * que o Blockbench já use o identificador final, obrigaria o desenho a conhecer o mod — e o
     * mesmo desenho não serviria a dois blocos com imagens diferentes.
     *
     * @return o identificador do modelo escrito, ou {@code null} quando não há modelo declarado
     */
    /**
     * Poe um modelo OBJ no pack, com um cubo texturizado de reserva.
     *
     * <p>O arquivo vai inteiro, e nao convertido: o cliente le a malha e monta as faces. O JSON
     * escrito ao lado dele e a reserva -- se o cliente nao interceptar o modelo, o bloco aparece
     * como um cubo com a textura certa em vez do cubo roxo de modelo ausente. Um mod que perde o
     * desenho e um problema; um mod que vira cubo roxo parece o jogo quebrado.
     *
     * <p>O arquivo e conferido aqui, na montagem, e nao no cliente: um OBJ quebrado precisa virar
     * mensagem para quem escreveu o mod, e o cliente e o lugar onde ninguem esta olhando o log.
     */
    private DeclaredModel assembleObjModel(ModLoader.LoadedMod mod,
                                           ModManifest.BlockDefinition block,
                                           Path generatedRoot, byte[] bytes) throws IOException {
        String namespace = mod.manifest().id;

        // A textura vem antes da malha porque a reserva precisa dela mesmo quando a malha falha.
        String textura = objTexture(mod, block, generatedRoot);

        int faces = 0;
        try (var reader = new java.io.InputStreamReader(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {

            var parsed = dev.lualoader.content.ObjModel.read(reader);
            faces = parsed.faces().size();

            Path destino = generatedRoot.resolve("assets").resolve(namespace)
                    .resolve("models/block").resolve(block.id + ".obj");
            Files.createDirectories(destino.getParent());
            Files.write(destino, bytes);
        } catch (IOException error) {
            // O arquivo quebrado nao vai para o pacote, e o bloco fica com a reserva. Deixar a
            // excecao subir derrubaria a montagem inteira: um OBJ torto num mod apagaria o pacote
            // de todos os outros, que e muito pior que um bloco desenhado como cubo.
            logger.warn("Modelo OBJ de {} recusado, usando a reserva: {}",
                    namespace + ":" + block.id, error.getMessage());
        }

        // Um catalogo de pecas vira um modelo por condicao, e um blockstate multipart que escolhe.
        var partes = block.render == null ? null : block.render.objParts;
        boolean porPeca = partes != null && faces > 0
                && dev.lualoader.content.BlockShapes.connects(block);

        if (porPeca) {
            writeObjParts(mod, generatedRoot, namespace, block, textura, partes);
        } else {
            write(generatedRoot.resolve("assets").resolve(namespace)
                            .resolve("models/block").resolve(block.id + ".json"),
                    objModelJson(namespace + ":block/" + block.id + ".obj", List.of(), textura, 1f));
        }

        // O item nao pode herdar do modelo do bloco: o pai dele passa a ser a malha, e o jogo exige
        // que o pai de um modelo JSON seja outro modelo JSON -- "BlockModel parent has to be a block
        // model", e o cliente nao abre. Entao o item ganha um modelo completo proprio.
        if (block.item == null || block.item.register) {
            write(generatedRoot.resolve("assets").resolve(namespace)
                            .resolve("models/item").resolve(block.id + ".json"),
                    "{" + NEWLINE
                            + "  \"parent\": \"minecraft:block/cube_all\"," + NEWLINE
                            + "  \"textures\": { \"all\": \"" + textura + "\","
                            + " \"particle\": \"" + textura + "\" }" + NEWLINE
                            + "}" + NEWLINE);
        }

        if (faces > 0) {
            logger.info("Modelo OBJ de {} montado: {} face(s), textura {}",
                    namespace + ":" + block.id, faces, textura);
        }
        return new DeclaredModel(namespace + ":block/" + block.id, porPeca);
    }

    /**
     * As letras que nomeiam cada direcao num arquivo de modelo.
     *
     * <p>N, S, E, W, U, D e a nomenclatura que as ferramentas usam nos nomes de grupo. Nao e escolha
     * do loader: e o que esta escrito nos arquivos que ele precisa ler.
     */
    private static final Map<String, String> LETRA_DO_LADO = Map.of(
            "north", "N", "south", "S", "east", "E", "west", "W", "up", "U", "down", "D");

    /**
     * Escreve um modelo por condicao e o blockstate que escolhe entre eles.
     *
     * <p>E como um mod monta um cano: o miolo sempre, uma peca no lado ligado, outra no lado livre.
     * O loader nao sabe nada sobre canos -- ele so aplica os grupos que o manifesto declarou, e a
     * direcao entra no lugar do {@code %s}.
     *
     * <p>O blockstate precisa de condicao <b>negativa</b> ({@code "false"}) para o lado livre. Ate
     * aqui so a positiva era escrita, e por isso um bloco so sabia crescer bracos -- nunca fechar a
     * face que ninguem usa.
     */
    private void writeObjParts(ModLoader.LoadedMod mod, Path generatedRoot, String namespace,
                               ModManifest.BlockDefinition block, String textura,
                               ModManifest.ObjPartsDefinition partes) throws IOException {
        String objRef = namespace + ":block/" + block.id + ".obj";
        StringBuilder multipart = new StringBuilder();

        if (partes.core != null && partes.core.groups != null && !partes.core.groups.isEmpty()) {
            String nome = block.id + "_core";
            write(modelPath(generatedRoot, namespace, nome),
                    objModelJson(objRef, partes.core.groups,
                            partTexture(mod, block, generatedRoot, partes.core, "core", textura),
                            partes.core.uvScale));
            multipart.append("    { \"apply\": { \"model\": \"")
                    .append(namespace).append(":block/").append(nome).append("\" } }");
        }

        for (String lado : dev.lualoader.content.BlockShapes.SIDES) {
            String letra = LETRA_DO_LADO.get(lado);

            appendPart(multipart, mod, generatedRoot, namespace, block, textura, objRef,
                    partes.connected, letra, lado, true);
            appendPart(multipart, mod, generatedRoot, namespace, block, textura, objRef,
                    partes.disconnected, letra, lado, false);
        }

        write(generatedRoot.resolve("assets").resolve(namespace)
                        .resolve("blockstates").resolve(block.id + ".json"),
                "{" + NEWLINE + "  \"multipart\": [" + NEWLINE + multipart + NEWLINE
                        + "  ]" + NEWLINE + "}" + NEWLINE);

        logger.info("Modelo OBJ de {} dividido em pecas por conexao", namespace + ":" + block.id);
    }

    /** Uma peca do catalogo, ligada a um lado do bloco estar ou nao conectado. */
    private void appendPart(StringBuilder multipart, ModLoader.LoadedMod mod, Path generatedRoot,
                            String namespace, ModManifest.BlockDefinition block, String textura,
                            String objRef, ModManifest.ObjPartDefinition peca, String letra,
                            String lado, boolean ligado) throws IOException {
        if (peca == null || peca.groups == null || peca.groups.isEmpty()) return;

        java.util.List<String> grupos = new java.util.ArrayList<>(peca.groups.size());
        for (String padrao : peca.groups) {
            if (padrao != null && !padrao.isBlank()) grupos.add(padrao.replace("%s", letra));
        }
        if (grupos.isEmpty()) return;

        String sufixo = ligado ? "on" : "off";
        String nome = block.id + "_" + sufixo + "_" + letra.toLowerCase(java.util.Locale.ROOT);
        write(modelPath(generatedRoot, namespace, nome),
                objModelJson(objRef, grupos,
                        partTexture(mod, block, generatedRoot, peca, sufixo, textura),
                        peca.uvScale));

        if (multipart.length() > 0) multipart.append(",").append(NEWLINE);
        multipart.append("    { \"when\": { \"").append(lado).append("\": \"")
                .append(ligado).append("\" },")
                .append(" \"apply\": { \"model\": \"")
                .append(namespace).append(":block/").append(nome).append("\" } }");
    }

    private Path modelPath(Path generatedRoot, String namespace, String nome) {
        return generatedRoot.resolve("assets").resolve(namespace)
                .resolve("models/block").resolve(nome + ".json");
    }

    /**
     * O JSON que aponta para a malha.
     *
     * <p>Ele nao e um modelo do jogo: e a declaracao que o cliente le para saber qual arquivo abrir
     * e quais grupos desenhar. O {@code parent} de cubo continua ali como reserva -- se o cliente
     * nao interceptar, o bloco vira um cubo com a textura certa em vez do cubo roxo de modelo
     * ausente.
     */
    /**
     * A textura de uma peca, copiada para o pacote.
     *
     * <p>Sem textura propria, vale a do bloco. E o caso comum -- so um modelo com atlas proprio
     * precisa de imagens diferentes por peca.
     */
    private String partTexture(ModLoader.LoadedMod mod, ModManifest.BlockDefinition block,
                               Path generatedRoot, ModManifest.ObjPartDefinition peca,
                               String sufixo, String padrao) throws IOException {
        if (peca.texture == null) return padrao;

        String namespace = mod.manifest().id;
        String nome = block.id + "_" + sufixo;
        Path arquivo = generatedRoot.resolve("assets").resolve(namespace)
                .resolve("textures/block").resolve(nome + ".png");
        Files.createDirectories(arquivo.getParent());

        try {
            var resolved = remoteResources.resolveTexture(mod.directory(),
                    new ResourceCatalog(mod.manifest()).resolveTexture(peca.texture),
                    mod.manifest().remoteBase);
            copyAsPng(resolved.path(), arquivo);
            return namespace + ":block/" + nome;
        } catch (IOException error) {
            logger.warn("Textura da peca {} de {} indisponivel; usando a do bloco: {}",
                    sufixo, namespace + ":" + block.id, error.getMessage());
            return padrao;
        }
    }

    private String objModelJson(String objRef, java.util.List<String> grupos, String textura,
                                float uvScale) {
        StringBuilder lista = new StringBuilder();
        for (String grupo : grupos) {
            if (lista.length() > 0) lista.append(", ");
            lista.append("\"").append(grupo).append("\"");
        }

        return "{" + NEWLINE
                + "  \"parent\": \"minecraft:block/cube_all\"," + NEWLINE
                + "  \"lua_obj\": \"" + objRef + "\"," + NEWLINE
                + "  \"lua_obj_groups\": [" + lista + "]," + NEWLINE
                + "  \"lua_obj_uv_scale\": " + number(uvScale) + "," + NEWLINE
                + "  \"textures\": { \"all\": \"" + textura + "\","
                + " \"particle\": \"" + textura + "\" }" + NEWLINE
                + "}" + NEWLINE;
    }

    /** A textura de um bloco com modelo OBJ, copiada para o pack. */
    private String objTexture(ModLoader.LoadedMod mod, ModManifest.BlockDefinition block,
                              Path generatedRoot) throws IOException {
        String namespace = mod.manifest().id;
        ModManifest.TextureDefinition declarada = singleTexture(block);
        if (declarada == null) return fallbackTexture(block);

        Path arquivo = generatedRoot.resolve("assets").resolve(namespace)
                .resolve("textures/block").resolve(block.id + "_v0.png");
        Files.createDirectories(arquivo.getParent());

        try {
            var resolved = remoteResources.resolveTexture(mod.directory(),
                    new ResourceCatalog(mod.manifest()).resolveTexture(declarada),
                    mod.manifest().remoteBase);
            copyAsPng(resolved.path(), arquivo);
            return namespace + ":block/" + block.id + "_v0";
        } catch (IOException error) {
            logger.warn("Textura do modelo OBJ de {} indisponivel; usando {}: {}",
                    namespace + ":" + block.id, fallbackTexture(block), error.getMessage());
            return fallbackTexture(block);
        }
    }

    /** O modelo declarado, e se ele ja cuidou do proprio blockstate. */
    private record DeclaredModel(String id, boolean blockstateEscrito) {
    }

    private DeclaredModel assembleDeclaredModel(ModLoader.LoadedMod mod,
                                         ModManifest.BlockDefinition block,
                                         Path generatedRoot) throws IOException {
        if (block.render == null || block.render.model == null) return null;

        String declared = block.render.model.trim();
        if (declared.isEmpty() || declared.charAt(0) != '@') return null;

        String namespace = mod.manifest().id;
        ResourceCatalog catalog = new ResourceCatalog(mod.manifest());
        ModManifest.ResourceDefinition resource =
                catalog.require(declared.substring(1), "model");

        byte[] bytes = remoteResources.resolveBytes(
                mod.directory(), resource, mod.manifest().remoteBase);

        // Um OBJ nao vira JSON de modelo: o formato do jogo descreve caixas, e uma malha nao e
        // uniao de caixas. O arquivo vai inteiro para o pack, e o cliente o transforma em faces na
        // hora de desenhar -- e por isso o desvio acontece aqui, antes de tentar ler como JSON.
        if (dev.lualoader.content.ObjModel.isObj(resource.from)) {
            return assembleObjModel(mod, block, generatedRoot, bytes);
        }

        String json = new String(bytes, StandardCharsets.UTF_8);

        var root = com.google.gson.JsonParser.parseString(json);
        if (!root.isJsonObject()) {
            throw new IOException("modelo @" + declared.substring(1) + " nao e um objeto JSON");
        }
        var model = root.getAsJsonObject();

        // As texturas do modelo passam a apontar para as do pack. O objeto e recriado, e nao
        // editado: um modelo do Blockbench costuma trazer nomes que o mod nao mapeou, e mante-los
        // apontando para o lugar antigo daria a textura ausente sem dizer por que.
        var textures = new com.google.gson.JsonObject();
        String particle = null;

        for (Map.Entry<String, ModManifest.TextureDefinition> entry
                : block.render.textures.entrySet()) {
            String slot = entry.getKey();
            String target = namespace + ":block/" + block.id + "_" + slot;

            Path file = generatedRoot.resolve("assets").resolve(namespace)
                    .resolve("textures/block").resolve(block.id + "_" + slot + ".png");
            Files.createDirectories(file.getParent());

            try {
                var resolved = remoteResources.resolveTexture(mod.directory(),
                        catalog.resolveTexture(entry.getValue()), mod.manifest().remoteBase);
                copyAsPng(resolved.path(), file);
                textures.addProperty(slot, target);
                if (particle == null) particle = target;
            } catch (IOException error) {
                ModManifest.TextureDefinition fallback = catalog.resolveTexture(entry.getValue());
                String reference = fallback != null && fallback.fallback != null
                        ? fallback.fallback
                        : "minecraft:block/stone";
                logger.warn("Textura {} do modelo de {} indisponivel; usando {}: {}",
                        slot, namespace + ":" + block.id, reference, error.getMessage());
                textures.addProperty(slot, reference);
                if (particle == null) particle = reference;
            }
        }

        // Sem particle a poeira ao quebrar sai roxa, e o modelo do Blockbench raramente o traz.
        if (particle != null && !textures.has("particle")) {
            textures.addProperty("particle", particle);
        }
        if (textures.size() > 0) model.add("textures", textures);

        String modelId = namespace + ":block/" + block.id;
        write(generatedRoot.resolve("assets").resolve(namespace)
                .resolve("models/block").resolve(block.id + ".json"), model + NEWLINE);

        logger.info("Modelo declarado aplicado a {} ({} textura(s))",
                namespace + ":" + block.id, textures.size());
        return new DeclaredModel(modelId, false);
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

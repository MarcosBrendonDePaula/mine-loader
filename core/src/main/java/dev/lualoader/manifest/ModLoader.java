package dev.lualoader.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Descobre e valida os mods-lua/<id>/mod.json. */
public final class ModLoader {
    private static final Pattern MOD_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");
    private static final Pattern LUA_FILE = Pattern.compile("^[^/\\\\][^:]*\\.lua$");
    private static final Set<String> RARITIES = Set.of("common", "uncommon", "rare", "epic");
    private static final Set<String> EVENTS = LoaderEvents.ALL;

    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            // Sem o adaptador, "texture": "@cristal" seria erro de leitura: o campo espera um
            // objeto, e uma string no lugar faz o Gson recusar o manifesto inteiro.
            .registerTypeAdapter(ModManifest.TextureDefinition.class,
                    new TextureReferenceAdapter())
            .create();
    private final Logger logger;
    private final Path importCache;
    /** Base remota do manifesto em validacao, usada para aceitar scripts que nao estao no disco. */
    private String manifestRemoteBase;

    /** Loader apenas local: imports remotos serao recusados. Usado em validacao offline e testes. */
    public ModLoader(Logger logger) {
        this(logger, null);
    }

    /**
     * @param importCache diretorio onde guardar pedacos de manifesto baixados;
     *                    {@code null} desabilita import remoto
     */
    public ModLoader(Logger logger, Path importCache) {
        this.logger = logger;
        this.importCache = importCache;
    }

    public List<LoadedMod> discover(Path root) throws IOException {
        Files.createDirectories(root);
        List<LoadedMod> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        try (var directories = Files.list(root)) {
            for (Path directory : directories.sorted().toList()) {
                if (!Files.isDirectory(directory)) continue;

                Path manifestPath = directory.resolve("mod.json");
                if (!Files.isRegularFile(manifestPath)) {
                    logger.warn("Ignorando {}: mod.json não encontrado", directory);
                    continue;
                }

                try {
                    ModManifest manifest = readManifest(manifestPath, directory);
                    validate(manifest, directory, ids);
                    if (!manifest.enabled) {
                        logger.info("Mod desabilitado: {}", manifest.id);
                        continue;
                    }
                    ids.add(manifest.id);
                    result.add(new LoadedMod(directory, manifest));
                } catch (IOException | RuntimeException error) {
                    // IOException inclui manifesto ilegivel e import quebrado. Sem este catch,
                    // um unico mod defeituoso impediria a carga de todos os outros.
                    logger.error("Falha ao carregar mod em {}: {}", directory, error.getMessage());
                }
            }
        }

        // A ordem alfabetica de diretorio nao serve quando ha bibliotecas: quem e usado por
        // outro precisa carregar antes.
        return new ModDependencies(logger).resolve(result);
    }

    private ModManifest readManifest(Path path, Path modRoot) throws IOException {
        try {
            // Os imports sao resolvidos antes da conversao, entao o resto do loader nao
            // precisa saber que o manifesto pode estar dividido em varios arquivos.
            var resolved = new ManifestImports(modRoot, importCache).readResolved(path);
            ModManifest manifest = gson.fromJson(resolved, ModManifest.class);
            if (manifest == null) throw new JsonParseException("manifesto vazio");
            return manifest;
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("JSON inválido: " + error.getMessage(), error);
        }
    }

    private void validate(ModManifest manifest, Path directory, Set<String> ids) {
        require(manifest.schema == 1, "schema deve ser 1");
        require(manifest.id != null && MOD_ID.matcher(manifest.id).matches(), "id inválido");
        require(manifest.name != null && !manifest.name.isBlank(), "name é obrigatório");
        require(manifest.version != null && !manifest.version.isBlank(), "version é obrigatória");
        // O entrypoint deixou de ser obrigatorio: um mod pode declarar apenas scripts por bloco.
        if (manifest.entrypoint != null && !manifest.entrypoint.isBlank()) {
            require(LUA_FILE.matcher(manifest.entrypoint).matches(), "entrypoint Lua inválido");
        }
        require(directory.getFileName().toString().equals(manifest.id), "o nome da pasta deve ser igual ao id");
        require(!ids.contains(manifest.id), "id duplicado: " + manifest.id);

        Path root = directory.toAbsolutePath().normalize();
        if (manifest.entrypoint != null && !manifest.entrypoint.isBlank()) {
            Path entrypoint = directory.resolve(manifest.entrypoint).toAbsolutePath().normalize();
            require(entrypoint.startsWith(root), "entrypoint sai da pasta do mod");
            require(Files.isRegularFile(entrypoint), "entrypoint não encontrado: " + manifest.entrypoint);
        }

        if (manifest.permissions != null) {
            Set<String> knownPermissions = Set.of(
                    "chat.send", "player.read", "player.inventory", "player.move", "player.menu",
                    "server.read", "server.command.register", "world.read", "world.write",
                    "entity.read", "entity.spawn", "entity.modify", "world.containers");
            for (String permission : manifest.permissions) {
                require(knownPermissions.contains(permission), "permissão desconhecida: " + permission);
            }
        }
        if (manifest.events != null) {
            for (String event : manifest.events.keySet()) {
                require(EVENTS.contains(event), "evento desconhecido: " + event);
            }
        }

        validateDependencies(manifest);
        validateItems(manifest);
        loadStructureFiles(manifest, directory);
        validateStructures(manifest);
        validateRecipes(manifest);
        validateCreativeTab(manifest);
        validateResources(manifest);

        Set<String> blockIds = new HashSet<>();
        if (manifest.blocks != null) {
            for (ModManifest.BlockDefinition block : manifest.blocks) {
                require(block != null && block.id != null && MOD_ID.matcher(block.id).matches(), "id de bloco inválido");
                require(block.name != null && !block.name.isBlank(), "name de bloco é obrigatório");
                require(blockIds.add(block.id), "bloco duplicado no mod: " + block.id);
                validateBlock(block);
                manifestRemoteBase = manifest.remoteBase;
                validateBehaviorScripts(block, directory);
            }
        }
    }

    /** Classes de ferramenta que o loader sabe registrar. */
    private static final Set<String> TOOL_TYPES =
            Set.of("pickaxe", "axe", "shovel", "hoe", "sword");

    /** Onde uma peca de armadura veste. */
    private static final Set<String> ARMOR_SLOTS =
            Set.of("helmet", "chestplate", "leggings", "boots");

    /**
     * Confere ferramenta e armadura de um item.
     *
     * <p>Um item nao pode ser as duas coisas: nao existe capacete que quebre pedra, e aceitar a
     * combinacao produziria um item que o jogo nao sabe representar.
     */
    private void validateToolAndArmor(ModManifest.ItemEntryDefinition item) {
        require(item.tool == null || item.armor == null,
                "item " + item.id + " nao pode ser ferramenta e armadura ao mesmo tempo");

        if (item.tool != null) {
            String type = item.tool.type == null
                    ? ""
                    : item.tool.type.toLowerCase(java.util.Locale.ROOT);
            require(TOOL_TYPES.contains(type),
                    "tipo de ferramenta desconhecido em " + item.id + ": " + item.tool.type
                            + " (use um de " + TOOL_TYPES + ")");

            // A escala e a do jogo -- madeira a netherita -- porque uma propria obrigaria quem
            // escreve o mod a traduzir mentalmente a cada bloco.
            require(item.tool.level >= 0 && item.tool.level <= 4,
                    "level de ferramenta em " + item.id + " deve estar entre 0 e 4");
            require(item.tool.speed > 0 && item.tool.speed <= 100,
                    "speed de ferramenta em " + item.id + " deve estar entre 0 e 100");
            require(item.tool.damage >= 0 && item.tool.damage <= 100,
                    "damage de ferramenta em " + item.id + " deve estar entre 0 e 100");
            require(item.tool.durability >= 0,
                    "durability de ferramenta em " + item.id + " nao pode ser negativa");
            require(item.tool.enchantability >= 0 && item.tool.enchantability <= 50,
                    "enchantability em " + item.id + " deve estar entre 0 e 50");

            // Uma ferramenta empilhada nao teria como guardar o desgaste de cada copia.
            require(item.maxStackSize == 1,
                    "ferramenta " + item.id + " precisa de max_stack_size 1");
        }

        if (item.armor != null) {
            String slot = item.armor.slot == null
                    ? ""
                    : item.armor.slot.toLowerCase(java.util.Locale.ROOT);
            require(ARMOR_SLOTS.contains(slot),
                    "slot de armadura desconhecido em " + item.id + ": " + item.armor.slot
                            + " (use um de " + ARMOR_SLOTS + ")");

            require(item.armor.protection >= 0 && item.armor.protection <= 20,
                    "protection em " + item.id + " deve estar entre 0 e 20");
            require(item.armor.toughness >= 0 && item.armor.toughness <= 20,
                    "toughness em " + item.id + " deve estar entre 0 e 20");
            require(item.armor.knockbackResistance >= 0 && item.armor.knockbackResistance <= 1,
                    "knockback_resistance em " + item.id + " deve estar entre 0 e 1");
            require(item.armor.durability >= 0,
                    "durability de armadura em " + item.id + " nao pode ser negativa");
            require(item.maxStackSize == 1,
                    "armadura " + item.id + " precisa de max_stack_size 1");
        }
    }

    private void validateItems(ModManifest manifest) {
        if (manifest.items == null) return;
        Set<String> itemIds = new HashSet<>();
        for (ModManifest.ItemEntryDefinition item : manifest.items) {
            require(item != null && item.id != null && MOD_ID.matcher(item.id).matches(), "id de item invalido");
            require(item.name != null && !item.name.isBlank(), "name de item e obrigatorio");
            require(itemIds.add(item.id), "item duplicado no mod: " + item.id);
            require(item.maxStackSize >= 1 && item.maxStackSize <= 64, "max_stack_size de item deve estar entre 1 e 64");
            require(item.maxDamage >= 0, "max_damage de item nao pode ser negativo");
            validateToolAndArmor(item);
            require(item.maxDamage == 0 || item.maxStackSize == 1,
                    "item com durabilidade precisa de max_stack_size igual a 1: " + item.id);
            require(RARITIES.contains(rarityOf(item.rarity)), "rarity de item desconhecida: " + item.rarity);
        }
    }

    private void validateRecipes(ModManifest manifest) {
        if (manifest.recipes == null) return;
        Set<String> ids = new HashSet<>();

        for (ModManifest.RecipeDefinition recipe : manifest.recipes) {
            require(recipe != null && recipe.id != null && MOD_ID.matcher(recipe.id).matches(),
                    "id de receita invalido");
            require(ids.add(recipe.id), "receita duplicada no mod: " + recipe.id);
            require(recipe.result != null && recipe.result.indexOf(':') > 0,
                    "receita " + recipe.id + " precisa de result no formato mod:item");
            require(recipe.count >= 1 && recipe.count <= 64,
                    "count da receita " + recipe.id + " deve estar entre 1 e 64");

            String type = recipe.type == null ? "shaped" : recipe.type.trim().toLowerCase(java.util.Locale.ROOT);
            require(Set.of("shaped", "shapeless").contains(type),
                    "tipo de receita desconhecido em " + recipe.id + ": " + recipe.type);

            if ("shaped".equals(type)) {
                require(recipe.pattern != null && !recipe.pattern.isEmpty() && recipe.pattern.size() <= 3,
                        "receita " + recipe.id + " precisa de 1 a 3 linhas em pattern");
                for (String line : recipe.pattern) {
                    require(line != null && !line.isEmpty() && line.length() <= 3,
                            "linha de pattern invalida em " + recipe.id + ": " + line);
                    for (char symbol : line.toCharArray()) {
                        // Espaco significa slot vazio e nao precisa estar na chave.
                        require(symbol == ' ' || recipe.key.containsKey(String.valueOf(symbol)),
                                "simbolo fora da key em " + recipe.id + ": " + symbol);
                    }
                }
                for (Map.Entry<String, String> entry : recipe.key.entrySet()) {
                    require(entry.getKey().length() == 1,
                            "simbolo de key precisa ter um caractere em " + recipe.id + ": " + entry.getKey());
                    require(entry.getValue() != null && entry.getValue().indexOf(':') > 0,
                            "ingrediente precisa do formato mod:item em " + recipe.id);
                }
            } else {
                require(recipe.ingredients != null && !recipe.ingredients.isEmpty()
                                && recipe.ingredients.size() <= 9,
                        "receita " + recipe.id + " precisa de 1 a 9 ingredientes");
                for (String ingredient : recipe.ingredients) {
                    require(ingredient != null && ingredient.indexOf(':') > 0,
                            "ingrediente precisa do formato mod:item em " + recipe.id);
                }
            }
        }
    }

    private void validateDependencies(ModManifest manifest) {
        if (manifest.dependencies == null) return;
        for (Map.Entry<String, String> entry : manifest.dependencies.entrySet()) {
            require(entry.getKey() != null && MOD_ID.matcher(entry.getKey()).matches(),
                    "id de dependencia invalido: " + entry.getKey());
            require(!entry.getKey().equals(manifest.id), "um mod nao pode depender de si mesmo");
        }
    }

    /**
     * Carrega as estruturas que vêm de um arquivo do jogo.
     *
     * <p>Acontece antes da validação, e não sob demanda: depois disto a estrutura é indistinguível
     * de uma escrita à mão, então tudo o que já vale para ela — símbolo fora da paleta, teto de
     * volume, {@code origin} — vale igual. E um arquivo quebrado recusa o mod na carga, em vez de
     * falhar quando alguém tentar construir.
     */
    private void loadStructureFiles(ModManifest manifest, Path directory) {
        if (manifest.structures == null) return;

        for (int index = 0; index < manifest.structures.size(); index++) {
            ModManifest.StructureDefinition structure = manifest.structures.get(index);
            if (structure == null || structure.from == null || structure.from.isBlank()) continue;

            require(structure.id != null && MOD_ID.matcher(structure.id).matches(),
                    "estrutura de arquivo precisa de id: " + structure.from);

            Path root = directory.toAbsolutePath().normalize();
            Path file = root.resolve(structure.from).normalize();
            require(file.startsWith(root),
                    "caminho de estrutura sai da pasta do mod: " + structure.from);
            require(Files.isRegularFile(file),
                    "arquivo de estrutura nao encontrado: " + structure.from);

            try {
                var loaded = dev.lualoader.structure.StructureNbt.read(
                        Files.readAllBytes(file), structure.id);
                // O nome declarado no manifesto vence o do arquivo: e o que aparece para quem joga.
                if (structure.name != null && !structure.name.isBlank()) loaded.name = structure.name;
                if (structure.origin != null && !structure.origin.isBlank()) {
                    loaded.origin = structure.origin;
                }
                manifest.structures.set(index, loaded);
            } catch (IOException error) {
                throw new IllegalArgumentException(
                        "estrutura " + structure.id + " ilegivel: " + error.getMessage(), error);
            }
        }
    }

    private void validateStructures(ModManifest manifest) {
        if (manifest.structures == null) return;
        Set<String> structureIds = new HashSet<>();

        for (ModManifest.StructureDefinition structure : manifest.structures) {
            require(structure != null && structure.id != null && MOD_ID.matcher(structure.id).matches(),
                    "id de estrutura invalido");
            require(structureIds.add(structure.id), "estrutura duplicada no mod: " + structure.id);
            require(structure.origin == null || Set.of("bottom_center", "corner").contains(structure.origin),
                    "origin de estrutura desconhecida: " + structure.origin);
            require(structure.layers != null && !structure.layers.isEmpty(),
                    "estrutura sem camadas: " + structure.id);
            require(structure.palette != null && !structure.palette.isEmpty(),
                    "estrutura sem paleta: " + structure.id);

            for (Map.Entry<String, String> entry : structure.palette.entrySet()) {
                require(entry.getKey() != null && entry.getKey().length() == 1,
                        "simbolo de paleta precisa ter exatamente um caractere: " + entry.getKey());
                String value = entry.getValue();
                if (value == null || value.isBlank()) continue;
                int separator = value.indexOf(':');
                require(separator > 0 && separator < value.length() - 1,
                        "bloco da paleta precisa do formato mod:bloco: " + value);
            }

            // Todo simbolo desenhado precisa existir na paleta, senao o posicionamento falharia
            // so na hora de construir, longe da causa.
            for (List<String> layer : structure.layers) {
                require(layer != null && !layer.isEmpty(), "camada vazia na estrutura " + structure.id);
                for (String row : layer) {
                    require(row != null, "linha nula na estrutura " + structure.id);
                    for (int index = 0; index < row.length(); index++) {
                        String symbol = String.valueOf(row.charAt(index));
                        require(structure.palette.containsKey(symbol),
                                "simbolo fora da paleta na estrutura " + structure.id + ": " + symbol);
                    }
                }
            }
        }
    }

    /** Nome de recurso: as mesmas letras de um id, para nao surpreender quem ja escreveu um. */
    private static final Pattern RESOURCE_NAME = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    /**
     * Confere os recursos declarados e as referências a eles.
     *
     * <p>Uma referência quebrada precisa falhar aqui, e não quando alguém olha o bloco pela
     * primeira vez: o sintoma lá é um cubo roxo, que não diz nome de recurso nenhum.
     */
    private void validateResources(ModManifest manifest) {
        if (manifest.resources != null) {
            for (Map.Entry<String, ModManifest.ResourceDefinition> entry
                    : manifest.resources.entrySet()) {
                String name = entry.getKey();
                ModManifest.ResourceDefinition resource = entry.getValue();

                require(name != null && RESOURCE_NAME.matcher(name).matches(),
                        "nome de recurso invalido: " + name);
                require(resource != null, "recurso vazio: " + name);
                require(resource.from != null && !resource.from.isBlank(),
                        "recurso @" + name + " sem 'from'");

                String type = resource.type == null ? "image" : resource.type;
                require(dev.lualoader.resources.ResourceCatalog.TYPES.contains(type),
                        "tipo de recurso desconhecido em @" + name + ": " + type
                                + "; conhecidos: " + dev.lualoader.resources.ResourceCatalog.TYPES);

                if (!dev.lualoader.resources.ResourceCatalog.isRemote(resource.from)) {
                    require(!resource.from.startsWith("/") && !resource.from.contains(".."),
                            "caminho de recurso sai da pasta do mod: @" + name);
                }
            }
        }

        // As referencias so podem ser conferidas depois de todos os recursos existirem, porque a
        // ordem no JSON nao diz nada sobre quem referencia quem.
        var catalog = new dev.lualoader.resources.ResourceCatalog(manifest);
        if (manifest.blocks != null) {
            for (ModManifest.BlockDefinition block : manifest.blocks) {
                if (block == null || block.render == null) continue;
                requireResolvable(catalog, block.render.texture, block.id);

                // O modelo tambem e referencia, e o tipo errado aqui produziria um JSON invalido
                // no pack -- erro que so aparece quando o cliente tenta desenhar o bloco.
                String model = block.render.model;
                if (model != null && model.startsWith("@")) {
                    try {
                        catalog.require(model.substring(1), "model");
                    } catch (IllegalArgumentException error) {
                        throw new IllegalArgumentException(
                                "em " + block.id + ": " + error.getMessage(), error);
                    }
                }
                for (ModManifest.TextureDefinition slot : block.render.textures.values()) {
                    requireResolvable(catalog, slot, block.id);
                }

                if (block.render.variantTextures != null) {
                    for (ModManifest.TextureDefinition variant
                            : block.render.variantTextures.values()) {
                        requireResolvable(catalog, variant, block.id);
                    }
                }
            }
        }
        if (manifest.items != null) {
            for (ModManifest.ItemEntryDefinition item : manifest.items) {
                if (item == null) continue;
                requireResolvable(catalog, item.texture, item.id);
            }
        }
    }

    private void requireResolvable(dev.lualoader.resources.ResourceCatalog catalog,
                                   ModManifest.TextureDefinition texture, String owner) {
        if (texture == null || texture.ref == null) return;
        try {
            catalog.resolveTexture(texture);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("em " + owner + ": " + error.getMessage(), error);
        }
    }

    private void validateCreativeTab(ModManifest manifest) {
        ModManifest.CreativeTabDefinition tab = manifest.creativeTab;
        if (tab == null) return;
        require(tab.id != null && MOD_ID.matcher(tab.id).matches(), "id de creative_tab invalido");
        require(tab.name == null || !tab.name.isBlank(), "name de creative_tab nao pode ser vazio");
        if (tab.icon != null && !tab.icon.isBlank()) {
            int separator = tab.icon.indexOf(':');
            require(separator > 0 && separator < tab.icon.length() - 1,
                    "icon de creative_tab precisa do formato mod:item: " + tab.icon);
        }
    }

    private static String rarityOf(String value) {
        return value == null || value.isBlank() ? "common" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Valida os scripts declarados em {@code behavior}.
     *
     * <p>Um valor terminado em {@code .lua} aponta para um arquivo; qualquer outro texto e o nome
     * de uma funcao devolvida pelo entrypoint. O arquivo e checado aqui para que um caminho errado
     * apareca na carga, e nao no primeiro clique do jogador.
     */
    private void validateBehaviorScripts(ModManifest.BlockDefinition block, Path directory) {
        if (block.behavior == null) return;
        Path root = directory.toAbsolutePath().normalize();

        for (Map.Entry<String, String> entry : behaviorHandlers(block.behavior).entrySet()) {
            String handler = entry.getValue();
            if (handler == null || handler.isBlank()) continue;
            if (!handler.toLowerCase(java.util.Locale.ROOT).endsWith(".lua")) continue;

            require(LUA_FILE.matcher(handler).matches(),
                    "caminho de script invalido em behavior." + entry.getKey() + ": " + handler);
            Path script = directory.resolve(handler).toAbsolutePath().normalize();
            require(script.startsWith(root),
                    "script de behavior." + entry.getKey() + " sai da pasta do mod: " + handler);
            // Com uma base remota declarada, o arquivo pode nao existir no disco: ele sera
            // buscado na rede no momento da carga do script.
            if (manifestRemoteBase == null) {
                require(Files.isRegularFile(script),
                        "script de behavior." + entry.getKey() + " nao encontrado: " + handler);
            }
        }
    }

    /** Handlers declarados, por nome de evento. */
    public static java.util.Map<String, String> behaviorHandlers(ModManifest.BehaviorDefinition behavior) {
        java.util.Map<String, String> handlers = new java.util.LinkedHashMap<>();
        if (behavior == null) return handlers;
        if (behavior.onUse != null) handlers.put("on_use", behavior.onUse);
        if (behavior.onAttack != null) handlers.put("on_attack", behavior.onAttack);
        if (behavior.onBreak != null) handlers.put("on_break", behavior.onBreak);
        if (behavior.onPlaced != null) handlers.put("on_placed", behavior.onPlaced);
        if (behavior.onBroken != null) handlers.put("on_broken", behavior.onBroken);
        if (behavior.onRandomTick != null) handlers.put("on_random_tick", behavior.onRandomTick);
        if (behavior.onNeighborUpdate != null) handlers.put("on_neighbor_update", behavior.onNeighborUpdate);
        if (behavior.onPlace != null) handlers.put("on_place", behavior.onPlace);
        return handlers;
    }

    private void validateBlock(ModManifest.BlockDefinition block) {
        if (block.settings != null) {
            require(block.settings.hardness >= 0 && block.settings.hardness <= 100, "hardness de bloco fora do intervalo");
            require(block.settings.resistance >= 0 && block.settings.resistance <= 100, "resistance de bloco fora do intervalo");
            require(block.settings.luminance >= 0 && block.settings.luminance <= 15, "luminance deve estar entre 0 e 15");
            require(block.settings.slipperiness >= 0 && block.settings.slipperiness <= 10, "slipperiness fora do intervalo");
            require(block.settings.velocityMultiplier >= 0 && block.settings.velocityMultiplier <= 10, "velocityMultiplier fora do intervalo");
            require(block.settings.jumpVelocityMultiplier >= 0 && block.settings.jumpVelocityMultiplier <= 10, "jumpVelocityMultiplier fora do intervalo");
        }
        if (block.item != null) {
            require(RARITIES.contains(rarityOf(block.item.rarity)),
                    "rarity de item de bloco desconhecida: " + block.item.rarity);
        }
        if (block.shape != null) {
            for (String forma : new String[]{
                    block.shape.collision, block.shape.outline, block.shape.visual}) {
                if (forma == null || forma.isBlank()) continue;
                require(dev.lualoader.content.BlockShapes.isKnown(forma),
                        "forma desconhecida: " + forma + "; conhecidas: "
                                + dev.lualoader.content.BlockShapes.names());
            }

            for (List<Float> caixa : block.shape.boxes) {
                require(caixa != null && caixa.size() == 6,
                        "cada caixa de shape.boxes precisa de seis numeros: x1,y1,z1,x2,y2,z2");
                for (Float valor : caixa) {
                    require(valor != null && valor >= 0 && valor <= 16,
                            "coordenada de caixa fora do bloco (0 a 16): " + valor);
                }
                // Uma caixa invertida desenha do avesso: as faces apontam para dentro e a peca
                // fica invisivel de fora, que e um defeito dificil de ligar a causa.
                require(caixa.get(0) < caixa.get(3) && caixa.get(1) < caixa.get(4)
                                && caixa.get(2) < caixa.get(5),
                        "caixa invertida: cada coordenada final precisa ser maior que a inicial");
            }
        }
        if (block.inventory != null) {
            // O teto e o do bau grande, e nao um numero escolhido aqui: a janela do jogo desenha
            // ate seis fileiras de nove, e um inventario maior teria slots que ninguem alcanca.
            require(block.inventory.size >= 1 && block.inventory.size <= 54,
                    "inventory.size deve estar entre 1 e 54");
            require(block.inventory.size % 9 == 0,
                    "inventory.size deve ser multiplo de 9, para fechar as fileiras da janela");
        }
        if (block.state != null && block.state.properties != null) {
            Set<String> propertyNames = new HashSet<>();
            for (ModManifest.StatePropertyDefinition property : block.state.properties) {
                require(property != null && property.name != null && property.name.matches("^[a-z][a-z0-9_]{0,31}$"), "nome de estado inválido");
                require(propertyNames.add(property.name), "propriedade de estado duplicada: " + property.name);
                require(property.values != null && !property.values.isEmpty(), "estado sem valores: " + property.name);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record LoadedMod(Path directory, ModManifest manifest) {
    }
}

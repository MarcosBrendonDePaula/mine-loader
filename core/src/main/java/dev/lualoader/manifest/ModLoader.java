package dev.lualoader.manifest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;
import dev.lualoader.camera.CameraProtocol;
import com.google.gson.JsonParseException;
import dev.lualoader.command.CommandSchema;
import dev.lualoader.input.KeybindProtocol;
import dev.lualoader.platform.EntityDefinition;
import dev.lualoader.platform.EntitySpec;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Descobre e valida os mods-lua/<id>/mod.json. */
public final class ModLoader {
    private static final Pattern MOD_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");
    private static final Pattern LUA_FILE = Pattern.compile("^[^/\\\\][^:]*\\.lua$");
    private static final Pattern DOMAIN_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,31}$");
    private static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+$");
    private static final Pattern CONTRACT_VERSION = Pattern.compile(
            "^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$");
    private static final Set<String> RARITIES = Set.of("common", "uncommon", "rare", "epic");

    /**
     * Categorias de criatura que o jogo conhece.
     *
     * <p>Conjunto fechado, e não uma string livre: a categoria decide limite populacional e
     * condição de nascimento, e um valor escrito errado viraria uma espécie que simplesmente
     * nunca aparece — o tipo de defeito que não deixa rastro no log.
     */
    /**
     * Como um bloco declarado escolhe a direcao ao ser colocado.
     *
     * <p>Conjunto fechado porque cada valor vira uma propriedade de estado diferente, e um nome
     * errado daria um bloco que nunca gira -- sem erro, e parecendo que o loader ignora o campo.
     * Que foi exatamente o que aconteceu enquanto o campo era aceito e descartado.
     */
    private static final Set<String> FACINGS = Set.of("none", "horizontal", "all", "player");

    private static final Set<String> ENTITY_CATEGORIES = Set.of(
            "monster", "creature", "ambient", "axolotls", "underground_water_creature",
            "water_creature", "water_ambient", "misc");
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
    private final RuntimeContract runtimeContract;
    /** Base remota do manifesto em validacao, usada para aceitar scripts que nao estao no disco. */
    private String manifestRemoteBase;

    /** Loader apenas local: imports remotos serao recusados. Usado em validacao offline e testes. */
    public ModLoader(Logger logger) {
        this(logger, null, RuntimeContract.standard());
    }

    /**
     * @param importCache diretorio onde guardar pedacos de manifesto baixados;
     *                    {@code null} desabilita import remoto
     */
    public ModLoader(Logger logger, Path importCache) {
        this(logger, importCache, RuntimeContract.standard());
    }

    /**
     * Cria um loader contra o contrato efectivamente exposto por um runtime.
     *
     * <p>O perfil pertence ao bridge, mas é um DTO do core: assim a validação não precisa conhecer
     * classes de Fabric, NeoForge ou Minecraft.
     */
    public ModLoader(Logger logger, Path importCache, RuntimeContract runtimeContract) {
        this.logger = logger;
        this.importCache = importCache;
        this.runtimeContract = java.util.Objects.requireNonNull(runtimeContract, "runtimeContract");
    }

    /**
     * Tudo que existe na pasta de mods, inclusive o que a carga descarta.
     *
     * <p>{@link #discover} devolve só o que vai rodar: um mod desativado é pulado, e um mod com
     * manifesto quebrado também. Isso é certo para carregar e <b>errado para uma lista</b> — uma
     * tela que mostrasse só o que carregou nunca deixaria alguém reativar o que desativou, nem
     * descobrir por que aquele mod sumiu.
     *
     * <p>Não valida além de conseguir ler o manifesto: o objetivo aqui é enxergar, e recusar um mod
     * torto na tela apagaria justamente a linha que explica o problema de quem está procurando.
     */
    public List<CatalogEntry> catalog(Path root) throws IOException {
        Files.createDirectories(root);
        List<CatalogEntry> entries = new ArrayList<>();

        try (var directories = Files.list(root)) {
            for (Path directory : directories.filter(Files::isDirectory).sorted().toList()) {
                Path manifestPath = directory.resolve("mod.json");
                if (!Files.isRegularFile(manifestPath)) continue;

                try {
                    ModManifest manifest = readManifest(manifestPath, directory);
                    entries.add(new CatalogEntry(directory, manifest,
                            manifest.enabled ? State.ENABLED : State.DISABLED, null));
                } catch (IOException | RuntimeException error) {
                    // A pasta entra na lista mesmo sem manifesto legivel: e o unico jeito de quem
                    // esta olhando descobrir que aquele mod existe e esta quebrado, em vez de
                    // concluir que ele nunca foi copiado.
                    entries.add(new CatalogEntry(directory, null, State.BROKEN,
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage()));
                }
            }
        }
        return List.copyOf(entries);
    }

    /** Em que pé está um mod da pasta. */
    public enum State {
        /** Vai carregar. */
        ENABLED,
        /** Declara {@code "enabled": false} e é pulado de propósito. */
        DISABLED,
        /** Tem pasta e não tem manifesto legível. */
        BROKEN
    }

    /**
     * Um mod visto de fora.
     *
     * @param manifest {@code null} quando não deu para ler
     * @param reason   por que está quebrado, ou {@code null}
     */
    public record CatalogEntry(Path directory, ModManifest manifest, State state, String reason) {
        /** O id, mesmo quando não há manifesto: aí o nome da pasta é o melhor que se tem. */
        public String id() {
            return manifest != null && manifest.id != null
                    ? manifest.id
                    : directory.getFileName().toString();
        }
    }

    /**
     * Liga ou desliga um mod, escrevendo {@code enabled} no manifesto dele.
     *
     * <p>Reescreve a árvore JSON inteira em vez de costurar texto: uma substituição por linha
     * quebraria em um manifesto que já traz {@code "enabled"} dentro de outro objeto, ou que não o
     * traz. O resto do conteúdo sobrevive — inclusive {@code $import}, porque o arquivo é lido cru,
     * antes de qualquer resolução.
     *
     * <p>A formatação, essa, é refeita. É o preço de não interpretar texto à mão.
     *
     * @return se o arquivo foi escrito
     */
    public boolean setEnabled(Path modDirectory, boolean enabled) throws IOException {
        Path manifestPath = modDirectory.resolve("mod.json");
        if (!Files.isRegularFile(manifestPath)) return false;

        String source = Files.readString(manifestPath, StandardCharsets.UTF_8);
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(source);
        if (!root.isJsonObject()) return false;

        root.getAsJsonObject().addProperty("enabled", enabled);
        Files.writeString(manifestPath,
                new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        logger.info("Mod {} agora esta {}", modDirectory.getFileName(),
                enabled ? "ligado" : "desligado");
        return true;
    }

    /**
     * A variavel de ambiente que acrescenta pastas de mod as do jogo.
     *
     * <p>Existe para desenvolver um mod que mora em outro repositorio sem copiar nada para dentro
     * de {@code mods-lua}. Copiar era o que se fazia, e custou caro nesta sessao: a copia
     * envelhecia e o servidor rodava contra um script velho dizendo que passou.
     *
     * <p>Aceita varias pastas, separadas pelo separador de caminho do sistema -- {@code ;} no
     * Windows, {@code :} no resto. Cada uma pode ser a pasta de um mod (tem {@code mod.json}) ou
     * uma pasta que contem varios.
     */
    public static final String EXTRA_DIRS_ENV = "MINE_LOADER_MODS";

    /** O mesmo, como propriedade de sistema, para passar com {@code -D} num run do Gradle. */
    public static final String EXTRA_DIRS_PROPERTY = "mineloader.mods";

    /**
     * As pastas extras declaradas no ambiente, na ordem em que foram escritas.
     *
     * <p>Publico porque a tela de mods tambem quer mostrar de onde cada mod veio: um mod que
     * aparece na lista e nao esta em {@code mods-lua} confunde ate se saber que veio daqui.
     */
    public static List<Path> extraDirectories() {
        String declarado = System.getProperty(EXTRA_DIRS_PROPERTY);
        if (declarado == null || declarado.isBlank()) declarado = System.getenv(EXTRA_DIRS_ENV);
        if (declarado == null || declarado.isBlank()) return List.of();

        List<Path> pastas = new ArrayList<>();
        for (String parte : declarado.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (parte == null || parte.isBlank()) continue;
            pastas.add(Path.of(parte.trim()).toAbsolutePath().normalize());
        }
        return List.copyOf(pastas);
    }

    /**
     * Descobre os mods da pasta do jogo e das pastas extras do ambiente.
     *
     * <p><b>As extras vem primeiro, e de proposito.</b> Quem aponta uma pasta extra esta
     * trabalhando naquele mod; se o mesmo id existir nas duas, a versao em desenvolvimento e a que
     * vale. A copia ignorada e registrada no log com as duas origens -- silenciar isso daria dois
     * mods iguais e nenhuma pista de qual esta rodando.
     */
    public List<LoadedMod> discover(Path root) throws IOException {
        Files.createDirectories(root);

        List<Path> extras = extraDirectories();
        if (extras.isEmpty()) return discoverIn(List.of(root));

        List<Path> todas = new ArrayList<>(extras);
        todas.add(root);
        return discoverIn(todas);
    }

    /** Descobre mods em varias pastas, na ordem dada. */
    public List<LoadedMod> discoverIn(List<Path> roots) throws IOException {
        List<LoadedMod> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, Path> origem = new java.util.LinkedHashMap<>();

        for (Path root : roots) {
            if (root == null) continue;
            collect(root, result, ids, origem);
        }

        // A ordem alfabetica de diretorio nao serve quando ha bibliotecas: quem e usado por
        // outro precisa carregar antes.
        return new ModDependencies(logger).resolve(result);
    }

    /** Uma pasta pode ser a de um mod, ou a pasta que contem varios. */
    private void collect(Path root, List<LoadedMod> result, Set<String> ids, Map<String, Path> origem)
            throws IOException {
        // Apontar direto para a pasta do mod e o gesto natural de quem tem o mod num repositorio
        // proprio: la ele e a raiz, e nao um item de uma lista.
        if (Files.isRegularFile(root.resolve("mod.json"))) {
            loadOne(root, result, ids, origem);
            return;
        }

        if (!Files.isDirectory(root)) {
            logger.warn("Pasta de mods nao encontrada: {}", root);
            return;
        }

        try (var directories = Files.list(root)) {
            for (Path directory : directories.sorted().toList()) {
                if (!Files.isDirectory(directory)) continue;

                if (!Files.isRegularFile(directory.resolve("mod.json"))) {
                    logger.warn("Ignorando {}: mod.json não encontrado", directory);
                    continue;
                }
                loadOne(directory, result, ids, origem);
            }
        }
    }

    private void loadOne(Path directory, List<LoadedMod> result, Set<String> ids,
                         Map<String, Path> origem) {
        try {
            ModManifest manifest = readManifest(directory.resolve("mod.json"), directory);

            // O id repetido entre pastas nao e erro de manifesto: e a copia antiga perdendo para a
            // pasta de desenvolvimento. Dizer as duas origens e o que evita a duvida de qual rodou.
            Path anterior = origem.get(manifest.id);
            if (anterior != null) {
                logger.info("Mod {} ja veio de {}; ignorando a copia em {}",
                        manifest.id, anterior, directory);
                return;
            }

            validate(manifest, directory, ids);
            if (!manifest.enabled) {
                logger.info("Mod desabilitado: {}", manifest.id);
                return;
            }
            ids.add(manifest.id);
            origem.put(manifest.id, directory);
            result.add(new LoadedMod(directory, manifest));
        } catch (IOException | RuntimeException error) {
            // IOException inclui manifesto ilegivel e import quebrado. Sem este catch, um unico
            // mod defeituoso impediria a carga de todos os outros.
            logger.error("Falha ao carregar mod em {}: {}", directory, error.getMessage());
        }
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
        //
        // Mas quem mapeia eventos precisa dele. O mapeamento aponta para funcoes do entrypoint, e
        // sem entrypoint essas funcoes nao existem em lugar nenhum: o loader registrava os blocos,
        // nao executava script algum, e nada reclamava. O sintoma era um mod que carrega e nao faz
        // nada -- o modo de falhar mais caro, porque parece que o loader esta quebrado, e nao que
        // o manifesto esta incompleto.
        boolean hasEntrypoint = manifest.entrypoint != null && !manifest.entrypoint.isBlank();
        require(hasEntrypoint || manifest.events == null || manifest.events.isEmpty(),
                "o manifesto mapeia evento e nao declara entrypoint;"
                        + " o mapeamento aponta para funcoes de um script que nao existe");

        if (hasEntrypoint) {
            require(LUA_FILE.matcher(manifest.entrypoint).matches(), "entrypoint Lua inválido");
        }
        if (manifest.side != null && !manifest.side.isBlank()) {
            String side = manifest.side.trim().toLowerCase(java.util.Locale.ROOT);
            require(side.equals("server") || side.equals("both"),
                    "side deve ser 'server' ou 'both'; 'client' nao existe porque nenhum script"
                            + " roda no cliente");

            // Declarar "server" tendo conteudo e uma promessa que quebra na hora de alguem entrar:
            // o bloco nao estaria registrado no cliente, e a conexao seria recusada por
            // divergencia de registro -- um erro que nao se parece nem um pouco com a causa.
            boolean hasContent = (manifest.blocks != null && !manifest.blocks.isEmpty())
                    || (manifest.items != null && !manifest.items.isEmpty());
            require(!(side.equals("server") && hasContent),
                    "side 'server' nao vale para um mod que registra bloco ou item:"
                            + " quem entrar precisa te-lo instalado");
        }

        require(directory.getFileName().toString().equals(manifest.id), "o nome da pasta deve ser igual ao id");
        require(!ids.contains(manifest.id), "id duplicado: " + manifest.id);

        Path root = directory.toAbsolutePath().normalize();
        if (manifest.entrypoint != null && !manifest.entrypoint.isBlank()) {
            Path entrypoint = directory.resolve(manifest.entrypoint).toAbsolutePath().normalize();
            require(entrypoint.startsWith(root), "entrypoint sai da pasta do mod");

            // Com uma base remota declarada, o arquivo pode nao existir no disco: ele sera buscado
            // na rede na hora de carregar, como ja acontece com modulo e comportamento de bloco.
            // E o que permite instalar um mod publicado na web com um mod.json de poucas linhas.
            boolean remoto = manifest.remoteBase != null && !manifest.remoteBase.isBlank();
            require(remoto || Files.isRegularFile(entrypoint),
                    "entrypoint não encontrado: " + manifest.entrypoint);
        }

        if (manifest.permissions != null) {
            Set<String> knownPermissions = Set.of(
                    "chat.send", "player.read", "player.inventory", "player.move", "player.menu",
                    "client.input.register", "client.camera.register",
                    // player.modify e separada de read e de inventory de proposito: escrever vida,
                    // fome, experiencia ou modo de jogo muda as regras sob os pes de quem joga, e
                    // um mod que so quer contar itens nao deveria carregar esse poder junto.
                    "player.modify",
                    "server.read", "server.command.register", "world.read", "world.write",
                    "world.explode", "world.lightning",
                    "entity.read", "entity.spawn", "entity.modify", "world.containers",
                    // Criar especie e mais forte que criar, ler ou modificar uma: acrescenta um
                    // tipo ao registro do jogo, que vale para o mundo inteiro e nao pode ser
                    // desfeito sem reiniciar. So vale na fase de registro.
                    "entity.register",
                    // Instalar outro mod. A mais forte da lista, porque acrescenta codigo ao
                    // servidor -- e por isso a unica que, alem de declarada, exige que o servidor
                    // a libere e que quem age seja operador.
                    "server.install");
            for (String permission : manifest.permissions) {
                require(knownPermissions.contains(permission), "permissão desconhecida: " + permission);
            }
        }
        if (manifest.events != null) {
            for (String event : manifest.events.keySet()) {
                require(EVENTS.contains(event), "evento desconhecido: " + event);
            }
        }
        validateKeybinds(manifest);
        validateCameras(manifest);
        validateCommands(manifest);

        validateDependencies(manifest);
        validateRequirements(manifest);
        validateRegistration(manifest, directory);
        validateItems(manifest);
        validateEntities(manifest);
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
                require(block.placement == null || block.placement.facing == null
                                || FACINGS.contains(
                                        block.placement.facing.toLowerCase(Locale.ROOT)),
                        "placement.facing desconhecido em " + block.id + ": "
                                + block.placement.facing + " (use um de " + FACINGS + ")");
                manifestRemoteBase = manifest.remoteBase;
                validateBehaviorScripts(block, directory);
            }
        }
    }

    /**
     * Confere uma lista de caixas declaradas.
     *
     * <p>Um metodo so para os cinco campos que aceitam caixa. Enquanto a regra vivia dentro do laco
     * de {@code boxes}, os outros campos entravam sem conferencia nenhuma -- e uma caixa invertida
     * desenha do avesso, com as faces apontando para dentro, que e um defeito dificil de ligar a
     * causa.
     */
    private void validarCaixas(String campo, List<List<Float>> caixas) {
        if (caixas == null) return;

        for (List<Float> caixa : caixas) {
            require(caixa != null && caixa.size() == 6,
                    "cada caixa de " + campo + " precisa de seis numeros: x1,y1,z1,x2,y2,z2");
            for (Float valor : caixa) {
                require(valor != null && valor >= 0 && valor <= 16,
                        "coordenada de " + campo + " fora do bloco (0 a 16): " + valor);
            }
            require(caixa.get(0) < caixa.get(3) && caixa.get(1) < caixa.get(4)
                            && caixa.get(2) < caixa.get(5),
                    "caixa invertida em " + campo
                            + ": cada coordenada final precisa ser maior que a inicial");
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
        if (item.food != null) {
            require(item.tool == null && item.armor == null && item.maxDamage == 0,
                    "comida " + item.id + " nao pode ser ferramenta, armadura ou item com durabilidade");
            require(item.food.nutrition >= 0 && item.food.nutrition <= 20,
                    "nutrition de comida em " + item.id + " deve estar entre 0 e 20");
            require(Double.isFinite(item.food.saturation)
                            && item.food.saturation >= 0 && item.food.saturation <= 4,
                    "saturation de comida em " + item.id + " deve estar entre 0 e 4");
        }
        require(item.fuelBurnTime >= 0 && item.fuelBurnTime <= 32767,
                "fuel_burn_time de item deve estar entre 0 e 32767: " + item.id);
        require(item.fuelBurnTime == 0 || (item.tool == null && item.armor == null),
                "combustível " + item.id + " não pode ser ferramenta ou armadura nesta versão do contrato");
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

    private void validateKeybinds(ModManifest manifest) {
        if (manifest.keybinds == null || manifest.keybinds.isEmpty()) return;

        require(manifest.permissions != null
                        && manifest.permissions.contains("client.input.register"),
                "keybinds exigem a permissao client.input.register");
        require(runtimeContract.satisfiesCapability("client.input.keybind", "1.0.0"),
                "o runtime nao entrega a capability client.input.keybind 1.0.0");

        Set<String> ids = new HashSet<>();
        for (ModManifest.KeybindDefinition binding : manifest.keybinds) {
            require(binding != null, "keybind invalida");
            require(binding.id != null && binding.id.matches("^[a-z][a-z0-9_-]{0,31}$"),
                    "id de keybind invalido: " + binding.id);
            require(ids.add(binding.id), "keybind duplicada: " + binding.id);
            require(binding.key != null, "keybind " + binding.id + " nao declara key");
            try {
                KeybindProtocol.validateKey(binding.key);
                KeybindProtocol.normalizeModifiers(binding.modifiers);
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("keybind " + binding.id + ": " + error.getMessage(), error);
            }
            String category = binding.category == null ? "keybinds" : binding.category;
            require(category.matches("^[a-z][a-z0-9_.-]{0,63}$"),
                    "categoria de keybind invalida: " + category);
        }
    }

    private void validateCameras(ModManifest manifest) {
        if (manifest.cameras == null || manifest.cameras.isEmpty()) return;

        require(manifest.permissions != null
                        && manifest.permissions.contains("client.camera.register"),
                "cameras exigem a permissao client.camera.register");
        require(manifest.requires != null
                        && manifest.requires.capabilities != null
                        && manifest.requires.capabilities.containsKey("client.camera.virtual"),
                "cameras exigem requires.capabilities.client.camera.virtual");
        require(runtimeContract.satisfiesCapability("client.camera.virtual", "1.0.0"),
                "o runtime nao entrega a capability client.camera.virtual 1.0.0");

        Set<String> ids = new HashSet<>();
        for (Map.Entry<String, ModManifest.CameraDefinition> entry : manifest.cameras.entrySet()) {
            String id = entry.getKey();
            require(id != null && id.matches("^[a-z][a-z0-9_-]{0,31}$"),
                    "id de câmera inválido: " + id);
            require(ids.add(id), "câmera duplicada no manifesto: " + id);
            ModManifest.CameraDefinition definition = entry.getValue();
            require(definition != null, "câmera inválida: " + id);
            try {
                new CameraProtocol.Camera(manifest.id, id,
                        value(definition.projection, "orthographic"),
                        value(definition.source, "world"),
                        value(definition.anchor, "player"),
                        value(definition.orientation, "north"),
                        definition.resolution, definition.radius,
                        definition.updateTicks, value(definition.output, "texture"));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("câmera " + id + ": " + error.getMessage(), error);
            }
        }
    }

    private static String value(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private void validateCommands(ModManifest manifest) {
        if (manifest.commands == null || manifest.commands.isEmpty()) return;

        require(manifest.permissions != null
                        && manifest.permissions.contains("server.command.register"),
                "commands exigem a permissao server.command.register");
        require(manifest.requires != null
                        && manifest.requires.capabilities != null
                        && manifest.requires.capabilities.containsKey("server.command.schema"),
                "commands exigem requires.capabilities.server.command.schema");
        require(runtimeContract.satisfiesCapability("server.command.schema", "1.0.0"),
                "o runtime nao entrega a capability server.command.schema 1.0.0");

        Set<String> names = new HashSet<>();
        for (Map.Entry<String, ModManifest.CommandDefinition> entry : manifest.commands.entrySet()) {
            String name = entry.getKey();
            require(name != null && name.matches("^[a-z][a-z0-9_-]{0,31}$"),
                    "nome de comando invalido: " + name);
            require(names.add(name), "comando duplicado no manifesto: " + name);
            try {
                CommandSchema.fromManifest(entry.getValue());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("schema do comando " + name
                        + " invalido: " + error.getMessage(), error);
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

    /** Confere que o manifesto exige somente o contrato que este runtime entrega. */
    private void validateRequirements(ModManifest manifest) {
        require(manifest.requires != null, "requires deve ser um objeto");
        require(manifest.requires.domains != null, "requires.domains deve ser um objeto");
        require(manifest.requires.capabilities != null, "requires.capabilities deve ser um objeto");

        if (manifest.requires.domains != null) {
            for (Map.Entry<String, String> entry : manifest.requires.domains.entrySet()) {
                String id = entry.getKey();
                String minimum = entry.getValue();
                require(id != null && DOMAIN_ID.matcher(id).matches(),
                        "id de dominio invalido: " + id);
                requireValidContractVersion(minimum, "dominio " + id);
                require(runtimeContract.satisfiesDomain(id, minimum),
                        "runtime " + runtimeContract.runtimeId() + " nao satisfaz o dominio "
                                + id + " na versao " + minimum);
            }
        }

        if (manifest.requires.capabilities != null) {
            for (Map.Entry<String, String> entry : manifest.requires.capabilities.entrySet()) {
                String id = entry.getKey();
                String minimum = entry.getValue();
                require(id != null && CAPABILITY_ID.matcher(id).matches(),
                        "id de capability invalido: " + id);
                requireValidContractVersion(minimum, "capability " + id);
                require(runtimeContract.satisfiesCapability(id, minimum),
                        "runtime " + runtimeContract.runtimeId() + " nao satisfaz a capability "
                                + id + " na versao " + minimum);
            }
        }
    }

    private void requireValidContractVersion(String version, String subject) {
        require(version != null && CONTRACT_VERSION.matcher(version.trim()).matches(),
                "versao de contrato invalida para " + subject + ": " + version);
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
        if (behavior.onScheduled != null) handlers.put("on_scheduled", behavior.onScheduled);
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

            validarCaixas("shape.boxes", block.shape.boxes);
            validarCaixas("shape.cores", block.shape.cores);
            validarCaixas("shape.arms", block.shape.arms);

            // As duas formas antigas passam pela mesma regra: enquanto so `boxes` era conferido,
            // um `core` com cinco numeros era ignorado em silencio e o bloco saia sem forma.
            if (block.shape.core != null && !block.shape.core.isEmpty()) {
                validarCaixas("shape.core", List.of(block.shape.core));
            }
            if (block.shape.arm != null && !block.shape.arm.isEmpty()) {
                validarCaixas("shape.arm", List.of(block.shape.arm));
            }

            // Braco sem nucleo nao tem em que se apoiar: o bloco seria so bracos soltos no ar.
            require(dev.lualoader.content.BlockShapes.armBoxes(block.shape).isEmpty()
                            || !dev.lualoader.content.BlockShapes.coreBoxes(block.shape).isEmpty(),
                    "shape declara braco e nao declara nucleo em " + block.id);
        }
        if (block.inventory != null) {
            // O teto e o do bau grande, e nao um numero escolhido aqui: a janela do jogo desenha
            // ate seis fileiras de nove, e um inventario maior teria slots que ninguem alcanca.
            require(block.inventory.size >= 1 && block.inventory.size <= 54,
                    "inventory.size deve estar entre 1 e 54");
            // A regra das fileiras vale para as janelas do jogo, que desenham nove por linha. Com
            // layout declarado ela deixa de valer: o manifesto diz onde cada slot fica, e uma
            // maquina com dez slots -- nove de padrao e um de saida -- e exatamente o caso que a
            // janela declarada existe para atender.
            require(block.inventory.layout != null || block.inventory.size % 9 == 0,
                    "inventory.size deve ser multiplo de 9, para fechar as fileiras da janela"
                            + " -- ou declare inventory.layout e diga onde cada slot fica");

            String janela = block.inventory.window == null ? "rows" : block.inventory.window.trim();
            require(janela.equals("rows") || janela.equals("3x3"),
                    "inventory.window aceita \"rows\" ou \"3x3\", recebido: " + janela);
            // A janela 3x3 e a do dispenser, e ela tem exatamente nove slots. Aceitar outro tamanho
            // ali daria uma tela com slots que ninguem alcanca -- o mesmo defeito do teto acima.
            require(!janela.equals("3x3") || block.inventory.size == 9,
                    "inventory.window 3x3 exige inventory.size 9, recebido: " + block.inventory.size);

            ModManifest.LayoutDefinition layout = block.inventory.layout;
            if (layout != null) {
                require(layout.width >= 32 && layout.width <= 512,
                        "inventory.layout.width deve estar entre 32 e 512");
                require(layout.height >= 32 && layout.height <= 512,
                        "inventory.layout.height deve estar entre 32 e 512");

                // Uma posicao por slot, nem mais nem menos. Faltando, o slot existe e nao aparece --
                // e o jogador procura por ele; sobrando, a posicao aponta para um slot que nao
                // existe, e o clique cai no vazio. Os dois sao silenciosos.
                require(layout.slots != null && layout.slots.size() == block.inventory.size,
                        "inventory.layout.slots precisa de uma posicao por slot: "
                                + block.inventory.size + " slots, "
                                + (layout.slots == null ? 0 : layout.slots.size()) + " posicoes");

                for (ModManifest.SlotDefinition slot : layout.slots) {
                    require(slot != null, "posicao de slot vazia em inventory.layout.slots");
                    require(slot.x >= 0 && slot.x + 18 <= layout.width
                                    && slot.y >= 0 && slot.y + 18 <= layout.height,
                            "slot em " + slot.x + "," + slot.y + " cai fora da janela de "
                                    + layout.width + "x" + layout.height);
                }

                Set<String> botoes = new HashSet<>();
                for (ModManifest.ButtonDefinition botao : layout.buttons) {
                    require(botao != null && botao.id != null && !botao.id.isBlank(),
                            "botao de janela sem id: o clique nao teria como ser identificado");
                    require(botoes.add(botao.id),
                            "botao duplicado em inventory.layout.buttons: " + botao.id);
                }
            }
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

    /**
     * Confere as espécies declaradas pelo mod.
     *
     * <p>Tudo aqui é recusa na carga, e não conserto silencioso. Uma espécie mal declarada que
     * chega ao registro vira um bicho invisível, sem colisão ou que nunca nasce, e nenhum desses
     * três se parece com erro de manifesto para quem escreveu o mod.
     */
    private void validateEntities(ModManifest manifest) {
        if (manifest.entities == null) return;
        Set<String> entityIds = new HashSet<>();

        for (EntityDefinition entity : manifest.entities) {
            require(entity != null && entity.id != null && MOD_ID.matcher(entity.id).matches(),
                    "id de entidade inválido");
            require(entity.name != null && !entity.name.isBlank(),
                    "name de entidade é obrigatório: " + entity.id);
            require(entityIds.add(entity.id), "entidade duplicada no mod: " + entity.id);

            // A base carrega modelo, animação e comportamento. Sem ela não há o que registrar.
            require(entity.base != null && entity.base.indexOf(':') > 0,
                    "entidade " + entity.id + " precisa de uma base do jogo, como minecraft:zombie");

            require(entity.category == null
                            || ENTITY_CATEGORIES.contains(entity.category.toLowerCase(Locale.ROOT)),
                    "category de entidade desconhecida em " + entity.id + ": " + entity.category
                            + " (use uma de " + ENTITY_CATEGORIES + ")");

            // O jogo não representa entidade maior que um pedaço de mundo.
            require(entity.width >= 0 && entity.width <= 16,
                    "width de " + entity.id + " deve estar entre 0 e 16");
            require(entity.height >= 0 && entity.height <= 16,
                    "height de " + entity.id + " deve estar entre 0 e 16");
            require(entity.trackingRange >= 0 && entity.trackingRange <= 32,
                    "tracking_range de " + entity.id + " deve estar entre 0 e 32");
            require(entity.updateInterval >= 0,
                    "update_interval de " + entity.id + " não pode ser negativo");

            validateEntityDefaults(entity);
            validateEntityLoot(entity);
            validateSpawnEgg(entity);
            validateNaturalSpawn(entity);
            validateAi(entity);
        }
    }

    /** Os valores de nascimento da espécie, no mesmo vocabulário de {@code spawn_entity}. */
    private void validateEntityDefaults(EntityDefinition entity) {
        EntitySpec defaults = entity.defaults;
        if (defaults == null) return;

        require(defaults.health == null || defaults.health > 0,
                "health de " + entity.id + " precisa ser maior que zero");

        for (var attribute : defaults.attributesOrEmpty().entrySet()) {
            require(attribute.getKey() != null && attribute.getKey().indexOf(':') > 0,
                    "atributo de " + entity.id + " precisa de um id do jogo: " + attribute.getKey());
        }
        for (EntitySpec.EffectSpec effect : defaults.effectsOrEmpty()) {
            require(effect != null && effect.id != null && effect.id.indexOf(':') > 0,
                    "efeito de " + entity.id + " precisa de um id do jogo");
            require(effect.duration == null || effect.duration > 0,
                    "duration de efeito em " + entity.id + " precisa ser maior que zero");
            require(effect.amplifier == null || effect.amplifier >= 0,
                    "amplifier de efeito em " + entity.id + " não pode ser negativo");
        }
        for (var slot : defaults.equipmentOrEmpty().entrySet()) {
            var piece = slot.getValue();
            require(piece != null && piece.item != null && piece.item.indexOf(':') > 0,
                    "equipamento de " + entity.id + " precisa de um id de item do jogo em "
                            + slot.getKey());
            require(piece.dropChance == null || (piece.dropChance >= 0 && piece.dropChance <= 1),
                    "drop_chance de " + entity.id + " deve estar entre 0 e 1");
        }
    }

    private void validateEntityLoot(EntityDefinition entity) {
        var loot = entity.loot;
        if (loot == null) return;

        require(loot.table == null || loot.table.indexOf(':') > 0,
                "table de saque de " + entity.id + " precisa de namespace: " + loot.table);

        for (EntityDefinition.EntityDropDefinition drop : loot.drops) {
            require(drop != null && drop.item != null && drop.item.indexOf(':') > 0,
                    "drop de " + entity.id + " precisa de um id de item com namespace");
            require(drop.min >= 0, "min de drop em " + entity.id + " não pode ser negativo");
            require(drop.max >= drop.min,
                    "max de drop em " + entity.id + " não pode ser menor que min");
            require(drop.chance > 0 && drop.chance <= 1,
                    "chance de drop em " + entity.id + " deve estar entre 0 (exclusivo) e 1");
        }
    }

    private void validateSpawnEgg(EntityDefinition entity) {
        var egg = entity.spawnEgg;
        if (egg == null || !egg.register) return;

        require(egg.id == null || MOD_ID.matcher(egg.id).matches(),
                "id de ovo de criação inválido em " + entity.id + ": " + egg.id);
        require(isColor(egg.primaryColor),
                "primary_color de " + entity.id + " deve estar entre 0x000000 e 0xffffff");
        require(isColor(egg.secondaryColor),
                "secondary_color de " + entity.id + " deve estar entre 0x000000 e 0xffffff");
    }

    private static boolean isColor(int value) {
        return value >= 0 && value <= 0xffffff;
    }

    /**
     * Confere os scripts da fase de registro.
     *
     * <p>O arquivo tem que existir agora, e nao na hora de executar: esta fase roda antes de o jogo
     * congelar os registros, e um erro ali chega no meio da carga do Minecraft, onde ninguem esta
     * olhando. Recusar na descoberta poe a mensagem onde ela e lida.
     */
    private void validateRegistration(ModManifest manifest, Path directory) {
        if (manifest.registration == null || manifest.registration.isEmpty()) return;

        for (Map.Entry<String, String> entry : manifest.registration.entrySet()) {
            String event = entry.getKey();
            require(LoaderEvents.REGISTRATION.contains(event),
                    "evento de registro desconhecido: " + event
                            + " (use um de " + LoaderEvents.REGISTRATION + ")");

            String script = entry.getValue();
            require(script != null && !script.isBlank(),
                    "script de registro vazio em " + event);

            // URL segue a mesma regra do behavior de bloco: e execucao de codigo baixado, e quem
            // controla o endereco decide o que roda. O aviso e a trava por hash vivem no runtime.
            String lower = script.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) continue;

            require(LUA_FILE.matcher(script).matches(),
                    "script de registro invalido em " + event + ": " + script);

            Path file = directory.resolve(script).toAbsolutePath().normalize();
            // Dentro da pasta do mod: um caminho com ".." leria arquivo de fora, e esta fase roda
            // com o jogo carregando, sem ninguem para notar.
            require(file.startsWith(directory.toAbsolutePath().normalize()),
                    "script de registro fora da pasta do mod: " + script);

            // Ausente e so erro quando nao ha de onde buscar: um mod publicado na web declara
            // caminhos relativos que so existem la, e a base do manifesto e o que os resolve.
            require(java.nio.file.Files.isRegularFile(file)
                            || (manifest.remoteBase != null && !manifest.remoteBase.isBlank()),
                    "script de registro nao encontrado: " + script);
        }

        // Registrar conteudo e mais forte que usar o que ja existe, e por isso e permissao propria.
        require(manifest.permissions != null
                        && manifest.permissions.contains("entity.register"),
                "a fase de registro exige a permissao entity.register");
    }

    /**
     * Confere a regra de nascimento natural.
     *
     * <p>Tudo aqui recusa uma regra que **nunca dispara**, e esse é o ponto: uma faixa de luz
     * invertida ou um grupo de tamanho zero não dão erro no jogo — a criatura simplesmente não
     * nasce, e quem escreveu o mod conclui que o loader não implementa spawn natural.
     */
    private void validateNaturalSpawn(EntityDefinition entity) {
        var spawn = entity.spawn;
        if (spawn == null) return;

        require(spawn.biomes != null && !spawn.biomes.isEmpty(),
                "spawn de " + entity.id + " precisa de ao menos um bioma;"
                        + " sem isso a especie nao nasceria em lugar nenhum");

        for (String biome : spawn.biomes) {
            require(biome != null && !biome.isBlank(), "bioma vazio em " + entity.id);
            // Uma tag comeca por "#"; o namespace vale para os dois casos.
            String bare = biome.startsWith("#") ? biome.substring(1) : biome;
            require(bare.indexOf(':') > 0,
                    "bioma de " + entity.id + " precisa de namespace: " + biome);
        }

        require(spawn.weight > 0,
                "weight de " + entity.id + " precisa ser maior que zero;"
                        + " peso zero nunca e sorteado");
        require(spawn.minGroup >= 1,
                "min_group de " + entity.id + " precisa ser ao menos 1");
        require(spawn.maxGroup >= spawn.minGroup,
                "max_group de " + entity.id + " nao pode ser menor que min_group");

        require(spawn.minLight >= 0 && spawn.minLight <= 15,
                "min_light de " + entity.id + " deve estar entre 0 e 15");
        require(spawn.maxLight >= 0 && spawn.maxLight <= 15,
                "max_light de " + entity.id + " deve estar entre 0 e 15");
        require(spawn.maxLight >= spawn.minLight,
                "max_light de " + entity.id + " nao pode ser menor que min_light;"
                        + " a faixa ficaria vazia e a especie nunca nasceria");

        require(spawn.minY == null || spawn.maxY == null || spawn.maxY >= spawn.minY,
                "max_y de " + entity.id + " nao pode ser menor que min_y");
    }

    /**
     * Confere o comportamento declarado.
     *
     * <p>Uma meta de tipo desconhecido é recusada em vez de ignorada: ignorar daria uma criatura
     * que simplesmente não faz o que o manifesto diz que ela faz, e a mensagem lista o vocabulário
     * para quem escreveu não ter que adivinhar o nome certo.
     */
    private void validateAi(EntityDefinition entity) {
        var ai = entity.ai;
        if (ai == null) return;

        if (ai.goals != null) {
            for (dev.lualoader.content.EntityAi.Goal goal : ai.goals) {
                require(goal != null, "meta vazia em " + entity.id);
                String type = dev.lualoader.content.EntityAi.normalized(goal.type);
                require(dev.lualoader.content.EntityAi.GOAL_TYPES.contains(type),
                        "meta desconhecida em " + entity.id + ": " + goal.type
                                + " (use uma de " + dev.lualoader.content.EntityAi.GOAL_TYPES + ")");

                // Uma meta de fugir sem de quem fugir nunca dispara, e nao da erro no jogo.
                require(!dev.lualoader.content.EntityAi.goalNeedsEntity(type)
                                || (goal.entity != null && goal.entity.indexOf(':') > 0),
                        "a meta " + type + " de " + entity.id
                                + " precisa de entity com namespace, de quem fugir");
                require(!dev.lualoader.content.EntityAi.goalNeedsItems(type)
                                || (goal.items != null && !goal.items.isEmpty()),
                        "a meta " + type + " de " + entity.id
                                + " precisa de ao menos um item que atraia a criatura");

                for (String item : goal.items == null ? List.<String>of() : goal.items) {
                    require(item != null && item.indexOf(':') > 0,
                            "item de " + entity.id + " precisa de namespace: " + item);
                }
                require(goal.speed > 0, "speed de uma meta de " + entity.id
                        + " precisa ser maior que zero; zero deixaria a criatura parada");
                require(goal.range > 0, "range de uma meta de " + entity.id
                        + " precisa ser maior que zero");
                require(goal.priority == null || goal.priority >= 0,
                        "priority de uma meta de " + entity.id + " nao pode ser negativa");
            }
        }

        if (ai.targets != null) {
            for (dev.lualoader.content.EntityAi.Target target : ai.targets) {
                require(target != null, "alvo vazio em " + entity.id);
                String type = dev.lualoader.content.EntityAi.normalized(target.type);
                require(dev.lualoader.content.EntityAi.TARGET_TYPES.contains(type),
                        "alvo desconhecido em " + entity.id + ": " + target.type
                                + " (use um de "
                                + dev.lualoader.content.EntityAi.TARGET_TYPES + ")");
                require(!dev.lualoader.content.EntityAi.targetNeedsEntity(type)
                                || (target.entity != null && target.entity.indexOf(':') > 0),
                        "o alvo " + type + " de " + entity.id
                                + " precisa de entity com namespace, quem cacar");
                require(target.priority == null || target.priority >= 0,
                        "priority de um alvo de " + entity.id + " nao pode ser negativa");
            }
        }

        // Limpar as metas da base e nao declarar nenhuma deixa a criatura parada para sempre. E
        // legitimo -- uma estatua -- mas quase sempre e engano, entao vale dizer.
        if (ai.clear && (ai.goals == null || ai.goals.isEmpty())) {
            logger.warn("Especie {} limpa a IA da base e nao declara meta nenhuma;"
                    + " ela ficara parada", entity.id);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    public record LoadedMod(Path directory, ModManifest manifest) {
    }
}

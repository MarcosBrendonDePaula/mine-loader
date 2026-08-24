package dev.lualoader.lua;

import dev.lualoader.manifest.LoaderEvents;
import dev.lualoader.manifest.ManifestImports;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import dev.lualoader.platform.EntityDefinition;
import dev.lualoader.platform.EntitySpec;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executa os scripts da fase de registro — antes de o jogo congelar os registros.
 *
 * <p><b>Por que existe.</b> O Lua do adaptador Fabric carrega na inicialização do mod, e o do
 * NeoForge quando o servidor sobe. Registrar conteúdo por script funcionaria só no primeiro: no
 * segundo o registro já fechou, e a criatura não entraria em lugar nenhum. Uma operação que vale
 * numa plataforma e não na outra quebra a promessa central do projeto — o mod passa nos testes de
 * quem o escreveu e some para metade de quem o usa.
 *
 * <p>Esta classe é o momento que faltava ao NeoForge. Cada adaptador a chama onde ainda pode
 * registrar, e a partir daí os dois respondem igual.
 *
 * <p><b>É uma fase, e não um evento comum.</b> Aqui não há servidor, jogador nem bloco para tocar:
 * o mundo ainda não existe. O que se alcança é só acrescentar conteúdo, e é por isso que o contexto
 * é pequeno de propósito — oferecer o resto seria oferecer chamadas que só podem falhar.
 *
 * <p>O script é um arquivo próprio, declarado em {@code registration}, e não uma função do
 * entrypoint. Carregar o {@code main.lua} aqui faria o topo dele executar duas vezes: toda linha
 * fora de função rodaria de novo mais tarde, e esse defeito não dá erro — só estado errado.
 */
public final class RegistrationRuntime {
    /**
     * Teto de tempo da fase, em milissegundos.
     *
     * <p>Mais folgado que os 20 ms de um callback, e por um motivo: um callback roda a cada tique e
     * um atraso ali aparece como travada no jogo; esta fase roda uma vez, com o jogo ainda
     * carregando, e montar um bestiario de centenas de especies e trabalho legitimo.
     */
    private static final long REGISTRATION_LIMIT_MILLIS = 5_000;

    private final Logger logger;
    private final Path remoteCache;

    /** Os ids ja declarados quando o script corrente comecou, por JSON ou por outro script. */
    private final List<String> declaredSoFar = new ArrayList<>();

    /**
     * @param remoteCache onde guardar script buscado por URL, ou {@code null} para recusar remoto
     */
    public RegistrationRuntime(Logger logger, Path remoteCache) {
        this.logger = logger;
        this.remoteCache = remoteCache;
    }

    /** Quantas espécies cada mod registrou nesta fase, para o adaptador reportar. */
    private final Map<String, Integer> registeredByMod = new LinkedHashMap<>();

    /**
     * Roda a fase de registro de todos os mods.
     *
     * <p>Um mod que falha aqui <b>não leva os outros junto</b>: o erro é registrado com o nome de
     * quem o causou, e o resto do conteúdo continua entrando. Um bestiário torto derrubar a carga
     * inteira transformaria um mod quebrado em "o jogo não abre".
     */
    public void runAll(List<ModLoader.LoadedMod> mods) {
        for (ModLoader.LoadedMod mod : mods) {
            for (EntityDefinition declared : mod.manifest().entities) {
                declaredSoFar.add(mod.manifest().id + ":" + declared.id);
            }
        }

        for (ModLoader.LoadedMod mod : mods) {
            Map<String, String> scripts = mod.manifest().registration;
            if (scripts == null || scripts.isEmpty()) continue;

            for (Map.Entry<String, String> entry : scripts.entrySet()) {
                if (!LoaderEvents.REGISTRATION.contains(entry.getKey())) continue;
                try {
                    run(mod, entry.getValue());
                } catch (IOException | RuntimeException error) {
                    logger.error("Falha na fase de registro do mod {} ({}): {}",
                            mod.manifest().id, entry.getValue(), error.getMessage());
                }
            }
        }
    }

    private void run(ModLoader.LoadedMod mod, String reference) throws IOException {
        // Um laco infinito aqui travaria a carga do jogo, sem tela, sem log e sem nada que
        // explique -- entao o orcamento vale nesta fase tambem. Ele e mais folgado que o de um
        // callback: montar um bestiario inteiro e trabalho legitimo, e acontece uma vez so.
        ExecutionBudget budget = new ExecutionBudget(REGISTRATION_LIMIT_MILLIS);
        Globals globals = LuaRuntime.restrictedGlobals(budget);

        String source = readScript(mod, reference);
        budget.start();
        try {
            LuaValue chunk = globals.load(source, mod.manifest().id + "/" + reference);
            LuaValue returned = chunk.call();
            if (!returned.isfunction()) {
                throw new IOException("script de registro precisa devolver uma funcao: "
                        + reference);
            }
            returned.call(contextFor(mod));
        } catch (LuaError error) {
            throw new IOException("erro no script de registro " + reference + ": "
                    + error.getMessage(), error);
        } finally {
            budget.stop();
        }

        int count = registeredByMod.getOrDefault(mod.manifest().id, 0);
        if (count > 0) {
            logger.info("Mod {} registrou {} especie(s) na fase de registro",
                    mod.manifest().id, count);
        }
    }

    /**
     * Lê o script, de disco ou da web.
     *
     * <p>As mesmas três origens que o comportamento de um bloco aceita, e pelo mesmo motivo: um mod
     * publicado na web declara caminhos relativos que só existem lá. Código baixado é dito em voz
     * alta no log — a origem do que roda no servidor nunca deve ser invisível a quem o administra.
     */
    private String readScript(ModLoader.LoadedMod mod, String reference) throws IOException {
        String lower = reference.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            if (remoteCache == null) {
                throw new IOException("script de registro remoto desabilitado: " + reference);
            }
            String hash = mod.manifest().registrationSha256;
            byte[] bytes = new ManifestImports(mod.directory(), remoteCache)
                    .fetchRemote(reference, hash);

            logger.warn("Mod {} executa codigo remoto na fase de registro, de {}{}",
                    mod.manifest().id, reference,
                    hash == null || hash.isBlank() ? " (sem hash fixo)" : " (fixado por hash)");
            return new String(bytes, StandardCharsets.UTF_8);
        }

        Path root = mod.directory().toAbsolutePath().normalize();
        Path script = root.resolve(reference).normalize();
        if (!script.startsWith(root)) {
            throw new IOException("script de registro sai da pasta do mod: " + reference);
        }
        if (Files.isRegularFile(script)) {
            return Files.readString(script, StandardCharsets.UTF_8);
        }

        if (remoteCache == null) {
            throw new IOException("script de registro nao encontrado: " + reference);
        }
        byte[] bytes = new ManifestImports(mod.directory(), remoteCache)
                .withRemoteBase(mod.manifest().remoteBase)
                .readRelative(reference);
        if (bytes == null) {
            throw new IOException("script de registro nao encontrado: " + reference);
        }
        logger.info("Script de registro {} do mod {} veio da base remota",
                reference, mod.manifest().id);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * O contexto da fase: pequeno, e de propósito.
     *
     * <p>{@code register} é um espaço que cresce — hoje entidade, amanhã o que mais o loader souber
     * registrar. Ele é separado de {@code server} porque não é o mesmo tipo de operação: uma
     * acrescenta ao jogo antes de ele existir, a outra age no mundo depois. Misturar os dois faria
     * um mod tentar tocar um bloco numa fase em que não há mundo.
     */
    private LuaTable contextFor(ModLoader.LoadedMod mod) {
        ModManifest manifest = mod.manifest();

        LuaTable log = new LuaTable();
        log.set("info", logFunction(manifest.id, false));
        log.set("warn", logFunction(manifest.id, true));

        LuaTable register = new LuaTable();
        register.set("entity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                requireRegisterPermission(manifest);
                EntityDefinition definition = readDefinition(manifest.id, args.arg(1));

                // Acrescentado ao manifesto em memoria, e nao registrado aqui. Parece indireto e e
                // o contrario: dali em diante a especie e indistinguivel de uma declarada em JSON,
                // e ganha de graca a ordenacao por heranca, a tabela de saque, a traducao, a
                // textura e o modelo do ovo. Registrar direto pulava o montador de recursos, e o
                // sintoma era o ovo sem icone -- que nenhum teste de servidor pega.
                for (EntityDefinition existing : manifest.entities) {
                    if (definition.id.equals(existing.id)) {
                        throw new LuaError("especie ja declarada neste mod: " + definition.id);
                    }
                }
                manifest.entities.add(definition);
                registeredByMod.merge(manifest.id, 1, Integer::sum);
                return LuaValue.TRUE;
            }
        });
        // A leitura da mesma fase: um mod que registra em cima do bestiario de outro precisa saber
        // o que ja existe. Sao os manifestos, e nao o registro do jogo: nesta fase nada foi
        // registrado ainda, nem o que veio de JSON.
        register.set("declared", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaTable list = new LuaTable();
                int index = 1;
                for (String id : declaredSoFar) {
                    list.set(index++, LuaValue.valueOf(id));
                }
                return list;
            }
        });

        LuaTable ctx = new LuaTable();
        ctx.set("log", log);
        ctx.set("register", register);
        ctx.set("mod_id", LuaValue.valueOf(manifest.id));
        return ctx;
    }

    private static void requireRegisterPermission(ModManifest manifest) {
        if (manifest.permissions == null || !manifest.permissions.contains("entity.register")) {
            throw new LuaError("permissão ausente: entity.register");
        }
    }

    /**
     * Traduz a tabela Lua para uma espécie declarada.
     *
     * <p>Reusa {@link LuaRuntime#readEntitySpec} para os padrões: é o mesmo vocabulário que
     * {@code spawn_entity} aceita, e duas leituras separadas divergiriam no primeiro campo novo.
     */
    private EntityDefinition readDefinition(String modId, LuaValue value) {
        if (!value.istable()) {
            throw new LuaError("register.entity espera uma tabela");
        }

        EntityDefinition definition = new EntityDefinition();
        definition.id = text(value, "id");
        definition.name = text(value, "name");
        definition.base = text(value, "base");

        if (definition.id == null || definition.id.isBlank()) {
            throw new LuaError("register.entity precisa de um id");
        }
        if (definition.base == null || definition.base.isBlank()) {
            // A base carrega modelo, animacao e comportamento. Sem ela o registro produziria uma
            // criatura invisivel e parada, que nao se parece com erro de script.
            throw new LuaError("register.entity precisa de uma base, como minecraft:zombie");
        }
        if (definition.name == null) definition.name = definition.id;

        definition.category = text(value, "category");
        definition.width = (float) number(value, "width", 0);
        definition.height = (float) number(value, "height", 0);
        definition.trackingRange = (int) number(value, "tracking_range", 0);
        definition.updateInterval = (int) number(value, "update_interval", 0);
        definition.fireImmune = flag(value, "fire_immune", false);
        definition.summonable = flag(value, "summonable", true);
        definition.saveable = flag(value, "saveable", true);

        LuaValue defaults = value.get("defaults");
        if (defaults.istable()) {
            EntitySpec spec = LuaRuntime.readEntitySpec(defaults);
            definition.defaults = spec == EntitySpec.EMPTY ? null : spec;
        }

        // Aparencia declarada por script vale igual a declarada em JSON. Sem estes dois, uma
        // especie gerada por laco nascia condenada a parecer a base, e o script viraria um caminho
        // de segunda classe -- exatamente o que a fase de registro existe para nao ser.
        definition.texture = text(value, "texture");
        definition.model = text(value, "model");

        readSpawn(definition, value.get("spawn"));

        LuaValue tags = value.get("tags");
        if (tags.istable()) {
            List<String> list = new ArrayList<>();
            for (int index = 1; index <= tags.length(); index++) {
                list.add(tags.get(index).tojstring());
            }
            definition.tags = list;
        }

        readLoot(definition, value.get("loot"));
        readSpawnEgg(definition, value.get("spawn_egg"));
        return definition;
    }

    /**
     * A regra de nascimento natural, quando o script declara uma.
     *
     * <p>Ausente deixa {@code spawn} nulo, e a especie so chega ao mundo por comando, ovo ou
     * script. E o padrao certo: um mod nao deveria comecar a povoar o mundo de quem instalou por
     * causa de um campo esquecido.
     */
    private static void readSpawn(EntityDefinition definition, LuaValue spawn) {
        if (!spawn.istable()) return;

        var declared = new EntityDefinition.SpawnDefinition();
        LuaValue biomes = spawn.get("biomes");
        if (biomes.istable()) {
            List<String> list = new ArrayList<>();
            for (int index = 1; index <= biomes.length(); index++) {
                list.add(biomes.get(index).tojstring());
            }
            declared.biomes = list;
        }

        declared.weight = (int) number(spawn, "weight", 10);
        declared.minGroup = (int) number(spawn, "min_group", 1);
        declared.maxGroup = (int) number(spawn, "max_group", 4);
        declared.minLight = (int) number(spawn, "min_light", 0);
        declared.maxLight = (int) number(spawn, "max_light", 15);

        // Nulo e "a faixa do mundo", e nao zero: um limite zerado prenderia a criatura ao fundo do
        // mundo, onde ela nunca nasceria.
        if (!spawn.get("min_y").isnil()) declared.minY = spawn.get("min_y").toint();
        if (!spawn.get("max_y").isnil()) declared.maxY = spawn.get("max_y").toint();

        definition.spawn = declared;
    }

    private static void readLoot(EntityDefinition definition, LuaValue loot) {
        if (!loot.istable()) return;

        var declared = new EntityDefinition.EntityLootDefinition();
        declared.table = text(loot, "table");

        LuaValue drops = loot.get("drops");
        if (drops.istable()) {
            for (int index = 1; index <= drops.length(); index++) {
                LuaValue entry = drops.get(index);
                if (!entry.istable()) continue;

                var drop = new EntityDefinition.EntityDropDefinition();
                drop.item = text(entry, "item");
                drop.min = (int) number(entry, "min", 1);
                drop.max = (int) number(entry, "max", drop.min);
                drop.chance = (float) number(entry, "chance", 1.0);
                drop.requiresPlayerKill = flag(entry, "requires_player_kill", false);
                declared.drops.add(drop);
            }
        }
        definition.loot = declared;
    }

    private static void readSpawnEgg(EntityDefinition definition, LuaValue egg) {
        if (!egg.istable()) return;

        var declared = new EntityDefinition.SpawnEggDefinition();
        declared.register = flag(egg, "register", true);
        declared.id = text(egg, "id");
        declared.name = text(egg, "name");
        declared.primaryColor = (int) number(egg, "primary_color", 0x8f8f8f);
        declared.secondaryColor = (int) number(egg, "secondary_color", 0x3f3f3f);
        definition.spawnEgg = declared;
    }

    private static String text(LuaValue table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.tojstring();
    }

    private static double number(LuaValue table, String key, double fallback) {
        LuaValue value = table.get(key);
        return value.isnumber() ? value.todouble() : fallback;
    }

    private static boolean flag(LuaValue table, String key, boolean fallback) {
        LuaValue value = table.get(key);
        return value.isnil() ? fallback : value.toboolean();
    }

    private OneArgFunction logFunction(String modId, boolean warning) {
        return new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue value) {
                if (warning) logger.warn("[{}] {}", modId, value.tojstring());
                else logger.info("[{}] {}", modId, value.tojstring());
                return LuaValue.NIL;
            }
        };
    }
}

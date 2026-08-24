package dev.lualoader.install;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Busca as dependências que os mods instalados declararam e que não estão presentes.
 *
 * <p>Sem isto, instalar um mod que depende de uma biblioteca é um trabalho em duas etapas para quem
 * joga: o mod entra, o log diz "exige X, que não foi encontrado", e alguém precisa descobrir o que
 * é X e onde achar. O mod já sabia as duas coisas — só não tinha como dizer.
 *
 * <p>Roda antes da carga e é intencionalmente conservador:
 *
 * <ul>
 *   <li><b>Só quando a chave está ligada.</b> Instalar dependência é instalar código que quem
 *       administra não escolheu, e isso é decisão do servidor.</li>
 *   <li><b>Só o que foi declarado.</b> Um mod só busca o que ele mesmo listou em
 *       {@code dependency_sources}; não há descoberta automática nem repositório central.</li>
 *   <li><b>Em profundidade limitada.</b> Uma dependência pode trazer as suas, mas o número de
 *       rodadas é fixo: sem isso, uma cadeia mal declarada instalaria em laço.</li>
 * </ul>
 */
public final class DependencyInstaller {
    /** Quantas rodadas de busca, para uma dependência poder trazer as suas sem virar laço. */
    public static final int MAX_ROUNDS = 4;

    private final Logger logger;
    private final ModInstaller installer;
    private final InstallPolicy policy;

    public DependencyInstaller(Logger logger, ModInstaller installer, InstallPolicy policy) {
        this.logger = logger;
        this.installer = installer;
        this.policy = policy;
    }

    /** O que faltava e o que foi resolvido, para quem chamou poder relatar. */
    public record Result(List<String> installed, List<String> failed, List<String> missingSource) {
        public boolean changedAnything() {
            return !installed.isEmpty();
        }
    }

    /**
     * Instala o que falta e devolve o resultado.
     *
     * <p>Quem chama precisa redescobrir os mods quando {@link Result#changedAnything()} for
     * verdadeiro: os manifestos novos só existem em disco, e a lista em memória é a de antes.
     */
    public Result resolve(List<ModLoader.LoadedMod> discovered) {
        List<String> installed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> missingSource = new ArrayList<>();

        if (!policy.autoInstallDependencies()) {
            // A chave desligada nao e silencio: quem esta com uma dependencia faltando precisa
            // saber que havia um caminho, e que ele esta fechado por escolha.
            for (String missing : missingIds(discovered)) {
                missingSource.add(missing);
            }
            if (!missingSource.isEmpty()) {
                logger.info("Dependencias ausentes ({}): a instalacao automatica esta desligada",
                        String.join(", ", missingSource));
            }
            return new Result(installed, failed, missingSource);
        }

        List<ModLoader.LoadedMod> current = discovered;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            Set<String> present = new LinkedHashSet<>();
            for (ModLoader.LoadedMod mod : current) present.add(mod.manifest().id);

            Map<String, String> pending = new java.util.LinkedHashMap<>();
            for (ModLoader.LoadedMod mod : current) {
                ModManifest manifest = mod.manifest();
                if (manifest.dependencies == null) continue;

                for (String required : manifest.dependencies.keySet()) {
                    if (present.contains(required)) continue;
                    if (installed.contains(required) || failed.contains(required)) continue;

                    String source = manifest.dependencySources == null
                            ? null
                            : manifest.dependencySources.get(required);

                    if (source == null || source.isBlank()) {
                        if (!missingSource.contains(required)) missingSource.add(required);
                        continue;
                    }
                    pending.put(required, source);
                }
            }

            if (pending.isEmpty()) break;

            for (var entry : pending.entrySet()) {
                if (install(entry.getKey(), entry.getValue())) installed.add(entry.getKey());
                else failed.add(entry.getKey());
            }

            // A rodada seguinte precisa enxergar o que acabou de entrar, inclusive as dependencias
            // que o proprio recem-instalado declara.
            current = rediscover(current);
        }

        return new Result(installed, failed, missingSource);
    }

    private boolean install(String modId, String source) {
        try {
            ModInstaller.Preview preview = installer.preview(source);

            // O manifesto baixado precisa ser o mod que foi pedido: um endereco que devolva outra
            // coisa instalaria um mod pelo nome de outro, e a dependencia continuaria faltando.
            if (!modId.equals(preview.id())) {
                logger.error("A origem de {} entregou o mod {}; nada foi instalado",
                        modId, preview.id());
                return false;
            }

            installer.install(preview);
            logger.info("Dependencia {} v{} instalada automaticamente ({} permissao(oes))",
                    preview.id(), preview.version(), preview.permissions().size());
            return true;
        } catch (IOException | RuntimeException error) {
            logger.error("Falha ao instalar a dependencia {}: {}", modId, error.getMessage());
            return false;
        }
    }

    /** Os ids exigidos que ninguem forneceu. */
    private static List<String> missingIds(List<ModLoader.LoadedMod> mods) {
        Set<String> present = new LinkedHashSet<>();
        for (ModLoader.LoadedMod mod : mods) present.add(mod.manifest().id);

        List<String> missing = new ArrayList<>();
        for (ModLoader.LoadedMod mod : mods) {
            if (mod.manifest().dependencies == null) continue;
            for (String required : mod.manifest().dependencies.keySet()) {
                if (!present.contains(required) && !missing.contains(required)) {
                    missing.add(required);
                }
            }
        }
        return missing;
    }

    private List<ModLoader.LoadedMod> rediscover(List<ModLoader.LoadedMod> fallback) {
        if (fallback.isEmpty()) return fallback;

        // A pasta de mods e a mae do diretorio de qualquer mod ja descoberto.
        java.nio.file.Path root = fallback.get(0).directory().getParent();
        if (root == null) return fallback;

        try {
            return new ModLoader(logger).discover(root);
        } catch (IOException error) {
            logger.error("Falha ao reler os mods apos instalar dependencia: {}", error.getMessage());
            return fallback;
        }
    }
}

package dev.lualoader.manifest;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolve a ordem de carga a partir das dependências declaradas.
 *
 * <p>Antes disso os mods carregavam em ordem alfabética de diretório, o que funcionava por acaso.
 * Assim que um mod passa a usar outro como biblioteca, a ordem deixa de ser detalhe: a biblioteca
 * precisa estar pronta antes de quem a consome, senão {@code mod.require} encontraria nada e a
 * causa da falha seria invisível.
 *
 * <p>Um mod cuja dependência esteja ausente, em versão insuficiente ou em ciclo é descartado, e os
 * demais continuam carregando.
 */
public final class ModDependencies {
    private final Logger logger;

    public ModDependencies(Logger logger) {
        this.logger = logger;
    }

    /**
     * Ordena os mods de modo que toda dependência apareça antes de quem depende dela.
     *
     * @return lista ordenada, sem os mods que não puderam ser satisfeitos
     */
    public List<ModLoader.LoadedMod> resolve(List<ModLoader.LoadedMod> mods) {
        Map<String, ModLoader.LoadedMod> byId = new HashMap<>();
        for (ModLoader.LoadedMod mod : mods) byId.put(mod.manifest().id, mod);

        List<ModLoader.LoadedMod> ordered = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        Set<String> rejected = new HashSet<>();

        for (ModLoader.LoadedMod mod : mods) {
            visit(mod, byId, placed, rejected, new LinkedHashSet<>(), ordered);
        }
        return List.copyOf(ordered);
    }

    /**
     * Insere o mod depois de suas dependências.
     *
     * @param visiting cadeia da visita atual, usada para detectar ciclos
     */
    private boolean visit(ModLoader.LoadedMod mod,
                          Map<String, ModLoader.LoadedMod> byId,
                          Set<String> placed,
                          Set<String> rejected,
                          Set<String> visiting,
                          List<ModLoader.LoadedMod> ordered) {
        String id = mod.manifest().id;
        if (placed.contains(id)) return true;
        if (rejected.contains(id)) return false;

        if (!visiting.add(id)) {
            logger.error("Dependencia circular envolvendo {}: {}", id, String.join(" -> ", visiting));
            rejected.add(id);
            return false;
        }

        try {
            Map<String, String> required = mod.manifest().dependencies;
            if (required != null) {
                for (Map.Entry<String, String> entry : required.entrySet()) {
                    String dependencyId = entry.getKey();
                    ModLoader.LoadedMod dependency = byId.get(dependencyId);

                    if (dependency == null) {
                        logger.error("Mod {} exige {}, que nao foi encontrado", id, dependencyId);
                        rejected.add(id);
                        return false;
                    }
                    if (!satisfies(dependency.manifest().version, entry.getValue())) {
                        logger.error("Mod {} exige {} na versao {} ou superior, mas ha {}",
                                id, dependencyId, entry.getValue(), dependency.manifest().version);
                        rejected.add(id);
                        return false;
                    }
                    if (!visit(dependency, byId, placed, rejected, visiting, ordered)) {
                        logger.error("Mod {} nao carrega porque {} falhou", id, dependencyId);
                        rejected.add(id);
                        return false;
                    }
                }
            }
        } finally {
            visiting.remove(id);
        }

        ordered.add(mod);
        placed.add(id);
        return true;
    }

    /**
     * Compara versões no formato {@code maior.menor.correcao}.
     *
     * <p>Partes ausentes valem zero e partes não numéricas encerram a comparação, de modo que
     * {@code 1.2.0-beta} seja tratado como {@code 1.2.0}. Uma versão exigida em branco aceita
     * qualquer versão presente.
     */
    public static boolean satisfies(String present, String minimum) {
        if (minimum == null || minimum.isBlank()) return true;
        if (present == null || present.isBlank()) return false;

        int[] found = parse(present);
        int[] wanted = parse(minimum);
        for (int index = 0; index < 3; index++) {
            if (found[index] > wanted[index]) return true;
            if (found[index] < wanted[index]) return false;
        }
        return true;
    }

    private static int[] parse(String version) {
        int[] parts = new int[3];
        String[] pieces = version.trim().replaceFirst("^[>=^~\\s]+", "").split("\\.");
        for (int index = 0; index < 3 && index < pieces.length; index++) {
            StringBuilder digits = new StringBuilder();
            for (char character : pieces[index].toCharArray()) {
                if (character < '0' || character > '9') break;
                digits.append(character);
            }
            if (digits.isEmpty()) break;
            parts[index] = Integer.parseInt(digits.toString());
        }
        return parts;
    }
}

package dev.lualoader.platform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolve espécies que descendem de outras espécies declaradas.
 *
 * <p>É o que permite um mod estender o bestiário de outro: {@code base} deixa de significar só uma
 * espécie do jogo e passa a aceitar {@code outromod:guardiao}. Um pacote de conteúdo declara o
 * guardião; um pacote de dificuldade declara o guardião de elite que herda dele e só muda a vida.
 *
 * <p><b>Mora no núcleo porque a pergunta não é sobre Minecraft.</b> Ordenar declarações por
 * dependência e recusar um ciclo é aritmética sobre texto, e as duas plataformas precisam da mesma
 * resposta. Deixar isso em cada adaptador daria duas implementações que concordam até o primeiro
 * caso torto — e o caso torto aqui é um mod que não carrega numa das plataformas.
 *
 * <p>A ordem importa porque o registro é feito uma vez, na carga: registrar o elite antes do
 * guardião faria o adaptador procurar uma base que ainda não existe e recusar uma espécie que está
 * perfeitamente declarada.
 */
public final class EntityDerivation {
    private EntityDerivation() {
    }

    /**
     * Uma espécie pronta para registrar, com o id completo e de quem ela herda.
     *
     * @param id         id completo, no formato {@code mod:especie}
     * @param definition o que o mod declarou, já com o herdado preenchido
     * @param parent     id completo da espécie declarada de que ela descende, ou {@code null}
     */
    public record Resolved(String id, EntityDefinition definition, String parent) {
    }

    /** Uma espécie que não pôde ser resolvida, e por quê. */
    public record Rejected(String id, String reason) {
    }

    /** O resultado da resolução: o que registrar, em ordem, e o que foi recusado. */
    public record Result(List<Resolved> ordered, List<Rejected> rejected) {
    }

    /**
     * Ordena as espécies de todos os mods e recusa as que não fecham.
     *
     * @param byMod as declarações de cada mod, na chave o id do mod
     * @param isGameBase se aquele id é uma espécie do jogo que o adaptador sabe derivar
     */
    public static Result resolve(Map<String, List<EntityDefinition>> byMod,
                                 java.util.function.Predicate<String> isGameBase) {
        Map<String, EntityDefinition> declared = new LinkedHashMap<>();
        byMod.forEach((modId, definitions) -> {
            if (definitions == null) return;
            for (EntityDefinition definition : definitions) {
                if (definition == null || definition.id == null) continue;
                declared.put(modId + ":" + definition.id, definition);
            }
        });

        List<Resolved> ordered = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();

        // O que ja foi resolvido, e nao so quais ids: a mescla de um neto precisa do pai ja
        // mesclado. Usar a declaracao crua do pai faria o neto herdar "a:avo" como base efetiva em
        // vez da especie do jogo, e o adaptador procuraria modelo numa especie que nao e do jogo.
        Map<String, EntityDefinition> done = new LinkedHashMap<>();

        // As recusas tambem sao definitivas: sem isto, uma base recusada seria dita uma vez pelo
        // laco de cima e outra por cada descendente que tropeca nela.
        Set<String> failed = new LinkedHashSet<>();

        for (String id : declared.keySet()) {
            visit(id, declared, isGameBase, done, failed, new LinkedHashSet<>(), ordered, rejected);
        }
        return new Result(List.copyOf(ordered), List.copyOf(rejected));
    }

    /**
     * Coloca uma espécie na ordem, depois de quem ela herda.
     *
     * <p>{@code path} é o caminho da descida atual, e não o conjunto do que já foi visto: é a
     * diferença entre detectar um ciclo e confundir um ancestral compartilhado com um. Dois elites
     * que herdam do mesmo guardião não são um ciclo, e uma marca única acusaria o segundo.
     */
    private static void visit(String id, Map<String, EntityDefinition> declared,
                              java.util.function.Predicate<String> isGameBase,
                              Map<String, EntityDefinition> done, Set<String> failed,
                              Set<String> path,
                              List<Resolved> ordered, List<Rejected> rejected) {
        if (done.containsKey(id) || failed.contains(id)) return;
        if (!path.add(id)) {
            // O ciclo é recusado inteiro, e com o caminho na mensagem: quem escreveu precisa ver
            // onde a volta fecha, e não só que existe uma.
            reject(id, "heranca circular: " + String.join(" -> ", path) + " -> " + id,
                    failed, rejected);
            return;
        }

        EntityDefinition definition = declared.get(id);
        String base = definition.base;

        if (isGameBase.test(base)) {
            ordered.add(new Resolved(id, definition, null));
            done.put(id, definition);
            path.remove(id);
            return;
        }

        if (!declared.containsKey(base)) {
            reject(id, "base desconhecida: " + base
                    + " (nao e especie do jogo suportada nem especie declarada por outro mod)",
                    failed, rejected);
            path.remove(id);
            return;
        }

        visit(base, declared, isGameBase, done, failed, path, ordered, rejected);
        path.remove(id);

        // A base foi recusada, então esta cai junto: registrar produziria uma criatura sem o que
        // herdar, e o motivo real já foi dito uma vez.
        EntityDefinition resolvedBase = done.get(base);
        if (resolvedBase == null) {
            reject(id, "a base declarada " + base + " nao pode ser registrada", failed, rejected);
            return;
        }

        EntityDefinition merged = merge(resolvedBase, definition);
        ordered.add(new Resolved(id, merged, base));
        done.put(id, merged);
    }

    /** Recusa uma vez só: um id recusado não volta a ser visitado por um descendente. */
    private static void reject(String id, String reason, Set<String> failed,
                               List<Rejected> rejected) {
        if (!failed.add(id)) return;
        rejected.add(new Rejected(id, reason));
    }

    /**
     * Junta o que a espécie declarou ao que ela herda.
     *
     * <p>O filho vence campo a campo, e o que ele não declarou vem do pai. É por isso que um mod de
     * dificuldade consegue declarar três linhas — id, nome e base — e mudar só a vida: sem a mescla,
     * ele teria que repetir o equipamento, o saque e os atributos inteiros, e as duas cópias
     * envelheceriam separadas.
     */
    private static EntityDefinition merge(EntityDefinition parent, EntityDefinition child) {
        EntityDefinition merged = new EntityDefinition();
        merged.id = child.id;
        merged.name = child.name;

        // A base efetiva é a do ancestral: é dela que vêm modelo e comportamento, e é o que o
        // adaptador precisa para achar a espécie do jogo.
        merged.base = parent.base;

        merged.category = child.category != null ? child.category : parent.category;
        merged.width = child.width > 0 ? child.width : parent.width;
        merged.height = child.height > 0 ? child.height : parent.height;
        merged.trackingRange = child.trackingRange > 0 ? child.trackingRange : parent.trackingRange;
        merged.updateInterval = child.updateInterval > 0
                ? child.updateInterval
                : parent.updateInterval;
        merged.fireImmune = child.fireImmune || parent.fireImmune;
        merged.summonable = child.summonable && parent.summonable;
        merged.saveable = child.saveable && parent.saveable;

        merged.defaults = mergeDefaults(parent.defaults, child.defaults);
        merged.loot = mergeLoot(parent.loot, child.loot);

        // O ovo não é herdado: dois ovos com a mesma cor e nomes parecidos são indistinguíveis na
        // aba do criativo, e herdar em silêncio criaria um por descendente.
        merged.spawnEgg = child.spawnEgg;

        // A tag nao e herdada, e a razao e consistencia com o que vai para o disco: o data pack e
        // gerado a partir do manifesto declarado, sem passar por esta mescla. Herdar aqui daria uma
        // especie que o adaptador considera marcada e o jogo nao -- a pior forma de divergencia,
        // porque some entre duas partes que, cada uma sozinha, parecem certas.
        merged.tags = child.tags;

        // Pela mesma razao da tag: o pacote de recursos sai do manifesto declarado, e herdar aqui
        // daria uma especie que o adaptador considera texturizada e o jogo desenha com a pele da
        // base.
        merged.texture = child.texture;
        merged.model = child.model;

        // Nascimento natural nao e herdado, e e a decisao mais conservadora possivel: um pacote de
        // dificuldade que cria uma variante de elite nao deveria, sem dizer nada, faze-la nascer em
        // todo bioma onde o original nasce -- dobrando a populacao do mundo de quem instalou.
        merged.spawn = child.spawn;
        merged.ai = child.ai;
        return merged;
    }

    /** Campo a campo, porque {@code null} aqui significa "não declarado", e não "vazio". */
    private static EntitySpec mergeDefaults(EntitySpec parent, EntitySpec child) {
        if (parent == null) return child;
        if (child == null) return parent;

        EntitySpec merged = new EntitySpec();
        merged.name = child.name != null ? child.name : parent.name;
        merged.nameVisible = child.nameVisible != null ? child.nameVisible : parent.nameVisible;
        merged.tame = child.tame != null ? child.tame : parent.tame;
        merged.baby = child.baby != null ? child.baby : parent.baby;
        merged.persistent = child.persistent != null ? child.persistent : parent.persistent;
        merged.noAi = child.noAi != null ? child.noAi : parent.noAi;
        merged.variant = child.variant != null ? child.variant : parent.variant;
        merged.health = child.health != null ? child.health : parent.health;
        merged.invulnerable = child.invulnerable != null
                ? child.invulnerable
                : parent.invulnerable;
        merged.silent = child.silent != null ? child.silent : parent.silent;
        merged.noGravity = child.noGravity != null ? child.noGravity : parent.noGravity;
        merged.glowing = child.glowing != null ? child.glowing : parent.glowing;
        merged.fireTicks = child.fireTicks != null ? child.fireTicks : parent.fireTicks;
        merged.frozenTicks = child.frozenTicks != null ? child.frozenTicks : parent.frozenTicks;
        merged.yaw = child.yaw != null ? child.yaw : parent.yaw;
        merged.pitch = child.pitch != null ? child.pitch : parent.pitch;

        // Atributos e equipamento se mesclam por chave, e nao em bloco: declarar so a velocidade
        // nao deveria apagar a vida e o dano que o pai declarou.
        merged.attributes = new LinkedHashMap<>(parent.attributesOrEmpty());
        merged.attributes.putAll(child.attributesOrEmpty());

        merged.equipment = new LinkedHashMap<>(parent.equipmentOrEmpty());
        merged.equipment.putAll(child.equipmentOrEmpty());

        // Efeitos somam: sao uma lista de coisas independentes, e o filho acrescentar veneno nao
        // quer dizer que ele desistiu da forca que o pai deu.
        List<EntitySpec.EffectSpec> effects = new ArrayList<>(parent.effectsOrEmpty());
        effects.addAll(child.effectsOrEmpty());
        merged.effects = effects;
        return merged;
    }

    /** A tabela mais específica vence; os drops somam, pelo mesmo motivo dos efeitos. */
    private static EntityDefinition.EntityLootDefinition mergeLoot(
            EntityDefinition.EntityLootDefinition parent,
            EntityDefinition.EntityLootDefinition child) {
        if (parent == null) return child;
        if (child == null) return parent;

        var merged = new EntityDefinition.EntityLootDefinition();
        merged.table = child.table != null && !child.table.isBlank() ? child.table : parent.table;
        merged.drops = new ArrayList<>(parent.drops);
        merged.drops.addAll(child.drops);
        return merged;
    }

    /** Só os ids, na ordem resolvida. Serve para o log e para o teste ler sem cerimônia. */
    public static List<String> idsOf(Collection<Resolved> resolved) {
        return resolved.stream().map(Resolved::id).toList();
    }
}

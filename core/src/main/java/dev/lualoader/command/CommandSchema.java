package dev.lualoader.command;

import dev.lualoader.manifest.ModManifest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Árvore declarativa de comandos do MineLoader.
 *
 * <p>O core conhece apenas literais, argumentos portáveis e limites. Cada bridge transforma este
 * modelo na árvore de comandos da sua plataforma, sem deixar Brigadier atravessar a fronteira Lua.
 */
public final class CommandSchema {
    public static final int VERSION = 1;
    public static final int MAX_NODES = 128;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_SUGGESTIONS = 32;
    public static final int MAX_SUGGESTION_LENGTH = 64;

    public static final Set<String> ARGUMENT_TYPES = Set.of(
            "word", "string", "greedy_string", "integer", "double", "boolean");

    private final List<Node> roots;
    private final int nodeCount;

    public CommandSchema(List<Node> roots) {
        this.roots = List.copyOf(Objects.requireNonNull(roots, "roots"));
        Counter counter = new Counter();
        validateNodes(this.roots, 0, counter, new HashMap<>());
        if (counter.value > MAX_NODES) {
            throw new IllegalArgumentException("schema de comando excede " + MAX_NODES + " nos");
        }
        this.nodeCount = counter.value;
    }

    public List<Node> roots() {
        return roots;
    }

    /** Converte a declaração de {@code mod.json} para o contrato neutro do core. */
    public static CommandSchema fromManifest(ModManifest.CommandDefinition definition) {
        if (definition == null || definition.children == null || definition.children.isEmpty()) {
            throw new IllegalArgumentException("comando do manifesto precisa de pelo menos um child");
        }
        List<Node> roots = new ArrayList<>();
        for (ModManifest.CommandNodeDefinition node : definition.children) {
            roots.add(fromManifestNode(node));
        }
        return new CommandSchema(roots);
    }

    private static Node fromManifestNode(ModManifest.CommandNodeDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("no de comando nulo");
        boolean hasLiteral = definition.literal != null;
        boolean hasArgument = definition.argument != null;
        if (hasLiteral == hasArgument) {
            throw new IllegalArgumentException("no deve declarar exactamente um de literal ou argument");
        }
        List<Node> children = new ArrayList<>();
        if (definition.children != null) {
            for (ModManifest.CommandNodeDefinition child : definition.children) {
                children.add(fromManifestNode(child));
            }
        }
        boolean executable = definition.executes == null
                ? children.isEmpty() : definition.executes;
        if (hasArgument) {
            ModManifest.CommandArgumentDefinition argument = definition.argument;
            return Node.argument(new Argument(argument.name, argument.type, argument.min, argument.max,
                    argument.suggestions), executable, children);
        }
        return Node.literal(definition.literal, executable, children);
    }

    public int nodeCount() {
        return nodeCount;
    }

    /** Procura a definição de argumento usada no caminho actual ou em outra ramificação compatível. */
    public Argument argument(String name) {
        return findArgument(roots, name);
    }

    /**
     * Junta uma extensão à árvore, preservando a ordem e recusando colisões semânticas.
     * Literais iguais podem juntar filhos; argumentos iguais precisam ter a mesma definição.
     */
    public CommandSchema merge(CommandSchema extension) {
        if (extension == null) return this;
        return new CommandSchema(mergeNodes(roots, extension.roots));
    }

    private static List<Node> mergeNodes(List<Node> base, List<Node> extension) {
        List<Node> merged = new ArrayList<>(base);
        for (Node incoming : extension) {
            int existingIndex = indexOfNode(merged, incoming.name());
            if (existingIndex < 0) {
                merged.add(incoming);
                continue;
            }

            Node existing = merged.get(existingIndex);
            if ((existing.literal() == null) != (incoming.literal() == null)) {
                throw new IllegalArgumentException("literal e argumento colidem no caminho "
                        + incoming.name());
            }
            if (existing.argument() != null && !existing.argument().equals(incoming.argument())) {
                throw new IllegalArgumentException("argumento " + incoming.argument().name()
                        + " foi declarado com definições diferentes");
            }
            merged.set(existingIndex, new Node(
                    existing.literal(), existing.argument(),
                    existing.executable() || incoming.executable(),
                    mergeNodes(existing.children(), incoming.children())));
        }
        return List.copyOf(merged);
    }

    private static int indexOfNode(List<Node> nodes, String name) {
        for (int index = 0; index < nodes.size(); index++) {
            if (nodes.get(index).name().equals(name)) return index;
        }
        return -1;
    }

    /** Nomes de argumentos declarados, sem duplicatas, para extrair valores do contexto Brigadier. */
    public List<String> argumentNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        collectArgumentNames(roots, names);
        return List.copyOf(names);
    }

    private static void collectArgumentNames(List<Node> nodes, LinkedHashSet<String> names) {
        for (Node node : nodes) {
            if (node.argument() != null) names.add(node.argument().name());
            collectArgumentNames(node.children(), names);
        }
    }

    private static Argument findArgument(List<Node> nodes, String name) {
        for (Node node : nodes) {
            if (node.argument() != null && node.argument().name().equals(name)) {
                return node.argument();
            }
            Argument nested = findArgument(node.children(), name);
            if (nested != null) return nested;
        }
        return null;
    }

    private static void validateNodes(List<Node> nodes, int depth, Counter counter,
                                      Map<String, String> argumentTypes) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("schema de comando excede a profundidade " + MAX_DEPTH);
        }
        Set<String> siblings = new HashSet<>();
        for (Node node : nodes) {
            if (node == null) throw new IllegalArgumentException("no de comando nulo");
            counter.value++;
            if ((node.literal() == null) == (node.argument() == null)) {
                throw new IllegalArgumentException("no deve ser literal ou argumento, nunca os dois");
            }
            if (!siblings.add(node.name())) {
                throw new IllegalArgumentException("no duplicado no mesmo nivel: " + node.name());
            }
            if (node.argument() != null && node.argument().type().equals("greedy_string")
                    && !node.children().isEmpty()) {
                throw new IllegalArgumentException("greedy_string não pode ter filhos");
            }
            validateNodes(node.children(), depth + 1, counter, argumentTypes);
            if (node.argument() != null) {
                String previous = argumentTypes.putIfAbsent(node.argument().name(), node.argument().type());
                if (previous != null && !previous.equals(node.argument().type())) {
                    throw new IllegalArgumentException("argumento " + node.argument().name()
                            + " usa tipos diferentes no schema");
                }
            }
        }
    }

    public record Node(String literal, Argument argument, boolean executable, List<Node> children) {
        public Node {
            if (literal != null) literal = requireIdentifier(literal, "literal");
            if (argument != null && literal != null) {
                throw new IllegalArgumentException("no de comando não pode ser literal e argumento");
            }
            children = children == null ? List.of() : List.copyOf(children);
        }

        public static Node literal(String literal, boolean executable, List<Node> children) {
            return new Node(literal, null, executable, children);
        }

        public static Node argument(Argument argument, boolean executable, List<Node> children) {
            return new Node(null, argument, executable, children);
        }

        public String name() {
            return literal != null ? literal : argument.name();
        }
    }

    public record Argument(String name, String type, Double min, Double max,
                           List<String> suggestions) {
        public Argument {
            name = requireIdentifier(name, "argumento");
            type = requireType(type);
            if (min != null && !Double.isFinite(min)) throw new IllegalArgumentException("min inválido");
            if (max != null && !Double.isFinite(max)) throw new IllegalArgumentException("max inválido");
            if (min != null && max != null && min > max) {
                throw new IllegalArgumentException("min não pode ser maior que max");
            }
            boolean numeric = type.equals("integer") || type.equals("double");
            if (!numeric && (min != null || max != null)) {
                throw new IllegalArgumentException("min e max exigem argumento numerico");
            }
            if (type.equals("integer") && ((min != null && min < Integer.MIN_VALUE)
                    || (max != null && max > Integer.MAX_VALUE))) {
                throw new IllegalArgumentException("limite inteiro fora do intervalo suportado");
            }
            List<String> safeSuggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            if (safeSuggestions.size() > MAX_SUGGESTIONS) {
                throw new IllegalArgumentException("argumento " + name + " excede as sugestões permitidas");
            }
            for (String suggestion : safeSuggestions) {
                if (suggestion == null || suggestion.isBlank()
                        || suggestion.length() > MAX_SUGGESTION_LENGTH) {
                    throw new IllegalArgumentException("sugestão inválida no argumento " + name);
                }
                try {
                    if (type.equals("integer")) Integer.parseInt(suggestion);
                    if (type.equals("double")) Double.parseDouble(suggestion);
                    if (type.equals("boolean")
                            && !suggestion.equals("true") && !suggestion.equals("false")) {
                        throw new IllegalArgumentException("booleano só aceita true ou false");
                    }
                } catch (NumberFormatException error) {
                    throw new IllegalArgumentException("sugestão incompatível com " + type + ": " + suggestion);
                }
            }
            suggestions = safeSuggestions;
        }

        public boolean numeric() {
            return type.equals("integer") || type.equals("double");
        }
    }

    /** Converte o formato neutro de argumentos para dados simples que o Lua possa consumir. */
    public static Map<String, String> copyArguments(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        return Map.copyOf(new LinkedHashMap<>(values));
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !value.matches("^[a-z][a-z0-9_.-]{0,31}$")) {
            throw new IllegalArgumentException(field + " inválido: " + value);
        }
        return value;
    }

    private static String requireType(String value) {
        String type = value == null ? "word" : value.toLowerCase(java.util.Locale.ROOT);
        if (!ARGUMENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("tipo de argumento desconhecido: " + value);
        }
        return type;
    }

    private static final class Counter {
        private int value;
    }
}

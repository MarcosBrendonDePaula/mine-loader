package dev.lualoader.resources;

import dev.lualoader.manifest.ModManifest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Os recursos nomeados de um mod, e a tradução de uma referência para o recurso.
 *
 * <p>Vive no núcleo porque resolver {@code "@cristal"} é leitura de manifesto, não conhecimento de
 * plataforma. Um nome que não existe precisa virar mensagem para quem escreveu o mod — na carga, e
 * não quando alguém olha o bloco pela primeira vez e vê um cubo roxo.
 */
public final class ResourceCatalog {
    /** Os tipos que o loader sabe resolver. */
    public static final Set<String> TYPES = Set.of("image", "model", "sound", "script", "data");

    private final Map<String, ModManifest.ResourceDefinition> resources;

    public ResourceCatalog(ModManifest manifest) {
        this.resources = manifest == null || manifest.resources == null
                ? Map.of()
                : manifest.resources;
    }

    /** Se o mod declarou algum recurso nomeado. */
    public boolean isEmpty() {
        return resources.isEmpty();
    }

    /** O recurso com aquele nome, ou {@code null}. */
    public ModManifest.ResourceDefinition get(String name) {
        return name == null ? null : resources.get(name);
    }

    /**
     * Converte uma textura declarada por referência na declaração completa.
     *
     * <p>Devolve a própria entrada quando não há referência: é o que faz a forma inline continuar
     * valendo, e o que permite os dois estilos conviverem no mesmo manifesto durante a transição.
     *
     * @throws IllegalArgumentException se a referência não existir ou apontar para outro tipo
     */
    public ModManifest.TextureDefinition resolveTexture(ModManifest.TextureDefinition texture) {
        if (texture == null || texture.ref == null || texture.ref.isBlank()) return texture;

        ModManifest.ResourceDefinition resource = require(texture.ref, "image");

        ModManifest.TextureDefinition resolved = new ModManifest.TextureDefinition();
        resolved.source = isRemote(resource.from) ? "remote" : "local";
        if (isRemote(resource.from)) {
            resolved.url = resource.from;
        } else {
            resolved.path = resource.from;
        }
        resolved.sha256 = resource.sha256;
        resolved.maxBytes = resource.maxBytes;
        // O fallback continua vindo de quem referencia: e uma decisao de como aquele bloco deve
        // aparecer quando o recurso falta, e nao uma propriedade do recurso.
        resolved.fallback = texture.fallback;
        return resolved;
    }

    /**
     * O recurso com aquele nome, exigindo o tipo.
     *
     * <p>O tipo errado é um erro tão real quanto o nome inexistente: uma textura que aponta para um
     * som falharia mais adiante, com uma mensagem sobre bytes inválidos que não diz o que fazer.
     */
    public ModManifest.ResourceDefinition require(String name, String expectedType) {
        ModManifest.ResourceDefinition resource = get(name);
        if (resource == null) {
            throw new IllegalArgumentException("recurso nao declarado: @" + name
                    + (resources.isEmpty() ? "" : "; declarados: " + names()));
        }

        String type = resource.type == null ? "image" : resource.type.toLowerCase(Locale.ROOT);
        if (!type.equals(expectedType)) {
            throw new IllegalArgumentException("recurso @" + name + " e do tipo " + type
                    + ", mas foi usado onde se espera " + expectedType);
        }
        return resource;
    }

    /** Os nomes declarados, em ordem, para mensagens que dizem o que se pode escrever. */
    public List<String> names() {
        return new LinkedHashSet<>(resources.keySet()).stream().sorted().toList();
    }

    /** Se a origem é um endereço remoto, e não um caminho dentro do mod. */
    public static boolean isRemote(String from) {
        if (from == null) return false;
        String lower = from.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }
}

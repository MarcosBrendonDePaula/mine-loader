package dev.lualoader.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A geometria declarada de uma espécie: ossos e caixas, sem plataforma nenhuma.
 *
 * <p><b>Os nomes dos ossos não são livres, e é isso que faz a coisa funcionar.</b> As classes de
 * modelo do jogo recebem uma raiz e procuram os filhos por nome — {@code head}, {@code body},
 * {@code right_arm} — para depois girá-los a cada quadro. Se a geometria declarada usar os mesmos
 * nomes que a base usa, a animação dela move as caixas novas sem saber que elas mudaram.
 *
 * <p>É o que permite entregar forma própria sem escrever animação: o bicho anda como um golem, e
 * parece outra coisa. Um osso com nome que a base não conhece simplesmente não é desenhado — não dá
 * erro, não some do registro, apenas não aparece. Por isso o loader avisa alto quando encontra um.
 *
 * <p>A escala é a do formato de modelo do Minecraft: pixels, com o corpo em torno de 24 de altura.
 * É a mesma que qualquer ferramenta de modelagem exporta, e converter aqui só introduziria erro de
 * meio pixel.
 */
public final class EntityModelSpec {
    /** Tamanho da folha de textura, em pixels. */
    public final int textureWidth;
    public final int textureHeight;

    /** Os ossos, na ordem em que foram declarados. */
    public final Map<String, Bone> bones;

    private EntityModelSpec(int textureWidth, int textureHeight, Map<String, Bone> bones) {
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.bones = Map.copyOf(bones);
    }

    /**
     * Um osso: o ponto em torno do qual a animação gira, e as caixas que giram com ele.
     *
     * @param pivotX ponto de rotação, na escala de pixels do jogo
     * @param cubes  as caixas presas a este osso
     */
    public record Bone(float pivotX, float pivotY, float pivotZ, List<Cube> cubes) {
    }

    /**
     * Uma caixa.
     *
     * @param uvX     canto da textura de onde a caixa é recortada
     * @param inflate cresce a caixa em todas as direções sem mover o pivô; é como se faz uma camada
     *                externa, como o casaco de um jogador, sem que ela brigue com a de baixo
     * @param mirror  espelha o recorte da textura, para um braço reusar a arte do outro
     */
    public record Cube(float fromX, float fromY, float fromZ,
                       float sizeX, float sizeY, float sizeZ,
                       int uvX, int uvY,
                       float inflate, boolean mirror) {
    }

    /** O que um modelo mal escrito produz, para a mensagem chegar a quem escreveu o mod. */
    public static final class InvalidModelException extends RuntimeException {
        public InvalidModelException(String message) {
            super(message);
        }
    }

    /**
     * Lê a geometria declarada.
     *
     * <p>Recusa em vez de completar o que falta. Uma caixa sem tamanho, um pivô com dois números ou
     * um osso sem caixa nenhuma produzem, cada um, uma peça que não aparece — e um bicho ao qual
     * falta um braço não se parece com erro de arquivo para quem o desenhou.
     */
    public static EntityModelSpec parse(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new InvalidModelException("modelo de entidade nao e um objeto JSON valido");
        }

        int width = 64;
        int height = 64;
        if (root.has("texture_size")) {
            JsonArray size = array(root, "texture_size", 2, "texture_size");
            width = size.get(0).getAsInt();
            height = size.get(1).getAsInt();
            if (width <= 0 || height <= 0) {
                throw new InvalidModelException("texture_size precisa ser positivo");
            }
        }

        if (!root.has("bones") || !root.get("bones").isJsonObject()) {
            throw new InvalidModelException("modelo de entidade precisa de um objeto bones");
        }

        Map<String, Bone> bones = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : root.getAsJsonObject("bones").entrySet()) {
            String name = entry.getKey();
            if (name.isBlank()) throw new InvalidModelException("osso sem nome");
            if (!entry.getValue().isJsonObject()) {
                throw new InvalidModelException("osso " + name + " precisa ser um objeto");
            }
            bones.put(name, parseBone(name, entry.getValue().getAsJsonObject()));
        }

        if (bones.isEmpty()) {
            throw new InvalidModelException("modelo de entidade sem osso nenhum");
        }
        return new EntityModelSpec(width, height, bones);
    }

    private static Bone parseBone(String name, JsonObject bone) {
        float pivotX = 0;
        float pivotY = 0;
        float pivotZ = 0;
        if (bone.has("pivot")) {
            JsonArray pivot = array(bone, "pivot", 3, "pivot do osso " + name);
            pivotX = pivot.get(0).getAsFloat();
            pivotY = pivot.get(1).getAsFloat();
            pivotZ = pivot.get(2).getAsFloat();
        }

        List<Cube> cubes = new ArrayList<>();
        if (bone.has("cubes")) {
            if (!bone.get("cubes").isJsonArray()) {
                throw new InvalidModelException("cubes do osso " + name + " precisa ser uma lista");
            }
            for (com.google.gson.JsonElement element : bone.getAsJsonArray("cubes")) {
                if (!element.isJsonObject()) {
                    throw new InvalidModelException("caixa do osso " + name + " precisa ser objeto");
                }
                cubes.add(parseCube(name, element.getAsJsonObject()));
            }
        }

        // Um osso sem caixa e legitimo: serve de junta, e a animacao gira os filhos dele. Mas o
        // formato de hoje e plano, entao um osso vazio aqui nao desenha e nao gira nada.
        if (cubes.isEmpty()) {
            throw new InvalidModelException("osso " + name + " nao tem caixa nenhuma; ele nao"
                    + " apareceria no jogo");
        }
        return new Bone(pivotX, pivotY, pivotZ, List.copyOf(cubes));
    }

    private static Cube parseCube(String bone, JsonObject cube) {
        JsonArray from = array(cube, "from", 3, "from de uma caixa do osso " + bone);
        JsonArray size = array(cube, "size", 3, "size de uma caixa do osso " + bone);

        float sizeX = size.get(0).getAsFloat();
        float sizeY = size.get(1).getAsFloat();
        float sizeZ = size.get(2).getAsFloat();
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
            throw new InvalidModelException("size de uma caixa do osso " + bone
                    + " nao pode ser negativo");
        }

        int uvX = 0;
        int uvY = 0;
        if (cube.has("uv")) {
            JsonArray uv = array(cube, "uv", 2, "uv de uma caixa do osso " + bone);
            uvX = uv.get(0).getAsInt();
            uvY = uv.get(1).getAsInt();
            if (uvX < 0 || uvY < 0) {
                throw new InvalidModelException("uv de uma caixa do osso " + bone
                        + " nao pode ser negativo");
            }
        }

        float inflate = cube.has("inflate") ? cube.get("inflate").getAsFloat() : 0.0f;
        boolean mirror = cube.has("mirror") && cube.get("mirror").getAsBoolean();

        return new Cube(from.get(0).getAsFloat(), from.get(1).getAsFloat(), from.get(2).getAsFloat(),
                sizeX, sizeY, sizeZ, uvX, uvY, inflate, mirror);
    }

    /** Exige uma lista de números do tamanho certo, dizendo qual campo falhou. */
    private static JsonArray array(JsonObject owner, String field, int length, String description) {
        if (!owner.has(field) || !owner.get(field).isJsonArray()) {
            throw new InvalidModelException(description + " e obrigatorio e precisa ser uma lista");
        }
        JsonArray array = owner.getAsJsonArray(field);
        if (array.size() != length) {
            throw new InvalidModelException(description + " precisa ter " + length
                    + " numeros, tem " + array.size());
        }
        for (com.google.gson.JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
                throw new InvalidModelException(description + " so aceita numeros");
            }
        }
        return array;
    }

    /**
     * Os nomes de osso que cada base do jogo sabe animar.
     *
     * <p>Vive no núcleo, e não em cada adaptador, porque a resposta é a mesma nos dois e a pergunta
     * é feita cedo: um osso com nome errado não dá erro em lugar nenhum — a peça só não aparece.
     * Avisar na carga é a diferença entre "o braço sumiu" e "você escreveu {@code arm} onde a base
     * espera {@code right_arm}".
     */
    public static final Map<String, List<String>> BONES_BY_BASE = Map.of(
            "minecraft:zombie",
            List.of("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg"),
            "minecraft:skeleton",
            List.of("head", "hat", "body", "right_arm", "left_arm", "right_leg", "left_leg"),
            "minecraft:creeper",
            List.of("head", "body", "right_hind_leg", "left_hind_leg",
                    "right_front_leg", "left_front_leg"),
            "minecraft:spider",
            List.of("head", "body0", "body1", "right_hind_leg", "left_hind_leg",
                    "right_middle_hind_leg", "left_middle_hind_leg",
                    "right_middle_front_leg", "left_middle_front_leg",
                    "right_front_leg", "left_front_leg"),
            "minecraft:pig",
            List.of("head", "body", "right_hind_leg", "left_hind_leg",
                    "right_front_leg", "left_front_leg"),
            "minecraft:cow",
            List.of("head", "body", "right_hind_leg", "left_hind_leg",
                    "right_front_leg", "left_front_leg"),
            "minecraft:sheep",
            List.of("head", "body", "right_hind_leg", "left_hind_leg",
                    "right_front_leg", "left_front_leg"),
            "minecraft:chicken",
            List.of("head", "beak", "red_thing", "body", "right_leg", "left_leg",
                    "right_wing", "left_wing"),
            "minecraft:wolf",
            List.of("head", "body", "right_hind_leg", "left_hind_leg",
                    "right_front_leg", "left_front_leg", "tail", "upper_body"),
            "minecraft:iron_golem",
            List.of("head", "body", "right_arm", "left_arm", "right_leg", "left_leg"));

    /**
     * Os ossos declarados que a base não conhece.
     *
     * <p>Lista, e não um booleano: a mensagem precisa dizer qual osso e o que a base espera, senão
     * quem escreveu o mod fica procurando entre sete nomes parecidos.
     */
    public List<String> unknownBones(String baseId) {
        List<String> known = BONES_BY_BASE.get(baseId);
        if (known == null) return List.of();

        List<String> unknown = new ArrayList<>();
        for (String declared : bones.keySet()) {
            if (!known.contains(declared)) unknown.add(declared);
        }
        return List.copyOf(unknown);
    }
}

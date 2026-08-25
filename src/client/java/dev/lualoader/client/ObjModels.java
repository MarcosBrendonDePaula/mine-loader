package dev.lualoader.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lualoader.content.ObjModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelResolver;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelBakeSettings;
import net.minecraft.client.render.model.UnbakedModel;
import net.minecraft.client.render.model.json.ModelOverrideList;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.SpriteIdentifier;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Desenha blocos cujo modelo é um arquivo OBJ.
 *
 * <p><b>Por que o loader precisa disto.</b> O formato de modelo do Minecraft descreve caixas, e uma
 * malha não é união de caixas. O cano do Logistic Pipes tem a geometria num OBJ de milhares de
 * vértices — sem ler esse formato, portar um mod de verdade esbarra no desenho.
 *
 * <p><b>Quem decide é o pacote, e não o manifesto.</b> Quando o jogo pede o modelo de um bloco, este
 * resolvedor procura um {@code .obj} com o mesmo nome no pacote gerado. Achou, desenha a malha;
 * não achou, deixa o jogo seguir pelo caminho de sempre. O cliente não precisa conhecer o manifesto
 * para isso, o que mantém a regra do repositório: o cliente interpreta dados.
 *
 * <p><b>A leitura da malha mora no núcleo</b> ({@link ObjModel}), junto com a do NeoForge. O
 * NeoForge tem um leitor de OBJ próprio e o Fabric não; usar o de lá num lado daria o mesmo arquivo
 * produzindo modelos diferentes em cada plataforma.
 */
public final class ObjModels {
    private ObjModels() {
    }

    public static void register() {
        ModelLoadingPlugin.register(plugin -> plugin.resolveModel().register(ObjModels::resolve));
    }

    /**
     * Procura um OBJ para o modelo pedido.
     *
     * <p>Devolver {@code null} não é falha: é o caso comum. Todo modelo do jogo passa por aqui, e a
     * pergunta é só se existe um arquivo com aquele nome.
     */
    @Nullable
    private static UnbakedModel resolve(ModelResolver.Context context) {
        Identifier id = context.id();
        if (!id.getPath().startsWith("block/")) return null;

        // A declaracao mora no JSON escrito pelo pacote, e nao no nome do arquivo: e ela que diz
        // qual malha abrir e quais grupos desenhar. Assim varias pecas de um mesmo bloco -- o
        // miolo, o lado ligado, o lado livre -- apontam para o mesmo OBJ com recortes diferentes.
        JsonObject declaracao = readJson(Identifier.of(id.getNamespace(),
                "models/" + id.getPath() + ".json"));
        if (declaracao == null || !declaracao.has("lua_obj")) return null;

        Identifier objId = Identifier.tryParse(declaracao.get("lua_obj").getAsString());
        if (objId == null) return null;
        // "logistica:block/cano.obj" aponta para "assets/logistica/models/block/cano.obj".
        objId = Identifier.of(objId.getNamespace(), "models/" + objId.getPath());

        var resource = MinecraftClient.getInstance().getResourceManager().getResource(objId);
        if (resource.isEmpty()) {
            LuaLoaderClient.LOGGER.warn("Malha {} declarada por {} nao existe no pacote", objId, id);
            return null;
        }

        List<String> grupos = new ArrayList<>();
        if (declaracao.has("lua_obj_groups")) {
            declaracao.getAsJsonArray("lua_obj_groups")
                    .forEach(elemento -> grupos.add(elemento.getAsString()));
        }

        try (var stream = resource.get().getInputStream();
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {

            // Escala primeiro, recorte depois. Ao contrario, cada peca seria escalada pela propria
            // caixa e sairia de tamanho diferente e fora do lugar -- o miolo no centro e a manga a
            // metros dali.
            ObjModel model = ObjModel.read(reader).normalized().filtered(grupos);
            Identifier texture = textureOf(declaracao);

            // Encolhe a coordenada de textura em torno do centro. Serve para uma peca mostrar so o
            // miolo de uma imagem de bloco, sem a borda -- sem isso a textura sai esticada de canto
            // a canto da peca, e um bloco pequeno vira uma mancha de cor.
            float uvScale = declaracao.has("lua_obj_uv_scale")
                    ? declaracao.get("lua_obj_uv_scale").getAsFloat()
                    : 1f;

            if (model.faces().isEmpty()) {
                LuaLoaderClient.LOGGER.warn("Peca {} nao casou com grupo nenhum de {}: {}",
                        id, objId, grupos);
                return null;
            }

            LuaLoaderClient.LOGGER.info("Modelo OBJ de {}: {} face(s), textura {}{}",
                    id, model.faces().size(), texture,
                    grupos.isEmpty() ? "" : " (grupos " + grupos + ")");
            return new ObjUnbakedModel(model, texture, uvScale);
        } catch (IOException error) {
            // Sem derrubar o jogo: a declaracao tem um parent de cubo como reserva, e o bloco
            // aparece como cubo texturizado em vez de sumir.
            LuaLoaderClient.LOGGER.warn("Malha {} nao pode ser lida, usando a reserva: {}",
                    objId, error.getMessage());
            return null;
        }
    }

    /** Le um JSON do pacote, ou devolve {@code null} quando ele nao existe. */
    @Nullable
    private static JsonObject readJson(Identifier id) {
        var resource = MinecraftClient.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) return null;

        try (var stream = resource.get().getInputStream();
             var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception error) {
            LuaLoaderClient.LOGGER.warn("Declaracao {} ilegivel: {}", id, error.getMessage());
            return null;
        }
    }

    /**
     * A textura da malha, lida da mesma declaracao que aponta a peca.
     *
     * <p>Ler de la em vez de adivinhar pelo nome e o que mantem a malha e a reserva iguais: se a
     * textura declarada faltou e o pacote caiu no substituto, a malha cai no mesmo substituto.
     * Divergir daria um bloco que muda de cor quando algo falha, e isso esconderia a falha.
     */
    private static Identifier textureOf(JsonObject declaracao) {
        if (declaracao.has("textures")) {
            JsonObject textures = declaracao.getAsJsonObject("textures");
            for (String slot : List.of("all", "particle")) {
                if (textures.has(slot)) {
                    Identifier parsed = Identifier.tryParse(textures.get(slot).getAsString());
                    if (parsed != null) return parsed;
                }
            }
        }
        return Identifier.ofVanilla("block/stone");
    }

    /** O modelo antes de o atlas existir: guarda a malha e o nome da textura. */
    private record ObjUnbakedModel(ObjModel model, Identifier texture, float uvScale)
            implements UnbakedModel {
        @Override
        public Collection<Identifier> getModelDependencies() {
            return List.of();
        }

        @Override
        public void setParents(Function<Identifier, UnbakedModel> resolver) {
            // Sem pai: a malha ja traz toda a geometria, e herdar nao acrescentaria nada.
        }

        @Override
        public BakedModel bake(Baker baker, Function<SpriteIdentifier, Sprite> textures,
                               ModelBakeSettings settings) {
            Sprite sprite = textures.apply(new SpriteIdentifier(
                    PlayerScreenHandler.BLOCK_ATLAS_TEXTURE, texture));

            List<BakedQuad> quads = new ArrayList<>(model.faces().size());
            for (ObjModel.Face face : model.faces()) {
                BakedQuad quad = quadOf(face, sprite, uvScale);
                if (quad != null) quads.add(quad);
            }

            LuaLoaderClient.LOGGER.info(
                    "Malha assada: {} quad(s), sprite {}, caixa x {}..{} y {}..{} z {}..{}",
                    quads.size(), sprite.getContents().getId(),
                    model.minX(), model.maxX(), model.minY(), model.maxY(),
                    model.minZ(), model.maxZ());

            return new ObjBakedModel(List.copyOf(quads), sprite);
        }
    }

    /**
     * Uma face da malha virando um quad do jogo.
     *
     * <p>Triângulo vira quad com o último vértice repetido. O jogo desenha quads, e repetir o
     * vértice é o jeito de descrever um triângulo sem inventar geometria — a alternativa seria
     * descartar as faces de três lados, e a maior parte de um OBJ exportado é feita delas.
     */
    @Nullable
    private static BakedQuad quadOf(ObjModel.Face face, Sprite sprite, float uvScale) {
        List<ObjModel.Vertex> vertices = face.vertices();
        if (vertices.size() < 3) return null;

        ObjModel.Vertex a = vertices.get(0);
        ObjModel.Vertex b = vertices.get(1);
        ObjModel.Vertex c = vertices.get(2);
        ObjModel.Vertex d = vertices.size() > 3 ? vertices.get(3) : c;

        Direction direction = dominantSide(a, b, c);
        int normal = packNormal(direction);

        int[] data = new int[32];
        putVertex(data, 0, a, sprite, normal, uvScale);
        putVertex(data, 8, b, sprite, normal, uvScale);
        putVertex(data, 16, c, sprite, normal, uvScale);
        putVertex(data, 24, d, sprite, normal, uvScale);

        // shade ligado: sem ele a malha fica com todas as faces na mesma luz, e o desenho perde o
        // volume -- um cano vira uma mancha da cor da textura.
        return new BakedQuad(data, -1, direction, sprite, true);
    }

    /** Escreve um vértice no formato que o jogo espera: posição, cor, uv, luz e normal. */
    private static void putVertex(int[] data, int offset, ObjModel.Vertex vertex,
                                  Sprite sprite, int normal, float uvScale) {
        // As coordenadas do modelo estao em dezesseis avos, e o jogo desenha o bloco de 0 a 1.
        data[offset] = Float.floatToRawIntBits((float) (vertex.x() / 16.0));
        data[offset + 1] = Float.floatToRawIntBits((float) (vertex.y() / 16.0));
        data[offset + 2] = Float.floatToRawIntBits((float) (vertex.z() / 16.0));
        data[offset + 3] = -1;
        // Em torno do centro: 0,75 usa os doze dezesseis avos do meio da imagem, e nao um canto.
        float u = 0.5f + ((float) vertex.u() - 0.5f) * uvScale;
        float v = 0.5f + ((float) vertex.v() - 0.5f) * uvScale;
        data[offset + 4] = Float.floatToRawIntBits(sprite.getFrameU(u));
        data[offset + 5] = Float.floatToRawIntBits(sprite.getFrameV(v));
        data[offset + 6] = 0;
        data[offset + 7] = normal;
    }

    /**
     * O lado do bloco para o qual a face aponta.
     *
     * <p>É a normal geométrica arredondada para uma das seis direções. O jogo usa esse lado para
     * iluminar; um valor errado não some com a face, mas deixa o desenho com sombras que não
     * combinam com a forma.
     */
    private static Direction dominantSide(ObjModel.Vertex a, ObjModel.Vertex b, ObjModel.Vertex c) {
        double ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z();
        double vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();

        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;

        double ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
        if (ay >= ax && ay >= az) return ny >= 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return nx >= 0 ? Direction.EAST : Direction.WEST;
        return nz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int packNormal(Direction direction) {
        int x = (int) (direction.getOffsetX() * 127) & 0xFF;
        int y = (int) (direction.getOffsetY() * 127) & 0xFF;
        int z = (int) (direction.getOffsetZ() * 127) & 0xFF;
        return x | (y << 8) | (z << 16);
    }

    /**
     * A malha pronta para desenhar.
     *
     * <p>Todos os quads saem em {@code getQuads(state, null, random)} e nenhum é cortado por lado.
     * Cortar exigiria saber que uma face cobre exatamente aquele lado do bloco, e uma malha
     * arbitrária não dá essa garantia: a face de um cano fica no meio do cubo, e escondê-la porque
     * há um vizinho abriria buracos no desenho.
     */
    private record ObjBakedModel(List<BakedQuad> quads, Sprite particle) implements BakedModel {
        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction face,
                                        Random random) {
            return face == null ? quads : List.of();
        }

        @Override
        public boolean useAmbientOcclusion() {
            return true;
        }

        @Override
        public boolean hasDepth() {
            return false;
        }

        @Override
        public boolean isSideLit() {
            return true;
        }

        @Override
        public boolean isBuiltin() {
            return false;
        }

        @Override
        public Sprite getParticleSprite() {
            return particle;
        }

        @Override
        public ModelTransformation getTransformation() {
            return ModelTransformation.NONE;
        }

        @Override
        public ModelOverrideList getOverrides() {
            return ModelOverrideList.EMPTY;
        }
    }
}

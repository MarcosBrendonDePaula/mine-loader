package dev.lualoader.neoforge.client;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonObject;
import dev.lualoader.content.ObjModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.jetbrains.annotations.Nullable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Desenha blocos cujo modelo é um arquivo OBJ, no NeoForge.
 *
 * <p><b>Por que não o leitor de OBJ do NeoForge.</b> Ele existe e funcionaria — e é justamente por
 * isso que não serve aqui. As regras de escala, de coordenada de textura e de normal dele são as
 * dele, e o Fabric não tem nada equivalente: o mesmo arquivo produziria dois desenhos diferentes, e
 * a matriz de compatibilidade diria "sim" nos dois lados mentindo. Ele também não sabe recortar o
 * modelo pelos grupos que o manifesto declara, que é o que faz um catálogo de peças virar um cano.
 *
 * <p>Então as duas plataformas leem o arquivo pelo mesmo {@link ObjModel}, no núcleo, e cada uma só
 * converte a mesma lista de faces para os quads dela.
 *
 * <p>A porta de entrada é diferente, e isso é da plataforma: no Fabric o modelo é interceptado pelo
 * id antes de o JSON ser lido; aqui o jogo entrega o JSON a quem o campo {@code loader} apontar.
 */
public final class NeoForgeObjModels {
    private NeoForgeObjModels() {
    }

    /** O nome pelo qual o manifesto pede este leitor, escrito pelo pacote gerado. */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("lua_loader", "obj");

    public static void install(IEventBus modBus) {
        // O tipo escrito por extenso, e nao uma referencia de metodo: o compilador nao infere o
        // generico de IGeometryLoader a partir dela, e a mensagem que sai nao aponta para a causa.
        net.neoforged.neoforge.client.model.geometry.IGeometryLoader<ObjGeometry> leitor =
                NeoForgeObjModels::read;

        modBus.addListener((ModelEvent.RegisterGeometryLoaders event) ->
                event.register(ID, leitor));
    }

    /**
     * Lê a declaração da peça: qual malha abrir e quais grupos desenhar.
     *
     * <p>A malha em si não é lida aqui. Este passo acontece antes de o pacote de recursos estar
     * pronto para leitura arbitrária, e guardar só a declaração deixa a leitura para o momento do
     * {@code bake}, quando o atlas já existe.
     */
    private static ObjGeometry read(JsonObject json, JsonDeserializationContext context) {
        String objRef = json.has("lua_obj") ? json.get("lua_obj").getAsString() : null;

        List<String> grupos = new ArrayList<>();
        if (json.has("lua_obj_groups")) {
            json.getAsJsonArray("lua_obj_groups").forEach(e -> grupos.add(e.getAsString()));
        }

        float uvScale = json.has("lua_obj_uv_scale")
                ? json.get("lua_obj_uv_scale").getAsFloat()
                : 1f;

        boolean doubleSided = json.has("lua_obj_double_sided")
                && json.get("lua_obj_double_sided").getAsBoolean();

        float expand = json.has("lua_obj_expand") ? json.get("lua_obj_expand").getAsFloat() : 1f;

        double[] recorte = null;
        if (json.has("lua_obj_clip")) {
            var caixa = json.getAsJsonArray("lua_obj_clip");
            if (caixa.size() == 6) {
                recorte = new double[6];
                for (int i = 0; i < 6; i++) recorte[i] = caixa.get(i).getAsDouble();
            }
        }

        ResourceLocation textura = null;
        if (json.has("textures")) {
            JsonObject textures = json.getAsJsonObject("textures");
            for (String slot : List.of("all", "particle")) {
                if (textures.has(slot)) {
                    textura = ResourceLocation.tryParse(textures.get(slot).getAsString());
                    if (textura != null) break;
                }
            }
        }
        if (textura == null) textura = ResourceLocation.withDefaultNamespace("block/stone");

        return new ObjGeometry(objRef, List.copyOf(grupos), uvScale, doubleSided, recorte, expand,
                textura);
    }

    /** A declaração, esperando o momento de virar malha. */
    private record ObjGeometry(@Nullable String objRef, List<String> groups, float uvScale,
                               boolean doubleSided, double @Nullable [] clip, float expand,
                               ResourceLocation texture)
            implements IUnbakedGeometry<ObjGeometry> {

        @Override
        public BakedModel bake(IGeometryBakingContext context, ModelBaker baker,
                               java.util.function.Function<Material, TextureAtlasSprite> sprites,
                               ModelState state, ItemOverrides overrides) {
            TextureAtlasSprite sprite = sprites.apply(
                    new Material(TextureAtlas.LOCATION_BLOCKS, texture));

            // As transformacoes de exibicao vem do contexto, que ja as resolveu pelo parent do
            // JSON. Sem elas o item sai do tamanho errado na mao, no inventario e na moldura.
            var transforms = context.getTransforms();

            if (objRef == null) return new ObjBakedModel(List.of(), sprite, transforms);

            ResourceLocation parsed = ResourceLocation.tryParse(objRef);
            if (parsed == null) return new ObjBakedModel(List.of(), sprite, transforms);

            // "logistica:block/cano.obj" aponta para "assets/logistica/models/block/cano.obj".
            ResourceLocation objId = ResourceLocation.fromNamespaceAndPath(
                    parsed.getNamespace(), "models/" + parsed.getPath());

            var resource = Minecraft.getInstance().getResourceManager().getResource(objId);
            if (resource.isEmpty()) {
                dev.lualoader.neoforge.NeoForgeLuaLoader.LOGGER.warn("Malha {} nao existe no pacote", objId);
                return new ObjBakedModel(List.of(), sprite, transforms);
            }

            try (var stream = resource.get().open();
                 var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {

                // Escala primeiro, recorte depois -- a mesma ordem do outro adaptador. Ao
                // contrario, cada peca seria escalada pela propria caixa e sairia fora do lugar.
                ObjModel model = ObjModel.read(reader).normalized().filtered(groups);
                // Recorte antes de duplicar, como no outro adaptador: dobrar primeiro so faria o
                // recorte percorrer o dobro do trabalho.
                if (clip != null) {
                    model = model.clipped(clip[0], clip[1], clip[2], clip[3], clip[4], clip[5]);
                }
                if (expand != 1f) model = model.expanded(expand);
                if (doubleSided) model = model.doubleSided();

                List<BakedQuad> quads = new ArrayList<>(model.faces().size());
                for (ObjModel.Face face : model.faces()) {
                    BakedQuad quad = quadOf(face, sprite, uvScale);
                    if (quad != null) quads.add(quad);
                }

                dev.lualoader.neoforge.NeoForgeLuaLoader.LOGGER.info("Modelo OBJ {}: {} face(s), textura {}{}",
                        objId, quads.size(), texture,
                        groups.isEmpty() ? "" : " (grupos " + groups + ")");
                return new ObjBakedModel(List.copyOf(quads), sprite, transforms);
            } catch (Exception error) {
                // Sem derrubar o jogo: a declaracao tem um parent de cubo como reserva.
                dev.lualoader.neoforge.NeoForgeLuaLoader.LOGGER.warn("Malha {} nao pode ser lida: {}",
                        objId, error.getMessage());
                return new ObjBakedModel(List.of(), sprite, transforms);
            }
        }
    }

    /**
     * Uma face da malha virando um quad do jogo.
     *
     * <p>Mesma conversão do adaptador Fabric, e precisa continuar sendo: o formato do vértice é o
     * do próprio Minecraft, e é o que garante que o mesmo arquivo desenhe igual nos dois lados.
     */
    @Nullable
    private static BakedQuad quadOf(ObjModel.Face face, TextureAtlasSprite sprite, float uvScale) {
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

        return new BakedQuad(data, -1, direction, sprite, true);
    }

    private static void putVertex(int[] data, int offset, ObjModel.Vertex vertex,
                                  TextureAtlasSprite sprite, int normal, float uvScale) {
        // As coordenadas do modelo estao em dezesseis avos, e o jogo desenha o bloco de 0 a 1.
        data[offset] = Float.floatToRawIntBits((float) (vertex.x() / 16.0));
        data[offset + 1] = Float.floatToRawIntBits((float) (vertex.y() / 16.0));
        data[offset + 2] = Float.floatToRawIntBits((float) (vertex.z() / 16.0));
        data[offset + 3] = -1;

        // Em torno do centro: 0,75 usa os doze dezesseis avos do meio da imagem, e nao um canto.
        float u = 0.5f + ((float) vertex.u() - 0.5f) * uvScale;
        float v = 0.5f + ((float) vertex.v() - 0.5f) * uvScale;
        data[offset + 4] = Float.floatToRawIntBits(sprite.getU(u));
        data[offset + 5] = Float.floatToRawIntBits(sprite.getV(v));
        data[offset + 6] = 0;
        data[offset + 7] = normal;
    }

    /** O lado do bloco para o qual a face aponta, usado pelo jogo para iluminar. */
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
        int x = (int) (direction.getStepX() * 127) & 0xFF;
        int y = (int) (direction.getStepY() * 127) & 0xFF;
        int z = (int) (direction.getStepZ() * 127) & 0xFF;
        return x | (y << 8) | (z << 16);
    }

    /**
     * A malha pronta para desenhar.
     *
     * <p>Todos os quads saem sem lado, e nenhum é cortado por vizinho: uma malha arbitrária não
     * garante que uma face cubra exatamente aquele lado do bloco, e escondê-la abriria buracos.
     */
    private record ObjBakedModel(List<BakedQuad> quads, TextureAtlasSprite particle,
                                 net.minecraft.client.renderer.block.model.ItemTransforms transforms)
            implements BakedModel {

        @Override
        public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() {
            return transforms;
        }

        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                        RandomSource random) {
            return side == null ? quads : List.of();
        }

        /**
         * Sem oclusao de ambiente.
         *
         * <p>O calculo de AO parte do principio de que a face esta encostada na parede do cubo, e
         * usa os blocos vizinhos para escurecer os cantos. Numa malha isso nao vale: a face de um
         * cano fica no meio do bloco, e o resultado e ela escurecer ate ficar preta.
         *
         * <p>Os dois renderizadores erram diferente com AO ligado -- o do Fabric passa pelo Indigo,
         * o do NeoForge pelo caminho vanilla --, e foi assim que o mesmo modelo apareceu certo numa
         * plataforma e preto na outra. Desligar deixa as duas iguais, que e o ponto de o leitor
         * morar no nucleo.
         */
        @Override
        public boolean useAmbientOcclusion() {
            return false;
        }

        @Override
        public boolean isGui3d() {
            return true;
        }

        @Override
        public boolean usesBlockLight() {
            return true;
        }

        @Override
        public boolean isCustomRenderer() {
            return false;
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return particle;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }
}

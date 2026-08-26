package dev.lualoader.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lualoader.content.ObjModel;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.Renderer;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.fabric.api.renderer.v1.material.RenderMaterial;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.api.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
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
        // Item tambem: o modelo do item nao pode HERDAR da malha -- o jogo exige que o pai de um
        // modelo JSON seja outro JSON, e com a malha como pai o cliente nao abre. Mas ser a malha
        // ele pode, porque ai nao ha heranca nenhuma.
        if (!id.getPath().startsWith("block/") && !id.getPath().startsWith("item/")) return null;

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
            // O recorte por regiao vem antes de duplicar as faces: dobrar primeiro so faria o
            // recorte percorrer o dobro do trabalho para chegar ao mesmo resultado.
            if (declaracao.has("lua_obj_clip")) {
                var caixa = declaracao.getAsJsonArray("lua_obj_clip");
                if (caixa.size() == 6) {
                    model = model.clipped(
                            caixa.get(0).getAsDouble(), caixa.get(1).getAsDouble(),
                            caixa.get(2).getAsDouble(), caixa.get(3).getAsDouble(),
                            caixa.get(4).getAsDouble(), caixa.get(5).getAsDouble());
                }
            }

            // Inflar antes de duplicar, pela mesma razao do recorte.
            if (declaracao.has("lua_obj_expand")) {
                model = model.expanded(declaracao.get("lua_obj_expand").getAsDouble());
            }

            if (declaracao.has("lua_obj_double_sided")
                    && declaracao.get("lua_obj_double_sided").getAsBoolean()) {
                model = model.doubleSided();
            }
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
        /**
         * O modelo de bloco padrao, do qual saem as transformacoes de exibicao.
         *
         * <p>Sem elas o item aparece do tamanho errado e fora de lugar na mao, no inventario e na
         * moldura -- um modelo JSON as herda do pai, e a malha nao tem pai.
         */
        private static final Identifier BLOCO_PADRAO = Identifier.ofVanilla("block/block");

        @Override
        public Collection<Identifier> getModelDependencies() {
            return List.of(BLOCO_PADRAO);
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

            Mesh mesh = meshOf(model, sprite, uvScale);

            // Os quads soltos existem so para quem pergunta pelo caminho antigo -- a tela desenha
            // pelo Mesh. Convertidos, eles perdem o material, e com ele a decisao de nao aplicar
            // sombreamento difuso.
            List<BakedQuad> quads = new ArrayList<>();
            mesh.forEach(quad -> quads.add(quad.toBakedQuad(sprite)));

            LuaLoaderClient.LOGGER.info(
                    "Malha assada: {} quad(s), sprite {}, caixa x {}..{} y {}..{} z {}..{}",
                    quads.size(), sprite.getContents().getId(),
                    model.minX(), model.maxX(), model.minY(), model.maxY(),
                    model.minZ(), model.maxZ());

            return new ObjBakedModel(mesh, List.copyOf(quads), sprite,
                    transformsOf(baker, settings));
        }

        /** As transformacoes do bloco padrao, ou nenhuma se ele nao puder ser assado. */
        private static ModelTransformation transformsOf(Baker baker, ModelBakeSettings settings) {
            try {
                BakedModel bloco = baker.bake(BLOCO_PADRAO, settings);
                return bloco == null ? ModelTransformation.NONE : bloco.getTransformation();
            } catch (RuntimeException error) {
                // Um item mal posicionado e melhor que um cliente que nao abre.
                LuaLoaderClient.LOGGER.warn("Sem as transformacoes do bloco padrao: {}",
                        error.getMessage());
                return ModelTransformation.NONE;
            }
        }
    }

    /**
     * A malha virando quads, pela API de emissao da plataforma.
     *
     * <p><b>Por que nao montar o vertice a mao.</b> A primeira versao escrevia o {@code int[32]} do
     * vertice diretamente -- posicao, cor, uv, luz e normal, na ordem certa e no formato certo. O
     * caminho vanilla tolera um campo mal preenchido; o renderizador do Fabric nao, e o resultado
     * era o mesmo modelo aparecendo bem numa plataforma e quebrado na outra.
     *
     * <p>O leitor de OBJ do NeoForge nao monta o array: ele preenche campo a campo por uma API que
     * conhece o formato. Aqui se faz o mesmo com o equivalente do Fabric -- a ideia e a mesma, o
     * codigo e o daqui.
     *
     * <p>A <b>normal</b> e a da geometria, calculada por face, e nao a direcao do cubo mais
     * parecida. E ela que da volume ao desenho: aproximar para um dos seis lados achata a malha na
     * iluminacao, e um cano redondo passa a parecer uma caixa.
     */
    private static Mesh meshOf(ObjModel model, Sprite sprite, float uvScale) {
        // Sem renderizador registrado nao ha como emitir, e o modelo fica sem quads. So acontece
        // se a Rendering API nao estiver presente -- caso em que a malha nao seria desenhada de
        // qualquer forma.
        if (!RendererAccess.INSTANCE.hasRenderer()) {
            LuaLoaderClient.LOGGER.warn("Sem renderizador da Rendering API; a malha nao sera desenhada");
            return null;
        }

        Renderer renderer = RendererAccess.INSTANCE.getRenderer();

        /*
         * Sem sombreamento difuso, e sem oclusao de ambiente.
         *
         * As duas contas partem do principio de que a face esta encostada na parede do cubo -- e
         * numa malha ela fica no meio do bloco. Pior: desenhar cada face tambem pelo avesso
         * (`double_sided`) cria faces com a normal apontando para dentro, e o difuso as ilumina
         * como se estivessem viradas para o lado errado. Elas ficam PRETAS, e sao justamente as que
         * aparecem de certos angulos.
         *
         * E a diferenca entre as duas plataformas: o caminho vanilla do NeoForge sombreia pela
         * direcao do quad, e o renderizador do Fabric pela normal do vertice. O mesmo modelo saia
         * bem la e preto aqui.
         */
        RenderMaterial material = renderer.materialFinder()
                .disableDiffuse(true)
                .ambientOcclusion(TriState.FALSE)
                .find();

        var builder = renderer.meshBuilder();
        QuadEmitter emitter = builder.getEmitter();

        for (ObjModel.Face face : model.faces()) {
            List<ObjModel.Vertex> vertices = face.vertices();
            if (vertices.size() < 3) continue;

            float[] normal = normalOf(vertices.get(0), vertices.get(1), vertices.get(2));

            for (int index = 0; index < 4; index++) {
                // Triangulo vira quad com o ultimo vertice repetido: o jogo desenha quads, e
                // descartar as faces de tres lados jogaria fora a maior parte de um OBJ exportado.
                ObjModel.Vertex vertex = vertices.get(Math.min(index, vertices.size() - 1));

                emitter.pos(index,
                        (float) (vertex.x() / 16.0),
                        (float) (vertex.y() / 16.0),
                        (float) (vertex.z() / 16.0));

                // Em torno do centro: 0,75 usa os doze dezesseis avos do meio da imagem.
                emitter.uv(index,
                        0.5f + ((float) vertex.u() - 0.5f) * uvScale,
                        0.5f + ((float) vertex.v() - 0.5f) * uvScale);

                emitter.color(index, -1);
                emitter.normal(index, normal[0], normal[1], normal[2]);
            }

            // Sem cullFace: uma malha nao garante que a face cubra exatamente um lado do bloco -- a
            // face de um cano fica no meio do cubo, e esconde-la por causa de um vizinho abriria
            // buracos no desenho.
            emitter.material(material);
            emitter.cullFace(null);
            emitter.spriteBake(sprite, MutableQuadView.BAKE_NORMALIZED);
            emitter.emit();
        }

        return builder.build();
    }

    /**
     * A normal da face, pela geometria.
     *
     * <p>Normalizada, porque a iluminacao espera um vetor de comprimento um; uma face grande daria
     * um vetor grande, e o desenho sairia claro ou escuro demais conforme o tamanho da peca.
     */
    private static float[] normalOf(ObjModel.Vertex a, ObjModel.Vertex b, ObjModel.Vertex c) {
        double ux = b.x() - a.x(), uy = b.y() - a.y(), uz = b.z() - a.z();
        double vx = c.x() - a.x(), vy = c.y() - a.y(), vz = c.z() - a.z();

        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;

        double tamanho = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (tamanho < 1.0e-6) return new float[]{0, 1, 0};

        return new float[]{(float) (nx / tamanho), (float) (ny / tamanho), (float) (nz / tamanho)};
    }

    /**
     * A malha pronta para desenhar.
     *
     * <p>Todos os quads saem em {@code getQuads(state, null, random)} e nenhum é cortado por lado.
     * Cortar exigiria saber que uma face cobre exatamente aquele lado do bloco, e uma malha
     * arbitrária não dá essa garantia: a face de um cano fica no meio do cubo, e escondê-la porque
     * há um vizinho abriria buracos no desenho.
     */
    private record ObjBakedModel(@Nullable Mesh mesh, List<BakedQuad> quads, Sprite particle,
                                 ModelTransformation transformation) implements BakedModel {

        /**
         * Desenha pela malha, e nao pelos quads soltos.
         *
         * <p><b>E o que faz o material valer.</b> Um quad convertido perde o material -- e com ele
         * a decisao de nao aplicar sombreamento difuso, que e o que escurecia ate o preto as faces
         * desenhadas pelo avesso. Enquanto o modelo se dizia "adaptador do caminho antigo", o
         * renderizador montava a malha por conta propria a partir dos quads, e a configuracao era
         * jogada fora antes de chegar na tela.
         */
        @Override
        public boolean isVanillaAdapter() {
            return false;
        }

        @Override
        public void emitBlockQuads(net.minecraft.world.BlockRenderView view, BlockState state,
                                   net.minecraft.util.math.BlockPos pos,
                                   java.util.function.Supplier<Random> random,
                                   net.fabricmc.fabric.api.renderer.v1.render.RenderContext context) {
            if (mesh != null) context.meshConsumer().accept(mesh);
        }

        @Override
        public void emitItemQuads(net.minecraft.item.ItemStack stack,
                                  java.util.function.Supplier<Random> random,
                                  net.fabricmc.fabric.api.renderer.v1.render.RenderContext context) {
            if (mesh != null) context.meshConsumer().accept(mesh);
        }
        @Override
        public List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction face,
                                        Random random) {
            return face == null ? quads : List.of();
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

        /**
         * O item tem profundidade: na mao e no inventario ele e desenhado como objeto, e nao como
         * figura chapada.
         *
         * <p>Este metodo e o {@code isGui3d} do outro mapeamento, e estava {@code false} aqui e
         * {@code true} no NeoForge -- o mesmo modelo virava um adesivo numa plataforma e um objeto
         * na outra. Uma malha sempre tem profundidade; quem nao tem e uma textura de item comum.
         */
        @Override
        public boolean hasDepth() {
            return true;
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
            return transformation;
        }

        @Override
        public ModelOverrideList getOverrides() {
            return ModelOverrideList.EMPTY;
        }
    }
}

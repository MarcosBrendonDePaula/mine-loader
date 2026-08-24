package dev.lualoader.manifest;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modelo de dados do manifesto mod.json. O Gson usa LOWER_CASE_WITH_UNDERSCORES. */
public final class ModManifest {
    public int schema;
    public String id;
    public String name;
    public String version;
    public String description;
    public String entrypoint;
    public List<String> authors = new ArrayList<>();
    public List<String> permissions = new ArrayList<>();
    public Map<String, String> events = new LinkedHashMap<>();
    public List<BlockDefinition> blocks = new ArrayList<>();
    public List<ItemEntryDefinition> items = new ArrayList<>();
    public CreativeTabDefinition creativeTab;
    /** Recursos nomeados do mod, referenciados no resto do manifesto por {@code "@nome"}. */
    public Map<String, ResourceDefinition> resources = new LinkedHashMap<>();
    public List<StructureDefinition> structures = new ArrayList<>();
    public List<RecipeDefinition> recipes = new ArrayList<>();
    /**
     * Mods necessarios para este funcionar, no formato {@code id -> versao minima}.
     *
     * <p>Uma dependencia declarada garante duas coisas: o mod so carrega se ela existir, e ela
     * carrega antes dele, para que {@code mod.require} ja encontre a API pronta.
     */
    public Map<String, String> dependencies = new LinkedHashMap<>();

    /**
     * De onde buscar cada dependencia, quando ela nao estiver instalada.
     *
     * <p>Campo separado de {@link #dependencies} de proposito. A dependencia diz <em>o que</em> o
     * mod precisa e continua valendo sem endereco nenhum -- e o contrato. A origem diz apenas
     * <em>onde achar</em>, e um mod distribuido de outro jeito pode nao ter uma. Juntar os dois num
     * campo so obrigaria todo mod a declarar um endereco que nem sempre existe.
     *
     * <p>Um endereco aqui nao instala nada sozinho: so vale quando quem administra o servidor
     * ligou a instalacao automatica. Ver {@code InstallPolicy}.
     */
    public Map<String, String> dependencySources = new LinkedHashMap<>();

    /**
     * Endereco base para resolver caminhos relativos que nao existam no disco.
     *
     * <p>Permite publicar um mod inteiro na web e instala-lo com um manifesto de poucas linhas: o
     * loader procura cada arquivo primeiro localmente e, se nao achar, busca sob esta base. Sem
     * isso, um pedaco importado por URL nao conseguiria referenciar os proprios scripts e
     * texturas, porque os caminhos declarados nele apontam para a pasta do mod de origem.
     */
    /**
     * Quem precisa ter este mod instalado: {@code server} ou {@code both}.
     *
     * <p>Existe por causa de quem entra num servidor. O Lua roda so no servidor, e a tela vai como
     * dados -- entao um mod de comando, evento, menu ou tela funciona para quem entrou sem ter
     * baixado nada. Ja um bloco declarado precisa estar registrado nos dois lados, ou a
     * sincronizacao de registro do jogo recusa a conexao.
     *
     * <p><b>Nao existe {@code client}</b>, e a ausencia e deliberada: nenhum script roda no
     * cliente hoje, entao o valor nao teria efeito nenhum. Um campo aceito e ignorado e pior que um
     * campo ausente -- o ausente da erro, o ignorado da silencio.
     *
     * <p>Vazio significa "deduza": um mod que registra bloco ou item e {@code both}, e o resto e
     * {@code server}. Deduzir e o padrao porque a resposta ja esta no manifesto, e pedi-la de novo
     * so criaria a chance de as duas discordarem.
     */
    public String side;

    public String remoteBase;
    public boolean enabled = true;

    public static final class BlockDefinition {
        public String id;
        public String name;
        public String type = "generic";
        public String base;
        public MaterialDefinition material = new MaterialDefinition();
        public SettingsDefinition settings = new SettingsDefinition();
        public StateDefinition state = new StateDefinition();
        public ShapeDefinition shape = new ShapeDefinition();
        public PlacementDefinition placement = new PlacementDefinition();
        public RenderDefinition render = new RenderDefinition();
        public LootDefinition loot = new LootDefinition();
        public List<String> tags = new ArrayList<>();
        public ItemDefinition item = new ItemDefinition();
        public BehaviorDefinition behavior = new BehaviorDefinition();
        /** Fixa a versao dos scripts remotos declarados em {@code behavior}. Opcional. */
        public String behaviorSha256;
        /** Quando verdadeiro, o bloco guarda dados proprios em cada posicao do mundo. */
        public boolean blockData = false;
        /** Inventario proprio do bloco. Nulo quando o bloco nao guarda itens. */
        public InventoryDefinition inventory;
    }

    /**
     * Um inventario que vive no bloco, e nao no jogador.
     *
     * <p>E o que separa um bloco decorativo de uma maquina: um bau customizado, uma fornalha de mod
     * ou um tanque precisam de itens presos aquela posicao do mundo, que sobrevivem a sair e voltar
     * e caem no chao quando o bloco quebra.
     *
     * <p>As permissoes de lado existem para tubos e funis. Um bloco que aceita insercao automatica
     * mas recusa extracao e a diferenca entre uma caixa de entrada e um bau comum, e nao ha como
     * expressar isso sem dizer o que cada lado permite.
     */
    public static final class InventoryDefinition {
        /** Quantos slots. Vira linhas de nove na tela, ate seis. */
        public int size = 27;
        /** Titulo da janela; o nome do bloco quando ausente. */
        public String title;
        /** Se clicar no bloco abre o inventario. Um mod que prefere reagir no script desliga. */
        public boolean openOnUse = true;
        /** Se maquinas e funis podem inserir itens. */
        public boolean allowInsert = true;
        /** Se maquinas e funis podem retirar itens. */
        public boolean allowExtract = true;
        /** Se o conteudo cai no chao quando o bloco e quebrado. */
        public boolean dropOnBreak = true;
    }

    public static final class MaterialDefinition {
        public String mapColor = "stone";
        public String sound = "stone";
        public String instrument = "harp";
        public String pistonBehavior = "normal";
        public boolean burnable = false;
        public int flammability = 0;
        public int burnSpread = 0;
        public boolean replaceable = false;
        public boolean liquid = false;
        public boolean air = false;
        public boolean solid = true;
        public boolean opaque = true;
    }

    public static final class SettingsDefinition {
        public float hardness = 1.0f;
        public float resistance = 1.0f;
        public boolean requiresTool = false;
        public boolean collidable = true;
        public boolean noCollision = false;
        public boolean randomTicks = false;
        public int luminance = 0;
        public float slipperiness = 0.6f;
        public float velocityMultiplier = 1.0f;
        public float jumpVelocityMultiplier = 1.0f;
        public boolean blockBreakParticles = true;
        public boolean dynamicBounds = false;
        public boolean solid = true;
        public boolean nonOpaque = false;
        public boolean breakInstantly = false;
        public String offset = "none";
        public boolean dropsNothing = false;
        public String dropsLike;
    }

    public static final class StateDefinition {
        public List<StatePropertyDefinition> properties = new ArrayList<>();
        @SerializedName("default")
        public Map<String, String> defaults = new LinkedHashMap<>();
    }

    public static final class StatePropertyDefinition {
        public String name;
        public String type = "string";
        public List<String> values = new ArrayList<>();
    }

    public static final class ShapeDefinition {
        public String collision = "full_cube";
        public String outline = "full_cube";
        /**
         * A silhueta desenhada. Nulo por padrao, e nao "full_cube": e o que permite herdar o
         * contorno quando so ele foi declarado. Um bloco que diz ser uma mesa para andar em cima
         * precisa parecer uma mesa, e exigir a repeticao do nome so multiplicaria a chance de os
         * dois divergirem.
         */
        public String visual;
        public boolean dynamic = false;
        /**
         * Caixas proprias, cada uma como {@code [x1, y1, z1, x2, y2, z2]} em unidades de bloco.
         *
         * <p>Para o que os nomes prontos nao cobrem. Quando presente, tem prioridade sobre o nome.
         */
        public List<List<Float>> boxes = new ArrayList<>();
    }

    public static final class PlacementDefinition {
        public boolean canReplace = false;
        public boolean canPlaceAt = true;
        public String facing = "none";
        public boolean waterloggable = false;
        public boolean rotateWithPlayer = false;
    }

    public static final class RenderDefinition {
        /**
         * O modelo desenhado.
         *
         * <p>Um nome como {@code cube_all} descreve a intencao e deixa o loader gerar; uma
         * referencia como {@code "@altar"} aponta para um arquivo de modelo declarado em
         * {@code resources}, e e por onde entra um desenho feito no Blockbench.
         */
        public String model = "cube_all";
        public TextureDefinition texture = new TextureDefinition();
        /**
         * As texturas que o modelo referencia por nome interno.
         *
         * <p>Um modelo do Blockbench nomeia as proprias texturas -- {@code tampo}, {@code pe} -- e
         * este mapa liga cada nome a um recurso. E o que permite dois blocos compartilharem o mesmo
         * desenho com imagens diferentes, e o que torna o mapeamento uma declaracao em vez de uma
         * conversao escondida no montador.
         */
        public Map<String, TextureDefinition> textures = new LinkedHashMap<>();
        public Map<String, TextureDefinition> variantTextures = new LinkedHashMap<>();
        public String renderLayer = "solid";
        public boolean translucent = false;
        public boolean cutout = false;
        public boolean emissive = false;
        public String tint;
    }

    public static final class TextureDefinition {
        public String source = "local";
        public String path;
        public String url;
        public String sha256;
        public long maxBytes = 1_048_576;
        public String fallback = "minecraft:block/stone";
        /**
         * Nome de um recurso declarado em {@code resources}, sem o arroba.
         *
         * <p>Preenchido quando o manifesto escreve {@code "texture": "@cristal"}. Quando presente,
         * os demais campos vem do recurso e o que estiver aqui e ignorado.
         */
        public String ref;
    }

    /**
     * Um recurso nomeado, declarado uma vez e referenciado onde for preciso.
     *
     * <p>Antes cada recurso era declarado no lugar em que era usado, o que trazia tres problemas de
     * uma vez: dez blocos com a mesma textura repetiam a declaracao dez vezes; a integridade nao
     * tinha onde morar junto do recurso, e acabou num campo paralelo -- {@code behaviorSha256},
     * duplicado em bloco e item; e nao havia como listar o que o mod precisa baixar antes de entrar
     * no mundo.
     *
     * <p>O tipo nao e enfeite: cada um valida de um jeito -- dimensao para imagem, JSON para modelo,
     * sandbox para script. O que nao muda e a resolucao: local ou remoto, cache e integridade sao
     * os mesmos para todos.
     */
    public static final class ResourceDefinition {
        /** {@code image}, {@code model}, {@code sound}, {@code script} ou {@code data}. */
        public String type = "image";
        /**
         * Onde o recurso esta: um caminho dentro do mod ou um endereco http.
         *
         * <p>Um campo so, e nao {@code path} mais {@code url}: o prefixo ja diz qual dos dois e, e
         * dois campos permitiriam declarar os dois e deixar a duvida sobre qual vale.
         */
        public String from;
        /** Conferido apos baixar. Fica junto do recurso, e nao num campo separado. */
        public String sha256;
        public long maxBytes = 1_048_576;
        /**
         * O que usar quando o recurso nao puder ser carregado.
         *
         * <p>Fica aqui porque quase sempre e o mesmo para todos os usos daquela imagem, e repeti-lo
         * em cada referencia devolveria parte da verborragia que os recursos eliminam. Quem
         * referencia ainda pode declarar o seu, e nesse caso o dele vale.
         */
        public String fallback;
    }

    /** Se quem entra num servidor precisa ter este mod instalado tambem. */
    public boolean requiresClient() {
        return "both".equals(effectiveSide());
    }

    /**
     * O lado declarado, ou o deduzido quando o manifesto nao diz.
     *
     * <p>Registrar conteudo e o que obriga o cliente a ter o mod; o resto atravessa a rede como
     * dados.
     */
    public String effectiveSide() {
        if (side != null && !side.isBlank()) return side.trim().toLowerCase(java.util.Locale.ROOT);

        boolean hasContent = (blocks != null && !blocks.isEmpty())
                || (items != null && !items.isEmpty());
        return hasContent ? "both" : "server";
    }

    public static final class LootDefinition {
        public String mode = "self";
        public String item;
        public int count = 1;
        public String table;
    }

    public static final class ItemDefinition {
        public boolean register = true;
        public int maxStackSize = 64;
        public int maxDamage = 0;
        public String rarity = "common";
        public boolean fireResistant = false;
    }

    /** Logica associada a um bloco. Cada campo aponta um arquivo .lua, uma URL ou uma funcao. */
    public static final class BehaviorDefinition {
        public String onUse;
        /** Jogador bateu no bloco, sem necessariamente quebra-lo. */
        public String onAttack;
        /** Nome antigo de {@link #onAttack}; mantido para nao quebrar mods ja escritos. */
        public String onBreak;
        public String onPlaced;
        public String onBroken;
        public String onRandomTick;
        public String onNeighborUpdate;
        /** Campo antigo, nunca implementado; use {@link #onPlaced}. */
        public String onPlace;
    }

    /** Item declarado pelo manifesto que nao pertence a um bloco. */
    public static final class ItemEntryDefinition {
        public String id;
        public String name;
        public int maxStackSize = 64;
        public int maxDamage = 0;
        public String rarity = "common";
        public boolean fireResistant = false;
        public TextureDefinition texture = new TextureDefinition();
        public String model = "item/generated";
        public ItemBehaviorDefinition behavior = new ItemBehaviorDefinition();
        /** Fixa a versao dos scripts remotos declarados em {@code behavior}. Opcional. */
        public String behaviorSha256;

        /** Faz do item uma ferramenta. Opcional. */
        public ToolDefinition tool;

        /** Faz do item uma peca de armadura. Opcional. */
        public ArmorDefinition armor;

        /**
         * Tags do jogo ou proprias em que o item entra.
         *
         * <p>Mesmo campo e mesmo formato do bloco, de proposito: quem escreve o mod ja aprendeu a
         * sintaxe em um e nao deveria reaprender no outro.
         */
        public List<String> tags = new ArrayList<>();
    }

    /**
     * Uma ferramenta: o que ela quebra bem, quanto dano faz e quanto aguenta.
     *
     * <p>Ferramenta era a categoria de mod mais obvia que o loader nao alcancava. Um item tinha
     * empilhamento, durabilidade nominal e textura, e nada disso faz uma picareta: sem nivel de
     * colheita ela nao quebra minerio, sem velocidade ela leva o mesmo tempo que a mao, e sem dano
     * ela e um enfeite.
     */
    public static final class ToolDefinition {
        /**
         * Que classe de bloco a ferramenta quebra bem: {@code pickaxe}, {@code axe},
         * {@code shovel}, {@code hoe} ou {@code sword}.
         *
         * <p>Define tambem que blocos ela consegue colher: uma picareta de ferro nao derruba
         * diamante por ser rapida, e sim por ter nivel suficiente.
         */
        public String type = "pickaxe";

        /**
         * Nivel de colheita, de zero a quatro.
         *
         * <p>Segue a escala do jogo -- madeira, pedra, ferro, diamante, netherita -- porque uma
         * escala propria obrigaria quem escreve o mod a traduzir mentalmente a cada bloco.
         */
        public int level = 1;

        /** Multiplicador de velocidade sobre os blocos da classe. */
        public double speed = 4.0;

        /** Dano somado ao ataque. */
        public double damage = 1.0;

        /** Quantos usos ate quebrar. Zero herda o {@code max_damage} do item. */
        public int durability = 0;

        /** Quao bem aceita encantamentos. */
        public int enchantability = 5;

        /** Item que conserta a ferramenta na bigorna. Opcional. */
        public String repairItem;
    }

    /**
     * Uma peca de armadura: onde veste e quanto protege.
     */
    public static final class ArmorDefinition {
        /** Onde a peca veste: {@code helmet}, {@code chestplate}, {@code leggings} ou {@code boots}. */
        public String slot = "chestplate";

        /** Pontos de protecao daquela peca. */
        public int protection = 2;

        /**
         * Resistencia a dano alto, como a netherita tem.
         *
         * <p>Zero para armaduras comuns: e o que separa uma armadura boa de uma de fim de jogo.
         */
        public double toughness = 0.0;

        /** Empurrao resistido, de zero a um. */
        public double knockbackResistance = 0.0;

        /** Quantos pontos de durabilidade. Zero usa o padrao da armadura de couro. */
        public int durability = 0;

        public int enchantability = 9;

        /** Item que conserta a peca na bigorna. Opcional. */
        public String repairItem;
    }

    /** Logica associada a um item. Cada campo aponta um arquivo .lua, uma URL ou uma funcao. */
    public static final class ItemBehaviorDefinition {
        /** Clique com o item na mao, sem alvo. */
        public String onUse;
        /** Clique com o item sobre um bloco. */
        public String onUseOnBlock;
    }

    /** Aba propria do mod no inventario criativo. */
    public static final class CreativeTabDefinition {
        public boolean register = true;
        public String id = "main";
        public String name;
        /** Item mostrado como icone da aba, no formato mod:item. Vazio usa o primeiro conteudo. */
        public String icon;
    }

    /**
     * Estrutura declarada como dados: uma paleta de simbolos e as camadas do desenho.
     *
     * <p>Cada entrada de {@code layers} e uma camada horizontal, da mais baixa para a mais alta.
     * Dentro de uma camada, cada string e uma linha no eixo Z, e cada caractere uma posicao no
     * eixo X. Um simbolo mapeado para {@code null} na paleta significa "nao tocar", preservando
     * o que ja existe no mundo.
     */
    public static final class StructureDefinition {
        public String id;
        public String name;
        /**
         * Referencia a um recurso do tipo {@code data} com um arquivo de estrutura do jogo.
         *
         * <p>Quando presente, a paleta e as camadas vem do arquivo em vez do manifesto: da para
         * construir dentro do Minecraft, salvar com o bloco de estrutura e distribuir junto do mod,
         * em vez de transcrever a construcao para texto.
         */
        public String from;
        /** {@code bottom_center} ancora no centro da base; {@code corner} ancora no canto minimo. */
        public String origin = "bottom_center";
        public Map<String, String> palette = new LinkedHashMap<>();
        public List<List<String>> layers = new ArrayList<>();
    }

    /**
     * Receita declarada pelo mod, gerada no data pack virtual.
     *
     * <p>{@code shaped} usa {@code pattern} e {@code key}; {@code shapeless} usa {@code ingredients}.
     */
    public static final class RecipeDefinition {
        public String id;
        public String type = "shaped";
        public List<String> pattern = new ArrayList<>();
        public Map<String, String> key = new LinkedHashMap<>();
        public List<String> ingredients = new ArrayList<>();
        public String result;
        public int count = 1;
        /** Grupo do livro de receitas, opcional. */
        public String group;

        /**
         * Tiques de queima, para os tipos de fornalha. Zero usa o padrao daquele tipo.
         *
         * <p>Fica aqui e nao num bloco proprio porque uma receita e uma so coisa: separar os
         * campos de queima obrigaria quem escreve a saber de antemao em qual metade o campo mora.
         */
        public int cookingTime = 0;

        /** Experiencia dada ao recolher, para os tipos de fornalha. */
        public double experience = 0.0;
    }
}

package dev.lualoader.manifest;

import com.google.gson.annotations.SerializedName;

import dev.lualoader.platform.EntityDefinition;

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
    /**
     * Scripts da fase de registro, no formato {@code evento -> arquivo .lua}.
     *
     * <p>Aponta um arquivo, como o {@code behavior} de um bloco, e nao uma funcao do entrypoint:
     * esta fase acontece antes de o jogo congelar os registros, e carregar o {@code main.lua} aqui
     * faria o topo dele executar duas vezes.
     */
    public Map<String, String> registration = new LinkedHashMap<>();

    /** Fixa a versao dos scripts remotos de {@link #registration}. Opcional. */
    public String registrationSha256;
    public List<BlockDefinition> blocks = new ArrayList<>();
    public List<ItemEntryDefinition> items = new ArrayList<>();
    public List<EntityDefinition> entities = new ArrayList<>();
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

    /**
     * Imagem do mod, para uma lista poder mostrar de que ele e a cara.
     *
     * <p>Um recurso declarado ({@code "@icone"}), um caminho no mod ou uma URL -- as mesmas tres
     * origens de qualquer imagem daqui. Ausente nao e erro: a lista desenha um lugar vazio, que e
     * melhor que um icone generico repetido em toda linha.
     *
     * <p>Fica na raiz do manifesto, e nao dentro de {@code resources}, porque nao e conteudo do
     * jogo: nenhum bloco ou item a usa, e ela existe so para quem esta olhando a lista de mods.
     */
    public String icon;

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

        /**
         * O nucleo de um bloco que conecta -- cano, cerca, muro, vidraca.
         *
         * <p>Esta sempre presente na forma final. Os bracos sao acrescentados a ele, um por lado
         * ligado.
         */
        public List<Float> core = new ArrayList<>();

        /**
         * O braco, desenhado apontando para o <b>norte</b>.
         *
         * <p>Uma direcao so, e o loader gira para as outras cinco. Declarar seis bracos daria seis
         * listas de numeros para manter em sincronia, e o primeiro ajuste esqueceria uma.
         */
        public List<Float> arm = new ArrayList<>();

        /**
         * O nucleo com mais de uma caixa, para quem precisa de detalhe.
         *
         * <p>Uma caixa so cobre cano, cerca e muro. Nao cobre o cano do Logistic Pipes, que tem
         * placas nas faces alem do miolo -- e esse foi o caso real que trouxe este campo. Quando
         * declarado, vence {@link #core}.
         */
        public List<List<Float>> cores = new ArrayList<>();

        /**
         * O braco com mais de uma caixa, tambem apontando para o <b>norte</b>.
         *
         * <p>Cada caixa gira junto com as outras, entao o conjunto se comporta como uma peca so. E
         * o que permite um braco ser tubo mais colar, em vez de um paralelepipedo -- a diferenca
         * entre parecer um cano e ser o cano do mod original.
         *
         * <p>Quando declarado, vence {@link #arm}.
         */
        public List<List<Float>> arms = new ArrayList<>();

        /**
         * A quem este bloco se conecta: ids de bloco, ou tags com {@code #}.
         *
         * <p>Vazio nao significa "a todos": significa que nada foi declarado, e o bloco nao conecta
         * a nada. Um padrao de "tudo" faria um cano crescer bracos em direcao a terra e pedra.
         */
        public List<String> connectsTo = new ArrayList<>();
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

        /**
         * Quais pecas de um modelo OBJ desenhar, conforme as conexoes do bloco.
         *
         * <p>Um OBJ de mod costuma ser um catalogo de pecas. Sem isto, o unico desenho possivel e o
         * catalogo inteiro -- um cano com as seis conexoes sempre abertas.
         */
        public ObjPartsDefinition objParts;
        public String renderLayer = "solid";
        public boolean translucent = false;
        public boolean cutout = false;
        public boolean emissive = false;
        public String tint;
    }

    /**
     * As pecas de um modelo OBJ, por condicao.
     *
     * <p>O vocabulario e o mesmo do bloco que conecta: sempre, quando aquele lado esta ligado, e
     * quando esta livre. E como o Logistic Pipes monta o cano dele -- miolo sempre, manga do lado
     * ligado, placa de textura do lado livre --, e e o que faz a textura do tipo de cano aparecer
     * so nas faces abertas.
     *
     * <p>Em {@code connected} e {@code disconnected}, {@code %s} vira a direcao em maiusculas:
     * {@code N}, {@code S}, {@code E}, {@code W}, {@code U}, {@code D}. E a nomenclatura que as
     * ferramentas de modelo usam, e a mesma do arquivo original.
     */
    public static final class ObjPartsDefinition {
        /** Pecas desenhadas em qualquer estado -- o miolo. */
        public List<ObjPartDefinition> core = new ArrayList<>();
        /** Pecas desenhadas no lado ligado, com {@code %s} pela direcao. */
        public List<ObjPartDefinition> connected = new ArrayList<>();
        /** Pecas desenhadas no lado livre, com {@code %s} pela direcao. */
        public List<ObjPartDefinition> disconnected = new ArrayList<>();
    }

    /**
     * Uma peca de modelo OBJ: quais grupos, e com que textura.
     *
     * <p><b>A textura e por peca, e nao por bloco.</b> Um modelo de malha costuma ter um atlas
     * proprio para o corpo -- com as coordenadas ja embutidas no arquivo -- e usar a textura que
     * identifica o bloco so em algumas faces. Pintar tudo com a mesma imagem faz o corpo perder o
     * desenho e virar uma mancha de cor.
     */
    /**
     * Uma peca. Cada condicao aceita <b>varias</b>, e nao uma so.
     *
     * <p>Uma condicao costuma precisar de mais de uma: a face livre de um cano leva a tampa, no
     * atlas do corpo, e o decalque que mostra o tipo, na imagem do bloco. Com uma peca por condicao
     * so daria para desenhar uma das duas -- e faltando a tampa a face fica aberta, com o interior
     * escuro aparecendo.
     */
    public static final class ObjPartDefinition {
        /** Nomes de grupo, ou prefixos deles. Com {@code %s} pela direcao onde faz sentido. */
        public List<String> groups = new ArrayList<>();

        /** A textura desta peca. Sem ela, vale a do bloco. */
        public TextureDefinition texture;

        /**
         * Encolhe a coordenada de textura em torno do centro.
         *
         * <p>Um valor de {@code 0.75} usa os doze dezesseis avos do meio da imagem. Serve para uma
         * placa mostrar so o miolo de uma textura de bloco, sem a borda -- e sem isso a imagem sai
         * esticada de canto a canto da peca.
         */
        public float uvScale = 1.0f;

        /**
         * Desenha cada face tambem pelo avesso.
         *
         * <p>Uma malha oca -- a manga de um cano sao quatro paredes finas -- perde metade das faces
         * conforme o angulo, porque o jogo so desenha a face pelo lado para o qual ela aponta. O
         * resultado parece um bloco com pedacos faltando.
         *
         * <p>Custa o dobro de faces, entao e declarado: um modelo macico nao ganha nada e pagaria
         * o dobro.
         */
        public boolean doubleSided = false;

        /**
         * Desenha so a parte da peca que cai nesta caixa, como {@code [x1,y1,z1,x2,y2,z2]}.
         *
         * <p>Declarada apontando para o <b>norte</b>, e girada pelo loader para os outros lados --
         * a mesma regra do braco. Serve quando o nome do grupo nao basta: num arquivo de verdade a
         * face de um cano e um mosaico de placas com nomes que a ferramenta gerou, sem lado nenhum
         * no nome, e a regiao e o que as separa.
         */
        public List<Float> keepWithin = new ArrayList<>();

        /**
         * Infla a peca a partir do centro do bloco.
         *
         * <p>Serve para um decalque colado numa parede nao brigar com ela pelo mesmo pixel: sem
         * isso as duas superficies cintilam conforme quem joga anda. Um milesimo basta
         * ({@code 1.001}), e e o mesmo empurrao que o mod original da na placa de textura dele.
         */
        public float expand = 1.0f;
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
        /**
         * Chamado quando chega o tique que o script pediu com {@code schedule_block}.
         *
         * <p>Diferente de {@link #onRandomTick}, que o jogo dispara quando quer: aqui o script diz
         * daqui a quantos tiques quer ser chamado naquela posicao. E o que faz um item atravessar
         * um cano em vez de aparecer do outro lado.
         */
        public String onScheduled;
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

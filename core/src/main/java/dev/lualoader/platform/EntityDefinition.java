package dev.lualoader.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * Especie declarada: um tipo de entidade proprio, derivado de um do jogo.
 *
 * <p>O {@code base} nao e enfeite nem heranca de conveniencia. Uma entidade viva e modelo, animacao
 * e comportamento, tres sistemas separados; derivar de uma especie do jogo entrega os tres de graca
 * e deixa o mod declarar so o que muda. Uma especie sem base alguma exigiria que a declaracao
 * descrevesse os tres, e e um projeto de outro tamanho.
 *
 * <p>Por isso a base e obrigatoria: uma entidade registrada sem saber como se desenhar nasce
 * invisivel, e um mob invisivel nao se parece com erro de manifesto para quem escreveu o mod.
 *
 * <p><b>Mora na camada de plataforma, e nao dentro do manifesto</b>, embora seja o manifesto quem
 * mais a preenche. Registrar especie e uma operacao que atravessa para o adaptador, como
 * {@link EntitySpec} e {@link ItemSpec} ja atravessam, e um mod tambem a monta por script sem
 * passar por {@code mod.json}. Deixa-la aninhada no manifesto faria o contrato de plataforma
 * depender do formato de arquivo, e os dois pacotes passariam a se importar em circulo.
 */
public final class EntityDefinition {
    public String id;
    public String name;

    /** Especie do jogo de onde vem modelo, animacao e comportamento. Ex.: minecraft:zombie. */
    public String base;

    /**
     * Como o jogo classifica a criatura: {@code monster}, {@code creature}, {@code ambient}...
     *
     * <p>Decide limite populacional e condicao de nascimento natural. Sem declarar, herda a
     * categoria da base -- que e quase sempre o que se quer, ja que a base tambem define a IA.
     */
    public String category;

    /** Largura da caixa de colisao, em blocos. Zero herda a da base. */
    public float width = 0.0f;

    /** Altura da caixa de colisao, em blocos. Zero herda a da base. */
    public float height = 0.0f;

    /** Distancia em pedacos de mundo em que o cliente acompanha a entidade. Zero herda. */
    public int trackingRange = 0;

    /** A cada quantos tiques a posicao e reenviada ao cliente. Zero herda. */
    public int updateInterval = 0;

    public boolean fireImmune = false;

    /** Se aparece para o comando {@code /summon}. */
    public boolean summonable = true;

    /** Se sobrevive ao descarregar do mundo. Falso e para o que existe so na sessao. */
    public boolean saveable = true;

    /**
     * O que a especie ja e ao nascer: nome, atributos, efeitos, equipamento, variante.
     *
     * <p>Reusa {@link EntitySpec} em vez de repetir os campos aqui. E o mesmo vocabulario que
     * {@code spawn_entity} e {@code apply_to_entity} ja aceitam, e mante-lo unico significa que o
     * que se pode declarar tambem se pode aplicar depois -- duas listas separadas divergiriam no
     * primeiro campo novo.
     */
    public EntitySpec defaults;

    public EntityLootDefinition loot = new EntityLootDefinition();

    /** Ovo de criacao para esta especie. Ausente nao gera ovo nenhum. */
    public SpawnEggDefinition spawnEgg;

    /**
     * A pele da criatura: um recurso declarado ({@code "@guardiao"}), um caminho dentro do mod ou
     * uma URL.
     *
     * <p>Sem declarar, a espécie usa a textura da base — que é o que faz um guardião declarado sair
     * idêntico a um golem de ferro. É correto como padrão e quase nunca é o que se quer no fim.
     *
     * <p><b>Não é herdada.</b> Vale a mesma regra das tags, e pelo mesmo motivo: o pacote de
     * recursos é montado a partir do manifesto declarado, sem passar pela mescla de herança.
     * Herdar aqui daria uma espécie que o adaptador considera texturizada e o jogo desenha com a
     * pele da base — divergência que some entre duas partes que, cada uma sozinha, parecem certas.
     *
     * <p>Texto, e não o objeto de textura que bloco e item usam: aquele mora no pacote do
     * manifesto, e este contrato é de plataforma. Fazer a plataforma depender do formato de arquivo
     * poria os dois pacotes a se importar em círculo.
     */
    public String texture;

    /**
     * A forma da criatura: um recurso declarado, um caminho no mod ou uma URL, apontando um JSON
     * de ossos e caixas.
     *
     * <p>Sem declarar, a espécie tem a forma da base. Com um modelo declarado, ela ganha forma
     * própria e <b>continua usando a animação e o comportamento da base</b> — o bicho anda como um
     * golem e parece outra coisa.
     *
     * <p>Os nomes dos ossos precisam ser os que a base anima. É o que faz a animação dela encontrar
     * as peças novas, e é conferido na carga: um nome desconhecido não dá erro no jogo, a peça
     * apenas não aparece.
     *
     * <p><b>Não é herdado</b>, pela mesma razão da textura e das tags.
     */
    public String model;

    /**
     * Onde e com que frequência a espécie nasce sozinha no mundo.
     *
     * <p>Ausente significa que ela só chega ao mundo por comando, por ovo ou por script — que é o
     * padrão certo: um mod que declara uma criatura não deveria começar a povoar o mundo de quem
     * instalou sem pedir.
     */
    public SpawnDefinition spawn;

    public List<String> tags = new ArrayList<>();

    /**
     * A regra de nascimento natural.
     *
     * <p>O jogo sorteia entre as espécies candidatas de um bioma usando o peso, e depois confere as
     * condições de cada uma. Declarar peso alto não garante nada: se a condição de luz ou altura
     * não fechar, a criatura simplesmente não nasce — e é por isso que um peso grande com uma
     * condição impossível parece "o loader não funciona" em vez de "a regra não fecha".
     */
    public static final class SpawnDefinition {
        /**
         * Biomas ou tags de bioma onde ela nasce, como {@code minecraft:desert} ou
         * {@code #minecraft:is_forest}.
         *
         * <p>Vazio não significa "todos": significa que nada foi declarado, e nesse caso a espécie
         * não nasce em lugar nenhum. Um padrão de "o mundo inteiro" transformaria um campo esquecido
         * num mod que enche a savana de guardiões.
         */
        public List<String> biomes = new ArrayList<>();

        /** Peso do sorteio. Quanto maior, mais candidata ela é entre as do bioma. */
        public int weight = 10;

        /** Menor grupo que nasce de uma vez. */
        public int minGroup = 1;

        /** Maior grupo que nasce de uma vez. */
        public int maxGroup = 4;

        /**
         * Faixa de luz de bloco em que ela aceita nascer, de 0 a 15.
         *
         * <p>Luz de bloco, e não o total: um lugar iluminado só pelo sol tem quinze ao meio-dia e
         * continua escuro à noite. Olhar o total faria o monstro nunca nascer.
         */
        public int minLight = 0;
        public int maxLight = 15;

        /** Faixa de altura. Ausente usa a do mundo inteiro. */
        public Integer minY;
        public Integer maxY;
    }

    /**
     * O que a especie deixa cair ao morrer.
     *
     * <p>Nao reusa o loot de bloco: o {@code mode} de la fala em derrubar o proprio bloco, o que
     * nao quer dizer nada para uma criatura. Um vocabulario emprestado que so faz sentido pela
     * metade custa mais para quem le do que dois pequenos.
     */
    public static final class EntityLootDefinition {
        /** Tabela de saque do jogo, no formato {@code mod:caminho}. Vazio usa a da base. */
        public String table;

        /**
         * Itens soltos, somados ao que a tabela ja produz.
         *
         * <p>Somados, e nao no lugar: sem isso, declarar um unico drop proprio apagaria em silencio
         * tudo que a base derrubava, e a perda so apareceria matando o bicho.
         */
        public List<EntityDropDefinition> drops = new ArrayList<>();
    }

    /** Um item que cai, com quantidade em faixa e chance propria. */
    public static final class EntityDropDefinition {
        public String item;
        public int min = 1;
        public int max = 1;

        /** De 0 a 1. Um por sorteio, e nao um por morte: min e max ainda valem quando sai. */
        public float chance = 1.0f;

        /** So cai quando quem matou foi um jogador, como couro de aldeao. */
        public boolean requiresPlayerKill = false;
    }

    /**
     * Ovo de criacao da especie.
     *
     * <p>As cores sao as do proprio ovo, e nao as do bicho: o jogo nao sabe olhar a textura da
     * criatura para escolher, entao um ovo sem cor declarada sai cinza sobre cinza e fica
     * indistinguivel dos outros na aba do criativo.
     */
    public static final class SpawnEggDefinition {
        public boolean register = true;

        /** Id do item. Vazio usa {@code <id da entidade>_spawn_egg}. */
        public String id;
        public String name;

        /** Cor de fundo, em 0xRRGGBB. */
        public int primaryColor = 0x8f8f8f;

        /** Cor das manchas, em 0xRRGGBB. */
        public int secondaryColor = 0x3f3f3f;
    }
}

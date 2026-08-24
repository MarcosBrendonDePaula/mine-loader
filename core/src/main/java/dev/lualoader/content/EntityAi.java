package dev.lualoader.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * O comportamento declarado de uma espécie: o que ela tenta fazer, e em que ordem.
 *
 * <p>Um vocabulário fechado, e não nomes de classe do jogo. É a mesma regra do {@code
 * ScreenProtocol}: o mod diz "foge de", e traduzir isso para o que esta versão do Minecraft chama de
 * fugir é trabalho do adaptador. Deixar o manifesto nomear a classe amarraria cada mod a uma versão
 * — as classes de meta mudaram de nome mais de uma vez — e ainda daria ao script alcance sobre
 * qualquer coisa que se pareça com uma meta.
 *
 * <p><b>A prioridade é a do jogo, e menor vence.</b> Duas metas que querem controlar o mesmo
 * movimento não rodam juntas: a de número menor manda. Nadar costuma ser zero, porque afogar-se
 * interrompe qualquer plano; vagar costuma ser alto, porque é o que se faz quando não há nada
 * melhor.
 *
 * <p>Metas e alvos são listas separadas porque o jogo as executa em seletores diferentes: uma
 * decide o que a criatura <i>faz</i>, a outra decide em quem ela <i>presta atenção</i>. Misturar as
 * duas faria "atacar quem me feriu" competir por prioridade com "vagar", que são perguntas
 * diferentes.
 */
public final class EntityAi {
    /**
     * Se as metas da base devem sair antes de entrar as declaradas.
     *
     * <p>Falso acrescenta às da base — bom para dar um comportamento a mais a um lobo. Verdadeiro
     * dá controle total, e é o que se quer quando a criatura só emprestou o corpo da base.
     */
    public boolean clear = false;

    public List<Goal> goals = new ArrayList<>();
    public List<Target> targets = new ArrayList<>();

    /**
     * Uma meta: o que a criatura tenta fazer.
     *
     * <p>Campos públicos e um {@code type} que decide quais deles valem. É deliberadamente frouxo:
     * um campo que não se aplica àquele tipo é ignorado, e não recusado, porque o contrário faria
     * um mod quebrar ao trocar o tipo de uma meta sem limpar os campos antigos.
     */
    public static final class Goal {
        public String type;

        /** Menor vence. Sem declarar, o loader usa a ordem em que a meta aparece na lista. */
        public Integer priority;

        /** Multiplicador de velocidade, para as metas que andam. */
        public double speed = 1.0;

        /** Alcance em blocos, para as metas que olham ou fogem. */
        public double range = 8.0;

        /** De quem fugir, ou quem seguir, no formato {@code mod:especie}. */
        public String entity;

        /** Itens que atraem a criatura, para {@code follow_item}. */
        public List<String> items = new ArrayList<>();
    }

    /** Um alvo: em quem a criatura presta atenção. */
    public static final class Target {
        public String type;
        public Integer priority;

        /** Que espécie caçar, para {@code attack_entity}. */
        public String entity;
    }

    /**
     * As metas que o loader sabe traduzir.
     *
     * <p>A lista é curta de propósito. Cada nome aqui é uma promessa que os dois adaptadores
     * precisam cumprir igual, e um vocabulário grande demais viraria uma lista em que metade
     * funciona de um lado só — exatamente o que a matriz de compatibilidade existe para impedir.
     */
    public static final Set<String> GOAL_TYPES = Set.of(
            // Não se afogar. Costuma ser prioridade zero: nenhum plano sobrevive à água.
            "float",
            // Fugir quando apanha ou pega fogo.
            "panic",
            // Perseguir e bater no alvo atual.
            "melee_attack",
            // Ir atrás de quem segura um dos itens declarados.
            "follow_item",
            // Manter distância de uma espécie.
            "avoid",
            // Encarar o jogador mais próximo.
            "look_at_player",
            // Olhar em volta quando não há nada melhor a fazer.
            "look_around",
            // Andar sem destino. Quase sempre a prioridade mais alta da lista.
            "wander");

    /** Os alvos que o loader sabe traduzir. */
    public static final Set<String> TARGET_TYPES = Set.of(
            // Revidar em quem feriu.
            "hurt_by",
            // Caçar jogadores.
            "attack_player",
            // Caçar uma espécie declarada.
            "attack_entity");

    /** Se o tipo de meta pede uma espécie; sem ela, a meta não teria de quem fugir. */
    public static boolean goalNeedsEntity(String type) {
        return "avoid".equals(normalized(type));
    }

    /** Se o tipo de meta pede itens; sem eles, nada atrairia a criatura. */
    public static boolean goalNeedsItems(String type) {
        return "follow_item".equals(normalized(type));
    }

    /** Se o tipo de alvo pede uma espécie. */
    public static boolean targetNeedsEntity(String type) {
        return "attack_entity".equals(normalized(type));
    }

    public static String normalized(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }
}

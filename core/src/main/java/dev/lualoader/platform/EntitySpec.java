package dev.lualoader.platform;

/**
 * O que um mod pode declarar sobre a entidade que está criando.
 *
 * <p>Um vocabulário fechado, e não NBT cru. NBT é a estrutura de dados do Minecraft, e os campos
 * dentro dela mudam entre versões — atributos foram renomeados, e o que hoje é um componente já foi
 * uma etiqueta solta. Deixar o mod escrever NBT amarraria cada mod a uma versão do jogo, que é
 * exatamente o que a camada de plataforma existe para evitar.
 *
 * <p>Cada campo aqui é uma pergunta que qualquer versão do jogo responde. Traduzir "domado" para o
 * que aquela versão chama de domado é trabalho do adaptador, e é onde deve ficar.
 *
 * <p>{@code null} em qualquer campo significa "não declarado": o jogo decide, como faria sem o mod.
 * É diferente de declarar o valor padrão, porque um {@code false} declarado impede o jogo de
 * escolher outra coisa.
 */
public record EntitySpec(
        String name,
        Boolean nameVisible,
        Boolean tame,
        Boolean baby,
        Boolean invulnerable,
        Boolean persistent,
        Boolean silent,
        Boolean noGravity,
        Boolean noAi,
        Double health) {

    /** Nada declarado: o jogo decide tudo. */
    public static final EntitySpec EMPTY =
            new EntitySpec(null, null, null, null, null, null, null, null, null, null);

    /** Se não há nada a aplicar, para o adaptador poder pular o trabalho. */
    public boolean isEmpty() {
        return name == null && nameVisible == null && tame == null && baby == null
                && invulnerable == null && persistent == null && silent == null
                && noGravity == null && noAi == null && health == null;
    }
}

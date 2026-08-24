package dev.lualoader.content;

import dev.lualoader.manifest.ModManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Quais blocos declarados pegam fogo, e com que números.
 *
 * <p>Vive no núcleo porque a leitura do manifesto é idêntica nas duas plataformas — muda só a
 * chamada que registra. Foi a decisão oposta que deixou {@code flammability} e {@code burn_spread}
 * declarados e ignorados por muito tempo: sem um lugar óbvio no núcleo, cada adaptador teria que
 * lembrar sozinho de aplicá-los, e nenhum lembrou.
 *
 * <p>As duas medidas não são a mesma coisa, e trocá-las produz um bloco que se comporta ao
 * contrário: {@code burn_spread} é a chance de o fogo <em>alcançar</em> o bloco a partir de um
 * vizinho, e {@code flammability} é a chance de ele <em>ser consumido</em> depois de aceso. Madeira
 * tem 5 e 20; lã tem 30 e 60 — espalha muito mais fácil do que queima.
 */
public final class Flammability {
    private Flammability() {
    }

    /** Um bloco declarado que pega fogo, com as duas medidas já validadas. */
    public record Entry(String blockId, int burnSpread, int flammability) {
    }

    /**
     * Os blocos do manifesto que declaram inflamabilidade.
     *
     * <p>Um bloco entra na lista quando declara qualquer uma das duas medidas: declarar só uma faz
     * sentido — um bloco que queima fácil e não espalha, por exemplo — e exigir as duas obrigaria
     * quem escreve o mod a repetir o padrão do jogo sem motivo.
     */
    public static List<Entry> declaredIn(ModManifest manifest) {
        List<Entry> entries = new ArrayList<>();
        if (manifest == null || manifest.blocks == null) return entries;

        for (ModManifest.BlockDefinition block : manifest.blocks) {
            if (block == null || block.id == null || block.material == null) continue;

            int spread = Math.max(0, Math.min(300, block.material.burnSpread));
            int burn = Math.max(0, Math.min(300, block.material.flammability));
            if (spread == 0 && burn == 0) continue;

            entries.add(new Entry(manifest.id + ":" + block.id, spread, burn));
        }
        return entries;
    }
}

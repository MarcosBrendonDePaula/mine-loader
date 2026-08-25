package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.BlockEventData;
import dev.lualoader.platform.TestBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O item viajando pelos tubos do exemplo, do baú de origem ao de destino.
 *
 * <p>Prende as três capacidades que só valem juntas: forma que varia com o estado, dados por
 * posição e tique agendado. Sem a terceira, o item some de um baú e aparece no outro; com ela, a
 * carga existe em algum lugar do caminho enquanto viaja — e é essa a diferença entre uma rede que
 * funciona e uma que parece funcionar.
 *
 * <p><b>Usa {@code examples/tubos}, e não um exemplo grande.</b> Um teste que aponta para um mod
 * cheio de outras coisas fica refém de mudanças que não têm nada a ver com ele. O mod de tubos
 * existe para isto e para demonstrar o mecanismo — nada mais.
 *
 * <p><b>Por que um mundo falso e não um GameTest.</b> O que precisa ser verificado é a decisão do
 * mod: quantos passos, o que acontece quando o caminho quebra no meio, o que acontece quando o baú
 * de destino enche. Num servidor de verdade cada uma dessas situações custaria montar um cenário e
 * esperar tiques; aqui o teste é o relógio, e cada caso é determinístico.
 *
 * <p>O que o mundo falso <b>não</b> prova é que a plataforma entrega o tique — isso é o GameTest de
 * cada adaptador, e é de propósito que sejam testes separados.
 */
class PipeTravelTest {

    /**
     * Um mundo pequeno o bastante para caber num teste: blocos, dados por posição e inventários.
     *
     * <p>Guarda os tiques agendados em vez de dispará-los. O teste avança o relógio de propósito,
     * um tique por vez, porque o que interessa é justamente o meio da viagem — um dublê que
     * entregasse tudo de uma vez esconderia exatamente o que mudou.
     */
    private static final class Mundo extends TestBridge {
        final Map<String, String> blocos = new HashMap<>();
        final Map<String, String> dados = new HashMap<>();
        /** Conteúdo de cada inventário: posição → (item → quantidade). */
        final Map<String, Map<String, Integer>> baus = new HashMap<>();
        final List<String> agendados = new ArrayList<>();
        final List<String> avisos = new ArrayList<>();

        private static String chave(int x, int y, int z) {
            return x + "," + y + "," + z;
        }

        void bloco(int x, int y, int z, String id) {
            blocos.put(chave(x, y, z), id);
        }

        void bau(int x, int y, int z, String item, int quantidade) {
            blocos.put(chave(x, y, z), "minecraft:chest");
            Map<String, Integer> conteudo = baus.computeIfAbsent(chave(x, y, z),
                    ignored -> new LinkedHashMap<>());
            if (quantidade > 0) conteudo.put(item, quantidade);
        }

        int quanto(int x, int y, int z, String item) {
            return baus.getOrDefault(chave(x, y, z), Map.of()).getOrDefault(item, 0);
        }

        @Override
        public String getBlock(int x, int y, int z) {
            return blocos.getOrDefault(chave(x, y, z), "minecraft:air");
        }

        @Override
        public void setBlock(String blockId, int x, int y, int z) {
            blocos.put(chave(x, y, z), blockId);
        }

        @Override
        public String getBlockData(int x, int y, int z) {
            return dados.getOrDefault(chave(x, y, z), "{}");
        }

        @Override
        public void setBlockData(int x, int y, int z, String json) {
            dados.put(chave(x, y, z), json);
        }

        @Override
        public void scheduleBlockTick(int x, int y, int z, int ticks) {
            agendados.add(chave(x, y, z));
        }

        @Override
        public java.util.Set<String> capabilitiesAt(int x, int y, int z) {
            return baus.containsKey(chave(x, y, z)) ? java.util.Set.of("items") : java.util.Set.of();
        }

        @Override
        public java.util.List<String> containerAt(int x, int y, int z) {
            List<String> linhas = new ArrayList<>();
            int slot = 0;
            for (Map.Entry<String, Integer> entrada
                    : baus.getOrDefault(chave(x, y, z), Map.of()).entrySet()) {
                linhas.add(slot++ + ";" + entrada.getKey() + ";" + entrada.getValue());
            }
            return linhas;
        }

        @Override
        public int insertInto(int x, int y, int z, String itemId, int count) {
            Map<String, Integer> conteudo = baus.get(chave(x, y, z));
            if (conteudo == null) return count;

            // Um baú com teto: sem ele o caso do destino cheio não teria como existir.
            int atual = conteudo.getOrDefault(itemId, 0);
            int cabe = Math.max(0, capacidade - atual);
            int posto = Math.min(cabe, count);
            if (posto > 0) conteudo.put(itemId, atual + posto);
            return count - posto;
        }

        int capacidade = 64;

        @Override
        public int extractFrom(int x, int y, int z, String itemId, int count) {
            Map<String, Integer> conteudo = baus.get(chave(x, y, z));
            if (conteudo == null) return 0;

            int tem = conteudo.getOrDefault(itemId, 0);
            int tirado = Math.min(tem, count);
            if (tirado <= 0) return 0;

            if (tem - tirado == 0) conteudo.remove(itemId);
            else conteudo.put(itemId, tem - tirado);
            return tirado;
        }

        @Override
        public void broadcast(String message) {
            avisos.add(message);
        }
    }

    /** Copia o exemplo do repositório, para o teste rodar contra o que está versionado. */
    private ModLoader.LoadedMod carregar(Path root) throws IOException {
        Path origem = Path.of("..", "examples", "tubos");
        Path destino = root.resolve("tubos");
        Files.createDirectories(destino);

        try (var caminhos = Files.walk(origem)) {
            for (Path arquivo : caminhos.filter(Files::isRegularFile).toList()) {
                Path relativo = origem.relativize(arquivo);
                Path alvo = destino.resolve(relativo.toString());
                Files.createDirectories(alvo.getParent());
                Files.copy(arquivo, alvo);
            }
        }

        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size(), "o exemplo de tubos deveria ter carregado");
        return mods.get(0);
    }

    /**
     * Monta a linha: baú, N+2 tubos, baú.
     *
     * <p>Em linha porque o que se mede é o número de passos, e uma rede torta só acrescentaria
     * aritmética ao teste sem acrescentar o que ele verifica.
     */
    private static void montarLinha(Mundo mundo, int tubos) {
        mundo.bau(0, 100, 0, "minecraft:diamond", 32);
        for (int z = 1; z <= 2 + tubos; z++) mundo.bloco(0, 100, z, "tubos:tubo");
        mundo.bau(0, 100, 3 + tubos, "minecraft:diamond", 0);
    }

    /** Um tique: entrega os agendamentos pendentes, e devolve quantos foram. */
    private static int avancar(LuaRuntime runtime, Mundo mundo) {
        List<String> agora = new ArrayList<>(mundo.agendados);
        mundo.agendados.clear();

        for (String posicao : agora) {
            String[] partes = posicao.split(",");
            int x = Integer.parseInt(partes[0]);
            int y = Integer.parseInt(partes[1]);
            int z = Integer.parseInt(partes[2]);

            runtime.triggerBlock("block_scheduled", null,
                    new BlockEventData(mundo.getBlock(x, y, z), x, y, z, 0, 1));
        }
        return agora.size();
    }

    private LuaRuntime runtime(Mundo mundo, Path root) throws IOException {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(mundo);
        runtime.load(carregar(root));
        return runtime;
    }

    @Test
    void oItemAtravessaOsTubosEmVezDeTeleportar(@TempDir Path root) throws IOException {
        Mundo mundo = new Mundo();
        montarLinha(mundo, 4);
        LuaRuntime runtime = runtime(mundo, root);

        assertTrue(runtime.runCommand("tubos", null, "enviar 0 100 1 0 100 6 minecraft:diamond 16"),
                "o comando do exemplo deveria existir");

        // O que mudou. Na hora do pedido o item ja saiu do bau de origem, e ainda NAO chegou ao
        // destino -- ele esta dentro da rede. Era exatamente isso que faltava: antes, os dois
        // numeros mudavam no mesmo instante.
        assertEquals(16, mundo.quanto(0, 100, 0, "minecraft:diamond"),
                "a linha deveria ter tirado 16 do bau de origem");
        assertEquals(0, mundo.quanto(0, 100, 7, "minecraft:diamond"),
                "o item nao pode chegar ao destino no mesmo instante do pedido");

        // E chega depois de andar: um passo por tubo do caminho.
        int passos = 0;
        while (mundo.quanto(0, 100, 7, "minecraft:diamond") == 0 && passos < 20) {
            avancar(runtime, mundo);
            passos++;
        }

        assertEquals(16, mundo.quanto(0, 100, 7, "minecraft:diamond"),
                "os 16 deveriam ter chegado ao bau do fim");
        assertEquals(6, passos, "a viagem deveria custar um passo por tubo do caminho");
    }

    @Test
    void aRedeMaisLongaCustaMaisPassos(@TempDir Path root) throws IOException {
        // O tempo de viagem acompanha a distancia. Um numero fixo passaria neste e no outro teste,
        // e daria uma rede em que atravessar a base custa o mesmo que o cano do lado.
        Mundo curta = new Mundo();
        montarLinha(curta, 2);
        LuaRuntime runtimeCurta = runtime(curta, root.resolve("a"));
        runtimeCurta.runCommand("tubos", null, "enviar 0 100 1 0 100 4 minecraft:diamond 16");

        int passosCurta = 0;
        while (curta.quanto(0, 100, 5, "minecraft:diamond") == 0 && passosCurta < 30) {
            avancar(runtimeCurta, curta);
            passosCurta++;
        }

        Mundo longa = new Mundo();
        montarLinha(longa, 8);
        LuaRuntime runtimeLonga = runtime(longa, root.resolve("b"));
        runtimeLonga.runCommand("tubos", null, "enviar 0 100 1 0 100 10 minecraft:diamond 16");

        int passosLonga = 0;
        while (longa.quanto(0, 100, 11, "minecraft:diamond") == 0 && passosLonga < 30) {
            avancar(runtimeLonga, longa);
            passosLonga++;
        }

        assertEquals(16, longa.quanto(0, 100, 11, "minecraft:diamond"), "a rede longa deveria entregar");
        assertEquals(passosCurta + 6, passosLonga, "seis tubos a mais, seis passos a mais");
    }

    @Test
    void aCargaFicaGuardadaNaPosicaoEmQueEsta(@TempDir Path root) throws IOException {
        Mundo mundo = new Mundo();
        montarLinha(mundo, 4);
        LuaRuntime runtime = runtime(mundo, root);
        runtime.runCommand("tubos", null, "enviar 0 100 1 0 100 6 minecraft:diamond 16");

        // A carga mora no block_data do cano em que esta, e nao numa tabela do mod. E o que a faz
        // sobreviver ao servidor cair, e sumir junto com o cano em vez de apontar para o vazio.
        assertTrue(mundo.dados.getOrDefault("0,100,1", "{}").contains("minecraft:diamond"),
                "a carga deveria estar no primeiro tubo: " + mundo.dados);

        avancar(runtime, mundo);

        assertFalse(mundo.dados.getOrDefault("0,100,1", "{}").contains("minecraft:diamond"),
                "a carga deveria ter saido do primeiro tubo");
        assertTrue(mundo.dados.getOrDefault("0,100,2", "{}").contains("minecraft:diamond"),
                "e chegado ao tubo seguinte: " + mundo.dados);
    }

    @Test
    void oTuboVazioParaDePedirTique(@TempDir Path root) throws IOException {
        Mundo mundo = new Mundo();
        montarLinha(mundo, 3);
        LuaRuntime runtime = runtime(mundo, root);
        runtime.runCommand("tubos", null, "enviar 0 100 1 0 100 5 minecraft:diamond 16");

        for (int i = 0; i < 10; i++) avancar(runtime, mundo);

        // Uma linha parada nao pode continuar custando tique para sempre: um tubo que se reagendasse
        // vazio faria cada tubo ja usado virar trabalho permanente do servidor.
        assertEquals(0, mundo.agendados.size(),
                "com a entrega feita, nao deveria sobrar tique agendado: " + mundo.agendados);
        assertEquals(16, mundo.quanto(0, 100, 6, "minecraft:diamond"));
    }

    @Test
    void quebrarOTuboNaFrenteNaoApagaOItem(@TempDir Path root) throws IOException {
        Mundo mundo = new Mundo();
        montarLinha(mundo, 4);
        // Um bau de emergencia encostado no cano onde a carga vai estar quando o caminho sumir.
        mundo.bau(1, 100, 3, "minecraft:diamond", 0);

        LuaRuntime runtime = runtime(mundo, root);
        runtime.runCommand("tubos", null, "enviar 0 100 1 0 100 6 minecraft:diamond 16");

        avancar(runtime, mundo);
        avancar(runtime, mundo);

        // Alguem quebra o tubo da frente com a carga em transito.
        mundo.bloco(0, 100, 4, "minecraft:air");
        avancar(runtime, mundo);

        // O item nao pode desaparecer -- e o pior defeito possivel num mod de logistica. Ele sai
        // pelo inventario mais proximo, que e o que o original faz ao derrubar no chao; largar item
        // solto ainda nao existe na API do loader.
        int emergencia = mundo.quanto(1, 100, 3, "minecraft:diamond");
        int destino = mundo.quanto(0, 100, 7, "minecraft:diamond");
        int origem = mundo.quanto(0, 100, 0, "minecraft:diamond");
        assertEquals(32, emergencia + destino + origem,
                "nenhum diamante pode ter sumido: emergencia=" + emergencia
                        + " destino=" + destino + " origem=" + origem);
        assertNotEquals(0, emergencia, "a carga deveria ter saido pelo bau vizinho");
    }

    @Test
    void oBauDeDestinoCheioSeguraACargaEmVezDeApagaLa(@TempDir Path root) throws IOException {
        Mundo mundo = new Mundo();
        montarLinha(mundo, 3);
        // O destino so aceita 4: os outros 12 tem que esperar em algum lugar.
        mundo.capacidade = 4;

        LuaRuntime runtime = runtime(mundo, root);
        runtime.runCommand("tubos", null, "enviar 0 100 1 0 100 5 minecraft:diamond 16");

        for (int i = 0; i < 12; i++) avancar(runtime, mundo);

        assertEquals(4, mundo.quanto(0, 100, 6, "minecraft:diamond"),
                "o destino aceita so o que cabe");

        // O resto continua existindo: parado no terminal, esperando espaco. Some-lo seria apagar
        // item do mundo, e a fila e o que o jogador espera ver.
        String noTerminal = mundo.dados.getOrDefault("0,100,5", "{}");
        assertTrue(noTerminal.contains("minecraft:diamond"),
                "a carga que nao coube deveria estar esperando no ultimo tubo: " + noTerminal);
    }
}

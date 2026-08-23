package dev.lualoader.lua;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.DebugLib;

/**
 * Interrompe um script que passa do tempo permitido.
 *
 * <p>Sem isto, um {@code while true do end} em qualquer mod trava a thread do servidor e derruba o
 * jogo inteiro: o Minecraft não tem como recuperar uma thread principal presa. Como o loader se
 * propõe a rodar código de terceiros, um limite é condição para aceitar mods que não foram escritos
 * por quem administra o servidor.
 *
 * <p>O interpretador chama {@link #onInstruction} a cada instrução executada. Consultar o relógio
 * nessa frequência custaria caro, então o tempo é verificado a cada bloco de instruções — o
 * suficiente para cortar um laço infinito em poucos milissegundos, sem pesar no caso normal.
 *
 * <p>A tabela {@code debug} não é exposta ao script: apenas o gancho interno é instalado.
 */
public final class ExecutionBudget extends DebugLib {
    /** De quantas em quantas instruções o relógio é consultado. */
    private static final int CHECK_INTERVAL = 2_048;

    private final long limitNanos;
    private long deadline;
    private int countdown = CHECK_INTERVAL;

    /**
     * @param limitMillis tempo máximo de um callback; zero ou menos desliga o limite
     */
    public ExecutionBudget(long limitMillis) {
        this.limitNanos = limitMillis <= 0 ? 0 : limitMillis * 1_000_000L;
    }

    /** Marca o início de um callback. */
    public void start() {
        if (limitNanos <= 0) {
            deadline = 0;
            return;
        }
        deadline = System.nanoTime() + limitNanos;
        countdown = CHECK_INTERVAL;
    }

    /** Marca o fim de um callback, liberando o script de qualquer limite fora dele. */
    public void stop() {
        deadline = 0;
    }

    @Override
    public void onInstruction(int pc, Varargs args, int top) {
        super.onInstruction(pc, args, top);
        if (deadline == 0) return;

        if (--countdown > 0) return;
        countdown = CHECK_INTERVAL;

        if (System.nanoTime() > deadline) {
            // Zera antes de lançar para o próprio tratamento do erro não cair no mesmo limite.
            deadline = 0;
            throw new LuaError("script excedeu o tempo limite de execucao");
        }
    }
}

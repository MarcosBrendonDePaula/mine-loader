package dev.lualoader.structure;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Leitor do formato NBT, o suficiente para ler um arquivo de estrutura.
 *
 * <p>O núcleo não podia usar o leitor do Minecraft: ele vive nas classes do jogo, e o núcleo não as
 * importa. Escrever um aqui é o preço de manter a regra — e o formato é pequeno o bastante para
 * caber num arquivo, o que torna o preço menor que a exceção.
 *
 * <p>Só leitura, e só o que um arquivo de estrutura usa. Não escreve NBT nem trata os detalhes que
 * um mundo salvo tem e uma estrutura não.
 */
public final class Nbt {
    private Nbt() {
    }

    // Os tipos do formato. Os nomes são os da especificação, para quem comparar com ela encontrar.
    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /**
     * Teto de elementos por lista ou array.
     *
     * <p>Um arquivo corrompido — ou malicioso — pode declarar um comprimento enorme, e alocar por
     * ele antes de ler esgotaria a memória com poucos bytes de entrada. O limite é bem maior que
     * qualquer estrutura razoável e bem menor que um estouro.
     */
    private static final int MAX_ELEMENTS = 4_000_000;

    /** Profundidade máxima de aninhamento, contra um arquivo que aninhe até estourar a pilha. */
    private static final int MAX_DEPTH = 64;

    /**
     * Lê um arquivo NBT, com ou sem gzip.
     *
     * <p>O bloco de estrutura grava comprimido, mas ferramentas de edição às vezes gravam cru. Olhar
     * os dois primeiros bytes evita obrigar quem usa a saber qual dos dois tem em mãos.
     *
     * @return o compound raiz, sempre um {@code Map}
     */
    public static Map<String, Object> read(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 3) throw new IOException("arquivo NBT vazio ou curto");

        boolean gzipped = (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        try (InputStream raw = new java.io.ByteArrayInputStream(bytes);
             InputStream stream = gzipped ? new GZIPInputStream(raw) : raw;
             DataInputStream input = new DataInputStream(stream)) {

            int type = input.readUnsignedByte();
            if (type != TAG_COMPOUND) {
                throw new IOException("NBT nao comeca com um compound: tipo " + type);
            }
            input.readUTF();  // O nome da raiz, que as estruturas deixam vazio.

            Object root = readPayload(input, TAG_COMPOUND, 0);
            @SuppressWarnings("unchecked")
            Map<String, Object> compound = (Map<String, Object>) root;
            return compound;
        } catch (EOFException error) {
            throw new IOException("arquivo NBT termina no meio de um valor", error);
        }
    }

    private static Object readPayload(DataInputStream input, int type, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT aninhado demais");

        return switch (type) {
            case TAG_BYTE -> input.readByte();
            case TAG_SHORT -> input.readShort();
            case TAG_INT -> input.readInt();
            case TAG_LONG -> input.readLong();
            case TAG_FLOAT -> input.readFloat();
            case TAG_DOUBLE -> input.readDouble();
            case TAG_BYTE_ARRAY -> readBytes(input);
            case TAG_STRING -> input.readUTF();
            case TAG_LIST -> readList(input, depth);
            case TAG_COMPOUND -> readCompound(input, depth);
            case TAG_INT_ARRAY -> readInts(input);
            case TAG_LONG_ARRAY -> readLongs(input);
            default -> throw new IOException("tipo NBT desconhecido: " + type);
        };
    }

    private static Map<String, Object> readCompound(DataInputStream input, int depth)
            throws IOException {
        Map<String, Object> compound = new LinkedHashMap<>();
        while (true) {
            int type = input.readUnsignedByte();
            if (type == TAG_END) return compound;

            String name = input.readUTF();
            compound.put(name, readPayload(input, type, depth + 1));
        }
    }

    private static List<Object> readList(DataInputStream input, int depth) throws IOException {
        int type = input.readUnsignedByte();
        int length = requireLength(input.readInt());

        List<Object> list = new ArrayList<>(Math.min(length, 1024));
        for (int index = 0; index < length; index++) {
            // Uma lista vazia pode declarar TAG_END como tipo; ler o payload dele nao faz sentido.
            if (type == TAG_END) break;
            list.add(readPayload(input, type, depth + 1));
        }
        return list;
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        byte[] values = new byte[requireLength(input.readInt())];
        input.readFully(values);
        return values;
    }

    private static int[] readInts(DataInputStream input) throws IOException {
        int[] values = new int[requireLength(input.readInt())];
        for (int index = 0; index < values.length; index++) values[index] = input.readInt();
        return values;
    }

    private static long[] readLongs(DataInputStream input) throws IOException {
        long[] values = new long[requireLength(input.readInt())];
        for (int index = 0; index < values.length; index++) values[index] = input.readLong();
        return values;
    }

    private static int requireLength(int length) throws IOException {
        if (length < 0) throw new IOException("comprimento NBT negativo: " + length);
        if (length > MAX_ELEMENTS) throw new IOException("comprimento NBT excessivo: " + length);
        return length;
    }

    // ------------------------------------------------------------------ leitura tipada

    /** O compound daquele campo, ou {@code null}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> parent, String field) {
        Object value = parent == null ? null : parent.get(field);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** A lista daquele campo, ou vazia — nunca nula, para quem itera não precisar checar. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> parent, String field) {
        Object value = parent == null ? null : parent.get(field);
        return value instanceof List ? (List<Object>) value : List.of();
    }

    /** O texto daquele campo, ou {@code null}. */
    public static String string(Map<String, Object> parent, String field) {
        Object value = parent == null ? null : parent.get(field);
        return value instanceof String text ? text : null;
    }

    /**
     * O número daquele campo como inteiro, ou o padrão.
     *
     * <p>Aceita qualquer tipo numérico de propósito: uma coordenada pode vir como byte, short ou int
     * conforme quem gravou, e obrigar o chamador a saber qual espalharia essa trivialidade.
     */
    public static int integer(Map<String, Object> parent, String field, int fallback) {
        Object value = parent == null ? null : parent.get(field);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** O elemento da lista como inteiro, ou o padrão. */
    public static int integerAt(List<Object> list, int index, int fallback) {
        if (list == null || index < 0 || index >= list.size()) return fallback;
        return list.get(index) instanceof Number number ? number.intValue() : fallback;
    }
}

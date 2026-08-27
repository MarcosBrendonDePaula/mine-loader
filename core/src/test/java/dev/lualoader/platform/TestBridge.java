package dev.lualoader.platform;

/**
 * Base para as bridges usadas em teste.
 *
 * <p>Implementa todas as operações como no-op para que cada teste sobrescreva apenas o que exercita.
 * Sem isso, acrescentar um método ao contrato quebraria a compilação de toda bridge de teste, o que
 * empurra na direção errada: dificultar a evolução do contrato para não mexer em testes.
 */
public abstract class TestBridge implements GameBridge {
    @Override
    public void broadcast(String message) {
    }

    @Override
    public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
    }

    @Override
    public void setBlockProperty(String blockId, String property, float value) {
    }

    @Override
    public void setBlockLuminance(String blockId, int x, int y, int z, int luminance) {
    }

    @Override
    public boolean isWorldAvailable() {
        return true;
    }

    @Override
    public java.util.List<String> onlinePlayers() {
        return java.util.List.of();
    }

    @Override
    public long timeOfDay() {
        return 0L;
    }

    @Override
    public String worldName() {
        return "minecraft:overworld";
    }

    @Override
    public String getBlock(int x, int y, int z) {
        return "minecraft:air";
    }

    @Override
    public void setBlock(String blockId, int x, int y, int z) {
    }

    @Override
    public int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2) {
        return 0;
    }

    @Override
    public void playSound(String soundId, int x, int y, int z, float volume, float pitch) {
    }

    @Override
    public void spawnParticles(String particleId, double x, double y, double z, int count, double spread) {
    }

    /**
     * O que foi agendado, na ordem: {@code "x,y,z,tiques"}.
     *
     * <p>Guardado em vez de executado. Um dublê que disparasse o tique sozinho esconderia o caso
     * que mais importa — o script que agenda e nunca é chamado de volta.
     */
    public final java.util.List<String> scheduledTicks = new java.util.ArrayList<>();

    @Override
    public void scheduleBlockTick(int x, int y, int z, int ticks) {
        scheduledTicks.add(x + "," + y + "," + z + "," + ticks);
    }

    /** Dados por posição, simulados em memória. */
    private final java.util.Map<String, String> blockData = new java.util.HashMap<>();

    @Override
    public String getBlockData(int x, int y, int z) {
        return blockData.getOrDefault(x + "," + y + "," + z, "{}");
    }

    @Override
    public void setBlockData(int x, int y, int z, String json) {
        blockData.put(x + "," + y + "," + z, json);
    }

    /** O bioma que o teste quer que o mundo responda. */
    public String biome = "minecraft:plains";

    @Override
    public String biomeAt(int x, int y, int z) {
        return biome;
    }

    /** Luz de bloco e do ceu, para o teste montar noite e caverna sem um mundo. */
    public int blockLight = 0;
    public int skyLight = 15;

    @Override
    public int lightAt(int x, int y, int z, boolean sky) {
        return sky ? skyLight : blockLight;
    }

    @Override
    public String spawnEntity(String entityId, double x, double y, double z) {
        return spawnEntity(entityId, x, y, z, EntitySpec.EMPTY);
    }

    /** O que foi declarado na ultima entidade criada, para o teste conferir. */
    public EntitySpec lastEntitySpec = EntitySpec.EMPTY;

    @Override
    public String spawnEntity(String entityId, double x, double y, double z, EntitySpec spec) {
        lastEntitySpec = spec == null ? EntitySpec.EMPTY : spec;
        return "00000000-0000-0000-0000-000000000000";
    }

    /** Última explosão recebida pelo dublê de mundo. */
    public double[] lastExplosionPosition;
    public float lastExplosionPower;
    public boolean lastExplosionBreakBlocks;

    @Override
    public void explode(double x, double y, double z, float power, boolean breakBlocks) {
        lastExplosionPosition = new double[]{x, y, z};
        lastExplosionPower = power;
        lastExplosionBreakBlocks = breakBlocks;
    }

    /** Último raio recebido pelo dublê de mundo. */
    public double[] lastLightningPosition;

    @Override
    public void strikeLightning(double x, double y, double z) {
        lastLightningPosition = new double[]{x, y, z};
    }

    @Override
    public java.util.List<String> entitiesNear(double x, double y, double z, double radius) {
        return java.util.List.of();
    }

    @Override
    public boolean removeEntity(String entityUuid) {
        return false;
    }

    @Override
    public boolean damageEntity(String entityUuid, float amount) {
        return false;
    }

    /** Onde cada entidade foi parar, para o teste conferir sem um mundo. */
    public final java.util.Map<String, double[]> entityPositions = new java.util.HashMap<>();

    @Override
    public boolean teleportEntity(String uuid, double x, double y, double z) {
        entityPositions.put(uuid, new double[]{x, y, z});
        return true;
    }

    @Override
    public boolean pushEntity(String uuid, double x, double y, double z) {
        double[] current = entityPositions.getOrDefault(uuid, new double[]{0, 0, 0});
        entityPositions.put(uuid, new double[]{current[0] + x, current[1] + y, current[2] + z});
        return true;
    }

    /** As especies registradas, na ordem em que chegaram. */
    private final java.util.Map<String, EntityDefinition> declared =
            new java.util.LinkedHashMap<>();

    @Override
    public java.util.List<String> declaredEntities() {
        return java.util.List.copyOf(declared.keySet());
    }

    @Override
    public EntityDefinition declaredEntity(String id) {
        return declared.get(id);
    }

    /** Itens que o "jogo" desta bridge conhece. */
    public final java.util.List<String> items = new java.util.ArrayList<>(java.util.List.of(
            "minecraft:stone", "minecraft:iron_ingot", "minecraft:iron_sword", "outro:vara"));

    /** Receitas conhecidas por esta bridge, ja no formato que o contrato descreve. */
    public final java.util.List<String> recipes = new java.util.ArrayList<>(java.util.List.of("""
            {"id":"minecraft:iron_sword","type":"minecraft:crafting_shaped",            "output":{"item":"minecraft:iron_sword","count":1},"width":1,"height":3,            "ingredients":[["minecraft:iron_ingot"],["minecraft:iron_ingot"],["minecraft:stick"]]}            """));

    /**
     * Quantos slots o duble diz que aquele inventario tem.
     *
     * <p>O duble guarda o inventario como um mapa de item para quantidade, sem posicao -- entao o
     * tamanho e quantos itens diferentes ha. E o bastante para o que os testes perguntam, e fingir
     * um numero fixo esconderia a diferenca entre uma maquina de tres slots e um bau.
     */
    @Override
    public int containerSize(int x, int y, int z) {
        java.util.Map<String, Integer> container = containers.get(x + "," + y + "," + z);
        if (container == null) {
            throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);
        }
        return container.size();
    }

    /**
     * Desenha um slot no duble.
     *
     * <p>O duble guarda o inventario como um mapa de item para quantidade, sem posicao -- entao um
     * slot desenhado aqui vira uma entrada como qualquer outra. E o bastante para o que os testes
     * perguntam, e fingir posicao seria fingir uma estrutura que o duble nao tem.
     */
    @Override
    public void setSlot(int x, int y, int z, int slot, String itemId, int count) {
        java.util.Map<String, Integer> container = containers.get(x + "," + y + "," + z);
        if (container == null) {
            throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);
        }
        if (itemId == null || itemId.isBlank() || count <= 0) return;
        container.merge(itemId, count, Integer::sum);
    }

    @Override
    public java.util.List<String> recipesFor(String itemId, int limit) {
        return filterRecipes(itemId, limit, true);
    }

    @Override
    public java.util.List<String> recipesUsing(String itemId, int limit) {
        return filterRecipes(itemId, limit, false);
    }

    /**
     * O que sai de um arranjo, procurando entre as receitas declaradas acima.
     *
     * <p>O duble compara <b>ignorando as celulas vazias em volta</b>: a receita da espada e 1x3, e
     * o jogador que a monta na coluna do meio de uma bancada 3x3 espera a mesma espada. Um
     * casamento posicao a posicao recusaria isso, e o teste passaria a exigir do script um
     * alinhamento que a bancada de verdade nao exige.
     */
    @Override
    public String craftingResult(java.util.List<String> items) {
        java.util.List<String> arranjo = recorta(items);
        if (arranjo.isEmpty()) return null;

        for (String recipe : recipes) {
            com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(recipe)
                    .getAsJsonObject();

            java.util.List<String> esperado = new java.util.ArrayList<>();
            for (com.google.gson.JsonElement posicao : json.getAsJsonArray("ingredients")) {
                com.google.gson.JsonArray opcoes = posicao.getAsJsonArray();
                esperado.add(opcoes.isEmpty() ? "" : opcoes.get(0).getAsString());
            }

            if (esperado.equals(arranjo)) {
                com.google.gson.JsonObject saida = json.getAsJsonObject("output");
                return saida.get("item").getAsString() + ";"
                        + (saida.has("count") ? saida.get("count").getAsInt() : 1);
            }
        }
        return null;
    }

    /**
     * Nenhum item queima, por padrao.
     *
     * <p>Zero e a resposta honesta de um duble que nao tem fornalha: o teste que precisa de
     * combustivel sobrescreve e diz quanto, e assim o numero fica escrito no proprio teste.
     */
    @Override
    public int fuelBurnTime(String item) {
        return 0;
    }

    /** O arranjo sem as linhas e colunas vazias das bordas, em ordem de leitura. */
    private static java.util.List<String> recorta(java.util.List<String> items) {
        String[][] grade = new String[3][3];
        for (int i = 0; i < 9; i++) {
            String valor = i < items.size() && items.get(i) != null ? items.get(i).trim() : "";
            grade[i / 3][i % 3] = valor;
        }

        int primeiraLinha = 3, ultimaLinha = -1, primeiraColuna = 3, ultimaColuna = -1;
        for (int linha = 0; linha < 3; linha++) {
            for (int coluna = 0; coluna < 3; coluna++) {
                if (grade[linha][coluna].isEmpty()) continue;
                primeiraLinha = Math.min(primeiraLinha, linha);
                ultimaLinha = Math.max(ultimaLinha, linha);
                primeiraColuna = Math.min(primeiraColuna, coluna);
                ultimaColuna = Math.max(ultimaColuna, coluna);
            }
        }
        if (ultimaLinha < 0) return java.util.List.of();

        java.util.List<String> recortado = new java.util.ArrayList<>();
        for (int linha = primeiraLinha; linha <= ultimaLinha; linha++) {
            for (int coluna = primeiraColuna; coluna <= ultimaColuna; coluna++) {
                recortado.add(grade[linha][coluna]);
            }
        }
        return recortado;
    }

    private java.util.List<String> filterRecipes(String itemId, int limit, boolean asOutput) {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String recipe : recipes) {
            boolean matches = asOutput
                    ? recipe.contains("\"output\":{\"item\":\"" + itemId + "\"")
                    : recipe.contains("[\"" + itemId + "\"]");
            if (matches && found.size() < limit) found.add(recipe);
        }
        return found;
    }

    /** Drops conhecidos por esta bridge: bloco para itens. */
    public final java.util.Map<String, java.util.List<String>> drops =
            new java.util.LinkedHashMap<>(java.util.Map.of(
                    "minecraft:iron_ore", java.util.List.of("minecraft:raw_iron"),
                    "minecraft:stone", java.util.List.of("minecraft:cobblestone"),
                    // Uma entidade responde pela mesma pergunta: matar a ovelha derruba la.
                    "minecraft:sheep", java.util.List.of("minecraft:white_wool", "minecraft:mutton")));

    /**
     * Inventarios desta bridge, por posicao. Simula o que uma plataforma exporia.
     *
     * <p>O dublê nao imita a Transfer API nem nenhuma outra: ele responde ao mesmo contrato neutro
     * que qualquer adaptador responde, que e o ponto de o contrato ser neutro.
     */
    public final java.util.Map<String, java.util.Map<String, Integer>> containers =
            new java.util.LinkedHashMap<>();

    private static String at(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    @Override
    public java.util.Set<String> capabilitiesAt(int x, int y, int z) {
        return containers.containsKey(at(x, y, z))
                ? java.util.Set.of("items")
                : java.util.Set.of();
    }

    @Override
    public java.util.List<String> containerAt(int x, int y, int z) {
        var content = containers.get(at(x, y, z));
        if (content == null) throw new BridgeException("nao ha inventario em " + at(x, y, z));

        java.util.List<String> lines = new java.util.ArrayList<>();
        int slot = 0;
        for (var entry : content.entrySet()) {
            lines.add(slot++ + ";" + entry.getKey() + ";" + entry.getValue());
        }
        return lines;
    }

    /**
     * O item que ocupa aquele slot, ou {@code null} se o slot esta vazio.
     *
     * <p>O dublê guarda o conteudo por item, e nao por posicao, entao "slot" aqui e a posicao na
     * ordem de insercao -- exatamente a mesma conta que {@link #containerAt} ja faz para numerar as
     * linhas. Manter as duas coerentes e o que importa: um teste que le o slot tres por
     * {@code container_at} e escreve no slot tres precisa acertar o mesmo lugar.
     */
    private String itemAtSlot(java.util.Map<String, Integer> content, int slot) {
        int index = 0;
        for (var key : content.keySet()) {
            if (index++ == slot) return key;
        }
        return null;
    }

    @Override
    public int insertIntoSlot(int x, int y, int z, int slot, String itemId, int count) {
        if (slot < 0) return insertInto(x, y, z, itemId, count);

        var content = containers.get(at(x, y, z));
        if (content == null) throw new BridgeException("nao ha inventario em " + at(x, y, z));
        if (slot >= 27) {
            throw new BridgeException("slot " + slot + " nao existe; o inventario tem 27");
        }

        String present = itemAtSlot(content, slot);
        // Um slot ocupado por outro item nao aceita nada, que e a regra do jogo.
        if (present != null && !present.equals(itemId)) return count;

        String key = present == null ? itemId : present;
        int atual = content.getOrDefault(key, 0);
        int cabe = Math.max(0, Math.min(count, 64 - atual));
        if (cabe > 0) content.merge(key, cabe, Integer::sum);
        return count - cabe;
    }

    @Override
    public int extractFromSlot(int x, int y, int z, int slot, String itemId, int count) {
        if (slot < 0) return extractFrom(x, y, z, itemId, count);

        var content = containers.get(at(x, y, z));
        if (content == null) throw new BridgeException("nao ha inventario em " + at(x, y, z));
        if (slot >= 27) {
            throw new BridgeException("slot " + slot + " nao existe; o inventario tem 27");
        }

        String present = itemAtSlot(content, slot);
        if (present == null || !present.equals(itemId)) return 0;

        int available = content.getOrDefault(present, 0);
        int taken = Math.min(available, count);
        if (taken >= available) content.remove(present);
        else content.put(present, available - taken);
        return taken;
    }

    @Override
    public int insertInto(int x, int y, int z, String itemId, int count) {
        var content = containers.get(at(x, y, z));
        if (content == null) throw new BridgeException("nao ha inventario em " + at(x, y, z));

        // Um bau de vinte e sete pilhas de sessenta e quatro: o que passa disso sobra.
        int total = content.values().stream().mapToInt(Integer::intValue).sum();
        int fits = Math.max(0, Math.min(count, 64 * 27 - total));

        if (fits > 0) content.merge(itemId, fits, Integer::sum);
        return count - fits;
    }

    @Override
    public int extractFrom(int x, int y, int z, String itemId, int count) {
        var content = containers.get(at(x, y, z));
        if (content == null) throw new BridgeException("nao ha inventario em " + at(x, y, z));

        int available = content.getOrDefault(itemId, 0);
        int taken = Math.min(available, count);

        if (taken == available) {
            content.remove(itemId);
        } else if (taken > 0) {
            content.put(itemId, available - taken);
        }
        return taken;
    }

    @Override
    public java.util.List<String> dropsOf(String blockId, int limit) {
        java.util.List<String> found = drops.getOrDefault(blockId, java.util.List.of());
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    @Override
    public java.util.List<String> droppedBy(String itemId, int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (var entry : drops.entrySet()) {
            if (entry.getValue().contains(itemId) && found.size() < limit) found.add(entry.getKey());
        }
        return found;
    }

    @Override
    public java.util.List<String> registeredItems(String namespace, String contains, int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String id : items) {
            if (namespace != null && !id.startsWith(namespace + ":")) continue;
            if (contains != null && !contains.isBlank()
                    && !id.substring(id.indexOf(':') + 1).contains(contains)) continue;
            found.add(id);
        }
        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }
}

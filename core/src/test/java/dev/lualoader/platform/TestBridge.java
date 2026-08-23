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

    @Override
    public String spawnEntity(String entityId, double x, double y, double z) {
        return "00000000-0000-0000-0000-000000000000";
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

    /** Itens que o "jogo" desta bridge conhece. */
    public final java.util.List<String> items = new java.util.ArrayList<>(java.util.List.of(
            "minecraft:stone", "minecraft:iron_ingot", "minecraft:iron_sword", "outro:vara"));

    /** Receitas conhecidas por esta bridge, ja no formato que o contrato descreve. */
    public final java.util.List<String> recipes = new java.util.ArrayList<>(java.util.List.of("""
            {"id":"minecraft:iron_sword","type":"minecraft:crafting_shaped",            "output":{"item":"minecraft:iron_sword","count":1},"width":1,"height":3,            "ingredients":[["minecraft:iron_ingot"],["minecraft:iron_ingot"],["minecraft:stick"]]}            """));

    @Override
    public java.util.List<String> recipesFor(String itemId, int limit) {
        return filterRecipes(itemId, limit, true);
    }

    @Override
    public java.util.List<String> recipesUsing(String itemId, int limit) {
        return filterRecipes(itemId, limit, false);
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
                    "minecraft:stone", java.util.List.of("minecraft:cobblestone")));

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

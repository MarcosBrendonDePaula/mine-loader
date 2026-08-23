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
}

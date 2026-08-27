package dev.lualoader.platform;

import java.util.LinkedHashMap;
import java.util.Map;

/** Estado simples de um bloco, transportável entre versões e plataformas. */
public final class BlockStateSnapshot {
    public final String id;
    public final Map<String, String> properties;

    public BlockStateSnapshot(String id, Map<String, String> properties) {
        this.id = id;
        this.properties = Map.copyOf(new LinkedHashMap<>(properties));
    }
}

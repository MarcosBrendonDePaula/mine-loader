package dev.lualoader.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Jogador simulado para os testes do núcleo.
 *
 * <p>Existe pelo mesmo motivo da {@link TestBridge}: sem uma base compartilhada, cada método novo no
 * contrato quebrava a compilação de todo jogador falso, o que empurra na direção errada — dificultar
 * a evolução do contrato para não mexer em teste.
 */
public class TestPlayer implements PlayerHandle {
    /** Tudo que foi enviado ao jogador, em ordem. */
    public final List<String> received = new ArrayList<>();

    /** Inventário simulado. */
    public final Map<String, Integer> inventory = new LinkedHashMap<>();

    public String held = "minecraft:air";
    public int[] where = {0, 64, 0};
    public int capacity = Integer.MAX_VALUE;

    public String menuId;
    public List<String> menuItems = List.of();

    public String screenId;
    public String screenJson;
    public String hudJson;
    public boolean screensSupported = true;

    @Override
    public String name() {
        return "Steve";
    }

    @Override
    public String uuid() {
        return "00000000-0000-0000-0000-000000000001";
    }

    @Override
    public void sendMessage(String message) {
        received.add(message);
    }

    @Override
    public void sendActionBar(String message) {
        received.add("[bar] " + message);
    }

    @Override
    public String heldItem() {
        return held;
    }

    @Override
    public int countItem(String itemId) {
        return inventory.getOrDefault(itemId, 0);
    }

    @Override
    public int giveItem(String itemId, int count) {
        int current = inventory.getOrDefault(itemId, 0);
        int fits = Math.max(0, Math.min(count, capacity - current));
        if (fits > 0) inventory.put(itemId, current + fits);
        return count - fits;
    }

    @Override
    public int takeItem(String itemId, int count) {
        int current = inventory.getOrDefault(itemId, 0);
        int removed = Math.min(current, count);
        if (removed > 0) inventory.put(itemId, current - removed);
        return removed;
    }

    @Override
    public int[] position() {
        return where;
    }

    @Override
    public float[] health() {
        return new float[]{18.0f, 20.0f};
    }

    @Override
    public void teleport(double x, double y, double z) {
        where = new int[]{(int) x, (int) y, (int) z};
    }

    @Override
    public void openMenu(String id, String title, int rows, List<String> items) {
        menuId = id;
        received.add("[menu] " + title + " (" + rows + " linhas)");
        menuItems = items;
    }

    @Override
    public boolean updateMenu(List<String> items) {
        if (menuId == null) return false;
        menuItems = items;
        return true;
    }

    @Override
    public String openMenuId() {
        return menuId;
    }

    @Override
    public void closeMenu() {
        menuId = null;
    }

    @Override
    public boolean supportsScreens() {
        return screensSupported;
    }

    @Override
    public boolean openScreen(String id, String descriptionJson) {
        if (!screensSupported) return false;
        screenId = id;
        screenJson = descriptionJson;
        return true;
    }

    @Override
    public boolean updateScreen(String descriptionJson) {
        if (screenId == null) return false;
        screenJson = descriptionJson;
        return true;
    }

    @Override
    public void closeScreen() {
        screenId = null;
        screenJson = null;
    }

    @Override
    public String openScreenId() {
        return screenId;
    }

    @Override
    public void setHud(String descriptionJson) {
        hudJson = descriptionJson;
    }

    /** Sobreposicoes registradas, por identificador. */
    public final java.util.Map<String, String> overlays = new java.util.LinkedHashMap<>();

    /** Tamanho de tela que este jogador reporta; null simula cliente que nao informou. */
    public int[] screenSize = {427, 240};

    @Override
    public int[] screenSize() {
        return screenSize;
    }

    @Override
    public boolean setOverlay(String overlayId, String descriptionJson) {
        overlays.put(overlayId, descriptionJson);
        return true;
    }

    @Override
    public boolean clearOverlay(String overlayId) {
        return overlays.remove(overlayId) != null;
    }
}

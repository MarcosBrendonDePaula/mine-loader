package dev.lualoader.client;

import dev.lualoader.client.mixin.HandledScreenAccessor;
import dev.lualoader.network.ScreenPayloads;
import dev.lualoader.ui.ScreenProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ingame.AbstractFurnaceScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desenho do mod sobre telas que o próprio jogo abre.
 *
 * <p>É a diferença entre abrir uma tela e participar de uma existente. Uma tela do loader substitui
 * o que estava na frente; uma sobreposição acompanha o inventário, o forno ou o menu de pausa, e é
 * o que permite acrescentar um botão de configuração ao menu, um painel ao lado do baú ou um aviso
 * na tela de morte — nenhum deles poderia substituir a tela em que aparece.
 *
 * <p>O registro vive no cliente e sobrevive a abrir e fechar a tela alvo: o servidor manda uma vez e
 * a sobreposição volta sempre que aquela tela aparecer, sem depender de um pacote por abertura.
 */
public final class GameScreenOverlay {
    /** Uma sobreposição registrada: sobre qual tela desenhar e o quê. */
    private record Overlay(String id, String target, ScreenModel model) {
    }

    /**
     * Sobreposições ativas, na ordem em que chegaram.
     *
     * <p>A ordem importa quando dois mods desenham na mesma tela: quem registrou antes fica atrás, a
     * mesma regra que já vale entre os elementos de uma tela.
     */
    private static final Map<String, Overlay> ACTIVE = new LinkedHashMap<>();

    /**
     * Recorte e rolagem da tela aberta agora.
     *
     * <p>Uma so, e nao uma por sobreposicao: elas dividem a mesma tela, e um viewport pertence a
     * tela em que aparece. Trocar de tela zera o que rolou, que e o comportamento esperado.
     */
    private static final ScreenSurface SURFACE = new ScreenSurface();

    private GameScreenOverlay() {
    }

    /** Registra ou substitui uma sobreposição. */
    public static void set(String overlayId, String description) {
        ScreenModel model = ScreenModel.parse(description);
        if (model == null) {
            LuaLoaderClient.LOGGER.error("Descricao de sobreposicao invalida em {}", overlayId);
            return;
        }

        synchronized (ACTIVE) {
            if (!ACTIVE.containsKey(overlayId) && ACTIVE.size() >= ScreenProtocol.MAX_OVERLAYS) {
                LuaLoaderClient.LOGGER.warn("Sobreposicao {} recusada: o limite de {} ja foi atingido",
                        overlayId, ScreenProtocol.MAX_OVERLAYS);
                return;
            }
            ACTIVE.put(overlayId, new Overlay(overlayId, model.target(), model));
        }
    }

    /** Remove uma sobreposição registrada. */
    public static void clear(String overlayId) {
        synchronized (ACTIVE) {
            ACTIVE.remove(overlayId);
        }
    }

    /** Esquece tudo. Chamado ao sair do servidor, para nada vazar para a próxima sessão. */
    public static void clearAll() {
        synchronized (ACTIVE) {
            ACTIVE.clear();
        }
    }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            List<Overlay> applicable = forScreen(screen);
            if (applicable.isEmpty()) return;

            SURFACE.reset();
            for (Overlay overlay : applicable) {
                addButtons(screen, overlay);
            }

            ScreenMouseEvents.afterMouseClick(screen).register(
                    (target, mouseX, mouseY, button) -> click(target, (int) mouseX, (int) mouseY));

            ScreenMouseEvents.allowMouseScroll(screen).register(
                    (target, mouseX, mouseY, horizontal, vertical) ->
                            !scroll(target, (int) mouseX, (int) mouseY, vertical));

            // Depois do render da tela: os elementos do mod ficam sobre o que o jogo desenhou, e a
            // caixa de ajuda sobre ambos.
            ScreenEvents.afterRender(screen).register(
                    (target, context, mouseX, mouseY, delta) -> draw(target, context, mouseX, mouseY));
        });

        // Sem isto, a sobreposição de um servidor continuaria registrada ao entrar em outro.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearAll());
    }

    private static List<Overlay> forScreen(Screen screen) {
        List<Overlay> matching = new ArrayList<>();
        synchronized (ACTIVE) {
            for (Overlay overlay : ACTIVE.values()) {
                if (matches(overlay.target(), screen)) matching.add(overlay);
            }
        }
        return matching;
    }

    /**
     * Traduz o nome do alvo para a tela do cliente.
     *
     * <p>O mod nomeia o alvo, não a classe: classes do cliente mudam entre versões do jogo e não
     * existem em outra plataforma, então deixar um mod citá-las quebraria a portabilidade que a
     * separação em camadas existe para manter.
     */
    private static boolean matches(String target, Screen screen) {
        return switch (target) {
            case "any" -> true;
            case "container" -> screen instanceof HandledScreen<?>;
            case "inventory" -> screen instanceof InventoryScreen;
            case "creative" -> screen instanceof CreativeInventoryScreen;
            case "crafting" -> screen instanceof CraftingScreen;
            case "furnace" -> screen instanceof AbstractFurnaceScreen<?>;
            case "chest" -> screen instanceof GenericContainerScreen;
            case "anvil" -> screen instanceof AnvilScreen;
            case "pause" -> screen instanceof GameMenuScreen;
            case "death" -> screen instanceof DeathScreen;
            case "title" -> screen instanceof TitleScreen;
            default -> false;
        };
    }

    /**
     * Área da janela da tela do jogo, para as âncoras {@code gui_}.
     *
     * <p>Fora de uma tela de container não há janela a que se prender, e a área passa a ser a tela
     * inteira: num alvo como o menu de pausa, {@code gui_top_left} então equivale a
     * {@code top_left}, em vez de posicionar o elemento contra um retângulo que não existe.
     */
    private static ScreenRenderer.Bounds guiBounds(Screen screen) {
        if (screen instanceof HandledScreen<?> && screen instanceof HandledScreenAccessor accessor) {
            return new ScreenRenderer.Bounds(
                    accessor.lua_loader$x(), accessor.lua_loader$y(),
                    accessor.lua_loader$backgroundWidth(), accessor.lua_loader$backgroundHeight());
        }
        return new ScreenRenderer.Bounds(0, 0, screen.width, screen.height);
    }

    private static void addButtons(Screen screen, Overlay overlay) {
        ScreenRenderer.Bounds surface = new ScreenRenderer.Bounds(0, 0, screen.width, screen.height);
        ScreenRenderer.Bounds gui = guiBounds(screen);

        for (ScreenModel.Element element : overlay.model().elements()) {
            if (!element.type().equals("button")) continue;

            int[] position = ScreenRenderer.resolve(element, surface, gui);
            Screens.getButtons(screen).add(ButtonWidget
                    .builder(Text.literal(element.text()),
                            widget -> send(overlay.id(), element.id(), "click", ""))
                    .dimensions(position[0], position[1],
                            Math.max(20, element.w()), Math.max(12, element.h()))
                    .build());
        }
    }

    private static void draw(Screen screen, DrawContext context, int mouseX, int mouseY) {
        List<Overlay> applicable = forScreen(screen);
        if (applicable.isEmpty()) return;

        var textRenderer = MinecraftClient.getInstance().textRenderer;
        ScreenRenderer.Bounds surface = surfaceBounds(screen);
        ScreenRenderer.Bounds gui = guiBounds(screen);

        String tooltip = null;
        for (Overlay overlay : applicable) {
            // Botao ja e um widget da tela: desenha-lo de novo o pintaria duas vezes.
            String found = SURFACE.draw(context, textRenderer, overlay.model().elements(),
                    surface, gui, mouseX, mouseY, true);
            if (found != null) tooltip = found;
        }
        ScreenRenderer.drawTooltip(context, textRenderer, tooltip, mouseX, mouseY);
    }

    /** Clique em uma celula de grade, que nao e um widget e por isso nao chega por outro caminho. */
    private static void click(Screen screen, int mouseX, int mouseY) {
        for (Overlay overlay : forScreen(screen)) {
            Object[] cell = SURFACE.clickedCell(overlay.model().elements(),
                    surfaceBounds(screen), guiBounds(screen), mouseX, mouseY);
            if (cell != null) {
                send(overlay.id(), (String) cell[0], "click", String.valueOf(cell[1]));
                return;
            }
        }
    }

    /** Rolagem sobre um viewport. Devolve true quando a tela de baixo nao deve ver o evento. */
    private static boolean scroll(Screen screen, int mouseX, int mouseY, double amount) {
        for (Overlay overlay : forScreen(screen)) {
            if (SURFACE.scroll(overlay.model().elements(), surfaceBounds(screen),
                    guiBounds(screen), mouseX, mouseY, amount)) {
                return true;
            }
        }
        return false;
    }

    private static ScreenRenderer.Bounds surfaceBounds(Screen screen) {
        return new ScreenRenderer.Bounds(0, 0, screen.width, screen.height);
    }

    private static void send(String overlayId, String elementId, String action, String value) {
        ClientPlayNetworking.send(new ScreenPayloads.ScreenEvent(
                ScreenProtocol.VERSION, overlayId, elementId, action, value));
    }
}

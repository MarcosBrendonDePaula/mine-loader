package dev.lualoader.client;

import dev.lualoader.network.ScreenPayloads;
import dev.lualoader.ui.ScreenProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Tela genérica que desenha qualquer descrição válida.
 *
 * <p>Não existe uma tela por mod: existe esta, que interpreta dados. Um mod novo não exige código de
 * cliente novo, e o cliente não precisa saber que mods existem — é o que permite que um mod baixado
 * por URL tenha interface própria.
 */
public class LuaScreen extends Screen {
    private final String screenId;
    private ScreenModel model;

    /** Campos de texto vivos, para o que foi digitado sobreviver a um redesenho. */
    private final Map<String, TextFieldWidget> fields = new HashMap<>();

    public LuaScreen(String screenId, ScreenModel model) {
        super(Text.literal(model.title()));
        this.screenId = screenId;
        this.model = model;
    }

    public String screenId() {
        return screenId;
    }

    /** Troca a descrição sem fechar, preservando o texto já digitado. */
    public void updateModel(ScreenModel novo) {
        Map<String, String> digitado = new HashMap<>();
        fields.forEach((id, campo) -> digitado.put(id, campo.getText()));

        this.model = novo;
        clearChildren();
        fields.clear();
        buildWidgets(digitado);
    }

    @Override
    protected void init() {
        fields.clear();
        buildWidgets(Map.of());
    }

    private int originX() {
        return (width - model.width()) / 2;
    }

    private int originY() {
        return (height - model.height()) / 2;
    }

    /** Resolve a posição final de um elemento a partir da âncora declarada. */
    private int[] resolve(ScreenModel.Element element) {
        int baseX = originX();
        int baseY = originY();

        // A âncora move o ponto de origem para um canto da tela do jogo, e não da janela do mod:
        // é o que permite prender um elemento à borda em telas de tamanhos diferentes.
        switch (element.anchor()) {
            case "top_left" -> { baseX = 0; baseY = 0; }
            case "top" -> { baseX = width / 2; baseY = 0; }
            case "top_right" -> { baseX = width; baseY = 0; }
            case "left" -> { baseX = 0; baseY = height / 2; }
            case "right" -> { baseX = width; baseY = height / 2; }
            case "bottom_left" -> { baseX = 0; baseY = height; }
            case "bottom" -> { baseX = width / 2; baseY = height; }
            case "bottom_right" -> { baseX = width; baseY = height; }
            default -> { }
        }
        return new int[]{baseX + element.x(), baseY + element.y()};
    }

    private void buildWidgets(Map<String, String> digitado) {
        for (ScreenModel.Element element : model.elements()) {
            int[] pos = resolve(element);

            if (element.type().equals("button")) {
                addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget
                        .builder(Text.literal(element.text()), botao -> send(element.id(), "click", ""))
                        .dimensions(pos[0], pos[1], Math.max(20, element.w()), Math.max(12, element.h()))
                        .build());
            } else if (element.type().equals("input")) {
                TextFieldWidget campo = new TextFieldWidget(textRenderer, pos[0], pos[1],
                        Math.max(20, element.w()), Math.max(12, element.h()),
                        Text.literal(element.text()));
                campo.setMaxLength(ScreenProtocol.MAX_TEXT_LENGTH);
                campo.setText(digitado.getOrDefault(element.id(), element.value()));
                campo.setChangedListener(texto -> send(element.id(), "change", texto));

                fields.put(element.id(), campo);
                addDrawableChild(campo);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        for (ScreenModel.Element element : model.elements()) {
            int[] pos = resolve(element);
            int x = pos[0];
            int y = pos[1];

            switch (element.type()) {
                case "panel" -> context.fill(x, y, x + element.w(), y + element.h(), element.color());
                case "label" -> {
                    if (element.scale() == 1.0) {
                        context.drawTextWithShadow(textRenderer, Text.literal(element.text()),
                                x, y, element.color());
                    } else {
                        // A escala multiplica a matriz, entao a posicao precisa ser dividida por ela.
                        context.getMatrices().push();
                        context.getMatrices().scale((float) element.scale(), (float) element.scale(), 1f);
                        context.drawTextWithShadow(textRenderer, Text.literal(element.text()),
                                (int) (x / element.scale()), (int) (y / element.scale()), element.color());
                        context.getMatrices().pop();
                    }
                }
                case "progress" -> {
                    context.fill(x, y, x + element.w(), y + element.h(), 0xFF303030);
                    int preenchido = (int) (element.w() * Math.max(0, Math.min(1, element.progress())));
                    context.fill(x, y, x + preenchido, y + element.h(), element.color());
                }
                case "item" -> {
                    Identifier id = Identifier.tryParse(element.item());
                    if (id != null && Registries.ITEM.containsId(id)) {
                        ItemStack stack = new ItemStack(Registries.ITEM.get(id), element.count());
                        context.drawItem(stack, x, y);
                        context.drawItemInSlot(textRenderer, stack, x, y);
                    }
                }
                case "image" -> {
                    Identifier textura = Identifier.tryParse(element.texture());
                    if (textura != null) {
                        context.drawTexture(textura, x, y, 0, 0,
                                element.w(), element.h(), element.w(), element.h());
                    }
                }
                default -> {
                    // Tipo desconhecido: ignorado de proposito, para uma tela nova nao quebrar em
                    // um cliente antigo. Botao e campo ja foram criados como widgets.
                }
            }
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private void send(String elementId, String action, String value) {
        ClientPlayNetworking.send(new ScreenPayloads.ScreenEvent(
                ScreenProtocol.VERSION, screenId, elementId, action, value));
    }

    @Override
    public void close() {
        send("", "close", "");
        super.close();
    }

    @Override
    public boolean shouldPause() {
        // Pausar seria errado em servidor, e inconsistente entre um mundo local e um dedicado.
        return false;
    }
}

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

    /**
     * Troca a descrição sem fechar a tela.
     *
     * <p>Campos de texto que continuam existindo são reaproveitados em vez de recriados. Recriar um
     * campo a cada atualização faria o jogador perder o foco e a posição do cursor a cada tecla,
     * porque um script costuma redesenhar a tela em resposta ao próprio evento de digitação.
     */
    public void updateModel(ScreenModel novo) {
        Map<String, TextFieldWidget> anteriores = new HashMap<>(fields);
        String focado = null;
        for (Map.Entry<String, TextFieldWidget> entrada : anteriores.entrySet()) {
            if (entrada.getValue().isFocused()) focado = entrada.getKey();
        }

        this.model = novo;
        clearChildren();
        fields.clear();
        buildWidgets(anteriores);

        // Devolve o foco ao campo em que o jogador estava digitando.
        if (focado != null && fields.containsKey(focado)) {
            setFocused(fields.get(focado));
            fields.get(focado).setFocused(true);
        }
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

    /**
     * Resolve a posição final de um elemento.
     *
     * <p>Sem âncora, a coordenada é relativa ao canto da janela do mod, que fica centralizada na
     * tela do jogo — é o que faz uma janela de 220 por 140 aparecer no meio, com os elementos
     * posicionados a partir do canto dela.
     *
     * <p>Com âncora, a origem passa a ser um ponto da tela do jogo, o que permite prender um
     * elemento à borda em resoluções diferentes. Nas âncoras centradas, metade do tamanho do
     * elemento é descontada: caso contrário o canto do elemento é que ficaria no centro, e não o
     * elemento.
     */
    private int[] resolve(ScreenModel.Element element) {
        // Sem âncora: relativo à janela do mod, já centralizada.
        if (element.anchor().isBlank()) {
            return new int[]{originX() + element.x(), originY() + element.y()};
        }

        int baseX;
        int baseY;
        switch (element.anchor()) {
            case "top_left" -> { baseX = 0; baseY = 0; }
            case "top" -> { baseX = width / 2 - element.w() / 2; baseY = 0; }
            case "top_right" -> { baseX = width - element.w(); baseY = 0; }
            case "left" -> { baseX = 0; baseY = height / 2 - element.h() / 2; }
            case "right" -> { baseX = width - element.w(); baseY = height / 2 - element.h() / 2; }
            case "bottom_left" -> { baseX = 0; baseY = height - element.h(); }
            case "bottom" -> { baseX = width / 2 - element.w() / 2; baseY = height - element.h(); }
            case "bottom_right" -> { baseX = width - element.w(); baseY = height - element.h(); }
            default -> { baseX = width / 2 - element.w() / 2; baseY = height / 2 - element.h() / 2; }
        }
        return new int[]{baseX + element.x(), baseY + element.y()};
    }

    private void buildWidgets(Map<String, TextFieldWidget> anteriores) {
        for (ScreenModel.Element element : model.elements()) {
            int[] pos = resolve(element);

            if (element.type().equals("button")) {
                addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget
                        .builder(Text.literal(element.text()), botao -> send(element.id(), "click", ""))
                        .dimensions(pos[0], pos[1], Math.max(20, element.w()), Math.max(12, element.h()))
                        .build());
            } else if (element.type().equals("input")) {
                TextFieldWidget existente = anteriores.get(element.id());

                if (existente != null) {
                    // Reaproveita o campo: o texto, o cursor e a selecao continuam onde estavam.
                    existente.setPosition(pos[0], pos[1]);
                    fields.put(element.id(), existente);
                    addDrawableChild(existente);
                    continue;
                }

                TextFieldWidget campo = new TextFieldWidget(textRenderer, pos[0], pos[1],
                        Math.max(20, element.w()), Math.max(12, element.h()),
                        Text.literal(element.text()));
                campo.setMaxLength(ScreenProtocol.MAX_TEXT_LENGTH);
                campo.setText(element.value());
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
                        // Escala inteira mantem a fonte bitmap nitida; uma fracionaria interpola e
                        // borra, e o arredondamento abaixo evita ainda cair em meio pixel.
                        float escala = (float) element.scale();
                        context.getMatrices().push();
                        context.getMatrices().scale(escala, escala, 1f);
                        context.drawTextWithShadow(textRenderer, Text.literal(element.text()),
                                Math.round(x / escala), Math.round(y / escala), element.color());
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
        LuaLoaderClient.LOGGER.info("Enviando evento: tela={} elemento={} acao={}",
                screenId, elementId, action);
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

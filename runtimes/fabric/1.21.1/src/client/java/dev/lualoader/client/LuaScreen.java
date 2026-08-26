package dev.lualoader.client;

import dev.lualoader.ui.ScreenLayout;
import dev.lualoader.ui.ScreenModel;
import dev.lualoader.network.ScreenPayloads;
import dev.lualoader.ui.ScreenProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

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

    /**
     * Os widgets desta tela, na ordem em que devem ser desenhados.
     *
     * <p>O jogo desenha os proprios widgets dentro de {@code Screen.render}, que tambem repinta o
     * fundo antes deles. Chamar aquele metodo depois de desenhar os elementos do mod apagaria tudo:
     * o painel sumia atras do fundo e so os botoes sobreviviam. Mantendo a lista aqui, a ordem fica
     * explicita — fundo, elementos, widgets.
     */
    private final java.util.List<net.minecraft.client.gui.widget.ClickableWidget> widgets =
            new java.util.ArrayList<>();

    /**
     * A camada de cada widget, na mesma ordem da lista acima.
     *
     * <p>Um botao e widget do jogo e desenha na profundidade zero, enquanto os elementos com camada
     * desenham deslocados para frente. Um modal aberto sobre outro cobria os proprios botoes: o
     * painel do modal de cima passava na frente deles, e o botao sumia de uma tela que continuava
     * respondendo ao clique — o pior sintoma possivel, porque nada parece quebrado.
     */
    private final java.util.List<Integer> widgetLayers = new java.util.ArrayList<>();

    /** Recorte, rolagem e clique de celula, compartilhados com a sobreposicao. */
    private final ScreenSurface surface = new ScreenSurface();

    /**
     * Para onde vão os cliques desta tela.
     *
     * <p>Quase sempre é a rede: o mod vive no servidor, e é ele quem decide o que um clique faz.
     * Mas o menu principal não tem servidor, e uma tela aberta ali precisa resolver o clique aqui
     * mesmo. Um destino trocável é o que permite as duas coisas sem duas telas.
     *
     * <p>Continua sendo <b>dados</b>: o que muda é quem interpreta a ação, e não o cliente passar a
     * executar código que veio de fora. A regra do {@code UI_SPEC.md} fica de pé.
     */
    public interface EventSink {
        void accept(String screenId, String elementId, String action, String value);
    }

    /** Nulo significa "manda pela rede", que é o caso de toda tela vinda de um mod. */
    private final EventSink sink;

    public LuaScreen(String screenId, ScreenModel model) {
        this(screenId, model, null);
    }

    public LuaScreen(String screenId, ScreenModel model, EventSink sink) {
        super(Text.literal(model.title()));
        this.screenId = screenId;
        this.model = model;
        this.sink = sink;
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
    public void updateModel(ScreenModel updated) {
        Map<String, TextFieldWidget> previous = new HashMap<>(fields);
        String focused = null;
        for (Map.Entry<String, TextFieldWidget> entry : previous.entrySet()) {
            if (entry.getValue().isFocused()) focused = entry.getKey();
        }

        this.model = updated;
        clearChildren();
        fields.clear();
        widgets.clear();
        widgetLayers.clear();
        buildWidgets(previous);

        // Devolve o foco ao campo em que o jogador estava digitando.
        if (focused != null && fields.containsKey(focused)) {
            setFocused(fields.get(focused));
            fields.get(focused).setFocused(true);
        }
    }

    @Override
    protected void init() {
        fields.clear();
        widgets.clear();
        widgetLayers.clear();
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
     * posicionados a partir do canto dela. Com âncora, a origem passa a ser um ponto dessa mesma
     * janela, o que mantém o elemento preso à borda dela em qualquer resolução.
     */
    private ScreenLayout.Bounds bounds() {
        return new ScreenLayout.Bounds(originX(), originY(), model.width(), model.height());
    }

    private int[] resolve(ScreenModel.Element element) {
        ScreenLayout.Bounds window = bounds();
        // Numa tela propria nao ha tela do jogo por baixo, entao as ancoras gui_ caem na janela do
        // mod: e a unica leitura util delas aqui, e evita um elemento sumir sem explicacao.
        return ScreenRenderer.resolve(element, window, window);
    }

    private void buildWidgets(Map<String, TextFieldWidget> previous) {
        for (ScreenModel.Element element : model.elements()) {
            int[] pos = resolve(element);

            if (element.type().equals("button")) {
                widgets.add(addDrawableChild(net.minecraft.client.gui.widget.ButtonWidget
                        .builder(Text.literal(element.text()), button -> send(element.id(), "click", ""))
                        .dimensions(pos[0], pos[1], Math.max(20, element.w()), Math.max(12, element.h()))
                        .build()));
                widgetLayers.add(element.layer());
            } else if (element.type().equals("input")) {
                TextFieldWidget existing = previous.get(element.id());

                if (existing != null) {
                    // Reaproveita o campo: o texto, o cursor e a selecao continuam onde estavam.
                    existing.setPosition(pos[0], pos[1]);
                    fields.put(element.id(), existing);
                    widgets.add(addDrawableChild(existing));
                    widgetLayers.add(element.layer());
                    continue;
                }

                TextFieldWidget field = new TextFieldWidget(textRenderer, pos[0], pos[1],
                        Math.max(20, element.w()), Math.max(12, element.h()),
                        Text.literal(element.text()));
                field.setMaxLength(ScreenProtocol.MAX_TEXT_LENGTH);
                field.setText(element.value());
                field.setChangedListener(text -> send(element.id(), "change", text));

                fields.put(element.id(), field);
                widgets.add(addDrawableChild(field));
                widgetLayers.add(element.layer());
            }
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // O padrao do jogo desfoca o mundo. Um painel consultado durante a partida fica melhor sem
        // isso, entao o desfoque so acontece quando o mod pede.
        if (model.blur()) {
            super.renderBackground(context, mouseX, mouseY, delta);
            return;
        }
        if (model.dim()) {
            context.fill(0, 0, width, height, 0x60000000);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        ScreenLayout.Bounds window = bounds();
        String tooltip = surface.draw(context, textRenderer, model.elements(),
                window, window, mouseX, mouseY, true);

        // Os widgets sao desenhados por ultimo, para ficarem sobre os elementos do mod. Nao se
        // chama Screen.render aqui: ele repintaria o fundo por cima do que acabou de ser desenhado.
        for (int i = 0; i < widgets.size(); i++) {
            int layer = i < widgetLayers.size() ? widgetLayers.get(i) : 0;
            if (layer > 0) {
                context.getMatrices().push();
                context.getMatrices().translate(0f, 0f, layer * 250f);
            }
            try {
                widgets.get(i).render(context, mouseX, mouseY, delta);
            } finally {
                if (layer > 0) context.getMatrices().pop();
            }
        }
        // O icone de um botao vem depois dele, senao o proprio botao o cobriria.
        surface.drawButtonIcons(context, textRenderer, model.elements(), window, window);

        // O texto de ajuda vem depois de tudo, senao os widgets cobririam a caixa.
        ScreenRenderer.drawTooltip(context, textRenderer, tooltip, mouseX, mouseY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Uma celula de grade nao e um widget: o clique e resolvido aqui, e o indice da celula vai
        // no valor do evento para o script saber qual item foi apontado.
        Object[] cell = surface.clickedCell(model.elements(), bounds(), bounds(),
                (int) mouseX, (int) mouseY);
        if (cell != null) {
            send((String) cell[0], "click", String.valueOf(cell[1]));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (surface.scroll(model.elements(), bounds(), bounds(),
                (int) mouseX, (int) mouseY, vertical)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    private void send(String elementId, String action, String value) {
        if (sink != null) {
            sink.accept(screenId, elementId, action, value);
            return;
        }

        LuaLoaderClient.LOGGER.info("Enviando evento: tela={} elemento={} acao={}",
                screenId, elementId, action);
        ClientPlayNetworking.send(new ScreenPayloads.ScreenEvent(
                ScreenProtocol.VERSION, screenId, elementId, action, value));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Enter em um campo de texto vira a acao submit, que o protocolo ja previa mas o cliente
        // nunca enviava: sem isso, confirmar um formulario exigia um botao ao lado do campo.
        boolean enter = keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;

        if (enter) {
            for (Map.Entry<String, TextFieldWidget> entry : fields.entrySet()) {
                if (entry.getValue().isFocused()) {
                    send(entry.getKey(), "submit", entry.getValue().getText());
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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

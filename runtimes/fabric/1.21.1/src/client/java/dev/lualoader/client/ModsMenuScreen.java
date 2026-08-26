package dev.lualoader.client;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import dev.lualoader.ui.ScreenModel;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A tela de mods do menu principal.
 *
 * <p><b>É a primeira tela do loader sem servidor do outro lado.</b> Todas as outras nascem de um
 * mod, que vive no servidor e decide o que cada clique faz. Aqui não há conexão: a descrição é
 * montada localmente e o clique é resolvido aqui mesmo.
 *
 * <p>Isso <b>não</b> quebra a regra do {@code UI_SPEC.md}. A regra é que o cliente interpreta dados
 * e nunca código; o que muda é de onde vêm os dados — do próprio loader, em vez da rede. Nenhum
 * script roda aqui, e nenhum mod escolhe o que esta tela desenha.
 *
 * <p>Ela lê o <b>catálogo</b>, e não a lista de mods carregados: um mod desativado é pulado na
 * carga, e mostrar só o que carregou nunca deixaria alguém reativá-lo.
 */
public final class ModsMenuScreen {
    private ModsMenuScreen() {
    }

    /** Id da tela. Só aparece no log; não há servidor para casá-lo com nada. */
    private static final String SCREEN_ID = "lua_loader:mods";

    private static final int WIDTH = 320;
    private static final int HEIGHT = 220;

    /** Quantos mods cabem numa página, respeitando o rodapé. */
    private static final int PER_PAGE = 5;
    private static final int ROW_HEIGHT = 26;

    /**
     * Cores em ARGB, com o alfa escrito.
     *
     * <p>O renderizador usa o valor como veio, e um {@code 0xFFFFFF} sem alfa é alfa zero — texto
     * que não aparece. É o tipo de erro que passa despercebido no código porque o número
     * <i>parece</i> branco, e só a tela denuncia.
     */
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF909090;
    private static final int AMBER = 0xFFFFD060;
    private static final int GREEN = 0xFF7BC96F;
    private static final int RED = 0xFFE06C6C;

    // ------------------------------------------------------------------ estado da tela

    /**
     * Dois estados, e não duas telas nem um modal.
     *
     * <p>O renderizador não tem janela sobre janela, e abrir uma segunda tela obrigaria a guardar
     * de onde se veio para poder voltar. Trocar o conteúdo e redesenhar é como o próprio
     * {@code gerenciador} resolve isso dentro do jogo.
     */
    private static boolean installing;

    /** O endereço digitado. Sobrevive ao redesenho, que acontece a cada tecla. */
    private static String url = "";

    /** O texto do filtro. Idem. */
    private static String filter = "";

    private static int page;
    private static String status = "";

    public static void open(Screen parent) {
        installing = false;
        status = "";
        page = 0;
        draw(parent);
    }

    private static void draw(Screen parent) {
        ScreenModel model = ScreenModel.parse(describe());
        if (model == null) {
            LuaLoaderClient.LOGGER.error("Descricao da tela de mods invalida");
            return;
        }
        MinecraftClient.getInstance().setScreen(new LuaScreen(SCREEN_ID, model,
                (screenId, elementId, action, value) -> onEvent(parent, elementId, action, value)));
    }

    // ------------------------------------------------------------------ o que a tela mostra

    /**
     * O catálogo, filtrado.
     *
     * <p>Lido a cada desenho, e não guardado: ligar um mod muda o estado dele no disco, e uma cópia
     * em memória mostraria o botão errado logo depois do clique.
     */
    private static List<ModLoader.CatalogEntry> visible() {
        List<ModLoader.CatalogEntry> all;
        try {
            all = new ModLoader(LoggerFactory.getLogger("lua_loader/menu"))
                    .catalog(LuaLoaderMod.modsDirectory());
        } catch (Exception error) {
            LuaLoaderClient.LOGGER.error("Nao foi possivel ler a pasta de mods", error);
            return List.of();
        }

        String needle = filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return all;

        List<ModLoader.CatalogEntry> matched = new ArrayList<>();
        for (ModLoader.CatalogEntry entry : all) {
            String name = entry.manifest() == null || entry.manifest().name == null
                    ? entry.id()
                    : entry.manifest().name;
            // Procura no nome e no id: quem lembra "guilda" e quem lembra "Guilda dos Mineiros"
            // sao a mesma pessoa em dias diferentes.
            if (entry.id().toLowerCase(Locale.ROOT).contains(needle)
                    || name.toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(entry);
            }
        }
        return matched;
    }

    private static String describe() {
        StringBuilder elements = new StringBuilder();

        // O painel vem primeiro para ficar atrás de tudo. Sem ele a tela sai preta: o renderizador
        // não desenha fundo nenhum por conta própria.
        elements.append(panel(0, 0, WIDTH, HEIGHT));

        return wrap(installing ? describeInstall(elements) : describeList(elements));
    }

    private static StringBuilder describeInstall(StringBuilder elements) {
        elements.append(label(12, 10, "Instalar mod por link", WHITE));
        elements.append(label(12, 32, "Endereco do mod.json:", GRAY));
        elements.append(input(12, 44, WIDTH - 24, 16, "url", url));

        if (!status.isEmpty()) elements.append(label(12, 70, status, AMBER));

        elements.append(label(12, HEIGHT - 46,
                "Vale a partir do proximo inicio do jogo.", AMBER));
        elements.append(button(12, HEIGHT - 30, 90, 20, "baixar", "Baixar"));
        elements.append(button(WIDTH - 92, HEIGHT - 30, 80, 20, "voltar", "Cancelar"));
        return elements;
    }

    private static StringBuilder describeList(StringBuilder elements) {
        List<ModLoader.CatalogEntry> mods = visible();

        int pages = Math.max(1, (mods.size() + PER_PAGE - 1) / PER_PAGE);
        // A página é presa à faixa a cada desenho, e não só ao virar: filtrar reduz a lista, e sem
        // isto quem estivesse na página 3 veria uma tela vazia sem entender por quê.
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;

        elements.append(label(12, 10, "Mods Lua  (" + mods.size() + ")", WHITE));
        elements.append(input(120, 6, WIDTH - 132, 16, "filtro", filter));

        int y = 30;
        int from = page * PER_PAGE;
        int to = Math.min(from + PER_PAGE, mods.size());

        for (int index = from; index < to; index++) {
            ModLoader.CatalogEntry entry = mods.get(index);
            ModManifest manifest = entry.manifest();

            String name = manifest == null || manifest.name == null ? entry.id() : manifest.name;
            String version = manifest == null || manifest.version == null ? "" : manifest.version;

            // O ícone quando há; um lugar vazio quando não. Um ícone genérico repetido em toda
            // linha polui mais do que informa.
            if (manifest != null && manifest.icon != null && !manifest.icon.isBlank()) {
                elements.append(texture(12, y, 16, 16, manifest.icon));
            }

            elements.append(label(34, y, name + "  " + version, colorOf(entry)));
            elements.append(label(34, y + 10, subtitle(entry), GRAY));

            if (entry.state() != ModLoader.State.BROKEN) {
                boolean on = entry.state() == ModLoader.State.ENABLED;
                elements.append(button(WIDTH - 76, y - 1, 64, 18,
                        "alternar:" + entry.directory().getFileName(),
                        on ? "Desligar" : "Ligar"));
            }
            y += ROW_HEIGHT;
        }

        if (mods.isEmpty()) {
            elements.append(label(12, 40,
                    filter.isBlank() ? "Nenhum mod na pasta." : "Nada encontrado.", GRAY));
        }

        if (!status.isEmpty()) elements.append(label(12, HEIGHT - 60, status, AMBER));

        elements.append(label(12, HEIGHT - 46,
                "Ligar ou desligar vale no proximo inicio do jogo.", AMBER));

        if (pages > 1) {
            elements.append(button(12, HEIGHT - 30, 24, 20, "anterior", "<"));
            elements.append(label(44, HEIGHT - 24, (page + 1) + "/" + pages, WHITE));
            elements.append(button(66, HEIGHT - 30, 24, 20, "proxima", ">"));
        }

        elements.append(button(WIDTH - 188, HEIGHT - 30, 90, 20, "instalar", "Instalar link"));
        elements.append(button(WIDTH - 92, HEIGHT - 30, 80, 20, "fechar", "Voltar"));
        return elements;
    }

    private static int colorOf(ModLoader.CatalogEntry entry) {
        return switch (entry.state()) {
            case ENABLED -> GREEN;
            case DISABLED -> GRAY;
            case BROKEN -> RED;
        };
    }

    /** A segunda linha diz o que a primeira não cabe: o id, ou o motivo de estar quebrado. */
    private static String subtitle(ModLoader.CatalogEntry entry) {
        if (entry.state() == ModLoader.State.BROKEN) {
            String reason = entry.reason() == null ? "manifesto ilegivel" : entry.reason();
            return reason.length() > 40 ? reason.substring(0, 40) + "..." : reason;
        }
        return entry.id();
    }

    // ------------------------------------------------------------------ eventos

    /**
     * Resolve o evento aqui mesmo, porque não há servidor a quem perguntar.
     *
     * <p>O vocabulário é o mesmo do protocolo — {@code click}, {@code change}, {@code submit},
     * {@code close}. Uma tela que inventasse ações próprias obrigaria o renderizador a aprender
     * dois vocabulários para desenhar a mesma coisa.
     */
    private static void onEvent(Screen parent, String elementId, String action, String value) {
        if ("close".equals(action)) return;

        // Os campos mandam cada tecla como change. Guardar aqui é o que faz o texto sobreviver ao
        // redesenho que vem depois de qualquer clique.
        if ("change".equals(action) || "submit".equals(action)) {
            if ("url".equals(elementId)) {
                url = value == null ? "" : value;
                if ("submit".equals(action)) install(parent);
                return;
            }
            if ("filtro".equals(elementId)) {
                filter = value == null ? "" : value;
                page = 0;
                draw(parent);
                return;
            }
        }
        if (!"click".equals(action)) return;

        if (elementId != null && elementId.startsWith("alternar:")) {
            toggle(parent, elementId.substring("alternar:".length()));
            return;
        }

        switch (elementId == null ? "" : elementId) {
            case "fechar" -> MinecraftClient.getInstance().setScreen(parent);
            case "instalar" -> {
                installing = true;
                status = "";
                draw(parent);
            }
            case "voltar" -> {
                installing = false;
                status = "";
                draw(parent);
            }
            case "baixar" -> install(parent);
            case "anterior" -> {
                page = Math.max(0, page - 1);
                draw(parent);
            }
            case "proxima" -> {
                page++;
                draw(parent);
            }
            default -> {
            }
        }
    }

    /**
     * Liga ou desliga o mod, escrevendo no manifesto dele.
     *
     * <p>Vale no próximo início, e a tela diz isso. Os registros do jogo congelam na inicialização:
     * desligar agora não desfaz o bloco que aquele mod já registrou, e ligar não faz aparecer o que
     * ele registraria.
     */
    private static void toggle(Screen parent, String folder) {
        try {
            Path directory = LuaLoaderMod.modsDirectory().resolve(folder);
            var loader = new ModLoader(LoggerFactory.getLogger("lua_loader/menu"));

            boolean enabled = loader.catalog(LuaLoaderMod.modsDirectory()).stream()
                    .filter(entry -> entry.directory().equals(directory))
                    .anyMatch(entry -> entry.state() == ModLoader.State.ENABLED);

            if (loader.setEnabled(directory, !enabled)) {
                status = folder + (enabled ? " desligado" : " ligado")
                        + " - vale no proximo inicio.";
            }
        } catch (Exception error) {
            status = describeFailure(error);
        }
        draw(parent);
    }

    /**
     * Baixa o mod para a pasta, sem tentar carregá-lo.
     *
     * <p><b>Não carrega de propósito, e é a decisão central desta tela.</b> Os registros do jogo
     * congelam na inicialização: um mod carregado agora não conseguiria registrar bloco, item nem
     * espécie, e quem instalasse ficaria procurando um conteúdo que não existe.
     */
    private static void install(Screen parent) {
        String address = url.trim();
        if (address.isEmpty()) {
            status = "Digite o endereco de um mod.json.";
            draw(parent);
            return;
        }

        try {
            // Preview antes de instalar: o erro de endereço aparece antes de qualquer arquivo ser
            // escrito no disco de quem joga.
            var preview = LuaLoaderMod.modInstaller().preview(address);
            LuaLoaderMod.modInstaller().install(preview);

            status = "Instalado: " + preview.name() + " " + preview.version();
            url = "";
            installing = false;
        } catch (Exception error) {
            // A mensagem da exceção, e não "falhou": quem está instalando precisa saber se o
            // endereço está errado, se a rede caiu ou se o manifesto é inválido.
            status = describeFailure(error);
        }
        draw(parent);
    }

    private static String describeFailure(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }

    // ------------------------------------------------------------------ elementos

    private static String wrap(StringBuilder elements) {
        return "{" + quoted("title") + ":" + quoted("Mods Lua") + ","
                + quoted("width") + ":" + WIDTH + ","
                + quoted("height") + ":" + HEIGHT + ","
                + quoted("elements") + ":[" + trimTrailingComma(elements.toString()) + "]}";
    }

    private static String panel(int x, int y, int w, int h) {
        return "{" + quoted("type") + ":" + quoted("panel") + ","
                + quoted("x") + ":" + x + "," + quoted("y") + ":" + y + ","
                + quoted("w") + ":" + w + "," + quoted("h") + ":" + h + ","
                + quoted("style") + ":" + quoted("vanilla") + "},";
    }

    private static String label(int x, int y, String text, int color) {
        return "{" + quoted("type") + ":" + quoted("label") + ","
                + quoted("x") + ":" + x + "," + quoted("y") + ":" + y + ","
                + quoted("text") + ":" + quoted(text) + ","
                + quoted("color") + ":" + color + "},";
    }

    private static String button(int x, int y, int w, int h, String id, String text) {
        return "{" + quoted("type") + ":" + quoted("button") + ","
                + quoted("id") + ":" + quoted(id) + ","
                + quoted("x") + ":" + x + "," + quoted("y") + ":" + y + ","
                + quoted("w") + ":" + w + "," + quoted("h") + ":" + h + ","
                + quoted("text") + ":" + quoted(text) + "},";
    }

    private static String input(int x, int y, int w, int h, String id, String value) {
        return "{" + quoted("type") + ":" + quoted("input") + ","
                + quoted("id") + ":" + quoted(id) + ","
                + quoted("x") + ":" + x + "," + quoted("y") + ":" + y + ","
                + quoted("w") + ":" + w + "," + quoted("h") + ":" + h + ","
                + quoted("value") + ":" + quoted(value) + "},";
    }

    private static String texture(int x, int y, int w, int h, String reference) {
        return "{" + quoted("type") + ":" + quoted("texture") + ","
                + quoted("x") + ":" + x + "," + quoted("y") + ":" + y + ","
                + quoted("w") + ":" + w + "," + quoted("h") + ":" + h + ","
                + quoted("texture") + ":" + quoted(reference) + "},";
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                out.append('\\').append(character);
            } else if (character < 0x20) {
                out.append(' ');
            } else {
                out.append(character);
            }
        }
        return out.append('"').toString();
    }

    private static String trimTrailingComma(String value) {
        return value.endsWith(",") ? value.substring(0, value.length() - 1) : value;
    }
}

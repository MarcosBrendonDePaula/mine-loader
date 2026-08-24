package dev.lualoader.install;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * O que o loader tem permissão para instalar sozinho.
 *
 * <p>Um mod pode declarar que precisa de outro — uma biblioteca compartilhada, um mod de base — e
 * dizer de onde ele vem. Sem esta chave, uma dependência ausente é um erro no log e o mod
 * simplesmente não carrega: quem instalou precisa descobrir o que faltou e buscar à mão.
 *
 * <p>Ligar a chave troca isso por "o loader busca o que o mod pediu". É conveniente, e é exatamente
 * por isso que ela nasce <b>desligada</b>: com ela ligada, instalar um mod pode instalar outros que
 * quem instalou nunca viu, com as permissões que eles declararem. A conveniência é real, o risco
 * também, e a escolha é de quem administra o servidor — não do mod.
 *
 * <p>Fica em disco e não em memória porque é uma decisão do servidor, não da sessão: reiniciar não
 * pode religar sozinho o que alguém desligou de propósito.
 */
public final class InstallPolicy {
    private final Logger logger;
    private final Path file;

    /** Se o loader pode instalar as dependências que um mod declarar. */
    private boolean autoInstallDependencies;

    /**
     * Se um mod pode instalar outro pela API do loader.
     *
     * <p>É o caso do mod modular: alguém publica um conjunto em pedaços — um núcleo mais módulos
     * opcionais — e oferece dentro do jogo a lista do que existe, para quem joga escolher o que
     * instalar. Sem esta chave, o mod até consegue mostrar a lista, e instalar precisa sair do jogo.
     *
     * <p>Separada da anterior porque as duas respondem perguntas diferentes. Aquela é sobre
     * requisito: o mod não funciona sem. Esta é sobre escolha: quem joga decide, dentro de uma tela
     * que o mod desenhou. Um servidor pode querer uma e não a outra.
     */
    private boolean allowApiInstall;

    public InstallPolicy(Logger logger, Path file) {
        this.logger = logger;
        this.file = file;
        load();
    }

    public boolean autoInstallDependencies() {
        return autoInstallDependencies;
    }

    public boolean allowApiInstall() {
        return allowApiInstall;
    }

    /** Libera ou bloqueia a instalação pela API dos mods, e grava a decisão. */
    public void setAllowApiInstall(boolean enabled) {
        if (this.allowApiInstall == enabled) return;
        this.allowApiInstall = enabled;
        save();
        logger.info("Instalacao pela API dos mods: {}", enabled ? "liberada" : "bloqueada");
    }

    /** Liga ou desliga a instalação automática e grava a decisão. */
    public void setAutoInstallDependencies(boolean enabled) {
        if (this.autoInstallDependencies == enabled) return;
        this.autoInstallDependencies = enabled;
        save();
        logger.info("Instalacao automatica de dependencias: {}",
                enabled ? "ligada" : "desligada");
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;

        try {
            JsonObject root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("auto_install_dependencies")) {
                autoInstallDependencies = root.get("auto_install_dependencies").getAsBoolean();
            }
            if (root.has("allow_api_install")) {
                allowApiInstall = root.get("allow_api_install").getAsBoolean();
            }
        } catch (IOException | RuntimeException error) {
            // Um arquivo ilegivel volta ao padrao seguro, que e desligado. Falhar aqui impediria o
            // servidor de subir por causa de uma preferencia.
            logger.warn("Configuracao de instalacao ilegivel, usando o padrao: {}",
                    error.getMessage());
        }
    }

    private void save() {
        if (file == null) return;

        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("auto_install_dependencies", autoInstallDependencies);
            root.addProperty("allow_api_install", allowApiInstall);
            Files.writeString(file, new Gson().toJson(root), StandardCharsets.UTF_8);
        } catch (IOException error) {
            logger.error("Nao foi possivel gravar a configuracao de instalacao: {}",
                    error.getMessage());
        }
    }
}

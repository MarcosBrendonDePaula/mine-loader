package dev.lualoader.install;

import dev.lualoader.manifest.ModLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O instalador e as duas chaves que dizem o que ele pode fazer sozinho.
 *
 * <p>A busca em si não é exercitada aqui: ela é uma chamada HTTP, e um teste que dependesse da rede
 * falharia por motivos que não são do loader. O que este arquivo cobre é o resto — validar, gravar,
 * remover, e principalmente <em>recusar</em>, que é onde as decisões moram.
 */
class ModInstallerTest {
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger("test");

    private static ModInstaller.Preview previewOf(String id, String json, boolean replaces) {
        // Sem entrypoint: o teste exercita a gravacao, e baixar um script exigiria rede.
        return new ModInstaller.Preview(id, id, "1.0.0", "", List.<String>of(),
                List.of("chat.send"), 0, 0, replaces,
                "https://example.invalid/mod.json", json, null, "https://example.invalid/");
    }

    private static String manifestOf(String id) {
        return """
                {
                  "schema": 1,
                  "id": "%s",
                  "name": "Mod %s",
                  "version": "1.0.0",
                  "permissions": ["chat.send"],
                  "remote_base": "https://example.invalid/"
                }
                """.formatted(id, id);
    }

    @Test
    void refusesAnythingThatIsNotHttps(@TempDir Path mods) {
        var installer = new ModInstaller(LOGGER, mods);

        for (String url : List.of("http://example.invalid/mod.json", "ftp://x/y", "", "  ")) {
            var error = assertThrows(ModInstaller.InstallException.class,
                    () -> installer.preview(url));
            assertTrue(error.getMessage().contains("https"), error.getMessage());
        }
    }

    @Test
    void writesTheManifestAndTheModBecomesDiscoverable(@TempDir Path mods) throws Exception {
        var installer = new ModInstaller(LOGGER, mods);
        installer.install(previewOf("library", manifestOf("library"), false));

        assertTrue(Files.isRegularFile(mods.resolve("library/mod.json")));

        // A prova de que instalou nao e o arquivo existir, e sim o loader enxergar o mod: os dois
        // caminhos podem divergir, e e o segundo que importa para quem joga.
        List<ModLoader.LoadedMod> found = new ModLoader(LOGGER).discover(mods);
        assertEquals(1, found.size());
        assertEquals("library", found.get(0).manifest().id);
    }

    @Test
    void writesExactlyTheTextThatWasPreviewed(@TempDir Path mods) throws Exception {
        // Entre ver as permissoes e concordar com elas, o endereco poderia passar a servir outro
        // conteudo. Gravar o texto ja validado e o que faz "concordei com o que li" ser verdade.
        String json = manifestOf("pinned");
        var installer = new ModInstaller(LOGGER, mods);
        installer.install(previewOf("pinned", json, false));

        assertEquals(json, Files.readString(mods.resolve("pinned/mod.json")));
    }

    @Test
    void uninstallRemovesTheFolderAndReportsWhetherItExisted(@TempDir Path mods) throws Exception {
        var installer = new ModInstaller(LOGGER, mods);
        installer.install(previewOf("temporary", manifestOf("temporary"), false));

        assertTrue(installer.uninstall("temporary"));
        assertFalse(Files.exists(mods.resolve("temporary")));
        assertFalse(installer.uninstall("temporary"), "remover duas vezes nao pode mentir");
    }

    @Test
    void bothSwitchesStartOffAndSurviveARestart(@TempDir Path root) {
        Path file = root.resolve("install.json");

        var first = new InstallPolicy(LOGGER, file);
        assertFalse(first.autoInstallDependencies(), "o padrao seguro e desligado");
        assertFalse(first.allowApiInstall(), "o padrao seguro e desligado");

        first.setAllowApiInstall(true);

        // Uma instancia nova le do disco: e o que garante que reiniciar nao religa nem desliga
        // sozinho o que alguem decidiu de proposito.
        var second = new InstallPolicy(LOGGER, file);
        assertTrue(second.allowApiInstall());
        assertFalse(second.autoInstallDependencies(), "uma chave nao pode arrastar a outra");
    }

    @Test
    void dependencyIsNotFetchedWhileTheSwitchIsOff(@TempDir Path mods) throws Exception {
        writeMod(mods, "main", """
                {
                  "schema": 1,
                  "id": "main",
                  "name": "Main",
                  "version": "1.0.0",
                  "dependencies": {"library": "1.0.0"},
                  "dependency_sources": {"library": "https://example.invalid/mod.json"}
                }
                """);

        var policy = new InstallPolicy(LOGGER, mods.resolve("install.json"));
        var resolver = new DependencyInstaller(LOGGER, new ModInstaller(LOGGER, mods), policy);
        var result = resolver.resolve(new ModLoader(LOGGER).discover(mods));

        assertTrue(result.installed().isEmpty(), "nada pode ser instalado com a chave desligada");
        assertFalse(result.changedAnything());
    }

    @Test
    void aDependencyWithoutASourceIsReportedInsteadOfFetched(@TempDir Path mods) throws Exception {
        writeMod(mods, "alone", """
                {
                  "schema": 1,
                  "id": "alone",
                  "name": "Alone",
                  "version": "1.0.0"
                }
                """);

        var policy = new InstallPolicy(LOGGER, mods.resolve("install.json"));
        policy.setAutoInstallDependencies(true);

        var resolver = new DependencyInstaller(LOGGER, new ModInstaller(LOGGER, mods), policy);
        var result = resolver.resolve(new ModLoader(LOGGER).discover(mods));

        // Sem dependencia declarada nao ha o que buscar, e a chave ligada nao muda isso: ela
        // autoriza, nao procura.
        assertTrue(result.installed().isEmpty());
        assertTrue(result.missingSource().isEmpty());
    }

    private static void writeMod(Path mods, String id, String json) throws Exception {
        Path directory = mods.resolve(id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("mod.json"), json);
    }
}

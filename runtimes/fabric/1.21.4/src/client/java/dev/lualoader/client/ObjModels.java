package dev.lualoader.client;

/**
 * Ponto de integração de modelos OBJ do runtime Fabric 1.21.4.
 *
 * <p>A API de model loading/rendering mudou nesta versão. O bridge principal continua compilável e
 * usa os modelos JSON de reserva; o suporte OBJ fica explicitamente desativado até ser reescrito
 * sobre o contrato atual da Fabric API, evitando declarar paridade visual sem a testar.
 */
public final class ObjModels {
    private ObjModels() {
    }

    public static void register() {
        LuaLoaderClient.LOGGER.warn(
                "Suporte a modelos OBJ está temporariamente desativado no runtime Fabric 1.21.4");
    }
}

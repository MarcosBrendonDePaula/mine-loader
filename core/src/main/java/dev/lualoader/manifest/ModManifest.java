package dev.lualoader.manifest;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Modelo de dados do manifesto mod.json. O Gson usa LOWER_CASE_WITH_UNDERSCORES. */
public final class ModManifest {
    public int schema;
    public String id;
    public String name;
    public String version;
    public String description;
    public String entrypoint;
    public List<String> authors = new ArrayList<>();
    public List<String> permissions = new ArrayList<>();
    public Map<String, String> events = new LinkedHashMap<>();
    public List<BlockDefinition> blocks = new ArrayList<>();
    public List<ItemEntryDefinition> items = new ArrayList<>();
    public CreativeTabDefinition creativeTab;
    public List<StructureDefinition> structures = new ArrayList<>();
    /**
     * Mods necessarios para este funcionar, no formato {@code id -> versao minima}.
     *
     * <p>Uma dependencia declarada garante duas coisas: o mod so carrega se ela existir, e ela
     * carrega antes dele, para que {@code mod.require} ja encontre a API pronta.
     */
    public Map<String, String> dependencies = new LinkedHashMap<>();
    public boolean enabled = true;

    public static final class BlockDefinition {
        public String id;
        public String name;
        public String type = "generic";
        public String base;
        public MaterialDefinition material = new MaterialDefinition();
        public SettingsDefinition settings = new SettingsDefinition();
        public StateDefinition state = new StateDefinition();
        public ShapeDefinition shape = new ShapeDefinition();
        public PlacementDefinition placement = new PlacementDefinition();
        public RenderDefinition render = new RenderDefinition();
        public LootDefinition loot = new LootDefinition();
        public List<String> tags = new ArrayList<>();
        public ItemDefinition item = new ItemDefinition();
        public BehaviorDefinition behavior = new BehaviorDefinition();
        /** Fixa a versao dos scripts remotos declarados em {@code behavior}. Opcional. */
        public String behaviorSha256;
    }

    public static final class MaterialDefinition {
        public String mapColor = "stone";
        public String sound = "stone";
        public String instrument = "harp";
        public String pistonBehavior = "normal";
        public boolean burnable = false;
        public int flammability = 0;
        public int burnSpread = 0;
        public boolean replaceable = false;
        public boolean liquid = false;
        public boolean air = false;
        public boolean solid = true;
        public boolean opaque = true;
    }

    public static final class SettingsDefinition {
        public float hardness = 1.0f;
        public float resistance = 1.0f;
        public boolean requiresTool = false;
        public boolean collidable = true;
        public boolean noCollision = false;
        public boolean randomTicks = false;
        public int luminance = 0;
        public float slipperiness = 0.6f;
        public float velocityMultiplier = 1.0f;
        public float jumpVelocityMultiplier = 1.0f;
        public boolean blockBreakParticles = true;
        public boolean dynamicBounds = false;
        public boolean solid = true;
        public boolean nonOpaque = false;
        public boolean breakInstantly = false;
        public String offset = "none";
        public boolean dropsNothing = false;
        public String dropsLike;
        public List<String> requiredFeatures = new ArrayList<>();
    }

    public static final class StateDefinition {
        public List<StatePropertyDefinition> properties = new ArrayList<>();
        @SerializedName("default")
        public Map<String, String> defaults = new LinkedHashMap<>();
    }

    public static final class StatePropertyDefinition {
        public String name;
        public String type = "string";
        public List<String> values = new ArrayList<>();
    }

    public static final class ShapeDefinition {
        public String collision = "full_cube";
        public String outline = "full_cube";
        public String visual = "full_cube";
        public boolean dynamic = false;
        public List<List<Float>> boxes = new ArrayList<>();
    }

    public static final class PlacementDefinition {
        public boolean canReplace = false;
        public boolean canPlaceAt = true;
        public String facing = "none";
        public boolean waterloggable = false;
        public boolean rotateWithPlayer = false;
    }

    public static final class RenderDefinition {
        public String model = "cube_all";
        public TextureDefinition texture = new TextureDefinition();
        public Map<String, TextureDefinition> variantTextures = new LinkedHashMap<>();
        public String renderLayer = "solid";
        public boolean translucent = false;
        public boolean cutout = false;
        public boolean emissive = false;
        public String tint;
    }

    public static final class TextureDefinition {
        public String source = "local";
        public String path;
        public String url;
        public String sha256;
        public long maxBytes = 1_048_576;
        public String fallback = "minecraft:block/stone";
    }

    public static final class LootDefinition {
        public String mode = "self";
        public String item;
        public int count = 1;
        public String table;
    }

    public static final class ItemDefinition {
        public boolean register = true;
        public int maxStackSize = 64;
        public int maxDamage = 0;
        public String rarity = "common";
        public boolean fireResistant = false;
    }

    /** Logica associada a um bloco. Cada campo aponta um arquivo .lua, uma URL ou uma funcao. */
    public static final class BehaviorDefinition {
        public String onUse;
        /** Jogador bateu no bloco, sem necessariamente quebra-lo. */
        public String onAttack;
        /** Nome antigo de {@link #onAttack}; mantido para nao quebrar mods ja escritos. */
        public String onBreak;
        public String onPlaced;
        public String onBroken;
        public String onRandomTick;
        public String onNeighborUpdate;
        /** Campo antigo, nunca implementado; use {@link #onPlaced}. */
        public String onPlace;
    }

    /** Logica associada a um item. */
    public static final class ItemBehaviorAdvanced {
        public String onUse;
        public String onUseOnBlock;
    }

    /** Item declarado pelo manifesto que nao pertence a um bloco. */
    public static final class ItemEntryDefinition {
        public String id;
        public String name;
        public int maxStackSize = 64;
        public int maxDamage = 0;
        public String rarity = "common";
        public boolean fireResistant = false;
        public TextureDefinition texture = new TextureDefinition();
        public String model = "item/generated";
        public ItemBehaviorDefinition behavior = new ItemBehaviorDefinition();
        /** Fixa a versao dos scripts remotos declarados em {@code behavior}. Opcional. */
        public String behaviorSha256;
    }

    /** Logica associada a um item. Cada campo aponta um arquivo .lua, uma URL ou uma funcao. */
    public static final class ItemBehaviorDefinition {
        /** Clique com o item na mao, sem alvo. */
        public String onUse;
        /** Clique com o item sobre um bloco. */
        public String onUseOnBlock;
    }

    /** Aba propria do mod no inventario criativo. */
    public static final class CreativeTabDefinition {
        public boolean register = true;
        public String id = "main";
        public String name;
        /** Item mostrado como icone da aba, no formato mod:item. Vazio usa o primeiro conteudo. */
        public String icon;
    }

    /**
     * Estrutura declarada como dados: uma paleta de simbolos e as camadas do desenho.
     *
     * <p>Cada entrada de {@code layers} e uma camada horizontal, da mais baixa para a mais alta.
     * Dentro de uma camada, cada string e uma linha no eixo Z, e cada caractere uma posicao no
     * eixo X. Um simbolo mapeado para {@code null} na paleta significa "nao tocar", preservando
     * o que ja existe no mundo.
     */
    public static final class StructureDefinition {
        public String id;
        public String name;
        /** {@code bottom_center} ancora no centro da base; {@code corner} ancora no canto minimo. */
        public String origin = "bottom_center";
        public Map<String, String> palette = new LinkedHashMap<>();
        public List<List<String>> layers = new ArrayList<>();
    }
}

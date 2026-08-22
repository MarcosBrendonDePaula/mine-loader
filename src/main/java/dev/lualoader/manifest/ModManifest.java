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

    public static final class BehaviorDefinition {
        public String onPlace;
        public String onBreak;
        public String onUse;
        public String onRandomTick;
        public String onNeighborUpdate;
    }
}

package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import dev.lualoader.minecraft.DeclarativeBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.enums.NoteBlockInstrument;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.sound.BlockSoundGroup;

import java.util.Locale;

/** Converte o formato estável do loader para a API de blocos da versão-alvo. */
public final class BlockSettingsFactory {
    private BlockSettingsFactory() {
    }

    public static AbstractBlock.Settings create(ModManifest.BlockDefinition definition) {
        ModManifest.MaterialDefinition material = definition.material == null
                ? new ModManifest.MaterialDefinition()
                : definition.material;
        ModManifest.SettingsDefinition values = definition.settings == null
                ? new ModManifest.SettingsDefinition()
                : definition.settings;

        AbstractBlock.Settings settings = AbstractBlock.Settings.create()
                .mapColor(mapColor(material.mapColor))
                .sounds(sound(material.sound))
                .instrument(instrument(material.instrument))
                .pistonBehavior(pistonBehavior(material.pistonBehavior))
                .strength(values.hardness, values.resistance)
                .slipperiness(values.slipperiness)
                .velocityMultiplier(values.velocityMultiplier)
                .jumpVelocityMultiplier(values.jumpVelocityMultiplier)
                .luminance(state -> state.contains(DeclarativeBlock.LUA_LUMINANCE)
                        ? state.get(DeclarativeBlock.LUA_LUMINANCE)
                        : clamp(values.luminance, 0, 15));

        if (values.requiresTool) settings = settings.requiresTool();
        if (values.randomTicks) settings = settings.ticksRandomly();
        if (values.noCollision || !values.collidable) settings = settings.noCollision();
        if (values.nonOpaque || !material.opaque) settings = settings.nonOpaque();
        if (values.breakInstantly) settings = settings.breakInstantly();
        if (material.burnable) settings = settings.burnable();
        if (material.replaceable) settings = settings.replaceable();
        if (material.liquid) settings = settings.liquid();
        if (material.air) settings = settings.air();
        if (values.solid && material.solid) settings = settings.solid();
        if (!values.blockBreakParticles) settings = settings.noBlockBreakParticles();
        if (values.dynamicBounds || (definition.shape != null && definition.shape.dynamic)) {
            settings = settings.dynamicBounds();
        }

        if (values.offset != null) {
            settings = settings.offset(offset(values.offset));
        }
        if (values.dropsNothing) {
            settings = settings.dropsNothing();
        }

        return settings;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static MapColor mapColor(String name) {
        String key = normalize(name, "stone");
        return switch (key) {
            case "clear", "transparent" -> MapColor.CLEAR;
            case "dirt", "brown" -> MapColor.DIRT_BROWN;
            case "wood" -> MapColor.OAK_TAN;
            case "iron", "gray", "grey" -> MapColor.IRON_GRAY;
            case "red" -> MapColor.RED;
            case "diamond", "blue" -> MapColor.DIAMOND_BLUE;
            case "gold", "yellow" -> MapColor.GOLD;
            case "grass", "green" -> MapColor.PALE_GREEN;
            case "sand" -> MapColor.PALE_YELLOW;
            case "white" -> MapColor.WHITE;
            case "black" -> MapColor.BLACK;
            default -> MapColor.STONE_GRAY;
        };
    }

    private static BlockSoundGroup sound(String name) {
        String key = normalize(name, "stone");
        return switch (key) {
            case "wood" -> BlockSoundGroup.WOOD;
            case "grass" -> BlockSoundGroup.GRASS;
            case "sand" -> BlockSoundGroup.SAND;
            case "gravel" -> BlockSoundGroup.GRAVEL;
            case "glass" -> BlockSoundGroup.GLASS;
            case "metal" -> BlockSoundGroup.METAL;
            case "wool" -> BlockSoundGroup.WOOL;
            case "snow" -> BlockSoundGroup.SNOW;
            default -> BlockSoundGroup.STONE;
        };
    }

    private static NoteBlockInstrument instrument(String name) {
        String key = normalize(name, "harp");
        return switch (key) {
            case "basedrum", "base_drum" -> NoteBlockInstrument.BASEDRUM;
            case "snare" -> NoteBlockInstrument.SNARE;
            case "hat" -> NoteBlockInstrument.HAT;
            case "bass" -> NoteBlockInstrument.BASS;
            case "flute" -> NoteBlockInstrument.FLUTE;
            case "bell" -> NoteBlockInstrument.BELL;
            case "guitar" -> NoteBlockInstrument.GUITAR;
            case "chime" -> NoteBlockInstrument.CHIME;
            case "xylophone" -> NoteBlockInstrument.XYLOPHONE;
            case "iron_xylophone" -> NoteBlockInstrument.IRON_XYLOPHONE;
            case "cow_bell" -> NoteBlockInstrument.COW_BELL;
            case "didgeridoo" -> NoteBlockInstrument.DIDGERIDOO;
            case "bit" -> NoteBlockInstrument.BIT;
            case "banjo" -> NoteBlockInstrument.BANJO;
            case "pling" -> NoteBlockInstrument.PLING;
            default -> NoteBlockInstrument.HARP;
        };
    }

    private static PistonBehavior pistonBehavior(String name) {
        String key = normalize(name, "normal");
        return switch (key) {
            case "block" -> PistonBehavior.BLOCK;
            case "destroy" -> PistonBehavior.DESTROY;
            case "push_only" -> PistonBehavior.PUSH_ONLY;
            default -> PistonBehavior.NORMAL;
        };
    }

    private static AbstractBlock.OffsetType offset(String name) {
        return switch (normalize(name, "none")) {
            case "xz" -> AbstractBlock.OffsetType.XZ;
            case "xyz" -> AbstractBlock.OffsetType.XYZ;
            default -> AbstractBlock.OffsetType.NONE;
        };
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}

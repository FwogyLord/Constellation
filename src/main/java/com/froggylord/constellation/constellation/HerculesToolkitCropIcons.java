package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.HerculesConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/ToolkitCropReplacer.kt
public final class HerculesToolkitCropIcons {
    private static HerculesConfig cfg;

    private HerculesToolkitCropIcons() {}

    public static void init(HerculesConfig config) {
        cfg = config;
    }

    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (!active(screen) || slot == null || slot.getItem().isEmpty()) return;
        if (!((slot.index >= 10 && slot.index <= 16) || (slot.index >= 20 && slot.index <= 24))) return;
        HerculesGardenTracker.Crop crop = HerculesGardenTracker.cropInHand(slot.getItem());
        if (crop == null) return;
        ItemStack icon = new ItemStack(icon(crop));
        if (cfg.toolkitCropIconBackground) graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, 0xFF8B8B8B);
        graphics.item(icon, slot.x, slot.y);
        if (cfg.toolkitCropIconDecorations)
            graphics.itemDecorations(Minecraft.getInstance().font, icon, slot.x, slot.y);
        HerculesGardenTracker.Crop held = heldCrop();
        if (cfg.toolkitCropIconHighlightHeld && held == crop) border(graphics, slot, cfg.toolkitCropIconHeldColor);
        if (cfg.toolkitCropIconLabels)
            graphics.text(Minecraft.getInstance().font, shortName(crop), slot.x + 1, slot.y + 1,
                cfg.toolkitCropIconLabelColor, true);
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toolkiticons")
            .executes(context -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
                .executes(context -> {
                    cfg.toolkitCropIcons = !cfg.toolkitCropIcons;
                    save();
                    return status();
                }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(context -> option(StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "state"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("color")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("target", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("argb", StringArgumentType.word())
                        .executes(context -> color(StringArgumentType.getString(context, "target"),
                            StringArgumentType.getString(context, "argb")))))));
    }

    private static int status() {
        local("Toolkit crop icons " + on(cfg.toolkitCropIcons) + ", background " + on(cfg.toolkitCropIconBackground)
            + ", held highlight " + on(cfg.toolkitCropIconHighlightHeld) + ", labels "
            + on(cfg.toolkitCropIconLabels) + ".");
        return 1;
    }

    private static int option(String name, String state) {
        Boolean value = parse(state);
        if (value == null) {
            local("State must be on or off.");
            return 0;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.toolkitCropIcons = value;
            case "background" -> cfg.toolkitCropIconBackground = value;
            case "decorations" -> cfg.toolkitCropIconDecorations = value;
            case "highlight", "held" -> cfg.toolkitCropIconHighlightHeld = value;
            case "labels" -> cfg.toolkitCropIconLabels = value;
            default -> {
                local("Option must be enabled, background, decorations, highlight or labels.");
                return 0;
            }
        }
        save();
        return status();
    }

    private static int color(String target, String raw) {
        Integer parsed = parseColor(raw);
        if (parsed == null) {
            local("Color must be RRGGBB or AARRGGBB.");
            return 0;
        }
        if (target.equalsIgnoreCase("held") || target.equalsIgnoreCase("highlight"))
            cfg.toolkitCropIconHeldColor = parsed;
        else if (target.equalsIgnoreCase("label")) cfg.toolkitCropIconLabelColor = parsed;
        else {
            local("Color target must be held or label.");
            return 0;
        }
        save();
        return status();
    }

    private static boolean active(AbstractContainerScreen<?> screen) {
        return cfg != null && cfg.enabled && cfg.toolkitCropIcons && ConstellationClient.loc().onHypixel()
            && clean(screen.getTitle().getString()).equals("Farming Toolkit");
    }

    private static HerculesGardenTracker.Crop heldCrop() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player == null ? null : HerculesGardenTracker.cropInHand(mc.player.getMainHandItem());
    }

    private static Item icon(HerculesGardenTracker.Crop crop) {
        return switch (crop) {
            case WHEAT -> Items.WHEAT;
            case CARROT -> Items.CARROT;
            case POTATO -> Items.POTATO;
            case NETHER_WART -> Items.NETHER_WART;
            case PUMPKIN -> Items.PUMPKIN;
            case MELON -> Items.MELON_SLICE;
            case COCOA -> Items.COCOA_BEANS;
            case SUGAR_CANE -> Items.SUGAR_CANE;
            case CACTUS -> Items.CACTUS;
            case MUSHROOM -> Items.RED_MUSHROOM;
            case SUNFLOWER -> Items.SUNFLOWER;
            case MOONFLOWER -> Items.BLUE_ORCHID;
            case WILD_ROSE -> Items.ROSE_BUSH;
        };
    }

    private static String shortName(HerculesGardenTracker.Crop crop) {
        return switch (crop) {
            case NETHER_WART -> "NW";
            case SUGAR_CANE -> "SC";
            case SUNFLOWER -> "SF";
            case MOONFLOWER -> "MF";
            case WILD_ROSE -> "WR";
            default -> crop.display().substring(0, 1);
        };
    }

    private static void border(GuiGraphicsExtractor graphics, Slot slot, int color) {
        graphics.fill(slot.x, slot.y, slot.x + 16, slot.y + 1, color);
        graphics.fill(slot.x, slot.y + 15, slot.x + 16, slot.y + 16, color);
        graphics.fill(slot.x, slot.y, slot.x + 1, slot.y + 16, color);
        graphics.fill(slot.x + 15, slot.y, slot.x + 16, slot.y + 16, color);
    }

    private static Integer parseColor(String raw) {
        try {
            String value = raw.startsWith("#") ? raw.substring(1) : raw.startsWith("0x") ? raw.substring(2) : raw;
            if (value.length() != 6 && value.length() != 8) return null;
            long parsed = Long.parseUnsignedLong(value, 16);
            return value.length() == 6 ? (int) (0xFF000000L | parsed) : (int) parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Boolean parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> null;
        };
    }

    private static String clean(String value) {
        return net.minecraft.ChatFormatting.stripFormatting(value).trim();
    }

    private static String on(boolean value) {
        return value ? "on" : "off";
    }

    private static void local(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.sendSystemMessage(Component.literal("\u00a72[Farming Toolkit] \u00a7f" + text));
    }

    private static void save() {
        ConstellationClient.saveConfig();
    }
}

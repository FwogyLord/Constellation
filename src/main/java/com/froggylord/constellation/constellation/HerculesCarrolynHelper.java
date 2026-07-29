package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.HerculesConfig;
import com.froggylord.constellation.core.LocationManager;
import com.froggylord.constellation.render.WorldRenderer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/inventory/CarrolynHelper.kt
// destination ported from SkyHanni (LGPL-3.0-or-later): repo/constants/island_graphs/CRIMSON_ISLE.json
public final class HerculesCarrolynHelper {
    private static final Vec3 DESTINATION = new Vec3(0, 104, -804);
    private static final int REQUIRED = 3000;
    private static HerculesConfig cfg;
    private static boolean navigating;
    private static long lastClick;

    private HerculesCarrolynHelper() {}

    public static void init(HerculesConfig config) {
        cfg = config;
        UseItemCallback.EVENT.register((player, level, hand) -> observe(player.getItemInHand(hand), hand));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> observe(player.getItemInHand(hand), hand));
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> observe(player.getItemInHand(hand), hand));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
            observe(player.getItemInHand(hand), hand));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
            observe(player.getItemInHand(hand), hand));
        ConstellationClient.tick().every(5, "hercules-carrolyn", HerculesCarrolynHelper::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
    }

    private static InteractionResult observe(ItemStack stack, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !active() || !isItem(stack) || !cfg.carrolynClickNavigation)
            return InteractionResult.PASS;
        long now = System.currentTimeMillis();
        if (now - lastClick < 250) return InteractionResult.PASS;
        lastClick = now;
        start(true);
        return InteractionResult.PASS;
    }

    private static void tick() {
        if (!navigating || !active() || !cfg.carrolynAutoStop || !inCrimson()) return;
        Minecraft mc = Minecraft.getInstance();
        double distance = mc.player == null ? Double.MAX_VALUE : mc.player.position().distanceTo(DESTINATION);
        if (distance <= Math.clamp(cfg.carrolynStopDistance, 2, 16)) {
            navigating = false;
            if (cfg.carrolynArrivalChat) local("You reached Carrolyn.");
        }
    }

    public static void draw(WorldRenderer.Ctx ctx) {
        if (!navigating || !active() || !inCrimson()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        double distance = mc.player.position().distanceTo(DESTINATION);
        if (distance > Math.clamp(cfg.carrolynRenderRange, 32, 4096)) return;
        int color = cfg.carrolynColor;
        boolean through = cfg.carrolynThroughWalls;
        if (cfg.carrolynBox)
            ctx.highlight(new AABB(DESTINATION.x - .5, DESTINATION.y - 1, DESTINATION.z - .5,
                DESTINATION.x + .5, DESTINATION.y + 1, DESTINATION.z + .5), color, through);
        if (cfg.carrolynBeam)
            ctx.beam(DESTINATION.x, DESTINATION.y, DESTINATION.z, color,
                Math.clamp(cfg.carrolynBeamHeight, 2, 64), through);
        if (cfg.carrolynLine)
            ctx.line(mc.player.getEyePosition(), DESTINATION.add(0, .5, 0), color, through);
        if (cfg.carrolynLabel) {
            String label = "Carrolyn";
            if (cfg.carrolynDistance) label += " " + Math.round(distance) + "m";
            ctx.label(DESTINATION.add(0, 1.5, 0), label, color, through);
        }
    }

    public static List<Component> appendTooltip(AbstractContainerScreen<?> screen, ItemStack stack,
                                                 List<Component> original) {
        if (!active() || !cfg.carrolynTooltip || !isItem(stack) || original == null) return original;
        List<Component> out = new ArrayList<>(original);
        out.add(Component.empty());
        out.add(Component.literal("\u00a7eClick while holding to navigate to Carrolyn."));
        if (cfg.carrolynTooltipOwned) {
            int owned = owned(stack);
            out.add(Component.literal("\u00a77Inventory: " + (owned >= REQUIRED ? "\u00a7a" : "\u00a7e")
                + format(owned) + "\u00a77/" + format(REQUIRED)));
        }
        if (!inCrimson() && cfg.carrolynTooltipWarp)
            out.add(Component.literal("\u00a77A clickable Crimson Isle warp will appear in chat."));
        return out;
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("carrolyn")
            .executes(context -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("start")
                .executes(context -> start(false)))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stop")
                .executes(context -> stop()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
                .executes(context -> {
                    cfg.carrolynHelper = !cfg.carrolynHelper;
                    if (!cfg.carrolynHelper) navigating = false;
                    save();
                    return status();
                }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("range")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("blocks",
                        IntegerArgumentType.integer(32, 4096))
                    .executes(context -> {
                        cfg.carrolynRenderRange = IntegerArgumentType.getInteger(context, "blocks");
                        save();
                        return status();
                    })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("beamheight")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("blocks",
                        IntegerArgumentType.integer(2, 64))
                    .executes(context -> {
                        cfg.carrolynBeamHeight = IntegerArgumentType.getInteger(context, "blocks");
                        save();
                        return status();
                    })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("stopdistance")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("blocks",
                        IntegerArgumentType.integer(2, 16))
                    .executes(context -> {
                        cfg.carrolynStopDistance = IntegerArgumentType.getInteger(context, "blocks");
                        save();
                        return status();
                    })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("color")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("argb", StringArgumentType.word())
                    .executes(context -> color(StringArgumentType.getString(context, "argb")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(context -> option(StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "state")))))));
    }

    private static int start(boolean fromItem) {
        if (!active()) return 0;
        navigating = true;
        if (inCrimson()) {
            if (cfg.carrolynStartChat) local("Navigation started.");
        } else if (cfg.carrolynClickableWarp) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                Component line = Component.literal("\u00a75[Carrolyn] \u00a7fCarrolyn is on the Crimson Isle. ")
                    .append(Component.literal("\u00a7a[Warp there]").withStyle(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/warp crimson"))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal("Run /warp crimson")))));
                mc.player.sendSystemMessage(line);
            }
        } else if (!fromItem || cfg.carrolynStartChat) {
            local("Go to the Crimson Isle to begin navigation.");
        }
        return 1;
    }

    private static int stop() {
        navigating = false;
        local("Navigation stopped.");
        return 1;
    }

    private static int status() {
        local("Helper " + on(cfg.carrolynHelper) + ", navigation " + on(navigating)
            + ", click-to-start " + on(cfg.carrolynClickNavigation) + ".");
        return 1;
    }

    private static int option(String name, String state) {
        Boolean value = parse(state);
        if (value == null) {
            local("State must be on or off.");
            return 0;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.carrolynHelper = value;
            case "tooltip" -> cfg.carrolynTooltip = value;
            case "owned" -> cfg.carrolynTooltipOwned = value;
            case "tooltipwarp" -> cfg.carrolynTooltipWarp = value;
            case "click" -> cfg.carrolynClickNavigation = value;
            case "warp" -> cfg.carrolynClickableWarp = value;
            case "box" -> cfg.carrolynBox = value;
            case "beam" -> cfg.carrolynBeam = value;
            case "line" -> cfg.carrolynLine = value;
            case "label" -> cfg.carrolynLabel = value;
            case "distance" -> cfg.carrolynDistance = value;
            case "throughwalls" -> cfg.carrolynThroughWalls = value;
            case "autostop" -> cfg.carrolynAutoStop = value;
            case "startchat" -> cfg.carrolynStartChat = value;
            case "arrivalchat" -> cfg.carrolynArrivalChat = value;
            default -> {
                local("Unknown Carrolyn option.");
                return 0;
            }
        }
        if (!cfg.carrolynHelper) navigating = false;
        save();
        return status();
    }

    private static int color(String raw) {
        Integer parsed = parseColor(raw);
        if (parsed == null) {
            local("Color must be RRGGBB or AARRGGBB.");
            return 0;
        }
        cfg.carrolynColor = parsed;
        save();
        return status();
    }

    private static boolean isItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (String line : lore(stack))
            if (line.matches("(?i)^Bring\\s+3,000\\s+of these to\\s+Carrolyn\\s+in$")) return true;
        return false;
    }

    private static int owned(ItemStack target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        String id = LyraTooltips.marketId(target);
        String name = clean(target.getHoverName().getString());
        int count = 0;
        for (ItemStack stack : mc.player.getInventory()) {
            if (!id.isEmpty() ? id.equals(LyraTooltips.marketId(stack))
                : name.equalsIgnoreCase(clean(stack.getHoverName().getString()))) count += stack.getCount();
        }
        return count;
    }

    private static List<String> lore(ItemStack stack) {
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore == null) return List.of();
        return lore.lines().stream().map(line -> clean(line.getString())).toList();
    }

    private static boolean active() {
        return cfg != null && cfg.enabled && cfg.carrolynHelper && ConstellationClient.loc().onHypixel();
    }

    private static boolean inCrimson() {
        return ConstellationClient.loc().area() == LocationManager.SkyblockArea.CRIMSON_ISLE;
    }

    private static void reset() {
        navigating = false;
        lastClick = 0;
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
        String clean = ChatFormatting.stripFormatting(value);
        return clean == null ? "" : clean.trim().replaceAll("\\s+", " ");
    }

    private static String format(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String on(boolean value) {
        return value ? "on" : "off";
    }

    private static void local(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.sendSystemMessage(Component.literal("\u00a75[Carrolyn] \u00a7f" + text));
    }

    private static void save() {
        ConstellationClient.saveConfig();
    }
}

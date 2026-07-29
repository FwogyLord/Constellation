package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.PhoenixConfig;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.regex.Pattern;

// ported from Devonian (GPL-3.0-only): features/misc/WardrobeKeybinds.kt
// advanced controls ported from Athen (BSD-3-Clause): modules/impl/general/WardrobeKeybinds.kt
// slot validation and sound ported from NoFrills (GPL-3.0-only): features/general/WardrobeKeybinds.java
// menu and slot-label handling ported from Skyblocker (LGPL-3.0-or-later): skyblock/WardrobeKeybinds.java
public final class PhoenixWardrobeKeybinds {
    private static final Pattern MENU = Pattern.compile(
        "^(?:(?:Wardrobe|Armor Sets|Equipment Sets) \\(\\d+/\\d+\\)|\\(\\d+/\\d+\\) (?:Armor Sets|Equipment Sets)|Armor Sets|Equipment Sets)$");
    private static final KeyMapping[] CUSTOM = new KeyMapping[9];
    private static PhoenixConfig cfg;
    private static KeyMapping previousPage;
    private static KeyMapping nextPage;
    private static KeyMapping unequip;
    private static KeyMapping swap;
    private static KeyMapping open;
    private static long lastClick;
    private static boolean initialized;

    private PhoenixWardrobeKeybinds() {}

    public static void init(PhoenixConfig config) {
        cfg = config;
        if (initialized) return;
        initialized = true;
        for (int i = 0; i < CUSTOM.length; i++)
            CUSTOM[i] = ConstellationClient.instance().keys().register("wardrobe_slot_" + (i + 1),
                InputConstants.UNKNOWN.getValue());
        previousPage = ConstellationClient.instance().keys().register("wardrobe_previous_page", GLFW.GLFW_KEY_A);
        nextPage = ConstellationClient.instance().keys().register("wardrobe_next_page", GLFW.GLFW_KEY_D);
        unequip = ConstellationClient.instance().keys().register("wardrobe_unequip", InputConstants.UNKNOWN.getValue());
        swap = ConstellationClient.instance().keys().register("wardrobe_swap", InputConstants.UNKNOWN.getValue());
        open = ConstellationClient.instance().keys().register("wardrobe_open", InputConstants.UNKNOWN.getValue());
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container) || !isMenu(container)) return;
            ScreenKeyboardEvents.allowKeyPress(container).register((ignored, event) -> key(container, event));
            ScreenMouseEvents.allowMouseClick(container).register((ignored, event) -> mouse(container, event));
            ScreenEvents.remove(container).register(ignored -> lastClick = 0);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (open == null) return;
            while (open.consumeClick()) {
                if (!active() || !cfg.wardrobeOpenKeybind || client.player == null || client.player.connection == null) continue;
                client.player.connection.sendCommand("wardrobe");
            }
        });
    }

    private static boolean key(AbstractContainerScreen<?> screen, KeyEvent event) {
        if (!active() || !isMenu(screen)) return true;
        int action = action(screen, mapping -> mapping.matches(event), event.key(), false);
        return action < 0 || perform(screen, action);
    }

    private static boolean mouse(AbstractContainerScreen<?> screen, MouseButtonEvent event) {
        if (!active() || !isMenu(screen)) return true;
        int action = action(screen, mapping -> mapping.matchesMouse(event), event.button(), true);
        return action < 0 || perform(screen, action);
    }

    private interface Match {
        boolean test(KeyMapping mapping);
    }

    private static int action(AbstractContainerScreen<?> screen, Match match, int raw, boolean mouse) {
        if (match.test(previousPage)) return 45;
        if (match.test(nextPage)) return 53;
        if (match.test(unequip)) return -2;
        if (cfg.wardrobeSwapEnabled && match.test(swap)) return -3;
        String style = normalizeStyle();
        for (int i = 0; i < 9; i++) {
            boolean matched = switch (style) {
                case "NUMBER" -> !mouse && raw == GLFW.GLFW_KEY_1 + i;
                case "CUSTOM" -> match.test(CUSTOM[i]);
                default -> match.test(Minecraft.getInstance().options.keyHotbarSlots[i]);
            };
            if (matched) return 36 + i;
        }
        return -1;
    }

    private static boolean perform(AbstractContainerScreen<?> screen, int action) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return true;
        long now = System.currentTimeMillis();
        if (now - lastClick < Math.clamp(cfg.wardrobeClickCooldownMillis, 0, 2000)) return false;
        if (action == -2) action = equipped(screen);
        else if (action == -3) action = swapTarget(screen);
        if (action < 0 || action >= screen.getMenu().slots.size()) return !cfg.wardrobeConsumeInvalidKeys;
        Slot slot = screen.getMenu().getSlot(action);
        if (action >= 36 && action <= 44) {
            if (!validButton(slot.getItem())) return !cfg.wardrobeConsumeInvalidKeys;
            if (cfg.wardrobePreventUnequip && equipped(slot.getItem())) {
                if (cfg.wardrobeFeedback) local("That set is already equipped.");
                return false;
            }
        } else if (slot.getItem().isEmpty()) {
            return !cfg.wardrobeConsumeInvalidKeys;
        }
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, action, GLFW.GLFW_MOUSE_BUTTON_LEFT,
            ContainerInput.PICKUP, mc.player);
        lastClick = now;
        if (cfg.wardrobeSound) mc.player.playSound(SoundEvents.HORSE_ARMOR.value(), .69f, 1f);
        return false;
    }

    private static int swapTarget(AbstractContainerScreen<?> screen) {
        int first = 36 + Math.clamp(cfg.wardrobeSwapSlotOne, 1, 9) - 1;
        int second = 36 + Math.clamp(cfg.wardrobeSwapSlotTwo, 1, 9) - 1;
        if (first == second || second >= screen.getMenu().slots.size()) return -1;
        ItemStack one = screen.getMenu().getSlot(first).getItem();
        ItemStack two = screen.getMenu().getSlot(second).getItem();
        if (!validButton(one) || !validButton(two)) return -1;
        return equipped(one) ? second : first;
    }

    private static int equipped(AbstractContainerScreen<?> screen) {
        for (int i = 36; i <= 44 && i < screen.getMenu().slots.size(); i++)
            if (equipped(screen.getMenu().getSlot(i).getItem())) return i;
        return -1;
    }

    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot) {
        if (!active() || !cfg.wardrobeSlotLabels || !isMenu(screen) || slot == null
            || slot.index < 36 || slot.index > 44 || !validButton(slot.getItem())) return;
        String label = label(slot.index - 36);
        if (label.isBlank()) return;
        int x = slot.x + (cfg.wardrobeLabelTopRight ? Math.max(0, 16 - Minecraft.getInstance().font.width(label)) : 1);
        int y = slot.y + (cfg.wardrobeLabelTopRight ? 1 : 8);
        graphics.text(Minecraft.getInstance().font, label, x, y, cfg.wardrobeLabelColor, true);
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("wardrobekeys")
            .executes(context -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
                .executes(context -> {
                    cfg.wardrobeKeybinds = !cfg.wardrobeKeybinds;
                    save();
                    return status();
                }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("style")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("style", StringArgumentType.word())
                    .executes(context -> style(StringArgumentType.getString(context, "style")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cooldown")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("milliseconds",
                        IntegerArgumentType.integer(0, 2000))
                    .executes(context -> {
                        cfg.wardrobeClickCooldownMillis = IntegerArgumentType.getInteger(context, "milliseconds");
                        save();
                        return status();
                    })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("swap")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("first",
                        IntegerArgumentType.integer(1, 9))
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("second",
                            IntegerArgumentType.integer(1, 9))
                        .executes(context -> {
                            cfg.wardrobeSwapSlotOne = IntegerArgumentType.getInteger(context, "first");
                            cfg.wardrobeSwapSlotTwo = IntegerArgumentType.getInteger(context, "second");
                            save();
                            return status();
                        }))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("color")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("argb", StringArgumentType.word())
                    .executes(context -> color(StringArgumentType.getString(context, "argb")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(context -> option(StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "state")))))));
    }

    private static int status() {
        local("Wardrobe keys " + on(cfg.wardrobeKeybinds) + ", style "
            + normalizeStyle().toLowerCase(Locale.ROOT) + ", cooldown " + cfg.wardrobeClickCooldownMillis + "ms.");
        local("Page, slot, unequip, swap and open keys are configurable in Minecraft Controls.");
        return 1;
    }

    private static int style(String raw) {
        String value = raw.toUpperCase(Locale.ROOT);
        if (!value.equals("HOTBAR") && !value.equals("NUMBER") && !value.equals("CUSTOM")) {
            local("Style must be hotbar, number or custom.");
            return 0;
        }
        cfg.wardrobeKeyStyle = value;
        save();
        return status();
    }

    private static int option(String name, String state) {
        Boolean value = parse(state);
        if (value == null) {
            local("State must be on or off.");
            return 0;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.wardrobeKeybinds = value;
            case "armor" -> cfg.wardrobeArmorSets = value;
            case "equipment" -> cfg.wardrobeEquipmentSets = value;
            case "preventunequip", "no_unequip", "nounequip" -> cfg.wardrobePreventUnequip = value;
            case "sound" -> cfg.wardrobeSound = value;
            case "feedback" -> cfg.wardrobeFeedback = value;
            case "consumeinvalid" -> cfg.wardrobeConsumeInvalidKeys = value;
            case "labels" -> cfg.wardrobeSlotLabels = value;
            case "labeltop" -> cfg.wardrobeLabelTopRight = value;
            case "swap" -> cfg.wardrobeSwapEnabled = value;
            case "open" -> cfg.wardrobeOpenKeybind = value;
            default -> {
                local("Unknown wardrobe-key option.");
                return 0;
            }
        }
        save();
        return status();
    }

    private static int color(String raw) {
        Integer value = parseColor(raw);
        if (value == null) {
            local("Color must be RRGGBB or AARRGGBB.");
            return 0;
        }
        cfg.wardrobeLabelColor = value;
        save();
        return status();
    }

    private static boolean isMenu(AbstractContainerScreen<?> screen) {
        String title = clean(screen.getTitle().getString());
        if (!MENU.matcher(title).matches()) return false;
        if (title.contains("Equipment")) return cfg != null && cfg.wardrobeEquipmentSets;
        return cfg != null && cfg.wardrobeArmorSets;
    }

    private static boolean validButton(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.is(Items.DYE.gray()) || stack.is(Items.DYE.lime()) || stack.is(Items.DYE.pink());
    }

    private static boolean equipped(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.is(Items.DYE.lime())
            || clean(stack.getHoverName().getString()).matches("(?i)^Slot \\d+: Equipped$"));
    }

    private static String label(int index) {
        KeyMapping mapping = switch (normalizeStyle()) {
            case "CUSTOM" -> CUSTOM[index];
            case "NUMBER" -> null;
            default -> Minecraft.getInstance().options.keyHotbarSlots[index];
        };
        if (mapping == null) return Integer.toString(index + 1);
        String label = clean(mapping.getTranslatedKeyMessage().getString());
        return label.equalsIgnoreCase("unknown") ? "" : label;
    }

    private static String normalizeStyle() {
        if (cfg.wardrobeKeyStyle == null) return "HOTBAR";
        String style = cfg.wardrobeKeyStyle.toUpperCase(Locale.ROOT);
        return style.equals("NUMBER") || style.equals("CUSTOM") ? style : "HOTBAR";
    }

    private static boolean active() {
        return cfg != null && cfg.enabled && cfg.wardrobeKeybinds && ConstellationClient.loc().onHypixel();
    }

    private static Boolean parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> null;
        };
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

    private static String clean(String value) {
        String clean = ChatFormatting.stripFormatting(value);
        return clean == null ? "" : clean.trim();
    }

    private static String on(boolean value) {
        return value ? "on" : "off";
    }

    private static void local(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.sendSystemMessage(Component.literal("\u00a75[Wardrobe Keys] \u00a7f" + text));
    }

    private static void save() {
        ConstellationClient.saveConfig();
    }
}

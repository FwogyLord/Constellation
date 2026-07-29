package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.PhoenixConfig;
import com.froggylord.constellation.mixin.ContainerScreenAccessor;
import com.froggylord.constellation.ui.SlotBindingEditorScreen;
import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// ported from Devonian (GPL-3.0-only): features/misc/inventory/SlotBinding.kt
// profile editor and one-to-one validation ported from Athen (BSD-3-Clause): modules/impl/general/slotbinds/SlotBinds.kt
// hover rendering and remembered targets ported from NoFrills (GPL-3.0-only): features/general/SlotBinding.java
// click-to-bind interaction cross-checked with NoammAddons (CC0-1.0): features/impl/general/SlotBinding.kt
public final class PhoenixSlotBinding {
    private static final int[] PALETTE = {
        0xFFE41A1C, 0xFF377EB8, 0xFF4DAF4A, 0xFF984EA3, 0xFFFF7F00,
        0xFFFFFF33, 0xFFA65628, 0xFFF781BF, 0xFF999999
    };
    private static final Map<Integer, int[]> LOCATIONS = new LinkedHashMap<>();
    private static final Gson GSON = new Gson();
    private static PhoenixConfig cfg;
    private static KeyMapping bindKey;
    private static Integer bindingSlot;
    private static AbstractContainerScreen<?> locationScreen;
    private static String lastArea = "";
    private static long lastFeedback;
    private static boolean initialized;

    private PhoenixSlotBinding() {}

    public static void init(PhoenixConfig config) {
        cfg = config;
        ensure();
        if (initialized) return;
        initialized = true;
        bindKey = ConstellationClient.instance().keys().register("slot_binding", GLFW.GLFW_KEY_B);
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            ScreenKeyboardEvents.allowKeyPress(screen).register((ignored, event) -> key(container, event, true));
            ScreenKeyboardEvents.allowKeyRelease(screen).register((ignored, event) -> key(container, event, false));
            ScreenMouseEvents.allowMouseClick(screen).register((ignored, event) -> mouse(container, event, true));
            ScreenMouseEvents.allowMouseRelease(screen).register((ignored, event) -> mouse(container, event, false));
            ScreenEvents.remove(screen).register(ignored -> resetTransient());
            locationScreen = container;
            LOCATIONS.clear();
        });
        ConstellationClient.tick().every(20, "phoenix-slot-binding-profile", PhoenixSlotBinding::selectAreaProfile);
    }

    private static boolean key(AbstractContainerScreen<?> screen, KeyEvent event, boolean pressed) {
        if (!active() || bindKey == null || !bindKey.matches(event)) return true;
        bindInput(screen, pressed);
        return false;
    }

    private static boolean mouse(AbstractContainerScreen<?> screen, MouseButtonEvent event, boolean pressed) {
        if (!active() || bindKey == null || !bindKey.matchesMouse(event)) return true;
        bindInput(screen, pressed);
        return false;
    }

    private static void bindInput(AbstractContainerScreen<?> screen, boolean pressed) {
        Integer hovered = canonical(hovered(screen));
        if (pressed) {
            if (bindingSlot != null || hovered == null) return;
            bindingSlot = hovered;
            sound(1.25f);
            return;
        }
        Integer first = bindingSlot;
        bindingSlot = null;
        if (first == null || hovered == null) return;
        if (first.equals(hovered)) {
            int removed = unbind(hovered);
            if (removed > 0) feedback("Cleared " + removed + " " + plural(removed, "binding", "bindings") + ".");
            else feedback("That slot is not bound.");
            return;
        }
        if (isHotbar(first) == isHotbar(hovered) || !valid(first) || !valid(hovered)) {
            feedback("Choose one hotbar slot and one inventory slot.");
            sound(.55f);
            return;
        }
        int hotbar = isHotbar(first) ? first : hovered;
        int inventory = isHotbar(first) ? hovered : first;
        bind(hotbar, inventory);
    }

    public static boolean shouldHandleClick(AbstractContainerScreen<?> screen, Slot slot, int button, ContainerInput input) {
        if (!active()) return false;
        if (cfg.slotBindingProtect && input == ContainerInput.PICKUP_ALL && !profile().binds.isEmpty()) {
            feedback("Collect-all blocked while bound slots are protected.");
            return true;
        }
        Integer canonical = canonical(slot);
        if (cfg.slotBindingProtect && input == ContainerInput.SWAP && button >= 0 && button <= 8 && isBound(button)) {
            if (cfg.slotBindingAllowHotbarKeys && canonical != null && partners(canonical).contains(button)) return false;
            feedback("Bound hotbar slot protected. Shift-left-click its partner to swap.");
            return true;
        }
        if (canonical == null) return false;
        if (!isBound(canonical)) return false;
        if (input == ContainerInput.QUICK_MOVE && button == 0) {
            swap(screen, canonical);
            return true;
        }
        if (!cfg.slotBindingProtect) return false;
        feedback("Bound slot protected. Shift-left-click it to swap.");
        return true;
    }

    private static void swap(AbstractContainerScreen<?> screen, int canonical) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        PhoenixConfig.SlotBindingProfile profile = profile();
        int hotbar;
        int inventory;
        if (isHotbar(canonical)) {
            hotbar = canonical;
            List<Integer> choices = partners(hotbar);
            if (choices.isEmpty()) return;
            inventory = profile.last.getOrDefault(hotbar, choices.getFirst());
            if (!choices.contains(inventory)) inventory = choices.getFirst();
        } else {
            inventory = canonical;
            hotbar = hotbarFor(inventory);
            if (hotbar < 0) return;
        }
        Slot inventorySlot = find(screen, inventory);
        Slot hotbarSlot = find(screen, hotbar);
        if (inventorySlot == null || hotbarSlot == null) {
            feedback("Both bound slots must be visible in this inventory.");
            return;
        }
        if (inventorySlot.getItem().isEmpty() && hotbarSlot.getItem().isEmpty()) return;
        profile.last.put(hotbar, inventory);
        mc.gameMode.handleContainerInput(screen.getMenu().containerId, inventorySlot.index, hotbar,
            ContainerInput.SWAP, mc.player);
        save();
        sound(1f);
    }

    public static boolean shouldBlockDrop(int selectedHotbar) {
        if (!active() || !cfg.slotBindingProtect || selectedHotbar < 0 || selectedHotbar > 8) return false;
        if (!isBound(selectedHotbar)) return false;
        feedback("Bound hotbar slot protected. Unbind it before dropping.");
        return true;
    }

    public static void drawSlot(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, Slot slot,
                                int mouseX, int mouseY) {
        if (!active() || slot == null) return;
        Integer canonical = canonical(slot);
        if (canonical == null) return;
        if (locationScreen != screen) {
            locationScreen = screen;
            LOCATIONS.clear();
        }
        LOCATIONS.put(canonical, new int[]{slot.x, slot.y});
        Integer hover = canonical(hovered(screen));
        if (cfg.slotBindingShowWhileBinding && bindingSlot != null) {
            boolean selected = bindingSlot.equals(canonical);
            boolean candidate = selected || isHotbar(bindingSlot) != isHotbar(canonical);
            if (selected || canonical.equals(hover)) border(graphics, slot.x, slot.y,
                candidate ? 0xFF55FF55 : 0xFFFF5555, Math.clamp(cfg.slotBindingLineWidth, 1, 4));
        }
        if (!isBound(canonical)) return;
        boolean visible = !cfg.slotBindingHoverOnly || canonical.equals(hover)
            || (hover != null && partners(canonical).contains(hover)) || bindingSlot != null;
        if (!visible) return;
        int color = color(canonical);
        if (cfg.slotBindingBorders) border(graphics, slot.x, slot.y, color, Math.clamp(cfg.slotBindingLineWidth, 1, 4));
        if (!isHotbar(canonical) || !cfg.slotBindingLines || !showLines()) return;
        for (int partner : partners(canonical)) {
            int[] other = LOCATIONS.get(partner);
            if (other != null) line(graphics, slot.x + 8, slot.y + 8, other[0] + 8, other[1] + 8, color,
                Math.clamp(cfg.slotBindingLineWidth, 1, 4));
        }
    }

    private static boolean showLines() {
        return cfg.slotBindingLineMode == 1 || cfg.slotBindingLineMode == 2
            && InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), GLFW.GLFW_KEY_LEFT_SHIFT);
    }

    private static void border(GuiGraphicsExtractor graphics, int x, int y, int color, int width) {
        for (int i = 0; i < width; i++) graphics.outline(x + i, y + i, 16 - i * 2, 16 - i * 2, color);
    }

    private static void line(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int color, int width) {
        int mid = (y1 + y2) / 2;
        graphics.fill(Math.min(x1, x2), mid - width / 2, Math.max(x1, x2) + 1, mid + (width + 1) / 2, color);
        graphics.fill(x1 - width / 2, Math.min(y1, mid), x1 + (width + 1) / 2, Math.max(y1, mid) + 1, color);
        graphics.fill(x2 - width / 2, Math.min(y2, mid), x2 + (width + 1) / 2, Math.max(y2, mid) + 1, color);
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("slotbind")
            .executes(context -> openEditor())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("gui").executes(context -> openEditor()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(context -> toggle()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(context -> status()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("list").executes(context -> listProfiles()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(context -> clear()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("export").executes(context -> exportProfile()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("import").executes(context -> importProfile()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("select")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .executes(context -> select(StringArgumentType.getString(context, "name")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("create")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("area", StringArgumentType.word())
                        .executes(context -> create(StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "area"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("delete")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .executes(context -> delete(StringArgumentType.getString(context, "name")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(context -> option(StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "state"))))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("linemode")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("mode", IntegerArgumentType.integer(0, 2))
                    .executes(context -> lineMode(IntegerArgumentType.getInteger(context, "mode")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("linewidth")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("width", IntegerArgumentType.integer(1, 4))
                    .executes(context -> lineWidth(IntegerArgumentType.getInteger(context, "width")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("color")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("argb", StringArgumentType.word())
                    .executes(context -> fixedColor(StringArgumentType.getString(context, "argb"))))));
    }

    private static int openEditor() {
        Minecraft.getInstance().schedule(() ->
            Minecraft.getInstance().setScreenAndShow(new SlotBindingEditorScreen(Minecraft.getInstance().gui.screen())));
        return 1;
    }

    private static int toggle() {
        cfg.slotBinding = !cfg.slotBinding;
        save();
        return status();
    }

    private static int status() {
        PhoenixConfig.SlotBindingProfile profile = profile();
        int count = profile.binds.values().stream().mapToInt(List::size).sum();
        feedback("Slot binding " + on(cfg.slotBinding) + ", profile " + cfg.slotBindingSelectedProfile
            + ", " + count + " " + plural(count, "binding", "bindings") + ".");
        return 1;
    }

    private static int listProfiles() {
        feedback("Profiles: " + String.join(", ", cfg.slotBindingProfiles.keySet()) + ".");
        return 1;
    }

    private static int clear() {
        int count = profile().binds.values().stream().mapToInt(List::size).sum();
        profile().binds.clear();
        profile().last.clear();
        profile().colors.clear();
        save();
        feedback("Cleared " + count + " " + plural(count, "binding", "bindings") + " from this profile.");
        return 1;
    }

    private static int select(String requested) {
        String name = profileName(requested);
        if (name == null) {
            feedback("No profile named " + requested + ".");
            return 0;
        }
        cfg.slotBindingSelectedProfile = name;
        save();
        return status();
    }

    private static int create(String requested, String areaMode) {
        String clean = cleanName(requested);
        if (clean.isEmpty() || profileName(clean) != null) {
            feedback("Choose a new profile name using letters, numbers, dash or underscore.");
            return 0;
        }
        PhoenixConfig.SlotBindingProfile profile = new PhoenixConfig.SlotBindingProfile();
        if (areaMode.equalsIgnoreCase("current")) {
            profile.area = ConstellationClient.loc().area().name();
            if (profile.area.equals("UNKNOWN")) {
                feedback("Current SkyBlock area is not known yet.");
                return 0;
            }
        }
        else if (!areaMode.equalsIgnoreCase("global")) {
            feedback("Area must be current or global.");
            return 0;
        }
        cfg.slotBindingProfiles.put(clean, profile);
        cfg.slotBindingSelectedProfile = clean;
        save();
        feedback("Created profile " + clean + " for " + (profile.area.isEmpty() ? "all areas" : profile.area) + ".");
        return 1;
    }

    private static int delete(String requested) {
        String name = profileName(requested);
        if (name == null || cfg.slotBindingProfiles.size() <= 1) {
            feedback("That profile cannot be deleted.");
            return 0;
        }
        cfg.slotBindingProfiles.remove(name);
        if (cfg.slotBindingSelectedProfile.equals(name)) cfg.slotBindingSelectedProfile = cfg.slotBindingProfiles.keySet().iterator().next();
        save();
        return status();
    }

    private static int option(String name, String raw) {
        Boolean value = parse(raw);
        if (value == null) {
            feedback("State must be on or off.");
            return 0;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.slotBinding = value;
            case "protect" -> cfg.slotBindingProtect = value;
            case "dynamic" -> cfg.slotBindingDynamicProfiles = value;
            case "borders" -> cfg.slotBindingBorders = value;
            case "lines" -> cfg.slotBindingLines = value;
            case "hover" -> cfg.slotBindingHoverOnly = value;
            case "preview" -> cfg.slotBindingShowWhileBinding = value;
            case "sound" -> cfg.slotBindingSound = value;
            case "feedback" -> cfg.slotBindingFeedback = value;
            case "hotbarkeys" -> cfg.slotBindingAllowHotbarKeys = value;
            case "fixedcolor" -> cfg.slotBindingUseFixedColor = value;
            default -> {
                feedback("Unknown slot-binding option.");
                return 0;
            }
        }
        save();
        return status();
    }

    private static int lineMode(int value) {
        cfg.slotBindingLineMode = value;
        save();
        return status();
    }

    private static int lineWidth(int value) {
        cfg.slotBindingLineWidth = value;
        save();
        return status();
    }

    private static int fixedColor(String raw) {
        Integer color = parseColor(raw);
        if (color == null) {
            feedback("Color must be RRGGBB or AARRGGBB.");
            return 0;
        }
        cfg.slotBindingFixedColor = color;
        save();
        return status();
    }

    private static int exportProfile() {
        String json = GSON.toJson(profile());
        String encoded = "CSB1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Minecraft.getInstance().keyboardHandler.setClipboard(encoded);
        feedback("Copied profile " + cfg.slotBindingSelectedProfile + " to the clipboard.");
        return 1;
    }

    private static int importProfile() {
        try {
            String value = Minecraft.getInstance().keyboardHandler.getClipboard();
            if (!value.startsWith("CSB1:") || value.length() > 32768) throw new IllegalArgumentException();
            byte[] decoded = Base64.getUrlDecoder().decode(value.substring(5));
            PhoenixConfig.SlotBindingProfile imported = GSON.fromJson(
                new String(decoded, java.nio.charset.StandardCharsets.UTF_8), PhoenixConfig.SlotBindingProfile.class);
            sanitize(imported);
            String base = "imported";
            String name = base;
            for (int i = 2; profileName(name) != null; i++) name = base + '-' + i;
            cfg.slotBindingProfiles.put(name, imported);
            cfg.slotBindingSelectedProfile = name;
            save();
            feedback("Imported " + imported.binds.values().stream().mapToInt(List::size).sum() + " bindings as " + name + ".");
            return 1;
        } catch (RuntimeException ignored) {
            feedback("Clipboard does not contain a valid Constellation slot-binding profile.");
            return 0;
        }
    }

    public static List<String> profileNames() {
        ensure();
        return List.copyOf(cfg.slotBindingProfiles.keySet());
    }

    public static String selectedProfile() {
        ensure();
        return cfg.slotBindingSelectedProfile;
    }

    public static boolean selectProfileFromEditor(String name) {
        return select(name) == 1;
    }

    public static boolean createProfileFromEditor(String name) {
        return create(name, "global") == 1;
    }

    public static boolean deleteProfileFromEditor(String name) {
        return delete(name) == 1;
    }

    public static Map<Integer, List<Integer>> bindings() {
        ensure();
        LinkedHashMap<Integer, List<Integer>> copy = new LinkedHashMap<>();
        profile().binds.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return copy;
    }

    public static int bindingColor(int canonical) {
        return color(canonical);
    }

    public static void editorClick(int canonical, boolean right) {
        if (!valid(canonical)) return;
        if (right) {
            unbind(canonical);
            return;
        }
        Integer first = bindingSlot;
        if (first == null) {
            bindingSlot = canonical;
            return;
        }
        bindingSlot = null;
        if (first.equals(canonical)) return;
        if (isHotbar(first) != isHotbar(canonical)) {
            int hotbar = isHotbar(first) ? first : canonical;
            int inventory = isHotbar(first) ? canonical : first;
            bind(hotbar, inventory);
        } else bindingSlot = canonical;
    }

    public static Integer editorSelection() {
        return bindingSlot;
    }

    public static void editorCycleColor(int canonical) {
        if (isBound(canonical)) cycleColor(canonical);
    }

    public static void clearEditorSelection() {
        bindingSlot = null;
    }

    private static void bind(int hotbar, int inventory) {
        PhoenixConfig.SlotBindingProfile profile = profile();
        for (List<Integer> values : profile.binds.values()) values.removeIf(value -> value == inventory);
        profile.binds.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        List<Integer> values = profile.binds.computeIfAbsent(hotbar, ignored -> new ArrayList<>());
        if (!values.contains(inventory)) values.add(inventory);
        profile.last.put(hotbar, inventory);
        profile.colors.putIfAbsent(hotbar, PALETTE[Math.floorMod(hotbar, PALETTE.length)]);
        save();
        feedback("Bound inventory slot " + inventory + " to hotbar slot " + (hotbar + 1) + ".");
        sound(1.45f);
    }

    private static int unbind(int canonical) {
        PhoenixConfig.SlotBindingProfile profile = profile();
        int before = profile.binds.values().stream().mapToInt(List::size).sum();
        if (isHotbar(canonical)) {
            profile.binds.remove(canonical);
            profile.last.remove(canonical);
            profile.colors.remove(canonical);
        } else {
            for (List<Integer> values : profile.binds.values()) values.removeIf(value -> value == canonical);
            profile.binds.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            profile.last.entrySet().removeIf(entry -> entry.getValue() == canonical);
        }
        int removed = before - profile.binds.values().stream().mapToInt(List::size).sum();
        if (removed > 0) save();
        return removed;
    }

    private static void cycleColor(int canonical) {
        int hotbar = isHotbar(canonical) ? canonical : hotbarFor(canonical);
        if (hotbar < 0) return;
        int current = profile().colors.getOrDefault(hotbar, PALETTE[hotbar]);
        int index = -1;
        for (int i = 0; i < PALETTE.length; i++) if (PALETTE[i] == current) index = i;
        profile().colors.put(hotbar, PALETTE[(index + 1) % PALETTE.length]);
        save();
    }

    private static int color(int canonical) {
        if (cfg.slotBindingUseFixedColor) return cfg.slotBindingFixedColor;
        int hotbar = isHotbar(canonical) ? canonical : hotbarFor(canonical);
        return hotbar < 0 ? cfg.slotBindingFixedColor
            : profile().colors.getOrDefault(hotbar, PALETTE[Math.floorMod(hotbar, PALETTE.length)]);
    }

    private static void selectAreaProfile() {
        if (!active() || !cfg.slotBindingDynamicProfiles) return;
        String area = ConstellationClient.loc().area().name();
        if (area.equals(lastArea)) return;
        lastArea = area;
        for (Map.Entry<String, PhoenixConfig.SlotBindingProfile> entry : cfg.slotBindingProfiles.entrySet()) {
            if (area.equalsIgnoreCase(entry.getValue().area)) {
                cfg.slotBindingSelectedProfile = entry.getKey();
                save();
                return;
            }
        }
        String global = cfg.slotBindingProfiles.entrySet().stream()
            .filter(entry -> entry.getValue().area == null || entry.getValue().area.isBlank())
            .map(Map.Entry::getKey).findFirst().orElse("default");
        cfg.slotBindingSelectedProfile = global;
        save();
    }

    private static Slot find(AbstractContainerScreen<?> screen, int canonical) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        for (Slot slot : screen.getMenu().slots)
            if (slot.container == mc.player.getInventory() && slot.getContainerSlot() == canonical) return slot;
        return null;
    }

    private static Slot hovered(AbstractContainerScreen<?> screen) {
        return ((ContainerScreenAccessor) screen).constellation$hoveredSlot();
    }

    private static Integer canonical(Slot slot) {
        Minecraft mc = Minecraft.getInstance();
        if (slot == null || mc.player == null || slot.container != mc.player.getInventory()) return null;
        return valid(slot.getContainerSlot()) ? slot.getContainerSlot() : null;
    }

    private static boolean valid(int canonical) {
        return canonical >= 0 && canonical <= 35;
    }

    private static boolean isHotbar(int canonical) {
        return canonical >= 0 && canonical <= 8;
    }

    private static boolean isBound(int canonical) {
        if (isHotbar(canonical)) return !partners(canonical).isEmpty();
        return hotbarFor(canonical) >= 0;
    }

    private static List<Integer> partners(int canonical) {
        if (isHotbar(canonical)) return profile().binds.getOrDefault(canonical, List.of());
        int hotbar = hotbarFor(canonical);
        return hotbar < 0 ? List.of() : List.of(hotbar);
    }

    private static int hotbarFor(int inventory) {
        for (Map.Entry<Integer, List<Integer>> entry : profile().binds.entrySet())
            if (entry.getValue().contains(inventory)) return entry.getKey();
        return -1;
    }

    private static PhoenixConfig.SlotBindingProfile profile() {
        PhoenixConfig.SlotBindingProfile selected = cfg.slotBindingProfiles == null ? null
            : cfg.slotBindingProfiles.get(cfg.slotBindingSelectedProfile);
        if (selected != null) return selected;
        ensure();
        return cfg.slotBindingProfiles.get(cfg.slotBindingSelectedProfile);
    }

    private static void ensure() {
        if (cfg == null) return;
        if (cfg.slotBindingProfiles == null) cfg.slotBindingProfiles = new LinkedHashMap<>();
        cfg.slotBindingProfiles.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        if (cfg.slotBindingProfiles.isEmpty()) cfg.slotBindingProfiles.put("default", new PhoenixConfig.SlotBindingProfile());
        cfg.slotBindingProfiles.values().forEach(PhoenixSlotBinding::sanitize);
        String selected = profileName(cfg.slotBindingSelectedProfile);
        cfg.slotBindingSelectedProfile = selected == null ? cfg.slotBindingProfiles.keySet().iterator().next() : selected;
    }

    private static void sanitize(PhoenixConfig.SlotBindingProfile profile) {
        if (profile.area == null) profile.area = "";
        if (profile.binds == null) profile.binds = new LinkedHashMap<>();
        if (profile.last == null) profile.last = new LinkedHashMap<>();
        if (profile.colors == null) profile.colors = new LinkedHashMap<>();
        LinkedHashMap<Integer, List<Integer>> clean = new LinkedHashMap<>();
        java.util.HashSet<Integer> used = new java.util.HashSet<>();
        profile.binds.forEach((hotbar, values) -> {
            if (hotbar == null || !isHotbar(hotbar) || values == null) return;
            ArrayList<Integer> valid = new ArrayList<>();
            for (Integer value : values)
                if (value != null && value >= 9 && value <= 35 && used.add(value) && !valid.contains(value)) valid.add(value);
            if (!valid.isEmpty()) clean.put(hotbar, valid);
        });
        profile.binds = clean;
        profile.last.entrySet().removeIf(entry -> !profile.binds.containsKey(entry.getKey())
            || !profile.binds.get(entry.getKey()).contains(entry.getValue()));
        profile.colors.entrySet().removeIf(entry -> !profile.binds.containsKey(entry.getKey()));
    }

    private static String profileName(String requested) {
        if (requested == null) return null;
        for (String name : cfg.slotBindingProfiles.keySet()) if (name.equalsIgnoreCase(requested)) return name;
        return null;
    }

    private static String cleanName(String value) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("[^A-Za-z0-9_-]", "");
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    private static void resetTransient() {
        bindingSlot = null;
        locationScreen = null;
        LOCATIONS.clear();
    }

    private static void save() {
        ConstellationClient.saveConfig();
    }

    private static boolean active() {
        return cfg != null && cfg.enabled && cfg.slotBinding && ConstellationClient.loc().onHypixel();
    }

    private static void feedback(String text) {
        if (cfg == null || !cfg.slotBindingFeedback) return;
        long now = System.currentTimeMillis();
        if (now - lastFeedback < 150) return;
        lastFeedback = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("\u00a75[Slot Binding] \u00a7f" + text));
    }

    private static void sound(float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (cfg != null && cfg.slotBindingSound && mc.player != null)
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, .7f, pitch);
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

    private static String plural(int amount, String singular, String plural) {
        return amount == 1 ? singular : plural;
    }

    private static String on(boolean value) {
        return value ? "on" : "off";
    }
}

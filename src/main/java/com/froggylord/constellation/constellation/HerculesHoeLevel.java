package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.HerculesConfig;
import com.froggylord.constellation.core.LocationManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/HoeLevelDisplay.kt
// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/MuteHoeLevelUp.kt
// levelable item fields ported from SkyHanni (LGPL-3.0-or-later): utils/SkyBlockItemModifierUtils.kt
public final class HerculesHoeLevel {
    public record Row(String label, String value, int color) {}
    private record State(ItemStack stack, String uuid, int baseLevel, int displayLevel, long xp, long needed, boolean upgrade, boolean overclock) {}

    private static final int OVERCLOCK_LEVEL = 40;
    private static final int MAX_LEVEL = 50;
    private static final long OVERFLOW_XP = 200_000;
    private static final long[] LEVELS = {
        1_000,2_000,3_000,5_000,8_000,12_000,16_000,20_000,25_000,40_000,
        60_000,80_000,100_000,120_000,140_000,160_000,180_000,200_000,250_000,300_000,
        350_000,400_000,450_000,500_000,550_000,600_000,650_000,700_000,750_000,800_000,
        850_000,900_000,950_000,1_000_000,1_050_000,1_100_000,1_150_000,1_200_000,1_250_000,1_300_000,
        1_500_000,2_000_000,2_500_000,3_000_000,3_500_000,4_000_000,4_500_000,5_000_000,5_500_000
    };
    private static final Pattern OVERFLOW = Pattern.compile("^OVERFLOW! Your (?<tool>.+) has just dropped a Tool Exp Capsule!$", Pattern.CASE_INSENSITIVE);

    private static HerculesConfig cfg;
    private static String observedTool = "";
    private static long observedXp = -1;
    private static long observedAt;
    private static double xpPerSecond;
    private static long lastGainAt;

    private HerculesHoeLevel() {}

    public static void init(HerculesConfig config) {
        cfg = config;
        maps();
        ConstellationClient.tick().every(2, "hercules-hoe-level", HerculesHoeLevel::tick);
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : onChat(message));
        ClientPlayConnectionEvents.JOIN.register((a, b, c) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((a, b) -> reset());
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("hoelevel")
            .executes(ctx -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("status").executes(ctx -> status()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("set")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("level", IntegerArgumentType.integer(MAX_LEVEL, 100_000))
                    .executes(ctx -> setLevel(IntegerArgumentType.getInteger(ctx, "level")))))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset")
                .executes(ctx -> resetOverflow()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(ctx -> option(StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "state")))))));
    }

    public static boolean hudVisible() {
        return active() && state() != null;
    }

    public static List<Row> hudRows() {
        State state = state();
        if (state == null) return List.of();
        List<Row> rows = new ArrayList<>();
        if (cfg.hoeLevelShowLevel) rows.add(new Row("Level", state.displayLevel + " -> " + (state.displayLevel + 1), 0xFF55FFFF));
        if (cfg.hoeLevelShowProgress) rows.add(new Row("Tool XP", number(state.xp) + "/" + number(state.needed), state.upgrade ? 0xFFFF5555 : 0xFFFFFF55));
        if (cfg.hoeLevelShowPercent) rows.add(new Row("Progress", decimal(Math.clamp((double)state.xp / state.needed, 0, 1) * 100, 2) + "%", state.upgrade ? 0xFFFF5555 : 0xFFFFFF55));
        if (cfg.hoeLevelShowRemaining) rows.add(new Row("Remaining", number(Math.max(0, state.needed - state.xp)), 0xFFFFFF55));
        if (cfg.hoeLevelShowRate) rows.add(new Row("XP/min", number(Math.round(rate() * 60)), 0xFFFFFF55));
        if (cfg.hoeLevelShowEta) rows.add(new Row("ETA", eta(Math.max(0, state.needed - state.xp)), 0xFF55FFFF));
        if (state.upgrade && cfg.hoeLevelShowUpgradeRequired)
            rows.add(new Row(state.overclock ? "Overclock" : "Upgrade", "Required", 0xFFFF5555));
        if (cfg.hoeLevelWrongCropWarning && wrongCrop())
            rows.add(new Row("Warning", "Wrong crop", 0xFFFF5555));
        return rows;
    }

    public static boolean shouldCancel(ClientboundSoundPacket packet) {
        return cfg != null && cfg.enabled && cfg.hoeLevelDisplay && cfg.hoeLevelMuteSounds && inGarden()
            && packet.getSound().value().location().equals(SoundEvents.PORTAL_TRAVEL.location())
            && Math.abs(packet.getPitch() - 1.4920635f) < 1.0E-6f;
    }

    private static void tick() {
        if (!active()) { resetRate(); return; }
        State state = state();
        if (state == null) { resetRate(); return; }
        long now = System.currentTimeMillis();
        String key = state.uuid.isBlank() ? LyraTooltips.marketId(state.stack) : state.uuid;
        if (!key.equals(observedTool)) {
            observedTool = key;
            observedXp = state.xp;
            observedAt = now;
            xpPerSecond = 0;
            lastGainAt = 0;
            return;
        }
        long delta = state.xp - observedXp;
        long elapsed = now - observedAt;
        observedXp = state.xp;
        observedAt = now;
        if (delta <= 0 || delta > 10_000_000 || elapsed <= 0) return;
        double observed = delta / (elapsed / 1000.0);
        xpPerSecond = xpPerSecond <= 0 ? observed : xpPerSecond * .65 + observed * .35;
        lastGainAt = now;
    }

    private static Component onChat(Component message) {
        if (cfg == null || !cfg.enabled || !cfg.hoeLevelDisplay || !inGarden()) return message;
        String clean = clean(message.getString());
        Matcher matcher = OVERFLOW.matcher(clean);
        if (!matcher.matches()) return message;
        State state = state();
        if (state == null || state.uuid.isBlank()) return message;
        String heldName = clean(state.stack.getHoverName().getString());
        if (!heldName.contains(matcher.group("tool"))) return message;
        int next = cfg.hoeOverflowLevels.getOrDefault(key(state.uuid), 0) + 1;
        cfg.hoeOverflowLevels.put(key(state.uuid), next);
        ConstellationClient.saveConfig();
        if (!cfg.hoeLevelOverflow) return message;
        return message.copy().append(Component.literal(" §8(§3Level " + (state.baseLevel + next) + "§8)"));
    }

    private static State state() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        ItemStack stack = mc.player.getMainHandItem();
        CompoundTag extra = extra(stack);
        if (!extra.contains("levelable_exp") || !extra.contains("levelable_lvl")) return null;
        long xp = (long)extra.getDoubleOr("levelable_exp", 0);
        int base = extra.getIntOr("levelable_lvl", 0);
        if (base <= 0) return null;
        String uuid = extra.getStringOr("uuid", "");
        int overflow = cfg.hoeLevelOverflow && base >= MAX_LEVEL && !uuid.isBlank()
            ? cfg.hoeOverflowLevels.getOrDefault(key(uuid), 0) : 0;
        int display = base + overflow;
        long needed = base <= LEVELS.length ? LEVELS[base - 1] : OVERFLOW_XP;
        boolean upgrade = xp > needed;
        return new State(stack, uuid, base, display, Math.max(0, xp), needed, upgrade, base >= OVERCLOCK_LEVEL);
    }

    private static boolean wrongCrop() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        HerculesGardenTracker.Crop held = HerculesGardenTracker.cropInHand(mc.player.getMainHandItem());
        var rates = HerculesGardenTracker.rates();
        return held != null && rates != null && !rates.crop().equalsIgnoreCase(held.display());
    }

    private static int setLevel(int level) {
        State state = state();
        if (state == null || state.uuid.isBlank()) { local("Hold a specialized farming tool with a UUID."); return 0; }
        if (state.baseLevel < MAX_LEVEL) { local("Tools below level 50 cannot have overflow levels."); return 0; }
        cfg.hoeOverflowLevels.put(key(state.uuid), level - state.baseLevel);
        ConstellationClient.saveConfig();
        return status();
    }

    private static int resetOverflow() {
        State state = state();
        if (state == null || state.uuid.isBlank()) { local("Hold a specialized farming tool with a UUID."); return 0; }
        cfg.hoeOverflowLevels.remove(key(state.uuid));
        ConstellationClient.saveConfig();
        return status();
    }

    private static int option(String name, String value) {
        Boolean enabled = bool(value);
        if (enabled == null) { local("Use on or off."); return 0; }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled", "display" -> cfg.hoeLevelDisplay = enabled;
            case "overflow" -> cfg.hoeLevelOverflow = enabled;
            case "mute", "sound" -> cfg.hoeLevelMuteSounds = enabled;
            case "level" -> cfg.hoeLevelShowLevel = enabled;
            case "progress" -> cfg.hoeLevelShowProgress = enabled;
            case "percent" -> cfg.hoeLevelShowPercent = enabled;
            case "remaining" -> cfg.hoeLevelShowRemaining = enabled;
            case "rate" -> cfg.hoeLevelShowRate = enabled;
            case "eta" -> cfg.hoeLevelShowEta = enabled;
            case "upgrade" -> cfg.hoeLevelShowUpgradeRequired = enabled;
            case "wrongcrop" -> cfg.hoeLevelWrongCropWarning = enabled;
            default -> { local("Options: display, overflow, mute, level, progress, percent, remaining, rate, eta, upgrade, wrongcrop."); return 0; }
        }
        ConstellationClient.saveConfig();
        return status();
    }

    private static int status() {
        State state = state();
        if (state == null) local("Hoe level display " + on(cfg.hoeLevelDisplay) + ". Hold a specialized farming tool.");
        else local("Level " + state.displayLevel + ", " + number(state.xp) + "/" + number(state.needed)
            + " tool XP; overflow " + on(cfg.hoeLevelOverflow) + ", sounds " + (cfg.hoeLevelMuteSounds ? "muted" : "normal") + ".");
        return 1;
    }

    private static double rate() {
        return System.currentTimeMillis() - lastGainAt > Math.max(2, cfg.hoeLevelRateResetSeconds) * 1000L ? 0 : Math.max(0, xpPerSecond);
    }
    private static String eta(long remaining) {
        double rate = rate();
        if (rate <= 0) return "Waiting";
        long seconds = (long)Math.ceil(remaining / rate), hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    private static CompoundTag extra(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return new CompoundTag();
        CompoundTag root = data.copyTag(), legacy = root.getCompoundOrEmpty("ExtraAttributes");
        return legacy.isEmpty() ? root : legacy;
    }
    private static String key(String uuid) { return profile() + "|" + uuid; }
    private static String profile() {
        String profile = LyraStorageValue.currentProfileKey();
        return profile == null || profile.isBlank() ? "unknown" : profile.toLowerCase(Locale.ROOT);
    }
    private static boolean active() { return cfg != null && cfg.enabled && cfg.hoeLevelDisplay && inGarden(); }
    private static boolean inGarden() { return ConstellationClient.loc().area() == LocationManager.SkyblockArea.GARDEN; }
    private static String clean(String text) { String clean = ChatFormatting.stripFormatting(text); return clean == null ? "" : clean.trim(); }
    private static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }
    private static String decimal(double value, int precision) { return String.format(Locale.ROOT, "%." + Math.clamp(precision, 0, 6) + "f", value); }
    private static String on(boolean value) { return value ? "on" : "off"; }
    private static Boolean bool(String value) { return switch (value.toLowerCase(Locale.ROOT)) { case "on", "true", "yes", "1" -> true; case "off", "false", "no", "0" -> false; default -> null; }; }
    private static void local(String text) { Minecraft mc = Minecraft.getInstance(); if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§2[Hoe Level] §f" + text)); }
    private static void resetRate() { observedTool = ""; observedXp = -1; observedAt = 0; xpPerSecond = 0; lastGainAt = 0; }
    private static void reset() { resetRate(); }
    private static void maps() { if (cfg.hoeOverflowLevels == null) cfg.hoeOverflowLevels = new HashMap<>(); }
}

package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.PhoenixConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ported from Devonian (GPL-3.0-only): features/misc/CenturyCakeTimer.kt
public final class PhoenixCenturyCake {
    private static final Pattern EAT = Pattern.compile("^(?:Big )?Yum! You (?:gain|refresh) (.+?) for 48 hours!$");
    private static final List<String> CAKES = List.of("Pet Luck", "Defense", "Health", "Intelligence",
        "Strength", "Sea Creature Chance", "Farming Fortune", "Speed", "Foraging Fortune", "Ferocity",
        "Mining Fortune", "Vitality", "True Defense", "Magic Find", "Rift Time", "Cold Resistance");
    private static PhoenixConfig cfg;
    private static final LinkedHashSet<String> missing = new LinkedHashSet<>();
    private static long lastEat;
    private static long previousRemaining = Long.MIN_VALUE;
    private static String trackedProfile = "";
    private static boolean warned;
    private static int ticks;

    private PhoenixCenturyCake() {}

    public static void init(PhoenixConfig config) {
        cfg = config;
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) chat(message.getString().replaceAll("\u00a7.", "").trim());
            return true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++ticks >= 20) {
                ticks = 0;
                tick();
            }
        });
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("centurycake")
            .executes(c -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(c -> {
                cfg.centuryCakeTimer = !cfg.centuryCakeTimer; save(); return status();
            }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("clear").executes(c -> {
                cfg.centuryCakeExpiryByProfile.remove(profile()); resetTransient(); save(); return status();
            }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("reset").executes(c -> {
                setExpiry(System.currentTimeMillis() + duration()); return status();
            }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("duration")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("hours", IntegerArgumentType.integer(1, 168))
                    .executes(c -> { cfg.centuryCakeDurationHours = IntegerArgumentType.getInteger(c, "hours"); save(); return status(); })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("warning")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("minutes", IntegerArgumentType.integer(0, 1440))
                    .executes(c -> { cfg.centuryCakeWarningMinutes = IntegerArgumentType.getInteger(c, "minutes"); save(); return status(); })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(c -> option(StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "state")))))));
    }

    private static void chat(String text) {
        if (!active()) return;
        Matcher match = EAT.matcher(text);
        if (!match.matches()) return;
        String cake = recognize(match.group(1));
        setExpiry(System.currentTimeMillis() + duration());
        if (!cfg.centuryCakeChatHelper) return;
        long now = System.currentTimeMillis();
        if (now - lastEat > Math.clamp(cfg.centuryCakeHelperWindowMinutes, 1, 30) * 60_000L || missing.isEmpty())
            missing.addAll(CAKES);
        lastEat = now;
        if (!cake.isEmpty()) missing.remove(cake);
        int eaten = CAKES.size() - missing.size();
        if (missing.isEmpty()) {
            local("All 16 Century Cakes eaten.");
        } else {
            Component line = Component.literal("\u00a7b[Century Cakes] \u00a7fEaten " + eaten + "/16 cakes.")
                .withStyle(style -> style.withHoverEvent(
                    new HoverEvent.ShowText(Component.literal("Missing:\n" + String.join("\n", missing)))));
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.sendSystemMessage(line);
        }
    }

    private static void tick() {
        if (!active()) return;
        String profile = profile();
        if (!profile.equals(trackedProfile)) {
            trackedProfile = profile;
            resetTransient();
        }
        long expiry = expiry();
        if (expiry <= 0) {
            previousRemaining = Long.MIN_VALUE;
            return;
        }
        long remaining = expiry - System.currentTimeMillis();
        long warning = Math.clamp(cfg.centuryCakeWarningMinutes, 0, 1440) * 60_000L;
        if (previousRemaining != Long.MIN_VALUE && previousRemaining > warning && remaining <= warning && remaining > 0 && cfg.centuryCakeWarning && !warned) {
            warned = true;
            alert("Century Cakes expire in " + format(remaining) + ".", false);
        }
        if (previousRemaining > 0 && remaining <= 0) alert("Century Cake buffs expired.", true);
        previousRemaining = remaining;
    }

    private static void alert(String text, boolean expired) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        boolean chat = expired ? cfg.centuryCakeExpiredChat : cfg.centuryCakeWarningChat;
        boolean title = expired ? cfg.centuryCakeExpiredTitle : cfg.centuryCakeWarningTitle;
        boolean sound = expired ? cfg.centuryCakeExpiredSound : cfg.centuryCakeWarningSound;
        if (chat) local(text);
        if (title) mc.gui.hud.setTitle(Component.literal(text).withColor((expired ? cfg.centuryCakeExpiredColor : cfg.centuryCakeWarningColor) & 0xFFFFFF));
        if (sound) mc.player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), .9f, expired ? .7f : 1f);
    }

    public static PhoenixConfig config() { return cfg; }
    public static long expiry() {
        if (cfg == null) return 0;
        return cfg.centuryCakeExpiryByProfile.getOrDefault(profile(), 0L);
    }
    public static String profile() {
        String value = LyraStorageValue.currentProfileKey();
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase(Locale.ROOT);
    }
    public static String format(long millis) {
        long seconds = Math.max(0, millis / 1000);
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0 || cfg == null || !cfg.centuryCakeShowSeconds) return minutes + "m";
        return seconds + "s";
    }

    private static void setExpiry(long value) {
        cfg.centuryCakeExpiryByProfile.put(profile(), value);
        warned = false;
        previousRemaining = value - System.currentTimeMillis();
        save();
    }
    private static long duration() { return Math.clamp(cfg.centuryCakeDurationHours, 1, 168) * 3_600_000L; }
    private static String recognize(String raw) {
        String clean = raw.replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        for (String cake : CAKES) if (clean.contains(cake.toLowerCase(Locale.ROOT))) return cake;
        return "";
    }
    private static int status() {
        long expiry = expiry();
        local("Timer " + on(cfg.centuryCakeTimer) + ", profile " + profile() + ", "
            + (expiry <= 0 ? "status unknown." : expiry > System.currentTimeMillis() ? format(expiry - System.currentTimeMillis()) + " remaining." : "buffs expired."));
        return 1;
    }
    private static int option(String name, String raw) {
        Boolean value = bool(raw);
        if (value == null) { local("State must be on or off."); return 0; }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.centuryCakeTimer = value;
            case "hud" -> cfg.centuryCakeHud = value;
            case "expiredonly" -> cfg.centuryCakeOnlyExpired = value;
            case "unknown" -> cfg.centuryCakeShowUnknown = value;
            case "helper" -> cfg.centuryCakeChatHelper = value;
            case "warning" -> cfg.centuryCakeWarning = value;
            case "warningchat" -> cfg.centuryCakeWarningChat = value;
            case "warningtitle" -> cfg.centuryCakeWarningTitle = value;
            case "warningsound" -> cfg.centuryCakeWarningSound = value;
            case "expiredchat" -> cfg.centuryCakeExpiredChat = value;
            case "expiredtitle" -> cfg.centuryCakeExpiredTitle = value;
            case "expiredsound" -> cfg.centuryCakeExpiredSound = value;
            case "seconds" -> cfg.centuryCakeShowSeconds = value;
            case "profile" -> cfg.centuryCakeShowProfile = value;
            default -> { local("Unknown Century Cake option."); return 0; }
        }
        save();
        return status();
    }
    private static Boolean bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> null;
        };
    }
    private static String on(boolean value) { return value ? "on" : "off"; }
    private static boolean active() {
        return cfg != null && cfg.enabled && cfg.centuryCakeTimer && ConstellationClient.loc().onHypixel();
    }
    private static void resetTransient() {
        missing.clear();
        lastEat = 0;
        previousRemaining = Long.MIN_VALUE;
        warned = false;
    }
    private static void local(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("\u00a7b[Century Cakes] \u00a7f" + text));
    }
    private static void save() { ConstellationClient.saveConfig(); }
}

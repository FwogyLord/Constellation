package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.PhoenixConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

// ported from Devonian (GPL-3.0-only): features/misc/WorldAge.kt
public final class PhoenixWorldAge {
    public enum Phase { DAY, SUNSET, NIGHT, SUNRISE }
    public record Snapshot(long ticks, long day, long dayTick, String clock, Phase phase,
                           long transitionTicks, String transition, String realAge) {}

    private static PhoenixConfig cfg;

    private PhoenixWorldAge() {}

    public static void init(PhoenixConfig config) {
        cfg = config;
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("worldage")
            .executes(c -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(c -> {
                cfg.worldAge = !cfg.worldAge; save(); return status();
            }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(c -> option(StringArgumentType.getString(c, "name"),
                            StringArgumentType.getString(c, "state")))))));
    }

    public static PhoenixConfig config() { return cfg; }

    public static Snapshot snapshot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        long ticks = Math.max(0, mc.level.getOverworldClockTime());
        long day = ticks / 24000L + (cfg != null && cfg.worldAgeOneBased ? 1 : 0);
        long dayTick = ticks % 24000L;
        Phase phase;
        long transitionAt;
        String transition;
        if (dayTick < 12000) {
            phase = Phase.DAY; transitionAt = 12000; transition = "Sunset";
        } else if (dayTick < 13000) {
            phase = Phase.SUNSET; transitionAt = 13000; transition = "Night";
        } else if (dayTick < 23000) {
            phase = Phase.NIGHT; transitionAt = 23000; transition = "Sunrise";
        } else {
            phase = Phase.SUNRISE; transitionAt = 24000; transition = "Day";
        }
        long clockTicks = (dayTick + 6000) % 24000;
        int hour = (int) (clockTicks / 1000);
        int minute = (int) ((clockTicks % 1000) * 60 / 1000);
        String clock;
        if (cfg != null && cfg.worldAgeTwelveHourClock) {
            int shown = hour % 12;
            if (shown == 0) shown = 12;
            clock = String.format(Locale.ROOT, "%d:%02d %s", shown, minute, hour < 12 ? "AM" : "PM");
        } else {
            clock = String.format(Locale.ROOT, "%02d:%02d", hour, minute);
        }
        long transitionTicks = transitionAt - dayTick;
        return new Snapshot(ticks, day, dayTick, clock, phase, transitionTicks, transition,
            realDuration(ticks));
    }

    public static boolean visible() {
        if (cfg == null || !cfg.enabled || !cfg.worldAge || !cfg.worldAgeHud) return false;
        return !cfg.worldAgeHypixelOnly || ConstellationClient.loc().onHypixel();
    }

    public static String transitionTime(long ticks) {
        long seconds = Math.max(0, Math.round(ticks / 20.0));
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static String realDuration(long ticks) {
        long seconds = ticks / 20;
        long days = seconds / 86400; seconds %= 86400;
        long hours = seconds / 3600; seconds %= 3600;
        long minutes = seconds / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return minutes + "m";
    }

    private static int status() {
        Snapshot value = snapshot();
        local("Display " + on(cfg.worldAge) + (value == null ? ", no world loaded."
            : ", day " + value.day() + ", " + value.clock() + ", " + phaseName(value.phase()) + "."));
        return 1;
    }

    private static int option(String name, String raw) {
        Boolean value = bool(raw);
        if (value == null) { local("State must be on or off."); return 0; }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.worldAge = value;
            case "hud" -> cfg.worldAgeHud = value;
            case "hypixel" -> cfg.worldAgeHypixelOnly = value;
            case "day" -> cfg.worldAgeShowDay = value;
            case "onebased" -> cfg.worldAgeOneBased = value;
            case "clock" -> cfg.worldAgeShowClock = value;
            case "twelvehour" -> cfg.worldAgeTwelveHourClock = value;
            case "phase" -> cfg.worldAgeShowPhase = value;
            case "transition" -> cfg.worldAgeShowTransition = value;
            case "realage" -> cfg.worldAgeShowRealAge = value;
            case "ticks" -> cfg.worldAgeShowTicks = value;
            default -> { local("Unknown World Age option."); return 0; }
        }
        save();
        return status();
    }

    public static String phaseName(Phase phase) {
        return switch (phase) {
            case DAY -> "Day";
            case SUNSET -> "Sunset";
            case NIGHT -> "Night";
            case SUNRISE -> "Sunrise";
        };
    }

    private static Boolean bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> null;
        };
    }
    private static String on(boolean value) { return value ? "on" : "off"; }
    private static void local(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("\u00a7b[World Age] \u00a7f" + text));
    }
    private static void save() { ConstellationClient.saveConfig(); }
}

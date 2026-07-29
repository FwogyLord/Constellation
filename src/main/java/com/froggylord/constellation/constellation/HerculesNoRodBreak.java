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
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.FishingRodItem;

import java.util.Locale;

// ported from SkyHanni (LGPL-3.0-or-later): features/garden/farming/NoRodBreak.kt
public final class HerculesNoRodBreak {
    private static HerculesConfig cfg;
    private static long blocked;
    private static long lastFeedback;

    private HerculesNoRodBreak() {}

    public static void init(HerculesConfig config) {
        cfg = config;
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!active() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(player.getMainHandItem().getItem() instanceof FishingRodItem)) return InteractionResult.PASS;
            if (cfg.noRodBreakSneakBypass && player.isShiftKeyDown()) return InteractionResult.PASS;
            blocked++;
            feedback();
            return InteractionResult.FAIL;
        });
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("norodbreak")
            .executes(context -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle")
                .executes(context -> {
                    cfg.noRodBreak = !cfg.noRodBreak;
                    save();
                    return status();
                }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("resetcount")
                .executes(context -> {
                    blocked = 0;
                    return status();
                }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("cooldown")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("seconds",
                        IntegerArgumentType.integer(0, 60))
                    .executes(context -> {
                        cfg.noRodBreakFeedbackCooldownSeconds = IntegerArgumentType.getInteger(context, "seconds");
                        save();
                        return status();
                    })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(context -> option(StringArgumentType.getString(context, "name"),
                            StringArgumentType.getString(context, "state")))))));
    }

    private static int status() {
        local("Rod-break protection " + on(cfg.noRodBreak) + ", sneak bypass "
            + on(cfg.noRodBreakSneakBypass) + ", " + blocked + " block clicks prevented this session.");
        return 1;
    }

    private static int option(String name, String state) {
        Boolean value = parse(state);
        if (value == null) {
            local("State must be on or off.");
            return 0;
        }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.noRodBreak = value;
            case "sneak", "bypass" -> cfg.noRodBreakSneakBypass = value;
            case "actionbar" -> cfg.noRodBreakActionbar = value;
            case "chat" -> cfg.noRodBreakChat = value;
            case "sound" -> cfg.noRodBreakSound = value;
            default -> {
                local("Option must be enabled, sneak, actionbar, chat or sound.");
                return 0;
            }
        }
        save();
        return status();
    }

    private static void feedback() {
        long now = System.currentTimeMillis();
        if (now - lastFeedback < Math.clamp(cfg.noRodBreakFeedbackCooldownSeconds, 0, 60) * 1000L) return;
        lastFeedback = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String message = cfg.noRodBreakSneakBypass
            ? "Block protected. Hold sneak while clicking to bypass."
            : "Block protected from fishing-rod damage.";
        if (cfg.noRodBreakActionbar) mc.gui.hud.setOverlayMessage(Component.literal(message), false);
        if (cfg.noRodBreakChat) local(message);
        if (cfg.noRodBreakSound) mc.player.playSound(SoundEvents.NOTE_BLOCK_BASS.value(), .6f, .8f);
    }

    private static boolean active() {
        return cfg != null && cfg.enabled && cfg.noRodBreak
            && ConstellationClient.loc().area() == LocationManager.SkyblockArea.GARDEN;
    }

    private static Boolean parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> null;
        };
    }

    private static String on(boolean value) {
        return value ? "on" : "off";
    }

    private static void local(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.sendSystemMessage(Component.literal("\u00a72[Rod Protection] \u00a7f" + text));
    }

    private static void save() {
        ConstellationClient.saveConfig();
    }
}

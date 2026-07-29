package com.froggylord.constellation.constellation;

import com.froggylord.constellation.ConstellationClient;
import com.froggylord.constellation.config.PhoenixConfig;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.util.Locale;

// ported from Devonian (GPL-3.0-only): features/misc/Misc.kt, api/ImageTransfer.kt, mixin/ScreenshotMixin.java
public final class PhoenixScreenshotClipboard {
    private static PhoenixConfig cfg;
    private static volatile BufferedImage lastImage;
    private static volatile long copied;
    private static volatile long failed;
    private static volatile String lastFailure = "";

    private PhoenixScreenshotClipboard() {}

    public static void init(PhoenixConfig config) {
        cfg = config;
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(LiteralArgumentBuilder.<FabricClientCommandSource>literal("screenshotclipboard")
            .executes(c -> status())
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("toggle").executes(c -> {
                cfg.autoCopyScreenshot = !cfg.autoCopyScreenshot; save(); return status();
            }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("copylast").executes(c -> copyLast()))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("forget").executes(c -> {
                lastImage = null; local("Forgot the retained screenshot."); return 1;
            }))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("retries")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("count", IntegerArgumentType.integer(0, 10))
                    .executes(c -> { cfg.screenshotClipboardRetries = IntegerArgumentType.getInteger(c, "count"); save(); return status(); })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("delay")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument("milliseconds", IntegerArgumentType.integer(0, 1000))
                    .executes(c -> { cfg.screenshotClipboardRetryDelayMillis = IntegerArgumentType.getInteger(c, "milliseconds"); save(); return status(); })))
            .then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("option")
                .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("name", StringArgumentType.word())
                    .then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("state", StringArgumentType.word())
                        .executes(c -> option(StringArgumentType.getString(c, "name"),
                            StringArgumentType.getString(c, "state")))))));
    }

    public static boolean enabled() {
        return cfg != null && cfg.enabled && cfg.autoCopyScreenshot && supported();
    }

    public static void capture(NativeImage nativeImage) {
        if (!enabled()) return;
        BufferedImage image;
        try {
            image = convert(nativeImage);
        } catch (Throwable error) {
            failure(error);
            return;
        }
        if (cfg.screenshotClipboardKeepLast) lastImage = image;
        copyAsync(image);
    }

    private static BufferedImage convert(NativeImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                image.setRGB(x, y, source.getPixel(x, y));
        return image;
    }

    private static void copyAsync(BufferedImage image) {
        Thread.ofVirtual().name("Constellation screenshot clipboard").start(() -> {
            int attempts = Math.clamp(cfg.screenshotClipboardRetries, 0, 10) + 1;
            Throwable last = null;
            for (int attempt = 0; attempt < attempts; attempt++) {
                try {
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new ImageTransfer(image), null);
                    copied++;
                    feedback(true, "Screenshot copied to clipboard.");
                    return;
                } catch (Throwable error) {
                    last = error;
                    if (attempt + 1 < attempts) {
                        try {
                            Thread.sleep(Math.clamp(cfg.screenshotClipboardRetryDelayMillis, 0, 1000));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            last = interrupted;
                            break;
                        }
                    }
                }
            }
            failure(last);
        });
    }

    private static void failure(Throwable error) {
        failed++;
        lastFailure = error == null ? "unknown error" : error.getClass().getSimpleName();
        feedback(false, "Could not copy screenshot: " + lastFailure + ".");
    }

    private static void feedback(boolean success, String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null || cfg == null) return;
            if (success && cfg.screenshotClipboardSuccessActionbar) mc.gui.hud.setOverlayMessage(Component.literal(text), false);
            if (success && cfg.screenshotClipboardSuccessChat || !success && cfg.screenshotClipboardFailureChat) local(text);
            if (success && cfg.screenshotClipboardSound)
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, .6f, 1.3f);
        });
    }

    private static int copyLast() {
        if (!supported()) {
            local("Image clipboard access is unavailable on this platform.");
            return 0;
        }
        BufferedImage image = lastImage;
        if (image == null) {
            local("No screenshot is retained. Take one while retention is enabled.");
            return 0;
        }
        copyAsync(image);
        return 1;
    }

    private static int status() {
        local("Automatic copy " + on(cfg.autoCopyScreenshot) + ", platform "
            + (supported() ? "supported" : "unsupported") + ", " + copied + " copied and " + failed
            + " failed this session" + (lastFailure.isEmpty() ? "." : "; last failure " + lastFailure + "."));
        return 1;
    }

    private static int option(String name, String raw) {
        Boolean value = bool(raw);
        if (value == null) { local("State must be on or off."); return 0; }
        switch (name.toLowerCase(Locale.ROOT)) {
            case "enabled" -> cfg.autoCopyScreenshot = value;
            case "actionbar" -> cfg.screenshotClipboardSuccessActionbar = value;
            case "chat" -> cfg.screenshotClipboardSuccessChat = value;
            case "failure" -> cfg.screenshotClipboardFailureChat = value;
            case "sound" -> cfg.screenshotClipboardSound = value;
            case "retain" -> {
                cfg.screenshotClipboardKeepLast = value;
                if (!value) lastImage = null;
            }
            default -> { local("Unknown screenshot clipboard option."); return 0; }
        }
        save();
        return status();
    }

    private static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return !os.contains("mac") && !GraphicsEnvironment.isHeadless();
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
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("\u00a7b[Screenshot Clipboard] \u00a7f" + text));
    }
    private static void save() { ConstellationClient.saveConfig(); }

    // ported from Devonian (GPL-3.0-only): api/ImageTransfer.kt
    private record ImageTransfer(Image image) implements Transferable {
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }
}

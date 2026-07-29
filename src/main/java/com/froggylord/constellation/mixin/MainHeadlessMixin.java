package com.froggylord.constellation.mixin;

import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

// ported from Devonian (GPL-3.0-only): mixin/MainMixin.java
// ported from ScreenshotToClipboard (MIT): common/mixin/AWTHackMixin.java
@Mixin(Main.class)
public abstract class MainHeadlessMixin {
    @Inject(method = "main", at = @At("HEAD"), remap = false)
    private static void constellation$allowImageClipboard(String[] args, CallbackInfo ci) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"))
            System.setProperty("java.awt.headless", "false");
    }
}

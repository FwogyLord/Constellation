package com.froggylord.constellation.mixin;

import com.froggylord.constellation.constellation.PhoenixScreenshotClipboard;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

// ported from Devonian (GPL-3.0-only): mixin/ScreenshotMixin.java
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @ModifyVariable(
        method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private static Consumer<NativeImage> constellation$copyScreenshot(Consumer<NativeImage> original) {
        return image -> {
            PhoenixScreenshotClipboard.capture(image);
            original.accept(image);
        };
    }
}

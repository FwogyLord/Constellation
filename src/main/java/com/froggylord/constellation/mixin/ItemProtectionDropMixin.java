package com.froggylord.constellation.mixin;

import com.froggylord.constellation.constellation.ItemProtection;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// ported from Skyblocker (LGPL-3.0): mixins/LocalPlayerMixin.java
@Mixin(LocalPlayer.class)
public class ItemProtectionDropMixin {
    @Inject(method = "drop(Z)Z", at = @At("HEAD"), cancellable = true)
    private void constellation$protectDrop(boolean entireStack, CallbackInfoReturnable<Boolean> cir) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        // ported from Athen (BSD-3-Clause): modules/impl/general/slotbinds/SlotBinds.kt
        if (com.froggylord.constellation.constellation.PhoenixSlotBinding.shouldBlockDrop(
            player.getInventory().getSelectedSlot())) { cir.setReturnValue(false); return; }
        if (ItemProtection.shouldBlockDrop(player.getMainHandItem())) cir.setReturnValue(false);
    }
}

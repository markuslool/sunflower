package com.sunflower.client.mixin;

import com.sunflower.client.rt.RtBoot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Инвалидация вокселей: поставлен/сломан блок (включая синк с сервера) —
 * секция 16x16x16 встает в очередь на перепек.
 * Дедуп через HashSet в VoxelVolume, так что спам безопасен.
 */
@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("TAIL"))
    private void sunflower$markSectionDirty(
            BlockPos pos, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            RtBoot.volume().markSectionDirty(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        }
    }
}

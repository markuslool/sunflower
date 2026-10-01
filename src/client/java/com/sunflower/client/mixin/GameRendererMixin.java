package com.sunflower.client.mixin;

import com.sunflower.client.rt.RtOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Хук оверлея RT-теней: хвост GameRenderer.renderLevel.
 *
 * <p>В этот момент main target содержит мир (террейн через Sodium в том числе,
 * рука, эффекты), а GUI еще не рисовалась — идеальная точка для multiply-пасса.
 * Сам проход использует только ванильный API (RenderPipeline + LOAD-pass),
 * поэтому барьеры и порядок сабмитов разруливает движок.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void sunflower$rtOverlay(DeltaTracker deltaTracker, CallbackInfo ci) {
        RtOverlay.render((GameRenderer) (Object) this, deltaTracker);
    }
}

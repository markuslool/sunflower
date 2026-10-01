package com.sunflower.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sunflower.client.rt.RtOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 *
 * <p>ВАЖНО про view-bob: ванилла рендерит мир НЕ базовой проекцией, а ее копией
 * с накаченными bobHurt/bobView (покачивание при ходьбе). Оверлей обязан считать
 * лучи по ТОЙ ЖЕ матрице, иначе тени плывут в такт шагам. Поэтому здесь
 * применяется та же пара методов через @Shadow — дублирования математики нет,
 * рассинхрон невозможен. Портально-тошнотный спин (редкий кейс) не повторяется —
 * под ним тени могут чуть ехать, это отмечено и приемлемо для v1.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Shadow
    private void bobHurt(CameraRenderState cameraState, PoseStack poseStack) {
        throw new AssertionError("mixin shadow");
    }

    @Shadow
    private void bobView(CameraRenderState cameraState, PoseStack poseStack) {
        throw new AssertionError("mixin shadow");
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void sunflower$rtOverlay(DeltaTracker deltaTracker, CallbackInfo ci) {
        GameRenderer self = (GameRenderer) (Object) this;
        CameraRenderState cam = self.gameRenderState().levelRenderState.cameraRenderState;
        Matrix4f levelProj = new Matrix4f(cam.projectionMatrix);
        if (RtOverlay.useBob() != 0) {
            PoseStack bobStack = new PoseStack();
            this.bobHurt(cam, bobStack);
            if (self.gameRenderState().optionsRenderState.bobView) {
                this.bobView(cam, bobStack);
            }
            levelProj.mul(bobStack.last().pose());
        }
        RtOverlay.render(self, deltaTracker, levelProj);
    }
}


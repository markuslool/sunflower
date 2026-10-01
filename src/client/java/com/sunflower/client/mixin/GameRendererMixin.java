package com.sunflower.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.sunflower.client.rt.RtOverlay;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Хук оверлея RT-теней: хвост GameRenderer.renderLevel.
 *
 * <p>В этот момент main target содержит мир (террейн через Sodium в том числе),
 * рука, эффекты), а GUI еще не рисовалась — идеальная точка для multiply-пасса.
 *
 * <p>ГЛАВНОЕ — построение той же матрицы, которой ванилла нарисовала мир.
 * Проверено по байткоду renderLevel 26.2, цепочка РОВНО такая:
 * <pre>
 *   levelProj = new Matrix4f(camState.projectionMatrix)   // P
 *   bobStack  = new PoseStack()
 *   bobHurt(camState, bobStack)                          // урон/смерть
 *   if (options.bobView) bobView(camState, bobStack)      // покачивание при ходьбе
 *   levelProj.mul(bobStack.last().pose())                 // P * bob
 *   ... screenEffect (тошнота/портал, если screenEffectScale != 0) ...
 *   levelProj.rotate(spin, axis); levelProj.scale(k,1,1); levelProj.rotate(-spin, axis)
 *   RenderSystem.setProjectionMatrix(levelProj)
 *   LevelRenderer.render(..., camState, camState.viewRotationMatrix, ...)
 * </pre>
 * То есть террейн = (P * bob * nausea) * Vrot, а лучи оверлея строятся из
 * inv((P * bob * nausea) * Vrot). Отсутствие последнего шага (тошнота/портал) —
 * это рассинхрон лучей с картинкой; раньше он здесь и был.
 *
 * <p>Почему НЕ «свои матрицы вместо ванильных»: оверлей — post-pass, который
 * рисуется ПОСЛЕ мира, и матрицы ваниллы уже применены к террейну. Свои матрицы
 * нельзя «заменить»: если они разойдутся с реально нарисованным кадром, тени
 * будут уезжать именно так, как сейчас. Поэтому здесь не переписывается чужое
 * состояние, а один в один повторяется та же последовательность вызовов, что и
 * у ваниллы — тогда рассинхрон структурно невозможен.
 *
 * <p>Портально-тошнотный спин повторяется вместе с матрицей (шаг 3 выше).
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

    /** Накопленное время спина от тошноты/портала (vanilla-копия, нужен для матрицы). */
    @Shadow
    private float spinningEffectTime;

    /** Скорость спина от тошноты/портала. */
    @Shadow
    private float spinningEffectSpeed;

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void sunflower$rtOverlay(DeltaTracker deltaTracker, CallbackInfo ci) {
        GameRenderer self = (GameRenderer) (Object) this;
        CameraRenderState cam = self.gameRenderState().levelRenderState.cameraRenderState;
        OptionsRenderState options = self.gameRenderState().optionsRenderState;

        // Шаг 1-2: P, затем P * bob.
        Matrix4f levelProj = new Matrix4f(cam.projectionMatrix);
        if (RtOverlay.useBob() != 0) {
            PoseStack bobStack = new PoseStack();
            this.bobHurt(cam, bobStack);
            if (options.bobView) {
                this.bobView(cam, bobStack);
            }
            levelProj.mul(bobStack.last().pose());
        }

        // Шаг 3: тошнота/портал — тот же блок, что в renderLevel после mul(bob).
        // Пока он не повторялся, лучи расходились с террейном при любом effectScale != 0.
        sunflower$applyScreenEffect(levelProj, options, deltaTracker,
                Minecraft.getInstance().player);

        RtOverlay.render(self, deltaTracker, levelProj);
    }

    /**
     * Тот же screen-effect блок, что у ваниллы: scale + двойной rotate вокруг
     * диагональной оси. Вызывается сразу после P*bob, до отрисовки мира —
     * порядок вызовов важен, иначе поворот окажется с другой стороны mul.
     */
    private void sunflower$applyScreenEffect(
            Matrix4f levelProj, OptionsRenderState options, DeltaTracker deltaTracker, LocalPlayer player) {
        if (player == null) {
            return;
        }
        float f2 = deltaTracker.getGameTimeDeltaPartialTick(false);
        float screenScale = options.screenEffectScale;
        float portal = Mth.lerp(f2, player.oPortalEffectIntensity, player.portalEffectIntensity);
        float nausea = player.getEffectBlendFactor(MobEffects.NAUSEA, f2);
        float f15 = Math.max(portal, nausea) * (screenScale * screenScale);
        if (f15 <= 0.0F) {
            return;
        }
        float f16 = 5.0F / (f15 * f15 + 5.0F) - f15 * 0.04F;
        f16 *= f16;
        Vector3f axis = new Vector3f(0.0F, Mth.SQRT_OF_TWO / 2.0F, Mth.SQRT_OF_TWO / 2.0F);
        // JOML rotate ждёт радианы, ванилла домножает на deg2rad — так и делаем.
        float spin = (this.spinningEffectTime + f2 * this.spinningEffectSpeed) * 0.017453292F;
        levelProj.rotate(spin, axis);
        levelProj.scale(1.0F / (1.0F + f16), 1.0F, 1.0F);
        levelProj.rotate(-spin, axis);
    }
}
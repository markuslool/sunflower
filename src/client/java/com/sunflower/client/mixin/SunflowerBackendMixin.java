package com.sunflower.client.mixin;

import com.sunflower.client.rt.RtBoot;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Хук перепроверки бэкенда после старта игры.
 *
 * <p>Почему здесь: ванильный VulkanDevice создается в конструкторе Minecraft,
 * а mixin на конструктор хрупкий между снапшотами. {@code run()} вызывается
 * один раз уже после создания девайса — тут бэкенд точно известен.
 *
 * <p>TODO тебе: когда уточнишь маппинги 26.2, добавь второй инжект в конец
 * ванильного opaque-прохода (WorldRenderer/RenderPass) и вызывай
 * SunShadowPipeline dispatch. Сейчас только проверка, без GL-фолбэка.
 */
@Mixin(Minecraft.class)
public class SunflowerBackendMixin {
    @Inject(at = @At("HEAD"), method = "run")
    private void sunflower$refreshBackend(CallbackInfo info) {
        RtBoot.refreshBackendState();
    }
}

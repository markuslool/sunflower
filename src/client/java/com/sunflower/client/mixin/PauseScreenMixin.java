package com.sunflower.client.mixin;

import com.sunflower.client.menu.SunflowerMenuScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Кнопка «Sunflower» в ванильном меню паузы (ESC).
 *
 * <p>Раньше настройки RT открывались только командой {@code /sunflower rt}, что неудобно:
 * команду надо помнить. Теперь ESC → «Sunflower» → «RTX — трассировка».
 *
 * <p>Позиция считается от нижней границы уже расставленных ванилой кнопок, а не от
 * фиксированного y: в 26.2 раскладка паузы зависит от одиночной/мультиплеерной игры,
 * наличия друзей и репорта, поэтому жёсткая координата либо наезжала бы на кнопки,
 * либо уезжала бы за экран. Нижняя граница + 4px с защитным прижатием к низу.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;

    @Inject(method = "init", at = @At("TAIL"))
    private void sunflower$addMenuButton(CallbackInfo ci) {
        PauseScreen self = (PauseScreen) (Object) this;

        int lowest = 0;
        for (Object child : self.children()) {
            if (child instanceof AbstractWidget widget) {
                lowest = Math.max(lowest, widget.getBottom());
            }
        }

        int x = (self.width - BUTTON_WIDTH) / 2;
        // Не даём кнопке уехать за нижний край на низких разрешениях / крупном GUI-масштабе.
        int y = Math.min(self.height - BUTTON_HEIGHT - 4, lowest + 4);

        Button button = Button.builder(Component.literal("Sunflower"), b -> SunflowerMenuScreen.open(self))
                .bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        ((ScreenInvoker) self).sunflower$addRenderableWidget(button);
    }
}
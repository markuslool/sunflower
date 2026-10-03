package com.sunflower.client.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Доступ к protected-методам {@link Screen} из миксинов.
 *
 * <p>Mixin-класс не наследует целевой класс на этапе компиляции, поэтому
 * {@code Screen.addRenderableWidget} из миксина недоступен — javac не считает
 * миксин подклассом {@code Screen}. На уровне байткода всё было бы валидно
 * (после применения миксина класс <em>является</em> PauseScreen), но компилятор
 * об этом не знает. Поэтому идём через {@link Invoker}.
 */
@Mixin(Screen.class)
public interface ScreenInvoker {
    @Invoker("addRenderableWidget")
    Button sunflower$addRenderableWidget(Button button);
}
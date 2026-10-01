package com.sunflower.client;

import com.sunflower.client.rt.RtBoot;
import com.sunflower.client.rt.RtOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class SunflowerClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// RT-инициализация: конфиг + проверка Vulkan (без GL-фолбэка).
		RtBoot.init();

		// Повторные пробы бэкенда + приветствие в чат при входе в мир.
		// TODO тебе: когда появится render-hook, дергать RtBoot.shouldRenderRt() в нем.
		ClientTickEvents.END_CLIENT_TICK.register(client -> RtBoot.tick());

		// /sunflower rt — экран настроек (временно, до кнопки в Video Settings 26.2).
		// /sunflower status — диагностика в чат без копания в логах.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("sunflower")
						.then(ClientCommands.literal("rt")
								.executes(ctx -> {
									Minecraft mc = Minecraft.getInstance();
									RtSettingsScreen.open(mc.gui.screen());
									return 1;
								}))
						.then(ClientCommands.literal("status")
								.executes(ctx -> {
									for (String line : RtBoot.statusLines()) {
										ctx.getSource().sendFeedback(Component.literal("§e[Sunflower RT] §f" + line));
									}
									return 1;
								}))
						.then(ClientCommands.literal("debug")
								.then(ClientCommands.argument("mode", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 2))
										.executes(ctx -> {
											int mode = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "mode");
											RtOverlay.setDebugMode(mode);
											ctx.getSource().sendFeedback(Component.literal(
													"§e[Sunflower RT] §fdebug=" + mode + " (0 shadows, 1 blacken surfaces, 2 half screen)"));
											return 1;
										}))))));
	}

	/** Текущий экран (в 26.2 живет в {@code mc.gui}). */
	private static net.minecraft.client.gui.screens.Screen getScreenReflect(Minecraft mc) {
		return mc.gui.screen();
	}
}

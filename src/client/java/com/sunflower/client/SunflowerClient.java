package com.sunflower.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
		ClientTickEvents.END_CLIENT_TICK.register(client -> RtBoot.tick());

		// /sunflower rt — экран настроек (временно, до кнопки в Video Settings 26.2).
		// /sunflower status — диагностика в чат без копания в логах.
		// /sunflower debug <0|1|2> — режимы визуализации.
		// /sunflower bob <0|1> — матрица лучей: базовая проекция или с view-bob.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			var root = ClientCommands.literal("sunflower");

			root.then(ClientCommands.literal("rt").executes(ctx -> {
				Minecraft mc = Minecraft.getInstance();
				RtSettingsScreen.open(mc.gui.screen());
				return 1;
			}));

			root.then(ClientCommands.literal("status").executes(ctx -> {
				for (String line : RtBoot.statusLines()) {
					ctx.getSource().sendFeedback(Component.literal("§e[Sunflower RT] §f" + line));
				}
				return 1;
			}));

			root.then(ClientCommands.literal("debug").then(ClientCommands.argument("mode", IntegerArgumentType.integer(0, 2)).executes(ctx -> {
				int mode = IntegerArgumentType.getInteger(ctx, "mode");
				RtOverlay.setDebugMode(mode);
				ctx.getSource().sendFeedback(Component.literal("§e[Sunflower RT] §fdebug=" + mode + " (0 shadows, 1 blacken surfaces, 2 half screen)"));
				return 1;
			})));

			root.then(ClientCommands.literal("bob").then(ClientCommands.argument("mode", IntegerArgumentType.integer(0, 1)).executes(ctx -> {
				int mode = IntegerArgumentType.getInteger(ctx, "mode");
				RtOverlay.setUseBob(mode);
				ctx.getSource().sendFeedback(Component.literal("§e[Sunflower RT] §fbob=" + mode + " (0 base projection, 1 +view-bob). Walk and compare."));
				return 1;
			})));

			dispatcher.register(root);
		});
	}

	/** Текущий экран (в 26.2 живет в {@code mc.gui}). */
	private static net.minecraft.client.gui.screens.Screen getScreenReflect(Minecraft mc) {
		return mc.gui.screen();
	}
}

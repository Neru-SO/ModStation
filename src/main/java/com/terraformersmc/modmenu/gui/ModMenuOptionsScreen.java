package com.terraformersmc.modmenu.gui;

import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.config.ModMenuConfig;
import com.terraformersmc.modmenu.config.ModMenuConfigManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.text.Text;

public class ModMenuOptionsScreen extends GameOptionsScreen {
	private int headerH = 48;

	public ModMenuOptionsScreen(Screen previous) {
		super(previous, MinecraftClient.getInstance().options, Text.translatable("modmenu.options"));
	}

	@Override
	protected void addOptions() {
		if (this.body != null) {
			this.body.addAll(ModMenuConfig.asOptions());
		}
	}

	@Override
	public void render(DrawContext drawContext, int mouseX, int mouseY, float delta) {
		int W = this.width;
		int H = this.height;
		this.headerH = Math.max(36, H / 16);

		drawContext.fill(0, 0, W, H, 0xFF08080D);
		drawContext.fill(0, 0, W, headerH, 0xFF0F0F17);
		drawContext.fill(0, headerH - 1, W, headerH, 0xFF1A1A28);

		drawContext.drawTextWithShadow(this.textRenderer, Text.translatable("modmenu.options"), 14, (headerH - 8) / 2, 0xFFF1F5F9);

		super.render(drawContext, mouseX, mouseY, delta);
	}

	@Override
	public void removed() {
		ModMenuConfigManager.save();
		ModMenu.checkForUpdates();
	}
}

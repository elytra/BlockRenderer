package com.unascribed.blockrenderer;

import com.google.common.base.Strings;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiPageButtonList.GuiResponder;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlider;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

public class GuiEnterModId extends GuiScreen implements GuiResponder {
	private String prefill;
	private GuiTextField text;
	private GuiSlider size;
	private GuiScreen old;
	
	public GuiEnterModId(GuiScreen old, String prefill) {
		this.old = old;
		this.prefill = Strings.nullToEmpty(prefill);
	}
	
	@Override
	public void initGui() {
		Keyboard.enableRepeatEvents(true);
		String oldText = (text == null ? prefill : text.getText());
		
		float oldSize = (size == null ? 512 : size.getSliderValue());
		
		text = new GuiTextField(0, mc.fontRenderer, width/2-100, height/6+50, 200, 20);
		text.setText(oldText);
		
		buttonList.add(new GuiButton(2, width/2-100, height/6+120, 98, 20, I18n.format("gui.cancel")));
		GuiButton render = new GuiButton(1, width/2+2, height/6+120, 98, 20, I18n.format("gui.render"));
		buttonList.add(render);
		int minSize = Math.min(mc.displayWidth, mc.displayHeight);
		size = new GuiSlider(this, 3, width/2-100, height/6+80, I18n.format("gui.rendersize"), 16, Math.min(2048, minSize), Math.min(oldSize, minSize), (id, name, value) -> {
			String px = Integer.toString(round(value));
			return name+": "+px+"x"+px;
		});
		size.width = 200;
		buttonList.add(size);
		
		text.setFocused(true);
		text.setCanLoseFocus(false);
		boolean enabled = mc.world != null;
		render.enabled = enabled;
		text.setEnabled(enabled);
		size.enabled = enabled;
	}

	private int round(float value) {
		int val = (int)value;
		// There's a more efficient method in MathHelper, but it rounds up. We want the nearest.
		int nearestPowerOfTwo = (int)Math.pow(2, Math.ceil(Math.log(val)/Math.log(2)));
		int minSize = Math.min(mc.displayHeight, mc.displayWidth);
		if (nearestPowerOfTwo < minSize && Math.abs(val-nearestPowerOfTwo) < 32) {
			val = nearestPowerOfTwo;
		}
		return Math.min(val, minSize);
	}
	
	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		drawDefaultBackground();
		super.drawScreen(mouseX, mouseY, partialTicks);
		drawCenteredString(mc.fontRenderer, I18n.format("gui.entermodid"), width/2, height/6, -1);
		if (mc.world == null) {
			drawCenteredString(mc.fontRenderer, I18n.format("gui.noworld"), width/2, height/6+30, 0xFF5555);
		} else {
			boolean widthCap = (mc.displayWidth < 2048);
			boolean heightCap = (mc.displayHeight < 2048);
			String str = null;
			if (widthCap && heightCap) {
				if (mc.displayWidth > mc.displayHeight) {
					str = "gui.cappedheight";
				} else if (mc.displayWidth == mc.displayHeight) {
					str = "gui.cappedboth";
				} else if (mc.displayHeight > mc.displayWidth) {
					str = "gui.cappedwidth";
				}
			} else if (widthCap) {
				str = "gui.cappedwidth";
			} else if (heightCap) {
				str = "gui.cappedheight";
			}
			if (str != null) {
				drawCenteredString(mc.fontRenderer, I18n.format(str, Math.min(mc.displayHeight, mc.displayWidth)), width/2, height/6+104, 0xFFFFFF);
			}
		}
		text.drawTextBox();
	}

	@Override
	protected void actionPerformed(GuiButton button) throws IOException {
		super.actionPerformed(button);
		if (button.id == 1) {
			if (mc.world != null) {
				BlockRenderer.inst.pendingBulkRender = text.getText();
				BlockRenderer.inst.pendingBulkRenderSize = round(size.getSliderValue());
			}
			mc.displayGuiScreen(old);
		} else if (button.id == 2) {
			mc.displayGuiScreen(old);
		}
	}

	@Override
	public void updateScreen() {
		super.updateScreen();
		text.updateCursorCounter();
	}

	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException {
		super.keyTyped(typedChar, keyCode);
		text.textboxKeyTyped(typedChar, keyCode);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
		super.mouseClicked(mouseX, mouseY, mouseButton);
		text.mouseClicked(mouseX, mouseY, mouseButton);
	}

	@Override
	public void onGuiClosed() {
		super.onGuiClosed();
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	public void setEntryValue(int id, float value) {
		size.setSliderValue(round(value), false);
	}
	
	@Override
	public void setEntryValue(int id, boolean value) {
	}
	
	@Override
	public void setEntryValue(int id, String value) {
	}
}

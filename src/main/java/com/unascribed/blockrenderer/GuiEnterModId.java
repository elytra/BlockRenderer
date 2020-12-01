package com.unascribed.blockrenderer;

import com.google.common.base.Strings;
import com.mojang.blaze3d.matrix.MatrixStack;
import javax.annotation.Nonnull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AbstractSlider;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.lwjgl.glfw.GLFW;

public class GuiEnterModId extends Screen {

    private final String prefill;
    private final Screen old;
    private TextFieldWidget text;
    private Slider slider;
    private double sliderValue;
    private boolean fixSliderMax;

    public GuiEnterModId(Screen old, String prefill) {
        super(StringTextComponent.EMPTY);
        this.old = old;
        this.prefill = Strings.nullToEmpty(prefill);
        this.sliderValue = 512;
    }

    @Override
    public void resize(@Nonnull Minecraft minecraft, int width, int height) {
        String oldText = text.getText();
        this.init(minecraft, width, height);
        text.setText(oldText);
        fixSliderMax = true;
    }

    @Override
    public void init() {
        minecraft.keyboardListener.enableRepeatEvents(true);
        text = new TextFieldWidget(font, width / 2 - 100, height / 6 + 50, 200, 20, StringTextComponent.EMPTY);
        text.setText(prefill);

        addButton(new Button(width / 2 - 100, height / 6 + 120, 98, 20, new TranslationTextComponent("gui.blockrenderer.cancel"),
              button -> minecraft.displayGuiScreen(old)));

        addButton(new Button(width / 2 + 2, height / 6 + 120, 98, 20, new TranslationTextComponent("gui.blockrenderer.render"), button -> render()));
        slider = addButton(new Slider(width / 2 - 100, height / 6 + 80, 200, 20, new TranslationTextComponent("gui.blockrenderer.render_size"),
              sliderValue, 16, getSliderMax()));

        text.setFocused2(true);
        text.setCanLoseFocus(false);
    }

    private int getSliderMax() {
        return Math.min(2048, Math.min(minecraft.getMainWindow().getWidth(), minecraft.getMainWindow().getHeight()));
    }

    private int round(double value) {
        int val = (int) value;
        // There's a more efficient method in MathHelper, but it rounds up. We want the nearest.
        int nearestPowerOfTwo = (int) Math.pow(2, Math.ceil(Math.log(val) / Math.log(2)));
        int minSize = Math.min(minecraft.getMainWindow().getHeight(), minecraft.getMainWindow().getWidth());
        if (nearestPowerOfTwo < minSize && Math.abs(val - nearestPowerOfTwo) < 32) {
            val = nearestPowerOfTwo;
        }
        return Math.min(val, minSize);
    }

    @Override
    public void render(@Nonnull MatrixStack matrix, int mouseX, int mouseY, float partialTicks) {
        renderBackground(matrix);
        super.render(matrix, mouseX, mouseY, partialTicks);
        drawCenteredString(matrix, minecraft.fontRenderer, new TranslationTextComponent("gui.blockrenderer.enter_modid"), width / 2, height / 6, -1);
        int displayWidth = minecraft.getMainWindow().getWidth();
        int displayHeight = minecraft.getMainWindow().getHeight();
        boolean widthCap = (displayWidth < 2048);
        boolean heightCap = (displayHeight < 2048);
        String translationKey = null;
        if (widthCap && heightCap) {
            if (displayWidth > displayHeight) {
                translationKey = "gui.blockrenderer.capped_height";
            } else if (displayWidth == displayHeight) {
                translationKey = "gui.blockrenderer.capped_both";
            } else { //displayHeight > displayWidth
                translationKey = "gui.blockrenderer.capped_width";
            }
        } else if (widthCap) {
            translationKey = "gui.blockrenderer.capped_width";
        } else if (heightCap) {
            translationKey = "gui.blockrenderer.capped_height";
        }
        if (translationKey != null) {
            drawCenteredString(matrix, minecraft.fontRenderer, new TranslationTextComponent(translationKey, Math.min(displayHeight, displayWidth)),
                  width / 2, height / 6 + 104, 0xFFFFFF);
        }
        text.renderButton(matrix, mouseX, mouseY, partialTicks);
    }

    private void render() {
        if (minecraft.world != null) {
            BlockRenderer.renderHandler.pendingBulkRender = text.getText();
            BlockRenderer.renderHandler.pendingBulkRenderSize = round(sliderValue);
        }
        minecraft.displayGuiScreen(old);
    }

    @Override
    public void tick() {
        super.tick();
        text.tick();
        if (fixSliderMax) {//Ugly "hack" because the window's size isn't actually updated yet during init after a resize
            fixSliderMax = false;
            slider.updateSliderMax(getSliderMax());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (text.canWrite() && keyCode != GLFW.GLFW_KEY_ESCAPE) {//Manually handle hitting escape to make the whole interface go away
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                //Handle processing both the enter key and the numpad enter key
                render();
                return true;
            }
            return text.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int keyCode) {
        if (text.canWrite()) {
            return text.charTyped(c, keyCode);
        }
        return super.charTyped(c, keyCode);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (text.isMouseOver(mouseX, mouseY)) {
            return text.mouseClicked(mouseX, mouseY, mouseButton);
        }
        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void onClose() {
        super.onClose();
        minecraft.keyboardListener.enableRepeatEvents(false);
    }

    private static double normalizeValue(double value, double min, double max) {
        return MathHelper.clamp((MathHelper.clamp(value, min, max) - min) / (max - min), 0.0D, 1.0D);
    }

    private class Slider extends AbstractSlider {

        private final double minValue;
        private double maxValue;

        public Slider(int x, int y, int width, int height, ITextComponent message, double defaultValue, double minValue, double maxValue) {
            super(x, y, width, height, message, normalizeValue(defaultValue, minValue, maxValue));
            this.minValue = minValue;
            this.maxValue = maxValue;
            func_230979_b_();
        }

        @Override
        protected void func_230979_b_() {
            setMessage(new TranslationTextComponent("gui.blockrenderer.selected_dimensions", round(GuiEnterModId.this.sliderValue)));
        }

        private double denormalizeValue() {
            return MathHelper.clamp(MathHelper.lerp(MathHelper.clamp(sliderValue, 0.0D, 1.0D), minValue, maxValue), minValue, maxValue);
        }

        @Override
        protected void func_230972_a_() {
            GuiEnterModId.this.sliderValue = denormalizeValue();
        }

        protected void updateSliderMax(double maxValue) {
            double value = denormalizeValue();
            this.maxValue = maxValue;
            sliderValue = normalizeValue(value, minValue, this.maxValue);
            func_230972_a_();
            func_230979_b_();
        }
    }
}

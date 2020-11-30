package com.unascribed.blockrenderer;

import com.google.common.base.Strings;
import com.mojang.blaze3d.matrix.MatrixStack;
import javax.annotation.Nonnull;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.AbstractSlider;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

public class GuiEnterModId extends Screen {

    private String prefill;
    private TextFieldWidget text;
    private Slider size;
    private Screen old;

    public GuiEnterModId(Screen old, String prefill) {
        super(StringTextComponent.EMPTY);
        this.old = old;
        this.prefill = Strings.nullToEmpty(prefill);
    }

    @Override
    public void init() {
        minecraft.keyboardListener.enableRepeatEvents(true);
        String oldText = (text == null ? prefill : text.getText());

        double oldSize = (size == null ? 512 : size.getSliderValue());

        text = new TextFieldWidget(font, width / 2 - 100, height / 6 + 50, 200, 20, StringTextComponent.EMPTY);
        text.setText(oldText);

        addButton(new Button(width / 2 - 100, height / 6 + 120, 98, 20, new TranslationTextComponent("gui.cancel"),
              button -> minecraft.displayGuiScreen(old)));

        Button render = addButton(new Button(width / 2 + 2, height / 6 + 120, 98, 20, new TranslationTextComponent("gui.render"), button -> {
            if (minecraft.world != null) {
                BlockRenderer.inst.pendingBulkRender = text.getText();
                BlockRenderer.inst.pendingBulkRenderSize = round(size.getSliderValue());
            }
            minecraft.displayGuiScreen(old);
        }));
        int minSize = Math.min(minecraft.getMainWindow().getWidth(), minecraft.getMainWindow().getHeight());
        //public GuiSlider(GuiPageButtonList.GuiResponder guiResponder, int idIn, int x, int y, String nameIn, float minIn, float maxIn, float defaultValue,
        // GuiSlider.FormatHelper formatter)
        size = addButton(new Slider(width / 2 - 100, height / 6 + 80, 16, Math.min(2048, minSize), new TranslationTextComponent("gui.rendersize"),
              Math.min(oldSize, minSize)));

        size.setWidth(200);

        text.setFocused2(true);
        text.setCanLoseFocus(false);
        boolean enabled = minecraft.world != null;
        render.active = enabled;
        text.setEnabled(enabled);
        size.active = enabled;
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
        drawCenteredString(matrix, minecraft.fontRenderer, I18n.format("gui.entermodid"), width / 2, height / 6, -1);
        if (minecraft.world == null) {
            drawCenteredString(matrix, minecraft.fontRenderer, I18n.format("gui.noworld"), width / 2, height / 6 + 30, 0xFF5555);
        } else {
            int displayWidth = minecraft.getMainWindow().getWidth();
            int displayHeight = minecraft.getMainWindow().getHeight();
            boolean widthCap = (displayWidth < 2048);
            boolean heightCap = (displayHeight < 2048);
            String str = null;
            if (widthCap && heightCap) {
                if (displayWidth > displayHeight) {
                    str = "gui.cappedheight";
                } else if (displayWidth == displayHeight) {
                    str = "gui.cappedboth";
                } else { //displayHeight > displayWidth
                    str = "gui.cappedwidth";
                }
            } else if (widthCap) {
                str = "gui.cappedwidth";
            } else if (heightCap) {
                str = "gui.cappedheight";
            }
            if (str != null) {
                drawCenteredString(matrix, minecraft.fontRenderer, I18n.format(str, Math.min(displayHeight, displayWidth)), width / 2, height / 6 + 104, 0xFFFFFF);
            }
        }
        text.renderButton(matrix, mouseX, mouseY, partialTicks);
    }

    @Override
    public void tick() {
        super.tick();
        text.tick();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        //TODO: Re-evaluate
        if (text.canWrite()) {
            return text.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int keyCode) {
        //TODO: Re-evaluate
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

    //TODO: Re-evaluate
    /*@Override
    public void setEntryValue(int id, float value) {
        size.setSliderValue(round(value), false);
    }

    @Override
    public void setEntryValue(int id, boolean value) {
    }

    @Override
    public void setEntryValue(int id, String value) {
    }*/

    private class Slider extends AbstractSlider {

        public Slider(int x, int y, int width, int height, ITextComponent message, double defaultValue) {
            super(x, y, width, height, message, defaultValue);
        }

        @Override
        protected void func_230979_b_() {
            String px = Integer.toString(round(sliderValue));
            setMessage(getTitle().deepCopy().append(new StringTextComponent(": " + px + "x" + px)));
        }

        @Override
        protected void func_230972_a_() {
            //TODO: This seems to be some sort of set
        }

        public double getSliderValue() {
            return sliderValue;
        }
    }
}

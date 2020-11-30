package com.unascribed.blockrenderer;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.fml.client.gui.GuiUtils;

/**
 * Static versions of Gui and Screen utility methods.
 */
public class Rendering {

    private static final Screen GUI = new Screen(StringTextComponent.EMPTY) {};

    public static void drawHoveringText(MatrixStack matrix, List<ITextComponent> textLines, int x, int y, FontRenderer font) {
        GuiUtils.drawHoveringText(matrix, textLines, x, y, GUI.width, GUI.height, -1, font);
    }

    public static void drawBackground(MatrixStack matrix, int width, int height) {
        GUI.resize(Minecraft.getInstance(), width, height);
        GUI.renderBackground(matrix);
    }

    private Rendering() {
    }
}

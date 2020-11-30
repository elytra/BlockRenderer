package com.unascribed.blockrenderer;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.StringTextComponent;

/**
 * Static versions of Gui and Screen utility methods.
 */
public class Rendering {

    private static final Screen GUI = new Screen(StringTextComponent.EMPTY) {};

    public static void drawBackground(MatrixStack matrix, int width, int height) {
        GUI.resize(Minecraft.getInstance(), width, height);
        GUI.renderBackground(matrix);
    }

    private Rendering() {
    }
}

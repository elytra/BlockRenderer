package com.unascribed.blockrenderer;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LoadingGui;
import net.minecraft.resources.IAsyncReloader;
import net.minecraft.util.ColorHelper;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;

public class ProgressBarGui extends LoadingGui {

    private static final int field_238627_b_ = ColorHelper.PackedColor.packColor(255, 239, 50, 61);
    private static final int field_238628_c_ = field_238627_b_ & 16777215;
    private final Minecraft mc;
    private final int total;
    private final String joined;
    private ITextComponent title;
    private ITextComponent subTitle = StringTextComponent.EMPTY;
    private IAsyncReloader asyncReloader;
    private float progress;
    private long fadeOutStart = -1L;
    private long fadeInStart = -1L;
    private int rendered;

    public ProgressBarGui(Minecraft mc, int total, String joined) {
        this.mc = mc;
        this.total = total;
        this.joined = joined;
        this.title = new TranslationTextComponent("gui.rendering", this.total, this.joined);
    }

    public void setFutures(List<CompletableFuture<Void>> futures) {
        this.asyncReloader = new AsyncItemRenderer(futures);
    }

    public void update() {
        rendered++;
        int remaining = total - rendered;
        if (remaining > 0) {
            subTitle = new TranslationTextComponent("gui.progress", rendered, total, remaining);
        } else {
            title = new TranslationTextComponent("gui.rendered", this.total, this.joined);
            subTitle = StringTextComponent.EMPTY;
        }
    }

    @Override
    public void render(@Nonnull MatrixStack matrix, int mouseX, int mouseY, float partialTicks) {
        int scaledWidth = mc.getMainWindow().getScaledWidth();
        int scaledHeight = mc.getMainWindow().getScaledHeight();
        long k = Util.milliTime();
        if ((asyncReloader.asyncPartDone() || mc.currentScreen != null) && fadeInStart == -1L) {
            fadeInStart = k;
        }
        float f = fadeOutStart > -1L ? (float) (k - fadeOutStart) / 1000.0F : -1.0F;
        float f1 = fadeInStart > -1L ? (float) (k - fadeInStart) / 500.0F : -1.0F;
        if (f >= 1.0F) {
            if (mc.currentScreen != null) {
                mc.currentScreen.render(matrix, 0, 0, partialTicks);
            }
            int l = MathHelper.ceil((1.0F - MathHelper.clamp(f - 1.0F, 0.0F, 1.0F)) * 255.0F);
            fill(matrix, 0, 0, scaledWidth, scaledHeight, field_238628_c_ | l << 24);
        } else {
            if (mc.currentScreen != null && f1 < 1.0F) {
                mc.currentScreen.render(matrix, mouseX, mouseY, partialTicks);
            }
            int i2 = MathHelper.ceil(MathHelper.clamp(f1, 0.15D, 1.0D) * 255.0D);
            fill(matrix, 0, 0, scaledWidth, scaledHeight, field_238628_c_ | i2 << 24);
        }

        double d0 = Math.min(scaledWidth * 0.75D, scaledHeight) * 0.25D;
        double d1 = d0 * 4.0D;
        int k1 = (int) (d1 * 0.5D);
        int l1 = (int) (scaledHeight * 0.8325D);
        progress = MathHelper.clamp(progress * 0.95F + asyncReloader.estimateExecutionSpeed() * 0.05F, 0.0F, 1.0F);
        renderProgressText(matrix, scaledWidth, scaledHeight);
        if (f < 1.0F) {
            drawProgressBar(matrix, scaledWidth / 2 - k1, l1 - 5, scaledWidth / 2 + k1, l1 + 5, 1.0F - MathHelper.clamp(f, 0.0F, 1.0F));
        } else if (f >= 2.0F) {
            this.mc.setLoadingGui(null);
        }
        if (fadeOutStart == -1L && asyncReloader.fullyDone() && f1 >= 2.0F) {
            fadeOutStart = Util.milliTime();
            try {
                asyncReloader.join();
            } catch (Throwable throwable) {
                BlockRenderer.log.warn("Error joining reloader", throwable);
            }
            if (mc.currentScreen != null) {
                mc.currentScreen.init(mc, scaledWidth, scaledHeight);
            }
        }
    }

    private void drawProgressBar(MatrixStack matrix, int xStart, int yStart, int xEnd, int yEnd, float alpha) {
        int i = MathHelper.ceil((float) (xEnd - xStart - 2) * this.progress);
        int j = Math.round(alpha * 255.0F);
        int k = ColorHelper.PackedColor.packColor(j, 255, 255, 255);
        fill(matrix, xStart + 1, yStart, xEnd - 1, yStart + 1, k);
        fill(matrix, xStart + 1, yEnd, xEnd - 1, yEnd - 1, k);
        fill(matrix, xStart, yStart, xStart + 1, yEnd, k);
        fill(matrix, xEnd, yStart, xEnd - 1, yEnd, k);
        fill(matrix, xStart + 2, yStart + 2, xStart + i, yEnd - 2, k);
    }

    private void renderProgressText(MatrixStack matrix, int scaledWidth, int scaledHeight) {
        matrix.push();
        matrix.scale(2, 2, 0);
        drawCenteredString(matrix, mc.fontRenderer, title, scaledWidth / 4, scaledHeight / 4 - 24, 0xFFFFFFFF);
        matrix.scale(0.5F, 0.5F, 0);
        drawCenteredString(matrix, mc.fontRenderer, subTitle, scaledWidth / 2, scaledHeight / 2 - 20, 0xFFFFFFFF);
        matrix.pop();
    }
}
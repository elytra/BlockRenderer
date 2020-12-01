package com.unascribed.blockrenderer;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LoadingGui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ColorHelper;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;

public class ProgressBarGui extends LoadingGui {

    private static final int field_238627_b_ = ColorHelper.PackedColor.packColor(255, 239, 50, 61);
    private static final int field_238628_c_ = field_238627_b_ & 16777215;
    private final Minecraft mc;
    private final int total;
    private final String joined;
    private ITextComponent title;
    private List<ITextComponent> subTitles = Collections.emptyList();
    private AsyncItemRenderer asyncReloader;
    private float progress;
    private long fadeOutStart = -1;
    private long fadeInStart = -1;
    private long start = -1;
    private int rendered;
    private ItemStack stack = ItemStack.EMPTY;
    private boolean canceled;

    public ProgressBarGui(Minecraft mc, int total, String joined) {
        this.mc = mc;
        this.total = total;
        this.joined = joined;
        this.title = new TranslationTextComponent("gui.blockrenderer.rendering", this.total, this.joined);
    }

    public void setFutures(List<CompletableFuture<Void>> futures) {
        this.asyncReloader = new AsyncItemRenderer(futures);
    }

    public void cancel() {
        if (!canceled) {
            canceled = true;
            title = new TranslationTextComponent("gui.blockrenderer.render_cancelled", rendered, total, total - rendered);
            subTitles = Collections.emptyList();
            stack = ItemStack.EMPTY;
            asyncReloader.cancel();
        }
    }

    public void update(ItemStack stack) {
        rendered++;
        int remaining = total - rendered;
        if (remaining > 0) {
            if (canceled) {
                //If canceled just update how much actually has been rendered so far
                title = new TranslationTextComponent("gui.blockrenderer.render_cancelled", rendered, total, remaining);
            } else {
                //Otherwise update the subtitles and the like
                subTitles = new ArrayList<>();
                subTitles.add(new TranslationTextComponent("gui.blockrenderer.progress", rendered, total, remaining));
                subTitles.add(stack.getDisplayName().deepCopy().mergeStyle(stack.getRarity().color));
                this.stack = stack;
            }
        } else {
            title = new TranslationTextComponent("gui.blockrenderer.rendered", total, joined);
            subTitles = Collections.emptyList();
            this.stack = ItemStack.EMPTY;
        }
    }

    @Override
    public void render(@Nonnull MatrixStack matrix, int mouseX, int mouseY, float partialTicks) {
        int scaledWidth = mc.getMainWindow().getScaledWidth();
        int scaledHeight = mc.getMainWindow().getScaledHeight();
        long k = Util.milliTime();
        if (start == -1) {
            start = k;
        }
        if ((asyncReloader.asyncPartDone() || mc.currentScreen != null) && fadeInStart == -1) {
            fadeInStart = k;
        }
        float f = fadeOutStart > -1 ? (k - fadeOutStart) / 1000F : -1;
        float f1 = fadeInStart > -1 ? (k - fadeInStart) / 500F : -1;
        if (f >= 1) {
            if (mc.currentScreen != null) {
                mc.currentScreen.render(matrix, 0, 0, partialTicks);
            }
            int l = MathHelper.ceil((1 - MathHelper.clamp(f - 1, 0, 1)) * 255);
            fill(matrix, 0, 0, scaledWidth, scaledHeight, field_238628_c_ | l << 24);
        } else {
            if (mc.currentScreen != null && f1 < 1) {
                mc.currentScreen.render(matrix, mouseX, mouseY, partialTicks);
            }
            int i2 = MathHelper.ceil(MathHelper.clamp(f1, 0.15, 1) * 255);
            fill(matrix, 0, 0, scaledWidth, scaledHeight, field_238628_c_ | i2 << 24);
        }

        double d0 = Math.min(scaledWidth * 0.75, scaledHeight) * 0.25;
        double d1 = d0 * 4;
        int k1 = (int) (d1 * 0.5);
        int l1 = (int) (scaledHeight * 0.8325);
        progress = MathHelper.clamp(progress * 0.95F + asyncReloader.estimateExecutionSpeed() * 0.05F, 0, 1);
        renderProgressText(matrix, scaledWidth, scaledHeight);
        if (f < 1) {
            drawProgressBar(matrix, scaledWidth / 2 - k1, l1 - 5, scaledWidth / 2 + k1, l1 + 5, 1 - MathHelper.clamp(f, 0, 1));
        } else if (f >= 2) {
            this.mc.setLoadingGui(null);
        }
        if (fadeOutStart == -1 && asyncReloader.fullyDone() && f1 >= 2) {
            fadeOutStart = Util.milliTime();
            try {
                asyncReloader.join();
                mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.blockrenderer.rendered", total, joined, k - start));
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
        int j = Math.round(alpha * 255);
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
        int subTitleCount = subTitles.size();
        if (subTitleCount > 0) {
            matrix.scale(0.5F, 0.5F, 0);
            int subTitleX = scaledWidth / 2;
            int subTitleY = scaledHeight / 2;
            for (int i = 0; i < subTitleCount; i++) {
                drawCenteredString(matrix, mc.fontRenderer, subTitles.get(i), subTitleX, subTitleY + 20 * (i - 1), 0xFFFFFFFF);
            }
            mc.getItemRenderer().renderItemAndEffectIntoGUI(stack, subTitleX - 8, subTitleY + 20 * (subTitleCount - 1));
        }
        matrix.pop();
    }
}
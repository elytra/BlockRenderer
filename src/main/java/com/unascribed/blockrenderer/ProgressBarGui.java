package com.unascribed.blockrenderer;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.LoadingGui;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.resources.IAsyncReloader;
import net.minecraft.util.ColorHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.gui.GuiUtils;
import org.lwjgl.opengl.GL11;

public class ProgressBarGui extends LoadingGui {

    private static final ResourceLocation MOJANG_LOGO_TEXTURE = new ResourceLocation("textures/gui/title/mojangstudios.png");
    private static final int field_238627_b_ = ColorHelper.PackedColor.packColor(255, 239, 50, 61);
    private static final int field_238628_c_ = field_238627_b_ & 16777215;
    private final Minecraft mc;
    private final IAsyncReloader asyncReloader;
    private float progress;
    private long fadeOutStart = -1L;
    private long fadeInStart = -1L;

    public ProgressBarGui(Minecraft mc, List<CompletableFuture<File>> futures) {
        this.mc = mc;
        this.asyncReloader = new AsyncItemRenderer(futures);
    }

    @Override
    public void render(@Nonnull MatrixStack matrix, int mouseX, int mouseY, float partialTicks) {
        int scaledWidth = mc.getMainWindow().getScaledWidth();
        int scaledHeight = mc.getMainWindow().getScaledHeight();
        long k = Util.milliTime();
        if ((this.asyncReloader.asyncPartDone() || this.mc.currentScreen != null) && this.fadeInStart == -1L) {
            this.fadeInStart = k;
        }

        float f = this.fadeOutStart > -1L ? (float) (k - this.fadeOutStart) / 1000.0F : -1.0F;
        float f1 = this.fadeInStart > -1L ? (float) (k - this.fadeInStart) / 500.0F : -1.0F;
        float f2;
        if (f >= 1.0F) {
            if (this.mc.currentScreen != null) {
                this.mc.currentScreen.render(matrix, 0, 0, partialTicks);
            }
            int l = MathHelper.ceil((1.0F - MathHelper.clamp(f - 1.0F, 0.0F, 1.0F)) * 255.0F);
            fill(matrix, 0, 0, scaledWidth, scaledHeight, field_238628_c_ | l << 24);
            f2 = 1.0F - MathHelper.clamp(f - 1.0F, 0.0F, 1.0F);
        } else {
            if (this.mc.currentScreen != null && f1 < 1.0F) {
                this.mc.currentScreen.render(matrix, mouseX, mouseY, partialTicks);
            }

            int i2 = MathHelper.ceil(MathHelper.clamp((double) f1, 0.15D, 1.0D) * 255.0D);
            fill(matrix, 0, 0, scaledWidth, scaledHeight, field_238628_c_ | i2 << 24);
            f2 = MathHelper.clamp(f1, 0.0F, 1.0F);
        }/* else {
            fill(matrix, 0, 0, scaledWidth, scaledHeight, field_238627_b_);
            f2 = 1.0F;
        }*/

        int j2 = (int) (scaledWidth * 0.5D);
        int i1 = (int) (scaledHeight * 0.5D);
        double d0 = Math.min(scaledWidth * 0.75D, scaledHeight) * 0.25D;
        int j1 = (int) (d0 * 0.5D);
        double d1 = d0 * 4.0D;
        int k1 = (int) (d1 * 0.5D);
        mc.getTextureManager().bindTexture(MOJANG_LOGO_TEXTURE);
        RenderSystem.enableBlend();
        RenderSystem.blendEquation(0x8006);
        RenderSystem.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
        RenderSystem.alphaFunc(GL11.GL_GREATER, 0);
        RenderSystem.color4f(1, 1, 1, f2);
        blit(matrix, j2 - k1, i1 - j1, k1, (int) d0, -0.0625F, 0.0F, 120, 60, 120, 120);
        blit(matrix, j2, i1 - j1, k1, (int) d0, 0.0625F, 60.0F, 120, 60, 120, 120);
        RenderSystem.defaultBlendFunc();
        RenderSystem.defaultAlphaFunc();
        RenderSystem.disableBlend();
        int l1 = (int) (scaledHeight * 0.8325D);
        float estimatedSpeed = this.asyncReloader.estimateExecutionSpeed();
        this.progress = MathHelper.clamp(this.progress * 0.95F + estimatedSpeed * 0.05F, 0.0F, 1.0F);
        renderProgressText(matrix, scaledWidth, scaledHeight);//net.minecraftforge.fml.client.ClientModLoader.renderProgressText();
        if (f < 1.0F) {
            func_238629_a_(matrix, scaledWidth / 2 - k1, l1 - 5, scaledWidth / 2 + k1, l1 + 5, 1.0F - MathHelper.clamp(f, 0.0F, 1.0F));
        }

        if (f >= 2.0F) {
            this.mc.setLoadingGui(null);
        }

        if (this.fadeOutStart == -1L && this.asyncReloader.fullyDone() && f1 >= 2.0F) {
            this.fadeOutStart = Util.milliTime();
            try {
                this.asyncReloader.join();
            } catch (Throwable throwable) {
                BlockRenderer.inst.log.warn("Error joining reloader", throwable);
            }
            if (mc.currentScreen != null) {
                mc.currentScreen.init(mc, scaledWidth, scaledHeight);
            }
        }
    }

    private void func_238629_a_(MatrixStack matrix, int p_238629_2_, int p_238629_3_, int p_238629_4_, int p_238629_5_, float p_238629_6_) {
        int i = MathHelper.ceil((float) (p_238629_4_ - p_238629_2_ - 2) * this.progress);
        int j = Math.round(p_238629_6_ * 255.0F);
        int k = ColorHelper.PackedColor.packColor(j, 255, 255, 255);
        fill(matrix, p_238629_2_ + 1, p_238629_3_, p_238629_4_ - 1, p_238629_3_ + 1, k);
        fill(matrix, p_238629_2_ + 1, p_238629_5_, p_238629_4_ - 1, p_238629_5_ - 1, k);
        fill(matrix, p_238629_2_, p_238629_3_, p_238629_2_ + 1, p_238629_5_, k);
        fill(matrix, p_238629_4_, p_238629_3_, p_238629_4_ - 1, p_238629_5_, k);
        fill(matrix, p_238629_2_ + 2, p_238629_3_ + 2, p_238629_2_ + i, p_238629_5_ - 2, k);
    }

    private void renderProgressText(MatrixStack matrix, int scaledWidth, int scaledHeight) {
        //drawCenteredString(matrix, mc.fontRenderer, title, scaledWidth / 2, scaledHeight / 2 - 24, -1);
        //TODO: Implement
        /*List<Pair<Integer, Message>> messages = StartupMessageManager.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            boolean nofade = i == 0;
            final Pair<Integer, StartupMessageManager.Message> pair = messages.get(i);
            final float fade = MathHelper.clamp((4000.0f - (float) pair.getLeft() - ( i - 4 ) * 1000.0f) / 5000.0f, 0.0f, 1.0f);
            if (fade <0.01f && !nofade) continue;
            StartupMessageManager.Message msg = pair.getRight();
            renderMessage(msg.getText(), msg.getTypeColour(), ((window.getScaledHeight() - 15) / 10) - i + 1, nofade ? 1.0f : fade);
        }
        renderMemoryInfo();*/
    }

    //TODO: FIXME, this doesn't seem to ever actually render, and throws a bunch of GL_INVALID_OPERATION warnings
    //TODO: Switch this to using LoadingGui, instead of the dirt background, might be easier to get working again
    private void renderLoading(MatrixStack matrix, ITextComponent title, ITextComponent subtitle, @Nonnull ItemStack is, float progress) {
        Minecraft mc = Minecraft.getInstance();
        mc.getFramebuffer().unbindFramebuffer();
        matrix.push();
        BlockRenderer.setupOverlayRendering();
        int scaledWidth = mc.getMainWindow().getScaledWidth();
        int scaledHeight = mc.getMainWindow().getScaledHeight();
        // Draw the dirt background and status text...
        Rendering.drawBackground(matrix, scaledWidth, scaledHeight);
        AbstractGui.drawCenteredString(matrix, mc.fontRenderer, title, scaledWidth / 2, scaledHeight / 2 - 24, -1);
        AbstractGui.fill(matrix, scaledWidth / 2 - 50, scaledHeight / 2 - 1, scaledWidth / 2 + 50, scaledHeight / 2 + 1, 0xFF001100);
        AbstractGui.fill(matrix, scaledWidth / 2 - 50, scaledHeight / 2 - 1, (scaledWidth / 2 - 50) + (int) (progress * 100), scaledHeight / 2 + 1, 0xFF55FF55);
        matrix.push();
        matrix.scale(0.5f, 0.5f, 1);
        AbstractGui.drawCenteredString(matrix, mc.fontRenderer, subtitle, scaledWidth, scaledHeight - 20, -1);
        // ...and draw the tooltip.
        if (!is.isEmpty()) {
            try {
                List<ITextComponent> list = is.getTooltip(mc.player, ITooltipFlag.TooltipFlags.NORMAL);

                // This code is copied from the tooltip renderer, so we can properly center it.
                for (int i = 0; i < list.size(); ++i) {
                    if (i == 0) {
                        list.set(i, list.get(i).deepCopy().mergeStyle(is.getRarity().color));
                    } else {
                        list.set(i, list.get(i).deepCopy().mergeStyle(TextFormatting.GRAY));
                    }
                }

                FontRenderer font = is.getItem().getFontRenderer(is);
                if (font == null) {
                    font = mc.fontRenderer;
                }
                int width = 0;

                for (ITextComponent s : list) {
                    int j = font.getStringPropertyWidth(s);

                    if (j > width) {
                        width = j;
                    }
                }
                // End copied code.
                matrix.translate((scaledWidth - width / 2) - 12, scaledHeight + 30, 0);
                GuiUtils.drawHoveringText(matrix, list, 0, 0, scaledWidth, scaledHeight, -1, font);
            } catch (Throwable t) {
            }
        }
        matrix.pop();
        matrix.pop();
        mc.getMainWindow().update();

        /*
         * While OpenGL itself is double-buffered, Minecraft is actually *triple*-buffered.
         * This is to allow shaders to work, as shaders are only available in "modern" GL.
         * Minecraft uses "legacy" GL, so it renders using a separate GL context to this
         * third buffer, which is then flipped to the back buffer with this call.
         */
        mc.getFramebuffer().bindFramebuffer(false);
    }
}
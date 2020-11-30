package com.unascribed.blockrenderer;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.util.InputMappings;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.RenderTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.client.gui.GuiUtils;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

@Mod(BlockRenderer.MODID)
public class BlockRenderer {

    public static final String MODID = "blockrenderer";

    public static BlockRenderer inst;

    protected KeyBinding bind;
    protected boolean down = false;
    protected static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
    protected String pendingBulkRender;
    protected int pendingBulkRenderSize;

    protected final Logger log = LogManager.getLogger("BlockRenderer");

    public BlockRenderer() {
        inst = this;
        bind = new KeyBinding("key.render", GLFW.GLFW_KEY_GRAVE_ACCENT, "key.categories.blockrenderer");
        ClientRegistry.registerKeyBinding(bind);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onFrameStart);
    }

    private static boolean isKeyDown(KeyBinding keyBinding) {
        InputMappings.Input key = keyBinding.getKey();
        int keyCode = key.getKeyCode();
        if (keyCode != InputMappings.INPUT_INVALID.getKeyCode()) {
            long windowHandle = Minecraft.getInstance().getMainWindow().getHandle();
            try {
                if (key.getType() == InputMappings.Type.KEYSYM) {
                    return InputMappings.isKeyDown(windowHandle, keyCode);
                } else if (key.getType() == InputMappings.Type.MOUSE) {
                    return GLFW.glfwGetMouseButton(windowHandle, keyCode) == GLFW.GLFW_PRESS;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    public void onFrameStart(RenderTickEvent e) {
        /**
         * Quick primer: OpenGL is double-buffered. This means, where we draw to is
         * /not/ on the screen. As such, we are free to do whatever we like before
         * Minecraft renders, as long as we put everything back the way it was.
         */
        if (e.phase == Phase.START) {
            MatrixStack matrix = new MatrixStack();
            if (pendingBulkRender != null) {
                // We *must* call render code in pre-render. If we don't, it won't work right.
                bulkRender(matrix, pendingBulkRender, pendingBulkRenderSize);
                pendingBulkRender = null;
            }
            if (isKeyDown(bind)) {
                if (!down) {
                    down = true;
                    Minecraft mc = Minecraft.getInstance();
                    Slot hovered = null;
                    Screen currentScreen = mc.currentScreen;
                    if (currentScreen instanceof ContainerScreen) {
                        hovered = ((ContainerScreen<?>) currentScreen).getSlotUnderMouse();
                    }

                    if (Screen.hasControlDown()) {
                        String modId = null;
                        if (hovered != null && hovered.getHasStack()) {
                            modId = hovered.getStack().getItem().getRegistryName().getNamespace();
                        }
                        mc.displayGuiScreen(new GuiEnterModId(mc.currentScreen, modId));
                    } else if (currentScreen instanceof ContainerScreen) {
                        if (hovered == null) {
                            mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.slot.absent"));
                        } else {
                            ItemStack is = hovered.getStack();
                            if (is.isEmpty()) {
                                mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.slot.empty"));
                            } else {
                                int size = 512;
                                if (Screen.hasShiftDown()) {
                                    size = (int) (16 * mc.getMainWindow().getGuiScaleFactor());
                                }
                                setUpRenderState(matrix, size);
                                mc.ingameGUI.getChatGUI().printChatMessage(render(matrix, is, new File("renders"), true));
                                tearDownRenderState(matrix);
                            }
                        }
                    } else {
                        mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.notcontainer"));
                    }
                }
            } else {
                down = false;
            }
        }
    }

    private void bulkRender(MatrixStack matrix, String modidSpec, int size) {
        Minecraft.getInstance().displayGuiScreen(new IngameMenuScreen(true));
        Set<String> modIds = Sets.newHashSet();
        for (String str : modidSpec.split(",")) {
            modIds.add(str.trim());
        }
        List<ItemStack> toRender = Lists.newArrayList();
        NonNullList<ItemStack> li = NonNullList.create();
        int rendered = 0;
        boolean wildcard = modIds.contains("*");
        for (Entry<RegistryKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            if (wildcard || modIds.contains(entry.getKey().getLocation().getNamespace())) {
                li.clear();
                Item item = entry.getValue();
                ItemGroup group = item.getGroup();
                try {
                    item.fillItemGroup(group, li);
                } catch (Throwable t) {
                    log.warn("Failed to get renderable items for {} and group {}", entry.getKey().getLocation(), group, t);
                }
                toRender.addAll(li);
            }
        }
        File folder = new File("renders/" + dateFormat.format(new Date()) + "_" + sanitize(modidSpec) + "/");
        long lastUpdate = 0;
        String joined = Joiner.on(", ").join(modIds);
        setUpRenderState(matrix, size);
        for (ItemStack is : toRender) {
            if (InputMappings.isKeyDown(Minecraft.getInstance().getMainWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE)) {
                break;
            }
            render(matrix, is, folder, false);
            rendered++;
            if (System.currentTimeMillis() - lastUpdate > 33) {
                tearDownRenderState(matrix);
                renderLoading(matrix, new TranslationTextComponent("gui.rendering", toRender.size(), joined),
                      new TranslationTextComponent("gui.progress", rendered, toRender.size(), (toRender.size() - rendered)), is,
                      (float) rendered / toRender.size());
                lastUpdate = System.currentTimeMillis();
                setUpRenderState(matrix, size);
            }
        }
        if (rendered >= toRender.size()) {
            renderLoading(matrix, new TranslationTextComponent("gui.rendered", toRender.size(), Joiner.on(", ").join(modIds)), StringTextComponent.EMPTY,
                  ItemStack.EMPTY, 1);
        } else {
            renderLoading(matrix, new TranslationTextComponent("gui.renderCancelled"), new TranslationTextComponent("gui.progress", rendered,
                        toRender.size(), (toRender.size() - rendered)),
                  ItemStack.EMPTY, (float) rendered / toRender.size());
        }
        tearDownRenderState(matrix);
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
        }
    }

    private static void setupOverlayRendering() {
        RenderSystem.clear(256, true);
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.loadIdentity();
        RenderSystem.ortho(0.0D, Minecraft.getInstance().getMainWindow().getScaledWidth(), Minecraft.getInstance().getMainWindow().getScaledHeight(),
              0.0D, 1000.0D, 3000.0D);
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.loadIdentity();
        RenderSystem.translatef(0.0F, 0.0F, -2000.0F);
    }

    //TODO: FIXME, this doesn't seem to ever actually render, and throws a bunch of GL_INVALID_OPERATION warnings
    //TODO: Switch this to using LoadingGui, instead of the dirt background, might be easier to get working again
    private void renderLoading(MatrixStack matrix, ITextComponent title, ITextComponent subtitle, @Nonnull ItemStack is, float progress) {
        Minecraft mc = Minecraft.getInstance();
        mc.getFramebuffer().unbindFramebuffer();
        matrix.push();
        setupOverlayRendering();
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

    private ITextComponent render(MatrixStack matrix, ItemStack is, File folder, boolean includeDateInFilename) {
        Minecraft mc = Minecraft.getInstance();
        String filename = (includeDateInFilename ? dateFormat.format(new Date()) + "_" : "") + sanitize(is.getDisplayName().getString());
        RenderSystem.pushMatrix();
        RenderSystem.multMatrix(matrix.getLast().getMatrix());
        RenderSystem.clearColor(0, 0, 0, 0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
        mc.getItemRenderer().renderItemAndEffectIntoGUI(is, 0, 0);
        RenderSystem.popMatrix();
        try {
            /*
             * We need to flip the image over here, because again, GL Y-zero is
             * the bottom, so it's "Y-up". Minecraft's Y-zero is the top, so it's
             * "Y-down". Since readPixels is Y-up, our Y-down render is flipped.
             * It's easier to do this operation on the resulting image than to
             * do it with GL transforms. Not faster, just easier.
             */
            BufferedImage img = createFlipped(readPixels(size, size));

            File f = new File(folder, filename + ".png");
            int i = 2;
            while (f.exists()) {
                f = new File(folder, filename + "_" + i + ".png");
                i++;
            }
            Files.createParentDirs(f);
            f.createNewFile();
            ImageIO.write(img, "PNG", f);
            return new TranslationTextComponent("msg.render.success", f.getPath());
        } catch (Exception ex) {
            ex.printStackTrace();
            return new TranslationTextComponent("msg.render.fail");
        }
    }

    private int size;
    private float oldZLevel;

    private void setUpRenderState(MatrixStack matrix, int desiredSize) {
        matrix.push();
        Minecraft mc = Minecraft.getInstance();
        /*
         * As we render to the back-buffer, we need to cap our render size
         * to be within the window's bounds. If we didn't do this, the results
         * of our readPixels up ahead would be undefined. And nobody likes
         * undefined behavior.
         */
        size = Math.min(Math.min(mc.getMainWindow().getHeight(), mc.getMainWindow().getWidth()), desiredSize);

        // Switches from 3D to 2D
        setupOverlayRendering();
        RenderHelper.enableStandardItemLighting();
        /*
         * The GUI scale affects us due to the call to setupOverlayRendering
         * above. As such, we need to counteract this to always get a 512x512
         * render. We could manually switch to orthogonal mode, but it's just
         * more convenient to leverage setupOverlayRendering.
         */
        float scale = (float) (size / (16 * mc.getMainWindow().getGuiScaleFactor()));
        matrix.translate(0, 0, -(scale * 100));

        matrix.scale(scale, scale, scale);

        oldZLevel = mc.getItemRenderer().zLevel;
        mc.getItemRenderer().zLevel = -50;

        RenderSystem.enableRescaleNormal();
        RenderSystem.enableColorMaterial();
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.SRC_ALPHA, DestFactor.ONE);
        RenderSystem.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.disableAlphaTest();
    }

    private void tearDownRenderState(MatrixStack matrix) {
        RenderSystem.disableLighting();
        RenderSystem.disableColorMaterial();
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();

        Minecraft.getInstance().getItemRenderer().zLevel = oldZLevel;
        matrix.pop();
    }

    private String sanitize(String str) {
        return str.replaceAll("[^A-Za-z0-9-_ ]", "_");
    }

    public BufferedImage readPixels(int width, int height) throws InterruptedException {
        /*
         * Make sure we're reading from the back buffer, not the front buffer.
         * The front buffer is what is currently on-screen, and is useful for
         * screenshots.
         */
        GL11.glReadBuffer(GL11.GL_BACK);
        // Allocate a native data array to fit our pixels
        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
        // And finally read the pixel data from the GPU...
        GL11.glReadPixels(0, Minecraft.getInstance().getMainWindow().getHeight() - height, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        // ...and turn it into a Java object we can do things to.
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        buf.asIntBuffer().get(pixels);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private static BufferedImage createFlipped(BufferedImage image) {
        AffineTransform at = new AffineTransform();
        /*
         * Creates a compound affine transform, instead of just one, as we need
         * to perform two transformations.
         *
         * The first one is to scale the image to 100% width, and -100% height.
         * (That's *negative* 100%.)
         */
        at.concatenate(AffineTransform.getScaleInstance(1, -1));
        /**
         * We then need to translate the image back up by it's height, as flipping
         * it over moves it off the bottom of the canvas.
         */
        at.concatenate(AffineTransform.getTranslateInstance(0, -image.getHeight()));
        return createTransformed(image, at);
    }

    private static BufferedImage createTransformed(BufferedImage image, AffineTransform at) {
        // Create a blank image with the same dimensions as the old one...
        BufferedImage newImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        // ...get it's renderer...
        Graphics2D g = newImage.createGraphics();
        /// ...and draw the old image on top of it with our transform.
        g.transform(at);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return newImage;
    }
}

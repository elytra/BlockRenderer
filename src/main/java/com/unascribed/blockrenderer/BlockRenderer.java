package com.unascribed.blockrenderer;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.RenderTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
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
            if (pendingBulkRender != null) {
                // We *must* call render code in pre-render. If we don't, it won't work right.
                bulkRender(pendingBulkRender, pendingBulkRenderSize);
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
                            ItemStack stack = hovered.getStack();
                            if (stack.isEmpty()) {
                                mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.slot.empty"));
                            } else {
                                int size = 512;
                                if (Screen.hasShiftDown()) {
                                    size = (int) (16 * mc.getMainWindow().getGuiScaleFactor());
                                }
                                setUpRenderState(mc, size);
                                RenderSystem.clearColor(0, 0, 0, 0);
                                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
                                mc.getItemRenderer().renderItemAndEffectIntoGUI(stack, 0, 0);
                                try {
                                    File f = createSavingFuture(readPixels(size, size), stack, new File("renders"), true).get();
                                    mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.render.success", f.getPath()));
                                } catch (InterruptedException | ExecutionException ex) {
                                    ex.printStackTrace();
                                    mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.render.fail"));
                                }
                                tearDownRenderState();
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

    private void bulkRender(String modidSpec, int size) {
        Minecraft mc = Minecraft.getInstance();
        mc.displayGuiScreen(new IngameMenuScreen(true));
        Set<String> modIds = Sets.newHashSet();
        for (String str : modidSpec.split(",")) {
            modIds.add(str.trim());
        }
        List<ItemStack> toRender = Lists.newArrayList();
        NonNullList<ItemStack> li = NonNullList.create();
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
        List<CompletableFuture<File>> futures = new ArrayList<>();
        //TODO: Allow early exiting?
        //Setup the render state once, render all the items, and then queue it for saving
        setUpRenderState(mc, size);
        for (ItemStack stack : toRender) {
            RenderSystem.clearColor(0, 0, 0, 0);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
            mc.getItemRenderer().renderItemAndEffectIntoGUI(stack, 0, 0);
            BufferedImage image = readPixels(size, size);
            futures.add(createSavingFuture(image, stack, folder, false));
        }
        tearDownRenderState();
        //TODO: Show progress of the above, not just saving?
        mc.setLoadingGui(new ProgressBarGui(mc, futures));
    }

    public static void setupOverlayRendering() {
        RenderSystem.clear(256, true);
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.loadIdentity();
        RenderSystem.ortho(0.0D, Minecraft.getInstance().getMainWindow().getScaledWidth(), Minecraft.getInstance().getMainWindow().getScaledHeight(),
              0.0D, 1000.0D, 3000.0D);
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.loadIdentity();
        RenderSystem.translatef(0.0F, 0.0F, -2000.0F);
    }

    private float oldZLevel;

    private void setUpRenderState(Minecraft mc, int desiredSize) {
        RenderSystem.pushMatrix();
        /*
         * As we render to the back-buffer, we need to cap our render size
         * to be within the window's bounds. If we didn't do this, the results
         * of our readPixels up ahead would be undefined. And nobody likes
         * undefined behavior.
         */
        int size = Math.min(Math.min(mc.getMainWindow().getHeight(), mc.getMainWindow().getWidth()), desiredSize);

        // Switches from 3D to 2D
        setupOverlayRendering();
        RenderHelper.enableStandardItemLighting();
        /*
         * The GUI scale affects us due to the call to setupOverlayRendering
         * above. As such, we need to counteract this to always get a 512x512
         * render. We could manually switch to orthogonal mode, but it's just
         * more convenient to leverage setupOverlayRendering.
         */
        double scale = size / (16 * mc.getMainWindow().getGuiScaleFactor());
        RenderSystem.translated(0, 0, -(scale * 100));

        RenderSystem.scaled(scale, scale, scale);

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

    private void tearDownRenderState() {
        RenderSystem.disableLighting();
        RenderSystem.disableColorMaterial();
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();

        Minecraft.getInstance().getItemRenderer().zLevel = oldZLevel;
        RenderSystem.popMatrix();
    }

    private static String sanitize(String str) {
        return str.replaceAll("[^A-Za-z0-9-_ ]", "_");
    }

    private static BufferedImage readPixels(int width, int height) {
        /*
         * Make sure we're reading from the back buffer, not the front buffer.
         * The front buffer is what is currently on-screen, and is useful for
         * screenshots.
         */
        //TODO: Doesn't seem to be a thing anymore and prints a bunch of gl warnings instead?
        //GL11.glReadBuffer(GL11.GL_BACK);
        // Allocate a native data array to fit our pixels
        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
        // And finally read the pixel data from the GPU...
        RenderSystem.readPixels(0, Minecraft.getInstance().getMainWindow().getHeight() - height, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        // ...and turn it into a Java object we can do things to.
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        buf.asIntBuffer().get(pixels);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private static CompletableFuture<File> createSavingFuture(BufferedImage img, ItemStack stack, File folder, boolean includeDateInFilename) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                /*
                 * We need to flip the image over here, because again, GL Y-zero is
                 * the bottom, so it's "Y-up". Minecraft's Y-zero is the top, so it's
                 * "Y-down". Since readPixels is Y-up, our Y-down render is flipped.
                 * It's easier to do this operation on the resulting image than to
                 * do it with GL transforms. Not faster, just easier.
                 */
                BufferedImage flipped = createFlipped(img);
                String fileName = (includeDateInFilename ? dateFormat.format(new Date()) + "_" : "") + sanitize(stack.getDisplayName().getString());
                File f = new File(folder, fileName + ".png");
                int i = 2;
                while (f.exists()) {
                    f = new File(folder, fileName + "_" + i + ".png");
                    i++;
                }
                Files.createParentDirs(f);
                f.createNewFile();
                ImageIO.write(flipped, "PNG", f);
                return f;
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
        }, Util.getServerExecutor());
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

package com.unascribed.blockrenderer;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Pair;
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
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.Phase;
import net.minecraftforge.event.TickEvent.RenderTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

public class ClientRenderHandler {

    private static final ScheduledExecutorService SCHEDULER = new ScheduledThreadPoolExecutor(0);
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");

    protected KeyBinding bind;
    protected boolean down = false;
    protected String pendingBulkRender;
    protected int pendingBulkRenderSize;
    private float oldZLevel;

    public ClientRenderHandler() {
        bind = new KeyBinding("key.blockrenderer.render", GLFW.GLFW_KEY_GRAVE_ACCENT, "key.blockrenderer.category");
        ClientRegistry.registerKeyBinding(bind);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onFrameStart);
    }

    public void onFrameStart(RenderTickEvent e) {
        //Quick primer: OpenGL is double-buffered. This means, where we draw to is
        // /not/ on the screen. As such, we are free to do whatever we like before
        // Minecraft renders, as long as we put everything back the way it was.
        if (e.phase == Phase.START) {
            if (pendingBulkRender != null) {
                //We *must* call render code in pre-render. If we don't, it won't work right.
                bulkRender(pendingBulkRender, pendingBulkRenderSize);
                pendingBulkRender = null;
            }
            if (isKeyDown(bind)) {
                if (!down) {
                    down = true;
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.world == null) {
                        mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.blockrenderer.no_world"));
                        return;
                    }
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
                            mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.blockrenderer.slot.absent"));
                        } else {
                            ItemStack stack = hovered.getStack();
                            if (stack.isEmpty()) {
                                mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.blockrenderer.slot.empty"));
                            } else {
                                int size = 512;
                                if (Screen.hasShiftDown()) {
                                    size = (int) (16 * mc.getMainWindow().getGuiScaleFactor());
                                }
                                mc.ingameGUI.getChatGUI().printChatMessage(render(mc, stack, size, new File("renders"), true));
                            }
                        }
                    } else {
                        mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.blockrenderer.not_container"));
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
        List<ItemStack> toRender = new ArrayList<>();
        NonNullList<ItemStack> li = NonNullList.create();
        boolean wildcard = modIds.contains("*");
        for (Entry<RegistryKey<Item>, Item> entry : ForgeRegistries.ITEMS.getEntries()) {
            if (wildcard || modIds.contains(entry.getKey().getLocation().getNamespace())) {
                li.clear();
                Item item = entry.getValue();
                ItemGroup group = item.getGroup();
                if (group == null && item instanceof EnchantedBookItem) {
                    //Vanilla has special handing for filling the enchanted book item's group, so just grab a single enchanted book
                    li.add(new ItemStack(item));
                } else {
                    try {
                        item.fillItemGroup(group, li);
                    } catch (Throwable t) {
                        BlockRenderer.log.warn("Failed to get renderable items for {} and group {}", item.getRegistryName(), group, t);
                    }
                }
                toRender.addAll(li);
            }
        }
        File folder = new File("renders/" + dateFormat.format(new Date()) + "_" + sanitize(modidSpec) + "/");
        ProgressBarGui progressBar = new ProgressBarGui(mc, toRender.size(), Joiner.on(", ").join(modIds));
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        //Create futures for generating and saving the images for each item
        // we split our items to render into batches, and then delay each batch
        // by batchIndex + 1. This allows us to have our progress bar properly
        // render instead of us freezing the game trying to render all the items
        // during a single tick
        List<List<ItemStack>> batchedLists = Lists.partition(toRender, 7);
        for (int batchIndex = 0, batchedCount = batchedLists.size(); batchIndex < batchedCount; batchIndex++) {
            futures.add(createFuture(batchedLists.get(batchIndex), size, folder, false, batchIndex + 1, progressBar));
        }
        progressBar.setFutures(futures);
        mc.setLoadingGui(progressBar);
    }

    private void setUpRenderState(Minecraft mc, int desiredSize) {
        RenderSystem.pushMatrix();
        //As we render to the back-buffer, we need to cap our render size
        // to be within the window's bounds. If we didn't do this, the results
        // of our readPixels up ahead would be undefined. And nobody likes
        // undefined behavior.
        int size = Math.min(Math.min(mc.getMainWindow().getHeight(), mc.getMainWindow().getWidth()), desiredSize);

        //Switches from 3D to 2D
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
        RenderSystem.matrixMode(GL11.GL_PROJECTION);
        RenderSystem.loadIdentity();
        RenderSystem.ortho(0.0D, Minecraft.getInstance().getMainWindow().getScaledWidth(), Minecraft.getInstance().getMainWindow().getScaledHeight(),
              0.0D, 1000.0D, 3000.0D);
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.loadIdentity();
        RenderSystem.translatef(0.0F, 0.0F, -2000.0F);
        RenderHelper.enableStandardItemLighting();
        //The GUI scale affects us due to the call to setupOverlayRendering
        // above. As such, we need to counteract this to always get a 512x512
        // render. We could manually switch to orthogonal mode, but it's just
        // more convenient to leverage setupOverlayRendering.
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

    private ITextComponent render(Minecraft mc, ItemStack stack, int size, File folder, boolean includeDateInFilename) {
        //Draw and read image on main thread
        setUpRenderState(mc, size);
        RenderSystem.clearColor(0, 0, 0, 0);
        RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
        mc.getItemRenderer().renderItemAndEffectIntoGUI(stack, 0, 0);
        BufferedImage image = readPixels(size, size);
        tearDownRenderState();
        try {
            //And then save the image off thread
            File file = CompletableFuture.supplyAsync(() -> saveImage(image, stack, folder, includeDateInFilename), Util.getServerExecutor()).get();
            return new TranslationTextComponent("msg.blockrenderer.render.success", file.getPath());
        } catch (InterruptedException | ExecutionException ex) {
            ex.printStackTrace();
            return new TranslationTextComponent("msg.blockrenderer.render.fail");
        }
    }

    private CompletableFuture<Void> createFuture(List<ItemStack> stacks, int size, File folder, boolean includeDateInFilename, int tickDelay, ProgressBarGui progressBar) {
        Executor gameExecutor;
        if (tickDelay == 0) {
            gameExecutor = Minecraft.getInstance();
        } else {
            //Note: We delay our executors by the given number of ticks so that we
            // can allow the progress screen to properly render instead of clogging
            // up the main game thread on rendering all the items
            gameExecutor = r -> SCHEDULER.schedule(() -> Minecraft.getInstance().execute(r), tickDelay * 50, TimeUnit.MILLISECONDS);
        }
        return CompletableFuture.supplyAsync(() -> {
            //Setup the render state for our batch on the main thread,
            // render the entire batch gathering the images for it
            // and revert the render state
            setUpRenderState(Minecraft.getInstance(), size);
            List<Pair<ItemStack, BufferedImage>> images = new ArrayList<>();
            for (ItemStack stack : stacks) {
                RenderSystem.clearColor(0, 0, 0, 0);
                RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
                Minecraft.getInstance().getItemRenderer().renderItemAndEffectIntoGUI(stack, 0, 0);
                images.add(Pair.of(stack, readPixels(size, size)));
                //Update the progress bar
                progressBar.update(stack);
            }
            tearDownRenderState();
            return images;
        }, gameExecutor).thenAcceptAsync(images -> {
            //Save images off thread
            for (Pair<ItemStack, BufferedImage> image : images) {
                saveImage(image.getSecond(), image.getFirst(), folder, includeDateInFilename);
            }
        }, Util.getServerExecutor());
    }

    private static File saveImage(BufferedImage image, ItemStack stack, File folder, boolean includeDateInFilename) {
        try {
            //We need to flip the image over here, because again, GL Y-zero is
            // the bottom, so it's "Y-up". Minecraft's Y-zero is the top, so it's
            // "Y-down". Since readPixels is Y-up, our Y-down render is flipped.
            // It's easier to do this operation on the resulting image than to
            // do it with GL transforms. Not faster, just easier.
            BufferedImage flipped = createFlipped(image);
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
    }

    private static String sanitize(String str) {
        return str.replaceAll("[^A-Za-z0-9-_ ]", "_");
    }

    private static BufferedImage readPixels(int width, int height) {
        //Allocate a native data array to fit our pixels
        ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
        //And finally read the pixel data from the GPU...
        RenderSystem.readPixels(0, Minecraft.getInstance().getMainWindow().getHeight() - height, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        //...and turn it into a Java object we can do things to.
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        buf.asIntBuffer().get(pixels);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        return img;
    }

    private static BufferedImage createFlipped(BufferedImage image) {
        AffineTransform at = new AffineTransform();
        //Creates a compound affine transform, instead of just one, as we need
        // to perform two transformations.
        // The first one is to scale the image to 100% width, and -100% height.
        // (That's *negative* 100%.)
        at.concatenate(AffineTransform.getScaleInstance(1, -1));
        //We then need to translate the image back up by it's height, as flipping
        // it over moves it off the bottom of the canvas.
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
}
package com.unascribed.blockrenderer;

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
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager.DestFactor;
import com.mojang.blaze3d.platform.GlStateManager.SourceFactor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.datafixers.util.Pair;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;

import net.minecraft.client.MainWindow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LoadingGui;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.IngameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.util.InputMappings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
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

public class ClientRenderHandler {

	private static final ScheduledExecutorService SCHEDULER = new ScheduledThreadPoolExecutor(0);
	private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");

	protected KeyBinding bind;
	protected boolean down = false;
	protected String pendingBulkRender;
	protected int pendingBulkRenderSize;
	protected boolean pendingBulkItems;
	protected boolean pendingBulkEntities;
	protected boolean pendingBulkStructures;
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
			Minecraft mc = Minecraft.getInstance();
			LoadingGui loadingGui = mc.getLoadingGui();
			if (loadingGui instanceof RenderProgressGui && InputMappings.isKeyDown(Minecraft.getInstance().getMainWindow().getHandle(), GLFW.GLFW_KEY_ESCAPE)) {
				//If we are currently rendering our progress bar and the user hit escapes, cancel the bulk rendering
				((RenderProgressGui) loadingGui).cancel();
				return;
			}
			if (pendingBulkRender != null) {
				//We *must* call render code in pre-render. If we don't, it won't work right.
				bulkRender(pendingBulkRender, pendingBulkRenderSize, pendingBulkItems, pendingBulkEntities, pendingBulkStructures);
				pendingBulkRender = null;
			}
			if (isKeyDown(bind)) {
				if (!down) {
					down = true;
					if (mc.world == null) {
						mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("msg.blockrenderer.no_world"));
						return;
					}
					Slot hovered = null;
					Screen currentScreen = mc.currentScreen;
					if (currentScreen instanceof ChatScreen) return;
					if (currentScreen instanceof ContainerScreen) {
						hovered = ((ContainerScreen<?>) currentScreen).getSlotUnderMouse();
					}

					if (Screen.hasControlDown()) {
						String modId = null;
						if (hovered != null && hovered.getHasStack()) {
							modId = hovered.getStack().getItem().getRegistryName().getNamespace();
						}
						mc.displayGuiScreen(new GuiConfigureRender(mc.currentScreen, modId));
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
								mc.ingameGUI.getChatGUI().printChatMessage(render(mc, new ItemRenderTask(stack), size, new File("renders/items"), true));
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

	private void bulkRender(String modidSpec, int size, boolean items, boolean entities, boolean structures) {
		Minecraft mc = Minecraft.getInstance();
		mc.displayGuiScreen(new IngameMenuScreen(true));
		Set<String> modIds = Sets.newHashSet();
		for (String str : modidSpec.split(",")) {
			modIds.add(str.trim());
		}
		List<RenderTask> toRender = new ArrayList<>();
		NonNullList<ItemStack> li = NonNullList.create();
		boolean wildcard = modIds.contains("*");
		if (items) {
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
					for (ItemStack is : li) {
						toRender.add(new ItemRenderTask(is));
					}
				}
			}
		}
		if (entities) {
			for (Entry<RegistryKey<EntityType<?>>, EntityType<?>> entry : ForgeRegistries.ENTITIES.getEntries()) {
				if (wildcard || modIds.contains(entry.getKey().getLocation().getNamespace())) {
					li.clear();
					EntityType<?> entityType = entry.getValue();
					try {
						Entity e = entityType.create(mc.world);
						if (e == null) continue;
						toRender.add(new EntityRenderTask(e));
					} catch (Throwable t) {
						BlockRenderer.log.warn("Failed to get renderable entity for {}", entry.getKey().getRegistryName());
					}
				}
			}
		}
		File folder = new File("renders/" + dateFormat.format(new Date()) + "_" + sanitize(modidSpec) + "/");
		RenderProgressGui progressBar = new RenderProgressGui(mc, toRender.size(), Joiner.on(", ").join(modIds));
		List<CompletableFuture<Void>> futures = new ArrayList<>();
		//Create futures for generating and saving the images for each item
		// we split our items to render into batches, and then delay each batch
		// by batchIndex + 1. This allows us to have our progress bar properly
		// render instead of us freezing the game trying to render all the items
		// during a single tick
		List<List<RenderTask>> batchedLists = Lists.partition(toRender, 10);
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
		MainWindow window = mc.getMainWindow();
		int size = Math.min(Math.min(window.getHeight(), window.getWidth()), desiredSize);

		//Switches from 3D to 2D
		RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
		RenderSystem.matrixMode(GL11.GL_PROJECTION);
		RenderSystem.loadIdentity();
		RenderSystem.ortho(0, window.getWidth(), window.getHeight(), 0, 1000, 3000);
		RenderSystem.matrixMode(GL11.GL_MODELVIEW);
		RenderSystem.loadIdentity();
		RenderSystem.translatef(0.0F, 0.0F, -2000.0F);
		RenderHelper.enableStandardItemLighting();
		double scale = size / (16D);
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

	private ITextComponent render(Minecraft mc, RenderTask task, int size, File folder, boolean includeDateInFilename) {
		setUpRenderState(mc, size);
		RenderSystem.clearColor(0, 0, 0, 0);
		RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
		try {
			task.render(size);
		} catch (Throwable t) {
			BlockRenderer.log.warn("Failed to render "+task.getId(), t);
			return new TranslationTextComponent("msg.blockrenderer.render.fail");
		}
		NativeImage image = readPixels(size, size);
		tearDownRenderState();
		// This code would need to be refactored to perform this save off-thread and not block the
		// main thread. This is a problem for later.
		File file = saveImage(image, task, folder, includeDateInFilename);
		return new TranslationTextComponent("msg.blockrenderer.render.success", file.getPath());
	}

	private CompletableFuture<Void> createFuture(List<RenderTask> tasks, int size, File folder, boolean includeDateInFilename, int tickDelay, RenderProgressGui progressBar) {
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
			List<Pair<RenderTask, NativeImage>> images = new ArrayList<>();
			for (RenderTask task : tasks) {
				RenderSystem.clearColor(0, 0, 0, 0);
				RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.IS_RUNNING_ON_MAC);
				task.render(size);
				images.add(Pair.of(task, readPixels(size, size)));
				//Update the progress bar
				progressBar.update(task);
			}
			tearDownRenderState();
			return images;
		}, gameExecutor).thenAcceptAsync(images -> {
			//Save images off thread
			int types = 0;
			if (pendingBulkItems) types++;
			if (pendingBulkEntities) types++;
			if (pendingBulkStructures) types++;
			for (Pair<RenderTask, NativeImage> image : images) {
				saveImage(image.getSecond(), image.getFirst(), types > 1 ? new File(folder, image.getFirst().getCategory()) : folder, includeDateInFilename);
			}
		}, Util.getServerExecutor());
	}

	private static File saveImage(NativeImage image, RenderTask task, File folder, boolean includeDateInFilename) {
		try {
			String fileName = (includeDateInFilename ? dateFormat.format(new Date()) + "_" : "") + sanitize(task.getDisplayName());
			File f = new File(folder, fileName + ".png");
			int i = 2;
			while (f.exists()) {
				f = new File(folder, fileName + "_" + i + ".png");
				i++;
			}
			Files.createParentDirs(f);
			image.write(f);
			return f;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private static String sanitize(String str) {
		return str.replaceAll("[^A-Za-z0-9-_ ]", "_");
	}

	private static NativeImage readPixels(int width, int height) {
		ByteBuffer buf = BufferUtils.createByteBuffer(width * height * 4);
		RenderSystem.readPixels(0, Minecraft.getInstance().getMainWindow().getHeight() - height, width, height, GL12.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
		NativeImage img = new NativeImage(width, height, false);
		int[] pixels = new int[width * height];
		buf.asIntBuffer().get(pixels);
		// Load the pixels into the NativeImage, flipped vertically as OpenGL and
		// Minecraft disagree about which way is up
		for (int y = 0; y < height; y++) {
			int off = y*width;
			for (int x = 0; x < width; x++) img.setPixelRGBA(x, (height-1)-y, pixels[off++]);
		}
		return img;
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
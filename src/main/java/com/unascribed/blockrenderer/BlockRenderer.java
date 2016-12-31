package com.unascribed.blockrenderer;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.RenderTickEvent;

@Mod(modid=BlockRenderer.MODID,name=BlockRenderer.NAME,version=BlockRenderer.VERSION,acceptableRemoteVersions="*",acceptableSaveVersions="*",clientSideOnly=true)
public class BlockRenderer {
	public static final String MODID = "blockrenderer";
	public static final String NAME = "BlockRenderer";
	public static final String VERSION = "1.0.0";
	
	@Instance
	public static BlockRenderer inst;
	
	protected KeyBinding bind;
	protected boolean down = false;
	protected static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss");
	protected String pendingBulkRender;
	protected int pendingBulkRenderSize;
	
	protected final Logger log = LogManager.getLogger("BlockRenderer");
	
	@EventHandler
	public void onPreInit(FMLPreInitializationEvent e) {
		bind = new KeyBinding("key.render", Keyboard.KEY_GRAVE, "key.categories.blockrenderer");
		ClientRegistry.registerKeyBinding(bind);
		MinecraftForge.EVENT_BUS.register(this);
	}
	
	@SubscribeEvent(priority=EventPriority.HIGHEST)
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
			// XXX is this really neccessary? I forget why I made it unwrap the binding...
			int code = bind.getKeyCode();
			if (code > 256) {
				return;
			}
			if (Keyboard.isKeyDown(code)) {
				if (!down) {
					down = true;
					Minecraft mc = Minecraft.getMinecraft();
					Slot hovered = null;
					GuiScreen currentScreen = mc.currentScreen;
					if (currentScreen instanceof GuiContainer) {
						int w = currentScreen.width;
						int h = currentScreen.height;
						final int x = Mouse.getX() * w / mc.displayWidth;
						// OpenGL's Y-zero is at the *bottom* of the window.
						// Minecraft's Y-zero is at the top. So, we need to flip it.
						final int y = h - Mouse.getY() * h / mc.displayHeight - 1;
						hovered = ((GuiContainer)currentScreen).getSlotAtPosition(x, y);
					}
					if (GuiScreen.isCtrlKeyDown()) {
						String modid = null;
						if (hovered != null && hovered.getHasStack()) {
							modid = Item.REGISTRY.getNameForObject(hovered.getStack().getItem()).getResourceDomain();
						}
						mc.displayGuiScreen(new GuiEnterModId(mc.currentScreen, modid));
					} else if (currentScreen instanceof GuiContainer) {
						if (hovered != null) {
							ItemStack is = hovered.getStack();
							if (is != null) {
								int size = 512;
								if (GuiScreen.isShiftKeyDown()) {
									size = 16*new ScaledResolution(mc).getScaleFactor();
								}
								setUpRenderState(size);
								mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString(render(is, new File("renders"), true)));
								tearDownRenderState();
							} else {
								mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("msg.slot.empty"));
							}
						} else {
							mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("msg.slot.absent"));
						}
					} else {
						mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("msg.notcontainer"));
					}
				}
			} else {
				down = false;
			}
		}
	}

	private void bulkRender(String modidSpec, int size) {
		Minecraft.getMinecraft().displayGuiScreen(new GuiIngameMenu());
		Set<String> modids = Sets.newHashSet();
		for (String str : modidSpec.split(",")) {
			modids.add(str.trim());
		}
		List<ItemStack> toRender = Lists.newArrayList();
		NonNullList<ItemStack> li = NonNullList.func_191196_a();
		int rendered = 0;
		for (ResourceLocation resloc : Item.REGISTRY.getKeys()) {
			if (resloc != null && modids.contains(resloc.getResourceDomain()) || modids.contains("*")) {
				li.clear();
				Item i = Item.REGISTRY.getObject(resloc);
				try {
					i.getSubItems(i, i.getCreativeTab(), li);
				} catch (Throwable t) {
					log.warn("Failed to get renderable items for "+resloc, t);
				}
				toRender.addAll(li);
			}
		}
		File folder = new File("renders/"+dateFormat.format(new Date())+"_"+sanitize(modidSpec)+"/");
		long lastUpdate = 0;
		String joined = Joiner.on(", ").join(modids);
		setUpRenderState(size);
		for (ItemStack is : toRender) {
			if (Keyboard.isKeyDown(Keyboard.KEY_ESCAPE))
				break;
			render(is, folder, false);
			rendered++;
			if (Minecraft.getSystemTime()-lastUpdate > 33) {
				tearDownRenderState();
				renderLoading(I18n.format("gui.rendering", toRender.size(), joined),
						I18n.format("gui.progress", rendered, toRender.size(), (toRender.size()-rendered)),
						is, (float)rendered/toRender.size());
				lastUpdate = Minecraft.getSystemTime();
				setUpRenderState(size);
			}
		}
		if (rendered >= toRender.size()) {
			renderLoading(I18n.format("gui.rendered", toRender.size(), Joiner.on(", ").join(modids)), "", null, 1);
		} else {
			renderLoading(I18n.format("gui.renderCancelled"),
					I18n.format("gui.progress", rendered, toRender.size(), (toRender.size()-rendered)),
					null, (float)rendered/toRender.size());
		}
		tearDownRenderState();
		try {
			Thread.sleep(1500);
		} catch (InterruptedException e) {}
	}

	private void renderLoading(String title, String subtitle, ItemStack is, float progress) {
		Minecraft mc = Minecraft.getMinecraft();
		mc.getFramebuffer().unbindFramebuffer();
		GlStateManager.pushMatrix();
			ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
			mc.entityRenderer.setupOverlayRendering();
			// Draw the dirt background and status text...
			Rendering.drawBackground(res.getScaledWidth(), res.getScaledHeight());
			Rendering.drawCenteredString(mc.fontRendererObj, title, res.getScaledWidth()/2, res.getScaledHeight()/2-24, -1);
			Rendering.drawRect(res.getScaledWidth()/2-50, res.getScaledHeight()/2-1, res.getScaledWidth()/2+50, res.getScaledHeight()/2+1, 0xFF001100);
			Rendering.drawRect(res.getScaledWidth()/2-50, res.getScaledHeight()/2-1, (res.getScaledWidth()/2-50)+(int)(progress*100), res.getScaledHeight()/2+1, 0xFF55FF55);
			GlStateManager.pushMatrix();
				GlStateManager.scale(0.5f, 0.5f, 1);
				Rendering.drawCenteredString(mc.fontRendererObj, subtitle, res.getScaledWidth(), res.getScaledHeight()-20, -1);
				// ...and draw the tooltip.
				if (is != null) {
					try {
						List<String> list = is.getTooltip(mc.thePlayer, true);
			
						// This code is copied from the tooltip renderer, so we can properly center it.
						for (int i = 0; i < list.size(); ++i) {
							if (i == 0) {
								list.set(i, is.getRarity().rarityColor + list.get(i));
							} else {
								list.set(i, TextFormatting.GRAY + list.get(i));
							}
						}
			
						FontRenderer font = is.getItem().getFontRenderer(is);
						if (font == null) {
							font = mc.fontRendererObj;
						}
						int width = 0;
			
						for (String s : list) {
							int j = font.getStringWidth(s);
			
							if (j > width) {
								width = j;
							}
						}
						// End copied code.
						GlStateManager.translate((res.getScaledWidth()-width/2)-12, res.getScaledHeight()+30, 0);
						Rendering.drawHoveringText(list, 0, 0, font);
					} catch (Throwable t) {}
				}
			GlStateManager.popMatrix();
		GlStateManager.popMatrix();
		mc.updateDisplay();
		/*
		 * While OpenGL itself is double-buffered, Minecraft is actually *triple*-buffered.
		 * This is to allow shaders to work, as shaders are only available in "modern" GL.
		 * Minecraft uses "legacy" GL, so it renders using a separate GL context to this
		 * third buffer, which is then flipped to the back buffer with this call.
		 */
		mc.getFramebuffer().bindFramebuffer(false);
	}

	private String render(ItemStack is, File folder, boolean includeDateInFilename) {
		Minecraft mc = Minecraft.getMinecraft();
		String filename = (includeDateInFilename ? dateFormat.format(new Date())+"_" : "")+sanitize(is.getDisplayName());
		GlStateManager.pushMatrix();
			GlStateManager.clearColor(0, 0, 0, 0);
			GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
			mc.renderItem.renderItemAndEffectIntoGUI(is, 0, 0);
		GlStateManager.popMatrix();
		try {
			/*
			 * We need to flip the image over here, because again, GL Y-zero is
			 * the bottom, so it's "Y-up". Minecraft's Y-zero is the top, so it's
			 * "Y-down". Since readPixels is Y-up, our Y-down render is flipped.
			 * It's easier to do this operation on the resulting image than to
			 * do it with GL transforms. Not faster, just easier.
			 */
			BufferedImage img = createFlipped(readPixels(size, size));
			
			File f = new File(folder, filename+".png");
			int i = 2;
			while (f.exists()) {
				f = new File(folder, filename+"_"+i+".png");
				i++;
			}
			Files.createParentDirs(f);
			f.createNewFile();
			ImageIO.write(img, "PNG", f);
			return I18n.format("msg.render.success", f.getPath());
		} catch (Exception ex) {
			ex.printStackTrace();
			return I18n.format("msg.render.fail");
		}
	}
	
	private int size;
	private float oldZLevel;
	
	private void setUpRenderState(int desiredSize) {
		Minecraft mc = Minecraft.getMinecraft();
		ScaledResolution res = new ScaledResolution(mc);
		/*
		 * As we render to the back-buffer, we need to cap our render size
		 * to be within the window's bounds. If we didn't do this, the results
		 * of our readPixels up ahead would be undefined. And nobody likes
		 * undefined behavior.
		 */
		size = Math.min(Math.min(mc.displayHeight, mc.displayWidth), desiredSize);
		
		// Switches from 3D to 2D
		mc.entityRenderer.setupOverlayRendering();
		RenderHelper.enableGUIStandardItemLighting();
		/*
		 * The GUI scale affects us due to the call to setupOverlayRendering
		 * above. As such, we need to counteract this to always get a 512x512
		 * render. We could manually switch to orthogonal mode, but it's just
		 * more convenient to leverage setupOverlayRendering.
		 */
		float scale = size/(16f*res.getScaleFactor());
		GlStateManager.translate(0, 0, -(scale*100));
		
		GlStateManager.scale(scale, scale, scale);
		
		oldZLevel = mc.renderItem.zLevel;
		mc.renderItem.zLevel = -50;

		GlStateManager.enableRescaleNormal();
		GlStateManager.enableColorMaterial();
		GlStateManager.enableDepth();
		GlStateManager.enableBlend();
		GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_SRC_ALPHA, GL11.GL_ONE);
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.disableAlpha();
	}
	
	private void tearDownRenderState() {
		GlStateManager.disableLighting();
		GlStateManager.disableColorMaterial();
		GlStateManager.disableDepth();
		GlStateManager.disableBlend();
		
		Minecraft.getMinecraft().renderItem.zLevel = oldZLevel;
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
		GL11.glReadPixels(0, Minecraft.getMinecraft().displayHeight-height, width, height, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
		// ...and turn it into a Java object we can do things to.
		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] pixels = new int[width*height];
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

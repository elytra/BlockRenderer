package com.unascribed.blockrenderer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BlockRenderer.MODID)
public class BlockRenderer {

    public static final Logger log = LogManager.getLogger("BlockRenderer");
    public static final String MODID = "blockrenderer";

    public static ClientRenderHandler renderHandler;

    public BlockRenderer() {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> renderHandler = new ClientRenderHandler());
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(() -> "ignored", (remote, isServer) -> true));
    }
}
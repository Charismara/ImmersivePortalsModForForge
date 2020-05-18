package com.qouteall.immersive_portals.mixin_client;

import com.qouteall.immersive_portals.CGlobal;
import com.qouteall.immersive_portals.ModMain;
import com.qouteall.immersive_portals.ducks.IEMinecraftClient;
import com.qouteall.immersive_portals.render.CrossPortalEntityRenderer;
import com.qouteall.immersive_portals.render.FPSMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraftClient implements IEMinecraftClient {
    @Final
    @Shadow
    @Mutable
    private Framebuffer framebuffer;
    
    @Shadow
    public Screen currentScreen;
    
    @Mutable
    @Shadow
    @Final
    public WorldRenderer worldRenderer;
    
    @Shadow private static int debugFPS;
    
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;runTick()V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;tick(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.AFTER
        )
    )
    private void onAfterClientTick(CallbackInfo ci) {
        ModMain.postClientTickSignal.emit();
        
        CGlobal.clientTeleportationManager.manageTeleportation(0);
    }
    
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;runGameLoop(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/profiler/Snooper;addMemoryStatsToSnooper()V"
        )
    )
    private void onSnooperUpdate(boolean tick, CallbackInfo ci) {
        FPSMonitor.updateEverySecond(debugFPS);
    }
    
    @Inject(
        method = "Lnet/minecraft/client/Minecraft;updateWorldRenderer(Lnet/minecraft/client/world/ClientWorld;)V",
        at = @At("HEAD")
    )
    private void onSetWorld(ClientWorld clientWorld_1, CallbackInfo ci) {
        CGlobal.clientWorldLoader.cleanUp();
        CrossPortalEntityRenderer.cleanUp();
    }
    
    @Override
    public void setFrameBuffer(Framebuffer buffer) {
        framebuffer = buffer;
    }
    
    @Override
    public Screen getCurrentScreen() {
        return currentScreen;
    }
    
    @Override
    public void setWorldRenderer(WorldRenderer r) {
        worldRenderer = r;
    }
}

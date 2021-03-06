package com.qouteall.immersive_portals.mixin;

import com.qouteall.immersive_portals.Global;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.ducks.IERayTraceContext;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.PortalPlaceholderBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RayTraceContext.class)
public abstract class MixinRayTraceContext implements IERayTraceContext {
    @SuppressWarnings("ShadowModifiers")
    @Shadow
    private Vec3d startVec;
    
    @SuppressWarnings("ShadowModifiers")
    @Shadow
    private Vec3d endVec;
    
    @Override
    public IERayTraceContext setStart(Vec3d newStart) {
        startVec = newStart;
        return this;
    }
    
    @Override
    public IERayTraceContext setEnd(Vec3d newEnd) {
        endVec = newEnd;
        return this;
    }
    
    @Inject(
        at = @At("HEAD"),
        method = "Lnet/minecraft/util/math/RayTraceContext;getBlockShape(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/IBlockReader;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/shapes/VoxelShape;",
        cancellable = true
    )
    private void onGetBlockShape(
        BlockState blockState,
        IBlockReader blockView,
        BlockPos blockPos,
        CallbackInfoReturnable<VoxelShape> cir
    ) {
        if (Global.portalPlaceholderPassthrough && blockState.getBlock() == PortalPlaceholderBlock.instance) {
            if (blockView instanceof World) {
                boolean isIntersectingWithPortal = McHelper.getEntitiesRegardingLargeEntities(
                    (World) blockView, new AxisAlignedBB(blockPos),
                    10, Portal.class, e -> true
                ).isEmpty();
                if (!isIntersectingWithPortal) {
                    cir.setReturnValue(VoxelShapes.empty());
                    cir.cancel();
                }
            }
        }
    }
}

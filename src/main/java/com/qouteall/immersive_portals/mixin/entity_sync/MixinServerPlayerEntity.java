package com.qouteall.immersive_portals.mixin.entity_sync;

import com.google.common.collect.HashMultimap;
import com.mojang.authlib.GameProfile;
import com.qouteall.immersive_portals.SGlobal;
import com.qouteall.immersive_portals.chunk_loading.NewChunkTrackingGraph;
import com.qouteall.immersive_portals.ducks.IEServerPlayerEntity;
import com.qouteall.hiding_in_the_bushes.network.NetworkMain;
import com.qouteall.immersive_portals.portal.global_portals.GlobalPortalStorage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.play.ServerPlayNetHandler;
import net.minecraft.network.play.server.SDestroyEntitiesPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.ITeleporter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

@Mixin(value = ServerPlayerEntity.class)
public abstract class MixinServerPlayerEntity extends PlayerEntity implements IEServerPlayerEntity {
    @Shadow
    public ServerPlayNetHandler connection;
    @Shadow
    private Vec3d enteredNetherPosition;
    
    private HashMultimap<DimensionType, Entity> myRemovedEntities;
    
    public MixinServerPlayerEntity(
        World p_i45324_1_,
        GameProfile p_i45324_2_
    ) {
        super(p_i45324_1_, p_i45324_2_);
        throw new IllegalStateException();
    }
    
    @Shadow
    public abstract void func_213846_b(ServerWorld serverWorld_1);
    
    @Shadow
    private boolean invulnerableDimensionChange;
    
    @Override
    public void setEnteredNetherPos(Vec3d pos) {
        enteredNetherPosition = pos;
    }
    
    @Override
    public void updateDimensionTravelAdvancements(ServerWorld fromWorld) {
        func_213846_b(fromWorld);
    }
    
    @Inject(
        method = "sendChunkUnload",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onSendUnloadChunkPacket(ChunkPos chunkPos_1, CallbackInfo ci) {
        ci.cancel();
    }
    
    @Inject(
        method = "tick",
        at = @At("TAIL")
    )
    private void onTicking(CallbackInfo ci) {
        if (myRemovedEntities != null) {
            myRemovedEntities.keySet().forEach(dimension -> {
                Set<Entity> list = myRemovedEntities.get(dimension);
                NetworkMain.sendRedirected(
                    connection.player, dimension,
                    new SDestroyEntitiesPacket(
                        list.stream().mapToInt(
                            Entity::getEntityId
                        ).toArray()
                    )
                );
            });
            myRemovedEntities = null;
        }
    }
    
    @Inject(method = "changeDimension", at = @At("HEAD"), remap = false)
    private void onChangeDimensionByVanilla(
        DimensionType p_changeDimension_1_,
        ITeleporter p_changeDimension_2_,
        CallbackInfoReturnable<Entity> cir
    ) {
        SGlobal.chunkDataSyncManager.onPlayerRespawn((ServerPlayerEntity) (Object) this);
    }
    
    /**
     * @author qouteall
     * @reason
     */
    @Overwrite
    public void removeEntity(Entity entity_1) {
        if (entity_1 instanceof PlayerEntity) {
            NetworkMain.sendRedirected(
                connection.player, entity_1.dimension,
                new SDestroyEntitiesPacket(entity_1.getEntityId())
            );
        }
        else {
            if (myRemovedEntities == null) {
                myRemovedEntities = HashMultimap.create();
            }
            //do not use entity.dimension
            //or it will work abnormally when changeDimension() is run
            myRemovedEntities.put(entity_1.world.dimension.getType(), entity_1);
        }
        
    }
    
    /**
     * @author qouteall
     * @reason
     */
    @Overwrite
    public void addEntity(Entity entity_1) {
        if (myRemovedEntities != null) {
            myRemovedEntities.remove(entity_1.dimension, entity_1);
        }
    }
    
    @Inject(
        method = "Lnet/minecraft/entity/player/ServerPlayerEntity;teleport(Lnet/minecraft/world/server/ServerWorld;DDDFF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/server/ServerWorld;removePlayer(Lnet/minecraft/entity/player/ServerPlayerEntity;Z)V",
            remap = false
        )
    )
    private void onForgeTeleport(
        ServerWorld p_200619_1_,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        CallbackInfo ci
    ) {
        ServerPlayerEntity player = (ServerPlayerEntity) (Object) this;
        
        //fix issue with good nights sleep
        player.clearBedPosition();
    
        NewChunkTrackingGraph.forceRemovePlayer(player);
    
        GlobalPortalStorage.onPlayerLoggedIn(player);
    }
    
    @Override
    public void setIsInTeleportationState(boolean arg) {
        invulnerableDimensionChange = arg;
    }
    
    @Override
    public void stopRidingWithoutTeleportRequest() {
        super.stopRiding();
    }
    
    @Override
    public void startRidingWithoutTeleportRequest(Entity newVehicle) {
        super.startRiding(newVehicle, true);
    }
}

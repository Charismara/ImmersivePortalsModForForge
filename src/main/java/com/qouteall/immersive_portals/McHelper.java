package com.qouteall.immersive_portals;

import com.google.common.collect.Streams;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.qouteall.immersive_portals.ducks.IEThreadedAnvilChunkStorage;
import com.qouteall.immersive_portals.ducks.IEWorldChunk;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.global_portals.GlobalPortalStorage;
import com.qouteall.immersive_portals.portal.global_portals.GlobalTrackedPortal;
import com.qouteall.immersive_portals.render.CrossPortalEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ServerChunkProvider;
import net.minecraft.world.server.ServerWorld;
import org.lwjgl.opengl.GL11;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class McHelper {
    
    public static WeakReference<MinecraftServer> refMinecraftServer =
        new WeakReference<>(null);
    
    public static IEThreadedAnvilChunkStorage getIEStorage(DimensionType dimension) {
        return (IEThreadedAnvilChunkStorage) (
            (ServerChunkProvider) getServer().getWorld(dimension).getChunkProvider()
        ).chunkManager;
    }
    
    public static ArrayList<ServerPlayerEntity> getCopiedPlayerList() {
        return new ArrayList<>(getServer().getPlayerList().getPlayers());
    }
    
    public static List<ServerPlayerEntity> getRawPlayerList() {
        return getServer().getPlayerList().getPlayers();
    }
    
    public static Vec3d lastTickPosOf(Entity entity) {
        return new Vec3d(entity.prevPosX, entity.prevPosY, entity.prevPosZ);
    }
    
    public static MinecraftServer getServer() {
        return refMinecraftServer.get();
    }
    
    public static ServerWorld getOverWorldOnServer() {
        return getServer().getWorld(DimensionType.OVERWORLD);
    }
    
    public static void serverLog(
        ServerPlayerEntity player,
        String text
    ) {
        player.sendMessage(new StringTextComponent(text));
    }
    
    public static AxisAlignedBB getChunkBoundingBox(ChunkPos chunkPos) {
        return new AxisAlignedBB(
            chunkPos.asBlockPos(),
            chunkPos.asBlockPos().add(16, 256, 16)
        );
    }
    
    public static long getServerGameTime() {
        return getOverWorldOnServer().getGameTime();
    }
    
    public static <T> void performFindingTaskOnServer(
        boolean isMultithreaded,
        Stream<T> stream,
        Predicate<T> predicate,
        IntPredicate taskWatcher,//return false to abort the task
        Consumer<T> onFound,
        Runnable onNotFound,
        Runnable finalizer
    ) {
        if (isMultithreaded) {
            performMultiThreadedFindingTaskOnServer(
                stream, predicate, taskWatcher, onFound, onNotFound, finalizer
            );
        }
        else {
            performSplittedFindingTaskOnServer(
                stream, predicate, taskWatcher, onFound, onNotFound, finalizer
            );
        }
    }
    
    public static <T> void performSplittedFindingTaskOnServer(
        Stream<T> stream,
        Predicate<T> predicate,
        IntPredicate taskWatcher,//return false to abort the task
        Consumer<T> onFound,
        Runnable onNotFound,
        Runnable finalizer
    ) {
        final long timeValve = (1000000000L / 50);
        int[] countStorage = new int[1];
        countStorage[0] = 0;
        Iterator<T> iterator = stream.iterator();
        ModMain.serverTaskList.addTask(() -> {
            boolean shouldContinueRunning =
                taskWatcher.test(countStorage[0]);
            if (!shouldContinueRunning) {
                finalizer.run();
                return true;
            }
            long startTime = System.nanoTime();
            for (; ; ) {
                for (int i = 0; i < 300; i++) {
                    if (iterator.hasNext()) {
                        T next = iterator.next();
                        if (predicate.test(next)) {
                            onFound.accept(next);
                            finalizer.run();
                            return true;
                        }
                        countStorage[0] += 1;
                    }
                    else {
                        //finished searching
                        onNotFound.run();
                        finalizer.run();
                        return true;
                    }
                }
                
                long currTime = System.nanoTime();
                
                if (currTime - startTime > timeValve) {
                    //suspend the task and retry it next tick
                    return false;
                }
            }
        });
    }
    
    public static <T> void performMultiThreadedFindingTaskOnServer(
        Stream<T> stream,
        Predicate<T> predicate,
        IntPredicate taskWatcher,//return false to abort the task
        Consumer<T> onFound,
        Runnable onNotFound,
        Runnable finalizer
    ) {
        int[] progress = new int[1];
        Helper.SimpleBox<Boolean> isAborted = new Helper.SimpleBox<>(false);
        Helper.SimpleBox<Runnable> finishBehavior = new Helper.SimpleBox<>(() -> {
            Helper.err("Error Occured???");
        });
        CompletableFuture<Void> future = CompletableFuture.runAsync(
            () -> {
                try {
                    T result = stream.peek(
                        obj -> {
                            progress[0] += 1;
                        }
                    ).filter(
                        predicate
                    ).findFirst().orElse(null);
                    if (result != null) {
                        finishBehavior.obj = () -> onFound.accept(result);
                    }
                    else {
                        finishBehavior.obj = onNotFound;
                    }
                }
                catch (Throwable t) {
                    t.printStackTrace();
                    finishBehavior.obj = () -> {
                        t.printStackTrace();
                    };
                }
            },
            McHelper.getServer().getBackgroundExecutor()
        );
        ModMain.serverTaskList.addTask(() -> {
            if (future.isDone()) {
                if (!isAborted.obj) {
                    finishBehavior.obj.run();
                    finalizer.run();
                }
                else {
                    Helper.log("Future done but the task is aborted");
                }
                return true;
            }
            if (future.isCancelled()) {
                Helper.err("The future is cancelled???");
                finalizer.run();
                return true;
            }
            if (future.isCompletedExceptionally()) {
                Helper.err("The future is completed exceptionally???");
                finalizer.run();
                return true;
            }
            boolean shouldContinue = taskWatcher.test(progress[0]);
            if (!shouldContinue) {
                isAborted.obj = true;
                future.cancel(true);
                finalizer.run();
                return true;
            }
            else {
                return false;
            }
        });
    }
    
    public static <ENTITY extends Entity> Stream<ENTITY> getEntitiesNearby(
        World world,
        Vec3d center,
        Class<ENTITY> entityClass,
        double range
    ) {
        AxisAlignedBB box = new AxisAlignedBB(center, center).grow(range);
        return (Stream) world.getEntitiesWithinAABB(entityClass, box, e -> true).stream();
    }
    
    public static <ENTITY extends Entity> Stream<ENTITY> getEntitiesNearby(
        Entity center,
        Class<ENTITY> entityClass,
        double range
    ) {
        return getEntitiesNearby(
            center.world,
            center.getPositionVec(),
            entityClass,
            range
        );
    }
    
    public static void runWithTransformation(
        MatrixStack matrixStack,
        Runnable renderingFunc
    ) {
        transformationPush(matrixStack);
        renderingFunc.run();
        transformationPop();
    }
    
    public static void transformationPop() {
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.popMatrix();
    }
    
    public static void transformationPush(MatrixStack matrixStack) {
        RenderSystem.matrixMode(GL11.GL_MODELVIEW);
        RenderSystem.pushMatrix();
        RenderSystem.loadIdentity();
        RenderSystem.multMatrix(matrixStack.getLast().getMatrix());
    }
    
    public static List<GlobalTrackedPortal> getGlobalPortals(World world) {
        List<GlobalTrackedPortal> result;
        if (world.isRemote) {
            result = CHelper.getClientGlobalPortal(world);
        }
        else {
            result = GlobalPortalStorage.get(((ServerWorld) world)).data;
        }
        return result != null ? result : Collections.emptyList();
    }
    
    public static Stream<Portal> getServerPortalsNearby(Entity center, double range) {
        List<GlobalTrackedPortal> globalPortals = GlobalPortalStorage.get(((ServerWorld) center.world)).data;
        Stream<Portal> nearbyPortals = McHelper.getServerEntitiesNearbyWithoutLoadingChunk(
            center.world,
            center.getPositionVec(),
            Portal.class,
            range
        );
        if (globalPortals == null) {
            return nearbyPortals;
        }
        else {
            return Streams.concat(
                globalPortals.stream().filter(
                    p -> p.getDistanceToNearestPointInPortal(center.getPositionVec()) < range * 2
                ),
                nearbyPortals
            );
        }
    }
    
    public static int getRenderDistanceOnServer() {
        return getIEStorage(DimensionType.OVERWORLD).getWatchDistance();
    }
    
    public static void setPosAndLastTickPos(
        Entity entity,
        Vec3d pos,
        Vec3d lastTickPos
    ) {
        
        
        //NOTE do not call entity.setPosition() because it may tick the entity
        entity.setRawPosition(pos.x, pos.y, pos.z);
        entity.lastTickPosX = lastTickPos.x;
        entity.lastTickPosY = lastTickPos.y;
        entity.lastTickPosZ = lastTickPos.z;
        entity.prevPosX = lastTickPos.x;
        entity.prevPosY = lastTickPos.y;
        entity.prevPosZ = lastTickPos.z;
    }
    
    public static Vec3d getEyePos(Entity entity) {
        float eyeHeight = entity.getEyeHeight();
        return entity.getPositionVec().add(0, eyeHeight, 0);
    }
    
    public static Vec3d getLastTickEyePos(Entity entity) {
        float eyeHeight = entity.getEyeHeight();
        return lastTickPosOf(entity).add(0, eyeHeight, 0);
    }
    
    public static void setEyePos(Entity entity, Vec3d eyePos, Vec3d lastTickEyePos) {
        float eyeHeight = entity.getEyeHeight();
        setPosAndLastTickPos(
            entity,
            eyePos.add(0, -eyeHeight, 0),
            lastTickEyePos.add(0, -eyeHeight, 0)
        );
    }
    
    public static double getVehicleY(Entity vehicle, Entity passenger) {
        return passenger.getPosY() - vehicle.getMountedYOffset() - passenger.getYOffset();
    }
    
    public static void adjustVehicle(Entity entity) {
        Entity vehicle = entity.getRidingEntity();
        if (vehicle == null) {
            return;
        }
        
        vehicle.setPosition(
            entity.getPosX(),
            getVehicleY(vehicle, entity),
            entity.getPosZ()
        );
    }
    
    public static void checkDimension(Entity entity) {
        if (entity.dimension != entity.world.dimension.getType()) {
            Helper.err(String.format(
                "Entity dimension field abnormal. Force corrected. %s %s %s",
                entity,
                entity.dimension,
                entity.world.dimension.getType()
            ));
            entity.dimension = entity.world.dimension.getType();
        }
    }
    
    public static Chunk getServerChunkIfPresent(
        DimensionType dimension,
        int x, int z
    ) {
        //TODO cleanup
        ChunkHolder chunkHolder_ = getIEStorage(dimension).getChunkHolder_(ChunkPos.asLong(x, z));
        if (chunkHolder_ == null) {
            return null;
        }
        return chunkHolder_.getChunkIfComplete();
    }
    
    public static Chunk getServerChunkIfPresent(
        ServerWorld world, int x, int z
    ) {
        ChunkHolder chunkHolder_ = ((IEThreadedAnvilChunkStorage) (
            (ServerChunkProvider) world.getChunkProvider()
        ).chunkManager).getChunkHolder_(ChunkPos.asLong(x, z));
        if (chunkHolder_ == null) {
            return null;
        }
        return chunkHolder_.getChunkIfComplete();
    }
    
    public static <ENTITY extends Entity> Stream<ENTITY> getServerEntitiesNearbyWithoutLoadingChunk(
        World world,
        Vec3d center,
        Class<ENTITY> entityClass,
        double range
    ) {
        return McHelper.findEntitiesRough(
            entityClass,
            world,
            center,
            (int) (range / 16),
            e -> true
        ).stream();

//        Box box = new Box(center, center).expand(range);
//        return (Stream) ((IEServerWorld) world).getEntitiesWithoutImmediateChunkLoading(
//            entityClass,
//            box,
//            e -> true
//        ).stream();
    }
    
    public static void updateBoundingBox(Entity player) {
        player.setPosition(player.getPosX(), player.getPosY(), player.getPosZ());
    }
    
    public static <T extends Entity> List<T> getEntitiesRegardingLargeEntities(
        World world,
        AxisAlignedBB box,
        double maxEntitySizeHalf,
        Class<T> entityClass,
        Predicate<T> predicate
    ) {
        return findEntitiesByBox(
            entityClass,
            world,
            box,
            maxEntitySizeHalf,
            predicate
        );
//        world.getProfiler().visit("getEntitiesPortal");
//        int i = MathHelper.floor((box.x1 - maxEntitySizeHalf) / 16.0D);
//        int j = MathHelper.ceil((box.x2 + maxEntitySizeHalf) / 16.0D);
//        int k = MathHelper.floor((box.z1 - maxEntitySizeHalf) / 16.0D);
//        int l = MathHelper.ceil((box.z2 + maxEntitySizeHalf) / 16.0D);
//        List<T> list = Lists.newArrayList();
//        ChunkManager chunkManager = world.getChunkManager();
//
//        for (int m = i; m < j; ++m) {
//            for (int n = k; n < l; ++n) {
//                WorldChunk worldChunk = chunkManager.getWorldChunk(m, n, false);
//                if (worldChunk != null) {
//                    worldChunk.getEntities(entityClass, box, list, predicate);
//                }
//            }
//        }

//        return list;
    }
    
    //avoid dedicated server crash
    public static void onClientEntityTick(Entity entity) {
        CrossPortalEntityRenderer.onEntityTickClient(entity);
    }
    
    public static interface ChunkAccessor {
        Chunk getChunk(int x, int z);
    }
    
    public static ChunkAccessor getChunkAccessor(World world) {
        if (world.isRemote()) {
            return world::getChunk;
        }
        else {
            return (x, z) -> getServerChunkIfPresent(((ServerWorld) world), x, z);
        }
    }
    
    public static <T extends Entity> List<T> findEntities(
        Class<T> entityClass,
        ChunkAccessor chunkAccessor,
        int chunkXStart,
        int chunkXEnd,
        int chunkYStart,
        int chunkYEnd,
        int chunkZStart,
        int chunkZEnd,
        Predicate<T> predicate
    ) {
        ArrayList<T> result = new ArrayList<>();
        for (int x = chunkXStart; x <= chunkXEnd; x++) {
            for (int z = chunkZStart; z <= chunkZEnd; z++) {
                Chunk chunk = chunkAccessor.getChunk(x, z);
                if (chunk != null) {
                    ClassInheritanceMultiMap<Entity>[] entitySections =
                        ((IEWorldChunk) chunk).getEntitySections();
                    for (int i = chunkYStart; i <= chunkYEnd; i++) {
                        ClassInheritanceMultiMap<Entity> entitySection = entitySections[i];
                        for (T entity : entitySection.getByClass(entityClass)) {
                            if (predicate.test(entity)) {
                                result.add(entity);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }
    
    //faster
    public static <T extends Entity> List<T> findEntitiesRough(
        Class<T> entityClass,
        World world,
        Vec3d center,
        int radiusChunks,
        Predicate<T> predicate
    ) {
        ChunkPos chunkPos = new ChunkPos(new BlockPos(center));
        return findEntities(
            entityClass,
            getChunkAccessor(world),
            chunkPos.x - radiusChunks,
            chunkPos.x + radiusChunks,
            0, 15,
            chunkPos.z - radiusChunks,
            chunkPos.z + radiusChunks,
            predicate
        );
    }
    
    //does not load chunk on server and works with large entities
    public static <T extends Entity> List<T> findEntitiesByBox(
        Class<T> entityClass,
        World world,
        AxisAlignedBB box,
        double maxEntityRadius,
        Predicate<T> predicate
    ) {
        int xMin = (int) Math.floor(box.minX - maxEntityRadius);
        int yMin = (int) Math.floor(box.minY - maxEntityRadius);
        int zMin = (int) Math.floor(box.minZ - maxEntityRadius);
        int xMax = (int) Math.ceil(box.maxX + maxEntityRadius);
        int yMax = (int) Math.ceil(box.maxY + maxEntityRadius);
        int zMax = (int) Math.ceil(box.maxZ + maxEntityRadius);
        
        return findEntities(
            entityClass,
            getChunkAccessor(world),
            xMin >> 4,
            xMax >> 4,
            Math.max(0, yMin >> 4),
            Math.min(15, yMax >> 4),
            zMin >> 4,
            zMax >> 4,
            e -> e.getBoundingBox().intersects(box) && predicate.test(e)
        );
    }
    
    public static ResourceLocation dimensionTypeId(DimensionType dimType) {
        return Objects.requireNonNull(Registry.DIMENSION_TYPE.getKey(dimType));
    }
}

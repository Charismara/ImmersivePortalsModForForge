package com.qouteall.immersive_portals.chunk_loading;

import com.qouteall.immersive_portals.CGlobal;
import net.minecraft.client.multiplayer.ClientChunkProvider;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.SectionPos;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.BiomeContainer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.lighting.WorldLightManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

//this class is modified based on ClientChunkManager
//re-write this class upon updating mod
@OnlyIn(Dist.CLIENT)
public class MyClientChunkManager extends ClientChunkProvider {
    private static final Logger LOGGER = LogManager.getLogger();
    private final Chunk emptyChunk;
    private final WorldLightManager lightingProvider;
    private final ClientWorld world;
    
    //its performance is a little lower than vanilla
    //but this is indispensable
    //there is no Long2ObjectConcurrentHashMap so I use ChunkPos which is less cache friendly
    private ConcurrentHashMap<ChunkPos, Chunk> chunkMap = new ConcurrentHashMap<>();
    
    public MyClientChunkManager(ClientWorld clientWorld_1, int int_1) {
        super(clientWorld_1, int_1);
        this.world = clientWorld_1;
        this.emptyChunk = new EmptyChunk(clientWorld_1, new ChunkPos(0, 0));
        this.lightingProvider = new WorldLightManager(
            this,
            true,
            clientWorld_1.getDimension().hasSkyLight()
        );
    
    
    }
    
    @Override
    public WorldLightManager getLightManager() {
        return this.lightingProvider;
    }
    
    @Override
    public void unloadChunk(int int_1, int int_2) {
        ChunkPos chunkPos = new ChunkPos(int_1, int_2);
        Chunk worldChunk_1 = chunkMap.get(chunkPos);
        if (positionEquals(worldChunk_1, int_1, int_2)) {
            chunkMap.remove(chunkPos);
        }
    }
    
    @Override
    public Chunk getChunk(int int_1, int int_2, ChunkStatus chunkStatus_1, boolean boolean_1) {
        Chunk worldChunk_1 = chunkMap.get(new ChunkPos(int_1, int_2));
        if (positionEquals(worldChunk_1, int_1, int_2)) {
            return worldChunk_1;
        }
        
        return boolean_1 ? this.emptyChunk : null;
    }
    
    @Override
    public IBlockReader getWorld() {
        return this.world;
    }
    
    @Override
    public Chunk loadChunk(
        int int_1,
        int int_2,
        BiomeContainer biomeArray_1,
        PacketBuffer packetByteBuf_1,
        CompoundNBT compoundTag_1,
        int int_3
    ) {
        ChunkPos chunkPos = new ChunkPos(int_1, int_2);
        Chunk worldChunk_1 = (Chunk) chunkMap.get(chunkPos);
        if (!positionEquals(worldChunk_1, int_1, int_2)) {
            if (biomeArray_1 == null) {
                LOGGER.warn(
                    "Ignoring chunk since we don't have complete data: {}, {}",
                    int_1,
                    int_2
                );
                return null;
            }
    
            worldChunk_1 = new Chunk(this.world, chunkPos, biomeArray_1);
            worldChunk_1.read(biomeArray_1, packetByteBuf_1, compoundTag_1, int_3);
            chunkMap.put(chunkPos, worldChunk_1);
        }
        else {
            worldChunk_1.read(biomeArray_1, packetByteBuf_1, compoundTag_1, int_3);
        }
    
        ChunkSection[] chunkSections_1 = worldChunk_1.getSections();
        WorldLightManager lightingProvider_1 = this.getLightManager();
        lightingProvider_1.enableLightSources(chunkPos, true);
    
        for (int int_5 = 0; int_5 < chunkSections_1.length; ++int_5) {
            ChunkSection chunkSection_1 = chunkSections_1[int_5];
            lightingProvider_1.updateSectionStatus(
                SectionPos.of(int_1, int_5, int_2),
                ChunkSection.isEmpty(chunkSection_1)
            );
        }
    
        this.world.onChunkLoaded(int_1, int_2);
        return worldChunk_1;
    }
    
    public static void updateLightStatus(Chunk chunk) {
        ChunkSection[] chunkSections_1 = chunk.getSections();
        WorldLightManager lightingProvider = chunk.getWorld().getLightManager();
        for (int int_5 = 0; int_5 < chunkSections_1.length; ++int_5) {
            ChunkSection chunkSection_1 = chunkSections_1[int_5];
            lightingProvider.updateSectionStatus(
                SectionPos.of(chunk.getPos().x, int_5, chunk.getPos().z),
                ChunkSection.isEmpty(chunkSection_1)
            );
        }
    }
    
    @Override
    public void tick(BooleanSupplier booleanSupplier_1) {
    }
    
    @Override
    public void setCenter(int int_1, int int_2) {
        //do nothing
    }
    
    @Override
    public void setViewDistance(int int_1) {
        //do nothing
    }
    
    @Override
    public String makeString() {
        return "Immersive Portals Present " + chunkMap.size();
    }
    
    @Override
    public int getLoadedChunksCount() {
        return chunkMap.size();
    }
    
    @Override
    public void markLightChanged(LightType lightType_1, SectionPos chunkSectionPos_1) {
        CGlobal.clientWorldLoader.getWorldRenderer(
            world.dimension.getType()
        ).markForRerender(
            chunkSectionPos_1.getSectionX(),
            chunkSectionPos_1.getSectionY(),
            chunkSectionPos_1.getSectionZ()
        );
    }
    
    @Override
    public boolean canTick(BlockPos blockPos_1) {
        return this.chunkExists(blockPos_1.getX() >> 4, blockPos_1.getZ() >> 4);
    }
    
    @Override
    public boolean isChunkLoaded(ChunkPos chunkPos_1) {
        return this.chunkExists(chunkPos_1.x, chunkPos_1.z);
    }
    
    @Override
    public boolean isChunkLoaded(Entity entity_1) {
        return this.chunkExists(
            MathHelper.floor(entity_1.getPosX()) >> 4,
            MathHelper.floor(entity_1.getPosZ()) >> 4
        );
    }
    
    private static boolean positionEquals(Chunk worldChunk_1, int int_1, int int_2) {
        if (worldChunk_1 == null) {
            return false;
        }
        else {
            ChunkPos chunkPos_1 = worldChunk_1.getPos();
            return chunkPos_1.x == int_1 && chunkPos_1.z == int_2;
        }
    }

//
//    @Override
//    public LightingProvider getLightingProvider() {
//        return this.lightingProvider;
//    }
//
//    private static boolean isChunkValid(WorldChunk worldChunk_1, int int_1, int int_2) {
//        if (worldChunk_1 == null) {
//            return false;
//        }
//        else {
//            ChunkPos chunkPos_1 = worldChunk_1.getPos();
//            return chunkPos_1.x == int_1 && chunkPos_1.z == int_2;
//        }
//    }
//
//    @Override
//    public void unload(int int_1, int int_2) {
//        ChunkPos chunkPos = new ChunkPos(int_1, int_2);
//        WorldChunk chunk = chunkMap.get(chunkPos);
//        if (isChunkValid(chunk, int_1, int_2)) {
//            chunkMap.remove(chunkPos);
//            world.unloadBlockEntities(chunk);
//        }
//    }
//
//    @Override
//    public BlockView getWorld() {
//        return this.world;
//    }
//
//    //@Nullable
//    public WorldChunk loadChunkFromPacket(
//        World world_1,
//        int x,
//        int z,
//        PacketByteBuf packetByteBuf_1,
//        CompoundTag compoundTag_1,
//        int mask,
//        boolean isFullChunk
//    ) {
//        ChunkPos chunkPos = new ChunkPos(x, z);
//        WorldChunk chunk = chunkMap.get(chunkPos);
//        if (!isChunkValid(chunk, x, z)) {
//            if (!isFullChunk) {
//                LOGGER.warn(
//                    "Ignoring chunk since we don't have complete data: {}, {}",
//                    x,
//                    z
//                );
//                return null;
//            }
//
//            chunk = new WorldChunk(
//                world_1,
//                new ChunkPos(x, z),
//                new Biome[256]
//            );
//            chunk.loadFromPacket(packetByteBuf_1, compoundTag_1, mask, isFullChunk);
//            chunkMap.put(chunkPos, chunk);
//
//            world.unloadBlockEntities(chunk);//TODO wrong?
//        }
//        else {
//            if (isFullChunk) {
//                Helper.log(String.format(
//                    "received full chunk while chunk is present. entity may duplicate %s %s",
//                    chunk.getWorld().dimension.getType(),
//                    chunk.getPos()
//                ));
//            }
//            chunk.loadFromPacket(packetByteBuf_1, compoundTag_1, mask, isFullChunk);
//        }
//
//        ChunkSection[] chunkSections_1 = chunk.getSectionArray();
//        LightingProvider lightingProvider_1 = this.getLightingProvider();
//        lightingProvider_1.suppressLight(new ChunkPos(x, z), true);
//
//        for (int int_5 = 0; int_5 < chunkSections_1.length; ++int_5) {
//            ChunkSection chunkSection_1 = chunkSections_1[int_5];
//            lightingProvider_1.updateSectionStatus(
//                ChunkSectionPos.from(x, int_5, z),
//                ChunkSection.isEmpty(chunkSection_1)
//            );
//        }
//
//        return chunk;
//    }
//
//    @Override
//    public void tick(BooleanSupplier booleanSupplier_1) {
//    }
//
//    @Override
//    public void setChunkMapCenter(int int_1, int int_2) {
//        //do nothing
//    }
//
//    @Override
//    public void updateLoadDistance(int int_1) {
//        //do nothing
//    }
//
//    private static int getLoadDistance(int int_1) {
//        return Math.max(2, int_1) + 3;
//    }
//
//    @Override
//    public String getStatus() {
//        return "Hacked Client Chunk Manager " + chunkMap.size();
//    }
//
//    @Override
//    public ChunkGenerator<?> getChunkGenerator() {
//        return null;
//    }
//
//    @Override
//    public void onLightUpdate(LightType lightType_1, ChunkSectionPos chunkSectionPos_1) {
//        MinecraftClient.getInstance().worldRenderer.scheduleBlockRender(
//            chunkSectionPos_1.getChunkX(),
//            chunkSectionPos_1.getChunkY(),
//            chunkSectionPos_1.getChunkZ()
//        );
//    }
//
//    @Override
//    public boolean shouldTickBlock(BlockPos blockPos_1) {
//        return this.isChunkLoaded(blockPos_1.getX() >> 4, blockPos_1.getZ() >> 4);
//    }
//
//    @Override
//    public boolean shouldTickChunk(ChunkPos chunkPos_1) {
//        return this.isChunkLoaded(chunkPos_1.x, chunkPos_1.z);
//    }
//
//    @Override
//    public boolean shouldTickEntity(Entity entity_1) {
//        return this.isChunkLoaded(
//            MathHelper.floor(entity_1.x) >> 4,
//            MathHelper.floor(entity_1.z) >> 4
//        );
//    }
//
//    // $FF: synthetic method
//    //@Nullable
//    @Override
//    public Chunk getChunk(int var1, int var2, ChunkStatus var3, boolean var4) {
//        WorldChunk worldChunk_1 = chunkMap.get(new ChunkPos(var1, var2));
//        if (isChunkValid(worldChunk_1, var1, var2)) {
//            return worldChunk_1;
//        }
//
//        return var4 ? this.emptyChunk : null;
//    }
    
    public int getChunkNum() {
        return chunkMap.size();
    }
    
}

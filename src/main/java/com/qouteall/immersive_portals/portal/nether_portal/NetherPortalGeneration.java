package com.qouteall.immersive_portals.portal.nether_portal;

import com.google.common.collect.Streams;
import com.qouteall.hiding_in_the_bushes.O_O;
import com.qouteall.immersive_portals.Global;
import com.qouteall.immersive_portals.Helper;
import com.qouteall.immersive_portals.McHelper;
import com.qouteall.immersive_portals.ModMain;
import com.qouteall.immersive_portals.chunk_loading.ChunkVisibilityManager;
import com.qouteall.immersive_portals.chunk_loading.DimensionalChunkPos;
import com.qouteall.immersive_portals.chunk_loading.NewChunkTrackingGraph;
import com.qouteall.immersive_portals.my_util.IntBox;
import com.qouteall.immersive_portals.portal.LoadingIndicatorEntity;
import com.qouteall.immersive_portals.portal.Portal;
import com.qouteall.immersive_portals.portal.PortalPlaceholderBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.gen.WorldGenRegion;
import net.minecraft.world.server.ServerWorld;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class NetherPortalGeneration {
    public final static int randomShiftFactor = 20;
    
    public static BlockPos getRandomShift() {
        Random rand = new Random();
        return new BlockPos(
            (rand.nextDouble() * 2 - 1) * randomShiftFactor,
            (rand.nextDouble() * 2 - 1) * randomShiftFactor,
            (rand.nextDouble() * 2 - 1) * randomShiftFactor
        );
    }
    
    public static IntBox findAirCubePlacement(
        ServerWorld toWorld,
        BlockPos mappedPosInOtherDimension,
        IntBox heightLimit,
        Direction.Axis axis,
        BlockPos neededAreaSize,
        int findingRadius
    ) {
        IntBox foundAirCube =
            axis == Direction.Axis.Y ?
                NetherPortalMatcher.findHorizontalPortalPlacement(
                    neededAreaSize, toWorld, mappedPosInOtherDimension,
                    findingRadius
                ) :
                NetherPortalMatcher.findVerticalPortalPlacement(
                    neededAreaSize, toWorld, mappedPosInOtherDimension,
                    findingRadius
                );
        
        if (foundAirCube == null) {
            Helper.log("Cannot find normal portal placement");
            foundAirCube = NetherPortalMatcher.findCubeAirAreaAtAnywhere(
                neededAreaSize, toWorld, mappedPosInOtherDimension, 10
            );
        }
        
        if (foundAirCube == null) {
            Helper.log("Cannot find air cube within 10 blocks");
            foundAirCube = NetherPortalMatcher.findCubeAirAreaAtAnywhere(
                neededAreaSize, toWorld, mappedPosInOtherDimension, 16
            );
        }
        
        if (foundAirCube == null) {
            Helper.err("Cannot find air cube within 16 blocks? " +
                "Force placed portal. It will occupy normal blocks.");
            
            foundAirCube = IntBox.getBoxByBasePointAndSize(
                neededAreaSize,
                mappedPosInOtherDimension
            );
        }
        return foundAirCube;
    }
    
    public static DimensionType getDestinationDimension(
        DimensionType fromDimension
    ) {
        if (fromDimension == DimensionType.THE_NETHER) {
            return DimensionType.OVERWORLD;
        }
        else if (fromDimension == DimensionType.THE_END) {
            return null;
        }
        else {
            //you can access nether in any other dimension
            //including alternate dimensions
            return DimensionType.THE_NETHER;
        }
    }
    
    public static BlockPos mapPosition(
        BlockPos pos,
        DimensionType dimensionFrom,
        DimensionType dimensionTo
    ) {
        if (dimensionFrom == DimensionType.OVERWORLD && dimensionTo == DimensionType.THE_NETHER) {
            return new BlockPos(
                Math.round(pos.getX() / 8.0),
                pos.getY(),
                Math.round(pos.getZ() / 8.0)
            );
        }
        else if (dimensionFrom == DimensionType.THE_NETHER && dimensionTo == DimensionType.OVERWORLD) {
            return new BlockPos(
                pos.getX() * 8,
                pos.getY(),
                pos.getZ() * 8
            );
        }
        else {
            return pos;
        }
    }
    
    public static void setPortalContentBlock(
        ServerWorld world,
        BlockPos pos,
        Direction.Axis normalAxis
    ) {
        world.setBlockState(
            pos,
            PortalPlaceholderBlock.instance.getDefaultState().with(
                PortalPlaceholderBlock.AXIS, normalAxis
            ),
            3
        );
    }
    
    //create portal entity and generate placeholder blocks
    public static void generateBreakablePortalEntities(
        Info info,
        EntityType<? extends BreakablePortalEntity> entityType
    ) {
        ServerWorld fromWorld = McHelper.getServer().getWorld(info.from);
        ServerWorld toWorld = McHelper.getServer().getWorld(info.to);
        
        fillInPlaceHolderBlocks(fromWorld, info.fromShape);
        fillInPlaceHolderBlocks(toWorld, info.toShape);
        
        BreakablePortalEntity[] portalArray = new BreakablePortalEntity[]{
            entityType.create(fromWorld),
            entityType.create(fromWorld),
            entityType.create(toWorld),
            entityType.create(toWorld)
        };
        
        info.fromShape.initPortalPosAxisShape(
            portalArray[0], false
        );
        info.fromShape.initPortalPosAxisShape(
            portalArray[1], true
        );
        info.toShape.initPortalPosAxisShape(
            portalArray[2], false
        );
        info.toShape.initPortalPosAxisShape(
            portalArray[3], true
        );
        
        portalArray[0].dimensionTo = info.to;
        portalArray[1].dimensionTo = info.to;
        portalArray[2].dimensionTo = info.from;
        portalArray[3].dimensionTo = info.from;
        
        Vec3d offset = new Vec3d(info.toShape.innerAreaBox.l.subtract(
            info.fromShape.innerAreaBox.l
        ));
        portalArray[0].destination = portalArray[0].getPositionVec().add(offset);
        portalArray[1].destination = portalArray[1].getPositionVec().add(offset);
        portalArray[2].destination = portalArray[2].getPositionVec().subtract(offset);
        portalArray[3].destination = portalArray[3].getPositionVec().subtract(offset);
        
        portalArray[0].blockPortalShape = info.fromShape;
        portalArray[1].blockPortalShape = info.fromShape;
        portalArray[2].blockPortalShape = info.toShape;
        portalArray[3].blockPortalShape = info.toShape;
        
        portalArray[0].reversePortalId = portalArray[2].getUniqueID();
        portalArray[1].reversePortalId = portalArray[3].getUniqueID();
        portalArray[2].reversePortalId = portalArray[0].getUniqueID();
        portalArray[3].reversePortalId = portalArray[1].getUniqueID();
        
        fromWorld.addEntity(portalArray[0]);
        fromWorld.addEntity(portalArray[1]);
        toWorld.addEntity(portalArray[2]);
        toWorld.addEntity(portalArray[3]);
    }
    
    public static class Info {
        public DimensionType from;
        public DimensionType to;
        public BlockPortalShape fromShape;
        public BlockPortalShape toShape;
        
        public Info(
            DimensionType from,
            DimensionType to,
            BlockPortalShape fromShape,
            BlockPortalShape toShape
        ) {
            this.from = from;
            this.to = to;
            this.fromShape = fromShape;
            this.toShape = toShape;
        }
    }
    
    //return null for not found
    //executed on main server thread
    public static boolean onFireLitOnObsidian(
        ServerWorld fromWorld,
        BlockPos firePos
    ) {
        DimensionType fromDimension = fromWorld.dimension.getType();
        
        DimensionType toDimension = getDestinationDimension(fromDimension);
        
        if (toDimension == null) return false;
        
        ServerWorld toWorld = McHelper.getServer().getWorld(toDimension);
        
        int searchingRadius = Global.netherPortalFindingRadius;
        
        if (Global.reversibleNetherPortalLinking) {
            if (fromDimension == DimensionType.OVERWORLD) {
                searchingRadius /= 8;
            }
        }
        
        BlockPortalShape thisSideShape = startGeneratingPortal(
            fromWorld,
            firePos,
            toWorld,
            searchingRadius,
            searchingRadius - 10,
            (fromPos1) -> mapPosition(
                fromPos1,
                fromWorld.dimension.getType(),
                toWorld.dimension.getType()
            ),
            //this side area
            NetherPortalMatcher::isAirOrFire,
            //this side frame
            O_O::isObsidian,
            //other side area
            BlockState::isAir,
            //other side frame
            O_O::isObsidian,
            (shape) -> embodyNewFrame(toWorld, shape, Blocks.OBSIDIAN.getDefaultState()),
            info -> generateBreakablePortalEntities(info, NetherPortalEntity.entityType)
        );
        return thisSideShape != null;
    }
    
    public static boolean activatePortalHelper(
        ServerWorld fromWorld,
        BlockPos firePos
    ) {
        Helper.SimpleBox<BlockPortalShape> thisSideShape = new Helper.SimpleBox<>(null);
        thisSideShape.obj = startGeneratingPortal(
            fromWorld,
            firePos,
            fromWorld,
            Global.netherPortalFindingRadius,
            Global.netherPortalFindingRadius,
            (fromPos1) -> getRandomShift().add(fromPos1),
            NetherPortalMatcher::isAirOrFire,
            blockState -> blockState.getBlock() == ModMain.portalHelperBlock,
            BlockState::isAir,
            (blockState) -> blockState.getBlock() == ModMain.portalHelperBlock,
            (toShape) -> {
                embodyNewFrame(fromWorld, toShape, ModMain.portalHelperBlock.getDefaultState());
            },
            info -> {
                generateHelperPortalEntities(info);
                info.fromShape.frameAreaWithCorner.forEach(blockPos -> {
                    if (fromWorld.getBlockState(blockPos).getBlock() == ModMain.portalHelperBlock) {
                        fromWorld.setBlockState(blockPos, Blocks.AIR.getDefaultState());
                    }
                });
            }
        );
        return thisSideShape.obj != null;
    }
    
    //return this side shape if the generation starts
    public static BlockPortalShape startGeneratingPortal(
        ServerWorld fromWorld,
        BlockPos startingPos,
        ServerWorld toWorld,
        int existingFrameSearchingRadius,
        int airCubeSearchingRadius,
        Function<BlockPos, BlockPos> positionMapping,
        Predicate<BlockState> thisSideAreaPredicate,
        Predicate<BlockState> thisSideFramePredicate,
        Predicate<BlockState> otherSideAreaPredicate,
        Predicate<BlockState> otherSideFramePredicate,
        Consumer<BlockPortalShape> newFrameGeneratedFunc,
        Consumer<Info> portalEntityGeneratingFunc
    ) {
        if (!fromWorld.isBlockLoaded(startingPos)) {
            Helper.log("Cancel Nether Portal Generation Because Chunk Not Loaded");
            return null;
        }
        
        DimensionType fromDimension = fromWorld.dimension.getType();
        DimensionType toDimension = toWorld.dimension.getType();
        
        Helper.log(String.format("Portal Generation Attempted %s %s %s %s",
            fromDimension, startingPos.getX(), startingPos.getY(), startingPos.getZ()
        ));
        
        BlockPortalShape foundShape = Arrays.stream(Direction.Axis.values())
            .map(
                axis -> {
                    return BlockPortalShape.findArea(
                        startingPos,
                        axis,
                        (pos) -> thisSideAreaPredicate.test(fromWorld.getBlockState(pos)),
                        (pos) -> thisSideFramePredicate.test(fromWorld.getBlockState(pos))
                    );
                }
            ).filter(
                Objects::nonNull
            ).findFirst().orElse(null);
        
        if (foundShape == null) {
            return null;
        }
        
        BlockPos fromPos = foundShape.innerAreaBox.getCenter();
        
        Vec3d indicatorPos = foundShape.innerAreaBox.getCenterVec();
        
        boolean isOtherGenerationRunning = McHelper.getEntitiesNearby(
            fromWorld, indicatorPos, LoadingIndicatorEntity.class, 1
        ).findAny().isPresent();
        if (isOtherGenerationRunning) {
            Helper.log(
                "Aborted Nether Portal Generation Because Another Generation is Running Nearby"
            );
            return null;
        }
        
        LoadingIndicatorEntity indicatorEntity =
            LoadingIndicatorEntity.entityType.create(fromWorld);
        indicatorEntity.isAlive = true;
        indicatorEntity.setPosition(
            indicatorPos.x, indicatorPos.y, indicatorPos.z
        );
        fromWorld.addEntity(indicatorEntity);
        
        BlockPos toPos = positionMapping.apply(fromPos);
        
        Runnable generateNewFrameFunc = () -> {
            generateNewFrame(
                toWorld,
                airCubeSearchingRadius,
                newFrameGeneratedFunc,
                portalEntityGeneratingFunc,
                fromDimension,
                toDimension,
                foundShape,
                indicatorEntity,
                toPos
            );
        };
        
        boolean shouldSkip = !McHelper.getIEStorage(toDimension)
            .portal_isChunkGenerated(new ChunkPos(toPos));
        if (shouldSkip) {
            Helper.log("Skip Portal Frame Searching Because The Region is not Generated");
            generateNewFrameFunc.run();
            indicatorEntity.remove();
            return foundShape;
        }
        
        int loaderRadius = Math.floorDiv(existingFrameSearchingRadius, 16) + 1;
        ChunkVisibilityManager.ChunkLoader chunkLoader = new ChunkVisibilityManager.ChunkLoader(
            new DimensionalChunkPos(
                toDimension, new ChunkPos(toPos)
            ),
            loaderRadius
        );
        
        NewChunkTrackingGraph.addAdditionalChunkLoader(chunkLoader);
        
        Runnable finalizer = () -> {
            indicatorEntity.remove();
            NewChunkTrackingGraph.removeAdditionalChunkLoader(chunkLoader);
        };
        
        ModMain.serverTaskList.addTask(() -> {
            boolean isPortalIntact = foundShape.isPortalIntact(
                blockPos -> thisSideAreaPredicate.test(fromWorld.getBlockState(blockPos)),
                blockPos -> thisSideFramePredicate.test(fromWorld.getBlockState(blockPos))
            );
            
            if (!isPortalIntact) {
                finalizer.run();
                return true;
            }
            
            int[] loadedChunkNum = {0};
            chunkLoader.foreachChunkPos((dim, x, z, dist) -> {
                Chunk chunk = McHelper.getServerChunkIfPresent(dim, x, z);
                if (chunk != null) {
                    loadedChunkNum[0] += 1;
                }
            });
            
            int allChunksNeedsLoading = (loaderRadius * 2 + 1) * (loaderRadius * 2 + 1);
            
            if (allChunksNeedsLoading > loadedChunkNum[0]) {
                indicatorEntity.setText(new TranslationTextComponent(
                    "imm_ptl.loading_chunks", loadedChunkNum[0], allChunksNeedsLoading
                ));
                return false;
            }
            else {
                WorldGenRegion chunkRegion = chunkLoader.createChunkRegion();
                
                indicatorEntity.setText(new TranslationTextComponent("imm_ptl.searching_for_frame"));
                
                
                startSearchingPortalFrame(
                    chunkRegion,
                    loaderRadius,
                    otherSideAreaPredicate,
                    otherSideFramePredicate,
                    portalEntityGeneratingFunc,
                    fromDimension,
                    toDimension,
                    foundShape,
                    toPos,
                    finalizer,
                    generateNewFrameFunc
                );
                
                return true;
            }
        });
        
        return foundShape;
    }
    
    private static void generateNewFrame(
        ServerWorld toWorld,
        int airCubeSearchingRadius,
        Consumer<BlockPortalShape> newFrameGeneratedFunc,
        Consumer<Info> portalEntityGeneratingFunc,
        DimensionType fromDimension,
        DimensionType toDimension,
        BlockPortalShape foundShape,
        LoadingIndicatorEntity indicatorEntity,
        BlockPos toPos
    ) {
        indicatorEntity.setText(new TranslationTextComponent(
            "imm_ptl.generating_new_frame"
        ));
        
        ModMain.serverTaskList.addTask(() -> {
            
            IntBox toWorldHeightLimit =
                NetherPortalMatcher.getHeightLimit(toWorld.dimension.getType());
            
            IntBox airCubePlacement =
                findAirCubePlacement(
                    toWorld, toPos, toWorldHeightLimit,
                    foundShape.axis, foundShape.totalAreaBox.getSize(),
                    airCubeSearchingRadius
                );
            
            BlockPortalShape toShape = foundShape.getShapeWithMovedAnchor(
                airCubePlacement.l.subtract(
                    foundShape.totalAreaBox.l
                ).add(foundShape.anchor)
            );
            
            newFrameGeneratedFunc.accept(toShape);
            
            Info info = new Info(
                fromDimension, toDimension, foundShape, toShape
            );
            portalEntityGeneratingFunc.accept(info);
            
            O_O.postPortalSpawnEventForge(info);
            
            return true;
        });
    }
    
    private static void startSearchingPortalFrame(
        WorldGenRegion toWorldRegion,
        int regionRadius,
        Predicate<BlockState> otherSideAreaPredicate,
        Predicate<BlockState> otherSideFramePredicate,
        Consumer<Info> portalEntityGeneratingFunc,
        DimensionType fromDimension,
        DimensionType toDimension,
        BlockPortalShape foundShape,
        BlockPos toPos,
        Runnable finishBehavior,
        Runnable onNotFound
    ) {
        
        //avoid blockpos object creation
        BlockPos.Mutable temp = new BlockPos.Mutable();
        
        Consumer<BlockPortalShape> onFound = toShape -> {
            Info info = new Info(
                fromDimension, toDimension, foundShape, toShape
            );
            
            portalEntityGeneratingFunc.accept(info);
        };
        
        FrameSearching.startSearchingPortalFrameAsync(
            toWorldRegion, regionRadius,
            toPos, foundShape,
            otherSideAreaPredicate, otherSideFramePredicate,
            (shape) -> {
                onFound.accept(shape);
                finishBehavior.run();
            },
            () -> {
                onNotFound.run();
                finishBehavior.run();
            }
        );
    }
    
    public static Stream<BlockPos> blockPosStreamNaive(
        ServerWorld toWorld,
        int x, int z, int raidus
    ) {
        Stream<BlockPos> blockPosStream = BlockPos.getAllInBox(
            new BlockPos(
                x - raidus,
                3,
                z - raidus
            ),
            new BlockPos(
                x + raidus,
                toWorld.getActualHeight() - 3,
                z + raidus
            )
        );
        return blockPosStream;
    }
    
    public static void embodyNewFrame(
        ServerWorld toWorld,
        BlockPortalShape toShape,
        BlockState frameBlockState
    ) {
        toShape.frameAreaWithCorner.forEach(blockPos ->
            toWorld.setBlockState(blockPos, frameBlockState)
        );
    }
    
    public static void fillInPlaceHolderBlocks(
        ServerWorld fromWorld,
        BlockPortalShape blockPortalShape
    ) {
        blockPortalShape.area.forEach(
            blockPos -> setPortalContentBlock(
                fromWorld, blockPos, blockPortalShape.axis
            )
        );
    }
    
    private static void generateHelperPortalEntities(Info info) {
        ServerWorld fromWorld1 = McHelper.getServer().getWorld(info.from);
        ServerWorld toWorld = McHelper.getServer().getWorld(info.to);
        
        Portal[] portalArray = new Portal[]{
            Portal.entityType.create(fromWorld1),
            Portal.entityType.create(fromWorld1),
            Portal.entityType.create(toWorld),
            Portal.entityType.create(toWorld)
        };
        
        info.fromShape.initPortalPosAxisShape(
            portalArray[0], false
        );
        info.fromShape.initPortalPosAxisShape(
            portalArray[1], true
        );
        info.toShape.initPortalPosAxisShape(
            portalArray[2], false
        );
        info.toShape.initPortalPosAxisShape(
            portalArray[3], true
        );
        
        portalArray[0].dimensionTo = info.to;
        portalArray[1].dimensionTo = info.to;
        portalArray[2].dimensionTo = info.from;
        portalArray[3].dimensionTo = info.from;
        
        Vec3d offset = new Vec3d(info.toShape.innerAreaBox.l.subtract(
            info.fromShape.innerAreaBox.l
        ));
        portalArray[0].destination = portalArray[0].getPositionVec().add(offset);
        portalArray[1].destination = portalArray[1].getPositionVec().add(offset);
        portalArray[2].destination = portalArray[2].getPositionVec().subtract(offset);
        portalArray[3].destination = portalArray[3].getPositionVec().subtract(offset);
        
        fromWorld1.addEntity(portalArray[0]);
        fromWorld1.addEntity(portalArray[1]);
        toWorld.addEntity(portalArray[2]);
        toWorld.addEntity(portalArray[3]);
    }
    
    public static Stream<BlockPos> fromNearToFarColumned(
        ServerWorld world,
        int x,
        int z,
        int range
    ) {
        if (range < 0) {
            range = 5;
        }
        
        if (Global.blameOpenJdk) {
            return blockPosStreamNaive(
                world, x, z, range
            );
        }
        
        int height = world.getActualHeight();
        
        BlockPos.Mutable temp0 = new BlockPos.Mutable();
        BlockPos.Mutable temp2 = new BlockPos.Mutable();
        BlockPos.Mutable temp1 = new BlockPos.Mutable();
        
        return IntStream.range(0, range).boxed()
            .flatMap(layer ->
                Streams.concat(
                    IntStream.range(0, layer * 2 + 1)
                        .mapToObj(i -> new BlockPos(layer, 0, i - layer)),
                    IntStream.range(0, layer * 2 + 1)
                        .mapToObj(i -> new BlockPos(-layer, 0, i - layer)),
                    IntStream.range(0, layer * 2 - 1)
                        .mapToObj(i -> new BlockPos(i - layer + 1, 0, layer)),
                    IntStream.range(0, layer * 2 - 1)
                        .mapToObj(i -> new BlockPos(i - layer + 1, 0, -layer))
                ).map(
                    columnPos_ -> new BlockPos(columnPos_.getX() + x, 0, columnPos_.getZ() + z)
                ).flatMap(
                    columnPos_ ->
                        IntStream.range(3, height - 3)
                            .mapToObj(y -> new BlockPos(columnPos_.getX(), y, columnPos_.getZ()))
                )
            );
    }
    
}

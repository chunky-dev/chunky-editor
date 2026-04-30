package io.github.notstirred.chunkyeditor.state.vanilla;

import io.github.notstirred.chunkyeditor.Accessor;
import io.github.notstirred.chunkyeditor.Editor;
import io.github.notstirred.chunkyeditor.VanillaRegionPos;
import io.github.notstirred.chunkyeditor.minecraft.WorldLock;
import io.github.notstirred.chunkyeditor.state.State;
import javafx.application.Platform;
import se.llbit.chunky.world.*;
import se.llbit.chunky.world.region.MCRegion;
import se.llbit.chunky.world.region.Region;
import se.llbit.log.Log;
import se.llbit.util.annotation.Nullable;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class VanillaWorldState {
    public static final int HEADER_SIZE_BYTES = 4096;

    private final Path regionDirectory;
    private final World world;
    private final WorldLock worldLock;

    private final VanillaStateTracker stateTracker;

    public VanillaWorldState(World world, WorldLock worldLock) throws FileNotFoundException {
        this.regionDirectory = world.getWorldDirectory().toPath().resolve("region");
        this.world = world;
        this.worldLock = worldLock;

        this.stateTracker = new VanillaStateTracker(regionDirectory);
    }

    /**
     * @return Null if the future failed to start because of user input or other error.
     */
    @Nullable
    public CompletableFuture<Void> deleteChunks(Executor taskExecutor, Collection<ChunkPosition> chunks) {
        if (!worldLock.tryLock()) {
            return null;
        }

        Map<VanillaRegionPos, List<ChunkPosition>> regionSelection = new HashMap<>();
        for (ChunkPosition chunkPosition : chunks) {
            ChunkPosition asRegionPos = chunkPosition.getRegionPosition();

            regionSelection.computeIfAbsent(new VanillaRegionPos(asRegionPos.x, asRegionPos.z), pos -> new ArrayList<>())
                    .add(chunkPosition);
        }
        List<VanillaRegionPos> regions = new ArrayList<>(regionSelection.keySet());

        try {
            // we first overwrite the current snapshot if it exists, ready to be undone
            if (stateTracker.hasState()) {
                stateTracker.snapshotCurrentState(regions);
            } else {
                // otherwise we just take a normal snapshot instead
                stateTracker.snapshotState(regions);
            }
        } catch (IOException e) {
            Log.warn("Could not take snapshot of regions, aborting.", e);
            return null; // We haven't started yet, so can safely cancel
        }

        CompletableFuture<Void> deletionFuture = this.deleteChunks(taskExecutor, regionSelection);
        // deletion is now complete, we MUST NOT fail to snapshot and exit or risk an invalid state for the user

        return deletionFuture.whenComplete((v, throwable) -> {
            // take snapshot of new state to warn user if anything changed when they press undo
            try {
                stateTracker.snapshotStateNoFail(regions);
            } catch (IOException e) {
                // failed to snapshot some regions? add the exception and continue.
                if (throwable != null) {
                    e.addSuppressed(throwable);
                }
                throwable = new IOException("Failed to take a complete snapshot after deleting chunks.\nThe chunks HAVE been deleted.", e);
            }
            if (throwable != null) {
                throw new RuntimeException(throwable);
            }
        });
    }

    private CompletableFuture<Void> deleteChunks(Executor taskExecutor, Map<VanillaRegionPos, List<ChunkPosition>> regionSelection) {
        // actually "delete" the chunks, suppressing all errors and outputting them together at the end.
        CompletableFuture<Void> deletionFuture = CompletableFuture.runAsync(() -> {
            IOException suppressed = null;
            for (Map.Entry<VanillaRegionPos, List<ChunkPosition>> entry : regionSelection.entrySet()) {
                VanillaRegionPos regionPos = entry.getKey();
                List<ChunkPosition> chunkPositions = entry.getValue();
                File regionFile = this.regionDirectory.resolve(regionPos.fileName()).toFile();

                try (RandomAccessFile file = new RandomAccessFile(regionFile, "rw")) {
                    long length = file.length();
                    if (length < 2 * HEADER_SIZE_BYTES) {
                        Log.warn("Missing header in region file, despite trying to delete chunks from it?!\nThis is really bad");
                        continue;
                    }

                    for (ChunkPosition chunkPos : chunkPositions) {
                        int x = chunkPos.x & 31;
                        int z = chunkPos.z & 31;
                        int index = x + z * 32;

                        file.seek(4 * index);
                        file.writeInt(0);
                    }
                } catch (IOException e) {
                    if (suppressed == null) {
                        suppressed = e;
                    } else {
                        suppressed.addSuppressed(e);
                    }
                }
            }

            // rethrow suppressed exceptions
            if (suppressed != null) {
                throw new UncheckedIOException(suppressed);
            }
        }, taskExecutor);

        // update the map view with the newly deleted chunks
        deletionFuture.whenCompleteAsync((_, throwable) ->
                regionSelection.forEach((regionPos, chunkPositions) -> {
                    Dimension dimension = world.currentDimension();
                    Region region = dimension.getRegion(new ChunkPosition(regionPos.x(), regionPos.z()));
                    for (ChunkPosition chunkPos : chunkPositions) {
                        Chunk chunk = dimension.getChunk(chunkPos);
                        if (!chunk.isEmpty()) {
                            chunk.reset();
                            Accessor.invoke_MCRegion$setChunk((MCRegion) region, chunkPos, EmptyChunk.INSTANCE);
                            dimension.chunkUpdated(chunkPos);
                            dimension.chunkDeleted(chunkPos);
                        }
                    }
                }), Platform::runLater
        );

        return deletionFuture;
    }

    /**
     * @return Null if the future failed to start because of user input or other error.
     */
    @Nullable
    public CompletableFuture<Void> undo(Executor taskExecutor) {
        if (!this.stateTracker.hasPreviousState())
            return null;
        if (!worldLock.tryLock())
            return null;

        List<VanillaRegionPos> writtenRegions = new ArrayList<>();

        CompletableFuture<Void> undoFuture = CompletableFuture.runAsync(() -> {
            IOException suppressed = null;
            for (Map.Entry<VanillaRegionPos, State> entry : this.stateTracker.previousState().getStates().entrySet()) {
                VanillaRegionPos position = entry.getKey();
                State state = entry.getValue();
                try {
                    //TODO: only write to regions modified since the snapshot was taken
                    Path regionPath = this.regionDirectory.resolve(position.fileName());
                    state.writeState(regionPath);
                    writtenRegions.add(position);
                } catch (IOException e) {
                    if (suppressed == null) {
                        suppressed = e;
                    } else {
                        suppressed.addSuppressed(e);
                    }
                }
            }
            // rethrow suppressed exceptions
            if (suppressed != null) {
                throw new UncheckedIOException(suppressed);
            }
        }, taskExecutor);

        undoFuture.whenCompleteAsync((v, throwable) ->
                writtenRegions.forEach(regionPos ->
                        Editor.INSTANCE.mapLoader().regionUpdated(new ChunkPosition(regionPos.x(), regionPos.z()))), Platform::runLater);
        return undoFuture;
    }

    public VanillaStateTracker getStateTracker() {
        return this.stateTracker;
    }
}

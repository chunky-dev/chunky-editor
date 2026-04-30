package io.github.notstirred.chunkyeditor.minecraft;

import se.llbit.util.annotation.NotNull;
import se.llbit.util.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.Function;

/**
 * This class isn't really a lock at all, but it first asks the user if they are sure they wish to modify their world.
 * If they say yes, the world lockfile timestamp is recorded.
 * If that ever changes the user is <u>bombarded</u> with additional LARGER warnings.
 */
public class WorldLock {
    @NotNull
    private final File lockFile;

    /**
     * The time the lock was validated. If the lock timestamp is after this then minecraft has opened the world.
     */
    @Nullable
    private ZonedDateTime validTime = null;

    private final Function<Boolean, Boolean> confirmFunction;
    private boolean isFirstConfirm = true;

    private WorldLock(@NotNull File lockFile, Function<Boolean, Boolean> userConfirmationFunction) {
        this.lockFile = lockFile;
        this.confirmFunction = userConfirmationFunction;
    }

    private boolean isValid() {
        if (this.validTime == null) {
            return false;
        }

        ZonedDateTime lockTimestamp = getLockTimestamp(this.lockFile);

        return this.validTime.isAfter(lockTimestamp);
    }

    /**
     * Check if the file is untouched, ask the user for confirmation if it has been modified (world has been opened externally)
     *
     * @return True if locked, false if not.
     */
    public boolean tryLock() {
        if (isValid()) {
            return true;
        }

        boolean isFirstConfirm = this.isFirstConfirm;
        this.isFirstConfirm = false;
        if (!this.confirmFunction.apply(isFirstConfirm)) {
            return false;
        }

        this.validTime = ZonedDateTime.now();

        return true;
    }

    @NotNull
    private static ZonedDateTime getLockTimestamp(@NotNull File lockFile) {
        long lastModified = lockFile.lastModified();

        ZonedDateTime timeNow = ZonedDateTime.now();
        int offset = timeNow.getOffset().getTotalSeconds();

        return ZonedDateTime.of(
                LocalDateTime.ofEpochSecond(lastModified / 1000, 0, ZoneOffset.ofTotalSeconds(offset)),
                timeNow.getZone()
        );
    }

    public static WorldLock of(@NotNull Path worldPath, Function<Boolean, Boolean> userConfirmationFunction) throws FileNotFoundException {
        Path sessionLockFile = worldPath.resolve("session.lock");

        File lockFile = sessionLockFile.toFile();
        return new WorldLock(lockFile, userConfirmationFunction);
    }
}

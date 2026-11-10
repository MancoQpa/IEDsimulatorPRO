package com.ied.simulator.cache;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Room Entity for cached model metadata
 * Stores information about cached SCL/CID files
 */
@Entity(tableName = "cached_models")
public class CachedModelEntity {

    @PrimaryKey
    @NonNull
    public String fileIdentifier;  // Unique ID (URI or filename)

    public String fileHash;        // MD5 hash of file content
    public String fileName;        // Display name
    public long cachedTimestamp;   // When it was cached
    public int nodeCount;          // Total nodes in model
    public int ldCount;            // Logical Device count
    public int lnCount;            // Logical Node count

    public CachedModelEntity() {
        this.fileIdentifier = "";
    }

    public CachedModelEntity(@NonNull String fileIdentifier, String fileHash, String fileName) {
        this.fileIdentifier = fileIdentifier;
        this.fileHash = fileHash;
        this.fileName = fileName;
        this.cachedTimestamp = System.currentTimeMillis();
    }
}

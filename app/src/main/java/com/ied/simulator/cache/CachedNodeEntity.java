package com.ied.simulator.cache;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * Room Entity for cached model nodes
 * Stores the hierarchical structure: LD -> LN -> DO -> DA
 */
@Entity(
    tableName = "cached_nodes",
    primaryKeys = {"modelId", "reference", "fc"},
    foreignKeys = @ForeignKey(
        entity = CachedModelEntity.class,
        parentColumns = "fileIdentifier",
        childColumns = "modelId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = {
        @Index("modelId"),
        @Index("parentRef"),
        @Index("nodeType")
    }
)
public class CachedNodeEntity {

    @NonNull
    public String modelId;      // Foreign key to CachedModelEntity

    @NonNull
    public String reference;    // Full reference path (e.g., "LD1/LN0.DO.DA")

    @NonNull
    public String fc;           // Functional Constraint (ST, MX, CF, etc.) or "NONE" for LD/LN

    public String name;         // Node name
    public String parentRef;    // Parent reference (null for root LDs)
    public String nodeType;     // "LD", "LN", "DO", "DA", "BDA"
    public String dataType;     // For DA/BDA: "Boolean", "Int32", "Float32", etc.
    public String value;        // Last known value (optional)
    public int childCount;      // Number of children
    public int depth;           // Depth in tree (0=LD, 1=LN, etc.)

    public CachedNodeEntity() {
        this.modelId = "";
        this.reference = "";
        this.fc = "NONE";
    }

    /**
     * Create a Logical Device node
     */
    public static CachedNodeEntity createLD(String modelId, String name) {
        CachedNodeEntity node = new CachedNodeEntity();
        node.modelId = modelId;
        node.name = name;
        node.reference = name;
        node.fc = "NONE";
        node.nodeType = "LD";
        node.parentRef = null;
        node.depth = 0;
        return node;
    }

    /**
     * Create a Logical Node
     */
    public static CachedNodeEntity createLN(String modelId, String ldName, String lnName) {
        CachedNodeEntity node = new CachedNodeEntity();
        node.modelId = modelId;
        node.name = lnName;
        node.reference = ldName + "/" + lnName;
        node.fc = "NONE";
        node.nodeType = "LN";
        node.parentRef = ldName;
        node.depth = 1;
        return node;
    }

    /**
     * Create a Data Object or Data Attribute
     */
    public static CachedNodeEntity createDataNode(String modelId, String parentRef,
                                                   String name, String fc, String nodeType,
                                                   String dataType, int depth) {
        CachedNodeEntity node = new CachedNodeEntity();
        node.modelId = modelId;
        node.name = name;
        node.reference = parentRef + "." + name;
        node.fc = fc != null ? fc : "NONE";
        node.nodeType = nodeType;
        node.parentRef = parentRef;
        node.dataType = dataType;
        node.depth = depth;
        return node;
    }
}

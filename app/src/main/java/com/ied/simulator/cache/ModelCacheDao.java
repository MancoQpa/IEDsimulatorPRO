package com.ied.simulator.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for model cache operations
 */
@Dao
public interface ModelCacheDao {

    // ==================== Model Metadata ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertModel(CachedModelEntity model);

    @Query("SELECT * FROM cached_models WHERE fileIdentifier = :fileId")
    CachedModelEntity getModel(String fileId);

    @Query("SELECT * FROM cached_models WHERE fileHash = :hash")
    CachedModelEntity getModelByHash(String hash);

    @Query("SELECT * FROM cached_models ORDER BY cachedTimestamp DESC")
    List<CachedModelEntity> getAllModels();

    @Query("DELETE FROM cached_models WHERE fileIdentifier = :fileId")
    void deleteModel(String fileId);

    @Query("DELETE FROM cached_models")
    void deleteAllModels();

    @Query("SELECT COUNT(*) FROM cached_models")
    int getModelCount();

    // ==================== Model Nodes ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertNode(CachedNodeEntity node);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertNodes(List<CachedNodeEntity> nodes);

    @Query("SELECT * FROM cached_nodes WHERE modelId = :modelId ORDER BY depth, reference")
    List<CachedNodeEntity> getNodesForModel(String modelId);

    @Query("SELECT * FROM cached_nodes WHERE modelId = :modelId AND nodeType = 'LD'")
    List<CachedNodeEntity> getLogicalDevices(String modelId);

    @Query("SELECT * FROM cached_nodes WHERE modelId = :modelId AND parentRef = :parentRef")
    List<CachedNodeEntity> getChildren(String modelId, String parentRef);

    @Query("SELECT * FROM cached_nodes WHERE modelId = :modelId AND reference = :reference AND fc = :fc")
    CachedNodeEntity getNode(String modelId, String reference, String fc);

    @Query("SELECT * FROM cached_nodes WHERE modelId = :modelId AND nodeType = :nodeType")
    List<CachedNodeEntity> getNodesByType(String modelId, String nodeType);

    @Query("SELECT * FROM cached_nodes WHERE modelId = :modelId AND fc = :fc")
    List<CachedNodeEntity> getNodesByFc(String modelId, String fc);

    @Query("UPDATE cached_nodes SET value = :value WHERE modelId = :modelId AND reference = :reference AND fc = :fc")
    void updateNodeValue(String modelId, String reference, String fc, String value);

    @Query("DELETE FROM cached_nodes WHERE modelId = :modelId")
    void deleteNodesForModel(String modelId);

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE modelId = :modelId")
    int getNodeCount(String modelId);

    // ==================== Statistics ====================

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE modelId = :modelId AND nodeType = 'LD'")
    int getLdCount(String modelId);

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE modelId = :modelId AND nodeType = 'LN'")
    int getLnCount(String modelId);

    @Query("SELECT COUNT(*) FROM cached_nodes WHERE modelId = :modelId AND nodeType = 'DA'")
    int getDaCount(String modelId);
}

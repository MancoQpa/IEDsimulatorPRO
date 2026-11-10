package com.ied.simulator.cache;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room Database for caching IEC 61850 model structures
 * Acts as "virtual memory" - parsed models are stored here for fast retrieval
 */
@Database(
    entities = {CachedModelEntity.class, CachedNodeEntity.class},
    version = 1,
    exportSchema = false
)
public abstract class ModelCacheDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "model_cache.db";
    private static volatile ModelCacheDatabase INSTANCE;

    public abstract ModelCacheDao modelCacheDao();

    /**
     * Get singleton instance of the database
     */
    public static ModelCacheDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ModelCacheDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            ModelCacheDatabase.class,
                            DATABASE_NAME
                    )
                    .fallbackToDestructiveMigration()  // Clear DB on schema change
                    .build();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Close the database (call on app exit if needed)
     */
    public static void closeDatabase() {
        if (INSTANCE != null && INSTANCE.isOpen()) {
            INSTANCE.close();
            INSTANCE = null;
        }
    }
}

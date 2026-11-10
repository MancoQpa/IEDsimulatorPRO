package com.ied.simulator;

import android.content.Context;
import android.util.Log;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.Fc;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.SclParseException;
import com.beanit.iec61850bean.ServerModel;
import com.ied.simulator.cache.CachedModelEntity;
import com.ied.simulator.cache.CachedNodeEntity;
import com.ied.simulator.cache.ModelCacheDao;
import com.ied.simulator.cache.ModelCacheDatabase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Cache Manager for SCL/CID models using Room SQLite
 *
 * Strategy:
 * 1. First load: Parse SCL → Extract structure → Save to SQLite
 * 2. Subsequent loads with same file: Load structure from SQLite instantly
 * 3. ServerModel still needs parsing for MMS, but UI shows cached structure immediately
 *
 * This provides "virtual memory" behavior - structure persists across app restarts
 */
public class ModelCacheManager {

    private static final String TAG = "ModelCacheManager";

    private final Context context;
    private final ModelCacheDatabase database;
    private final ModelCacheDao dao;

    public ModelCacheManager(Context context) {
        this.context = context;
        this.database = ModelCacheDatabase.getInstance(context);
        this.dao = database.modelCacheDao();
    }

    /**
     * Parse an SCL file and return ALL server models it contains.
     * SCD/CID files can describe several IEDs (or AccessPoints) - the caller
     * decides which one to use; nothing is discarded here.
     *
     * @param inputStream The SCL file input stream
     * @return List of ServerModel objects (one per IED/AccessPoint)
     */
    public List<ServerModel> parseModels(InputStream inputStream) throws SclParseException {
        return parseModels(inputStream, null);
    }

    /**
     * Igual que parseModels(), pero ademas deja en iedNamesOut el nombre de cada
     * IED tal como lo declara el SCL, para poder etiquetar el selector de
     * dispositivo con el nombre del equipo y no con el de su primer LD.
     */
    public List<ServerModel> parseModels(InputStream inputStream, List<String> iedNamesOut)
            throws SclParseException {
        long startTime = System.currentTimeMillis();

        try {
            Log.i(TAG, "Parsing SCL file directly...");
            List<ServerModel> models = SclFileParser.parse(inputStream, iedNamesOut);

            if (models == null || models.isEmpty()) {
                throw new SclParseException("No models found in SCL file");
            }

            long parseTime = System.currentTimeMillis() - startTime;
            Log.i(TAG, "Parsed " + models.size() + " model(s) in " + parseTime + "ms");
            return models;

        } catch (Exception e) {
            Log.e(TAG, "Error loading model", e);
            if (e instanceof SclParseException) {
                throw (SclParseException) e;
            }
            throw new SclParseException("Failed to load model: " + e.getMessage());
        }
    }

    /**
     * Cache the structure of the selected model in background (non-blocking)
     */
    public void cacheModelAsync(ServerModel model, String fileIdentifier) {
        final String cacheKey = sanitizeKey(fileIdentifier);
        final String fileName = fileIdentifier.substring(
                Math.max(0, fileIdentifier.lastIndexOf('/') + 1));

        new Thread(() -> {
            try {
                cacheModelStructure(model, cacheKey, "no-hash", fileName);
            } catch (Exception e) {
                Log.e(TAG, "Error caching model", e);
            }
        }).start();
    }

    /**
     * Cache the model structure to SQLite
     */
    private void cacheModelStructure(ServerModel model, String cacheKey, String fileHash, String fileName) {
        long startTime = System.currentTimeMillis();

        try {
            // Delete old cache for this file
            dao.deleteModel(cacheKey);

            // Create model metadata
            CachedModelEntity modelEntity = new CachedModelEntity(cacheKey, fileHash, fileName);

            // Extract all nodes
            List<CachedNodeEntity> nodes = new ArrayList<>();
            int ldCount = 0, lnCount = 0;

            for (ModelNode ld : model.getChildren()) {
                ldCount++;
                // Add Logical Device
                CachedNodeEntity ldNode = CachedNodeEntity.createLD(cacheKey, ld.getName());
                ldNode.childCount = ld.getChildren().size();
                nodes.add(ldNode);

                for (ModelNode ln : ld.getChildren()) {
                    lnCount++;
                    // Add Logical Node
                    CachedNodeEntity lnNode = CachedNodeEntity.createLN(cacheKey, ld.getName(), ln.getName());
                    lnNode.childCount = ln.getChildren().size();
                    nodes.add(lnNode);

                    // Add Data Objects and Attributes (limit depth for performance)
                    extractDataNodes(nodes, cacheKey, lnNode.reference, ln, 2);
                }
            }

            modelEntity.ldCount = ldCount;
            modelEntity.lnCount = lnCount;
            modelEntity.nodeCount = nodes.size();

            // Save to database
            dao.insertModel(modelEntity);
            dao.insertNodes(nodes);

            long elapsed = System.currentTimeMillis() - startTime;
            Log.i(TAG, "Cached " + nodes.size() + " nodes in " + elapsed + "ms");

        } catch (Exception e) {
            Log.e(TAG, "Error caching model structure", e);
        }
    }

    /**
     * Recursively extract data nodes (DO, DA, BDA)
     */
    private void extractDataNodes(List<CachedNodeEntity> nodes, String modelId,
                                  String parentRef, ModelNode parent, int depth) {
        if (depth > 5) return; // Limit recursion depth

        Collection<ModelNode> deEsteNivel = parent.getChildren();
        if (deEsteNivel == null) return;   // nodo hoja: getChildren() es null
        for (ModelNode child : deEsteNivel) {
            String fc = "NONE";
            String nodeType = "DO";
            String dataType = null;

            if (child instanceof FcModelNode) {
                Fc childFc = ((FcModelNode) child).getFc();
                fc = childFc != null ? childFc.name() : "NONE";
            }

            // OJO: en iec61850bean getChildren() devuelve NULL en los nodos hoja
            // (BasicDataAttribute), no una coleccion vacia. Llamarlo sin
            // proteccion lanzaba NPE y el cacheo fallaba SIEMPRE, en silencio:
            // la excepcion se atrapaba mas arriba y solo quedaba un log de error,
            // asi que la app funcionaba pero el cache nunca se poblaba.
            Collection<ModelNode> hijos = child.getChildren();
            int cantHijos = (hijos != null) ? hijos.size() : 0;

            if (child instanceof BasicDataAttribute) {
                nodeType = "DA";
                dataType = child.getClass().getSimpleName().replace("Bda", "");
            } else if (cantHijos == 0) {
                nodeType = "DA";
            }

            CachedNodeEntity node = CachedNodeEntity.createDataNode(
                    modelId, parentRef, child.getName(), fc, nodeType, dataType, depth);
            node.childCount = cantHijos;
            nodes.add(node);

            // Recurse for children
            if (cantHijos > 0) {
                extractDataNodes(nodes, modelId, node.reference, child, depth + 1);
            }
        }
    }

    /**
     * Get cached model structure (for quick UI display)
     */
    public List<CachedNodeEntity> getCachedStructure(String fileIdentifier) {
        String cacheKey = sanitizeKey(fileIdentifier);
        return dao.getNodesForModel(cacheKey);
    }

    /**
     * Get cached Logical Devices
     */
    public List<CachedNodeEntity> getCachedLogicalDevices(String fileIdentifier) {
        String cacheKey = sanitizeKey(fileIdentifier);
        return dao.getLogicalDevices(cacheKey);
    }

    /**
     * Get children of a node
     */
    public List<CachedNodeEntity> getCachedChildren(String fileIdentifier, String parentRef) {
        String cacheKey = sanitizeKey(fileIdentifier);
        return dao.getChildren(cacheKey, parentRef);
    }

    /**
     * Check if file is cached with same hash
     */
    public boolean isCached(String fileIdentifier) {
        String cacheKey = sanitizeKey(fileIdentifier);
        CachedModelEntity model = dao.getModel(cacheKey);
        return model != null;
    }

    /**
     * Check if file hash matches cached version
     */
    public boolean isCacheValid(String fileIdentifier, byte[] fileBytes) {
        String cacheKey = sanitizeKey(fileIdentifier);
        String fileHash = calculateMD5(fileBytes);
        CachedModelEntity model = dao.getModel(cacheKey);
        return model != null && fileHash.equals(model.fileHash);
    }

    /**
     * Get cache statistics
     */
    public String getCacheStats() {
        int modelCount = dao.getModelCount();
        List<CachedModelEntity> models = dao.getAllModels();

        StringBuilder sb = new StringBuilder();
        sb.append("Cached models: ").append(modelCount);

        if (!models.isEmpty()) {
            sb.append("\n");
            for (CachedModelEntity m : models) {
                sb.append("  - ").append(m.fileName)
                  .append(" (").append(m.nodeCount).append(" nodes)\n");
            }
        }

        return sb.toString();
    }

    /**
     * Clear all cached models
     */
    public void clearCache() {
        dao.deleteAllModels();
        Log.i(TAG, "Cache cleared");
    }

    /**
     * Calculate MD5 hash
     */
    private String calculateMD5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(data.length);
        }
    }

    /**
     * Read all bytes from input stream
     */
    private byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    /**
     * Sanitize cache key (remove special characters)
     */
    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}

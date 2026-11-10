package com.ied.simulator;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.beanit.iec61850bean.BasicDataAttribute;
import com.beanit.iec61850bean.BdaBoolean;
import com.beanit.iec61850bean.BdaDoubleBitPos;
import com.beanit.iec61850bean.BdaCheck;
import com.beanit.iec61850bean.BdaTapCommand;
import com.beanit.iec61850bean.FcModelNode;
import com.beanit.iec61850bean.LogicalDevice;
import com.beanit.iec61850bean.LogicalNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.ServerModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ModelTreeAdapter extends RecyclerView.Adapter<ModelTreeAdapter.ViewHolder> {

    public enum NodeType {
        LOGICAL_DEVICE,
        LOGICAL_NODE,
        DATA_OBJECT,
        DATA_ATTRIBUTE
    }

    // Value types for dropdown selection
    public enum ValueType {
        TEXT,       // Regular text input
        BOOLEAN,    // true/false dropdown
        DBPOS,      // off/on/intermediate/bad dropdown
        TAPCOMMAND, // stop/lower/higher/reserved dropdown
        CHECK       // synchrocheck/interlockcheck dropdown
    }

    public static class TreeNode {
        public String name;
        public String reference;
        public String value;
        public NodeType type;
        public int level;
        public boolean expanded;
        public boolean editable;
        public String fc;       // Functional Constraint (ST, MX, CO, etc.)
        public String bdaType;  // BDA Type (Float32, Boolean, Int32, etc.)
        public ValueType valueType = ValueType.TEXT; // Type of value editor to show
        public String lnClass;  // LN class for coloring (XCBR, XSWI, MMXU, etc.)

        /**
         * Logical Node de origen, solo en los nodos de nivel 1.
         *
         * El arbol se arma perezoso: de entrada se crean unicamente los Logical
         * Devices y sus Logical Nodes, y el subarbol de cada LN (sus Data Objects
         * y atributos) se construye recien cuando el usuario lo expande. En el
         * modelo de un relé real eso baja el armado inicial de 92.527 nodos a 193.
         */
        public LogicalNode fuenteLn;
        /** true si ya se generaron los hijos de este LN. */
        public boolean hijosListos;

        public TreeNode(String name, String reference, NodeType type, int level) {
            this.name = name;
            this.reference = reference;
            this.type = type;
            this.level = level;
            this.expanded = false; // Start collapsed by default
            this.editable = type == NodeType.DATA_ATTRIBUTE;
        }
    }

    public interface OnValueChangeListener {
        void onValueChanged(String nodeRef, String newValue);
    }

    public interface OnLongClickListener {
        boolean onLongClick(TreeNode node);
    }

    // Dropdown options
    private static final String[] BOOLEAN_OPTIONS = {"false", "true"};
    private static final String[] DBPOS_OPTIONS = {"off", "on", "intermediate", "bad"};
    private static final String[] TAPCOMMAND_OPTIONS = {"stop", "lower", "higher", "reserved"};
    private static final String[] CHECK_OPTIONS = {"false", "true"};

    // Simulation mode allows editing MX and ST values
    private static boolean simulationMode = true;

    public static void setSimulationMode(boolean enabled) {
        simulationMode = enabled;
    }

    public static boolean isSimulationMode() {
        return simulationMode;
    }

    private Context context;
    private List<TreeNode> allNodes;
    private List<TreeNode> visibleNodes;
    private Map<String, TreeNode> nodeMap;
    private OnValueChangeListener valueChangeListener;
    private OnLongClickListener longClickListener;
    private String selectedReference = null;

    public ModelTreeAdapter(Context context, List<TreeNode> nodes) {
        this.context = context;
        this.allNodes = nodes;
        this.visibleNodes = new ArrayList<>(nodes);
        this.nodeMap = new HashMap<>();
        for (TreeNode node : nodes) {
            nodeMap.put(node.reference, node);
        }
    }

    public void setOnValueChangeListener(OnValueChangeListener listener) {
        this.valueChangeListener = listener;
    }

    public void setOnLongClickListener(OnLongClickListener listener) {
        this.longClickListener = listener;
    }

    /**
     * Update tree data - nodeMap should be pre-built for large trees
     */
    public void updateData(List<TreeNode> nodes) {
        this.allNodes = nodes;
        // nodeMap will be built lazily or by updateDataWithMap
        this.nodeMap.clear();
        for (TreeNode node : nodes) {
            nodeMap.put(node.reference, node);
        }
        refreshVisibleNodes();
        notifyDataSetChanged();
    }

    /**
     * Update tree data with pre-built nodeMap (faster for large trees)
     * Call this from background thread after building both nodes and nodeMap
     */
    public void updateDataWithMap(List<TreeNode> nodes, Map<String, TreeNode> prebuiltMap) {
        this.allNodes = nodes;
        this.nodeMap = prebuiltMap;
        refreshVisibleNodes();
        notifyDataSetChanged();
    }

    public void updateNodeValue(String reference, String value) {
        // First try exact match
        TreeNode node = nodeMap.get(reference);

        // If not found, convert reference formats and search
        if (node == null) {
            // Normalize the incoming reference to match tree format
            String normalizedRef = normalizeReference(reference);

            // Try exact match with normalized reference
            node = nodeMap.get(normalizedRef);

            // If still not found, search by base reference (without FC)
            if (node == null) {
                String baseRef = getBaseReference(normalizedRef);

                for (Map.Entry<String, TreeNode> entry : nodeMap.entrySet()) {
                    String nodeBaseRef = getBaseReference(entry.getKey());
                    if (nodeBaseRef.equals(baseRef)) {
                        node = entry.getValue();
                        break;
                    }
                }
            }
        }

        if (node != null) {
            node.value = value;
            int position = visibleNodes.indexOf(node);
            if (position >= 0) {
                notifyItemChanged(position);
            }
        }
    }

    /**
     * Normalize reference to tree format: "LD/LN.DO.attr$FC"
     * Handles old format: "LD/LN$FC$DO$DA" -> "LD/LN.DO.DA$FC"
     * And new format: "LD/LN.DO.DA" (adds default FC if missing)
     */
    private String normalizeReference(String ref) {
        if (ref == null) return null;

        // Check for old format: contains $ followed by FC followed by more $
        // Pattern: "LD/LN$FC$DO$DA$..."
        if (ref.contains("$")) {
            String[] parts = ref.split("\\$");
            if (parts.length >= 3) {
                // Check if second part is a valid FC
                String possibleFc = parts[1];
                if (isValidFc(possibleFc)) {
                    // Old format: "LD/LN$FC$DO$DA" -> "LD/LN.DO.DA$FC"
                    StringBuilder sb = new StringBuilder(parts[0]); // LD/LN
                    for (int i = 2; i < parts.length; i++) {
                        sb.append(".").append(parts[i]);
                    }
                    sb.append("$").append(possibleFc);
                    return sb.toString();
                }
            }

            // Check if last part is FC (new format already)
            String lastPart = parts[parts.length - 1];
            if (isValidFc(lastPart)) {
                return ref; // Already in correct format
            }
        }

        // No FC in reference - return as-is (will match by base ref)
        return ref;
    }

    /**
     * Get base reference without FC suffix
     */
    private String getBaseReference(String ref) {
        if (ref == null) return null;
        if (ref.contains("$")) {
            return ref.substring(0, ref.lastIndexOf("$"));
        }
        return ref;
    }

    /**
     * Check if string is a valid Functional Constraint
     */
    private boolean isValidFc(String fc) {
        if (fc == null || fc.length() != 2) return false;
        return "ST".equals(fc) || "MX".equals(fc) || "CO".equals(fc) || "SP".equals(fc) ||
               "SG".equals(fc) || "SE".equals(fc) || "SV".equals(fc) || "CF".equals(fc) ||
               "DC".equals(fc) || "EX".equals(fc) || "SR".equals(fc) || "OR".equals(fc) ||
               "BL".equals(fc) || "RP".equals(fc) || "LG".equals(fc) || "BR".equals(fc) ||
               "GO".equals(fc) || "GS".equals(fc) || "MS".equals(fc) || "US".equals(fc);
    }

    /**
     * Get the current value for a reference
     */
    public String getValueForReference(String reference) {
        TreeNode node = nodeMap.get(reference);
        return node != null ? node.value : null;
    }

    /**
     * Refresh values from ServerModel - FAST update without rebuilding tree
     * Only updates values in existing nodes, preserves structure
     */
    public void refreshValuesFromModel(ServerModel model) {
        if (model == null || allNodes.isEmpty()) return;

        // FAST PATH: Just update values in existing nodes without rebuilding
        boolean anyChanged = false;
        for (TreeNode node : allNodes) {
            if (node.type == NodeType.DATA_ATTRIBUTE && node.reference != null) {
                String newValue = getValueFromModel(model, node.reference, node.fc);
                if (newValue != null && !newValue.equals(node.value)) {
                    node.value = newValue;
                    anyChanged = true;
                }
            }
        }

        // Only notify if something changed
        if (anyChanged) {
            notifyDataSetChanged();
        }
    }

    /**
     * Get value from model for a given reference
     * Handles reference format: "LD/LN.DO.attr$FC"
     */
    private String getValueFromModel(ServerModel model, String reference, String fc) {
        try {
            // Parse reference: "LD/LN.DO.attr$FC" -> clean ref + FC
            String cleanRef = reference;
            String fcStr = fc;

            if (cleanRef.contains("$")) {
                int idx = cleanRef.lastIndexOf("$");
                fcStr = cleanRef.substring(idx + 1);
                cleanRef = cleanRef.substring(0, idx);
            }

            if (fcStr == null || fcStr.isEmpty()) {
                fcStr = "ST";
            }

            com.beanit.iec61850bean.Fc fcEnum = com.beanit.iec61850bean.Fc.valueOf(fcStr);
            ModelNode node = model.findModelNode(cleanRef, fcEnum);

            if (node instanceof BasicDataAttribute) {
                return getDisplayValue((BasicDataAttribute) node);
            }
        } catch (Exception e) {
            // Ignore - node may not exist or FC invalid
        }
        return null;
    }

    /**
     * Crea los hijos de un Logical Node si todavia no existen.
     *
     * Se llama al expandir. Los nodos nuevos se insertan justo detras del LN para
     * que refreshVisibleNodes(), que recorre allNodes en orden y se apoya en el
     * nivel y en la referencia del padre, los vea en el lugar correcto.
     */
    private void materializarHijos(TreeNode node) {
        if (node == null || node.type != NodeType.LOGICAL_NODE) return;
        if (node.hijosListos || node.fuenteLn == null) return;

        List<TreeNode> hijos = construirSubarbolDeLn(node);
        node.hijosListos = true;
        if (hijos.isEmpty()) return;

        int pos = allNodes.indexOf(node);
        if (pos < 0) return;
        allNodes.addAll(pos + 1, hijos);
        for (TreeNode h : hijos) nodeMap.put(h.reference, h);
    }

    /** Materializa todos los Logical Nodes. Necesario antes de expandir todo. */
    private void materializarTodo() {
        // Se copia la lista porque materializarHijos() modifica allNodes.
        for (TreeNode n : new ArrayList<>(allNodes)) {
            materializarHijos(n);
        }
    }

    /**
     * Expand all nodes in the tree
     */
    public void expandAll() {
        prepararExpandirTodo();
        notifyDataSetChanged();
    }

    /**
     * La parte pesada de expandAll(), sin avisarle a la vista.
     *
     * materializarTodo() construye los hijos de cada nodo logico: en un SCD de
     * subestacion son mas de cien mil nodos y bloquea el hilo de interfaz varios
     * segundos. Se separa para poder correrla fuera de ese hilo; quien la llame
     * tiene que hacer notifyDataSetChanged() despues, ya en el hilo de interfaz,
     * y asegurarse de que el RecyclerView no este mostrando la lista mientras
     * tanto -- estas estructuras no son seguras entre hilos.
     */
    public void prepararExpandirTodo() {
        materializarTodo();
        for (TreeNode node : allNodes) {
            node.expanded = true;
        }
        refreshVisibleNodes();
    }

    /** Idem para colapsar. Ver prepararExpandirTodo(). */
    public void prepararColapsarTodo() {
        for (TreeNode node : allNodes) {
            node.expanded = false;
        }
        refreshVisibleNodes();
    }

    /**
     * Collapse all nodes in the tree (show only root level)
     */
    public void collapseAll() {
        for (TreeNode node : allNodes) {
            node.expanded = false;
        }
        refreshVisibleNodes();
        notifyDataSetChanged();
    }

    /**
     * Expand only the first level (Logical Devices)
     */
    public void expandFirstLevel() {
        for (TreeNode node : allNodes) {
            node.expanded = (node.level == 0);
        }
        refreshVisibleNodes();
        notifyDataSetChanged();
    }

    private void refreshVisibleNodes() {
        visibleNodes.clear();

        // OPTIMIZATION: Use iterative approach instead of recursive
        // Track which parent paths are currently visible+expanded
        java.util.Set<String> visibleExpandedPaths = new java.util.HashSet<>();

        for (TreeNode node : allNodes) {
            if (node.level == 0) {
                // Root nodes are always visible
                visibleNodes.add(node);
                if (node.expanded) {
                    visibleExpandedPaths.add(node.reference);
                }
            } else {
                // Check if parent path is in visible+expanded set
                String parentRef = getParentReferenceInternal(node.reference);
                if (parentRef != null && visibleExpandedPaths.contains(parentRef)) {
                    visibleNodes.add(node);
                    if (node.expanded) {
                        // Use base reference without $FC for path tracking
                        String baseRef = node.reference;
                        if (baseRef.contains("$")) {
                            baseRef = baseRef.substring(0, baseRef.lastIndexOf("$"));
                        }
                        visibleExpandedPaths.add(baseRef);
                    }
                }
            }
        }
    }

    /**
     * Get parent reference for visibility check (faster version without nodeMap lookup)
     */
    private String getParentReferenceInternal(String ref) {
        // Remove FC suffix if present
        String cleanRef = ref;
        if (cleanRef.contains("$")) {
            cleanRef = cleanRef.substring(0, cleanRef.lastIndexOf("$"));
        }

        int lastDot = cleanRef.lastIndexOf('.');
        int lastSlash = cleanRef.lastIndexOf('/');
        int lastSeparator = Math.max(lastDot, lastSlash);
        if (lastSeparator > 0) {
            return cleanRef.substring(0, lastSeparator);
        }
        return null;
    }

    // Keep old method for backward compatibility
    private boolean shouldBeVisible(TreeNode node) {
        // Root nodes are always visible
        if (node.level == 0) return true;

        // Find parent and check if expanded
        String parentRef = getParentReference(node.reference);
        TreeNode parent = nodeMap.get(parentRef);
        return parent != null && parent.expanded && shouldBeVisible(parent);
    }

    private String getParentReference(String ref) {
        // First, remove FC suffix if present (e.g., "LD/LN.DO.attr$MX" -> "LD/LN.DO.attr")
        int dollarPos = ref.lastIndexOf('$');
        if (dollarPos > 0) {
            ref = ref.substring(0, dollarPos);
        }

        int lastDot = ref.lastIndexOf('.');
        int lastSlash = ref.lastIndexOf('/');
        int lastSeparator = Math.max(lastDot, lastSlash);
        if (lastSeparator > 0) {
            return ref.substring(0, lastSeparator);
        }
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_tree_node, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        TreeNode node = visibleNodes.get(position);

        // Set indentation
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) holder.itemView.getLayoutParams();
        params.leftMargin = node.level * 32; // 32dp per level

        // Icono: forma segun el tipo de nodo y, en los LN, segun su grupo
        // IEC 61850-7-4. Mismo sistema que IconFactory de IEDNavigator: la
        // FORMA comunica el grupo (escudo=proteccion, medidor=medicion,
        // engranaje=automatico, diamante=sistema) y el COLOR la clase concreta.
        holder.ivIcon.setImageResource(iconForNode(node));
        ImageViewCompat.setImageTintList(holder.ivIcon,
                ColorStateList.valueOf(iconTintForNode(node)));

        // Reset text styles (RecyclerView recycles views)
        holder.tvName.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        holder.tvName.setTypeface(null, Typeface.NORMAL);

        // Set name
        holder.tvName.setText(node.name);

        // === LN CLASS COLORING ===
        // El icono ya lleva el color de TODAS las clases; el nombre se resalta
        // solo en los grupos salientes, para no dejar el arbol entero en negrita.
        if (node.type == NodeType.LOGICAL_NODE && shouldEmphasizeLn(node.lnClass)) {
            holder.tvName.setTextColor(getLnClassColor(node.lnClass));
            holder.tvName.setTypeface(null, Typeface.BOLD);
        }

        // === DA STATE COLORING (on/off/intermediate) ===
        if (node.type == NodeType.DATA_ATTRIBUTE && node.value != null) {
            int stateColor = getStateColor(node.value);
            if (stateColor != 0) {
                holder.tvName.setTextColor(stateColor);
                holder.tvName.setTypeface(null, Typeface.BOLD);
            }
        }

        // === SELECTION: bold the selected node ===
        if (node.reference != null && node.reference.equals(selectedReference)) {
            holder.tvName.setTypeface(null, Typeface.BOLD);
        }

        // Set FC badge with color
        if (node.fc != null && !node.fc.isEmpty()) {
            holder.tvFc.setVisibility(View.VISIBLE);
            holder.tvFc.setText("[" + node.fc + "]");
            // Set badge color based on FC
            int fcColor = getFcColor(node.fc);
            holder.tvFc.setBackgroundColor(ContextCompat.getColor(context, fcColor));
        } else {
            holder.tvFc.setVisibility(View.GONE);
        }

        // Set BDA Type if available
        if (node.bdaType != null && !node.bdaType.isEmpty()) {
            holder.tvBdaType.setVisibility(View.VISIBLE);
            holder.tvBdaType.setText(node.bdaType);
        } else {
            holder.tvBdaType.setVisibility(View.GONE);
        }

        // Handle expandable nodes
        if (hasChildren(node)) {
            holder.ivExpand.setVisibility(View.VISIBLE);
            holder.ivExpand.setRotation(node.expanded ? 90 : 0);
            holder.itemView.setOnClickListener(v -> {
                selectedReference = node.reference;
                node.expanded = !node.expanded;
                holder.ivExpand.setRotation(node.expanded ? 90 : 0);
                if (node.expanded) materializarHijos(node);
                refreshVisibleNodes();
                notifyDataSetChanged();
            });
        } else {
            holder.ivExpand.setVisibility(View.INVISIBLE);
            holder.itemView.setOnClickListener(v -> {
                selectedReference = node.reference;
                notifyDataSetChanged();
            });
        }

        // Handle long click for adding to watch list
        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                return longClickListener.onLongClick(node);
            }
            return false;
        });

        // Handle editable values
        if (node.editable && node.value != null) {
            holder.layoutValue.setVisibility(View.VISIBLE);

            // Show switch state icon for XCBR/CSWI Pos.stVal
            if (isSwitchPositionNode(node) && node.valueType == ValueType.DBPOS) {
                holder.ivSwitchState.setVisibility(View.VISIBLE);
                holder.ivSwitchState.setImageResource(getSwitchStateIcon(node.value));
                ImageViewCompat.setImageTintList(holder.ivSwitchState,
                        ColorStateList.valueOf(getSwitchStateTintColor(node.value)));
            } else {
                holder.ivSwitchState.setVisibility(View.GONE);
                ImageViewCompat.setImageTintList(holder.ivSwitchState, null);
            }

            // Determine if we should show dropdown or text input
            if (node.valueType == ValueType.BOOLEAN || node.valueType == ValueType.DBPOS ||
                    node.valueType == ValueType.TAPCOMMAND || node.valueType == ValueType.CHECK) {

                // Show spinner, hide edit text
                holder.spinnerValue.setVisibility(View.VISIBLE);
                holder.etValue.setVisibility(View.GONE);

                // Setup spinner based on value type
                String[] options = getOptionsForValueType(node.valueType);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                        android.R.layout.simple_spinner_item, options);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                holder.spinnerValue.setAdapter(adapter);

                // Clear listener before setting selection to avoid false triggers
                holder.spinnerValue.setOnItemSelectedListener(null);

                // Set current selection
                int selectedIndex = getIndexForValue(node.valueType, node.value);
                if (selectedIndex >= 0 && selectedIndex < options.length) {
                    holder.spinnerValue.setSelection(selectedIndex, false);
                }

                // Store current value in tag to detect real changes
                holder.spinnerValue.setTag(node.value);

                // Set listener after selection is set (using post to ensure it's set after UI update)
                holder.spinnerValue.post(() -> {
                    holder.spinnerValue.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                            String selectedValue = options[pos];
                            String previousValue = (String) holder.spinnerValue.getTag();

                            // Only trigger if value actually changed
                            if (!selectedValue.equals(previousValue)) {
                                holder.spinnerValue.setTag(selectedValue);
                                node.value = selectedValue;
                                if (valueChangeListener != null) {
                                    valueChangeListener.onValueChanged(node.reference, selectedValue);
                                }
                            }
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {}
                    });
                });

                holder.btnApply.setVisibility(View.GONE);

            } else {
                // Show edit text, hide spinner
                holder.etValue.setVisibility(View.VISIBLE);
                holder.spinnerValue.setVisibility(View.GONE);
                holder.etValue.setText(node.value);
                holder.etValue.setEnabled(true);
                holder.btnApply.setVisibility(View.VISIBLE);

                holder.btnApply.setOnClickListener(v -> {
                    String newValue = holder.etValue.getText().toString();
                    if (valueChangeListener != null) {
                        valueChangeListener.onValueChanged(node.reference, newValue);
                    }
                });
            }

        } else if (node.value != null) {
            // Read-only value display
            holder.layoutValue.setVisibility(View.VISIBLE);

            // Show switch state icon for XCBR/CSWI Pos.stVal (read-only mode too)
            if (isSwitchPositionNode(node) && node.valueType == ValueType.DBPOS) {
                holder.ivSwitchState.setVisibility(View.VISIBLE);
                holder.ivSwitchState.setImageResource(getSwitchStateIcon(node.value));
                ImageViewCompat.setImageTintList(holder.ivSwitchState,
                        ColorStateList.valueOf(getSwitchStateTintColor(node.value)));
            } else {
                holder.ivSwitchState.setVisibility(View.GONE);
                ImageViewCompat.setImageTintList(holder.ivSwitchState, null);
            }

            holder.etValue.setVisibility(View.VISIBLE);
            holder.spinnerValue.setVisibility(View.GONE);
            holder.etValue.setText(node.value);
            holder.etValue.setEnabled(false);
            holder.btnApply.setVisibility(View.GONE);
        } else {
            holder.layoutValue.setVisibility(View.GONE);
            holder.ivSwitchState.setVisibility(View.GONE);
        }
    }

    /**
     * Grupo IEC 61850-7-4 del LN. Delega en ControlPolicy para que el arbol y la
     * politica de control usen EXACTAMENTE la misma descomposicion de nombres.
     *
     * No alcanza con mirar la primera letra del nombre: casi todos los
     * fabricantes prefijan, y ahi el grupo sale mal. Verificado en el emulador
     * con ABB_CID.CID, donde "DRRDRE1" (clase RDRE, grupo R) se clasificaba como
     * D y "SPGGIO1" (clase GGIO, grupo G) como S.
     */
    private static char lnGroup(String lnClass) {
        return ControlPolicy.groupOf(lnClass);
    }

    /**
     * Forma del icono. Sigue el criterio de IconFactory de IEDNavigator: la
     * forma dice el grupo funcional, no la clase exacta.
     */
    private int iconForNode(TreeNode node) {
        switch (node.type) {
            case LOGICAL_DEVICE:  return R.drawable.ic_node_ld;
            case DATA_OBJECT:     return R.drawable.ic_node_do;
            case DATA_ATTRIBUTE:  return R.drawable.ic_node_da;
            case LOGICAL_NODE:
                switch (lnGroup(node.lnClass)) {
                    case 'P':               // proteccion
                    case 'R': return R.drawable.ic_ln_shield;   // relacionada a proteccion
                    case 'M':               // medicion / energia
                    case 'S':               // supervision
                    case 'T':               // transformadores de medida
                    case 'Y': return R.drawable.ic_ln_meter;    // transformador de potencia
                    case 'A': return R.drawable.ic_ln_gear;     // control automatico
                    case 'L':               // sistema (LLN0, LPHD...)
                    case 'G': return R.drawable.ic_ln_diamond;  // genericos (GGIO, GAPC)
                    default:  return R.drawable.ic_ln_node;     // X, C, I, Z y el resto
                }
            default:
                return R.drawable.ic_ln_node;
        }
    }

    /** Color del icono: por clase de LN, o el del tipo de nodo. */
    private int iconTintForNode(TreeNode node) {
        switch (node.type) {
            case LOGICAL_DEVICE: return ContextCompat.getColor(context, R.color.node_ld);
            case DATA_OBJECT:    return ContextCompat.getColor(context, R.color.node_do);
            case DATA_ATTRIBUTE: return ContextCompat.getColor(context, R.color.node_da);
            case LOGICAL_NODE:   return getLnClassColor(node.lnClass);
            default:             return ContextCompat.getColor(context, R.color.text_secondary);
        }
    }

    /**
     * Color de un LN, con los mismos RGB que IconFactory de IEDNavigator.
     *
     * Primero las clases con color propio y despues, como respaldo, el grupo
     * IEC 61850-7-4 (primera letra). Cubre todas las clases: lo usa el tinte del
     * icono, que es lo que lleva la semantica completa.
     */
    private int getLnClassColor(String lnClass) {
        if (lnClass == null || lnClass.isEmpty()) {
            return ContextCompat.getColor(context, R.color.ln_default);
        }
        // Comparar contra la CLASE descompuesta, no contra el nombre crudo:
        // "SCILO1" (ABB) o "CBCILO1" (SCD de subestacion) nunca coincidirian
        // con un startsWith("CILO") sobre el nombre completo.
        String cls = ControlPolicy.classOf(lnClass);
        if (cls == null) cls = lnClass.toUpperCase();
        cls = cls.toUpperCase();

        // Clases con color propio
        if (cls.equals("XCBR")) return ContextCompat.getColor(context, R.color.ln_xcbr);
        if (cls.equals("XSWI")) return ContextCompat.getColor(context, R.color.ln_xswi);
        if (cls.equals("MMXU") || cls.equals("MSQI")) return ContextCompat.getColor(context, R.color.ln_mmxu);
        if (cls.equals("MMTR") || cls.equals("MSTA")) return ContextCompat.getColor(context, R.color.ln_mmtr);
        if (cls.equals("CSWI") || cls.equals("CILO")) return ContextCompat.getColor(context, R.color.ln_cswi);

        // Respaldo por grupo
        switch (lnGroup(cls)) {
            case 'P': return ContextCompat.getColor(context, R.color.ln_prot);
            case 'R': return ContextCompat.getColor(context, R.color.ln_rela);
            case 'A': return ContextCompat.getColor(context, R.color.ln_auto);
            case 'L': return ContextCompat.getColor(context, R.color.ln_syst);
            case 'G': return ContextCompat.getColor(context, R.color.ln_genr);
            case 'S': return ContextCompat.getColor(context, R.color.ln_supv);
            case 'T':
            case 'Y': return ContextCompat.getColor(context, R.color.ln_trfm);
            case 'I': return ContextCompat.getColor(context, R.color.ln_intf);
            case 'Z': return ContextCompat.getColor(context, R.color.ln_zpwr);
            case 'X':
            case 'C': return ContextCompat.getColor(context, R.color.ln_xswi);
            case 'M': return ContextCompat.getColor(context, R.color.ln_mmxu);
            default:  return ContextCompat.getColor(context, R.color.ln_default);
        }
    }

    /**
     * Si el nombre del LN va coloreado y en negrita.
     *
     * Deliberadamente NO son todos: ahora el icono lleva la semantica completa,
     * asi que resaltar tambien cada nombre dejaria el arbol entero en negrita y
     * sin jerarquia. Se reserva para los grupos operativamente salientes —
     * maniobra (X), control (C), medicion (M) y proteccion (P/R).
     */
    private boolean shouldEmphasizeLn(String lnClass) {
        switch (lnGroup(lnClass)) {
            case 'X': case 'C': case 'M': case 'P': case 'R': return true;
            default: return false;
        }
    }

    /**
     * Color del texto según el estado del valor (on=verde, off=rojo, intermedio=naranja).
     * Retorna 0 si el valor no es un estado reconocido.
     */
    private int getStateColor(String value) {
        if (value == null) return 0;
        String v = value.toLowerCase().trim();
        String label = v.contains("[") && v.contains("]")
                ? v.substring(v.indexOf('[') + 1, v.lastIndexOf(']')).trim() : v;

        boolean isOn = label.equals("on") || label.equals("ok") || label.equals("closed")
                || label.equals("true") || (!v.contains("[") && (v.equals("on") || v.equals("2")));
        boolean isOff = label.equals("off") || label.equals("alarm") || label.equals("open")
                || label.equals("false") || (!v.contains("[") && (v.equals("off") || v.equals("1")));
        boolean isWarn = label.contains("intermediate") || label.equals("bad") || label.equals("bad_state")
                || label.equals("blocked") || label.equals("warning")
                || (!v.contains("[") && (v.equals("0") || v.equals("3")));

        if (isOn)   return ContextCompat.getColor(context, R.color.state_on);
        if (isOff)  return ContextCompat.getColor(context, R.color.state_off);
        if (isWarn) return ContextCompat.getColor(context, R.color.state_warn);
        return 0;
    }

    /**
     * Color del tint del icono de interruptor según estado DBPOS.
     */
    private int getSwitchStateTintColor(String value) {
        if (value == null) return ContextCompat.getColor(context, R.color.state_bad);
        String lv = value.toLowerCase().trim();
        if (lv.equals("on")  || lv.equals("2")) return ContextCompat.getColor(context, R.color.state_on);
        if (lv.equals("off") || lv.equals("1")) return ContextCompat.getColor(context, R.color.state_off);
        if (lv.equals("bad") || lv.equals("3")) return ContextCompat.getColor(context, R.color.state_bad);
        return ContextCompat.getColor(context, R.color.state_warn); // intermediate
    }

    private int getFcColor(String fc) {
        switch (fc) {
            case "ST": return R.color.fc_st;
            case "MX": return R.color.fc_mx;
            case "CO": return R.color.fc_co;
            case "SP": return R.color.fc_sp;
            case "CF": return R.color.fc_cf;
            default: return R.color.fc_default;
        }
    }

    /**
     * Check if node is a switch/breaker position value (XCBR or CSWI Pos.stVal)
     */
    private boolean isSwitchPositionNode(TreeNode node) {
        if (node == null || node.reference == null) return false;
        String ref = node.reference.toUpperCase();
        // Check for XCBR (Circuit Breaker) or CSWI (Switch) position status
        return (ref.contains("XCBR") || ref.contains("CSWI") || ref.contains("XSWI"))
                && ref.contains("POS") && ref.contains("STVAL");
    }

    /**
     * Get switch state icon resource based on DBPOS value
     * OFF = open switch, ON = closed switch, INTERMEDIATE = transitioning, BAD = error
     */
    private int getSwitchStateIcon(String value) {
        if (value == null) return R.drawable.ic_switch_intermediate;

        String lowerValue = value.toLowerCase().trim();

        // OFF state (open)
        if (lowerValue.equals("off") || lowerValue.equals("01") || lowerValue.equals("1")) {
            return R.drawable.ic_switch_off;
        }
        // ON state (closed)
        if (lowerValue.equals("on") || lowerValue.equals("10") || lowerValue.equals("2")) {
            return R.drawable.ic_switch_on;
        }
        // INTERMEDIATE state (transitioning)
        if (lowerValue.equals("intermediate") || lowerValue.equals("intermediate-state") ||
                lowerValue.equals("00") || lowerValue.equals("0")) {
            return R.drawable.ic_switch_intermediate;
        }
        // BAD state (error)
        if (lowerValue.equals("bad") || lowerValue.equals("bad-state") ||
                lowerValue.equals("11") || lowerValue.equals("3")) {
            return R.drawable.ic_switch_bad;
        }

        return R.drawable.ic_switch_intermediate; // default
    }

    private String[] getOptionsForValueType(ValueType valueType) {
        switch (valueType) {
            case BOOLEAN: return BOOLEAN_OPTIONS;
            case DBPOS: return DBPOS_OPTIONS;
            case TAPCOMMAND: return TAPCOMMAND_OPTIONS;
            case CHECK: return CHECK_OPTIONS;
            default: return new String[]{};
        }
    }

    private int getIndexForValue(ValueType valueType, String value) {
        if (value == null) return 0;
        String lowerValue = value.toLowerCase().trim();

        switch (valueType) {
            case BOOLEAN:
                if (lowerValue.equals("true") || lowerValue.equals("1")) return 1;
                return 0; // false
            case DBPOS:
                if (lowerValue.equals("off") || lowerValue.equals("01") || lowerValue.equals("1")) return 0;
                if (lowerValue.equals("on") || lowerValue.equals("10") || lowerValue.equals("2")) return 1;
                if (lowerValue.equals("intermediate") || lowerValue.equals("intermediate-state") || lowerValue.equals("00") || lowerValue.equals("0")) return 2;
                if (lowerValue.equals("bad") || lowerValue.equals("bad-state") || lowerValue.equals("11") || lowerValue.equals("3")) return 3;
                return 0;
            case TAPCOMMAND:
                if (lowerValue.equals("stop") || lowerValue.equals("0")) return 0;
                if (lowerValue.equals("lower") || lowerValue.equals("1")) return 1;
                if (lowerValue.equals("higher") || lowerValue.equals("2")) return 2;
                if (lowerValue.equals("reserved") || lowerValue.equals("3")) return 3;
                return 0;
            case CHECK:
                if (lowerValue.equals("true") || lowerValue.equals("1")) return 1;
                return 0;
            default:
                return 0;
        }
    }

    private boolean hasChildren(TreeNode node) {
        // Un Logical Node todavia sin materializar no tiene hijos EN LA LISTA, pero
        // los va a tener al expandirlo. Sin este caso la flecha no se dibuja, se le
        // asigna el listener de hoja y el nodo no abre nunca: el arbol perezoso
        // quedaria inservible. Se mira el modelo, no la lista.
        if (node.type == NodeType.LOGICAL_NODE && node.fuenteLn != null && !node.hijosListos) {
            return node.fuenteLn.getChildren() != null && !node.fuenteLn.getChildren().isEmpty();
        }

        String baseRef = node.reference;
        // Remove $FC suffix if present for comparison
        int dollarPos = baseRef.lastIndexOf('$');
        if (dollarPos > 0) {
            baseRef = baseRef.substring(0, dollarPos);
        }

        String prefix = baseRef + ".";
        String prefix2 = baseRef + "/";
        for (TreeNode n : allNodes) {
            String childRef = n.reference;
            // Remove $FC suffix from child reference for comparison
            int childDollar = childRef.lastIndexOf('$');
            if (childDollar > 0) {
                childRef = childRef.substring(0, childDollar);
            }

            if (childRef.startsWith(prefix) || childRef.startsWith(prefix2)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getItemCount() {
        return visibleNodes.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivExpand;
        ImageView ivIcon;
        TextView tvName;
        TextView tvFc;
        TextView tvBdaType;
        View layoutValue;
        ImageView ivSwitchState;  // Switch state icon for XCBR/CSWI
        EditText etValue;
        Spinner spinnerValue;
        ImageButton btnApply;

        ViewHolder(View itemView) {
            super(itemView);
            ivExpand = itemView.findViewById(R.id.iv_expand);
            ivIcon = itemView.findViewById(R.id.iv_icon);
            tvName = itemView.findViewById(R.id.tv_name);
            tvFc = itemView.findViewById(R.id.tv_fc);
            tvBdaType = itemView.findViewById(R.id.tv_bda_type);
            layoutValue = itemView.findViewById(R.id.layout_value);
            ivSwitchState = itemView.findViewById(R.id.iv_switch_state);
            etValue = itemView.findViewById(R.id.et_value);
            spinnerValue = itemView.findViewById(R.id.spinner_value);
            btnApply = itemView.findViewById(R.id.btn_apply);
        }
    }

    /**
     * Build tree structure from ServerModel.
     */
    /**
     * Arma el esqueleto del arbol: Logical Devices y Logical Nodes.
     *
     * Los Data Objects y sus atributos NO se crean aca. Antes se construia el
     * arbol entero de una: en un CID real eran 92.527 nodos y ~600 ms en el
     * telefono, para mostrar una lista que arranca colapsada y de la que se ven
     * ocho filas. Ahora el subarbol de cada LN se genera cuando se lo expande,
     * en materializarHijos().
     */
    public static List<TreeNode> buildTree(ServerModel model) {
        List<TreeNode> nodes = new ArrayList<>();

        if (model == null) return nodes;

        for (ModelNode ldNode : model.getChildren()) {
            if (!(ldNode instanceof LogicalDevice)) continue;
            LogicalDevice ld = (LogicalDevice) ldNode;
            nodes.add(new TreeNode(ld.getName(), ld.getName(), NodeType.LOGICAL_DEVICE, 0));

            for (ModelNode lnNode : ld.getChildren()) {
                if (!(lnNode instanceof LogicalNode)) continue;
                LogicalNode ln = (LogicalNode) lnNode;
                TreeNode lnTreeNode = new TreeNode(
                        ln.getName(), ld.getName() + "/" + ln.getName(),
                        NodeType.LOGICAL_NODE, 1);
                lnTreeNode.lnClass = extractLnClass(ln.getName());
                lnTreeNode.fuenteLn = ln;
                nodes.add(lnTreeNode);
            }
        }
        return nodes;
    }

    /**
     * Genera los Data Objects y atributos de un Logical Node.
     *
     * Es exactamente lo que buildTree() hacia por cada LN, movido aca para
     * poder ejecutarlo bajo demanda.
     */
    private static List<TreeNode> construirSubarbolDeLn(TreeNode lnTreeNode) {
        List<TreeNode> nodes = new ArrayList<>();
        LogicalNode ln = lnTreeNode.fuenteLn;
        if (ln == null) return nodes;
        String lnRef = lnTreeNode.reference;

        // Los Data Objects se agrupan por nombre, no por FC: un mismo DO aparece
        // una vez por cada Functional Constraint en el que este presente.
        Map<String, List<FcModelNode>> doByName = new LinkedHashMap<>();
        for (ModelNode doNode : ln.getChildren()) {
            if (!(doNode instanceof FcModelNode)) continue;
            FcModelNode fcDo = (FcModelNode) doNode;
            doByName.computeIfAbsent(fcDo.getName(), k -> new ArrayList<>()).add(fcDo);
        }

        for (Map.Entry<String, List<FcModelNode>> entry : doByName.entrySet()) {
            String doRef = lnRef + "." + entry.getKey();
            nodes.add(new TreeNode(entry.getKey(), doRef, NodeType.DATA_OBJECT, 2));
            addDataObjectChildren(nodes, entry.getValue(), doRef, 3);
        }
        return nodes;
    }

    private static void addDataObjectChildren(List<TreeNode> nodes, List<FcModelNode> fcNodes,
                                              String parentRef, int level) {
        Map<String, List<ChildWithFc>> childrenByName = new LinkedHashMap<>();

        for (FcModelNode fcNode : fcNodes) {
            String fc = fcNode.getFc().toString();

            for (ModelNode child : fcNode.getChildren()) {
                String childName = child.getName();

                if (!childrenByName.containsKey(childName)) {
                    childrenByName.put(childName, new ArrayList<>());
                }
                childrenByName.get(childName).add(new ChildWithFc(child, fc));
            }
        }

        // Now add tree nodes for each unique child name
        for (Map.Entry<String, List<ChildWithFc>> entry : childrenByName.entrySet()) {
            String childName = entry.getKey();
            List<ChildWithFc> childList = entry.getValue();
            String childRef = parentRef + "." + childName;

            // Check if all instances are BasicDataAttribute (leaf) or not
            ChildWithFc first = childList.get(0);
            ModelNode firstChild = first.node;

            if (firstChild instanceof BasicDataAttribute) {
                // It's a leaf attribute - show each FC variant
                for (ChildWithFc cwf : childList) {
                    BasicDataAttribute bda = (BasicDataAttribute) cwf.node;
                    // Create unique reference for each FC variant
                    String uniqueRef = childRef + "$" + cwf.fc;

                    TreeNode daNode = new TreeNode(
                            bda.getName(),
                            uniqueRef,
                            NodeType.DATA_ATTRIBUTE,
                            level
                    );
                    daNode.fc = cwf.fc;
                    daNode.bdaType = getDisplayTypeName(bda);
                    daNode.value = getDisplayValue(bda);
                    daNode.editable = isEditableAttribute(bda);
                    daNode.valueType = getValueType(bda);
                    nodes.add(daNode);
                }
            } else {
                // It's a sub-data object - create one node and merge children
                TreeNode sdoNode = new TreeNode(
                        childName,
                        childRef,
                        NodeType.DATA_OBJECT,
                        level
                );
                nodes.add(sdoNode);

                // Recursively add children from all FC variants
                addSubDataObjectChildrenMerged(nodes, childList, childRef, level + 1);
            }
        }
    }

    /**
     * Helper class to track a child node with its FC
     */
    private static class ChildWithFc {
        ModelNode node;
        String fc;

        ChildWithFc(ModelNode node, String fc) {
            this.node = node;
            this.fc = fc;
        }
    }

    /**
     * Add children of sub-data objects, merging from multiple FC sources
     */
    private static void addSubDataObjectChildrenMerged(List<TreeNode> nodes, List<ChildWithFc> parents,
                                                        String parentRef, int level) {
        Map<String, List<ChildWithFc>> childrenByName = new LinkedHashMap<>();

        for (ChildWithFc parent : parents) {
            for (ModelNode child : parent.node.getChildren()) {
                String childName = child.getName();

                if (!childrenByName.containsKey(childName)) {
                    childrenByName.put(childName, new ArrayList<>());
                }
                childrenByName.get(childName).add(new ChildWithFc(child, parent.fc));
            }
        }

        // Add tree nodes
        for (Map.Entry<String, List<ChildWithFc>> entry : childrenByName.entrySet()) {
            String childName = entry.getKey();
            List<ChildWithFc> childList = entry.getValue();
            String childRef = parentRef + "." + childName;

            ChildWithFc first = childList.get(0);

            if (first.node instanceof BasicDataAttribute) {
                // Leaf attribute
                for (ChildWithFc cwf : childList) {
                    BasicDataAttribute bda = (BasicDataAttribute) cwf.node;
                    String uniqueRef = childRef + "$" + cwf.fc;

                    TreeNode daNode = new TreeNode(
                            bda.getName(),
                            uniqueRef,
                            NodeType.DATA_ATTRIBUTE,
                            level
                    );
                    daNode.fc = cwf.fc;
                    daNode.bdaType = getDisplayTypeName(bda);
                    daNode.value = getDisplayValue(bda);
                    daNode.editable = isEditableAttribute(bda);
                    daNode.valueType = getValueType(bda);
                    nodes.add(daNode);
                }
            } else {
                // Sub-data object
                TreeNode sdoNode = new TreeNode(
                        childName,
                        childRef,
                        NodeType.DATA_OBJECT,
                        level
                );
                nodes.add(sdoNode);

                addSubDataObjectChildrenMerged(nodes, childList, childRef, level + 1);
            }
        }
    }

    /**
     * Extrae la clase del LN desde su nombre completo.
     * Ej: "XCBR1" → "XCBR1", "M03_MMXU1" → "MMXU1", "LLN0" → "LLN0"
     * Se elimina el prefijo opcional (letras+dígitos+guion_bajo).
     */
    private static String extractLnClass(String lnName) {
        if (lnName == null || lnName.isEmpty()) return "";
        return lnName.replaceFirst("^[A-Za-z0-9]+_", "");
    }

    private static boolean isEditableAttribute(BasicDataAttribute bda) {
        String fc = bda.getFc().toString();

        // In simulation mode, allow editing MX (measurements) and ST (status) values
        if (simulationMode) {
            // Allow editing of most common types in simulation mode
            return "CO".equals(fc) || "SP".equals(fc) || "SG".equals(fc) || "SE".equals(fc)
                    || "MX".equals(fc) || "ST".equals(fc)
                    || bda instanceof BdaBoolean
                    || bda instanceof BdaDoubleBitPos      // Double-bit position (XCBR, XSWI)
                    || bda instanceof BdaCheck      // Check for control
                    || bda instanceof BdaTapCommand; // Tap commands for transformers
        }

        // Normal mode: only control/setting FCs are writable
        return "CO".equals(fc) || "SP".equals(fc) || "SG".equals(fc) || "SE".equals(fc)
                || bda instanceof BdaBoolean
                || bda instanceof BdaDoubleBitPos
                || bda instanceof BdaCheck
                || bda instanceof BdaTapCommand;
    }

    /**
     * Determine the value type for dropdown selection
     */
    private static ValueType getValueType(BasicDataAttribute bda) {
        if (bda instanceof BdaBoolean) {
            return ValueType.BOOLEAN;
        } else if (bda instanceof BdaDoubleBitPos) {
            return ValueType.DBPOS;
        } else if (bda instanceof BdaTapCommand) {
            return ValueType.TAPCOMMAND;
        } else if (bda instanceof BdaCheck) {
            return ValueType.CHECK;
        }
        return ValueType.TEXT;
    }

    /**
     * Get a more descriptive type name for display
     */
    private static String getDisplayTypeName(BasicDataAttribute bda) {
        if (bda instanceof BdaDoubleBitPos) {
            return "Dbpos";
        } else if (bda instanceof BdaCheck) {
            return "Check";
        } else if (bda instanceof BdaTapCommand) {
            return "TapCmd";
        }
        return bda.getClass().getSimpleName().replace("Bda", "");
    }

    /**
     * Get a more readable display value for special types
     */
    private static String getDisplayValue(BasicDataAttribute bda) {
        if (bda instanceof BdaDoubleBitPos) {
            BdaDoubleBitPos dbpos = (BdaDoubleBitPos) bda;
            BdaDoubleBitPos.DoubleBitPos val = dbpos.getDoubleBitPos();
            if (val != null) {
                switch (val) {
                    case OFF: return "off";
                    case ON: return "on";
                    case INTERMEDIATE_STATE: return "intermediate";
                    case BAD_STATE: return "bad";
                }
            }
            return bda.getValueString();
        } else if (bda instanceof BdaTapCommand) {
            BdaTapCommand tap = (BdaTapCommand) bda;
            BdaTapCommand.TapCommand val = tap.getTapCommand();
            if (val != null) {
                switch (val) {
                    case STOP: return "stop";
                    case LOWER: return "lower";
                    case HIGHER: return "higher";
                    case RESERVED: return "reserved";
                }
            }
            return bda.getValueString();
        } else if (bda instanceof BdaBoolean) {
            return ((BdaBoolean) bda).getValue() ? "true" : "false";
        }
        return bda.getValueString();
    }
}

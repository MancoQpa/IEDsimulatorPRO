package com.ied.simulator;

import android.util.Log;

import com.beanit.iec61850bean.BdaVisibleString;
import com.beanit.iec61850bean.Brcb;
import com.beanit.iec61850bean.DataSet;
import com.beanit.iec61850bean.LogicalDevice;
import com.beanit.iec61850bean.LogicalNode;
import com.beanit.iec61850bean.ModelNode;
import com.beanit.iec61850bean.Rcb;
import com.beanit.iec61850bean.SclParseException;
import com.beanit.iec61850bean.SclParser;
import com.beanit.iec61850bean.ServerModel;
import com.beanit.iec61850bean.Urcb;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Utility class for parsing SCL/ICD/CID/SCD files.
 *
 * Incluye pre-procesado de arrays SCL (atributo count en SDO/DA/BDA) antes de
 * pasar el documento a iec61850bean, que no soporta ese atributo nativamente.
 * Indexado base 0: count="51" genera phsAHar00..phsAHar50 (igual que IEDNavigator).
 */
public class SclFileParser {

    private static final String TAG = "SclFileParser";

    /**
     * Parse an SCL file and return the list of server models.
     * Pre-procesa el documento (arrays con count + EnumTypes incompletos) antes
     * de entregarlo a iec61850bean.
     */
    public static List<ServerModel> parse(InputStream inputStream) throws SclParseException {
        return parse(inputStream, null);
    }

    /**
     * Igual que parse(), pero ademas deja en iedNamesOut el nombre real de cada
     * IED tal como lo declara el SCL (<IED name="...">), en el mismo orden que
     * los modelos devueltos.
     *
     * Sirve para etiquetar el selector de dispositivo con el nombre del equipo
     * ("B02_R22_F003") en vez de con el de su primer Logical Device
     * ("B02_R22_F003AnUn_MeasTransdIn"), que es lo que se veria si se dedujera
     * del modelo. Es tambien lo que muestra IEDScout.
     *
     * @param iedNamesOut lista a rellenar; puede ser null si no interesa
     */
    public static List<ServerModel> parse(InputStream inputStream, List<String> iedNamesOut)
            throws SclParseException {
        if (inputStream == null) {
            throw new SclParseException("Input stream is null");
        }

        try {
            long tA = System.currentTimeMillis();
            byte[] xml = preprocess(inputStream);
            long tPre = System.currentTimeMillis() - tA;

            tA = System.currentTimeMillis();
            List<ServerModel> perAccessPoint = parseTolerant(xml);
            long tParse = System.currentTimeMillis() - tA;
            Log.i(TAG, "SclParser devolvio "
                    + (perAccessPoint != null ? perAccessPoint.size() : 0)
                    + " modelo(s) (uno por AccessPoint)");

            tA = System.currentTimeMillis();
            List<ServerModel> perIed = mergeByIed(perAccessPoint, xml, iedNamesOut);
            long tMerge = System.currentTimeMillis() - tA;

            Log.i(TAG, "PERF  preprocess=" + tPre + "ms  SclParser=" + tParse
                    + "ms  mergeByIed=" + tMerge + "ms");
            Log.i(TAG, "Modelos por IED tras fusionar: " + perIed.size());
            return perIed;
        } catch (SclParseException e) {
            Log.e(TAG, "SCL parse error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Lista los IED que tienen servidor MMS, en el MISMO orden en que parse()
     * devuelve los modelos.
     *
     * Es un barrido DOM barato: no toca SclParser, que es el 93% del costo. Sirve
     * para mostrar el selector de dispositivo al instante y recien despues
     * parsear el que el usuario eligio.
     *
     * Solo se cuentan los IED con AccessPoint que tenga <Server>, por la misma
     * razon que en mergeByIed: los demas no producen ServerModel, y si se los
     * listara los indices no coincidirian con los modelos.
     */
    public static List<String> listIedNames(InputStream inputStream) throws SclParseException {
        try {
            byte[] xml = leerTodo(inputStream);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

            List<String> nombres = new ArrayList<>();
            NodeList ieds = doc.getElementsByTagName("IED");
            for (int i = 0; i < ieds.getLength(); i++) {
                Element ied = (Element) ieds.item(i);
                if (countServerAccessPoints(ied) == 0) continue;
                String name = ied.getAttribute("name");
                nombres.add((name == null || name.isEmpty()) ? ("IED_" + i) : name);
            }
            return nombres;
        } catch (Exception e) {
            throw new SclParseException("No se pudo listar los IED: " + e.getMessage());
        }
    }

    /**
     * Parsea SOLO el IED indicado, recortando los demas del XML antes de entregarlo
     * a la libreria.
     *
     * SclParser no tiene forma de pedirle un IED puntual: parsea el archivo entero
     * siempre. En un SCD de subestacion eso significa construir el modelo de
     * decenas de equipos para usar uno. Medido en un SCD de 4 IEDs:
     * SclParser.parse() pasa de 831 ms a 126 ms recortando a uno, 6,6x. Y el
     * ahorro tambien alcanza a nuestro pre-procesado, que trabaja sobre un
     * documento mucho mas chico.
     *
     * @param iedIndex indice sobre la lista que devuelve listIedNames()
     */
    public static List<ServerModel> parseOnlyIed(InputStream inputStream, int iedIndex)
            throws SclParseException {
        try {
            byte[] xml = leerTodo(inputStream);
            byte[] recortado = recortarAlIed(xml, iedIndex);
            if (recortado == null) {
                Log.w(TAG, "No se pudo recortar al IED " + iedIndex + " - se parsea completo");
                return parse(new ByteArrayInputStream(xml), null);
            }
            if (recortado.length == xml.length) {
                Log.i(TAG, "Un solo IED: no hay nada que recortar (" + xml.length + " bytes)");
            } else {
                Log.i(TAG, "Recortado al IED " + iedIndex + " ("
                        + xml.length + " -> " + recortado.length + " bytes)");
            }
            return parse(new ByteArrayInputStream(recortado), null);
        } catch (SclParseException e) {
            throw e;
        } catch (Exception e) {
            throw new SclParseException("No se pudo recortar el SCL: " + e.getMessage());
        }
    }

    /**
     * Devuelve el XML con un solo IED: el que ocupa iedIndex entre los que tienen
     * servidor. Tambien saca del bloque <Communication> los <ConnectedAP> de los
     * IED eliminados, para no dejar referencias colgando.
     *
     * NO toca <DataTypeTemplates>: los tipos son compartidos y sacarlos romperia
     * el que queda.
     *
     * @return el XML recortado, o null si no se pudo (el llamador parsea completo)
     */
    private static byte[] recortarAlIed(byte[] xml, int iedIndex) {
        // Un archivo con un solo IED no tiene nada que recortar, y averiguarlo no
        // justifica construir el DOM: en un CID de 1,4 MB eso son ~320 ms en el
        // escritorio y bastante mas en un telefono, para terminar devolviendo los
        // mismos bytes que entraron. Se cuentan las etiquetas <IED sobre el arreglo.
        //
        // Si hay una sola, es forzosamente la elegida: el indice viene de
        // listIedNames(), que solo lista los IED con servidor, asi que un archivo
        // con un IED sin servidor no habria llegado hasta aca.
        if (contarEtiquetasIed(xml) <= 1) {
            return xml;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

            NodeList ieds = doc.getElementsByTagName("IED");
            List<Element> conServidor = new ArrayList<>();
            for (int i = 0; i < ieds.getLength(); i++) {
                Element ied = (Element) ieds.item(i);
                if (countServerAccessPoints(ied) > 0) conServidor.add(ied);
            }
            if (iedIndex < 0 || iedIndex >= conServidor.size()) return null;
            if (conServidor.size() == 1) return xml;   // ya hay uno solo: nada que recortar

            Element elegido = conServidor.get(iedIndex);
            String nombreElegido = elegido.getAttribute("name");

            // Quitar los demas <IED>, incluidos los que no tienen servidor
            List<Element> aBorrar = new ArrayList<>();
            for (int i = 0; i < ieds.getLength(); i++) {
                Element ied = (Element) ieds.item(i);
                if (ied != elegido) aBorrar.add(ied);
            }
            for (Element e : aBorrar) {
                if (e.getParentNode() != null) e.getParentNode().removeChild(e);
            }

            // Quitar los ConnectedAP que apuntan a IED que ya no existen
            NodeList caps = doc.getElementsByTagName("ConnectedAP");
            List<Element> capsBorrar = new ArrayList<>();
            for (int i = 0; i < caps.getLength(); i++) {
                Element cap = (Element) caps.item(i);
                if (!nombreElegido.equals(cap.getAttribute("iedName"))) capsBorrar.add(cap);
            }
            for (Element e : capsBorrar) {
                if (e.getParentNode() != null) e.getParentNode().removeChild(e);
            }

            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();

        } catch (Exception e) {
            Log.w(TAG, "recortarAlIed fallo: " + e.getMessage());
            return null;
        }
    }

    /** Lee un InputStream completo a memoria (solo se puede leer una vez). */
    private static byte[] leerTodo(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** Tope de valores malos que se descartan antes de darse por vencido. */
    private static final int MAX_VALORES_DESCARTADOS = 200;

    /**
     * Intenta sacar el tamano de un array del nombre del tipo, para los casos en
     * que el atributo count no es numerico sino el nombre de otro DA.
     * Convencion de SIPROTEC5: "SIPROTEC5_DAType_ARRAY_of_30_Points_V08..." -> 30.
     *
     * @return el tamano, o 0 si el nombre no lo dice
     */
    private static int deducirCountDelTipo(String typeId) {
        if (typeId == null) return 0;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("ARRAY_of_(\\d+)_").matcher(typeId);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Parsea tolerando valores iniciales invalidos, descartandolos uno a uno.
     *
     * iec61850bean aborta la carga del **archivo entero** cuando un solo <Val>
     * no se puede convertir al tipo de su atributo. Los CID de Ingeteam traen
     * cosas como <DAI name="general"><Val>CTRL/GAPC1.Op1.ST.general</Val></DAI>:
     * un booleano cuyo valor inicial es una referencia a otro objeto — una
     * convencion interna del fabricante que no es SCL valido.
     *
     * Perder el archivo entero por eso no tiene sentido: es un valor inicial, no
     * la estructura del modelo. OMICRON IEDScout abre estos mismos archivos
     * mostrando un indicador de errores, es decir degrada en vez de abortar, y
     * eso es lo que se hace aca: se saca el <Val> culpable —el atributo queda con
     * el valor por defecto de su tipo— y se reintenta.
     *
     * El mensaje de la excepcion trae el valor que no se pudo convertir, asi que
     * el descarte no es especifico de ningun fabricante.
     */
    private static List<ServerModel> parseTolerant(byte[] xml) throws SclParseException {
        byte[] actual = xml;
        int descartados = 0;

        while (true) {
            try {
                return SclParser.parse(new ByteArrayInputStream(actual));
            } catch (SclParseException e) {
                String malo = extraerValorInvalido(e.getMessage());
                if (malo == null || descartados >= MAX_VALORES_DESCARTADOS) throw e;

                byte[] siguiente = dropValsConTexto(actual, malo);
                if (siguiente == null) throw e;   // no se pudo quitar: no insistir

                descartados++;
                actual = siguiente;
                Log.w(TAG, "parseTolerant: descartado valor inicial invalido '"
                        + malo + "' (" + descartados + ")");
            }
        }
    }

    /**
     * Saca del mensaje de SclParseException el valor que no se pudo convertir.
     * Formato de la libreria: "invalid <tipo> configured value: <valor>".
     */
    private static String extraerValorInvalido(String mensaje) {
        if (mensaje == null) return null;
        final String marca = "configured value: ";
        int i = mensaje.indexOf(marca);
        if (i < 0) return null;
        String valor = mensaje.substring(i + marca.length()).trim();
        return valor.isEmpty() ? null : valor;
    }

    /**
     * Devuelve el XML sin los <Val> cuyo texto sea exactamente el dado,
     * o null si no habia ninguno (para no reintentar en vano).
     */
    private static byte[] dropValsConTexto(byte[] xml, String texto) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));

            NodeList vals = doc.getElementsByTagNameNS("*", "Val");
            if (vals.getLength() == 0) vals = doc.getElementsByTagName("Val");

            List<Element> aBorrar = new ArrayList<>();
            for (int i = 0; i < vals.getLength(); i++) {
                Element val = (Element) vals.item(i);
                if (texto.equals(val.getTextContent().trim())) aBorrar.add(val);
            }
            if (aBorrar.isEmpty()) return null;

            for (Element val : aBorrar) {
                Node parent = val.getParentNode();
                if (parent != null) parent.removeChild(val);
            }

            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();

        } catch (Exception e) {
            Log.w(TAG, "dropValsConTexto fallo: " + e.getMessage());
            return null;
        }
    }

    /**
     * Corrige el 'max' de <RptEnabled>, que es de donde iec61850bean saca la
     * cantidad de instancias del Report Control Block.
     *
     * La librería exige que quede entre 1 y 99 y, si no, aborta la carga del
     * archivo entero con "Report Control Block max instances should be between
     * 1 and 99 but is: 0". Los CID de Ingeteam declaran literalmente
     * <RptEnabled max="0"/>, que significa "ningún cliente puede habilitar este
     * RCB" — inútil para un simulador cuyo propósito es justamente que un
     * cliente se suscriba.
     *
     * También se cubre el caso de un ReportControl sin RptEnabled: se le agrega
     * uno con max=1, que es el mínimo con el que el RCB queda utilizable.
     *
     * @return cantidad de RptEnabled corregidos o creados
     */
    private static int patchReportControlMax(Document doc) {
        NodeList rcs = doc.getElementsByTagNameNS("*", "ReportControl");
        if (rcs.getLength() == 0) rcs = doc.getElementsByTagName("ReportControl");

        int patched = 0;
        for (int i = 0; i < rcs.getLength(); i++) {
            Element rc = (Element) rcs.item(i);

            Element rptEnabled = null;
            NodeList children = rc.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE
                        && "RptEnabled".equals(child.getLocalName() != null
                                ? child.getLocalName() : child.getNodeName())) {
                    rptEnabled = (Element) child;
                    break;
                }
            }

            if (rptEnabled == null) {
                String nsUri = rc.getNamespaceURI();
                rptEnabled = (nsUri != null && !nsUri.isEmpty())
                        ? doc.createElementNS(nsUri, "RptEnabled")
                        : doc.createElement("RptEnabled");
                rptEnabled.setAttribute("max", "1");
                rc.appendChild(rptEnabled);
                patched++;
                continue;
            }

            int max = 0;
            try {
                String raw = rptEnabled.getAttribute("max").trim();
                if (!raw.isEmpty()) max = Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {}

            if (max < 1 || max > 99) {
                rptEnabled.setAttribute("max", "1");
                patched++;
            }
        }
        return patched;
    }

    /**
     * Elimina los <Val> sin contenido.
     *
     * Un <Val/> vacío hace que iec61850bean intente convertir "" al tipo del
     * atributo y aborte la carga entera ("invalid INT32U configured value: ").
     * Los CID de ZIV traen cientos de estos. Quitarlos equivale a que el DAI no
     * declare valor inicial, que es justamente lo que el archivo quiere decir, y
     * el atributo queda con el valor por defecto de su tipo.
     *
     * @return cantidad de <Val> eliminados
     */
    private static int dropEmptyVals(Document doc) {
        NodeList vals = doc.getElementsByTagNameNS("*", "Val");
        if (vals.getLength() == 0) vals = doc.getElementsByTagName("Val");

        // NodeList es live: juntar primero, borrar despues
        List<Element> toDrop = new ArrayList<>();
        for (int i = 0; i < vals.getLength(); i++) {
            Element val = (Element) vals.item(i);
            String text = val.getTextContent();
            if (text == null || text.trim().isEmpty()) toDrop.add(val);
        }

        for (Element val : toDrop) {
            Node parent = val.getParentNode();
            if (parent != null) parent.removeChild(val);
        }
        return toDrop.size();
    }

    /**
     * Agrupa por IED los modelos que devuelve SclParser.
     *
     * SclParser.parse() devuelve **un ServerModel por AccessPoint, no por IED**.
     * Un IED que declara dos <AccessPoint> aparece como dos modelos sueltos, cada
     * uno con parte de sus LDs. Si se los trata 1:1 como si fueran IEDs, el
     * selector de dispositivo muestra entradas duplicadas/fragmentadas y simular
     * una de ellas deja afuera, sin avisar, los LDs que viven en el AccessPoint
     * hermano.
     *
     * Acá se recorre el XML para saber cuántos AccessPoints declara cada <IED> y
     * se fusionan los modelos consecutivos que le corresponden.
     *
     * IMPORTANTE: esta fusión tiene que pasar dentro de parse(), que es el único
     * punto por el que entran tanto MainActivity (arma el selector) como
     * IEDServerService (sirve el índice elegido). Si sólo fusionara uno de los
     * dos, los índices se desalinearían y se serviría el IED equivocado.
     */
    private static List<ServerModel> mergeByIed(List<ServerModel> models, byte[] xml,
                                                List<String> iedNamesOut) {
        List<ServerModel> result = new ArrayList<>();
        if (models == null || models.isEmpty()) return result;

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml));

            NodeList ieds = doc.getElementsByTagName("IED");
            int modelIndex = 0;

            for (int i = 0; i < ieds.getLength() && modelIndex < models.size(); i++) {
                Element ied = (Element) ieds.item(i);
                String name = ied.getAttribute("name");
                if (name == null || name.isEmpty()) name = "IED_" + i;

                int apCount = countServerAccessPoints(ied);
                if (apCount == 0) {
                    // IED sin servidor MMS: no aporta modelo y no debe consumir ninguno
                    Log.i(TAG, "IED '" + name + "': sin AccessPoint con Server - se omite");
                    continue;
                }
                ServerModel merged = mergeRange(models, modelIndex, modelIndex + apCount);

                if (merged != null) {
                    result.add(merged);
                    if (iedNamesOut != null) iedNamesOut.add(name);
                    Log.i(TAG, "IED '" + name + "': " + apCount + " AccessPoint(s), "
                            + merged.getChildren().size() + " LD(s)");
                }
                modelIndex += apCount;
            }

            // Si el XML no declaraba IEDs (o no cuadró), no perder nada
            if (result.isEmpty()) {
                Log.w(TAG, "No se pudo agrupar por IED - se usan los modelos sin fusionar");
                if (iedNamesOut != null) iedNamesOut.clear();
                return new ArrayList<>(models);
            }
            return result;

        } catch (Exception e) {
            Log.w(TAG, "mergeByIed fallo (" + e.getMessage()
                    + ") - se usan los modelos sin fusionar");
            if (iedNamesOut != null) iedNamesOut.clear();
            return new ArrayList<>(models);
        }
    }

    /**
     * Cuenta los AccessPoints de un IED que **tienen seccion <Server>**, que son
     * los unicos que producen un ServerModel.
     *
     * No alcanza con contar los <AccessPoint>: un equipo puede declarar un
     * AccessPoint solo para publicar GOOSE/SV, o uno de ingenieria, sin servidor
     * MMS. Verificado con archivos de fabricantes: una merging unit y un transformador
     * declaran 2 AccessPoints pero uno solo con Server, y SclParser devuelve 1
     * modelo; un SCD declara 12 AccessPoints los 12 con Server, y devuelve 12.
     *
     * Contar de mas desincronizaria el recorrido: a un IED se le asignarian los
     * modelos del siguiente y el ultimo quedaria sin ninguno.
     *
     * Puede devolver 0: un IED declarado **sin ningun** AccessPoint con servidor
     * no aporta modelo y no debe consumir ninguno. Pasa de verdad — un SCD de
     * subestacion real declara 53 IEDs y solo 52 tienen
     * Server. Ahi el IED sin servidor esta al final y devolver 1 no rompia nada;
     * si estuviera en el medio, correria la asignacion de todos los siguientes.
     *
     * @return cantidad de AccessPoints con Server (0 si ninguno)
     */
    private static int countServerAccessPoints(Element ied) {
        NodeList aps = ied.getElementsByTagName("AccessPoint");
        int withServer = 0;
        for (int i = 0; i < aps.getLength(); i++) {
            Element ap = (Element) aps.item(i);
            if (ap.getElementsByTagName("Server").getLength() > 0) withServer++;
        }
        return withServer;
    }

    /** Fusiona LDs y DataSets de los modelos [fromIdx, toIdx) en uno solo. */
    private static ServerModel mergeRange(List<ServerModel> models, int fromIdx, int toIdx) {
        List<LogicalDevice> allLDs = new ArrayList<>();
        List<DataSet> allDataSets = new ArrayList<>();

        for (int i = fromIdx; i < toIdx && i < models.size(); i++) {
            ServerModel m = models.get(i);
            for (ModelNode child : m.getChildren()) {
                if (child instanceof LogicalDevice) {
                    allLDs.add((LogicalDevice) child);
                }
            }
            Collection<DataSet> ds = m.getDataSets();
            if (ds != null) allDataSets.addAll(ds);
        }

        if (allLDs.isEmpty()) return null;

        // Un solo AccessPoint: no hay nada que fusionar ni que re-vincular
        if (toIdx - fromIdx == 1 && fromIdx < models.size()) {
            return models.get(fromIdx);
        }

        ServerModel merged = new ServerModel(allLDs, allDataSets);
        relinkRcbDataSets(merged);
        return merged;
    }

    /**
     * Re-vincula Rcb.dataSet en el modelo fusionado.
     *
     * SclParser sólo resuelve referencias dentro de un mismo AccessPoint: un RCB
     * que apunta a un DataSet del AccessPoint hermano queda con dataSet=null.
     * Tras fusionar, ese DataSet ya existe en el modelo pero el campo (que es
     * package-private en iec61850bean) sigue en null, y entonces la librería
     * responde PARAMETER_VALUE_INAPPROPRIATE cuando el cliente hace
     * retrieveModel(). Este es exactamente el modo de falla que sufrió
     * IEDnavigatorPRO al hacer la fusión análoga del lado cliente.
     *
     * Si un datSet no se puede resolver, se limpia el atributo para que el
     * cliente no pida un DataSet inexistente.
     */
    private static void relinkRcbDataSets(ServerModel model) {
        Map<String, DataSet> dsIndex = new HashMap<>();
        Collection<DataSet> allDs = model.getDataSets();
        if (allDs != null) {
            for (DataSet ds : allDs) {
                dsIndex.put(ds.getReferenceStr(), ds);
                // iec61850bean usa indistintamente '.' y '$' antes del ultimo segmento
                dsIndex.put(ds.getReferenceStr().replaceAll("\\.(?=[^./]+$)", "\\$"), ds);
            }
        }

        Field urcbField = findDataSetField(Urcb.class);
        Field brcbField = findDataSetField(Brcb.class);
        int linked = 0, cleared = 0;

        for (ModelNode ldNode : model.getChildren()) {
            if (!(ldNode instanceof LogicalDevice)) continue;
            for (ModelNode lnNode : ldNode.getChildren()) {
                if (!(lnNode instanceof LogicalNode)) continue;
                LogicalNode ln = (LogicalNode) lnNode;

                Collection<Urcb> urcbs = ln.getUrcbs();
                if (urcbs != null) {
                    for (Urcb urcb : urcbs) {
                        if (resolveRcbDataSet(urcb, dsIndex, urcbField)) linked++; else cleared++;
                    }
                }
                Collection<Brcb> brcbs = ln.getBrcbs();
                if (brcbs != null) {
                    for (Brcb brcb : brcbs) {
                        if (resolveRcbDataSet(brcb, dsIndex, brcbField)) linked++; else cleared++;
                    }
                }
            }
        }

        if (linked > 0 || cleared > 0) {
            Log.i(TAG, "RCB re-link: " + linked + " enlazado(s), "
                    + cleared + " sin DataSet valido (limpiado(s))");
        }
    }

    /**
     * Cuenta las etiquetas de apertura {@code <IED } sobre los bytes crudos.
     *
     * No es un parser: busca la secuencia literal seguida de un separador, que en un
     * SCL solo puede ser el elemento IED (el estandar no define ningun otro elemento
     * cuyo nombre empiece con "IED"). Alcanza para decidir si hace falta recortar, y
     * cuesta una pasada lineal en vez de un DOM entero.
     */
    private static int contarEtiquetasIed(byte[] xml) {
        final byte[] patron = {'<', 'I', 'E', 'D'};
        int n = 0;
        for (int i = 0; i + patron.length < xml.length; i++) {
            boolean coincide = true;
            for (int j = 0; j < patron.length; j++) {
                if (xml[i + j] != patron[j]) { coincide = false; break; }
            }
            if (!coincide) continue;
            byte sig = xml[i + patron.length];
            // Solo cuenta si termina ahi el nombre del elemento: <IED> o <IED atributo=...
            if (sig == ' ' || sig == '\t' || sig == '\r' || sig == '\n' || sig == '>') n++;
        }
        return n;
    }

    /** Busca el campo de tipo DataSet recorriendo la jerarquia de clases. */
    private static Field findDataSetField(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (DataSet.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** @return true si se resolvio el DataSet del RCB; false si hubo que limpiarlo. */
    private static boolean resolveRcbDataSet(Rcb rcb, Map<String, DataSet> dsIndex, Field dataSetField) {
        BdaVisibleString datSetAttr = rcb.getDatSet();
        if (datSetAttr == null) return true;

        String datSetVal = datSetAttr.getStringValue();
        if (datSetVal == null || datSetVal.isEmpty()) return true;

        DataSet found = dsIndex.get(datSetVal);
        if (found == null) {
            String alt = datSetVal.contains("$")
                    ? datSetVal.replaceAll("\\$(?=[^$]+$)", ".")
                    : datSetVal.replaceAll("\\.(?=[^./]+$)", "\\$");
            found = dsIndex.get(alt);
        }
        if (found == null) {
            // El datSet puede venir relativo, sin el prefijo del IED
            String normalized = datSetVal.replace('$', '.');
            for (Map.Entry<String, DataSet> e : dsIndex.entrySet()) {
                if (e.getKey().endsWith("/" + normalized) || e.getKey().endsWith("." + normalized)) {
                    found = e.getValue();
                    break;
                }
            }
        }

        if (found != null) {
            if (dataSetField != null) {
                try {
                    if (dataSetField.get(rcb) == null) dataSetField.set(rcb, found);
                } catch (Exception ignored) {
                    // el valor string del datSet sigue siendo correcto
                }
            }
            return true;
        }

        Log.w(TAG, "datSet '" + datSetVal + "' no existe en el modelo - limpiando RCB " + rcb.getName());
        datSetAttr.setValue("");
        if (dataSetField != null) {
            try { dataSetField.set(rcb, null); } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * Pre-procesa el SCL en un único pase DOM, aplicando dos parches que
     * iec61850bean no hace por sí solo:
     *
     *   1. Expansión de arrays: elementos SDO/BDA con atributo count > 1.
     *      Ej: <SDO name="phsAHar" count="51"/> → phsAHar00 .. phsAHar50
     *      Los DA no: quedan como arreglo (ver el comentario en el bucle).
     *   2. Relleno de EnumTypes incompletos (ver patchMissingEnumOrdinals).
     *
     * Portado de IEC61850Server.expandSclArrays() / patchMissingEnumOrdinals()
     * de IEDNavigator (indexado base 0).
     *
     * Si el pre-procesado falla por cualquier motivo, retorna el stream original
     * sin modificar: nunca debe impedir que se cargue un archivo que ya andaba.
     */
    static byte[] preprocess(InputStream in) {
        // Leer todo el stream a memoria (solo puede leerse una vez)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        } catch (IOException e) {
            Log.w(TAG, "preprocess: error leyendo stream — usando original");
            return bos.toByteArray();
        }
        byte[] originalBytes = bos.toByteArray();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(originalBytes));

            boolean modified = false;

            for (String tag : new String[]{"SDO", "DA", "BDA"}) {
                NodeList nodes = doc.getElementsByTagNameNS("*", tag);

                // Recoger primero los elementos con count (NodeList es live)
                List<Element> toExpand = new ArrayList<>();
                for (int i = 0; i < nodes.getLength(); i++) {
                    Node node = nodes.item(i);
                    if (!(node instanceof Element)) continue;
                    Element el = (Element) node;
                    String countStr = el.getAttribute("count").trim();
                    if (countStr.isEmpty()) continue;

                    try {
                        int count = Integer.parseInt(countStr);
                        if (count > 1) toExpand.add(el);
                    } catch (NumberFormatException e) {
                        // count NO numerico: en IEC 61850-6 puede ser el nombre de otro
                        // DA que lleva el tamano en tiempo de ejecucion (array dinamico).
                        // Ej. real (SIPROTEC5): <DA name="crvPts" count="maxPts" .../>
                        // junto a <DA name="maxPts" bType="INT16U" valKind="RO"/>.
                        //
                        // SclParser hace Integer.parseInt() sobre eso y lanza
                        // NumberFormatException — una excepcion NO declarada, que ni
                        // siquiera es SclParseException, asi que tira abajo la carga
                        // entera sin que el lazo tolerante pueda hacer nada.
                        //
                        // No se puede resolver por valor: en el archivo que lo trae no
                        // hay ningun DAI que fije ese DA. Lo unico disponible es el
                        // nombre del tipo, que suele traer el tamano
                        // ("..._ARRAY_of_30_Points_..."). Si tampoco esta, se quita el
                        // atributo: el elemento queda como uno solo en vez de un array,
                        // que es una degradacion aceptable frente a perder el archivo.
                        int deducido = deducirCountDelTipo(el.getAttribute("type"));
                        if (deducido > 1) {
                            el.setAttribute("count", String.valueOf(deducido));
                            toExpand.add(el);
                            // El documento cambió aunque el elemento no se expanda (los DA
                            // ya no se expanden): sin esto, a SclParser le llegaria el
                            // count no numerico original y el archivo entero fallaria.
                            modified = true;
                            Log.i(TAG, "preprocess: count='" + countStr + "' no numerico en "
                                    + tag + " '" + el.getAttribute("name")
                                    + "' - deducido " + deducido + " del tipo");
                        } else {
                            el.removeAttribute("count");
                            modified = true;
                            Log.w(TAG, "preprocess: count='" + countStr + "' no numerico en "
                                    + tag + " '" + el.getAttribute("name")
                                    + "' y no deducible - se quita el atributo");
                        }
                    }
                }

                for (Element el : toExpand) {
                    // Los DA quedan como arreglo: iec61850bean los arma como Array, que es
                    // lo que expone un IED real (crvPts(0), crvPts(1)...). Expandirlos los
                    // volvia atributos sueltos con otros nombres (crvPts00...) y sus valores
                    // se perdian, porque los DAI/SDI del archivo los nombran "crvPts" con ix.
                    // Desde 2026-10-21 el SclParser parcheado lee ix. SDO y BDA si se
                    // expanden: la libreria no arma arreglos de ninguno de los dos.
                    if ("DA".equals(tag)) continue;
                    int count  = Integer.parseInt(el.getAttribute("count").trim());
                    String name = el.getAttribute("name");
                    Node parent = el.getParentNode();
                    String nsUri    = el.getNamespaceURI();
                    String localTag = el.getLocalName();
                    int digits = String.valueOf(count - 1).length(); // e.g. 50 → 2 dígitos
                    NamedNodeMap attrs = el.getAttributes();

                    // Expandir base 0: i=0..count-1 → phsAHar00..phsAHar50
                    for (int i = 0; i < count; i++) {
                        String indexedName = name + String.format("%0" + digits + "d", i);
                        Element newEl = (nsUri != null)
                            ? doc.createElementNS(nsUri, localTag)
                            : doc.createElement(localTag);

                        // Copiar atributos (excepto 'count' y declaraciones xmlns)
                        for (int j = 0; j < attrs.getLength(); j++) {
                            Attr attr = (Attr) attrs.item(j);
                            String attrLocal = attr.getLocalName() != null
                                ? attr.getLocalName() : attr.getName();
                            if ("count".equals(attrLocal)) continue;
                            if ("xmlns".equals(attrLocal) || attrLocal.startsWith("xmlns:")) continue;
                            newEl.setAttribute(attrLocal, attr.getValue());
                        }
                        newEl.setAttribute("name", indexedName);
                        parent.insertBefore(newEl, el);
                    }
                    parent.removeChild(el);
                    modified = true;
                    Log.d(TAG, "preprocess: " + tag + " '" + name + "' count=" + count
                            + " → " + name + "00.." + name
                            + String.format("%0" + digits + "d", count - 1));
                }
            }

            // Parche 2: EnumTypes incompletos (Siemens SIPROTEC5, ZIV, Ingeteam)
            int patchedEnums = patchMissingEnumOrdinals(doc);
            if (patchedEnums > 0) {
                modified = true;
                Log.i(TAG, "preprocess: " + patchedEnums + " EnumVal sintéticos agregados");
            }

            // Parche 3: ReportControl sin 'max' valido (Ingeteam)
            int patchedRcb = patchReportControlMax(doc);
            if (patchedRcb > 0) {
                modified = true;
                Log.i(TAG, "preprocess: " + patchedRcb + " ReportControl con max corregido");
            }

            // Parche 4: <Val> vacios (ZIV)
            int droppedVals = dropEmptyVals(doc);
            if (droppedVals > 0) {
                modified = true;
                Log.i(TAG, "preprocess: " + droppedVals + " <Val> vacios eliminados");
            }

            if (!modified) {
                Log.d(TAG, "preprocess: nada que parchear");
                return originalBytes;
            }

            // Serializar DOM parcheado de vuelta a InputStream
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            Log.i(TAG, "preprocess: completado ("
                    + originalBytes.length + " → " + out.size() + " bytes)");
            return out.toByteArray();

        } catch (Exception e) {
            Log.w(TAG, "preprocess: error (" + e.getMessage()
                    + ") — usando stream original");
            return originalBytes;
        }
    }

    /**
     * Rellena los EnumType a los que les faltan ordinals.
     *
     * Verificado sobre el bytecode de SclParser (iec61850bean 1.9.0): para
     * resolver un <Val> recorre los EnumVal del EnumType comparando el texto del
     * Val contra EnumVal.getId() (el *texto* del elemento) y, al coincidir, usa
     * el ord de ese EnumVal como valor. **No hay fallback por ordinal**: si
     * ningún EnumVal tiene ese texto, lanza SclParseException y **falla la carga
     * del archivo entero**, no sólo de ese nodo.
     *
     * Eso rompe dos familias de archivos reales:
     *
     *  - Siemens SIPROTEC5 trunca las definiciones (p.ej. Behavior define sólo
     *    {1,3,5} y omite 2 y 4), así que faltan tanto el ord como el texto.
     *  - ZIV e Ingeteam definen los EnumVal sólo con etiquetas ("on", "blocked",
     *    "High"...) mientras sus DAI traen <Val>1</Val> numéricos. Acá el ord
     *    existe, pero ningún EnumVal tiene "1" como texto.
     *
     * Por eso la condición es por **texto**, no por ordinal: para cada entero N
     * que aparezca en algún <Val>, si el EnumType no tiene ya un EnumVal cuyo
     * texto sea "N", se sintetiza <EnumVal ord="N">N</EnumVal>. Se le pone
     * ord=N —aun si ese ord ya está tomado por una etiqueta— justamente porque
     * el ord del EnumVal que coincide es el que termina siendo el valor: con
     * cualquier otro ord se guardaría un valor equivocado. Quedan dos EnumVal
     * con el mismo ord y distinto texto ("on" y "1"), y el bucle de la librería
     * los tolera: devuelve el primero que coincida por texto, y ambos resuelven
     * al mismo ord.
     *
     * Es deliberadamente sobre-inclusivo: puede agregar entradas que ningún DAI
     * usa. Eso no rompe nada —sólo deja un enum con más opciones que las reales—
     * y es preferible a que el archivo no cargue.
     *
     * @return cantidad de EnumVal sintéticos agregados
     */
    private static int patchMissingEnumOrdinals(Document doc) {
        NodeList enumTypes = doc.getElementsByTagNameNS("*", "EnumType");
        if (enumTypes.getLength() == 0) enumTypes = doc.getElementsByTagName("EnumType");
        if (enumTypes.getLength() == 0) return 0;

        // Paso 1: aprender del propio documento que ordinal le corresponde a cada
        // texto, mirando todos los <EnumVal> definidos en cualquier EnumType.
        // Los textos que aparezcan con ordinales CONTRADICTORIOS se descartan: sin
        // un ordinal univoco no se puede sintetizar nada correcto.
        Map<String, String> textoAOrd = new HashMap<>();
        Set<String> ambiguos = new HashSet<>();
        for (int i = 0; i < enumTypes.getLength(); i++) {
            NodeList children = enumTypes.item(i).getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() != Node.ELEMENT_NODE) continue;
                String ord = ((Element) child).getAttribute("ord").trim();
                if (ord.isEmpty()) continue;
                String texto = child.getTextContent().trim();
                if (texto.isEmpty()) continue;
                String previo = textoAOrd.put(texto, ord);
                if (previo != null && !previo.equals(ord)) ambiguos.add(texto);
            }
        }
        for (String a : ambiguos) textoAOrd.remove(a);

        // Paso 2: juntar los textos que se usan en <Val> y para los que se conoce
        // un ordinal. Un texto numerico es su propio ordinal; uno simbolico
        // ("status-only") solo sirve si el documento lo define en algun lado.
        Map<String, String> usados = new LinkedHashMap<>();
        NodeList valNodes = doc.getElementsByTagNameNS("*", "Val");
        if (valNodes.getLength() == 0) valNodes = doc.getElementsByTagName("Val");
        for (int i = 0; i < valNodes.getLength(); i++) {
            String texto = valNodes.item(i).getTextContent().trim();
            if (texto.isEmpty() || usados.containsKey(texto)) continue;
            try {
                Integer.parseInt(texto);
                usados.put(texto, texto);              // numerico: ord = el numero
            } catch (NumberFormatException e) {
                String ord = textoAOrd.get(texto);     // simbolico: ord aprendido
                if (ord != null) usados.put(texto, ord);
            }
        }
        if (usados.isEmpty()) return 0;

        // Paso 3: completar cada EnumType con los textos que le falten
        int patchCount = 0;
        for (int i = 0; i < enumTypes.getLength(); i++) {
            Element enumType = (Element) enumTypes.item(i);
            String nsUri = enumType.getNamespaceURI();

            // Textos ya presentes en este EnumType (que es por lo que busca la libreria),
            // y si el EnumType YA es ancho de por si.
            Set<String> definedTexts = new HashSet<>();
            boolean yaEsAncho = false;
            NodeList children = enumType.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    definedTexts.add(child.getTextContent().trim());
                    String o = ((Element) child).getAttribute("ord").trim();
                    if (!o.isEmpty() && !cabeEnInt8(o)) yaEsAncho = true;
                }
            }

            for (Map.Entry<String, String> e : usados.entrySet()) {
                if (definedTexts.contains(e.getKey())) continue;
                // Si el EnumType ya declara de por si un ordinal fuera del byte, ya es
                // ancho y filtrar no protege nada: solo perderia entradas legitimas y el
                // archivo podria no cargar, que es justo lo que este parche evita.
                if (!yaEsAncho && !cabeEnInt8(e.getValue())) continue;   // ver cabeEnInt8()
                Element synth = (nsUri != null && !nsUri.isEmpty())
                        ? doc.createElementNS(nsUri, "EnumVal")
                        : doc.createElement("EnumVal");
                synth.setAttribute("ord", e.getValue());
                synth.setTextContent(e.getKey());
                enumType.appendChild(synth);
                patchCount++;
            }
        }
        return patchCount;
    }

    /**
     * Un EnumVal sintetico solo se agrega si su ordinal entra en un byte con signo.
     *
     * El relleno de arriba es a proposito sobre-inclusivo, y durante mucho tiempo
     * se asumio que agregar opciones de mas "no rompe nada". Si rompe. SclParser
     * elige el ancho del Enum segun el mayor ordinal del EnumType: con un ordinal
     * fuera del rango de int8 el atributo deja de ser BdaInt8 y pasa a BdaInt16.
     *
     * Y ServerAssociation.getWriteResult() —verificado en el bytecode— exige
     * literalmente que ctlModel sea 'instanceof BdaInt8'; si no lo es, responde
     * object-access-denied y NI SIQUIERA llama al ServerEventListener. Resultado
     * observado con ABB_CID.CID: un <Val>724976</Val> de otro atributo cualquiera
     * se colaba en el EnumType ctlModel, lo ensanchaba a 16 bits y el servidor
     * rechazaba TODOS los mandos, incluso con ctlModel=1. El ControlEngine no se
     * ejecutaba nunca.
     *
     * Los ordinales que este parche necesita de verdad (los de ZIV e Ingeteam)
     * son de un digito, asi que acotar al rango de int8 conserva la tolerancia y
     * elimina el dano colateral.
     */
    private static boolean cabeEnInt8(String ord) {
        try {
            int v = Integer.parseInt(ord.trim());
            return v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Get summary information about a server model
     */
    public static String getModelSummary(ServerModel model) {
        if (model == null) {
            return "No model loaded";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Server Model Summary:\n");

        int ldCount = 0;
        int lnCount = 0;
        int doCount = 0;
        int daCount = 0;

        for (var ld : model.getChildren()) {
            ldCount++;
            for (var ln : ld.getChildren()) {
                lnCount++;
                for (var fcdo : ln.getChildren()) {
                    doCount++;
                    daCount += countBasicDataAttributes(fcdo);
                }
            }
        }

        sb.append("  Logical Devices: ").append(ldCount).append("\n");
        sb.append("  Logical Nodes: ").append(lnCount).append("\n");
        sb.append("  Data Objects: ").append(doCount).append("\n");
        sb.append("  Data Attributes: ").append(daCount);

        return sb.toString();
    }

    private static int countBasicDataAttributes(com.beanit.iec61850bean.ModelNode node) {
        int count = 0;
        if (node instanceof com.beanit.iec61850bean.BasicDataAttribute) {
            return 1;
        }
        for (var child : node.getChildren()) {
            count += countBasicDataAttributes(child);
        }
        return count;
    }
}

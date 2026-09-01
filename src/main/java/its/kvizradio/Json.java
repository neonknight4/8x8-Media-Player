package its.kvizradio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sitni citac JSON-a, za odgovore Radio Browser API-ja. Isti kao u HUB-u, da se
 * paket kasnije prekopira bez povlacenja Jacksona: objekat postaje Map, niz
 * List, broj Double, ostalo String / Boolean / null.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parsiraj(String tekst) {
        Json j = new Json(tekst);
        j.preskoci();
        return j.vrednost();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> mapa(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> lista(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    public static String tekst(Object o) {
        return o instanceof String t ? t : "";
    }

    public static double broj(Object o, double podrazumevano) {
        return o instanceof Double d ? d : podrazumevano;
    }

    /** Ugnjezdeni kljucevi, npr. put(odgovor, "station", "url_resolved"). */
    public static Object put(Object koren, String... kljucevi) {
        Object tekuci = koren;
        for (String k : kljucevi) {
            tekuci = mapa(tekuci).get(k);
            if (tekuci == null) {
                return null;
            }
        }
        return tekuci;
    }

    /**
     * Escapovan string sa navodnicima. Citac je glavni posao, ali omiljene
     * stanice treba i upisati, a to je jedini deo pisanja koji nam treba.
     */
    public static String navodnici(String tekst) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < tekst.length(); k++) {
            char c = tekst.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private Object vrednost() {
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return objekat();
            case '[':
                return niz();
            case '"':
                return string();
            case 't':
                i += 4;
                return Boolean.TRUE;
            case 'f':
                i += 5;
                return Boolean.FALSE;
            case 'n':
                i += 4;
                return null;
            default:
                return broj();
        }
    }

    private Map<String, Object> objekat() {
        Map<String, Object> mapa = new LinkedHashMap<>();
        i++; // {
        preskoci();
        if (s.charAt(i) == '}') {
            i++;
            return mapa;
        }
        while (true) {
            preskoci();
            String kljuc = string();
            preskoci();
            i++; // :
            preskoci();
            mapa.put(kljuc, vrednost());
            preskoci();
            char c = s.charAt(i++);
            if (c == '}') {
                return mapa;
            }
        }
    }

    private List<Object> niz() {
        List<Object> lista = new ArrayList<>();
        i++; // [
        preskoci();
        if (s.charAt(i) == ']') {
            i++;
            return lista;
        }
        while (true) {
            preskoci();
            lista.add(vrednost());
            preskoci();
            char c = s.charAt(i++);
            if (c == ']') {
                return lista;
            }
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++; // "
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e);
            }
        }
    }

    private Double broj() {
        int od = i;
        while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return Double.valueOf(s.substring(od, i));
    }

    private void preskoci() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}

package leakplanner;

import java.util.*;

/** Minimal JSON reader/writer (objects -> LinkedHashMap, arrays -> ArrayList, numbers -> Long/Double). */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("Trailing data in JSON");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        if (text == null || text.isBlank()) return new LinkedHashMap<>();
        try {
            Object o = parse(text);
            return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private Object value() {
        if (i >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String w) {
        if (!s.startsWith(w, i)) throw new IllegalArgumentException("Bad JSON at " + i);
        i += w.length();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws(); String k = string(); ws();
            if (s.charAt(i++) != ':') throw new IllegalArgumentException("Expected ':' at " + i);
            ws(); m.put(k, value()); ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw new IllegalArgumentException("Expected ',' at " + i);
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws(); l.add(value()); ws();
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw new IllegalArgumentException("Expected ',' at " + i);
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw new IllegalArgumentException("Expected string at " + i);
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(st, i);
        if (t.isEmpty()) throw new IllegalArgumentException("Bad JSON at " + st);
        if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
        return Long.parseLong(t);
    }

    // ---------------- writer ----------------
    public static String stringify(Object o, boolean pretty) {
        StringBuilder b = new StringBuilder();
        write(b, o, pretty ? 0 : -1);
        return b.toString();
    }

    private static void nl(StringBuilder b, int ind) {
        if (ind < 0) return;
        b.append('\n');
        for (int k = 0; k < ind; k++) b.append(' ');
    }

    private static void write(StringBuilder b, Object o, int ind) {
        int next = ind < 0 ? -1 : ind + 1;
        if (o == null) b.append("null");
        else if (o instanceof String) quote(b, (String) o);
        else if (o instanceof Boolean) b.append(o);
        else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) b.append((long) d);
            else b.append(d);
        } else if (o instanceof Number) b.append(o);
        else if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (m.isEmpty()) { b.append("{}"); return; }
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) b.append(',');
                first = false;
                nl(b, next);
                quote(b, String.valueOf(e.getKey()));
                b.append(ind < 0 ? ":" : ": ");
                write(b, e.getValue(), next);
            }
            nl(b, ind);
            b.append('}');
        } else if (o instanceof Collection) {
            Collection<?> c = (Collection<?>) o;
            if (c.isEmpty()) { b.append("[]"); return; }
            b.append('[');
            boolean first = true;
            for (Object v : c) {
                if (!first) b.append(',');
                first = false;
                nl(b, next);
                write(b, v, next);
            }
            nl(b, ind);
            b.append(']');
        } else quote(b, o.toString());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}

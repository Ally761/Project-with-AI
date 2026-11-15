package gameagent.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON reader/writer.
 * Parsed values are: Map&lt;String,Object&gt; (insertion ordered), List&lt;Object&gt;, String, Double, Boolean, null.
 */
public final class Json {
    private Json() {}

    // ---------------------------------------------------------------- parsing

    public static Object parse(String text) throws JsonException {
        if (text == null) throw new JsonException("null input");
        Parser p = new Parser(text);
        p.skipWs();
        Object v = p.value();
        p.skipWs();
        if (p.pos != text.length()) throw new JsonException("Trailing characters at " + p.pos);
        return v;
    }

    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object v = parse(text);
        if (v instanceof Map<?, ?>) return asObject(v);
        throw new JsonException("Expected a JSON object");
    }

    /**
     * LLMs often wrap JSON in markdown fences or add chatter. Pulls out the outermost {...} block.
     * Returns the input unchanged if no braces are found.
     */
    public static String extractObject(String raw) {
        if (raw == null) return "";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) return raw.substring(start, end + 1);
        return raw.trim();
    }

    /** Parse an object out of possibly-messy LLM output. */
    public static Map<String, Object> parseLenientObject(String raw) throws JsonException {
        return parseObject(extractObject(raw));
    }

    private static final class Parser {
        final String s;
        int pos = 0;

        Parser(String s) {
            this.s = s;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        Object value() throws JsonException {
            if (pos >= s.length()) throw new JsonException("Unexpected end of input");
            char c = s.charAt(pos);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default:
                    if (c == '-' || Character.isDigit(c)) return number();
                    throw new JsonException("Unexpected '" + c + "' at " + pos);
            }
        }

        void expect(String word) throws JsonException {
            if (!s.startsWith(word, pos)) throw new JsonException("Expected " + word + " at " + pos);
            pos += word.length();
        }

        Map<String, Object> object() throws JsonException {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') { pos++; return m; }
            while (true) {
                skipWs();
                if (peek() != '"') throw new JsonException("Expected string key at " + pos);
                String key = string();
                skipWs();
                if (peek() != ':') throw new JsonException("Expected ':' at " + pos);
                pos++;
                skipWs();
                m.put(key, value());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') return m;
                if (c != ',') throw new JsonException("Expected ',' or '}' at " + (pos - 1));
            }
        }

        List<Object> array() throws JsonException {
            List<Object> l = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') { pos++; return l; }
            while (true) {
                skipWs();
                l.add(value());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') return l;
                if (c != ',') throw new JsonException("Expected ',' or ']' at " + (pos - 1));
            }
        }

        char peek() throws JsonException {
            if (pos >= s.length()) throw new JsonException("Unexpected end of input");
            return s.charAt(pos);
        }

        String string() throws JsonException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= s.length()) throw new JsonException("Unterminated string");
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (pos >= s.length()) throw new JsonException("Bad escape");
                char e = s.charAt(pos++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) throw new JsonException("Bad unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new JsonException("Bad unicode escape at " + pos);
                        }
                        pos += 4;
                        break;
                    default: throw new JsonException("Bad escape \\" + e);
                }
            }
        }

        Double number() throws JsonException {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            try {
                return Double.valueOf(s.substring(start, pos));
            } catch (NumberFormatException ex) {
                throw new JsonException("Bad number at " + start);
            }
        }
    }

    // ---------------------------------------------------------------- writing

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) { sb.append("{}"); return; }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, pretty, depth + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, depth + 1);
            }
            newline(sb, pretty, depth);
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            boolean first = true;
            sb.append('[');
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, pretty, depth + 1);
                write(sb, o, pretty, depth + 1);
            }
            if (!first) newline(sb, pretty, depth);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, boolean pretty, int depth) {
        if (!pretty) return;
        sb.append('\n');
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- typed access helpers

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object o) {
        return o instanceof List<?> ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key) {
        return str(m, key, null);
    }

    public static String str(Map<String, Object> m, String key, String def) {
        if (m == null) return def;
        Object v = m.get(key);
        if (v == null) return def;
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? (Object) n.longValue() : v);
        return def;
    }

    public static double num(Map<String, Object> m, String key, double def) {
        if (m == null) return def;
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) { }
        }
        return def;
    }

    public static int integer(Map<String, Object> m, String key, int def) {
        return (int) Math.round(num(m, key, def));
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        if (m == null) return def;
        Object v = m.get(key);
        return v instanceof Boolean b ? b : def;
    }

    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        return m == null ? null : asObject(m.get(key));
    }

    public static List<Object> list(Map<String, Object> m, String key) {
        List<Object> l = m == null ? null : asList(m.get(key));
        return l == null ? new ArrayList<>() : l;
    }

    /** Reads a list of strings (non-string entries are skipped; a lone string becomes a one-item list). */
    public static List<String> strList(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<>();
        if (m == null) return out;
        Object v = m.get(key);
        if (v instanceof String s) {
            if (!s.isBlank()) out.add(s);
        } else if (v instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s && !s.isBlank()) out.add(s);
        }
        return out;
    }
}

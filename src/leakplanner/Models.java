package leakplanner;

import java.util.*;

/** Plain data classes. Each can convert to/from a JSON-friendly Map. */
public final class Models {
    private Models() {}

    static String str(Object o) { return o == null ? null : o.toString(); }
    static Double dbl(Object o) { return o == null ? null : ((Number) o).doubleValue(); }
    static Long lng(Object o) { return o == null ? null : ((Number) o).longValue(); }
    static Integer intg(Object o) { return o == null ? null : ((Number) o).intValue(); }

    public static class Base {
        public String name; public double lat, lng;
        Base() {}
        Base(String name, double lat, double lng) { this.name = name; this.lat = lat; this.lng = lng; }
        Map<String, Object> toMap() { return new LinkedHashMap<>(Map.of("name", name, "lat", lat, "lng", lng)); }
        static Base from(Map<String, Object> m) { return new Base(str(m.get("name")), dbl(m.get("lat")), dbl(m.get("lng"))); }
    }

    public static class User {
        public int id; public String username, role, name, hash;
        User() {}
        User(int id, String username, String role, String name, String hash) {
            this.id = id; this.username = username; this.role = role; this.name = name; this.hash = hash;
        }
        /** Public view (never includes the password hash). */
        Map<String, Object> toPublic() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("username", username); m.put("role", role); m.put("name", name);
            return m;
        }
        Map<String, Object> toMap() { Map<String, Object> m = toPublic(); m.put("hash", hash); return m; }
        static User from(Map<String, Object> m) {
            return new User(intg(m.get("id")), str(m.get("username")), str(m.get("role")), str(m.get("name")), str(m.get("hash")));
        }
    }

    public static class Leak {
        public int id; public String name, severity, status = "Reported", notes = "", source = "manual";
        public double lat, lng, pressure, flow;
        public Integer assignedTo, sensorId; public long createdAt; public Long resolvedAt;

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("name", name); m.put("lat", lat); m.put("lng", lng); m.put("severity", severity);
            m.put("pressure", pressure); m.put("flow", flow); m.put("status", status); m.put("assignedTo", assignedTo);
            m.put("notes", notes); m.put("source", source); m.put("createdAt", createdAt);
            if (sensorId != null) m.put("sensorId", sensorId);
            if (resolvedAt != null) m.put("resolvedAt", resolvedAt);
            return m;
        }
        static Leak from(Map<String, Object> m) {
            Leak l = new Leak();
            l.id = intg(m.get("id")); l.name = str(m.get("name")); l.lat = dbl(m.get("lat")); l.lng = dbl(m.get("lng"));
            l.severity = str(m.get("severity")); l.pressure = dbl(m.get("pressure")); l.flow = dbl(m.get("flow"));
            l.status = str(m.get("status")); l.assignedTo = intg(m.get("assignedTo")); l.notes = str(m.get("notes"));
            l.source = str(m.get("source")); l.createdAt = lng(m.get("createdAt"));
            l.sensorId = intg(m.get("sensorId")); l.resolvedAt = lng(m.get("resolvedAt"));
            return l;
        }
    }

    public static class Sample {
        public long t; public double p, f;
        Sample(long t, double p, double f) { this.t = t; this.p = p; this.f = f; }
    }

    public static class Sensor {
        public int id; public String name;
        public double lat, lng, basePressure, baseFlow, pressure, flow;
        public long updatedAt;
        public List<Sample> history = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("name", name); m.put("lat", lat); m.put("lng", lng);
            m.put("basePressure", basePressure); m.put("baseFlow", baseFlow);
            m.put("pressure", pressure); m.put("flow", flow); m.put("updatedAt", updatedAt);
            List<Map<String, Object>> h = new ArrayList<>();
            for (Sample s : history) h.add(new LinkedHashMap<>(Map.of("t", s.t, "p", s.p, "f", s.f)));
            m.put("history", h);
            return m;
        }
        @SuppressWarnings("unchecked")
        static Sensor from(Map<String, Object> m) {
            Sensor s = new Sensor();
            s.id = intg(m.get("id")); s.name = str(m.get("name")); s.lat = dbl(m.get("lat")); s.lng = dbl(m.get("lng"));
            s.basePressure = dbl(m.get("basePressure")); s.baseFlow = dbl(m.get("baseFlow"));
            s.pressure = dbl(m.get("pressure")); s.flow = dbl(m.get("flow")); s.updatedAt = lng(m.get("updatedAt"));
            Object h = m.get("history");
            if (h instanceof List) for (Object o : (List<Object>) h) {
                Map<String, Object> x = (Map<String, Object>) o;
                s.history.add(new Sample(lng(x.get("t")), dbl(x.get("p")), dbl(x.get("f"))));
            }
            return s;
        }
    }

    public static class LogEntry {
        public long t; public String who, msg;
        LogEntry(long t, String who, String msg) { this.t = t; this.who = who; this.msg = msg; }
        Map<String, Object> toMap() { return new LinkedHashMap<>(Map.of("t", t, "who", who, "msg", msg)); }
    }
}

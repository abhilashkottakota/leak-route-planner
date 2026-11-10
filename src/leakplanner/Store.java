package leakplanner;

import leakplanner.Models.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** JSON-file storage (swap for SQLite/MySQL via JDBC in production). All access is synchronized on this object. */
public class Store {
    public int nextId = 100;
    public Base base;
    public Map<String, Double> distances = new LinkedHashMap<>();
    public List<Leak> leaks = new ArrayList<>();
    public List<User> users = new ArrayList<>();
    public List<Sensor> sensors = new ArrayList<>();
    public List<LogEntry> log = new ArrayList<>();
    private final Path file;

    public Store(Path file) {
        this.file = file;
        if (Files.exists(file)) load(); else seed();
        save();
    }

    public static long now() { return System.currentTimeMillis(); }

    // ---------- seed data ----------
    private void seed() {
        long n = now(), H = 3_600_000L;
        base = new Base("Chennai Central Depot", 13.0827, 80.2707);
        Object[][] L = {
            {1, "T. Nagar Main Rd", 13.0418, 80.2341, "Critical", 1.6, 210, 30},
            {2, "Adyar Junction", 13.0012, 80.2565, "High", 2.4, 165, 20},
            {3, "Anna Nagar 2nd Ave", 13.085, 80.2101, "Medium", 3.1, 140, 10},
            {4, "Velachery Bypass", 12.9815, 80.218, "Low", 3.6, 128, 5},
            {5, "Guindy Industrial Est.", 13.0067, 80.2206, "High", 2.2, 180, 14},
            {6, "Mylapore Tank St", 13.0368, 80.2676, "Medium", 3.0, 150, 8}};
        for (Object[] r : L) {
            Leak l = new Leak();
            l.id = (int) r[0]; l.name = (String) r[1]; l.lat = (double) r[2]; l.lng = (double) r[3]; l.severity = (String) r[4];
            l.pressure = (double) r[5]; l.flow = ((Number) r[6]).doubleValue(); l.createdAt = n - (long) (((Number) r[7]).doubleValue() * H);
            leaks.add(l);
        }
        users.add(new User(1, "admin", "admin", "Administrator", Auth.hashPassword("admin123")));
        users.add(new User(2, "engineer", "engineer", "Field Engineer 1", Auth.hashPassword("eng123")));
        users.add(new User(3, "engineer2", "engineer", "Field Engineer 2", Auth.hashPassword("eng123")));
        Object[][] S = {
            {201, "Sensor Perambur", 13.1143, 80.2329}, {202, "Sensor Kodambakkam", 13.0521, 80.2255},
            {203, "Sensor Sholinganallur", 12.9010, 80.2279}, {204, "Sensor Porur", 13.0382, 80.1565},
            {205, "Sensor Nungambakkam", 13.0569, 80.2425}};
        for (Object[] r : S) {
            Sensor s = new Sensor();
            s.id = (int) r[0]; s.name = (String) r[1]; s.lat = (double) r[2]; s.lng = (double) r[3];
            s.basePressure = s.pressure = 4; s.baseFlow = s.flow = 120; s.updatedAt = n;
            sensors.add(s);
        }
    }

    // ---------- persistence ----------
    @SuppressWarnings("unchecked")
    private void load() {
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            nextId = Models.intg(m.get("nextId"));
            base = Base.from((Map<String, Object>) m.get("base"));
            ((Map<String, Object>) m.get("distances")).forEach((k, v) -> distances.put(k, ((Number) v).doubleValue()));
            for (Object o : (List<Object>) m.get("leaks")) leaks.add(Leak.from((Map<String, Object>) o));
            for (Object o : (List<Object>) m.get("users")) users.add(User.from((Map<String, Object>) o));
            for (Object o : (List<Object>) m.get("sensors")) sensors.add(Sensor.from((Map<String, Object>) o));
            Object lg = m.get("log");
            if (lg instanceof List) for (Object o : (List<Object>) lg) {
                Map<String, Object> e = (Map<String, Object>) o;
                log.add(new LogEntry(Models.lng(e.get("t")), Models.str(e.get("who")), Models.str(e.get("msg"))));
            }
        } catch (IOException e) {
            throw new RuntimeException("Cannot read " + file, e);
        }
    }

    public void save() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nextId", nextId); m.put("base", base.toMap()); m.put("distances", distances);
        m.put("leaks", leaks.stream().map(Leak::toMap).toList());
        m.put("users", users.stream().map(User::toMap).toList());
        m.put("sensors", sensors.stream().map(Sensor::toMap).toList());
        m.put("log", log.stream().map(LogEntry::toMap).toList());
        try {
            Files.writeString(file, Json.stringify(m, true), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Cannot write " + file, e);
        }
    }

    // ---------- helpers ----------
    public void log(String msg, String who) {
        log.add(new LogEntry(now(), who, msg));
        while (log.size() > 50) log.remove(0);
    }

    public void hist(Sensor s) {
        s.history.add(new Sample(now(), s.pressure, s.flow));
        while (s.history.size() > 20) s.history.remove(0);
    }
}

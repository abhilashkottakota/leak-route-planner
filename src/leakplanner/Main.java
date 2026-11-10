package leakplanner;

import com.sun.net.httpserver.*;
import leakplanner.Models.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Executors;

/** TSP route optimisation for water-leak repair (Java / JDK HttpServer REST API). */
public class Main {
    static final Set<String> STATUSES = Set.of("Reported", "Assigned", "In Progress", "Resolved");
    static final Random RND = new Random();
    static Store db;
    static Path publicDir;

    /** Thrown to send a JSON error response {"error": message}. */
    static class ApiException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;
        ApiException(int status, String msg) { super(msg); this.status = status; }
    }

    @FunctionalInterface interface Route { Object handle(HttpExchange ex, User me, Map<String, Object> body) throws Exception; }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "5000"));
        db = new Store(Path.of(System.getenv().getOrDefault("DATA_FILE", "data.json")));
        publicDir = Path.of(System.getenv().getOrDefault("PUBLIC_DIR", "public")).toAbsolutePath().normalize();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", Main::api);
        server.createContext("/", Main::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Route planner running on http://localhost:" + port + "  (Ctrl+C to stop)");
    }

    // =====================================================================
    // Request plumbing
    // =====================================================================
    static void send(HttpExchange ex, int status, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length > 0) try (OutputStream o = ex.getResponseBody()) { o.write(data); }
        ex.close();
    }

    static void sendJson(HttpExchange ex, int status, Object body) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.stringify(body, false).getBytes(StandardCharsets.UTF_8));
    }

    static void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            Map<String, Object> body = Json.parseObject(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Object result;
            int status = 200;
            synchronized (db) {   // single lock keeps the JSON store consistent
                Route r = null; boolean needAuth = true;
                if (path.equals("/api/login") && method.equals("POST")) { r = Main::login; needAuth = false; }
                else if (path.equals("/api/signup") && method.equals("POST")) { r = Main::signup; needAuth = false; status = 201; }
                else if (path.equals("/api/report") && method.equals("POST")) { r = Main::publicReport; needAuth = false; status = 201; }
                else if (path.equals("/api/me") && method.equals("GET")) r = (e, me, b) -> me.toPublic();
                else if (path.equals("/api/state") && method.equals("GET")) r = Main::state;
                else if (path.equals("/api/leaks") && method.equals("POST")) { r = Main::addLeak; status = 201; }
                else if (path.startsWith("/api/leaks/") && (method.equals("PUT") || method.equals("DELETE"))) r = Main::leak;
                else if (path.equals("/api/sensors/reading") && method.equals("POST")) r = Main::reading;
                else if (path.equals("/api/sensors/simulate") && method.equals("POST")) r = Main::simulate;
                else if (path.equals("/api/distances") && method.equals("PUT")) r = Main::distances;
                else if (path.equals("/api/route") && method.equals("POST")) r = Main::route;
                if (r == null) throw new ApiException(404, "Not found");
                result = r.handle(ex, needAuth ? auth(ex) : null, body);
            }
            sendJson(ex, status, result);
        } catch (ApiException e) {
            sendJson(ex, e.status, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(ex, 500, Map.of("error", "Server error"));
        }
    }

    static void staticFile(HttpExchange ex) throws IOException {
        String p = ex.getRequestURI().getPath();
        if (p.equals("/")) p = "/index.html";
        Path f = publicDir.resolve(p.substring(1)).normalize();
        if (!f.startsWith(publicDir) || !Files.isRegularFile(f) || !ex.getRequestMethod().equals("GET")) {
            sendJson(ex, 404, Map.of("error", "Not found"));
            return;
        }
        String n = f.getFileName().toString().toLowerCase();
        String type = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".png") ? "image/png"
                : n.endsWith(".css") ? "text/css" : n.endsWith(".js") ? "text/javascript" : n.endsWith(".svg") ? "image/svg+xml"
                : n.endsWith(".ico") ? "image/x-icon" : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(f));
    }

    // =====================================================================
    // Auth helpers
    // =====================================================================
    static User auth(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        Integer uid = h == null ? null : Auth.verifyToken(h.replace("Bearer ", "").trim());
        User me = null;
        if (uid != null) for (User u : db.users) if (u.id == uid) me = u;
        if (me == null) throw new ApiException(401, "Not authenticated");
        return me;
    }

    static boolean isAdmin(User u) { return "admin".equals(u.role); }

    static void adminOnly(User u) { if (!isAdmin(u)) throw new ApiException(403, "Admin only"); }

    static double toDouble(Object o) {
        double d;
        if (o instanceof Number) d = ((Number) o).doubleValue();
        else if (o instanceof String) d = Double.parseDouble(((String) o).trim());
        else throw new NumberFormatException("not a number");
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new NumberFormatException("not finite");
        return d;
    }

    /** Python's {@code float(x or default)}: null, "" and 0 fall back to the default. */
    static double numOr(Object o, double def) {
        if (o == null || "".equals(o) || (o instanceof Number && ((Number) o).doubleValue() == 0)) return def;
        return toDouble(o);
    }

    static double round2(double d) { return Math.round(d * 100) / 100.0; }

    // =====================================================================
    // Endpoints
    // =====================================================================
    static Object login(HttpExchange ex, User unused, Map<String, Object> b) {
        User u = null;
        for (User x : db.users) if (x.username.equals(b.get("username"))) u = x;
        Object pw = b.get("password");
        if (u == null || !Auth.checkPassword(u.hash, pw == null ? "" : pw.toString()))
            throw new ApiException(401, "Invalid username or password");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("token", Auth.issueToken(u.id)); r.put("user", u.toPublic());
        return r;
    }

    static Object signup(HttpExchange ex, User unused, Map<String, Object> b) {
        String name = b.get("name") == null ? "" : b.get("name").toString().trim();
        String username = b.get("username") == null ? "" : b.get("username").toString().trim().toLowerCase(Locale.ROOT);
        String password = b.get("password") == null ? "" : b.get("password").toString();
        if (name.isEmpty() || name.length() > 80) throw new ApiException(400, "Enter your name (up to 80 characters)");
        if (!username.matches("[a-z0-9_.-]{3,32}")) throw new ApiException(400, "Username must be 3–32 characters (letters, numbers, ., _ or -)");
        if (password.length() < 8 || password.length() > 128) throw new ApiException(400, "Password must be 8–128 characters");
        for (User u : db.users) if (u.username.equalsIgnoreCase(username)) throw new ApiException(409, "That username is already taken");
        int id = db.users.stream().mapToInt(u -> u.id).max().orElse(0) + 1;
        User u = new User(id, username, "engineer", name, Auth.hashPassword(password));
        db.users.add(u);
        db.log("Engineer account created: " + name, name);
        db.save();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", Auth.issueToken(u.id)); result.put("user", u.toPublic());
        return result;
    }

    static Object publicReport(HttpExchange ex, User unused, Map<String, Object> b) {
        String reporter = b.get("name") == null ? "" : b.get("name").toString().trim();
        String contact = b.get("contact") == null ? "" : b.get("contact").toString().trim();
        String location = b.get("location") == null ? "" : b.get("location").toString().trim();
        String details = b.get("details") == null ? "" : b.get("details").toString().trim();
        if (reporter.isEmpty() || reporter.length() > 80) throw new ApiException(400, "Enter your name (up to 80 characters)");
        if (contact.isEmpty() || contact.length() > 80) throw new ApiException(400, "Enter a phone number or other contact (up to 80 characters)");
        if (location.isEmpty() || location.length() > 160) throw new ApiException(400, "Enter the leak location or nearest address (up to 160 characters)");
        if (details.length() > 1000) throw new ApiException(400, "Details must be 1000 characters or less");
        double lat, lng;
        try { lat = toDouble(b.get("lat")); lng = toDouble(b.get("lng")); }
        catch (RuntimeException e) { throw new ApiException(400, "Enter valid latitude and longitude"); }
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) throw new ApiException(400, "Coordinates are outside the valid range");
        Leak l = new Leak();
        l.id = db.nextId++; l.name = location; l.lat = lat; l.lng = lng; l.severity = "Medium";
        l.status = "Reported"; l.source = "public"; l.createdAt = Store.now();
        l.notes = "Public report by: " + reporter + " | Contact: " + contact + (details.isEmpty() ? "" : " | Details: " + details);
        db.leaks.add(l);
        db.log("Public leak report received: " + location, reporter);
        db.save();
        return Map.of("ok", true, "id", l.id);
    }

    static Object state(HttpExchange ex, User me, Map<String, Object> b) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("base", db.base.toMap());
        r.put("users", db.users.stream().filter(u -> u.role.equals("engineer")).map(User::toPublic).toList());
        r.put("sensors", db.sensors.stream().map(Sensor::toMap).toList());
        r.put("leaks", db.leaks.stream().filter(l -> isAdmin(me) || (l.assignedTo != null && l.assignedTo == me.id))
                .map(Logic::withPriority).toList());
        r.put("distances", db.distances);
        List<Map<String, Object>> log = new ArrayList<>();
        if (isAdmin(me)) for (int i = db.log.size() - 1; i >= 0 && log.size() < 12; i--) log.add(db.log.get(i).toMap());
        r.put("log", log);
        return r;
    }

    static Object addLeak(HttpExchange ex, User me, Map<String, Object> b) {
        adminOnly(me);
        String bad = "name, lat, lng and valid severity required";
        double lat, lng;
        try { lat = toDouble(b.get("lat")); lng = toDouble(b.get("lng")); }
        catch (RuntimeException e) { throw new ApiException(400, bad); }
        Object name = b.get("name"), sev = b.get("severity");
        if (name == null || name.toString().isEmpty() || !(sev instanceof String) || !Logic.SEV.containsKey(sev)) throw new ApiException(400, bad);
        Leak l = new Leak();
        try {
            l.pressure = numOr(b.get("pressure"), 3); l.flow = numOr(b.get("flow"), 120);
        } catch (RuntimeException e) { throw new ApiException(400, "pressure and flow must be numbers"); }
        l.id = db.nextId++; l.name = name.toString(); l.lat = lat; l.lng = lng; l.severity = (String) sev;
        l.notes = b.get("notes") == null ? "" : b.get("notes").toString(); l.createdAt = Store.now();
        db.leaks.add(l);
        db.log("Leak registered: " + l.name + " (" + l.severity + ")", me.name);
        db.save();
        return Logic.withPriority(l);
    }

    static Object leak(HttpExchange ex, User me, Map<String, Object> b) {
        String path = ex.getRequestURI().getPath();
        int lid;
        try { lid = Integer.parseInt(path.substring("/api/leaks/".length())); }
        catch (NumberFormatException e) { throw new ApiException(404, "Not found"); }
        Leak l = null;
        for (Leak x : db.leaks) if (x.id == lid) l = x;
        if (l == null) throw new ApiException(404, "Not found");

        if (ex.getRequestMethod().equals("DELETE")) {
            adminOnly(me);
            db.leaks.remove(l);
            db.log("Leak deleted: " + l.name, me.name);
            db.save();
            return Map.of("ok", 1);
        }
        if (!isAdmin(me) && !(l.assignedTo != null && l.assignedTo == me.id)) throw new ApiException(403, "Admin only");
        try {
            if (b.containsKey("status")) {
                if (!(b.get("status") instanceof String) || !STATUSES.contains(b.get("status"))) throw new ApiException(400, "Invalid status");
                l.status = (String) b.get("status");
            }
            if (b.containsKey("notes")) l.notes = b.get("notes") == null ? "" : b.get("notes").toString();
            if (isAdmin(me)) {
                if (b.containsKey("name")) l.name = String.valueOf(b.get("name"));
                if (b.containsKey("lat")) l.lat = toDouble(b.get("lat"));
                if (b.containsKey("lng")) l.lng = toDouble(b.get("lng"));
                if (b.containsKey("pressure")) l.pressure = toDouble(b.get("pressure"));
                if (b.containsKey("flow")) l.flow = toDouble(b.get("flow"));
                if (b.containsKey("severity")) {
                    if (!(b.get("severity") instanceof String) || !Logic.SEV.containsKey(b.get("severity"))) throw new ApiException(400, "Invalid severity");
                    l.severity = (String) b.get("severity");
                }
                if (b.containsKey("assignedTo")) {
                    Object a = b.get("assignedTo");
                    l.assignedTo = a == null ? null : (int) toDouble(a);
                }
            }
        } catch (NumberFormatException e) { throw new ApiException(400, "Invalid number"); }
        if ("Resolved".equals(l.status) && l.resolvedAt == null) l.resolvedAt = Store.now();
        db.log(l.name + ": status " + l.status, me.name);
        db.save();
        return Logic.withPriority(l);
    }

    static Object reading(HttpExchange ex, User me, Map<String, Object> b) {
        Sensor s = null;
        for (Sensor x : db.sensors) if (String.valueOf(x.id).equals(String.valueOf(b.get("sensorId")))) s = x;
        if (s == null) throw new ApiException(404, "Unknown sensor");
        try { s.pressure = toDouble(b.get("pressure")); s.flow = toDouble(b.get("flow")); }
        catch (RuntimeException e) { throw new ApiException(400, "pressure and flow are required numbers"); }
        s.updatedAt = Store.now();
        db.hist(s);
        Leak l = Logic.detect(db, s);
        db.save();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sensor", s.toMap()); r.put("leakCreated", l == null ? null : Logic.withPriority(l));
        return r;
    }

    static double jitter() { return 1 + (RND.nextDouble() - .5) * .08; }

    static Object simulate(HttpExchange ex, User me, Map<String, Object> b) {
        adminOnly(me);
        List<Map<String, Object>> created = new ArrayList<>();
        for (Sensor s : db.sensors) {
            boolean bad = RND.nextDouble() < 0.3;
            s.pressure = round2(s.basePressure * (bad ? 0.45 + RND.nextDouble() * .35 : jitter()));
            s.flow = Math.rint(s.baseFlow * (bad ? 1.3 + RND.nextDouble() * .8 : jitter()));
            s.updatedAt = Store.now();
            db.hist(s);
            Leak l = Logic.detect(db, s);
            if (l != null) created.add(Logic.withPriority(l));
        }
        db.save();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sensors", db.sensors.stream().map(Sensor::toMap).toList()); r.put("created", created);
        return r;
    }

    static Object distances(HttpExchange ex, User me, Map<String, Object> b) {
        adminOnly(me);
        String x = String.valueOf(b.get("a")), y = String.valueOf(b.get("b"));
        String k = x.compareTo(y) <= 0 ? x + "-" + y : y + "-" + x;
        if (b.get("km") == null) db.distances.remove(k);
        else {
            try { db.distances.put(k, toDouble(b.get("km"))); }
            catch (RuntimeException e) { throw new ApiException(400, "km must be a number"); }
        }
        db.save();
        return db.distances;
    }

    static Object route(HttpExchange ex, User me, Map<String, Object> b) {
        Set<Integer> ids = new HashSet<>();
        try {
            Object raw = b.get("ids");
            if (raw instanceof List) for (Object o : (List<?>) raw) ids.add((int) toDouble(o));
        } catch (RuntimeException e) { throw new ApiException(400, "ids must be numbers"); }
        List<Leak> leaks = new ArrayList<>();
        for (Leak l : db.leaks)
            if (ids.contains(l.id) && (isAdmin(me) || (l.assignedTo != null && l.assignedTo == me.id))) leaks.add(l);
        if (leaks.isEmpty()) throw new ApiException(400, "Select at least one leak");
        Base start = db.base;
        Object st = b.get("start");
        if (st instanceof Map && ((Map<?, ?>) st).containsKey("lat")) {
            Map<?, ?> m = (Map<?, ?>) st;
            try { start = new Base(m.get("name") == null ? "Start" : m.get("name").toString(), toDouble(m.get("lat")), toDouble(m.get("lng"))); }
            catch (RuntimeException e) { throw new ApiException(400, "Invalid start coordinates"); }
        }
        return Logic.planRoute(db, start, leaks, b.get("mode") instanceof String ? (String) b.get("mode") : null, Boolean.TRUE.equals(b.get("returnToBase")));
    }
}

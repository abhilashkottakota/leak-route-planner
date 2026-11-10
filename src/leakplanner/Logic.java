package leakplanner;

import leakplanner.Models.*;
import java.util.*;

/** Domain logic: priority scoring, leak detection and TSP route optimisation. */
public final class Logic {
    private Logic() {}

    public static final Map<String, Integer> SEV = Map.of("Critical", 4, "High", 3, "Medium", 2, "Low", 1);

    // ---------- priority ----------
    public static int priority(Leak l) {
        double drop = Math.max(0, (4 - l.pressure) / 4) * 100;
        double age = Math.min(24, (Store.now() - l.createdAt) / 3600e3);
        double score = Math.min(100, SEV.get(l.severity) * 15 + drop * 0.25 + age * 0.6 + ("Resolved".equals(l.status) ? -100 : 0));
        return (int) Math.max(0, Math.rint(score));   // rint = round-half-even, same as Python round()
    }

    /** Leak as a JSON map including its computed priority (the "wp" helper in the Python version). */
    public static Map<String, Object> withPriority(Leak l) {
        Map<String, Object> m = l.toMap();
        m.put("priority", priority(l));
        return m;
    }

    // ---------- leak detection engine ----------
    /** Pressure drop / flow surge versus sensor baseline. Returns the auto-created leak or null. */
    public static Leak detect(Store db, Sensor s) {
        double dp = (s.basePressure - s.pressure) / s.basePressure;
        double df = (s.flow - s.baseFlow) / s.baseFlow;
        double score = Math.max(dp / 0.25, df / 0.30);
        if (score < 1) return null;
        for (Leak x : db.leaks) if (x.sensorId != null && x.sensorId == s.id && !"Resolved".equals(x.status)) return null;
        Leak l = new Leak();
        l.severity = score >= 2.2 ? "Critical" : score >= 1.7 ? "High" : score >= 1.3 ? "Medium" : "Low";
        l.id = db.nextId++;
        l.name = "Auto-detected near " + s.name; l.lat = s.lat; l.lng = s.lng; l.pressure = s.pressure; l.flow = s.flow;
        l.source = "sensor"; l.sensorId = s.id; l.createdAt = Store.now();
        l.notes = "Sensor anomaly: pressure " + fmt(s.pressure) + " bar (base " + fmt(s.basePressure) + "), flow "
                + fmt(s.flow) + " L/min (base " + fmt(s.baseFlow) + ").";
        db.leaks.add(l);
        db.log("Auto-detected " + l.severity + " leak near " + s.name, "system");
        return l;
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }

    // ---------- distances ----------
    public static double haversine(double lat1, double lng1, double lat2, double lng2) {   // km x 1.3 road factor
        double dla = Math.toRadians(lat2 - lat1), dlo = Math.toRadians(lng2 - lng1);
        double h = Math.pow(Math.sin(dla / 2), 2) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dlo / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(h)) * 1.3;
    }

    /** Exact TSP via dynamic programming (start at node 0). */
    public static int[] heldKarp(double[][] D, boolean ret) {
        int m = D.length - 1;
        if (m <= 0) return new int[]{0};
        int N = 1 << m;
        double INF = Double.POSITIVE_INFINITY;
        double[][] dp = new double[N][m];
        int[][] par = new int[N][m];
        for (double[] r : dp) Arrays.fill(r, INF);
        for (int[] r : par) Arrays.fill(r, -1);
        for (int j = 0; j < m; j++) dp[1 << j][j] = D[0][j + 1];
        for (int s = 1; s < N; s++)
            for (int j = 0; j < m; j++) {
                if ((s >> j & 1) == 0 || dp[s][j] == INF) continue;
                for (int k = 0; k < m; k++) {
                    if ((s >> k & 1) != 0) continue;
                    int t = s | 1 << k;
                    double c = dp[s][j] + D[j + 1][k + 1];
                    if (c < dp[t][k]) { dp[t][k] = c; par[t][k] = j; }
                }
            }
        int bj = 0; double best = INF;
        for (int j = 0; j < m; j++) {
            double c = dp[N - 1][j] + (ret ? D[j + 1][0] : 0);
            if (c < best) { best = c; bj = j; }
        }
        int[] rev = new int[m];
        int s = N - 1, j = bj, idx = 0;
        while (j >= 0) { rev[idx++] = j + 1; int p = par[s][j]; s ^= 1 << j; j = p; }
        int[] order = new int[m + 1];
        for (int a = 0; a < m; a++) order[a + 1] = rev[m - 1 - a];
        return order;
    }

    private static double tourLen(double[][] D, int[] p, boolean ret) {
        double t = 0;
        for (int i = 1; i < p.length; i++) t += D[p[i - 1]][p[i]];
        return t + (ret ? D[p[p.length - 1]][0] : 0);
    }

    /** Nearest neighbour + 2-opt for larger inputs. */
    public static int[] heuristic(double[][] D, boolean ret) {
        int n = D.length;
        boolean[] used = new boolean[n];
        int[] o = new int[n];
        used[0] = true;
        for (int i = 1; i < n; i++) {
            int bestV = -1;
            for (int v = 1; v < n; v++) if (!used[v] && (bestV < 0 || D[o[i - 1]][v] < D[o[i - 1]][bestV])) bestV = v;
            o[i] = bestV; used[bestV] = true;
        }
        boolean improved = true;
        while (improved) {
            improved = false;
            for (int i = 1; i < n - 1; i++)
                for (int k = i + 1; k < n; k++) {
                    int[] q = o.clone();
                    for (int a = i, b = k; a < b; a++, b--) { int t = q[a]; q[a] = q[b]; q[b] = t; }
                    if (tourLen(D, q, ret) < tourLen(D, o, ret) - 1e-9) { o = q; improved = true; }
                }
        }
        return o;
    }

    private static String key(Object a, Object b) {
        String x = String.valueOf(a), y = String.valueOf(b);
        return x.compareTo(y) <= 0 ? x + "-" + y : y + "-" + x;
    }

    private static double round2(double d) { return Math.round(d * 100) / 100.0; }

    /** Plans a route from {@code start} through {@code leaks}. */
    public static Map<String, Object> planRoute(Store db, Base start, List<Leak> leaks, String mode, boolean ret) {
        int n = leaks.size() + 1;
        Object[] ids = new Object[n]; double[] lat = new double[n], lng = new double[n];
        ids[0] = "start"; lat[0] = start.lat; lng[0] = start.lng;
        for (int i = 1; i < n; i++) { Leak l = leaks.get(i - 1); ids[i] = l.id; lat[i] = l.lat; lng[i] = l.lng; }

        double[][] D = new double[n][n], W = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                Double custom = db.distances.get(key(ids[i], ids[j]));
                D[i][j] = custom != null ? custom : haversine(lat[i], lng[i], lat[j], lng[j]);
                W[i][j] = "priority".equals(mode) && j > 0 ? D[i][j] * (1 - 0.4 * priority(leaks.get(j - 1)) / 100) : D[i][j];
            }
        int[] order = n <= 13 ? heldKarp(W, ret) : heuristic(W, ret);
        if (ret) { order = Arrays.copyOf(order, order.length + 1); order[order.length - 1] = 0; }

        List<Double> legs = new ArrayList<>(); double sum = 0;
        for (int i = 1; i < order.length; i++) { legs.add(round2(D[order[i - 1]][order[i]])); sum += legs.get(i - 1); }
        double total = round2(sum);

        List<Map<String, Object>> stops = new ArrayList<>();
        for (int i : order) {
            if (i == 0) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", "start"); s.put("name", start.name); s.put("lat", start.lat); s.put("lng", start.lng);
                stops.add(s);
            } else stops.add(withPriority(leaks.get(i - 1)));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("algorithm", n <= 13 ? "Held-Karp (exact TSP)" : "Nearest-neighbour + 2-opt");
        r.put("stops", stops); r.put("legs", legs); r.put("totalKm", total);
        r.put("etaMin", Math.round(total / 30 * 60));
        return r;
    }
}

# Route Optimization (Java)

Java port of the Python/Flask leak-route planner. Same REST API, same `public/index.html` frontend, same data model.
Zero dependencies: uses only the JDK (`com.sun.net.httpserver`), so it needs JDK 17+ and nothing else.

    ./run.sh            (Mac/Linux)   |   run.bat   (Windows)   ->  open http://localhost:5000

Logins: admin/admin123, engineer/eng123. REST API under `/api` (login, me, state, leaks, sensors/reading,
sensors/simulate, distances, route). Send `Authorization: Bearer <token>` from `POST /api/login`.

## Layout

| File | Python equivalent |
|---|---|
| `src/leakplanner/Main.java` - HTTP server, routing, all endpoints, static files | Flask routes |
| `src/leakplanner/Logic.java` - priority score, leak detection, Held-Karp, NN + 2-opt, route planning | "domain logic" section |
| `src/leakplanner/Store.java` + `Models.java` - `data.json` persistence, seed data, activity log | storage section |
| `src/leakplanner/Auth.java` - PBKDF2 password hashes, HMAC-signed 8 h tokens | werkzeug + itsdangerous |
| `src/leakplanner/Json.java` - small JSON reader/writer | `json` module |

Environment variables: `PORT` (5000), `SECRET` (token signing key), `DATA_FILE` (`data.json`), `PUBLIC_DIR` (`public`).

Notes: `data.json` from the Python version is structurally compatible, but its password hashes (werkzeug format) are not -
delete it and let the Java app create fresh seed data. Swap `Store` for JDBC (SQLite/MySQL) when you need real concurrency/production use.

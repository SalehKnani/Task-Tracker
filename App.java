import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/* ------------------------- MODEL ------------------------- */
class Task {
    public String id;
    public String title;
    public String status;     // todo | in_progress | done
    public String createdAt;  // ISO-8601 string
    public String updatedAt;  // ISO-8601 string
    public List<String> tags;
    public String notes;

    @Override
    public String toString() {
        return id + " | " + status + " | " + title;
    }
}

/* ------------------------- REPO (JSON via Gson) ------------------------- */
class JsonTaskRepository {
    private final Path dataFile;
    private final com.google.gson.Gson gson;
    private List<Task> cache = new ArrayList<>();

    JsonTaskRepository(String filePath) throws IOException {
        this.dataFile = Paths.get(filePath);
        this.gson = GsonFactory.make();               // Gson with Instant adapters
        if (!Files.exists(dataFile)) {
            save(); // create empty array file
        }
        load();
    }

    private synchronized void load() throws IOException {
        try (Reader r = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            var listType = new com.google.gson.reflect.TypeToken<List<Task>>() {
            }.getType();
            List<Task> loaded = gson.fromJson(r, listType);
            cache = (loaded == null) ? new ArrayList<>() : loaded;
        }
    }

    private synchronized void save() throws IOException {
        try (Writer w = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            gson.newBuilder().setPrettyPrinting().create().toJson(cache, w);
        }
    }

    /* ---------- CRUD ---------- */
    synchronized List<Task> listAll() {
        return new ArrayList<>(cache);
    }

    synchronized List<Task> listByStatus(String status) {
        List<Task> out = new ArrayList<>();
        for (Task t : cache) if (t.status.equalsIgnoreCase(status)) out.add(t);
        return out;
    }

    synchronized Task add(String title, List<String> tags, String notes) throws IOException {
        Task t = new Task();
        t.id = UUID.randomUUID().toString();
        t.title = title;
        t.status = "todo";
        String now = Instant.now().toString();
        t.createdAt = now;
        t.updatedAt = now;
        t.tags = (tags == null) ? List.of() : tags;
        t.notes = notes;
        cache.add(t);
        save();
        return t;
    }

    synchronized boolean update(String id, String title, String status, List<String> tags, String notes) throws IOException {
        Task t = find(id);
        if (t == null) return false;
        if (title != null && !title.isBlank()) t.title = title;
        if (status != null && !status.isBlank()) {
            if (!isValidStatus(status)) {
                System.out.println("Invalid status. Use: todo | in_progress | done");
                return false;
            }
            t.status = status;
        }
        if (tags != null) t.tags = tags;
        if (notes != null) t.notes = notes;
        t.updatedAt = Instant.now().toString();
        save();
        return true;
    }

    synchronized boolean delete(String id) throws IOException {
        boolean removed = cache.removeIf(t -> t.id.equals(id));
        if (removed) save();
        return removed;
    }

    private Task find(String id) {
        for (Task t : cache) if (t.id.equals(id)) return t;
        return null;
    }

    private boolean isValidStatus(String s) {
        return s.equals("todo") || s.equals("in_progress") || s.equals("done");
    }
}

/* ------------------------- GSON FACTORY (with Instant adapters) ------------------------- */
class GsonFactory {
    static com.google.gson.Gson make() {
        var b = new com.google.gson.GsonBuilder();
        // store Instant as String (ISO-8601) but tolerate invalid strings on read
        b.registerTypeAdapter(Instant.class, (com.google.gson.JsonSerializer<Instant>) (src, typeOfSrc, context) ->
                new com.google.gson.JsonPrimitive(src.toString()));
        b.registerTypeAdapter(Instant.class, (com.google.gson.JsonDeserializer<Instant>) (json, typeOfT, context) -> {
            try {
                return Instant.parse(json.getAsString());
            } catch (DateTimeParseException e) {
                return Instant.EPOCH;
            }
        });
        return b.create();
    }
}

/* ------------------------- CLI ------------------------- */
public class App {
    private static void usage() {
        System.out.println("""
                Task Tracker (single file)
                Usage:
                  java App list [all|todo|in_progress|done]
                  java App add "title" [tags=tag1,tag2] [notes="..."]
                  java App update <id> [title="..."] [status=todo|in_progress|done] [tags=a,b] [notes="..."]
                  java App delete <id>
                """);
    }

    public static void main(String[] args) throws Exception {
        Path jsonPath = Paths.get("tasks.json"); // your layout from the screenshot
        JsonTaskRepository repo = new JsonTaskRepository(jsonPath.toString());

        if (args.length == 0) {
            usage();
            return;
        }

        switch (args[0]) {
            case "list" -> {
                String which = (args.length > 1) ? args[1] : "all";
                List<Task> tasks = switch (which) {
                    case "todo", "in_progress", "done" -> repo.listByStatus(which);
                    default -> repo.listAll();
                };
                tasks.forEach(System.out::println);
            }
            case "add" -> {
                if (args.length < 2) {
                    System.out.println("add \"title\" [tags=...] [notes=...]");
                    return;
                }
                String title = args[1];
                List<String> tags = null;
                String notes = null;
                for (int i = 2; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("tags=")) tags = Arrays.asList(a.substring(5).split(","));
                    else if (a.startsWith("notes=")) notes = stripQuotes(a.substring(6));
                }
                Task t = repo.add(title, tags, notes);
                System.out.println("Added: " + t.id);
            }
            case "update" -> {
                if (args.length < 2) {
                    System.out.println("update <id> [title=...] [status=...] [tags=...] [notes=...]");
                    return;
                }
                String id = args[1];
                String title = null, status = null, notes = null;
                List<String> tags = null;
                for (int i = 2; i < args.length; i++) {
                    String a = args[i];
                    if (a.startsWith("title=")) title = stripQuotes(a.substring(6));
                    else if (a.startsWith("status=")) status = a.substring(7);
                    else if (a.startsWith("tags=")) tags = Arrays.asList(a.substring(5).split(","));
                    else if (a.startsWith("notes=")) notes = stripQuotes(a.substring(6));
                }
                boolean ok = repo.update(id, title, status, tags, notes);
                System.out.println(ok ? "Updated." : "Not found.");
            }
            case "delete" -> {
                if (args.length < 2) {
                    System.out.println("delete <id>");
                    return;
                }
                System.out.println(repo.delete(args[1]) ? "Deleted." : "Not found.");
            }
            default -> { /* ignore */ }
        }
    }

    private static String stripQuotes(String s) {
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))
            return s.substring(1, s.length() - 1);
        return s;
    }
}

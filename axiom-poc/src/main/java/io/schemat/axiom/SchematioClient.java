package io.schemat.axiom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Uses the JDK transport and Minecraft's Gson; no additional runtime dependencies. */
final class SchematioClient implements LibraryClient {
    static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;
    private final URI endpoint;

    SchematioClient(URI endpoint) {
        boolean local = "http".equals(endpoint.getScheme()) && List.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost());
        if ((!"https".equals(endpoint.getScheme()) && !local) || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Use an HTTPS API endpoint (or loopback HTTP for tests)");
        }
        this.endpoint = URI.create(endpoint.toString().replaceAll("/+$", "") + "/");
    }

    public Page search(String query, int page) throws IOException {
        String path = "schematics?per_page=8&page=" + Math.max(1, page) + "&sort=created_at&order=desc&search=" + encode(query);
        JsonObject root = JsonParser.parseString(new String(request(path, null, 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
        var builds = new ArrayList<Build>();
        for (var element : root.getAsJsonArray("data")) {
            var item = element.getAsJsonObject();
            String id = UUID.fromString(item.get("id").getAsString()).toString();
            var authors = item.getAsJsonArray("authors");
            String author = authors == null || authors.isEmpty() ? "" : authors.get(0).getAsJsonObject().get("last_seen_name").getAsString();
            builds.add(new Build(id, item.get("name").getAsString(), author,
                    item.has("short_id") && !item.get("short_id").isJsonNull() ? item.get("short_id").getAsString() : id));
        }
        var meta = root.getAsJsonObject("meta");
        return new Page(List.copyOf(builds), meta.get("current_page").getAsInt(), meta.get("last_page").getAsInt(), meta.get("total").getAsInt());
    }

    public byte[] download(String id) throws IOException {
        String safeId = UUID.fromString(id).toString();
        return request("schematics/" + safeId + "/download", "{\"format\":\"schem\"}", MAX_DOWNLOAD_BYTES);
    }

    private byte[] request(String path, String body, int limit) throws IOException {
        var connection = (HttpURLConnection) endpoint.resolve(path).toURL().openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", body == null ? "application/json" : "application/octet-stream");
        connection.setRequestProperty("User-Agent", "Schematio-Axiom/0.1.0-poc.1");
        try {
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (var out = connection.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException(switch (code) {
                case 401, 403 -> "This build requires access through your Schematio account.";
                case 404 -> "This build is no longer available.";
                case 429 -> "Schematio is busy. Try again shortly.";
                default -> "Schematio returned HTTP " + code + ".";
            });
            if (connection.getContentLengthLong() > limit) throw new IOException("Build exceeds this proof of concept's download limit.");
            try (InputStream input = connection.getInputStream()) { return readBounded(input, limit); }
        } finally { connection.disconnect(); }
    }

    static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (var output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Request cancelled.");
                if (output.size() + (long) read > limit) throw new IOException("Response exceeds this proof of concept's size limit.");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}

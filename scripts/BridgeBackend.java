import com.google.gson.*;
import com.sun.net.httpserver.*;
import com.github.schemat.nucleation.Schematic;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Local-only backend fixture for real Fabric <-> Paper/WorldEdit bridge checks. */
public class BridgeBackend {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var gson = new Gson();
        byte[] publicKey = key.getPublic().getEncoded();
        String rawKey = Base64.getEncoder().encodeToString(Arrays.copyOfRange(publicKey, publicKey.length - 32, publicKey.length));
        byte[] schematic;
        try (var s = new Schematic("bridge-smoke")) {
            s.setBlock(0, 0, 0, "minecraft:stone");
            s.setBlock(1, 0, 0, "minecraft:oak_log[axis=x]");
            schematic = s.toSchematic();
        }
        Files.write(output.resolve("fixture.schem"), schematic);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 38272), 0);
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath();
                byte[] body = ex.getRequestBody().readAllBytes();
                Files.writeString(output.resolve("requests.jsonl"), gson.toJson(Map.of("method", ex.getRequestMethod(), "path", path, "bytes", body.length)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                Object result;
                int status = 200;
                byte[] response = null;
                if (path.equals("/.well-known/schematio-keys.json")) {
                    result = Map.of("keys", List.of(Map.of("kid", "bridge-smoke", "alg", "Ed25519", "key", rawKey)));
                } else if (!"Bearer smoke.community.token".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    status = 401; result = Map.of("error", "unauthorized");
                } else if (path.equals("/api/v1/check")) {
                    result = Map.of("success", true);
                } else if (path.equals("/api/v1/plugin/community")) {
                    result = Map.of("community", Map.of("id", "bridge-smoke", "slug", "bridge-smoke"));
                } else if (path.equals("/api/v1/plugin/attest")) {
                    var request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                    String payload = gson.toJson(Map.of("communityId", "bridge-smoke", "tokenId", "test-token", "platform", request.get("platform").getAsString(), "nonce", request.get("nonce_hex").getAsString(), "issuedAt", System.currentTimeMillis() / 1000));
                    var signature = Signature.getInstance("Ed25519");
                    signature.initSign(key.getPrivate()); signature.update(payload.getBytes(StandardCharsets.UTF_8));
                    result = Map.of("payload", payload, "signature_base64", Base64.getEncoder().encodeToString(signature.sign()), "key_id", "bridge-smoke");
                } else if (path.equals("/api/v1/plugin/clipboard/resolve")) {
                    var request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                    if (!request.get("ref_id").getAsString().equals("bridge-fixture")) throw new AssertionError("Unexpected reference");
                    response = schematic; result = null;
                    ex.getResponseHeaders().set("X-Schematio-Format", "schem");
                } else if (path.equals("/api/v1/plugin/clipboard/drafts")) {
                    // Isolate the file part without decoding binary bytes as UTF-8.
                    String multipart = new String(body, StandardCharsets.ISO_8859_1);
                    String boundary = ex.getRequestHeaders().getFirst("Content-Type").split("boundary=")[1].replace("\"", "");
                    int start = multipart.indexOf("\r\n\r\n", multipart.indexOf("filename=")) + 4;
                    int end = multipart.indexOf("\r\n--" + boundary, start);
                    byte[] uploaded = Arrays.copyOfRange(body, start, end);
                    Files.write(output.resolve("uploaded.schem"), uploaded);
                    try (var s = Schematic.fromBytes(uploaded)) {
                        if (!s.getBlockName(0, 0, 0).orElseThrow().equals("minecraft:stone")) throw new AssertionError("Clipboard lost stone");
                        if (!s.getBlockName(1, 0, 0).orElseThrow().contains("oak_log")) throw new AssertionError("Clipboard lost log");
                    }
                    result = Map.of("draft_id", "bridge-draft", "web_url", "http://127.0.0.1:38272/drafts/bridge-draft");
                    status = 201;
                    Files.writeString(output.resolve("upload-passed.txt"), "WorldEdit serialized both loaded blocks into the draft upload.\n");
                } else { status = 404; result = Map.of("error", "not_found"); }
                if (response == null) response = gson.toJson(result).getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, response.length);
                ex.getResponseBody().write(response);
            } catch (Throwable t) {
                t.printStackTrace();
                ex.sendResponseHeaders(500, -1);
            } finally { ex.close(); }
        });
        server.start();
        System.out.println("Bridge fixture listening on 127.0.0.1:38272");
    }
}

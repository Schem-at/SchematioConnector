package io.schemat.axiom;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchematioClientTest {
    @Test void rejectsOversizeBodiesEvenWithoutContentLength() throws IOException {
        assertThrows(IOException.class, () -> SchematioClient.readBounded(new ByteArrayInputStream(new byte[1025]), 1024));
        assertEquals(1024, SchematioClient.readBounded(new ByteArrayInputStream(new byte[1024]), 1024).length);
    }
    @Test void permitsLocalTestsWithoutDisablingHttpsVerification() {
        assertDoesNotThrow(() -> new SchematioClient(URI.create("https://schemat.io/api/v1")));
        assertDoesNotThrow(() -> new SchematioClient(URI.create("http://127.0.0.1:18426/api/v1")));
        assertThrows(IllegalArgumentException.class, () -> new SchematioClient(URI.create("http://schemat.io/api/v1")));
        assertThrows(IllegalArgumentException.class, () -> new SchematioClient(URI.create("https://user:pass@schemat.io/api/v1")));
    }
    @Test void searchUsesTheRealApiContractAndEncodesPlayerText() throws Exception {
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/schematics", exchange -> {
            request.set(exchange.getRequestURI().getRawQuery());
            byte[] body = ("{\"data\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"short_id\":\"build1\","
                    + "\"name\":\"Oak & Birch\",\"authors\":[{\"last_seen_name\":\"Builder\"}]}],"
                    + "\"meta\":{\"current_page\":2,\"last_page\":3,\"total\":17}}") .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var client = new SchematioClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1"));
            var page = client.search("oak & birch", 2);
            assertTrue(request.get().contains("search=oak+%26+birch"));
            assertTrue(request.get().contains("page=2"));
            assertEquals("Oak & Birch", page.builds().getFirst().name());
            assertEquals("Builder", page.builds().getFirst().author());
            assertEquals("https://schemat.io/schematics/build1", page.builds().getFirst().webUrl());
            assertEquals(17, page.total());
        } finally { server.stop(0); }
    }
}

package io.schemat.axiom;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Data and transport boundary shared by the small add-on and the full Connector. */
public interface LibraryClient {
    record Build(String id, String name, String author, String shortId) {
        public String webUrl() { return "https://schemat.io/schematics/" + URLEncoder.encode(shortId, StandardCharsets.UTF_8); }
    }
    record Page(List<Build> builds, int number, int last, int total) {}
    Page search(String query, int page) throws IOException;
    byte[] download(String id) throws IOException;
}

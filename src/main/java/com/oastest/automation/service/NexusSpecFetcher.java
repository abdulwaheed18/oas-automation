package com.oastest.automation.service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;

/**
 * Downloads an artifact (ZIP) from a Nexus (or any HTTP) URL, extracts it in-memory and returns
 * the OpenAPI document found inside.
 */
@Service
public class NexusSpecFetcher {

    private final HttpClient http = InsecureHttp.client(Duration.ofSeconds(30));

    /** Result of locating a spec inside a downloaded archive. */
    public static class FetchedSpec {
        public final String fileName;
        public final String content;

        FetchedSpec(String fileName, String content) {
            this.fileName = fileName;
            this.content = content;
        }
    }

    public FetchedSpec fetchFromZipUrl(String zipUrl) {
        byte[] zipBytes = download(zipUrl);
        return extractSpec(zipBytes);
    }

    private byte[] download(String zipUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(zipUrl))
                    .timeout(Duration.ofSeconds(120))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalArgumentException("Download failed with HTTP " + response.statusCode()
                        + " for URL: " + zipUrl);
            }
            return response.body();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not download artifact from " + zipUrl + ": " + e.getMessage(), e);
        }
    }

    private FetchedSpec extractSpec(byte[] zipBytes) {
        String preferredName = null;
        String preferredContent = null;
        String fallbackName = null;
        String fallbackContent = null;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String lower = name.toLowerCase();
                if (!(lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json"))) {
                    continue;
                }
                String content = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                String baseName = lower.substring(lower.lastIndexOf('/') + 1);
                if (baseName.startsWith("openapi.") || baseName.startsWith("swagger.")) {
                    preferredName = name;
                    preferredContent = content;
                } else if (fallbackContent == null && looksLikeOpenApi(content)) {
                    fallbackName = name;
                    fallbackContent = content;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Downloaded artifact is not a readable ZIP: " + e.getMessage(), e);
        }

        if (preferredContent != null) {
            return new FetchedSpec(preferredName, preferredContent);
        }
        if (fallbackContent != null) {
            return new FetchedSpec(fallbackName, fallbackContent);
        }
        throw new IllegalArgumentException(
                "No OpenAPI document (openapi.yaml/.yml/.json) found inside the downloaded ZIP.");
    }

    private boolean looksLikeOpenApi(String content) {
        return content.contains("openapi") || content.contains("swagger");
    }
}

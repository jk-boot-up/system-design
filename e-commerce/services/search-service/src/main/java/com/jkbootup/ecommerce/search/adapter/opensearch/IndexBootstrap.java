package com.jkbootup.ecommerce.search.adapter.opensearch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Creates the versioned index and points the alias at it, once. Readers and writers only ever use the alias, so a
 * mapping change is: create products-v2, replay the catalogue topic into it, then move the alias in one atomic call.
 */
public class IndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(IndexBootstrap.class);

    private final RestClient http;
    private final String alias;
    private final String index;

    public IndexBootstrap(RestClient http, String alias, String index) {
        this.http = http;
        this.alias = alias;
        this.index = index;
    }

    public void ensureIndex() {
        try {
            http.head().uri("/{alias}", alias).retrieve().toBodilessEntity();
            return;                                                        // alias exists
        } catch (HttpClientErrorException.NotFound e) {
            // create below
        }
        String body;
        try {
            body = new ClassPathResource("opensearch/" + index + ".json").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing mapping for " + index, e);
        }
        body = body.replaceFirst("\\{", "{ \"aliases\": { \"" + alias + "\": { \"is_write_index\": true } },");
        try {
            http.put().uri("/{index}", index).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
            log.info("Created index {} behind alias {}", index, alias);
        } catch (HttpClientErrorException.BadRequest e) {
            if (!e.getResponseBodyAsString().contains("resource_already_exists_exception")) throw e;   // another replica won
        }
    }
}

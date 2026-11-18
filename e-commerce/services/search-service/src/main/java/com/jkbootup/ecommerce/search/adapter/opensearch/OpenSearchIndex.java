package com.jkbootup.ecommerce.search.adapter.opensearch;

import com.jkbootup.ecommerce.search.application.port.SearchIndex;
import com.jkbootup.ecommerce.search.domain.DomainException;
import com.jkbootup.ecommerce.search.domain.ProductDocument;
import com.jkbootup.ecommerce.search.domain.SearchQuery;
import com.jkbootup.ecommerce.search.domain.SearchResult;
import com.jkbootup.ecommerce.search.domain.SortOrder;
import com.jkbootup.ecommerce.search.domain.StockUpdate;
import com.jkbootup.ecommerce.search.domain.WriteOutcome;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The product index in OpenSearch, used through its REST API. Documents are keyed by SKU, because both event streams
 * know the SKU. Each document holds two versions, {@code catalogVersion} and {@code stockVersion}; a painless script
 * applies a write only if its version is higher than the one stored, so late, repeated or reordered events are no-ops.
 */
public class OpenSearchIndex implements SearchIndex {

    static final String PRODUCT_SCRIPT = """
            if (ctx._source.catalogVersion != null && ctx._source.catalogVersion >= params.v) { ctx.op = 'none'; }
            else { for (e in params.doc.entrySet()) { ctx._source[e.getKey()] = e.getValue(); } ctx._source.catalogVersion = params.v; }""";
    static final String STOCK_SCRIPT = """
            if (ctx._source.stockVersion != null && ctx._source.stockVersion >= params.v) { ctx.op = 'none'; }
            else { ctx._source.available = params.available; ctx._source.inStock = params.inStock; ctx._source.stockVersion = params.v; }""";

    private final RestClient http;
    private final JsonMapper json;
    private final String alias;
    private final String refresh;

    /** @param refreshOnWrite wait until a write is searchable before answering (tests); production relies on the 1 s refresh */
    public OpenSearchIndex(RestClient http, JsonMapper json, String alias, boolean refreshOnWrite) {
        this.http = http;
        this.json = json;
        this.alias = alias;
        this.refresh = refreshOnWrite ? "wait_for" : "false";
    }

    @Override
    public WriteOutcome upsertProduct(ProductDocument p) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("productId", p.productId());
        doc.put("sku", p.sku());
        doc.put("name", p.name());
        doc.put("description", p.description());
        doc.put("category", p.category());
        doc.put("brand", p.brand());
        doc.put("price", p.price());
        doc.put("currency", p.currency());
        doc.put("attributes", p.attributes());
        doc.put("status", p.status());
        doc.put("image", p.image());
        doc.put("updatedAt", p.updatedAt().toString());
        return update(p.sku(), PRODUCT_SCRIPT, Map.of("v", p.catalogVersion(), "doc", doc));
    }

    @Override
    public WriteOutcome updateStock(StockUpdate s) {
        return update(s.sku(), STOCK_SCRIPT, Map.of("v", s.stockVersion(), "available", s.available(), "inStock", s.inStock()));
    }

    private WriteOutcome update(String id, String script, Map<String, Object> params) {
        Map<String, Object> body = Map.of("scripted_upsert", true, "upsert", Map.of(),
                "script", Map.of("lang", "painless", "source", script, "params", params));
        JsonNode res = call(() -> http.post().uri("/{alias}/_update/{id}?retry_on_conflict=5&refresh={r}", alias, id, refresh)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class));
        return "noop".equals(res.path("result").asString()) ? WriteOutcome.STALE : WriteOutcome.APPLIED;
    }

    @Override
    public SearchResult search(SearchQuery q) {
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("status", "ACTIVE")));
        filters.add(Map.of("exists", Map.of("field", "productId")));       // stock arrived, product not yet: hidden
        if (q.category() != null) filters.add(Map.of("term", Map.of("category", q.category())));
        if (Boolean.TRUE.equals(q.inStock())) filters.add(Map.of("term", Map.of("inStock", true)));
        if (q.minPrice() != null || q.maxPrice() != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (q.minPrice() != null) range.put("gte", q.minPrice());
            if (q.maxPrice() != null) range.put("lte", q.maxPrice());
            filters.add(Map.of("range", Map.of("price", range)));
        }
        Object must = q.text() == null ? Map.of("match_all", Map.of())
                : Map.of("multi_match", Map.of("query", q.text(), "fields", List.of("name^3", "brand^2", "description"),
                "fuzziness", "AUTO"));
        // Brand is a multi-select facet: the brand filter is a post_filter, so the brand counts still show the other brands.
        Object brandFilter = q.brands().isEmpty() ? Map.of("match_all", Map.of()) : Map.of("terms", Map.of("brand", q.brands()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", q.limit() + 1);
        body.put("query", Map.of("bool", Map.of("must", must, "filter", filters)));
        body.put("post_filter", brandFilter);
        body.put("sort", sort(q.sort()));
        body.put("track_total_hits", true);
        body.put("aggs", Map.of(
                "brand", Map.of("terms", Map.of("field", "brand", "size", 20)),
                "category", Map.of("filter", brandFilter, "aggs", Map.of("values", Map.of("terms", Map.of("field", "category", "size", 20))))));
        if (q.cursor() != null) body.put("search_after", decodeCursor(q.cursor()));

        JsonNode res = call(() -> http.post().uri("/{alias}/_search", alias).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(JsonNode.class));

        List<SearchResult.Hit> items = new ArrayList<>();
        JsonNode hits = res.path("hits").path("hits");
        for (int i = 0; i < Math.min(hits.size(), q.limit()); i++) {
            JsonNode s = hits.get(i).path("_source");
            items.add(new SearchResult.Hit(s.path("productId").asString(), s.path("sku").asString(), s.path("name").asString(),
                    s.path("brand").asString(), s.path("category").asString(), new BigDecimal(s.path("price").asString()).setScale(2),
                    s.path("currency").asString(), s.path("inStock").asBoolean(false),
                    s.path("image").isNull() || s.path("image").isMissingNode() ? null : s.path("image").asString()));
        }
        String next = hits.size() > q.limit() ? encodeCursor(hits.get(q.limit() - 1).path("sort")) : null;
        Map<String, List<SearchResult.FacetValue>> facets = new LinkedHashMap<>();
        facets.put("brand", buckets(res.path("aggregations").path("brand")));
        facets.put("category", buckets(res.path("aggregations").path("category").path("values")));
        return new SearchResult(items, facets, res.path("hits").path("total").path("value").asLong(), next);
    }

    private static List<Object> sort(SortOrder order) {
        Object first = switch (order) {
            case RELEVANCE -> Map.of("_score", "desc");
            case PRICE_ASC -> Map.of("price", "asc");
            case PRICE_DESC -> Map.of("price", "desc");
            case NEWEST -> Map.of("updatedAt", "desc");
        };
        return List.of(first, Map.of("sku", "asc"));                     // the SKU breaks ties, so pages never overlap
    }

    private static List<SearchResult.FacetValue> buckets(JsonNode agg) {
        List<SearchResult.FacetValue> out = new ArrayList<>();
        for (JsonNode b : agg.path("buckets")) out.add(new SearchResult.FacetValue(b.path("key").asString(), b.path("doc_count").asLong()));
        return out;
    }

    private String encodeCursor(JsonNode sortValues) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(sortValues).getBytes(StandardCharsets.UTF_8));
    }

    private List<Object> decodeCursor(String cursor) {
        try {
            JsonNode node = json.readTree(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            if (!node.isArray() || node.size() != 2) throw new IllegalArgumentException("not a cursor");
            List<Object> values = new ArrayList<>();
            for (JsonNode v : node) values.add(v.isNumber() ? v.numberValue() : v.asString());
            return values;
        } catch (RuntimeException e) {
            throw new DomainException("INVALID_CURSOR", "cursor is not one this service returned");
        }
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new IndexUnavailableException("OpenSearch is unavailable: " + e.getMessage(), e);
        } catch (HttpClientErrorException e) {
            throw new IllegalStateException("OpenSearch rejected the request: " + e.getResponseBodyAsString(), e);
        }
    }
}

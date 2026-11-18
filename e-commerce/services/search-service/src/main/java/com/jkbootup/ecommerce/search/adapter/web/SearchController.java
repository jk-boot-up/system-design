package com.jkbootup.ecommerce.search.adapter.web;

import com.jkbootup.ecommerce.search.application.ProductSearch;
import com.jkbootup.ecommerce.search.domain.SearchQuery;
import com.jkbootup.ecommerce.search.domain.SearchResult;
import com.jkbootup.ecommerce.search.domain.SortOrder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** GET /api/v1/search: public, read-only. */
@RestController
public class SearchController {

    public record MoneyView(String amount, String currency) {}

    public record HitView(String id, String sku, String name, String brand, String category, MoneyView price, boolean inStock,
                          String image) {}

    public record SearchView(List<HitView> items, Map<String, List<SearchResult.FacetValue>> facets, long total, String nextCursor) {}

    private final ProductSearch search;

    public SearchController(ProductSearch search) {
        this.search = search;
    }

    @GetMapping("/api/v1/search")
    public SearchView search(@RequestParam(required = false) String q, @RequestParam(required = false) String category,
                             @RequestParam(required = false) List<String> brand, @RequestParam(required = false) BigDecimal minPrice,
                             @RequestParam(required = false) BigDecimal maxPrice, @RequestParam(required = false) Boolean inStock,
                             @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int limit,
                             @RequestParam(required = false) String cursor) {
        SearchResult r = search.search(new SearchQuery(q, category, brand == null ? null : new HashSet<>(brand), minPrice, maxPrice,
                inStock, SortOrder.parse(sort), limit, cursor));
        return new SearchView(r.items().stream().map(h -> new HitView(h.productId(), h.sku(), h.name(), h.brand(), h.category(),
                new MoneyView(h.price().toPlainString(), h.currency()), h.inStock(), h.image())).toList(), r.facets(), r.total(),
                r.nextCursor());
    }
}

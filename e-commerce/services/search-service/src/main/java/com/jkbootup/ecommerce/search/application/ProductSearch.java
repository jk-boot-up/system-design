package com.jkbootup.ecommerce.search.application;

import com.jkbootup.ecommerce.search.application.port.SearchIndex;
import com.jkbootup.ecommerce.search.domain.SearchQuery;
import com.jkbootup.ecommerce.search.domain.SearchResult;

/** Shopper search: only ACTIVE products that the catalogue has described are ever returned (the index adapter filters). */
public class ProductSearch {

    private final SearchIndex index;

    public ProductSearch(SearchIndex index) {
        this.index = index;
    }

    public SearchResult search(SearchQuery query) {
        return index.search(query);
    }
}

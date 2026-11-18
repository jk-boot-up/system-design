package com.jkbootup.ecommerce.search.application.port;

import com.jkbootup.ecommerce.search.domain.ProductDocument;
import com.jkbootup.ecommerce.search.domain.SearchQuery;
import com.jkbootup.ecommerce.search.domain.SearchResult;
import com.jkbootup.ecommerce.search.domain.StockUpdate;
import com.jkbootup.ecommerce.search.domain.WriteOutcome;

/**
 * The product index. Writes are conditional on versions held in the document itself, so they can be repeated and
 * applied in any order: the result is always the newest product data and the newest stock level.
 *
 * @throws IndexUnavailableException when the index cannot be reached
 */
public interface SearchIndex {

    WriteOutcome upsertProduct(ProductDocument product);

    WriteOutcome updateStock(StockUpdate stock);

    SearchResult search(SearchQuery query);

    class IndexUnavailableException extends RuntimeException {
        public IndexUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

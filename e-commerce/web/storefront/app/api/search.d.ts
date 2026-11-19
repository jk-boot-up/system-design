// Generated from services/search-service/contracts/openapi.yaml by scripts/api-types.ts. Do not edit.
export interface paths {
    "/api/v1/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["search"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        FacetValue: {
            count?: number;
            value?: string;
        };
        Problem: {
            code?: string;
            detail?: string;
            status?: number;
            title?: string;
        };
        ProductHit: {
            brand?: string;
            category?: string;
            /** Format: uuid */
            id?: string;
            /** @description object key of the first image */
            image?: string | null;
            inStock?: boolean;
            name?: string;
            price?: {
                amount?: string;
                currency?: string;
            };
            sku?: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    search: {
        parameters: {
            query?: {
                brand?: string[];
                /** @description category slug */
                category?: string;
                cursor?: string;
                inStock?: boolean;
                limit?: number;
                maxPrice?: number;
                minPrice?: number;
                q?: string;
                sort?: "relevance" | "price_asc" | "price_desc" | "newest";
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page of hits with facet counts */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        facets?: {
                            brand?: components["schemas"]["FacetValue"][];
                            category?: components["schemas"]["FacetValue"][];
                        };
                        items?: components["schemas"]["ProductHit"][];
                        nextCursor?: string | null;
                        total?: number;
                    };
                };
            };
            /** @description INVALID_QUERY, INVALID_SORT or INVALID_CURSOR */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
            /** @description SEARCH_UNAVAILABLE (Retry-After: 5) */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/problem+json": components["schemas"]["Problem"];
                };
            };
        };
    };
}

// Generated from services/review-service/contracts/openapi.yaml by scripts/api-types.ts. Do not edit.
export interface paths {
    "/api/v1/products/{sku}/reviews": {
        parameters: {
            query?: never;
            header?: never;
            path: {
                sku: string;
            };
            cookie?: never;
        };
        get: operations["listReviews"];
        put?: never;
        post: operations["postReview"];
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
        Review: {
            body?: string;
            /** Format: date-time */
            createdAt?: string;
            /** Format: uuid */
            id?: string;
            rating?: number;
            sku?: string;
            title?: string;
            verified?: boolean;
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
    listReviews: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path: {
                sku: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Newest first */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items?: components["schemas"]["Review"][];
                        nextCursor?: string | null;
                        summary?: {
                            average?: number | null;
                            count?: number;
                        };
                    };
                };
            };
        };
    };
    postReview: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                sku: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    body?: string;
                    rating: number;
                    title: string;
                };
            };
        };
        responses: {
            /** @description Created (or an interrupted earlier attempt finished) */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Review"];
                };
            };
            /** @description Validation failed */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description No customer token */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description ALREADY_REVIEWED */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
}

// Generated from services/catalog-service/contracts/openapi.yaml by scripts/api-types.ts. Do not edit.
export interface paths {
    "/api/v1/admin/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["createCategory"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/products": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["createProduct"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/products/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getProductForStaff"];
        put: operations["updateProduct"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/products/{id}/images": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["startImageUpload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/admin/products/{id}/images/confirm": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["confirmImage"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/categories": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["listCategories"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getProduct"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/products/by-sku": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Active products for up to 50 SKUs, in the order asked; unknown or inactive SKUs are left out. */
        get: operations["getProductsBySku"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/products/prices": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["prices"];
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
        Category: {
            children?: components["schemas"]["Category"][];
            /** Format: uuid */
            id?: string;
            name?: string;
            slug?: string;
        };
        Money: {
            /** @example 89.90 */
            amount: string;
            /** @example EUR */
            currency: string;
        };
        Problem: {
            code?: string;
            detail?: string;
            status?: number;
            title?: string;
        };
        Product: components["schemas"]["ProductWrite"] & {
            /** Format: uuid */
            id?: string;
            images?: {
                position?: number;
                url?: string;
            }[];
            version?: number;
        };
        ProductWrite: {
            attributes?: Record<string, never>;
            brand: string;
            /** Format: uuid */
            categoryId: string;
            description?: string;
            name: string;
            price: components["schemas"]["Money"];
            /** @description Create only; 3-40 of A-Z, 0-9, dash */
            sku?: string;
            /** @enum {unknown} */
            status?: "DRAFT" | "ACTIVE" | "ARCHIVED";
        };
    };
    responses: {
        /** @description Problem details */
        Problem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
        /** @description Product, with its version as a strong ETag */
        Product: {
            headers: {
                ETag?: string;
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["Product"];
            };
        };
    };
    parameters: {
        Id: string;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    createCategory: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    name: string;
                    /** Format: uuid */
                    parentId?: string;
                    slug: string;
                };
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description SLUG_TAKEN */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    createProduct: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProductWrite"];
            };
        };
        responses: {
            201: components["responses"]["Product"];
            400: components["responses"]["Problem"];
            /** @description SKU_TAKEN */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description UNKNOWN_CATEGORY */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    getProductForStaff: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Product"];
            404: components["responses"]["Problem"];
        };
    };
    updateProduct: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
            };
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProductWrite"];
            };
        };
        responses: {
            200: components["responses"]["Product"];
            /** @description INVALID_TRANSITION */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description VERSION_MISMATCH: someone else changed the product; reload and retry */
            412: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description PRECONDITION_REQUIRED: If-Match missing */
            428: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    startImageUpload: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @enum {unknown} */
                    contentType: "image/jpeg" | "image/png" | "image/webp";
                    sizeBytes: number;
                };
            };
        };
        responses: {
            /** @description PUT the file to uploadUrl with the same Content-Type before expiresAt */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        /** Format: date-time */
                        expiresAt?: string;
                        objectKey?: string;
                        uploadUrl?: string;
                    };
                };
            };
        };
    };
    confirmImage: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    objectKey: string;
                };
            };
        };
        responses: {
            200: components["responses"]["Product"];
            /** @description IMAGE_MISSING: nothing uploaded under that key */
            422: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    listCategories: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Category tree */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Category"][];
                };
            };
        };
    };
    getProduct: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: components["parameters"]["Id"];
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Product"];
            404: components["responses"]["Problem"];
        };
    };
    getProductsBySku: {
        parameters: {
            query: {
                sku: string[];
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Products */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Product"][];
                };
            };
            400: components["responses"]["Problem"];
        };
    };
    prices: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    skus: string[];
                };
            };
        };
        responses: {
            /** @description Known SKUs only */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items?: {
                            name?: string;
                            price?: components["schemas"]["Money"];
                            sku?: string;
                            status?: string;
                        }[];
                    };
                };
            };
        };
    };
}

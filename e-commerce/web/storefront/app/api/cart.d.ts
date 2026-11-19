// Generated from services/cart-service/contracts/openapi.yaml by scripts/api-types.ts. Do not edit.
export interface paths {
    "/api/v1/cart": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["getCart"];
        put?: never;
        post?: never;
        delete: operations["clearCart"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cart/items/{sku}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["setQuantity"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/cart/merge": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["mergeGuestCart"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/internal/carts/{customerId}/checkout-snapshot": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["checkoutSnapshot"];
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
        Cart: {
            checkoutReady?: boolean;
            lines?: {
                available?: boolean;
                lineTotal?: components["schemas"]["Money"];
                name?: string;
                quantity?: number;
                sku?: string;
                unitPrice?: components["schemas"]["Money"];
            }[];
            /** @example c:0193… */
            owner?: string;
            /** @enum {string} */
            pricing?: "CURRENT" | "PENDING";
            total?: components["schemas"]["Money"];
        };
        Money: {
            amount?: string;
            currency?: string;
        };
        Problem: {
            code?: string;
            detail?: string;
            status?: number;
            title?: string;
        };
    };
    responses: {
        /** @description Priced cart */
        Cart: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/json": components["schemas"]["Cart"];
            };
        };
        /** @description Problem details */
        Problem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
            };
        };
    };
    parameters: {
        /** @description Required when no customer token is sent */
        Guest: string;
    };
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    getCart: {
        parameters: {
            query?: never;
            header?: {
                /** @description Required when no customer token is sent */
                "X-Guest-Id"?: components["parameters"]["Guest"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Cart"];
            400: components["responses"]["Problem"];
        };
    };
    clearCart: {
        parameters: {
            query?: never;
            header?: {
                /** @description Required when no customer token is sent */
                "X-Guest-Id"?: components["parameters"]["Guest"];
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Cleared */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    setQuantity: {
        parameters: {
            query?: never;
            header?: {
                /** @description Required when no customer token is sent */
                "X-Guest-Id"?: components["parameters"]["Guest"];
            };
            path: {
                sku: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** @description 0 removes the line */
                    quantity: number;
                };
            };
        };
        responses: {
            200: components["responses"]["Cart"];
            400: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
        };
    };
    mergeGuestCart: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    /** Format: uuid */
                    guestId: string;
                };
            };
        };
        responses: {
            200: components["responses"]["Cart"];
            401: components["responses"]["Problem"];
        };
    };
    checkoutSnapshot: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                customerId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            200: components["responses"]["Cart"];
            /** @description PRICING_UNAVAILABLE: prices cannot be confirmed (Retry-After) */
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

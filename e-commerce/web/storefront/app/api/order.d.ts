// Generated from services/order-service/contracts/openapi.yaml by scripts/api-types.ts. Do not edit.
export interface paths {
    "/orders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** The customer's orders, newest first */
        get: operations["listOrders"];
        put?: never;
        /** Place an order from the customer's cart (checkout saga) */
        post: operations["placeOrder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orders/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** One order (owner or staff) */
        get: operations["getOrder"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/orders/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** Cancel a confirmed order (refund and return stock) */
        post: operations["cancelOrder"];
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
        Money: {
            /** @example 179.80 */
            amount: string;
            /** @example EUR */
            currency: string;
        };
        Order: {
            cancelReason?: string | null;
            /** Format: date-time */
            createdAt?: string;
            /** Format: uuid */
            id?: string;
            lines?: {
                productName?: string;
                quantity?: number;
                sku?: string;
                unitPrice?: components["schemas"]["Money"];
            }[];
            /** @enum {string} */
            status?: "PENDING" | "CONFIRMED" | "CANCELLING" | "CANCELLED" | "SHIPPED" | "DELIVERED";
            total?: components["schemas"]["Money"];
        };
        Problem: {
            code?: string;
            detail?: string;
            order?: {
                cancelReason?: string;
                id?: string;
                status?: string;
            };
            status?: number;
            title?: string;
            type?: string;
        };
    };
    responses: {
        /** @description Checkout did not complete: OUT_OF_STOCK (409), PAYMENT_DECLINED (402), DEPENDENCY_UNAVAILABLE (503, Retry-After), PAYMENT_UNKNOWN (504) */
        CheckoutProblem: {
            headers: {
                [name: string]: unknown;
            };
            content: {
                "application/problem+json": components["schemas"]["Problem"];
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
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    listOrders: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description One page */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": {
                        items?: components["schemas"]["Order"][];
                        nextCursor?: string | null;
                    };
                };
            };
        };
    };
    placeOrder: {
        parameters: {
            query?: never;
            header: {
                /** @description Repeat the same key to retry safely: the first result is returned again */
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": {
                    paymentToken: string;
                };
            };
        };
        responses: {
            /** @description Replay of an earlier request with the same key */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Order"];
                };
            };
            /** @description Confirmed */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Order"];
                };
            };
            /** @description Payment authorised; finishing in the background (poll the Location) */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Order"];
                };
            };
            402: components["responses"]["CheckoutProblem"];
            409: components["responses"]["CheckoutProblem"];
            422: components["responses"]["Problem"];
            503: components["responses"]["CheckoutProblem"];
            504: components["responses"]["CheckoutProblem"];
        };
    };
    getOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description The order */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Order"];
                };
            };
            404: components["responses"]["Problem"];
        };
    };
    cancelOrder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Cancelled */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["Order"];
                };
            };
            404: components["responses"]["Problem"];
            409: components["responses"]["Problem"];
        };
    };
}

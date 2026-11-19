/**
 * API problems (RFC 9457 with `code` and `traceId`, design/09) and the one table that turns them into words for shoppers
 * (design/16 §16.9). Upstream `detail` text is never shown: it is written for developers and may change.
 */
export interface FieldError {
  field: string;
  message: string;
}

export interface Problem {
  status: number;
  code: string;
  detail?: string;
  traceId?: string;
  errors?: FieldError[];
  retryAfter?: number;
}

/**
 * An API failure, with the shape of a React Router error response (status, statusText, internal, data), so one
 * that escapes a loader or action gives the page its real status (404, 429, 503…) instead of a 500, and the error
 * boundary receives only the public part of the problem (`data`), never upstream detail.
 */
export class ApiError {
  readonly name = 'ApiError';
  readonly problem: Problem;
  readonly internal = false;

  // Not an Error subclass on purpose: React Router hides thrown Errors behind a generic one in production, but passes
  // error responses through, so the page can say what happened (busy, unavailable) without leaking any detail.
  constructor(problem: Problem) {
    this.problem = problem;
  }

  get message(): string {
    return `${this.problem.status} ${this.problem.code}`;
  }

  toString(): string {
    return `ApiError: ${this.message}`;
  }

  get statusText(): string {
    return this.problem.code;
  }

  get data(): Problem {
    return publicProblem(this);
  }

  get status(): number {
    return this.problem.status;
  }

  get code(): string {
    return this.problem.code;
  }

  static async from(res: Response): Promise<ApiError> {
    let body: Partial<Problem> = {};
    try {
      body = (await res.json()) as Partial<Problem>;
    } catch {
      // not a problem body
    }
    return new ApiError({
      ...body,
      status: res.status,
      code: body.code ?? 'UNEXPECTED',
      retryAfter: Number(res.headers.get('Retry-After')) || undefined,
    });
  }
}

const MESSAGES: Record<string, string> = {
  VALIDATION_FAILED: 'Please check the highlighted fields.',
  UNAUTHENTICATED: 'Please sign in to continue.',
  FORBIDDEN: 'You do not have access to this.',
  NOT_FOUND: 'We could not find that.',
  OUT_OF_STOCK: 'Some items are no longer available in the quantity you chose.',
  NOT_CANCELLABLE: 'This order can no longer be cancelled.',
  ALREADY_REVIEWED: 'You have already reviewed this product.',
  ILLEGAL_STATE: 'That is not possible right now.',
  PAYMENT_DECLINED: 'Your payment was declined. Your cart is unchanged; please try another card.',
  PAYMENT_UNKNOWN: 'We could not confirm your payment in time, so the order was cancelled and you were not charged. Please try again.',
  VERSION_MISMATCH: 'Someone else changed this in the meantime. Reload to see their changes.',
  PRECONDITION_REQUIRED: 'Reload the page and try again.',
  CART_NOT_READY: 'Your cart is empty or prices are still being confirmed.',
  UPLOAD_MISSING: 'The image upload did not finish. Please try again.',
  SEARCH_UNAVAILABLE: 'Search is temporarily unavailable.',
  DEPENDENCY_UNAVAILABLE: 'This is temporarily unavailable. Please try again in a moment.',
  RATE_LIMITED: 'Too many requests.',
  INVALID_QUERY: 'That search could not be understood.',
};

export function messageFor(problem: Pick<Problem, 'code' | 'retryAfter'>): string {
  if (problem.code === 'RATE_LIMITED') {
    return problem.retryAfter ? `Too many requests. Please try again in ${problem.retryAfter} seconds.` : MESSAGES.RATE_LIMITED!;
  }
  return MESSAGES[problem.code] ?? 'Something went wrong.';
}

/** Field errors keyed by field name, for messages next to inputs. */
export function fieldErrors(problem: Problem | undefined): Record<string, string> {
  return Object.fromEntries((problem?.errors ?? []).map((e) => [e.field, e.message]));
}

/** What a route may send to the browser about a failure: no upstream detail. */
export function publicProblem(e: ApiError): Problem {
  const { status, code, traceId, errors, retryAfter } = e.problem;
  return { status, code, traceId, errors, retryAfter };
}

export const KNOWN_CODES = Object.keys(MESSAGES);

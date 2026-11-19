import { messageFor, type Problem } from '../lib/problem.ts';

/** A problem in words, with the reference to quote to support. */
export function ProblemNotice({ problem, className = '' }: { problem: Problem; className?: string }) {
  return (
    <div role="alert" className={`notice border-danger text-danger ${className}`}>
      <p>{messageFor(problem)}</p>
      {problem.traceId && problem.status >= 500 && <p className="mt-1 text-sm text-muted">Reference: {problem.traceId}</p>}
    </div>
  );
}

export function Unavailable({ what }: { what: string }) {
  return <p role="status" className="notice border-line text-muted">{what} is temporarily unavailable.</p>;
}

/** The hidden per-session CSRF token every signed-in form carries (design/16 §16.6). */
export function Csrf({ token }: { token: string | undefined }) {
  return token ? <input type="hidden" name="_csrf" value={token} /> : null;
}

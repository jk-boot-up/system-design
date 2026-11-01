-- One payment per order. amount, currency and payment_token are empty only for a void that arrived before any
-- authorisation (it blocks a later authorisation for that order).
create table payments (
  id             uuid primary key,
  order_id       uuid not null unique,
  amount         numeric(19, 2) check (amount > 0),
  currency       char(3),
  status         text not null check (status in ('AUTHORIZING', 'AUTHORIZED', 'CAPTURED', 'DECLINED', 'UNKNOWN', 'VOIDED', 'REFUNDED')),
  payment_token  text,
  provider_ref   text,
  failure_reason text,
  void_requested boolean not null default false,
  version        bigint not null,
  created_at     timestamptz not null,
  updated_at     timestamptz not null,
  check (status = 'VOIDED' or (amount is not null and currency is not null and payment_token is not null))
);
create index payments_unsettled on payments (updated_at)
  where status in ('AUTHORIZING', 'UNKNOWN') or (status = 'AUTHORIZED' and void_requested);

-- Append-only audit trail of every provider interaction.
create table payment_attempts (
  id           uuid primary key,
  payment_id   uuid not null references payments (id),
  operation    text not null,
  outcome      text not null,
  provider_ref text,
  detail       text,
  created_at   timestamptz not null default now()
);
create index payment_attempts_by_payment on payment_attempts (payment_id, created_at);

create table outbox (
  id             uuid primary key,
  aggregate_type text        not null,
  aggregate_id   text        not null,
  event_type     text        not null,
  topic          text        not null,
  payload        jsonb       not null,
  created_at     timestamptz not null default now(),
  published_at   timestamptz
);
create index outbox_unpublished on outbox (created_at) where published_at is null;

create table shedlock (
  name       varchar(64)  primary key,
  lock_until timestamp    not null,
  locked_at  timestamp    not null,
  locked_by  varchar(255) not null
);

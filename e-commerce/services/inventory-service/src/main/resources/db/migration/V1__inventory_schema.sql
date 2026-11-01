-- Stock counters. available and reserved can never go negative: the conditional updates guarantee it,
-- and the check constraints are a second line of defence.
create table stock (
  sku        text   not null,
  warehouse  text   not null default 'MAIN',
  available  int    not null check (available >= 0),
  reserved   int    not null default 0 check (reserved >= 0),
  version    bigint not null default 0,
  updated_at timestamptz not null default now(),
  primary key (sku, warehouse)
);

create table reservations (
  order_id   uuid not null,
  sku        text not null,
  warehouse  text not null default 'MAIN',
  quantity   int  not null check (quantity > 0),
  status     text not null check (status in ('RESERVED', 'COMMITTED', 'RELEASED', 'EXPIRED', 'RETURNED')),
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (order_id, sku)
);
create index reservations_live_expiry on reservations (expires_at) where status = 'RESERVED';

-- Transactional outbox and idempotent-consumer ledger.
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

create table processed_events (
  consumer     text not null,
  event_id     uuid not null,
  processed_at timestamptz not null default now(),
  primary key (consumer, event_id)
);

-- ShedLock: one runner per scheduled job across replicas.
create table shedlock (
  name       varchar(64)  primary key,
  lock_until timestamp    not null,
  locked_at  timestamp    not null,
  locked_by  varchar(255) not null
);

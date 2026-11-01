-- Orders and lines are range-partitioned by month on created_at, which is the time inside the UUIDv7 id.
create table orders (
  id             uuid        not null,
  created_at     timestamptz not null,
  customer_id    uuid        not null,
  customer_email text        not null,
  status         text        not null check (status in ('PENDING', 'CONFIRMED', 'CANCELLING', 'CANCELLED', 'SHIPPED', 'DELIVERED')),
  saga_step      text        not null,
  cancel_reason  text,
  total_amount   numeric(19, 2) not null check (total_amount > 0),
  currency       char(3)     not null,
  version        bigint      not null,
  updated_at     timestamptz not null,
  primary key (id, created_at)
) partition by range (created_at);

create table order_lines (
  order_id         uuid        not null,
  order_created_at timestamptz not null,
  line_no          int         not null,
  sku              text        not null,
  product_name     text        not null,
  quantity         int         not null check (quantity > 0),
  unit_price       numeric(19, 2) not null check (unit_price >= 0),
  primary key (order_id, order_created_at, line_no),
  foreign key (order_id, order_created_at) references orders (id, created_at)
) partition by range (order_created_at);

create index orders_by_customer on orders (customer_id, created_at desc, id desc);
create index orders_in_flight on orders (updated_at) where status in ('PENDING', 'CANCELLING');

create table orders_default partition of orders default;
create table order_lines_default partition of order_lines default;

-- Creates the monthly partitions from this month up to months_ahead months ahead (idempotent).
create or replace function ensure_order_partitions(months_ahead int) returns int language plpgsql as $$
declare
  m        date := date_trunc('month', now())::date;
  created  int  := 0;
  suffix   text;
begin
  for i in 0..months_ahead loop
    suffix := to_char(m, 'YYYY_MM');
    if to_regclass('orders_' || suffix) is null then
      execute format('create table %I partition of orders for values from (%L) to (%L)',
                     'orders_' || suffix, m, (m + interval '1 month')::date);
      execute format('create table %I partition of order_lines for values from (%L) to (%L)',
                     'order_lines_' || suffix, m, (m + interval '1 month')::date);
      created := created + 1;
    end if;
    m := (m + interval '1 month')::date;
  end loop;
  return created;
end $$;

select ensure_order_partitions(3);

create table idempotency_keys (
  customer_id     uuid not null,
  idempotency_key text not null,
  order_id        uuid not null,
  created_at      timestamptz not null default now(),
  primary key (customer_id, idempotency_key)
);

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

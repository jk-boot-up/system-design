-- Categories form a tree; products belong to one category.
create table categories (
  id         uuid primary key,
  parent_id  uuid references categories (id),
  slug       text not null unique,
  name       text not null,
  created_at timestamptz not null default now()
);

-- version drives optimistic locking: every update is "... where id = ? and version = ?" and bumps it by one.
create table products (
  id           uuid primary key,
  sku          text not null unique,
  name         text not null,
  description  text not null default '',
  category_id  uuid not null references categories (id),
  brand        text not null,
  price_amount numeric(19, 2) not null check (price_amount >= 0),
  currency     char(3) not null,
  attributes   jsonb not null default '{}'::jsonb,
  status       text not null check (status in ('DRAFT', 'ACTIVE', 'ARCHIVED')),
  version      bigint not null,
  created_at   timestamptz not null,
  updated_at   timestamptz not null
);
create index products_category_active on products (category_id) where status = 'ACTIVE';
create index products_attributes on products using gin (attributes jsonb_path_ops);

create table product_images (
  product_id   uuid not null references products (id) on delete cascade,
  position     int  not null,
  object_key   text not null,
  content_type text not null,
  primary key (product_id, position)
);

-- Transactional outbox.
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

-- ShedLock: one runner per scheduled job across replicas.
create table shedlock (
  name       varchar(64)  primary key,
  lock_until timestamp    not null,
  locked_at  timestamp    not null,
  locked_by  varchar(255) not null
);

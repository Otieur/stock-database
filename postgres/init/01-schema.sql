set search_path = app, public;

do $$
begin
  if exists (select 1 from information_schema.columns where table_schema=current_schema() and table_name='daily_price_historic' and column_name='trade_date') then
    execute 'alter table daily_price_historic rename column trade_date to day';
  end if;
  if exists (select 1 from information_schema.columns where table_schema=current_schema() and table_name='daily_price_historic' and column_name='ticker') then
    execute 'alter table daily_price_historic rename column ticker to symbol';
  end if;
end$$;

create table if not exists users(
  user_id bigserial primary key,
  name text,
  email text not null,
  password_hash text not null,
  created_at timestamptz default now(),
  unique(email)
);

create unique index if not exists users_email_lower_uq on users(lower(email));

create table if not exists friend_requests(
  request_id bigserial primary key,
  requester_id bigint not null references users(user_id) on delete cascade,
  receiver_id bigint not null references users(user_id) on delete cascade,
  status text not null check (status in ('pending','accepted','rejected','cancelled')),
  created_at timestamptz default now(),
  responded_at timestamptz,
  check (requester_id <> receiver_id)
);

create index if not exists friend_requests_receiver_idx on friend_requests(receiver_id);

create table if not exists friendships(
  user1_id bigint not null references users(user_id) on delete cascade,
  user2_id bigint not null references users(user_id) on delete cascade,
  since_at timestamptz default now(),
  deleted_at timestamptz,
  check (user1_id < user2_id),
  primary key (user1_id,user2_id)
);

create index if not exists friendships_user1_idx on friendships(user1_id);
create index if not exists friendships_user2_idx on friendships(user2_id);

create table if not exists stocks(
  symbol text primary key,
  company_name text
);

create table if not exists stock_lists(
  list_id bigserial primary key,
  owner_id bigint not null references users(user_id) on delete cascade,
  name text not null,
  visibility text not null default 'private' check (visibility in ('private','friends','public')),
  created_at timestamptz default now()
);

create index if not exists stock_lists_owner_idx on stock_lists(owner_id);

create table if not exists stock_list_items(
  list_id bigint not null references stock_lists(list_id) on delete cascade,
  symbol text not null references stocks(symbol) on delete cascade,
  shares numeric(18,6),
  primary key (list_id,symbol)
);

create table if not exists stock_list_shares(
  list_id bigint not null references stock_lists(list_id) on delete cascade,
  shared_with bigint not null references users(user_id) on delete cascade,
  shared_at timestamptz default now(),
  primary key (list_id,shared_with)
);

create table if not exists reviews(
  reviewer_id bigint not null references users(user_id) on delete cascade,
  list_id bigint not null references stock_lists(list_id) on delete cascade,
  body text not null,
  created_at timestamptz default now(),
  updated_at timestamptz,
  deleted_at timestamptz,
  primary key (reviewer_id,list_id)
);

create index if not exists reviews_list_idx on reviews(list_id);

create table if not exists portfolios(
  portfolio_id bigserial primary key,
  owner_id bigint not null references users(user_id) on delete cascade,
  name text not null,
  cash_balance numeric(14,2) not null default 0,
  created_at timestamptz default now()
);

create index if not exists portfolios_owner_idx on portfolios(owner_id);

create table if not exists portfolio_holdings(
  portfolio_id bigint not null references portfolios(portfolio_id) on delete cascade,
  symbol text not null references stocks(symbol) on delete cascade,
  shares numeric(18,6) not null check (shares >= 0),
  primary key (portfolio_id,symbol)
);

create table if not exists trades(
  trade_id bigserial primary key,
  portfolio_id bigint not null references portfolios(portfolio_id) on delete cascade,
  symbol text not null references stocks(symbol),
  shares numeric(18,6) not null check (shares > 0),
  side text not null check (side in ('BUY','SELL')),
  price numeric(14,4) not null check (price >= 0),
  trade_at timestamptz default now()
);

create index if not exists trades_portfolio_ts_idx on trades(portfolio_id,trade_at);
create index if not exists trades_symbol_idx on trades(symbol);

create table if not exists cash_transactions(
  cash_txn_id bigserial primary key,
  portfolio_id bigint not null references portfolios(portfolio_id) on delete cascade,
  txn_kind text not null check (txn_kind in ('DEPOSIT','WITHDRAW','DIVIDEND','FEE','INTEREST','ADJUSTMENT')),
  amount numeric(14,2) not null,
  occurred_at timestamptz not null default now()
);

create index if not exists cash_txn_portfolio_ts_idx on cash_transactions(portfolio_id,occurred_at);

create table if not exists daily_price_historic(
  symbol text not null,
  day date not null,
  open numeric(12,4),
  high numeric(12,4),
  low numeric(12,4),
  close numeric(12,4),
  volume bigint,
  primary key (symbol,day)
);

create index if not exists dph_day_idx on daily_price_historic(day);

create table if not exists daily_price_new(
  symbol text not null,
  day date not null,
  open numeric(12,4),
  high numeric(12,4),
  low numeric(12,4),
  close numeric(12,4),
  volume bigint,
  added_by bigint references users(user_id) on delete set null,
  added_at timestamptz default now(),
  primary key (symbol,day)
);

create index if not exists dpn_day_idx on daily_price_new(day);

create or replace view unified_prices as
select symbol,day,open,high,low,close,volume,added_by,added_at from daily_price_new
union all
select h.symbol,h.day,h.open,h.high,h.low,h.close,h.volume,null::bigint,null::timestamptz
from daily_price_historic h
left join daily_price_new n on n.symbol=h.symbol and n.day=h.day
where n.symbol is null;

create table players
(
    id             bigserial primary key,
    username       varchar(16)  not null,
    email          varchar(254),
    password_hash  varchar(100) not null,
    trophies       integer      not null default 0,
    matches_played integer      not null default 0,
    wins           integer      not null default 0,
    created_at     timestamptz  not null default now()
);

-- имена и email уникальны без учёта регистра
create unique index ux_players_username on players (lower(username));
create unique index ux_players_email on players (lower(email)) where email is not null;

-- обработанные матчи: защита от повторного применения результата
create table match_results
(
    match_id    varchar(64) primary key,
    winner_team integer     not null,
    payload     text        not null,
    finished_at timestamptz not null default now()
);

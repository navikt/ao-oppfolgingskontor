create table alternativ_aokontor
(
    id                  serial primary key,
    fnr                 varchar(11) not null,
    kontor_id           varchar(4) not null,
    endret_av           varchar(255) not null,
    endret_av_type      varchar(255) not null,
    kontorendringstype  text not null,
    created_at          timestamp with time zone default now(),
    updated_at          timestamp with time zone default now()
);


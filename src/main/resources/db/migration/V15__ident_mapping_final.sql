drop table if exists ident_mapping;

create table ident_mapping (
    ident varchar(20) not null primary key,
    ident_type varchar(11) not null, -- Folkeregister-ident (fnr eller dnr), NPID, aktørId
    historisk boolean,
    intern_ident integer not null,
    created_at timestamptz default current_timestamp not null,
    updated_at timestamptz default current_timestamp not null
);

create index idx_ident_mapping_intern_ident ON ident_mapping(intern_ident);
create sequence intern_ident_seq;


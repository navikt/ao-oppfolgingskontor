create table ident_mapping (
    ident char(20) not null primary key,
    ident_type char(11) not null, -- Folkeregister-ident (fnr eller dnr), NPID, aktørId
    historisk boolean,
    intern_ident uuid not null,
    createdAt timestamptz default current_timestamp not null,
    updatedAt timestamptz default current_timestamp not null
);

create index idx_ident_mapping_intern_ident ON ident_mapping(intern_ident);

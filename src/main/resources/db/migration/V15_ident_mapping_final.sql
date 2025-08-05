create table ident_mapping (
    ident char(11),
    ident_type char(11), -- Folkeregister-ident (fnr eller dnr), NPID
    gjeldende boolean,
    intern_ident uuid,
    createdAt timestamp default current_timestamp
);

create index idx_ident_mapping_ident ON ident_mapping(ident);
create index idx_ident_mapping_intern_ident ON ident_mapping(intern_ident);

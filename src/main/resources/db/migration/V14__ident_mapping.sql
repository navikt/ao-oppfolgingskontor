create table ident_mapping (
    aktorid varchar(20),
    fnr char(11),
    npid char(11),
    createdAt timestamp default current_timestamp
);

create index idx_ident_mapping_aktorid ON ident_mapping(aktorid);
create index idx_ident_mapping_fnr ON ident_mapping(fnr);
create index idx_ident_mapping_npid ON ident_mapping(npid);

create table ident_mapping (
    ident char(11),
    ident_type char(11), -- Folkeregister-ident, npid
    intern_ident uuid,
    createdAt timestamp default current_timestamp
);

create index idx_ident_mapping_ident ON ident_mapping(ident);
create index idx_ident_mapping_intern_ident ON ident_mapping(intern_ident);

# ident -> [...indenter]
select b.ident, b.ident_type from ident_mapping a
    join ident_mapping b.intern_ident = a.intern_ident


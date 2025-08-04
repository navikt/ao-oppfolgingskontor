create table ident_mapping (
    aktorid varchar(20),
    fnr char(11),
    npid char(11),
    createdAt timestamp default current_timestamp
);

create index idx_ident_mapping_aktorid ON ident_mapping(aktorid);
create index idx_ident_mapping_fnr ON ident_mapping(fnr);
create index idx_ident_mapping_npid ON ident_mapping(npid);

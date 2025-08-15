alter table kontorhistorikk
rename column fnr to ident;

create index ident_idx on kontorhistorikk(ident);
create index kontorid_idx on kontorhistorikk(kontor_id);

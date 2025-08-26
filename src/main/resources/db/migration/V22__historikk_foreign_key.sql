alter table arbeidsoppfolgingskontor
    add column historikk_entry integer null,
    add constraint historikk_fk foreign key (historikk_entry) references kontorhistorikk(id);

alter table arenakontor
    add column historikk_entry integer null,
    add constraint historikk_fk foreign key (historikk_entry) references kontorhistorikk(id);

alter table geografisktilknytningkontor
    add column historikk_entry integer null,
    add constraint historikk_fk foreign key (historikk_entry) references kontorhistorikk(id);

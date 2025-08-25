alter table arenakontor
    alter column endret_av_type type varchar(255),
    alter column endret_av type varchar(255);

alter table geografisktilknytningkontor
    alter column endret_av_type type varchar(255),
    alter column endret_av type varchar(255);

alter table arbeidsoppfolgingskontor
    alter column endret_av_type type varchar(255),
    alter column endret_av type varchar(255);

alter table kontorhistorikk
    alter column endret_av_type type varchar(255),
    alter column endret_av type varchar(255);
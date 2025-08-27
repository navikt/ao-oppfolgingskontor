
select count(*) from arbeidsoppfolgingskontor where historikk_entry is null;
with arbeidsoppfolgingskontor_uten_historikk_entry as (
    select fnr from arbeidsoppfolgingskontor
    where historikk_entry is null
),
     siste_historikk_entry as (
         select distinct on (ident) id, ident from kontorhistorikk
             join arbeidsoppfolgingskontor_uten_historikk_entry
         on arbeidsoppfolgingskontor_uten_historikk_entry.fnr = ident
         where kontor_type = 'ARBEIDSOPPFOLGING'
         order by ident, created_at desc
     )
update arbeidsoppfolgingskontor set historikk_entry = siste_historikk_entry.id
    from siste_historikk_entry
where fnr = siste_historikk_entry.ident;

select count(*) from geografisktilknytningkontor where historikk_entry is null;
with gtkontor_uten_historikk_entry as (
    select fnr from geografisktilknytningkontor
    where historikk_entry is null
),
     siste_historikk_entry as (
         select distinct on (ident) id, ident from kontorhistorikk
             join gtkontor_uten_historikk_entry
         on gtkontor_uten_historikk_entry.fnr = ident
         where kontor_type = 'GEOGRAFISK_TILKNYTNING'
         order by ident, created_at desc
     )
update geografisktilknytningkontor set historikk_entry = siste_historikk_entry.id
    from siste_historikk_entry
where fnr = siste_historikk_entry.ident;

select count(*) from arenakontor where historikk_entry is null;
with arenakontor_uten_historikk_entry as (
    select fnr from arenakontor
    where historikk_entry is null
),
     siste_historikk_entry as (
         select distinct on (ident) id, ident from kontorhistorikk
                                                       join arenakontor_uten_historikk_entry
                                                            on arenakontor_uten_historikk_entry.fnr = ident
         where kontor_type = 'ARENA'
         order by ident, created_at desc
     )
update arenakontor set historikk_entry = siste_historikk_entry.id
from siste_historikk_entry
where fnr = siste_historikk_entry.ident;

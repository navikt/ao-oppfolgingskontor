update arbeidsoppfolgingskontor
set endret_av = 'VEILARBOPPFOLGING'
from kontorhistorikk
where arbeidsoppfolgingskontor.historikk_entry = kontorhistorikk.id
  and arbeidsoppfolgingskontor.endret_av = 'SYSTEM'
  and kontorhistorikk.kontorendringstype in ('AutomatiskNorgRuting', 'AutomatiskNorgRutingFallback', 'AutomatiskRutetTilNavItGtErLand',
                                             'AutomatiskRutetTilNavItUgyldigGt', 'AutomatiskRutetTilNOE',
                                             'AutomatiskRutingArbeidsgiverFallback', 'GTKontorVedOppfolgingStart');

update arbeidsoppfolgingskontor
set endret_av = 'PDL'
from kontorhistorikk
where arbeidsoppfolgingskontor.historikk_entry = kontorhistorikk.id
  and arbeidsoppfolgingskontor.endret_av = 'SYSTEM'
  and kontorhistorikk.kontorendringstype in ('FikkAddressebeskyttelse', 'AddressebeskyttelseMistet', 'EndretBostedsadresse');

update arbeidsoppfolgingskontor
set endret_av = 'SKJERMING'
from kontorhistorikk
where arbeidsoppfolgingskontor.historikk_entry = kontorhistorikk.id
  and arbeidsoppfolgingskontor.endret_av = 'SYSTEM'
  and kontorhistorikk.kontorendringstype in ('FikkSkjerming', 'MistetSkjerming');

update arbeidsoppfolgingskontor
set endret_av = 'ARENA'
from kontorhistorikk
where arbeidsoppfolgingskontor.historikk_entry = kontorhistorikk.id
  and arbeidsoppfolgingskontor.endret_av = 'SYSTEM'
  and kontorhistorikk.kontorendringstype in
      ('ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart', 'ArenaKontorHentetSynkrontVedOppfolgingsStart',
       'ArenaKontorManuellSynk', 'ArenaKontorVedOppfolgingsStart', 'ArenaKontorVedOppfolgingStartMedEtterslep',
       'EndretIArena', 'TidligArenaKontorVedOppfolgingStart');
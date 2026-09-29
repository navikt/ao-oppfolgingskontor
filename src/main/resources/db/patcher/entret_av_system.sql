-- Kjørt manuelt i dev og prod 16/3-26 for å sette mer spesifikke endret-av-verdier for endringer gjort av system.

-- alternativ_aokontor:
update alternativ_aokontor
set endret_av = 'VEILARBOPPFOLGING'
where endret_av = 'SYSTEM'
  and kontorendringstype in ('AutomatiskNorgRuting', 'AutomatiskNorgRutingFallback', 'AutomatiskRutetTilNavItGtErLand',
                             'AutomatiskRutetTilNavItUgyldigGt', 'AutomatiskRutetTilNOE',
                             'AutomatiskRutingArbeidsgiverFallback');

update alternativ_aokontor
set endret_av = 'PDL'
where endret_av = 'SYSTEM'
  and kontorendringstype = 'FikkAddressebeskyttelse';

update alternativ_aokontor
set endret_av = 'SKJERMING'
where endret_av = 'SYSTEM'
  and kontorendringstype in ('FikkSkjerming', 'MistetSkjerming');

-- kontorhistorikk:
update kontorhistorikk
set endret_av = 'VEILARBOPPFOLGING'
where endret_av = 'SYSTEM'
  and kontorendringstype in ('AutomatiskNorgRuting', 'AutomatiskNorgRutingFallback', 'AutomatiskRutetTilNavItGtErLand',
                             'AutomatiskRutetTilNavItUgyldigGt', 'AutomatiskRutetTilNOE',
                             'AutomatiskRutingArbeidsgiverFallback', 'GTKontorVedOppfolgingStart');

update kontorhistorikk
set endret_av = 'PDL'
where endret_av = 'SYSTEM'
  and kontorendringstype in ('FikkAddressebeskyttelse', 'AddressebeskyttelseMistet', 'EndretBostedsadresse');

update kontorhistorikk
set endret_av = 'SKJERMING'
where endret_av = 'SYSTEM'
  and kontorendringstype in ('FikkSkjerming', 'MistetSkjerming');

update kontorhistorikk
set endret_av = 'ARENA'
where endret_av = 'SYSTEM'
  and kontorendringstype in
      ('ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart', 'ArenaKontorHentetSynkrontVedOppfolgingsStart',
       'ArenaKontorManuellSynk', 'ArenaKontorVedOppfolgingsStart', 'ArenaKontorVedOppfolgingStartMedEtterslep',
       'EndretIArena', 'TidligArenaKontorVedOppfolgingStart');

-- arbeidsoppfolgingskontor: 
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
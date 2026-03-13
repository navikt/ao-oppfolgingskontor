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
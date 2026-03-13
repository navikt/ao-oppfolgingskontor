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
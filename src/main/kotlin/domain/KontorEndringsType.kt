package no.nav.domain

enum class KontorEndringsType {
    /* Arbeidsoppfølgingskontor */
    AutomatiskRutetTilNOE,
    AutomatiskNorgRuting, // navkontor/{{geografisk-tilhørighet}}
    AutomatiskNorgRutingFallback, // /arbeidsfordeling/bestmatch
    AutomatiskRutingArbeidsgiverFallback, // adresse fra arbeidsgiver forhold hentet fra aareg
    AutomatiskRutetTilNavItManglerGt,
    AutomatiskRutetTilNavItGtErLand,
    AutomatiskRutetTilNavItUgyldigGt,
    AutomatiskRutetTilNavItIngenKontorFunnetForGt,
    FlyttetAvVeileder,

    /* Både Arbeidsoppfølgingskontor og GT kontor  */
    FikkSkjerming,
    FikkAddressebeskyttelse,

    /* GT kontor */
    GTKontorVedOppfolgingStart,
    EndretBostedsadresse,
    MistetSkjerming,
    AddressebeskyttelseMistet,

    /* ArenaKontor */
    EndretIArena,
    ArenaKontorManuellSynk,
    @Deprecated("Skal ikke lenger brukes")
    ArenaKontorVedOppfolgingsStart,
    ArenaKontorHentetSynkrontVedOppfolgingsStart,
    @Deprecated("Skal ikke lenger brukes")
    TidligArenaKontorVedOppfolgingStart,
    ArenaKontorVedOppfolgingStartMedEtterslep,
    @Deprecated("Skal ikke lenger brukes")
    ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart,
    MIGRERING,
    PATCH
}
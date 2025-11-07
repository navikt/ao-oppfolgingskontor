package no.nav.domain

enum class KontorEndringsType {
    /* Arbeidsoppfølgingskontor */
    AutomatiskRutetTilNOE,
    AutomatiskNorgRuting, // navkontor/{{geografisk-tilhørighet}}
    AutomatiskNorgRutingFallback, // /arbeidsfordeling/bestmatch
    AutomatiskRutetTilNavItManglerGt,
    AutomatiskRutetTilNavItGtErLand,
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
    @Deprecated("Skal ikke lenger brukes")
    ArenaKontorVedOppfolgingsStart,
    ArenaKontorHentetSynkrontVedOppfolgingsStart,
    TidligArenaKontorVedOppfolgingStart,
    ArenaKontorVedOppfolgingStartMedEtterslep,
    MIGRERING,
    ArenaMigrering,
    PATCH
}
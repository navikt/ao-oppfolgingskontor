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
    @Deprecated("Alle kontor bør taes fra topic, ikke fra veilarboppfolging")
    ArenaKontorVedOppfolgingsStart,
    TidligArenaKontorVedOppfolgingStart,
    ArenaKontorVedOppfolgingStartMedEtterslep,
    MIGRERING,
    ArenaMigrering,
    PATCH
}
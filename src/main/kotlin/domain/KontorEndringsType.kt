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
    StartKontorSattManueltAvVeileder,
    FlyttetAvVeileder,
    KontorMergeViaAdmin, // Ønsker å kalle det "kontor-sammenslåing" men graphql blir sint når man bruker å

    /* Både Arbeidsoppfølgingskontor og GT kontor  */
    FikkSkjerming,
    FikkAddressebeskyttelse,
    MistetSkjerming,
    FikkNorskGt,

    /* GT kontor */
    GTKontorVedOppfolgingStart,
    AddressebeskyttelseMistet,
    EndretBostedsadresse,

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
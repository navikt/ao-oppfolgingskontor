package services

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.INGEN_GT_KONTOR_FALLBACK
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.Sensitivitet
import no.nav.domain.events.AOKontorEndretPgaAdressebeskyttelseEndret
import no.nav.domain.events.AOKontorEndretPgaSkjermingEndret
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetFallbackKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetSensitivKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeAvsluttet
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderIkkeFunnet
import no.nav.http.client.AlderResult
import no.nav.http.client.FnrFunnet
import no.nav.http.client.FnrIkkeFunnet
import no.nav.http.client.FnrResult
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.HarStrengtFortroligAdresseFunnet
import no.nav.http.client.HarStrengtFortroligAdresseIkkeFunnet
import no.nav.http.client.HarStrengtFortroligAdresseResult
import no.nav.http.client.SkjermingFunnet
import no.nav.http.client.SkjermingIkkeFunnet
import no.nav.http.client.SkjermingResult
import no.nav.http.client.arbeidssogerregisteret.HentProfileringsResultat
import no.nav.http.client.arbeidssogerregisteret.ProfileringFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringIkkeFunnet
import no.nav.http.client.arbeidssogerregisteret.ProfileringsResultat
import no.nav.http.client.toGtKontorFunnet
import no.nav.kafka.consumers.EndringISkjermingResult
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.AutomatiskKontorRutingService.Companion.VIKAFOSSEN
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorForGtNrFantLand
import no.nav.services.KontorForGtNrResultat
import no.nav.services.TilordningFeil
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret

class AutomatiskKontorRutingServiceTest: DescribeSpec({

    describe("Endring i oppfolgingsperiode") {
        val aktorId = "223456789"

        describe("start oppfolgingsperiode ") {
            it("skal sette AO kontor til lokalkontor for unge brukere (under 30)") {
                gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(ungBrukerMedGodeMuligheter.fnr(),  ungBrukerMedGodeMuligheter.gtKontor()),
                        ingenSensitivitet
                    )
                )
            }

            it("skal sette AO kontor til NOE hvis gode muligheter og over 30 år") {
                gitt(eldreBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsperiodeStartetNoeTilordning(
                        eldreBrukerMedGodeMuligheter.fnr()
                    )
                )
            }

            it("skal sette AO kontor til fallback (it avdelingen) hvis gt ikke finnes") {
                gitt(brukerSomManglerGt).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetFallbackKontorTilordning(
                        brukerSomManglerGt.fnr(),
                        Sensitivitet(
                            HarSkjerming(false),
                            HarStrengtFortroligAdresse(false)
                        )
                    )
                )
            }

            it("skal sette AO kontor til et adressebeskyttet kontor hvis adressebeskyttet bruker") {
                gitt(adressebeskyttetBruker).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(adressebeskyttetBruker.fnr(), adressebeskyttetBruker.gtKontor()),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true))
                    )
                )
            }

            it("skal sette AO kontor til et skjermet kontor hvis skjermet bruker") {
                gitt(skjermetBruker).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(skjermetBruker.fnr(), adressebeskyttetBruker.gtKontor()),
                        Sensitivitet(
                            HarSkjerming(true),
                            HarStrengtFortroligAdresse(false)
                        )
                    )
                )
            }

            it("skal sette AO kontor til lokalkontor hvis har antatt behov for veiledening") {
                gitt(ungBrukerMedbehovForVeiledning).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(ungBrukerMedbehovForVeiledning.fnr(), ungBrukerMedbehovForVeiledning.gtKontor()),
                        ingenSensitivitet
                    )
                )
            }

            it("skal bruke fallbackkontor hvis bruker har landskode som gt") {
                gitt(brukerMedLandskode).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetFallbackKontorTilordning(
                        brukerMedLandskode.fnr(),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(false))
                    )
                )
            }

            it("skal rute til vikafossen hvis bruker har landskode som gt, men adressebeskyttelse") {
                gitt(brukerMedAdressebeskyttelseOgLandskode).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(brukerMedAdressebeskyttelseOgLandskode.fnr(), VIKAFOSSEN),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true))
                    )
                )
            }

            it("skal throwe hvis bruker har landskode som gt, men er skjermet") {
                gitt(skjermetBrukerMedLandskode).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                ) shouldBe TilordningFeil("Feil ved tilordning av kontor: Vi håndterer ikke skjermede brukere uten geografisk tilknytning")
            }

        }

        it("avsluttet oppfolgingsperiode skal ikke sette ao kontor") {
            gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                OppfolgingsperiodeAvsluttet(aktorId)
            ) shouldBe TilordningSuccessIngenEndring
        }
    }

    describe("Endring i adressebeskyttelse") {
        it("skal bare endre GT kontor når bruker ikke har strengt fortrolig adresse") {
            gitt(ungBrukerMedGodeMuligheter)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(ungBrukerMedGodeMuligheter.fnr(), Gradering.FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(listOf(
                    GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor()
                        )
                    )
                ))
        }

        it("skal sette AO og GT kontor når bruker får strengt fortrolig adresse") {
            gitt(ungBrukerMedGodeMuligheter)
                .handterEndringForAdressebeskyttelse(
                AdressebeskyttelseEndret(ungBrukerMedGodeMuligheter.fnr(), Gradering.STRENGT_FORTROLIG)
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        ungBrukerMedGodeMuligheter.fnr(),
                        ungBrukerMedGodeMuligheter.gtKontor()
                    )
                ),
                AOKontorEndretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        ungBrukerMedGodeMuligheter.fnr(),
                        ungBrukerMedGodeMuligheter.gtKontor()
                    )
                )
            ))
        }

        it("skal sette AO og GT kontor når bruker får strengt fortrolig adresse også når bruker har landskode") {
            gitt(brukerMedAdressebeskyttelseOgLandskode)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerMedAdressebeskyttelseOgLandskode.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        brukerMedAdressebeskyttelseOgLandskode.fnr(),
                        VIKAFOSSEN
                    )
                ),
                AOKontorEndretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        brukerMedAdressebeskyttelseOgLandskode.fnr(),
                        VIKAFOSSEN
                    )
                )
            ))
        }
    }

    describe("Endring i skjermingstatus") {

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    ungBrukerMedGodeMuligheter.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe  Result.success(EndringISkjermingResult(
                listOf(
                    GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor()
                        )
                    ),
                    AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor()
                        )
                    ),
                )
            ))
        }

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet også når bruker har landskode") {
            gitt(brukerMedLandskode).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    ungBrukerMedGodeMuligheter.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe  Result.success(EndringISkjermingResult(
                listOf(
                    GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            INGEN_GT_KONTOR_FALLBACK
                        )
                    ),
                    AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            INGEN_GT_KONTOR_FALLBACK
                        )
                    ),
                )
            ))
        }

        it("skal bare sette GT kontor når bruker blir av-skjermet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    ungBrukerMedGodeMuligheter.fnr(),
                    HarSkjerming(false)
                )
            ) shouldBe  Result.success(EndringISkjermingResult(
                listOf(
                    GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor()
                        )
                    )
                )
            ))
        }
    }

    describe("Endring i bostedsadresse") {
        it("skal bare sette gt kontor ved adresseendring for bruker uten sensitivitet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringForBostedsadresse(
                BostedsadresseEndret(ungBrukerMedGodeMuligheter.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        ungBrukerMedGodeMuligheter.fnr(),
                        ungBrukerMedGodeMuligheter.gtKontor()
                    )
                )
            ))
        }

        it("skal bare sette gt kontor ved adresseendring for bruker med strengt fortrolig adresse") {
            gitt(adressebeskyttetBruker).handterEndringForBostedsadresse(
                BostedsadresseEndret(adressebeskyttetBruker.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        adressebeskyttetBruker.fnr(),
                        adressebeskyttetBruker.gtKontor()
                    )
                )
            ))
        }

        it("skal bare sette gt kontor ved adresseendring for bruker med skjerming") {
            gitt(skjermetBruker).handterEndringForBostedsadresse(
                BostedsadresseEndret(skjermetBruker.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        skjermetBruker.fnr(),
                        skjermetBruker.gtKontor()
                    )
                )
            ))
        }
    }

    describe("Feilhåndtering") {
        val aktorId = "123456789"
        describe("tilordneKontorAutomatisk") {
            feilendeBrukere.map { bruker ->
                gitt(bruker).tilordneKontorAutomatisk(
                    OppfolgingsperiodeStartet(aktorId)
                )
            } shouldBe listOf(
                TilordningFeil("Fant ikke fnr: feil i fnr"),
                TilordningFeil("Kunne ikke hente alder: feil i alder"),
                TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(
                            brukerMedFeilendeProfilering.fnr(),
                            brukerMedFeilendeProfilering.gtKontor()
                        ),
                        ingenSensitivitet
                    )
                ),
                TilordningFeil("Kunne ikke hente skjerming ved kontortilordning: feil i skjerming"),
                TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: feil i adressebeskyttelse"),
            )
        }


        it("handterEndringISkjermingStatus - feil ved henting av adressebeskyttelse skal returnere feil") {
            val fnr = "123456789"
            val bruker = feilendeBrukere.last()
            gitt(bruker).handterEndringISkjermingStatus (
                SkjermetStatusEndret(fnr, HarSkjerming(true))
            ).isFailure shouldBe true
        }

        describe("handterEndringISkjermingStatus") {
            val fnr = "123456789"
            val success = Result.success(EndringISkjermingResult(listOf(
                GTKontorEndret.endretPgaSkjermingEndret(
                    KontorTilordning(
                        fnr,
                        brukerMedFeilendeFnr.gtKontor()
                    )
                ),
                AOKontorEndretPgaSkjermingEndret(
                    KontorTilordning(
                        fnr,
                        brukerMedFeilendeFnr.gtKontor()
                    )
                )
            )
            ))
            feilendeBrukere.take(4) .map { bruker ->
                gitt(bruker).handterEndringISkjermingStatus (
                    SkjermetStatusEndret(fnr, HarSkjerming(true))
                )
            } shouldBe listOf(
                success,
                success,
                success,
                success,
            )
        }
    }
})

fun gitt(bruker: Bruker): AutomatiskKontorRutingService {
    return AutomatiskKontorRutingService(
        {},
        { _, strengtFortroligAdresse, skjerming ->
            when (bruker.gtKontor) {
                is KontorForGtNrFantKontor -> bruker.gtKontor.kontorId.toGtKontorFunnet(strengtFortroligAdresse, skjerming)
                else -> bruker.gtKontor
            }
        },
        { bruker.alder },
        { bruker.fnr },
        { bruker.profilering },
        { bruker.skjerming },
        { bruker.strengtFortroligAdresse },
    )
}

data class Bruker(
    val fnr: FnrResult,
    val alder: AlderResult,
    val profilering: HentProfileringsResultat,
    val gtKontor: KontorForGtNrResultat,
    val skjerming: SkjermingResult,
    val strengtFortroligAdresse: HarStrengtFortroligAdresseResult
) {
    fun fnr(): String {
        if (fnr is FnrFunnet) {
            return fnr.fnr
        }
        throw IllegalStateException("Fnr is ${this.fnr}")
    }
    fun gtKontor(): KontorId {
        if (gtKontor is KontorForGtNrFantKontor) {
            return gtKontor.kontorId
        }
        throw IllegalStateException("gtKontor is ${this.gtKontor}")
    }
}

val ungBrukerMedGodeMuligheter = Bruker(
    FnrFunnet("123456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val eldreBrukerMedGodeMuligheter = Bruker(
    FnrFunnet("223456789"),
    AlderFunnet(31),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val ungBrukerMedbehovForVeiledning = Bruker(
    FnrFunnet("323456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_BEHOV_FOR_VEILEDNING),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerSomManglerGt = Bruker(
    FnrFunnet("423456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFinnesIkke(HarSkjerming(false),
        HarStrengtFortroligAdresse(false)
    ),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val skjermetBruker = Bruker(
    FnrFunnet("523456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(true), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val adressebeskyttetBruker = Bruker(
    FnrFunnet("623456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(true)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val skjermetBrukerMedLandskode = Bruker(
    FnrFunnet("723456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(true), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)))
val brukerMedLandskode = Bruker(
    FnrFunnet("823456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedAdressebeskyttelseOgLandskode = Bruker(
    FnrFunnet("923456789"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(true)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)

/* Brukere med feil */
val brukerMedFeilendeFnr = Bruker(
    FnrIkkeFunnet("feil i fnr"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeAlder = Bruker(
    FnrFunnet("1"),
    AlderIkkeFunnet("feil i alder"),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeProfilering = Bruker(
    FnrFunnet("1"),
    AlderFunnet(20),
    ProfileringIkkeFunnet("feil i profilering"),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeSkjerming = Bruker(
    FnrFunnet("1"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingIkkeFunnet("feil i skjerming"),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeAdressebeskyttelse = Bruker(
    FnrFunnet("1"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseIkkeFunnet("feil i adressebeskyttelse")
)
val feilendeBrukere = listOf(
    brukerMedFeilendeFnr,
    brukerMedFeilendeAlder,
    brukerMedFeilendeProfilering,
    brukerMedFeilendeSkjerming,
    brukerMedFeilendeAdressebeskyttelse
)

val ingenSensitivitet = Sensitivitet(
    HarSkjerming(false),
    HarStrengtFortroligAdresse(false)
)
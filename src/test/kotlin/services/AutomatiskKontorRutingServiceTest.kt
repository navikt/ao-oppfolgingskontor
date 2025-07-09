package services

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.INGEN_GT_KONTOR_FALLBACK
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
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
import no.nav.http.client.FnrOppslagFeil
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
import no.nav.kafka.consumers.HåndterPersondataEndretFail
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.AutomatiskKontorRutingService.Companion.VIKAFOSSEN
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorForGtNrFantLand
import no.nav.services.KontorForGtNrFeil
import no.nav.services.KontorForGtNrResultat
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeOppslagResult
import no.nav.services.TilordningFeil
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import java.time.ZonedDateTime
import java.util.UUID

class AutomatiskKontorRutingServiceTest: DescribeSpec({

    describe("Endring i oppfolgingsperiode") {
        val aktorId = "223456789"

        describe("start oppfolgingsperiode ") {
            it("skal sette AO kontor til lokalkontor for unge brukere (under 30)") {
                gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(ungBrukerMedGodeMuligheter)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(ungBrukerMedGodeMuligheter.fnr(),  ungBrukerMedGodeMuligheter.gtKontor(), ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()),
                        ingenSensitivitet
                    )
                )
            }

            it("skal sette AO kontor til NOE hvis gode muligheter og over 30 år") {
                gitt(eldreBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(eldreBrukerMedGodeMuligheter)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsperiodeStartetNoeTilordning(
                        eldreBrukerMedGodeMuligheter.fnr(),
                        eldreBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                    )
                )
            }

            it("skal sette AO kontor til fallback (it avdelingen) hvis gt ikke finnes") {
                gitt(brukerSomManglerGt).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerSomManglerGt)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetFallbackKontorTilordning(
                        brukerSomManglerGt.fnr(),
                        brukerSomManglerGt.oppfolgingsperiodeId(),
                        Sensitivitet(
                            HarSkjerming(false),
                            HarStrengtFortroligAdresse(false)
                        )
                    )
                )
            }

            it("skal sette AO kontor til et adressebeskyttet kontor hvis adressebeskyttet bruker") {
                gitt(adressebeskyttetBruker).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(adressebeskyttetBruker)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(adressebeskyttetBruker.fnr(), adressebeskyttetBruker.gtKontor(), adressebeskyttetBruker.oppfolgingsperiodeId()),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true))
                    )
                )
            }

            it("skal sette AO kontor til et skjermet kontor hvis skjermet bruker") {
                gitt(skjermetBruker).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(skjermetBruker)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(skjermetBruker.fnr(), skjermetBruker.gtKontor(), skjermetBruker.oppfolgingsperiodeId()),
                        Sensitivitet(
                            HarSkjerming(true),
                            HarStrengtFortroligAdresse(false)
                        )
                    )
                )
            }

            it("skal sette AO kontor til lokalkontor hvis har antatt behov for veiledening") {
                gitt(ungBrukerMedbehovForVeiledning).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(ungBrukerMedbehovForVeiledning)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(ungBrukerMedbehovForVeiledning.fnr(), ungBrukerMedbehovForVeiledning.gtKontor(), ungBrukerMedbehovForVeiledning.oppfolgingsperiodeId()),
                        ingenSensitivitet
                    )
                )
            }

            it("skal bruke fallbackkontor hvis bruker har landskode som gt") {
                gitt(brukerMedLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedLandskode)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetFallbackKontorTilordning(
                        brukerMedLandskode.fnr(),
                        brukerMedLandskode.oppfolgingsperiodeId(),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(false))
                    )
                )
            }

            it("skal rute til vikafossen hvis bruker har landskode som gt, men adressebeskyttelse") {
                gitt(brukerMedAdressebeskyttelseOgLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedAdressebeskyttelseOgLandskode)
                ) shouldBe TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetSensitivKontorTilordning(
                        KontorTilordning(brukerMedAdressebeskyttelseOgLandskode.fnr(), VIKAFOSSEN, brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()),
                        Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true))
                    )
                )
            }

            it("skal throwe hvis bruker har landskode som gt, men er skjermet") {
                gitt(skjermetBrukerMedLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(skjermetBrukerMedLandskode)
                ) shouldBe TilordningFeil("Feil ved tilordning av kontor: Vi håndterer ikke skjermede brukere uten geografisk tilknytning")
            }

        }

        it("avsluttet oppfolgingsperiode skal ikke sette ao kontor") {
            gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                OppfolgingsperiodeAvsluttet(ungBrukerMedGodeMuligheter.fnr())
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
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        HarStrengtFortroligAdresse(false)
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
                        ungBrukerMedGodeMuligheter.gtKontor(),
                        ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                    ),
                    HarStrengtFortroligAdresse(true)
                ),
                AOKontorEndretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        ungBrukerMedGodeMuligheter.fnr(),
                        ungBrukerMedGodeMuligheter.gtKontor(),
                        ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
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
                        VIKAFOSSEN,
                        brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                    ),
                    HarStrengtFortroligAdresse(true)
                ),
                AOKontorEndretPgaAdressebeskyttelseEndret(
                    KontorTilordning(
                        brukerMedAdressebeskyttelseOgLandskode.fnr(),
                        VIKAFOSSEN,
                        brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                    )
                )
            ))
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerIkkeUnderOppfolging.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(emptyList())
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
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(true)
                    ),
                    AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        )
                    ),
                )
            ))
        }

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet også når bruker har landskode") {
            gitt(brukerMedLandskode).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    brukerMedLandskode.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe  Result.success(EndringISkjermingResult(
                listOf(
                    GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskode.fnr(),
                            INGEN_GT_KONTOR_FALLBACK,
                            brukerMedLandskode.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(true)
                    ),
                    AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskode.fnr(),
                            INGEN_GT_KONTOR_FALLBACK,
                            brukerMedLandskode.oppfolgingsperiodeId()
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
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(false)
                    )
                )
            ))
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging).handterEndringISkjermingStatus(
                SkjermetStatusEndret(brukerIkkeUnderOppfolging.fnr(), HarSkjerming(true))
            ) shouldBe Result.success(EndringISkjermingResult(emptyList()))
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
                        ungBrukerMedGodeMuligheter.gtKontor(),
                        ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
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
                        adressebeskyttetBruker.gtKontor(),
                        adressebeskyttetBruker.oppfolgingsperiodeId()
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
                        skjermetBruker.gtKontor(),
                        skjermetBruker.oppfolgingsperiodeId()
                    )
                )
            ))
        }

        it("skal synce gt kontor med norg for brukere med gt-landskode også") {
            gitt(brukerMedLandskode).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerMedLandskode.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(listOf(
                GTKontorEndret.endretPgaBostedsadresseEndret(
                    KontorTilordning(
                        brukerMedLandskode.fnr(),
                        INGEN_GT_KONTOR_FALLBACK,
                        brukerMedLandskode.oppfolgingsperiodeId()
                    )
                )
            ))
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerIkkeUnderOppfolging.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(emptyList())
        }
    }

    describe("Feilhåndtering") {
        val fnr = Fnr("12345678901")
        describe("tilordneKontorAutomatisk") {
            feilendeBrukere.map { bruker ->
                gitt(bruker).tilordneKontorAutomatisk(oppfolgingsperiodeStartet(fnr))
            } shouldBe listOf(
                TilordningFeil("Feil ved oppslag på fnr: feil i fnr"),
                TilordningFeil("Kunne ikke hente alder: feil i alder"),
                TilordningSuccessKontorEndret(
                    OppfolgingsPeriodeStartetLokalKontorTilordning(
                        KontorTilordning(
                            brukerMedFeilendeProfilering.fnr(),
                            brukerMedFeilendeProfilering.gtKontor(),
                            brukerMedFeilendeProfilering.oppfolgingsperiodeId()
                        ),
                        ingenSensitivitet
                    )
                ),
                TilordningFeil("Kunne ikke hente skjerming ved kontortilordning: feil i skjerming"),
                TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: feil i adressebeskyttelse"),
                TilordningFeil("Feil ved henting av gt-kontor: Feil i gt-kontor oppslag"),
            )
        }

        describe("handterEndringISkjermingStatus") {
            it("handterEndringISkjermingStatus - feil ved henting av adressebeskyttelse skal returnere feil") {
                gitt(brukerMedFeilendeAdressebeskyttelse).handterEndringISkjermingStatus (
                    SkjermetStatusEndret(brukerMedFeilendeAdressebeskyttelse.fnr(), HarSkjerming(true))
                ).isFailure shouldBe true
            }
            it("handterEndringISkjermingStatus - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringISkjermingStatus (
                    SkjermetStatusEndret(brukerMedFeilendeKontorForGt.fnr(), HarSkjerming(true))
                ).isFailure shouldBe true
            }
        }

        describe("handterEndringForAdressebeskyttelse") {
            it("handterEndringForAdressebeskyttelse - feil ved henting av skjerming skal returnere feil") {
                gitt(brukerMedFeilendeSkjerming).handterEndringForAdressebeskyttelse (
                    AdressebeskyttelseEndret(brukerMedFeilendeSkjerming.fnr(), Gradering.STRENGT_FORTROLIG)
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForAdressebeskyttelse - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringForAdressebeskyttelse (
                    AdressebeskyttelseEndret(brukerMedFeilendeKontorForGt.fnr(), Gradering.STRENGT_FORTROLIG)
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
        }

        describe("handterEndringForBostedsadresse") {
            it("handterEndringForBostedsadresse - feil ved henting av adressebeskyttelse skal returnere feil") {
                gitt(brukerMedFeilendeAdressebeskyttelse).handterEndringForBostedsadresse (
                    BostedsadresseEndret(brukerMedFeilendeAdressebeskyttelse.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForBostedsadresse - feil ved henting av skjerming skal returnere feil") {
                gitt(brukerMedFeilendeSkjerming).handterEndringForBostedsadresse (
                    BostedsadresseEndret(brukerMedFeilendeSkjerming.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForAdressebeskyttelse - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringForBostedsadresse (
                    BostedsadresseEndret(brukerMedFeilendeKontorForGt.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
        }
    }
})

fun oppfolgingsperiodeStartet(bruker: Bruker) = oppfolgingsperiodeStartet(bruker.fnr())

fun oppfolgingsperiodeStartet(fnr: Fnr): OppfolgingsperiodeStartet {
    return OppfolgingsperiodeStartet(
        fnr,
        ZonedDateTime.now(),
        UUID.randomUUID()
    )
}

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
        { bruker.oppfolgingsPeriodeResult }
    )
}

fun defaultOppfolgingsperiodeOppslagResult(fnr: FnrResult): OppfolgingsperiodeOppslagResult {
    return when (fnr) {
        is FnrFunnet -> return AktivOppfolgingsperiode(fnr.fnr, OppfolgingsperiodeId(UUID.randomUUID()))
        is FnrIkkeFunnet -> OppfolgingperiodeOppslagFeil(fnr.message)
        is FnrOppslagFeil -> OppfolgingperiodeOppslagFeil(fnr.message)
    }
}

data class Bruker(
    val fnr: FnrResult,
    val alder: AlderResult,
    val profilering: HentProfileringsResultat,
    val gtKontor: KontorForGtNrResultat,
    val skjerming: SkjermingResult,
    val strengtFortroligAdresse: HarStrengtFortroligAdresseResult,
    val oppfolgingsPeriodeResult: OppfolgingsperiodeOppslagResult = defaultOppfolgingsperiodeOppslagResult(fnr)
) {
    fun fnr(): Fnr {
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
    fun oppfolgingsperiodeId(): OppfolgingsperiodeId {
        if (oppfolgingsPeriodeResult is AktivOppfolgingsperiode) {
            return oppfolgingsPeriodeResult.periodeId
        }
        throw IllegalStateException("OppfolgingsperiodeResult is ${this.oppfolgingsPeriodeResult}")
    }
}

val ungBrukerMedGodeMuligheter = Bruker(
    FnrFunnet(Fnr("12345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val eldreBrukerMedGodeMuligheter = Bruker(
    FnrFunnet(Fnr("22345678901")),
    AlderFunnet(31),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val ungBrukerMedbehovForVeiledning = Bruker(
    FnrFunnet(Fnr("32345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_BEHOV_FOR_VEILEDNING),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerSomManglerGt = Bruker(
    FnrFunnet(Fnr("42345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFinnesIkke(HarSkjerming(false),
        HarStrengtFortroligAdresse(false)
    ),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val skjermetBruker = Bruker(
    FnrFunnet(Fnr("52345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(true), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val adressebeskyttetBruker = Bruker(
    FnrFunnet(Fnr("62345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(true)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val skjermetBrukerMedLandskode = Bruker(
    FnrFunnet(Fnr("72345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(true), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)))
val brukerMedLandskode = Bruker(
    FnrFunnet(Fnr("82345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedAdressebeskyttelseOgLandskode = Bruker(
    FnrFunnet(Fnr("92345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(true)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val brukerIkkeUnderOppfolging = Bruker(
    FnrFunnet(Fnr("93345678901")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("4141"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
    NotUnderOppfolging
)

/* Brukere med feil */
val brukerMedFeilendeFnr = Bruker(
    FnrIkkeFunnet("feil i fnr"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
)
val brukerMedFeilendeAlder = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderIkkeFunnet("feil i alder"),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeProfilering = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringIkkeFunnet("feil i profilering"),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeSkjerming = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingIkkeFunnet("feil i skjerming"),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeAdressebeskyttelse = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantKontor(KontorId("1234"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseIkkeFunnet("feil i adressebeskyttelse")
)
val brukerMedFeilendeKontorForGt = Bruker(
    FnrFunnet(Fnr("11111111111")),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFeil("Feil i gt-kontor oppslag"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val feilendeBrukere = listOf(
    brukerMedFeilendeFnr,
    brukerMedFeilendeAlder,
    brukerMedFeilendeProfilering,
    brukerMedFeilendeSkjerming,
    brukerMedFeilendeAdressebeskyttelse,
    brukerMedFeilendeKontorForGt
)

val ingenSensitivitet = Sensitivitet(
    HarSkjerming(false),
    HarStrengtFortroligAdresse(false)
)
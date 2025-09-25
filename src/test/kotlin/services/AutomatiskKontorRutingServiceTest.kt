package services

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.db.Fnr
import no.nav.db.Ident.HistoriskStatus.AKTIV
import no.nav.db.Ident.HistoriskStatus.UKJENT
import no.nav.db.IdentSomKanLagres
import no.nav.domain.GT_VAR_LAND_FALLBACK
import no.nav.domain.HarSkjerming
import no.nav.domain.HarStrengtFortroligAdresse
import no.nav.domain.INGEN_GT_KONTOR_FALLBACK
import no.nav.domain.KontorEndringsType
import no.nav.domain.KontorId
import no.nav.domain.KontorTilordning
import no.nav.domain.OppfolgingsperiodeId
import no.nav.domain.Sensitivitet
import no.nav.domain.events.AOKontorEndretPgaAdressebeskyttelseEndret
import no.nav.domain.events.AOKontorEndretPgaSkjermingEndret
import no.nav.domain.events.ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart
import no.nav.domain.events.ArenaKontorVedOppfolgingStart
import no.nav.domain.events.GTKontorEndret
import no.nav.domain.events.OppfolgingsPeriodeStartetFallbackKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetLokalKontorTilordning
import no.nav.domain.events.OppfolgingsPeriodeStartetSensitivKontorTilordning
import no.nav.domain.events.OppfolgingsperiodeStartetNoeTilordning
import no.nav.domain.externalEvents.AdressebeskyttelseEndret
import no.nav.domain.externalEvents.BostedsadresseEndret
import no.nav.domain.externalEvents.OppfolgingsperiodeStartet
import no.nav.domain.externalEvents.SkjermetStatusEndret
import no.nav.domain.externalEvents.TidligArenaKontor
import no.nav.http.client.AlderFunnet
import no.nav.http.client.AlderIkkeFunnet
import no.nav.http.client.AlderResult
import no.nav.http.client.IdentFunnet
import no.nav.http.client.IdentIkkeFunnet
import no.nav.http.client.IdentOppslagFeil
import no.nav.http.client.IdentResult
import no.nav.http.client.GeografiskTilknytningBydelNr
import no.nav.http.client.GeografiskTilknytningLand
import no.nav.http.client.GtForBrukerFunnet
import no.nav.http.client.GtForBrukerIkkeFunnet
import no.nav.http.client.GtForBrukerOppslagFeil
import no.nav.http.client.GtForBrukerResult
import no.nav.http.client.GtForBrukerSuccess
import no.nav.http.client.GtLandForBrukerFunnet
import no.nav.http.client.GtNummerForBrukerFunnet
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
import no.nav.kafka.consumers.EndringISkjermingResult
import no.nav.kafka.consumers.HåndterPersondataEndretFail
import no.nav.kafka.consumers.HåndterPersondataEndretSuccess
import no.nav.kafka.consumers.KontorEndringer
import no.nav.person.pdl.leesah.adressebeskyttelse.Gradering
import no.nav.services.AktivOppfolgingsperiode
import no.nav.services.AutomatiskKontorRutingService
import no.nav.services.AutomatiskKontorRutingService.Companion.VIKAFOSSEN
import no.nav.services.KontorForGtFinnesIkke
import no.nav.services.KontorForGtNrFantDefaultKontor
import no.nav.services.KontorForGtNrFantKontor
import no.nav.services.KontorForGtFantLand
import no.nav.services.KontorForGtFeil
import no.nav.services.KontorForGtNrFantFallbackKontorForManglendeGt
import no.nav.services.KontorForGtResultat
import no.nav.services.KontorForGtSuccess
import no.nav.services.NotUnderOppfolging
import no.nav.services.OppfolgingperiodeOppslagFeil
import no.nav.services.OppfolgingsperiodeOppslagResult
import no.nav.services.TilordningFeil
import no.nav.services.TilordningSuccessIngenEndring
import no.nav.services.TilordningSuccessKontorEndret
import utils.Outcome
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID

class AutomatiskKontorRutingServiceTest : DescribeSpec({

    describe("Endring i oppfolgingsperiode") {
        val aktorId = "223456789"

        describe("start oppfolgingsperiode ") {
            it("skal sette AO kontor til lokalkontor for unge brukere (under 30)") {
                gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(ungBrukerMedGodeMuligheter)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                            KontorTilordning(
                                ungBrukerMedGodeMuligheter.fnr(),
                                ungBrukerMedGodeMuligheter.gtKontor(),
                                ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                            ),
                            ingenSensitivitet
                        ),
                        gtKontorEndret = ungBrukerMedGodeMuligheter.defaultGtKontorVedOppfolgStart()
                    )
                )
            }

            it("skal sette AO kontor til NOE hvis gode muligheter og over 30 år") {
                gitt(eldreBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(eldreBrukerMedGodeMuligheter)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        aoKontorEndret = OppfolgingsperiodeStartetNoeTilordning(
                            eldreBrukerMedGodeMuligheter.fnr(),
                            eldreBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        gtKontorEndret = eldreBrukerMedGodeMuligheter.defaultGtKontorVedOppfolgStart()
                    )
                )
            }

            it("skal sette AO kontor til fallback (it avdelingen) hvis gt ikke finnes") {
                gitt(brukerSomManglerGt).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerSomManglerGt)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        aoKontorEndret = OppfolgingsPeriodeStartetFallbackKontorTilordning(
                            brukerSomManglerGt.fnr(),
                            brukerSomManglerGt.oppfolgingsperiodeId(),
                            Sensitivitet(
                                HarSkjerming(false),
                                HarStrengtFortroligAdresse(false)
                            )
                        )
                    )
                )
            }

            it("skal sette AO kontor til adressebeskyttet kontor hvis adressebeskyttet bruker") {
                gitt(adressebeskyttetBruker).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(adressebeskyttetBruker)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = adressebeskyttetBruker.gtVikafossen(),
                        aoKontorEndret = OppfolgingsPeriodeStartetSensitivKontorTilordning(
                            KontorTilordning(
                                adressebeskyttetBruker.fnr(),
                                adressebeskyttetBruker.gtKontor(),
                                adressebeskyttetBruker.oppfolgingsperiodeId()
                            ),
                            Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true)),
                            adressebeskyttetBruker.gtKontor as KontorForGtNrFantDefaultKontor
                        )
                    )
                )
            }

            it("skal sette AO kontor til et skjermet kontor hvis skjermet bruker") {
                gitt(skjermetBruker).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(skjermetBruker)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = skjermetBruker.defaultGtKontorVedOppfolgStart(),
                        aoKontorEndret = OppfolgingsPeriodeStartetSensitivKontorTilordning(
                            KontorTilordning(
                                skjermetBruker.fnr(),
                                skjermetBruker.gtKontor(),
                                skjermetBruker.oppfolgingsperiodeId()
                            ),
                            Sensitivitet(
                                HarSkjerming(true),
                                HarStrengtFortroligAdresse(false)
                            ),
                            skjermetBruker.gtKontor as KontorForGtNrFantKontor
                        )
                    )
                )
            }

            it("skal sette AO kontor til lokalkontor hvis har antatt behov for veiledening") {
                gitt(ungBrukerMedbehovForVeiledning).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(ungBrukerMedbehovForVeiledning)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = ungBrukerMedbehovForVeiledning.defaultGtKontorVedOppfolgStart(),
                        aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                            KontorTilordning(
                                ungBrukerMedbehovForVeiledning.fnr(),
                                ungBrukerMedbehovForVeiledning.gtKontor(),
                                ungBrukerMedbehovForVeiledning.oppfolgingsperiodeId()
                            ),
                            ingenSensitivitet
                        )
                    )
                )
            }

            it("skal sette AO kontor til lokalkontor hvis bruker har feilende profilering") {
                gitt(brukerMedFeilendeProfilering).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedFeilendeProfilering)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = brukerMedFeilendeProfilering.defaultGtKontorVedOppfolgStart(),
                        aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                            KontorTilordning(
                                brukerMedFeilendeProfilering.fnr(),
                                brukerMedFeilendeProfilering.gtKontor(),
                                brukerMedFeilendeProfilering.oppfolgingsperiodeId()
                            ),
                            ingenSensitivitet
                        )
                    )
                )
            }

            it("skal bruke arbeidsfordeling-fallback hvis bruker har landskode som gt") {
                gitt(brukerMedLandskodeOgFallback).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedLandskodeOgFallback)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = brukerMedLandskodeOgFallback.defaultGtKontorVedOppfolgStart(),
                        aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                            KontorTilordning(
                                brukerMedLandskodeOgFallback.fnr(),
                                brukerMedLandskodeOgFallback.gtKontor(),
                                brukerMedLandskodeOgFallback.oppfolgingsperiodeId()
                            ),
                            Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(false))
                        )
                    )
                )
            }

            it("skal bruke hardkodet-fallback hvis bruker har landskode som gt men ikke fikk treff på arbeidsfordeling") {
                gitt(brukerMedLandskodeUtenFallback).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedLandskodeUtenFallback)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = GTKontorEndret(
                            kontorTilordning = KontorTilordning(
                                brukerMedLandskodeUtenFallback.fnr(),
                                GT_VAR_LAND_FALLBACK,
                                brukerMedLandskodeUtenFallback.oppfolgingsperiodeId()
                            ),
                            KontorEndringsType.GTKontorVedOppfolgingStart,
                            brukerMedLandskodeUtenFallback.gtForBruker as GtForBrukerFunnet,
                        ),
                        aoKontorEndret = OppfolgingsPeriodeStartetFallbackKontorTilordning(
                            brukerMedLandskodeUtenFallback.fnr(),
                            brukerMedLandskodeUtenFallback.oppfolgingsperiodeId(),
                            Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(false))
                        )
                    )
                )
            }

            it("skal rute til vikafossen hvis bruker har landskode som gt, men adressebeskyttelse") {
                gitt(brukerMedAdressebeskyttelseOgLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedAdressebeskyttelseOgLandskode)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = GTKontorEndret(
                            kontorTilordning = KontorTilordning(
                                brukerMedAdressebeskyttelseOgLandskode.fnr(),
                                VIKAFOSSEN,
                                brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                            ),
                            KontorEndringsType.GTKontorVedOppfolgingStart,
                            brukerMedAdressebeskyttelseOgLandskode.gtForBruker as GtForBrukerFunnet,
                        ),
                        aoKontorEndret = OppfolgingsPeriodeStartetSensitivKontorTilordning(
                            KontorTilordning(
                                brukerMedAdressebeskyttelseOgLandskode.fnr(),
                                VIKAFOSSEN,
                                brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                            ),
                            Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true)),
                            brukerMedAdressebeskyttelseOgLandskode.gtKontor as KontorForGtFantLand
                        )
                    )
                )
            }

            it("skal rute til vikafossen hvis bruker mangler gt, men har adressebeskyttelse") {
                gitt(brukerMedAdressebeskyttelseSomManglerGt).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedAdressebeskyttelseSomManglerGt)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = brukerMedAdressebeskyttelseSomManglerGt.gtVikafossen(),
                        aoKontorEndret = OppfolgingsPeriodeStartetSensitivKontorTilordning(
                            KontorTilordning(
                                brukerMedAdressebeskyttelseSomManglerGt.fnr(),
                                VIKAFOSSEN,
                                brukerMedAdressebeskyttelseSomManglerGt.oppfolgingsperiodeId()
                            ),
                            Sensitivitet(HarSkjerming(false), HarStrengtFortroligAdresse(true)),
                            brukerMedAdressebeskyttelseSomManglerGt.gtKontor as KontorForGtSuccess
                        )
                    )
                )
            }

            it("skal throwe hvis bruker har landskode som gt, men er skjermet") {
                gitt(skjermetBrukerMedLandskode).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(skjermetBrukerMedLandskode)
                ) shouldBe TilordningFeil("Feil ved tilordning av kontor: Vi håndterer ikke skjermede brukere uten geografisk tilknytning")
            }

            it("skal ikke sette AO kontor hvis bruker allerede har fått satt kontor pga. oppfolgingsperiode startet") {
                gitt(brukerMedTilordnetKontorForOppfolgingStartet).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(brukerMedTilordnetKontorForOppfolgingStartet)
                ) shouldBe TilordningSuccessIngenEndring
            }

            it("skal sette Arenakontor hvis det kommer med i startmelding") {
                val arenaKontor = KontorId("3311")
                gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(ungBrukerMedGodeMuligheter, arenaKontor)
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = ungBrukerMedGodeMuligheter.defaultGtKontorVedOppfolgStart(),
                        aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                            KontorTilordning(
                                ungBrukerMedGodeMuligheter.fnr(),
                                ungBrukerMedGodeMuligheter.gtKontor(),
                                ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                            ),
                            ingenSensitivitet
                        ),
                        arenaKontorEndret = ArenaKontorVedOppfolgingStart(
                            KontorTilordning(
                                ungBrukerMedGodeMuligheter.fnr(),
                                arenaKontor,
                                ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                            )
                        )
                    )
                )
            }
            it("skal bruke Arena-kontor fra oppfolgingsbruker-endret ved oppfolgingsperiodeStart") {
                val arenaKontorId = "ARENA1111"
                val tidligArenaKontor =
                    TidligArenaKontor(sistEndretDato = OffsetDateTime.now(), kontor = KontorId(arenaKontorId))
                val aoKontorTilordning = KontorTilordning(
                    ungBrukerMedGodeMuligheter.fnr(),
                    ungBrukerMedGodeMuligheter.gtKontor(),
                    ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                )
                val arenaKontorTilordning = KontorTilordning(
                    ungBrukerMedGodeMuligheter.fnr(),
                    KontorId(arenaKontorId),
                    ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                )
                gitt(ungBrukerMedGodeMuligheter).tilordneKontorAutomatisk(
                    oppfolgingsperiodeStartet(
                        bruker = ungBrukerMedGodeMuligheter,
                        tidligArenaKontor = tidligArenaKontor
                    )
                ) shouldBe TilordningSuccessKontorEndret(
                    KontorEndringer(
                        gtKontorEndret = ungBrukerMedGodeMuligheter.defaultGtKontorVedOppfolgStart(),
                        aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                            aoKontorTilordning,
                            ingenSensitivitet
                        ),
                        arenaKontorEndret = ArenaKontorFraOppfolgingsbrukerVedOppfolgingStart(
                            arenaKontorTilordning,
                            tidligArenaKontor.sistEndretDato
                        )
                    )
                )
            }
        }
    }

    describe("Endring i adressebeskyttelse") {
        it("skal bare endre GT kontor når bruker ikke har strengt fortrolig adresse") {
            gitt(ungBrukerMedGodeMuligheter)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(ungBrukerMedGodeMuligheter.fnr(), Gradering.FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        HarStrengtFortroligAdresse(false),
                        ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                    )
                )
            )
        }

        it("skal sette AO og GT kontor når bruker får strengt fortrolig adresse") {
            gitt(adressebeskyttetBruker)
                .handterEndringForAdressebeskyttelse(
                    /* Setter med vilje en "utdatert" verdi (UGRADERT) i kafka meldingen. Vi må hente ferske
                    data på nytt fra PDL når vi behandler endring i adressebeskyttelse */
                    AdressebeskyttelseEndret(adressebeskyttetBruker.fnr(), Gradering.UGRADERT)
                ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            adressebeskyttetBruker.fnr(),
                            adressebeskyttetBruker.gtKontor(),
                            adressebeskyttetBruker.oppfolgingsperiodeId()
                        ),
                        HarStrengtFortroligAdresse(true),
                        adressebeskyttetBruker.gtForBruker as GtForBrukerFunnet
                    ),
                    aoKontorEndret = AOKontorEndretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            adressebeskyttetBruker.fnr(),
                            adressebeskyttetBruker.gtKontor(),
                            adressebeskyttetBruker.oppfolgingsperiodeId()
                        )
                    )
                )
            )
        }

        it("skal sette AO og GT kontor når bruker får strengt fortrolig adresse også når bruker har landskode") {
            gitt(brukerMedAdressebeskyttelseOgLandskode)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerMedAdressebeskyttelseOgLandskode.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            brukerMedAdressebeskyttelseOgLandskode.fnr(),
                            VIKAFOSSEN,
                            brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                        ),
                        HarStrengtFortroligAdresse(true),
                        brukerMedAdressebeskyttelseOgLandskode.gtForBruker as GtForBrukerFunnet
                    ),
                    aoKontorEndret = AOKontorEndretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            brukerMedAdressebeskyttelseOgLandskode.fnr(),
                            VIKAFOSSEN,
                            brukerMedAdressebeskyttelseOgLandskode.oppfolgingsperiodeId()
                        )
                    )
                )
            )
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerIkkeUnderOppfolging.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(KontorEndringer())
        }

        it("skal sette ao-kotor og gt-kontor på brukere som mangler GT og har adressebeskyttelse") {
            gitt(brukerMedAdressebeskyttelseSomManglerGt)
                .handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerMedAdressebeskyttelseSomManglerGt.fnr(), Gradering.STRENGT_FORTROLIG)
                ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            brukerMedAdressebeskyttelseSomManglerGt.fnr(),
                            VIKAFOSSEN,
                            brukerMedAdressebeskyttelseSomManglerGt.oppfolgingsperiodeId()
                        ),
                        HarStrengtFortroligAdresse(true),
                        brukerMedAdressebeskyttelseSomManglerGt.gtForBruker as GtForBrukerIkkeFunnet
                    ),
                    aoKontorEndret = AOKontorEndretPgaAdressebeskyttelseEndret(
                        KontorTilordning(
                            brukerMedAdressebeskyttelseSomManglerGt.fnr(),
                            VIKAFOSSEN,
                            brukerMedAdressebeskyttelseSomManglerGt.oppfolgingsperiodeId()
                        )
                    )
                )
            )
        }
    }

    describe("Endring i skjermingstatus") {

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    ungBrukerMedGodeMuligheter.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe Result.success(
                EndringISkjermingResult(
                    KontorEndringer(
                        gtKontorEndret = GTKontorEndret.endretPgaSkjermingEndret(
                            KontorTilordning(
                                ungBrukerMedGodeMuligheter.fnr(),
                                ungBrukerMedGodeMuligheter.gtKontor(),
                                ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                            ),
                            HarSkjerming(true),
                            ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                        ),
                        aoKontorEndret = AOKontorEndretPgaSkjermingEndret(
                            KontorTilordning(
                                ungBrukerMedGodeMuligheter.fnr(),
                                ungBrukerMedGodeMuligheter.gtKontor(),
                                ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                            )
                        ),
                    )
                )
            )
        }

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet også når bruker har landskode") {
            gitt(brukerMedLandskodeOgFallback).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    brukerMedLandskodeOgFallback.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe Result.success(
                EndringISkjermingResult(
                    KontorEndringer(
                        gtKontorEndret = GTKontorEndret.endretPgaSkjermingEndret(
                            KontorTilordning(
                                brukerMedLandskodeOgFallback.fnr(),
                                brukerMedLandskodeOgFallback.gtKontor(),
                                brukerMedLandskodeOgFallback.oppfolgingsperiodeId()
                            ),
                            HarSkjerming(true),
                            brukerMedLandskodeOgFallback.gtForBruker as GtLandForBrukerFunnet
                        ),
                        aoKontorEndret = AOKontorEndretPgaSkjermingEndret(
                            KontorTilordning(
                                brukerMedLandskodeOgFallback.fnr(),
                                brukerMedLandskodeOgFallback.gtKontor(),
                                brukerMedLandskodeOgFallback.oppfolgingsperiodeId()
                            )
                        ),
                    )
                )
            )
        }

        it("skal sette AO og GT kontor til skjermet kontor når bruker blir skjermet også når bruker har landskode men ikke arbeidsfordeling fallback") {
            gitt(brukerMedLandskodeUtenFallback).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    brukerMedLandskodeUtenFallback.fnr(),
                    HarSkjerming(true)
                )
            ) shouldBe Result.success(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskodeUtenFallback.fnr(),
                            GT_VAR_LAND_FALLBACK,
                            brukerMedLandskodeUtenFallback.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(true),
                        brukerMedLandskodeUtenFallback.gtForBruker as GtLandForBrukerFunnet
                    ),
                    aoKontorEndret = AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskodeUtenFallback.fnr(),
                            GT_VAR_LAND_FALLBACK,
                            brukerMedLandskodeUtenFallback.oppfolgingsperiodeId()
                        )
                    ),
                )
            ) shouldBe Result.success(EndringISkjermingResult(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskodeUtenFallback.fnr(),
                            GT_VAR_LAND_FALLBACK,
                            brukerMedLandskodeUtenFallback.oppfolgingsperiodeId()
                        ),
                        HarSkjerming(true),
                        brukerMedLandskodeUtenFallback.gtForBruker as GtLandForBrukerFunnet
                    ),
                    aoKontorEndret = AOKontorEndretPgaSkjermingEndret(
                        KontorTilordning(
                            brukerMedLandskodeUtenFallback.fnr(),
                            GT_VAR_LAND_FALLBACK,
                            brukerMedLandskodeUtenFallback.oppfolgingsperiodeId()
                        )
                    ),
                )
            )
            )
        }

        it("skal bare sette GT kontor når bruker blir av-skjermet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringISkjermingStatus(
                SkjermetStatusEndret(
                    ungBrukerMedGodeMuligheter.fnr(),
                    HarSkjerming(false)
                )
            ) shouldBe Result.success(
                EndringISkjermingResult(
                    KontorEndringer(
                        gtKontorEndret = GTKontorEndret.endretPgaSkjermingEndret(
                            KontorTilordning(
                                ungBrukerMedGodeMuligheter.fnr(),
                                ungBrukerMedGodeMuligheter.gtKontor(),
                                ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                            ),
                            HarSkjerming(false),
                            ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                        )
                    )
                )
            )
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging).handterEndringISkjermingStatus(
                SkjermetStatusEndret(brukerIkkeUnderOppfolging.fnr(), HarSkjerming(true))
            ) shouldBe Result.success(EndringISkjermingResult(KontorEndringer()))
        }

        it("skal sette hardkodet-fallback kontor (navit) på ao-kontor og gt-kontor hvis gt ikke finnes og fallback til arbeidsforedeling heller ikke finner kontor og skjerming er true") {
            gitt(brukerSomManglerGt).handterEndringISkjermingStatus(
                SkjermetStatusEndret(brukerSomManglerGt.fnr(), HarSkjerming(true))
            ) shouldBe Result.success(
                EndringISkjermingResult(
                    KontorEndringer(
                        gtKontorEndret = GTKontorEndret.endretPgaSkjermingEndret(
                            KontorTilordning(
                                brukerSomManglerGt.fnr(),
                                INGEN_GT_KONTOR_FALLBACK,
                                brukerSomManglerGt.oppfolgingsperiodeId()
                            ),
                            HarSkjerming(true),
                            brukerSomManglerGt.gtForBruker as GtForBrukerIkkeFunnet
                        ),
                        aoKontorEndret = AOKontorEndretPgaSkjermingEndret(
                            KontorTilordning(
                                brukerSomManglerGt.fnr(),
                                INGEN_GT_KONTOR_FALLBACK,
                                brukerSomManglerGt.oppfolgingsperiodeId()
                            )
                        ),
                    )
                )
            )
        }
    }

    describe("Endring i bostedsadresse") {
        it("skal bare sette gt kontor ved adresseendring for bruker uten sensitivitet") {
            gitt(ungBrukerMedGodeMuligheter).handterEndringForBostedsadresse(
                BostedsadresseEndret(ungBrukerMedGodeMuligheter.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(
                            ungBrukerMedGodeMuligheter.fnr(),
                            ungBrukerMedGodeMuligheter.gtKontor(),
                            ungBrukerMedGodeMuligheter.oppfolgingsperiodeId()
                        ),
                        ungBrukerMedGodeMuligheter.gtForBruker as GtForBrukerFunnet
                    )
                )
            )
        }

        it("skal bare sette gt kontor ved adresseendring for bruker med strengt fortrolig adresse") {
            gitt(adressebeskyttetBruker).handterEndringForBostedsadresse(
                BostedsadresseEndret(adressebeskyttetBruker.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(
                            adressebeskyttetBruker.fnr(),
                            adressebeskyttetBruker.gtKontor(),
                            adressebeskyttetBruker.oppfolgingsperiodeId()
                        ),
                        adressebeskyttetBruker.gtForBruker as GtForBrukerFunnet
                    )
                )
            )
        }

        it("skal bare sette gt kontor ved adresseendring for bruker med skjerming") {
            gitt(skjermetBruker).handterEndringForBostedsadresse(
                BostedsadresseEndret(skjermetBruker.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(
                            skjermetBruker.fnr(),
                            skjermetBruker.gtKontor(),
                            skjermetBruker.oppfolgingsperiodeId()
                        ),
                        skjermetBruker.gtForBruker as GtForBrukerFunnet
                    )
                )
            )
        }

        it("skal synce gt kontor med norg for brukere med gt-landskode (med arbeidsfordeling fallback)") {
            gitt(brukerMedLandskodeOgFallback).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerMedLandskodeOgFallback.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(
                            brukerMedLandskodeOgFallback.fnr(),
                            brukerMedLandskodeOgFallback.gtKontor(),
                            brukerMedLandskodeOgFallback.oppfolgingsperiodeId()
                        ),
                        brukerMedLandskodeOgFallback.gtForBruker as GtForBrukerFunnet
                    )
                )
            )
        }

        it("skal synce gt kontor med norg for brukere med gt-landskode uten arbeidsfordeling fallback") {
            gitt(brukerMedLandskodeUtenFallback).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerMedLandskodeUtenFallback.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(
                            brukerMedLandskodeUtenFallback.fnr(),
                            GT_VAR_LAND_FALLBACK,
                            brukerMedLandskodeUtenFallback.oppfolgingsperiodeId()
                        ),
                        brukerMedLandskodeUtenFallback.gtForBruker as GtForBrukerFunnet
                    )
                )
            )
        }

        it("skal ikke behandle brukere som ikke er under oppfølging") {
            gitt(brukerIkkeUnderOppfolging).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerIkkeUnderOppfolging.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(KontorEndringer())
        }

        it("skal sette hardkodet-fallback kontor hvis gt ikke finner og fallback til arbeidsforedeling også feiler") {
            gitt(brukerSomManglerGt).handterEndringForBostedsadresse(
                BostedsadresseEndret(brukerSomManglerGt.fnr())
            ) shouldBe HåndterPersondataEndretSuccess(
                KontorEndringer(
                    gtKontorEndret = GTKontorEndret.endretPgaBostedsadresseEndret(
                        KontorTilordning(
                            brukerSomManglerGt.fnr(),
                            INGEN_GT_KONTOR_FALLBACK,
                            brukerSomManglerGt.oppfolgingsperiodeId()
                        ),
                        brukerSomManglerGt.gtForBruker as GtForBrukerIkkeFunnet
                    )
                )
            )
        }
    }

    describe("Feilhåndtering") {
        val fnr = Fnr("12345678901", UKJENT)
        describe("tilordneKontorAutomatisk") {
            feilendeBrukere.map { bruker ->
                val starttidspunkt = when (bruker.oppfolgingsPeriodeResult) {
                    is AktivOppfolgingsperiode -> bruker.oppfolgingsPeriodeResult.startDato
                    else -> OffsetDateTime.now()
                }
                gitt(bruker).tilordneKontorAutomatisk(oppfolgingsperiodeStartet(fnr = fnr, startDato = starttidspunkt))
            } shouldBe listOf(
                TilordningFeil("Feil ved oppslag på oppfolgingsperiode: feil i fnr"),
                TilordningFeil("Kunne ikke hente alder: feil i alder"),
                TilordningSuccessKontorEndret(
                    KontorEndringer(
                        aoKontorEndret = OppfolgingsPeriodeStartetLokalKontorTilordning(
                            KontorTilordning(
                                brukerMedFeilendeProfilering.fnr(),
                                brukerMedFeilendeProfilering.gtKontor(),
                                brukerMedFeilendeProfilering.oppfolgingsperiodeId()
                            ),
                            ingenSensitivitet
                        ),
                        gtKontorEndret = brukerMedFeilendeProfilering.defaultGtKontorVedOppfolgStart()
                    )
                ),
                TilordningFeil(message = "Fant ikke profilering, men skal forsøke på nytt. Ble registrert for 0 sekunder siden"),
                TilordningFeil("Kunne ikke hente skjerming ved kontortilordning: feil i skjerming"),
                TilordningFeil("Kunne ikke hente adressebeskyttelse ved kontortilordning: feil i adressebeskyttelse"),
                TilordningFeil("Feil ved henting av gt-kontor: Feil i gt-kontor oppslag"),
                TilordningFeil("Feil ved oppslag på oppfolgingsperiode: Incorrect resultsize exception"),
            )
        }

        describe("handterEndringISkjermingStatus") {
            it("handterEndringISkjermingStatus - feil ved henting av adressebeskyttelse skal returnere feil") {
                gitt(brukerMedFeilendeAdressebeskyttelse).handterEndringISkjermingStatus(
                    SkjermetStatusEndret(brukerMedFeilendeAdressebeskyttelse.fnr(), HarSkjerming(true))
                ).isFailure shouldBe true
            }
            it("handterEndringISkjermingStatus - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringISkjermingStatus(
                    SkjermetStatusEndret(brukerMedFeilendeKontorForGt.fnr(), HarSkjerming(true))
                ).isFailure shouldBe true
            }
        }

        describe("handterEndringForAdressebeskyttelse") {
            it("handterEndringForAdressebeskyttelse - feil ved henting av skjerming skal returnere feil") {
                gitt(brukerMedFeilendeSkjerming).handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerMedFeilendeSkjerming.fnr(), Gradering.STRENGT_FORTROLIG)
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForAdressebeskyttelse - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringForAdressebeskyttelse(
                    AdressebeskyttelseEndret(brukerMedFeilendeKontorForGt.fnr(), Gradering.STRENGT_FORTROLIG)
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
        }

        describe("handterEndringForBostedsadresse") {
            it("handterEndringForBostedsadresse - feil ved henting av adressebeskyttelse skal returnere feil") {
                gitt(brukerMedFeilendeAdressebeskyttelse).handterEndringForBostedsadresse(
                    BostedsadresseEndret(brukerMedFeilendeAdressebeskyttelse.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForBostedsadresse - feil ved henting av skjerming skal returnere feil") {
                gitt(brukerMedFeilendeSkjerming).handterEndringForBostedsadresse(
                    BostedsadresseEndret(brukerMedFeilendeSkjerming.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
            it("handterEndringForAdressebeskyttelse - feil ved henting av gt skal returnere feil") {
                gitt(brukerMedFeilendeKontorForGt).handterEndringForBostedsadresse(
                    BostedsadresseEndret(brukerMedFeilendeKontorForGt.fnr())
                ).shouldBeInstanceOf<HåndterPersondataEndretFail>()
            }
        }
    }
})

fun oppfolgingsperiodeStartet(
    bruker: Bruker,
    arenaKontor: KontorId? = null,
    tidligArenaKontor: TidligArenaKontor? = null
) =
    oppfolgingsperiodeStartet(
        bruker.fnr(),
        arenaKontor,
        tidligArenaKontor,
        (bruker.oppfolgingsPeriodeResult as AktivOppfolgingsperiode).startDato
    )

fun oppfolgingsperiodeStartet(
    fnr: IdentSomKanLagres,
    arenaKontor: KontorId? = null,
    tidligArenaKontor: TidligArenaKontor? = null,
    startDato: OffsetDateTime = OffsetDateTime.now(),
): OppfolgingsperiodeStartet {
    return OppfolgingsperiodeStartet(
        fnr = fnr,
        startDato = startDato.toZonedDateTime(),
        periodeId = OppfolgingsperiodeId(UUID.randomUUID()),
        startetArenaKontor = arenaKontor,
        arenaKontorFraOppfolgingsbrukerTopic = tidligArenaKontor,
        erArbeidssøkerRegistrering = true
    )
}

fun gitt(bruker: Bruker): AutomatiskKontorRutingService {
    return AutomatiskKontorRutingService(
        {},
        { _, strengtFortroligAdresse, skjerming -> bruker.gtKontor },
        { bruker.alder },
        { bruker.profilering },
        { bruker.skjerming },
        { bruker.strengtFortroligAdresse },
        { bruker.oppfolgingsPeriodeResult },
        { _, _ -> bruker.harTilordnetKontorForOppfolgingsperiodeStartet }
    )
}

fun defaultOppfolgingsperiodeOppslagResult(fnr: IdentResult): OppfolgingsperiodeOppslagResult {
    return when (fnr) {
        is IdentFunnet -> return AktivOppfolgingsperiode(
            fnr.ident,
            OppfolgingsperiodeId(UUID.randomUUID()),
            OffsetDateTime.now()
        )

        is IdentIkkeFunnet -> OppfolgingperiodeOppslagFeil(fnr.message)
        is IdentOppslagFeil -> OppfolgingperiodeOppslagFeil(fnr.message)
    }
}

data class Bruker(
    val fnr: IdentResult,
    val alder: AlderResult,
    val profilering: HentProfileringsResultat,
    val gtKontor: KontorForGtResultat,
    val gtForBruker: GtForBrukerResult,
    val skjerming: SkjermingResult,
    val strengtFortroligAdresse: HarStrengtFortroligAdresseResult,
    val oppfolgingsPeriodeResult: OppfolgingsperiodeOppslagResult = defaultOppfolgingsperiodeOppslagResult(fnr),
    val harTilordnetKontorForOppfolgingsperiodeStartet: Outcome<Boolean> = Outcome.Success(false)
) {
    fun fnr(): IdentSomKanLagres {
        if (fnr is IdentFunnet) {
            return fnr.ident
        }
        throw IllegalStateException("Fnr is ${this.fnr}")
    }

    fun gtKontor(): KontorId {
        if (gtKontor is KontorForGtNrFantKontor) {
            return gtKontor.kontorId
        }
        throw IllegalStateException("Prøvde hente gtKontor fra testbruker men bruker var ikke konfigurert med et gt-kontor, men hadde istedet: ${this.gtKontor}")
    }

    fun defaultGtKontorVedOppfolgStart(): GTKontorEndret {
        return GTKontorEndret.syncVedStartOppfolging(
            tilordning = KontorTilordning(
                this.fnr(),
                this.gtKontor(),
                this.oppfolgingsperiodeId()
            ),
            gt = this.gtForBruker as GtForBrukerSuccess
        )
    }

    fun gtVikafossen(): GTKontorEndret {
        return GTKontorEndret.syncVedStartOppfolging(
            tilordning = KontorTilordning(
                this.fnr(),
                VIKAFOSSEN,
                this.oppfolgingsperiodeId()
            ),
            gt = this.gtForBruker as GtForBrukerSuccess
        )
    }

    fun oppfolgingsperiodeId(): OppfolgingsperiodeId {
        if (oppfolgingsPeriodeResult is AktivOppfolgingsperiode) {
            return oppfolgingsPeriodeResult.periodeId
        }
        throw IllegalStateException("OppfolgingsperiodeResult is ${this.oppfolgingsPeriodeResult}")
    }
}

val ungBrukerMedGodeMuligheter = Bruker(
    IdentFunnet(Fnr("12345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedArenaKontorIStartOppfolging = Bruker(
    IdentFunnet(Fnr("12345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val eldreBrukerMedGodeMuligheter = Bruker(
    IdentFunnet(Fnr("22345678901", AKTIV)),
    AlderFunnet(31),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val ungBrukerMedbehovForVeiledning = Bruker(
    IdentFunnet(Fnr("32345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_BEHOV_FOR_VEILEDNING),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerSomManglerGt = Bruker(
    IdentFunnet(Fnr("42345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFinnesIkke(
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GtForBrukerIkkeFunnet("Denne brukeren mangler gt")
    ),
    GtForBrukerIkkeFunnet("Denne brukeren mangler gt"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val skjermetBruker = Bruker(
    IdentFunnet(Fnr("52345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(true),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val adressebeskyttetBruker = Bruker(
    IdentFunnet(Fnr("62345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(true),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val skjermetBrukerMedLandskode = Bruker(
    IdentFunnet(Fnr("72345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(true), HarStrengtFortroligAdresse(false)),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(true)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedLandskodeOgFallback = Bruker(
    IdentFunnet(Fnr("82345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantFallbackKontorForManglendeGt(
        KontorId("3443"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN"))
    ),
    GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedLandskodeUtenFallback = Bruker(
    IdentFunnet(Fnr("82345678991", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(false)),
    GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedAdressebeskyttelseOgLandskode = Bruker(
    IdentFunnet(Fnr("92345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFantLand(GeografiskTilknytningLand("JPN"), HarSkjerming(false), HarStrengtFortroligAdresse(true)),
    GtLandForBrukerFunnet(GeografiskTilknytningLand("JPN")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val brukerMedAdressebeskyttelseSomManglerGt = Bruker(
    IdentFunnet(Fnr("11345678901", AKTIV)),
    AlderFunnet(31), // Hadde blitt rutet til NOE hvis ikke bruker hadde adressebeskytelse
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFinnesIkke(
        HarSkjerming(false),
        HarStrengtFortroligAdresse(true),
        GtForBrukerIkkeFunnet("GT ikke funnet")
    ),
    GtForBrukerIkkeFunnet("GT ikke funnet"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(true))
)
val brukerIkkeUnderOppfolging = Bruker(
    IdentFunnet(Fnr("93345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("4141"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
    NotUnderOppfolging
)
val brukerMedTilordnetKontorForOppfolgingStartet = Bruker(
    IdentFunnet(Fnr("94345678901", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("4141"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
    AktivOppfolgingsperiode(Fnr("94345678901", UKJENT), OppfolgingsperiodeId(UUID.randomUUID()), OffsetDateTime.now()),
    Outcome.Success(true)
)

/* Brukere med feil */
val brukerMedFeilendeFnr = Bruker(
    IdentIkkeFunnet("feil i fnr"),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
)
val brukerMedFeilendeAlder = Bruker(
    IdentFunnet(Fnr("11111111111", AKTIV)),
    AlderIkkeFunnet("feil i alder"),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerUtenProfileringEnnå = Bruker(
    IdentFunnet(Fnr("11111111111", AKTIV)),
    AlderFunnet(20),
    ProfileringIkkeFunnet("profilering ikke funnet"),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeProfilering = brukerUtenProfileringEnnå.copy(
    oppfolgingsPeriodeResult = (brukerUtenProfileringEnnå.oppfolgingsPeriodeResult as AktivOppfolgingsperiode).copy(
        startDato = OffsetDateTime.now().minusSeconds(11)
    )
)
val brukerMedFeilendeSkjerming = Bruker(
    IdentFunnet(Fnr("11111111111", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingIkkeFunnet("feil i skjerming"),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeAdressebeskyttelse = Bruker(
    IdentFunnet(Fnr("11111111111", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtNrFantDefaultKontor(
        KontorId("1234"),
        HarSkjerming(false),
        HarStrengtFortroligAdresse(false),
        GeografiskTilknytningBydelNr("1111")
    ),
    GtNummerForBrukerFunnet(GeografiskTilknytningBydelNr("1111")),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseIkkeFunnet("feil i adressebeskyttelse")
)
val brukerMedFeilendeKontorForGt = Bruker(
    IdentFunnet(Fnr("11111111111", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFeil("Feil i gt-kontor oppslag"),
    GtForBrukerOppslagFeil("Testbruker som har feilende gt"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false))
)
val brukerMedFeilendeOppfolgingperiodeOppslagFeil = Bruker(
    IdentFunnet(Fnr("11111111111", AKTIV)),
    AlderFunnet(20),
    ProfileringFunnet(ProfileringsResultat.ANTATT_GODE_MULIGHETER),
    KontorForGtFeil("Feil i gt-kontor oppslag"),
    GtForBrukerOppslagFeil("Testbruker som har feilende gt"),
    SkjermingFunnet(HarSkjerming(false)),
    HarStrengtFortroligAdresseFunnet(HarStrengtFortroligAdresse(false)),
    OppfolgingperiodeOppslagFeil("Incorrect resultsize exception")
)
val feilendeBrukere = listOf(
    brukerMedFeilendeFnr,
    brukerMedFeilendeAlder,
    brukerMedFeilendeProfilering,
    brukerUtenProfileringEnnå,
    brukerMedFeilendeSkjerming,
    brukerMedFeilendeAdressebeskyttelse,
    brukerMedFeilendeKontorForGt,
    brukerMedFeilendeOppfolgingperiodeOppslagFeil
)

val ingenSensitivitet = Sensitivitet(
    HarSkjerming(false),
    HarStrengtFortroligAdresse(false)
)
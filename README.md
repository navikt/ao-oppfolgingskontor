# ao-oppfolgingskontor
Oppfølgingskontor for Arbeidsrettet Oppfølging

## Inbound data
| Endepunkt                              | Beskrivelse                                  |      
|----------------------------------------|----------------------------------------------|
| `POST /api/kontor`                     | Sett arbeidsoppfolgings-kontor for en bruker |
| `pto.endring-paa-oppfolgingsbruker-v2` | Alle oppfolgingskontor fra arena             |
| `Liste over Kontor fra Norg`           | Henter liste over kontor fra Norg2           |

## Ubiquitous language
- Arbeidsoppfolgingkontor kontor - Alltid satt manuelt av veileder (foreløpig)
- Arena kontor - satt enten manuelt eller automatisk i Arena
- Geografisk-tilknyttet kontor
  - Gitt en geografisk tilknytning (GT), sjekk i Norg2 hvilket kontor som er tilknyttet den GT-en

## Business rules
- Arbeidsoppfølgings-kontor > Arena-kontor > GT-kontor. 
- kontorForBruker gir ut kontoret med høyest prioritet.

## Outbound data
| Endepunkt                    | Beskrivelse                                                          |      
|------------------------------|----------------------------------------------------------------------|
| `/graphql (alleKontor)`      | Liste over alle kontor som kan velges når man skal sette kontor      |
| `/graphql (kontorForBruker)` | Nåværende kontor for en bruker                                       |
| `/graphql (kontorHistorikk)` | All historikk på kontor for en bruker                                |
| `topic for kontorendringer`  | Alle endringer? Bare "overstyringer"? Bare Arena + arbeidsoppfølging? |


## Tech
- Kotlin
- [Ktor (v3)](https://ktor.io/docs/welcome.html)
- [graphql-kotlin (expedia)](https://opensource.expediagroup.com/graphql-kotlin/docs/)
- [Kafka Streams](https://kafka.apache.org/documentation/streams/)
- [Exposed](https://www.jetbrains.com/help/exposed/home.html)

[Link til GraphiQl](https://ao-oppfolgingskontor.intern.dev.nav.no/graphiql)
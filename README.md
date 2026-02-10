# ao-oppfolgingskontor
Oppfølgingskontor for Arbeidsrettet Oppfølging

<img src="[https://github.com/favicon.ico](https://github.com/user-attachments/assets/26fbe963-22c7-44e7-a14d-397f8626abb0)" width="48" height="48" >

<img width="911" height="739" alt="image" src="https://github.com/user-attachments/assets/a96969f1-06d3-4ea0-83b4-be9f962c4a47" />


## Inbound data
| Endepunkt                              | Beskrivelse                                                       |      
|----------------------------------------|-------------------------------------------------------------------|
| `POST /api/kontor`                     | Setter arbeidsoppfolgings-kontor for en bruker (Kontortilordning) |
| `pto.endring-paa-oppfolgingsbruker-v2` | Alle oppfolgingskontor fra arena                                  |
| `Liste over Kontor fra Norg`           | Henter liste over kontor fra Norg2                                |

## Ubiquitous language
- **Arbeidsoppfolging-kontor**: Alltid satt manuelt av veileder (foreløpig)
- **Arena-kontor**: satt enten manuelt eller automatisk i Arena
- **Geografisk-tilknyttet-kontor**: Kontor som tilhører brukers folkeregistrerte adresse
  - Gitt en geografisk tilknytning (GT), sjekk i Norg2 hvilket kontor som er tilknyttet den GT-en
- **Kontortilhørighet**: hvilket kontor en bruker tilhører
  - Kan være arbeidsoppfølging-kontor, arena-kontor eller GT-kontor
  - Kan være flere kontortilhørigheter samtidig men kun én av hver type
  - Har prioriteringsrekkefølge: arbeidsoppfølging-kontor (viktigst) > arena-kontor > GT-kontor
- **Kontortilordning**: handlingen å sette kontoret til en bruker, kan være manuelt eller automatisk. Alle tre kontortyper kan settes. Inneholder hvem som utførte handlingen (system eller veileder-ident) og tidspunkt for når kontoret ble satt.
  - AOKontorEndret
    - KontorSattAvVeileder
    - OppfolgingsPeriodeStartetLokalKontorTilordning
    - OppfolgingsperiodeStartetNoeTilordning
  - ArenaKontorEndret
    - EndringPaaOppfolgingsBrukerFraArena
  - GTKontorEndret
    - Ingen foreløpig :(

## Business rules
- Arbeidsoppfølgings-kontor > Arena-kontor > GT-kontor. 
- kontorForBruker gir ut kontoret med høyest prioritet.

## Outbound data
| Endepunkt                    | Beskrivelse                                                                                                                 |      
|------------------------------|-----------------------------------------------------------------------------------------------------------------------------|
| `/graphql (alleKontor)`      | Liste over alle kontor som kan velges når man skal sette kontor                                                             |
| `/graphql (kontorForBruker)` | Nåværende, høyest prioriterte **Kontortilhørighet**. Returnerer ett en tilhørighet men kontoret kan være av alle tre typene |
| `/graphql (kontorHistorikk)` | Alle historiske **Kontortilhørighet**-er                                                                                    |
| `topic for kontorendringer`  | Alle endringer? Bare "overstyringer"? Bare Arena + arbeidsoppfølging?                                                       |

## Automatisk kontortilordning - forklaring

### Beslutningsflyt for automatisk tilordning

```mermaid
flowchart TD
    Start([Oppfølgingsperiode starter]) --> HentData[Hent brukerdata:<br/>- Alder<br/>- Geografisk tilknytning GT<br/>- Adressebeskyttelse<br/>- Skjerming<br/>- Profilering kun ved arbeidssøkerregistrering]
    
    HentData --> SjekkAlleredeTilordnet{Har allerede<br/>AO-kontor for<br/>denne perioden?}
    SjekkAlleredeTilordnet -->|Ja| IngenEndring([Ingen endring])
    SjekkAlleredeTilordnet -->|Nei| SjekkProfilering{Er arbeidssøker-<br/>registrering OG<br/>profilering mangler?}
    
    SjekkProfilering -->|Ja, og registrert<br/>for < 30 min siden| Retry([Retry senere:<br/>Venter på profilering])
    SjekkProfilering -->|Nei eller<br/>registrert for lenge siden| SjekkOverstyring{Har manuell<br/>kontorOverstyring?}
    
    SjekkOverstyring -->|Ja| SjekkSensitivForOverstyring{Er brukeren sensitiv?}
    SjekkSensitivForOverstyring -->|Nei| TilManuelt[Tilordne til<br/>manuelt valgt kontor]
    SjekkSensitivForOverstyring -->|Ja| IgnorerOverstyring[Ignorer overstyring<br/>pga sikkerhet]
    
    SjekkOverstyring -->|Nei| SjekkSensitiv{Er brukeren sensitiv?<br/>Skjermet eller<br/>strengt fortrolig adresse}
    IgnorerOverstyring --> SjekkSensitiv
    
    SjekkSensitiv -->|Ja| HåndterSensitiv[Håndter sensitiv bruker]
    SjekkSensitiv -->|Nei| SjekkNOE{Skal til NOE?<br/>Alder 30-66 år OG<br/>gode muligheter}
    
    SjekkNOE -->|Ja| TilNOE[Tilordne til<br/>Nasjonal Oppfølgingsenhet NOE<br/>Kontor: 4154]
    SjekkNOE -->|Nei| SjekkGT{Har bruker<br/>geografisk tilknytning?}
    
    SjekkGT -->|Ja, GT-nummer| TilLokal[Tilordne til<br/>lokalkontor basert på GT]
    SjekkGT -->|Ja, men landskode| ForsøkArbeidsgiver[Forsøk arbeidsgiveradresse:<br/>Hent siste arbeidsforhold<br/>og arbeidsgivers adresse]
    SjekkGT -->|Nei, mangler GT| ForsøkArbeidsgiver
    
    ForsøkArbeidsgiver --> ArbeidsgiverOK{Fant kontor basert<br/>på arbeidsgiver?}
    ArbeidsgiverOK -->|Ja| TilArbeidsgiverKontor[Tilordne til kontor<br/>basert på arbeidsgivers<br/>kommunenummer]
    ArbeidsgiverOK -->|Nei| SjekkFallback{Finnes arbeidsfordeling<br/>bestmatch-kontor?}
    
    SjekkFallback -->|Ja| TilFallbackKontor[Tilordne til<br/>arbeidsfordeling-fallback]
    SjekkFallback -->|Nei| TilFallback[Tilordne til<br/>IT-fallback-kontor<br/>NAV IT<br/>Kontor: 2990]
    
    HåndterSensitiv --> SjekkStrengtFortrolig{Strengt fortrolig<br/>adresse?}
    SjekkStrengtFortrolig -->|Ja| TilVikafossen[Tilordne til<br/>Vikafossen<br/>Kontor: 2103]
    SjekkStrengtFortrolig -->|Nei, kun skjermet| SjekkGTSensitiv{Har bruker GT?}
    
    SjekkGTSensitiv -->|Ja| TilSensitivtLokal[Tilordne til<br/>GT-kontor som håndterer<br/>skjermede brukere]
    SjekkGTSensitiv -->|Nei| Error[Feil: Kan ikke håndtere<br/>skjermet uten GT]
    
    TilManuelt --> LagreKontor[Lagre både<br/>AO-kontor og GT-kontor]
    TilNOE --> LagreKontor
    TilLokal --> LagreKontor
    TilArbeidsgiverKontor --> LagreKontor
    TilFallbackKontor --> LagreKontor
    TilFallback --> LagreKontor
    TilVikafossen --> LagreKontor
    TilSensitivtLokal --> LagreKontor
    
    LagreKontor --> Slutt([Kontortilordning fullført])
```

### Kriterier for Nasjonal Oppfølgingsenhet (NOE)
Brukere blir rutet til NOE (kontor 4154) når **alle** følgende kriterier er oppfylt:
- Alder mellom 30 og 66 år (inkludert 30 og 66)
- Profilering viser "antatt gode muligheter"
- Brukeren er **ikke** skjermet
- Brukeren har **ikke** strengt fortrolig adresse
- Ingen manuell kontorOverstyring er satt


### Eksempler på kontortilordning

| Brukertype | Alder | GT | Skjermet | Adressebeskyttelse | Profilering | Manuell overstyring | Resultat |
|------------|-------|-----|----------|-------------------|-------------|---------------------|----------|
| Ung med gode muligheter | 25 | 0301 | Nei | Nei | Gode muligheter | Nei | **Lokalkontor** (Oslo) |
| Eldre med gode muligheter | 40 | 5001 | Nei | Nei | Gode muligheter | Nei | **NOE** (4154) |
| Voksen med behov for veiledning | 35 | 1103 | Nei | Nei | Behov for veiledning | Nei | **Lokalkontor** (Stavanger) |
| Strengt fortrolig | 30 | 0301 | Nei | Ja, kode 6 | Gode muligheter | Nei | **Vikafossen** (2103) |
| Skjermet bruker | 45 | 5001 | Ja | Nei | Gode muligheter | Nei | **Lokalkontor** med skjerming (Trondheim) |
| Mangler GT | 28 | - | Nei | Nei | Behov for veiledning | Nei | **IT-fallback** (NAV IT, 2990) |
| Bor i utlandet, arbeidsgiver-fallback | 32 | SWE | Nei | Nei | Gode muligheter | Nei | **Kontor basert på arbeidsgiver** (f.eks. 0219 hvis arbeidsgiver i Oslo) |
| Bor i utlandet med bestmatch-fallback | 32 | SWE | Nei | Nei | Gode muligheter | Nei | **Arbeidsfordeling-fallback** |
| Manuell overstyring akseptert | 40 | 5001 | Nei | Nei | Gode muligheter | Ja, kontor 7777 | **Kontor 7777** (overstyrer NOE) |
| Manuell overstyring ignorert | 45 | 5001 | Ja | Nei | Gode muligheter | Ja, kontor 9999 | **Lokalkontor** (Trondheim, ignorerer 9999 pga skjerming) |
| Strengt fortrolig med overstyring | 30 | 0301 | Nei | Ja, kode 6 | Gode muligheter | Ja, kontor 8888 | **Vikafossen** (2103, ignorerer 8888 pga sikkerhet) |

### Hendelser som trigger oppdatering av kontortilhørighet

```mermaid
sequenceDiagram
    participant Kafka
    participant System
    participant PDL
    participant Norg2
    participant Aareg
    participant Ereg
    participant Database
    
    Note over Kafka,Database: Scenario: Oppfølgingsperiode starter
    
    Kafka->>System: OppfolgingsperiodeStartet
    
    alt Har allerede AO-kontor for perioden
        System->>Database: Sjekk om kontor finnes
        Database-->>System: Ja, kontor finnes
        System->>System: Ingen endring
    else Ny tilordning nødvendig
        System->>PDL: Hent alder
        System->>PDL: Hent adressebeskyttelse
        System->>PDL: Hent skjerming
        
        alt Er arbeidssøkerregistrering
            System->>PDL: Hent arbeidsøkerprofilering
            PDL-->>System: Profilering (eller retry hvis < 30 min)
        end
        
        PDL-->>System: Brukerdata
        
        System->>PDL: Hent geografisk tilknytning (GT)
        PDL-->>System: GT (f.eks. 0301, SWE, eller ingen)
        
        alt GT er norsk kommunenr/bydelnr
            System->>Norg2: Finn kontor for GT
            Norg2-->>System: Kontor-ID (f.eks. 0219 for NAV Oslo)
        else GT er landskode eller mangler
            Note over System,Ereg: Forsøk arbeidsgiver-fallback først
            System->>Aareg: Hent arbeidsforhold
            Aareg-->>System: Arbeidsforhold (nyeste prioriteres)
            
            opt Har arbeidsforhold
                System->>Ereg: Hent arbeidsgiver adresse
                Ereg-->>System: Adresse med kommunenummer
                System->>Norg2: Finn kontor for arbeidsgivers kommunenr
                Norg2-->>System: Kontor-ID basert på arbeidsgiver
            end
            
            opt Arbeidsgiver-fallback feilet
                Note over System,Norg2: Forsøk arbeidsfordeling bestmatch
                System->>Norg2: Arbeidsfordeling bestmatch
                Norg2-->>System: Fallback kontor-ID
            end
        end
        
        System->>System: Evaluer regler for kontortilordning
        
        System->>Database: Lagre AO-kontor tilordning
        System->>Database: Lagre GT-kontor tilordning
        System->>Kafka: Publiser KontorEndret event
        
        Note over System: Både AO-kontor og GT-kontor lagres.<br/>GT-kontor brukes hvis ingen AO-kontor eller Arena-kontor finnes.
    end
```

## Built with
- Kotlin
- [Ktor (v3)](https://ktor.io/docs/welcome.html)
- [graphql-kotlin (expedia)](https://opensource.expediagroup.com/graphql-kotlin/docs/)
- [Kafka Streams](https://kafka.apache.org/documentation/streams/)
- [Exposed](https://www.jetbrains.com/help/exposed/home.html)

[Link til GraphiQl](https://ao-oppfolgingskontor.intern.dev.nav.no/graphiql)

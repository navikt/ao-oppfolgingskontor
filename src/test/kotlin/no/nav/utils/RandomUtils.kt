package no.nav.utils

import no.nav.db.AktorId
import no.nav.db.Dnr
import no.nav.db.Fnr
import no.nav.db.Ident
import no.nav.db.InternIdent
import kotlin.random.Random

fun randomFnr(identStatus: Ident.HistoriskStatus = Ident.HistoriskStatus.AKTIV): Fnr {
    val date = (1..31).random().toString().padStart(2, '0')
    val month = (1..12).random().toString().padStart(2, '0')
    val year = (1..99).random().toString().padStart(2, '0')
    val randomDigits = (1..5).map { (0..9).random() }.joinToString("")
    return Fnr("${date}${month}${year}${randomDigits}", identStatus)
}

fun randomDnr(identStatus: Ident.HistoriskStatus = Ident.HistoriskStatus.AKTIV): Dnr {
    val date = (1..31).random().toString().padStart(2, '0')
        .replaceFirstChar { firstDigit -> firstDigit.plus(4) }
    val month = (1..12).random().toString().padStart(2, '0')
    val year = (1..99).random().toString().padStart(2, '0')
    val randomDigits = (1..5).map { (0..9).random() }.joinToString("")
    return Dnr("${date}${month}${year}${randomDigits}", identStatus)
}

fun randomAktorId(identStatus: Ident.HistoriskStatus = Ident.HistoriskStatus.AKTIV): AktorId {
    val randomDigits = (1..13).map { (0..9).random() }.joinToString("")
    return AktorId("$randomDigits", identStatus)
}

fun randomTopicName(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..10)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}

fun randomInternIdent(): InternIdent = InternIdent(Random.nextLong(1000000))
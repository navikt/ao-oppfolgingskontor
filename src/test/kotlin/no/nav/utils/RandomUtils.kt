package no.nav.utils

import no.nav.db.Fnr
import no.nav.db.Ident
import kotlin.random.Random

fun randomFnr(identStatus: Ident.HistoriskStatus = Ident.HistoriskStatus.AKTIV): Fnr {
    val date = (1..31).random().toString().padStart(2, '0')
    val month = (1..12).random().toString().padStart(2, '0')
    val year = (1..99).random().toString().padStart(2, '0')
    val randomDigits = (1..5).map { (0..9).random() }.joinToString("")
    return Fnr("${date}${month}${year}${randomDigits}", identStatus)
}

fun randomTopicName(): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..10)
        .map { chars[Random.nextInt(chars.length)] }
        .joinToString("")
}
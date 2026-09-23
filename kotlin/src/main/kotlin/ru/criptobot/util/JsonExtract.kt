package ru.criptobot.util

fun extractJsonObject(text: String): String {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start == -1 || end <= start) throw IllegalArgumentException("No JSON object in response")
    return text.substring(start, end + 1)
}

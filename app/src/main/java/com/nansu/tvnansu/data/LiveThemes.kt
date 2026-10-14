package com.nansu.tvnansu.data

import com.nansu.tvnansu.data.db.CategoryEntity

/**
 * Groups the provider's live categories into a few themes ("ES | DEPORTES",
 * "SPORTS HD" and "DAZN" all land in Deportes). A category belongs to the first
 * theme it matches.
 */
enum class LiveTheme(val title: String, private val pattern: String) {
    SPORTS(
        "Deportes",
        "deport|sport|f[uú]tbol|futbol|soccer|\\bliga\\b|laliga|dazn|espn|eurosport|\\bnba\\b|\\bnfl\\b|\\bf1\\b|" +
            "motor|golf|tenis|tennis|bein|\\bufc\\b|boxeo|\\bppv\\b|evento|event",
    ),
    NEWS("Noticias", "noticia|news|informa|24 ?h"),
    KIDS("Infantil", "infantil|kids|niñ|child|cartoon|disney|nick|baby|junior|\\bclan\\b|boing|anim"),
    MOVIES("Cine y series", "cine|movie|film|pel[ií]cula|series|\\bhbo\\b|cinema|\\btnt\\b|\\baxn\\b|\\bamc\\b|paramount|\\bvod\\b"),
    DOCS("Documentales", "docu|discovery|national geo|nat ?geo|history|historia|natura|odisea|viaje|travel|ciencia|science"),
    MUSIC("Música", "m[uú]sica|music|\\bmtv\\b|hits|\\bradio\\b|\\bvh1\\b|concierto"),
    ;

    private val regex = Regex(pattern, RegexOption.IGNORE_CASE)

    fun matches(name: String) = regex.containsMatchIn(name)

    companion object {
        fun of(category: String): LiveTheme? = entries.firstOrNull { it.matches(category) }

        fun fromKey(key: String): LiveTheme? = entries.firstOrNull { it.name == key }
    }
}

data class ThemeSummary(
    val theme: LiveTheme,
    val categoryIds: List<String>,
    val channelCount: Int,
)

fun themeCategories(categories: List<CategoryEntity>): Map<LiveTheme, List<String>> =
    categories.mapNotNull { c -> LiveTheme.of(c.name)?.let { it to c.remoteId } }
        .groupBy({ it.first }, { it.second })

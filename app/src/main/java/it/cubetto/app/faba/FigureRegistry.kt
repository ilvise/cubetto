package it.cubetto.app.faba

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Una riga del registro TAGS.md di myfaba-hacks. */
data class KnownFigure(val id: String, val language: String, val name: String)

/**
 * Registro degli ID noti delle statuine FABA™. Usa la copia inclusa nell'app e
 * può aggiornarsi scaricando TAGS.md da GitHub.
 */
class FigureRegistry(private val context: Context) {
    private val cacheFile get() = File(context.filesDir, "TAGS.md")

    @Volatile
    var figures: List<KnownFigure> = emptyList()
        private set

    fun load() {
        val text = if (cacheFile.exists()) cacheFile.readText() else context.assets.open("TAGS.md").bufferedReader().readText()
        figures = parse(text).ifEmpty { parse(context.assets.open("TAGS.md").bufferedReader().readText()) }
    }

    /** Scarica l'ultima versione del registro. Ritorna il numero di statuine o null in caso di errore. */
    suspend fun update(): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(TAGS_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = parse(text)
            if (parsed.size < 50) error("Registro non valido")
            cacheFile.writeText(text)
            figures = parsed
            parsed.size
        }.getOrNull()
    }

    fun byId(id: String): List<KnownFigure> = figures.filter { it.id == id }

    companion object {
        const val TAGS_URL = "https://raw.githubusercontent.com/wansors/myfaba-hacks/main/TAGS.md"

        fun parse(markdown: String): List<KnownFigure> = markdown.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("|") }
            .mapNotNull { line ->
                val cells = line.trim('|').split('|').map { it.trim() }
                val id = cells.getOrNull(0) ?: return@mapNotNull null
                if (!FabaTag.isValidId(id)) return@mapNotNull null
                KnownFigure(id, cells.getOrElse(1) { "" }, cells.getOrElse(2) { "" })
            }.toList()

        /**
         * Lingue del registro che corrispondono alla lingua dell'app: le statuine in
         * queste lingue sono quelle che probabilmente hai in casa, quindi da evitare.
         */
        fun registryLanguagesFor(appLanguage: String): Set<String> = when (appLanguage) {
            "it" -> setOf("Italian", "Italian/English")
            "es" -> setOf("Spanish", "Latam")
            "ca" -> setOf("Catalan", "Spanish")
            "fr" -> setOf("French")
            "nl" -> setOf("Dutch")
            else -> setOf("Italian/English")
        }

        /** FABA•ME e simili sono venduti in più paesi: mai consigliarli. */
        const val NEUTRAL = "Neutral"
    }
}

/** Stato di un ID rispetto alla lingua in uso e alle statuine già create. */
enum class IdStatus { SUGGESTED, FREE, OWN_LANGUAGE, NEUTRAL, USED_BY_ME }

data class IdOption(val id: String, val figures: List<KnownFigure>, val status: IdStatus, val usedBy: String? = null)

object IdAdvisor {
    /** Tutti gli ID del registro più alcuni ID liberi, ordinati con i consigliati prima. */
    fun options(
        registry: List<KnownFigure>,
        appLanguage: String,
        usedIds: Map<String, String>,
        freeCount: Int = 12,
    ): List<IdOption> {
        val own = FigureRegistry.registryLanguagesFor(appLanguage)
        val known = registry.groupBy { it.id }.toSortedMap().map { (id, figs) ->
            val status = when {
                usedIds.containsKey(id) -> IdStatus.USED_BY_ME
                figs.any { it.language in own } -> IdStatus.OWN_LANGUAGE
                figs.any { it.language == FigureRegistry.NEUTRAL } -> IdStatus.NEUTRAL
                else -> IdStatus.SUGGESTED
            }
            IdOption(id, figs, status, usedIds[id])
        }
        val knownIds = registry.map { it.id }.toSet()
        // ID liberi: dopo il massimo noto sotto 9000, così restano lontani dalle serie ufficiali
        val start = (registry.mapNotNull { it.id.toIntOrNull() }.filter { it < 9000 }.maxOrNull() ?: 1000) + 1
        val free = generateSequence(start) { it + 1 }
            .takeWhile { it < 9999 }
            .map { "%04d".format(it) }
            .filter { it !in knownIds }
            .take(freeCount)
            .map { IdOption(it, emptyList(), if (usedIds.containsKey(it)) IdStatus.USED_BY_ME else IdStatus.FREE, usedIds[it]) }
            .toList()
        val order = listOf(IdStatus.SUGGESTED, IdStatus.FREE, IdStatus.USED_BY_ME, IdStatus.NEUTRAL, IdStatus.OWN_LANGUAGE)
        return (known + free).sortedWith(compareBy({ order.indexOf(it.status) }, { it.id }))
    }

    fun firstSuggestion(options: List<IdOption>): String? =
        options.firstOrNull { it.status == IdStatus.SUGGESTED || it.status == IdStatus.FREE }?.id
}

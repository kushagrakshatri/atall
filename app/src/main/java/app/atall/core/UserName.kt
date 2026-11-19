package app.atall.core

/**
 * The name agents and Juno call the user. The "Your name" setting wins; when it is blank, the first name on the
 * ChatGPT account. Pure, for tests.
 */
object UserName {
    const val MAX_CHARS = 40

    /** A typed name, tidied: spaces squashed, trimmed, at most [MAX_CHARS]. */
    fun normalize(typed: String): String = typed.replace(Regex("\\s+"), " ").trim().take(MAX_CHARS).trim()

    /** "Kushagra Kshatri" -> "Kushagra"; blank stays blank. */
    fun firstName(fullName: String?): String = normalize(fullName.orEmpty()).substringBefore(' ')

    /**
     * The name the owner goes by when a longer one is all the app has (his ChatGPT account, an old prefill). Kush asked
     * to be called "Kush", never "Kushagra" (2026-11-19). Typing any other name in Settings > Agents still wins.
     */
    private val PREFERRED = mapOf("kushagra" to "Kush", "kushagra kshatri" to "Kush")

    /** [name] as its owner prefers to be called. */
    fun preferred(name: String): String = normalize(name).let { n -> PREFERRED[n.lowercase()] ?: n }

    fun effective(setting: String, chatGptFirstName: String): String =
        preferred(setting).ifEmpty { preferred(chatGptFirstName) }

    /** What the Settings field shows: the saved name, else the ChatGPT first name as a prefill. */
    fun fieldValue(setting: String, chatGptFirstName: String): String = effective(setting, chatGptFirstName)
}

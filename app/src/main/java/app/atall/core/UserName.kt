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

    fun effective(setting: String, chatGptFirstName: String): String =
        normalize(setting).ifEmpty { normalize(chatGptFirstName) }

    /** What the Settings field shows: the saved name, else the ChatGPT first name as a prefill. */
    fun fieldValue(setting: String, chatGptFirstName: String): String = effective(setting, chatGptFirstName)
}

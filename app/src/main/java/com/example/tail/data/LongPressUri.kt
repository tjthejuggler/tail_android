package com.example.tail.data

import java.net.URLEncoder

/**
 * Pure URI helpers for the habit long-press "URL" action
 * (see [LONG_PRESS_URL] and LongPressActionSection in HabitGridScreen).
 *
 * The action accepts ANY pasted URI — plain https links as well as app deep
 * links like `obsidian://open?vault=…&file=…`, `spotify://…`, `tel:…`. Because
 * humans paste URIs that are not always correctly percent-encoded (spaces in
 * vault/file names are the classic case), every stored value is normalized
 * with [normalizeLongPressUri] before it is persisted and again before it is
 * launched. Normalization is idempotent: already-encoded URIs pass through
 * unchanged.
 */
private val SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

/** Characters allowed anywhere in a URI (RFC 3986 reserved + unreserved sets). */
private const val URI_SAFE_CHARS = "-._~:/?#[]@!$&'()*+,;="

/** True when the string starts with a URI scheme (`obsidian:`, `https:`, `tel:` …). */
fun hasUriScheme(s: String): Boolean = SCHEME_REGEX.containsMatchIn(s.trim())

/** Lowercase scheme of the string (`"obsidian"` for `obsidian://open?…`), or null. */
fun uriSchemeOf(s: String): String? =
    SCHEME_REGEX.find(s.trim())?.value?.dropLast(1)?.lowercase()

/**
 * Percent-encodes a value for use as a single URI query component
 * (e.g. an Obsidian vault or file path). `URLEncoder.encode` produces
 * `+` for spaces — valid in HTML forms but NOT in URI queries — so
 * spaces are re-encoded as `%20`.
 */
fun encodeUriComponent(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/**
 * Builds an `obsidian://open` deep link for a vault (and optionally a note
 * inside it). Both parts are percent-encoded, so vault/file names with
 * spaces, `&`, `#`, non-ASCII etc. survive the round trip:
 *
 *   buildObsidianOpenUri("My Vault", "Notes/My Note.md")
 *     → "obsidian://open?vault=My%20Vault&file=Notes%2FMy%20Note.md"
 *
 * A blank [relativeFilePath] opens just the vault.
 */
fun buildObsidianOpenUri(vaultName: String, relativeFilePath: String): String {
    val vault = encodeUriComponent(vaultName.trim())
    val file = relativeFilePath.trim()
    return if (file.isEmpty()) {
        "obsidian://open?vault=$vault"
    } else {
        "obsidian://open?vault=$vault&file=${encodeUriComponent(file)}"
    }
}

/**
 * Normalizes a pasted URI so Intent.ACTION_VIEW resolves it reliably:
 *
 *  1. trims surrounding whitespace (clipboards love trailing newlines);
 *  2. unwraps `<uri>` / `"uri"` wrapping copied from docs and chats;
 *  3. prefixes `https://` when no scheme is present (bare domains keep
 *     working exactly as before this helper existed);
 *  4. percent-encodes characters that are illegal in URIs (spaces, `"`,
 *     `<`, `>`, `\`, `^`, backtick, `{`, `|`, `}`, non-ASCII, stray `%`)
 *     while leaving RFC 3986 structure characters and existing `%XX`
 *     escapes untouched.
 *
 * Returns the input unchanged (except trimming) when it is already a
 * well-formed URI. Idempotent: normalize(normalize(x)) == normalize(x).
 */
fun normalizeLongPressUri(raw: String): String {
    var s = raw.trim()
    if (s.length >= 2 &&
        ((s.startsWith("<") && s.endsWith(">")) || (s.startsWith("\"") && s.endsWith("\"")))
    ) {
        s = s.substring(1, s.length - 1).trim()
    }
    if (s.isEmpty()) return s
    if (!hasUriScheme(s)) s = "https://$s"
    return encodeIllegalUriChars(s)
}

/**
 * Percent-encodes every character that is not legal somewhere in a URI,
 * preserving reserved structure characters (`:/?#…`) and valid `%XX`
 * escapes. Iterates by code point so non-ASCII (incl. emoji) encode
 * correctly.
 */
internal fun encodeIllegalUriChars(uri: String): String = buildString {
    var i = 0
    while (i < uri.length) {
        val c = uri[i]
        when {
            // Existing escape sequence — keep the '%' and let the hex
            // digits flow through (they are safe chars).
            c == '%' && isHexEscapeAt(uri, i) -> {
                append('%')
                i++
            }
            // ASCII-only: non-ASCII letters (è, ü, emoji…) must be encoded
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in URI_SAFE_CHARS -> {
                append(c)
                i++
            }
            else -> {
                val cp = uri.codePointAt(i)
                append(encodeUriComponent(String(Character.toChars(cp))))
                i += Character.charCount(cp)
            }
        }
    }
}

/** True when the `%` at [i] starts a valid `%XX` escape. */
private fun isHexEscapeAt(s: String, i: Int): Boolean {
    if (i + 2 >= s.length) return false
    return isHexDigit(s[i + 1]) && isHexDigit(s[i + 2])
}

private fun isHexDigit(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

package dev.kiln.server.api.screen

/**
 * Limits the screen DSL checks early, so mistakes fail in the plugin's own code. They mirror `ProtocolLimits` of the
 * wire protocol, which this API module does not depend on.
 */
internal object ScreenLimits {
    /** Maximum width/height of an element, in GUI pixels. */
    const val MAX_SIZE = 8_192

    /** Maximum height of the scrollable content of a scroll panel, in GUI pixels. */
    const val MAX_SCROLL_CONTENT_HEIGHT = 65_536

    /** Maximum `maxLength` of a text field. */
    const val MAX_TEXT_FIELD_LENGTH = 1_024

    /** Maximum length of an element id. */
    const val MAX_ID_LENGTH = 64
}

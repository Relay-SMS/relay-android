package com.filestech.sms.data.repository

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Feedback round 5 — garde anti-régression sur l'échappement LIKE utilisé par
 * la recherche de fil ([observeSearchIdsInConversation]). Toute modification
 * de l'algo doit garder ces cas verts, sous peine de faire matcher n'importe
 * quoi (ou rien) sur de vraies queries utilisateur contenant `%`, `_` ou `\`.
 */
class EscapeLikePatternTest {

    @Test fun `empty input returns empty`() {
        assertThat(escapeLikePattern("")).isEmpty()
    }

    @Test fun `plain text is unchanged`() {
        assertThat(escapeLikePattern("bonjour")).isEqualTo("bonjour")
    }

    @Test fun `percent is escaped`() {
        assertThat(escapeLikePattern("100%")).isEqualTo("100\\%")
    }

    @Test fun `underscore is escaped`() {
        assertThat(escapeLikePattern("a_b")).isEqualTo("a\\_b")
    }

    @Test fun `backslash is escaped first`() {
        assertThat(escapeLikePattern("a\\b")).isEqualTo("a\\\\b")
    }

    @Test fun `backslash before a wildcard is not double-escaped into a wildcard`() {
        // "\" then "%": the backslash itself is escaped, then the percent —
        // the result matches a literal backslash followed by a literal percent.
        assertThat(escapeLikePattern("\\%")).isEqualTo("\\\\\\%")
    }

    @Test fun `all three wildcards together`() {
        assertThat(escapeLikePattern("%_\\")).isEqualTo("\\%\\_\\\\")
    }
}

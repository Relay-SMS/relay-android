package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Category mutes — the wire shapes of `category_mutes` and
 * `set_category_muted`, plus the `category` echo on
 * `action_result`. The phone is the source of truth for the
 * muted set; the desktop replaces its own list wholesale.
 */
class SyncProtocolCategoryMutesTest {

    @Test fun `category_mutes encodes the full set under muted_categories`() {
        val encoded = SyncProtocol.encodeCategoryMutes(
            SyncProtocol.CategoryMutes(mutedCategories = listOf("otp", "promotional")),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("category_mutes")
        val muted = obj["muted_categories"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(muted).containsExactly("otp", "promotional").inOrder()
    }

    @Test fun `category_mutes encodes an empty set`() {
        val encoded = SyncProtocol.encodeCategoryMutes(
            SyncProtocol.CategoryMutes(mutedCategories = emptyList()),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("category_mutes")
        assertThat(obj["muted_categories"]!!.jsonArray).isEmpty()
    }

    @Test fun `set_category_muted parses`() {
        assertThat(SyncProtocol.parse("""{"type":"set_category_muted","category":"otp","muted":true}"""))
            .isEqualTo(
                SyncProtocol.Parsed.SetCategoryMuted(
                    SyncProtocol.SetCategoryMuted(category = "otp", muted = true),
                ),
            )
    }

    @Test fun `set_category_muted with an unknown category string still parses`() {
        // Lenient parsing: the category is just a string on the
        // wire — an unknown one parses fine and is refused at
        // apply time (`invalid_request`), never a crash.
        assertThat(SyncProtocol.parse("""{"type":"set_category_muted","category":"spam","muted":true}"""))
            .isEqualTo(
                SyncProtocol.Parsed.SetCategoryMuted(
                    SyncProtocol.SetCategoryMuted(category = "spam", muted = true),
                ),
            )
    }

    @Test fun `set_category_muted without muted is malformed`() {
        // Like `set_muted`'s `value`, the flag is required — a
        // missing flag is never a silent unmute.
        assertThat(SyncProtocol.parse("""{"type":"set_category_muted","category":"otp"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `set_category_muted without a category is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"set_category_muted","muted":true}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `action_result echoes the category for set_category_muted`() {
        val encoded = SyncProtocol.encodeActionResult(
            SyncProtocol.ActionResult(
                action = "set_category_muted",
                category = "otp",
                ok = true,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("action_result")
        assertThat(obj["action"]?.jsonPrimitive?.content).isEqualTo("set_category_muted")
        assertThat(obj["category"]?.jsonPrimitive?.content).isEqualTo("otp")
        assertThat(obj["ok"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(obj.containsKey("conversation_id")).isFalse()
    }

    @Test fun `action_result omits the category when none is set`() {
        // The new field is optional: every existing action frame
        // is byte-identical to before.
        val encoded = SyncProtocol.encodeActionResult(
            SyncProtocol.ActionResult(
                action = "set_pin",
                conversationId = "123",
                ok = true,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj.containsKey("category")).isFalse()
    }

    @Test fun `category_mutes from the client side is unknown`() {
        // The phone only SENDS `category_mutes` — an inbound one
        // is not a frame the server accepts.
        assertThat(SyncProtocol.parse("""{"type":"category_mutes","muted_categories":["otp"]}"""))
            .isEqualTo(SyncProtocol.Parsed.Unknown("category_mutes"))
    }
}

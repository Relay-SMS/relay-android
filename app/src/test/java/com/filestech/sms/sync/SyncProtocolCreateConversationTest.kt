package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Create conversation — the new-conversation wire types:
 * `create_conversation` parses (the desktop's request) and
 * `conversation_created` encodes (the phone's ack). The frame
 * shapes are the contract the desktop creates against, so the
 * field names and the required fields are pinned here.
 */
class SyncProtocolCreateConversationTest {

    @Test fun `create_conversation parses with addresses and a group name`() {
        val frame = """{"type":"create_conversation","addresses":["+15551234567","+15550001111"],""" +
            """"custom_name":"Family"}"""
        assertThat(SyncProtocol.parse(frame)).isEqualTo(
            SyncProtocol.Parsed.CreateConversation(
                SyncProtocol.CreateConversation(
                    addresses = listOf("+15551234567", "+15550001111"),
                    customName = "Family",
                ),
            ),
        )
    }

    @Test fun `create_conversation parses without a group name`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"create_conversation","addresses":["+15551234567"]}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.CreateConversation(
                SyncProtocol.CreateConversation(
                    addresses = listOf("+15551234567"),
                    customName = null,
                ),
            ),
        )
    }

    @Test fun `create_conversation without addresses is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"create_conversation","custom_name":"Family"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `create_conversation with a non-array addresses is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"create_conversation","addresses":"+15551234567"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `conversation_created encodes type and the conversation`() {
        val json = SyncProtocol.encodeConversationCreated(
            SyncProtocol.ConversationCreated(
                sampleConversation(id = 123, title = "Family", messageCount = 7),
            ),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("conversation_created")
        val conversation = obj["conversation"]!!.jsonObject
        assertThat(conversation["id"]?.jsonPrimitive?.content).isEqualTo("123")
        assertThat(conversation["title"]?.jsonPrimitive?.content).isEqualTo("Family")
        assertThat(conversation["message_count"]?.jsonPrimitive?.content).isEqualTo("7")
    }

    @Test fun `conversation_created round-trips through the wire shape`() {
        // The ack's conversation is the exact same shape as one
        // entry in the `conversations` batches — the desktop opens
        // it the same way — so the encoded frame must decode back
        // to the same conversation.
        val sent = sampleConversation(id = 123, title = "Family", messageCount = 7)
        val json = SyncProtocol.encodeConversationCreated(
            SyncProtocol.ConversationCreated(sent),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        val decoded = Json.decodeFromJsonElement<SyncedConversation>(obj["conversation"]!!)
        assertThat(decoded).isEqualTo(sent)
    }
}

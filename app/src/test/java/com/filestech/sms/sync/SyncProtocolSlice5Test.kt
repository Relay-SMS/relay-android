package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Slice 5 — the contact-sync wire types: every new message type
 * encodes with its `type` field and parses back to the same value,
 * and the contact id never crosses the wire (the privacy invariant
 * — `@Transient`, pinned here so a future refactor cannot leak it).
 */
class SyncProtocolSlice5Test {

    // -- Slice 5: contact sync ----------------------------------------

    @Test fun `contacts_request parses to the data object`() {
        assertThat(SyncProtocol.parse("""{"type":"contacts_request"}"""))
            .isEqualTo(SyncProtocol.Parsed.ContactsRequest(SyncProtocol.ContactsRequest))
    }

    @Test fun `avatar_request parses the address`() {
        assertThat(
            SyncProtocol.parse("""{"type":"avatar_request","address":"+15551234567"}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.AvatarRequest(
                SyncProtocol.AvatarRequest(address = "+15551234567"),
            ),
        )
    }

    @Test fun `avatar_request without an address is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"avatar_request"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `contacts encodes a batch with has_more`() {
        val encoded = SyncProtocol.encodeContacts(
            SyncProtocol.Contacts(
                contacts = listOf(
                    SyncedContact(
                        displayName = "Marie",
                        phones = listOf("+15551234567", "+15557654321"),
                        hasPhoto = true,
                        photoUpdatedAt = 1_794_670_000_000L,
                        contactId = 42L,
                    ),
                ),
                hasMore = true,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("contacts")
        assertThat(obj["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
        val contact = obj["contacts"]?.jsonArray?.get(0)?.jsonObject
        assertThat(contact?.get("display_name")?.jsonPrimitive?.content).isEqualTo("Marie")
        assertThat(contact?.get("phones")?.jsonArray?.map { it.jsonPrimitive.content })
            .containsExactly("+15551234567", "+15557654321").inOrder()
        assertThat(contact?.get("has_photo")?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(contact?.get("photo_updated_at")?.jsonPrimitive?.content).isEqualTo("1794670000000")
    }

    @Test fun `a contact id never crosses the wire`() {
        val encoded = SyncProtocol.encodeContacts(
            SyncProtocol.Contacts(
                contacts = listOf(sampleContact(id = 42L, hasPhoto = true, photoUpdatedAt = 1L)),
                hasMore = false,
            ),
        )
        // No `contact_id` key anywhere in the frame — the id is
        // `@Transient`, internal to the server's snapshot diff only.
        assertThat(encoded).doesNotContain("contact_id")
        assertThat(encoded).doesNotContain("42")
    }

    @Test fun `a contact without a photo encodes a null photo version`() {
        val encoded = SyncProtocol.encodeContacts(
            SyncProtocol.Contacts(
                contacts = listOf(sampleContact(id = 7L, hasPhoto = false)),
                hasMore = false,
            ),
        )
        val contact = Json.parseToJsonElement(encoded).jsonObject["contacts"]
            ?.jsonArray?.get(0)?.jsonObject
        assertThat(contact?.get("has_photo")?.jsonPrimitive?.content).isEqualTo("false")
        // `photo_updated_at` is null when `has_photo` is false — and
        // `encodeDefaults = false` omits a null default, so the key
        // is absent, which the desktop reads as null.
        assertThat(contact?.containsKey("photo_updated_at")).isFalse()
    }

    @Test fun `contact_updated encodes the contact and removed phones`() {
        val encoded = SyncProtocol.encodeContactUpdated(
            SyncProtocol.ContactUpdated(
                contact = sampleContact(id = 42L, phones = listOf("+15551234567")),
                removedPhones = listOf("+15550001111"),
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("contact_updated")
        assertThat(obj["removed_phones"]?.jsonArray?.map { it.jsonPrimitive.content })
            .containsExactly("+15550001111")
        assertThat(obj["contact"]?.jsonObject?.get("display_name")?.jsonPrimitive?.content)
            .isEqualTo("Marie")
    }

    @Test fun `contact_updated without removed phones omits the field`() {
        val encoded = SyncProtocol.encodeContactUpdated(
            SyncProtocol.ContactUpdated(contact = sampleContact(id = 42L)),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj.containsKey("removed_phones")).isFalse()
    }

    @Test fun `contact_deleted encodes the last-known phones`() {
        val encoded = SyncProtocol.encodeContactDeleted(
            SyncProtocol.ContactDeleted(phones = listOf("+15551234567", "+15557654321")),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("contact_deleted")
        assertThat(obj["phones"]?.jsonArray?.map { it.jsonPrimitive.content })
            .containsExactly("+15551234567", "+15557654321").inOrder()
    }

    @Test fun `contacts_error encodes code and message`() {
        val encoded = SyncProtocol.encodeContactsError(
            SyncProtocol.ContactsError(
                code = SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS,
                message = "Relay on the phone needs contacts permission to sync names and photos.",
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("contacts_error")
        assertThat(obj["code"]?.jsonPrimitive?.content).isEqualTo("hub_no_contacts_access")
        assertThat(obj["message"]?.jsonPrimitive?.content)
            .isEqualTo("Relay on the phone needs contacts permission to sync names and photos.")
    }

    @Test fun `contacts error codes are the spec strings`() {
        assertThat(SyncProtocol.ContactsError.HUB_NO_CONTACTS_ACCESS).isEqualTo("hub_no_contacts_access")
        assertThat(SyncProtocol.ContactsError.INTERNAL).isEqualTo("internal")
    }

    @Test fun `sync_started encodes total_contacts`() {
        val encoded = SyncProtocol.encodeSyncStarted(
            SyncProtocol.SyncStarted(
                mode = "full",
                totalConversations = 12,
                totalMessages = 3401L,
                cursorMessageId = "9876",
                hubIsDefaultSms = true,
                totalContacts = 214,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["total_contacts"]?.jsonPrimitive?.content).isEqualTo("214")
    }

    @Test fun `sync_started without total_contacts omits the field`() {
        val encoded = SyncProtocol.encodeSyncStarted(
            SyncProtocol.SyncStarted(
                mode = "full",
                totalConversations = 12,
                totalMessages = 3401L,
                cursorMessageId = "9876",
                hubIsDefaultSms = true,
            ),
        )
        // Default 0 + `encodeDefaults = false`: the field is absent,
        // which an older desktop reads as "no contacts synced" — and
        // the frame stays byte-identical to the pre-Slice-5 shape.
        assertThat(Json.parseToJsonElement(encoded).jsonObject.containsKey("total_contacts")).isFalse()
    }
}

package com.idroid.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mirrors the relay's ItemMeta JSON (see relay/internal/relay/store.go).
 * Kept intentionally identical field-for-field so the client never needs
 * to translate between two shapes of the same concept.
 */
@Serializable
data class ItemMeta(
    val id: String,
    val sender_id: String,
    val recipient_id: String,
    val kind: String,
    val filename: String? = null,
    val content_type: String,
    val size: Long,
    val created_at: String,
    val expires_at: String,
)

/**
 * Pushed over the websocket: "new_item" (refetch GET /inbox), "devices_changed"
 * (refetch GET /devices), or "fs_request" (another device is browsing this
 * phone's shared folders; answered with POST /fs/respond/{request_id}, ADR-0004).
 */
@Serializable
data class NotifyEvent(
    val event: String,
    val item_id: String = "",
    val request_id: String = "",
    val op: String = "",
    val path: String = "/",
    val sender_id: String = "",
)

/**
 * One entry of a remote folder listing (GET /fs/{device}/list), the same JSON
 * both apps produce when answering an fs_request. [path] is absolute within the
 * serving device's shares: "/" lists the shared folders themselves.
 */
@Serializable
data class FsEntry(
    val name: String,
    val path: String,
    val is_dir: Boolean,
    val size: Long = 0,
    val modified: String? = null,
    val mime: String? = null,
)

@Serializable
data class FsListing(val entries: List<FsEntry>)

/** One device in this phone's group (GET /devices) — everything it can send to. */
@Serializable
data class PeerDevice(
    val id: String,
    val name: String,
    val created_at: String,
    val online: Boolean = false,
)

@Serializable
data class DevicesResponse(
    @SerialName("self") val me: PeerDevice,
    val devices: List<PeerDevice>,
)

object ItemKind {
    const val FILE = "file"
    const val TEXT = "text"
    const val CLIPBOARD = "clipboard"
}

/**
 * PIN pairing (see relay/README.md#api): one device calls /pair/start and
 * shows the resulting code, the other calls /pair/claim with it. Mirrors
 * the relay's JSON field-for-field, same spirit as ItemMeta above.
 */
@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val token: String,
    val created_at: String,
)

@Serializable
data class PairingInfo(
    val session_id: String,
    val code: String,
    val expires_at: String,
)

@Serializable
data class PairStartResponse(
    val device: DeviceInfo,
    val pairing: PairingInfo,
)

@Serializable
data class PairPeer(
    val id: String,
    val name: String,
)

@Serializable
data class PairClaimResponse(
    val device: DeviceInfo,
    val peer: PairPeer,
)

@Serializable
data class PairStatusResponse(
    val claimed: Boolean,
    val peer: PairPeer? = null,
)

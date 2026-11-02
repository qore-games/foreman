package games.qore.foreman.client.link

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The inbound drop selection and the exemption list both packet hooks rely on: a test names fragments, packets whose
 * type contains one are dropped, and the ones that keep the connection alive always pass.
 */
class PacketFilterTest {
    @BeforeTest
    fun arm() = PacketFilter.drop(listOf("setslot", "containerset"))

    @AfterTest
    fun clear() = PacketFilter.clear()

    @Test
    fun dropsSelectedTypes() {
        assertTrue(PacketFilter.shouldDrop(ClientboundContainerSetSlotPacket()))
        assertTrue(PacketFilter.shouldDrop(ClientboundContainerSetContentPacket()))
        assertFalse(PacketFilter.shouldDrop(ClientboundContainerClosePacket()))
    }

    @Test
    fun matchesFragmentsWithoutSeparators() {
        PacketFilter.drop(listOf("set_slot"))
        assertTrue(PacketFilter.shouldDrop(ClientboundContainerSetSlotPacket()))
    }

    @Test
    fun neverDropsThePacketsThatKeepTheConnection() {
        PacketFilter.drop(listOf("keepalive", "ping", "disconnect", "chatsession", "configuration"))
        assertFalse(PacketFilter.shouldDrop(ClientboundKeepAlivePacket()))
        assertFalse(PacketFilter.shouldDrop(ClientboundPingPacket()))
        assertFalse(PacketFilter.shouldDrop(ClientboundDisconnectPacket()))
        assertFalse(PacketFilter.shouldDrop(ServerboundChatSessionUpdatePacket()))
        assertFalse(PacketFilter.shouldDrop(ServerboundConfigurationAcknowledgedPacket()))
    }

    @Test
    fun exemptsTheSamePacketsForHolding() {
        assertTrue(PacketFilter.exempt(ClientboundKeepAlivePacket()))
        assertTrue(PacketFilter.exempt(ServerboundChatSessionUpdatePacket()))
        assertTrue(PacketFilter.exempt(ServerboundConfigurationAcknowledgedPacket()))
        assertFalse(PacketFilter.exempt(ServerboundMovePlayerPacket()))
    }

    @Test
    fun clearedSelectionDropsNothing() {
        PacketFilter.clear()
        assertFalse(PacketFilter.shouldDrop(ClientboundContainerSetSlotPacket()))
    }

    private class ClientboundContainerSetSlotPacket
    private class ClientboundContainerSetContentPacket
    private class ClientboundContainerClosePacket
    private class ClientboundKeepAlivePacket
    private class ClientboundPingPacket
    private class ClientboundDisconnectPacket
    private class ServerboundChatSessionUpdatePacket
    private class ServerboundConfigurationAcknowledgedPacket
    private class ServerboundMovePlayerPacket
}

# Network profiles (desync dupe testing)

The hardest item dupes need the client and server to disagree for a tick. Foreman's
[dupe testing](../server/dupe-testing.md) runs the input side of that (action bursts in one tick), and two stock network
profiles run the network side: `burst` holds the client's sends and sends them together at the end, and `ping_<n>` adds
n ms to each. A test names one as `network` on `inventory_actions`, and the profile is held around the burst only, or
on any `client_test` step, held around that step.
The hook is open too: implement another condition against the client connection and register it.

## Stock profiles

| id | What it does while armed |
|---|---|
| `burst` | Every outbound packet is held; on release they are sent in order, in one go. The client goes silent, then blinks. |
| `ping_100`, `ping_300`, `ping_500`, `ping_1000` | Each outbound packet is delayed by that many ms. Packets already scheduled when the burst ends keep their delay; the ones after it go normally. |

Keepalive, ping, pong, disconnect, login, kick, chat-session and configuration packets always pass untouched, so a test
is not kicked for lagging. Without a `network` argument the burst runs at normal timing.

## The interface

In the client mod (`games.qore.foreman.client.link`):

```kotlin
interface NetworkProfile {
    val id: String        // what a test names it by: "burst", "ping_300"
    fun apply()           // start the condition (delay/withhold/queue packets)
    fun release()         // stop and flush: the connection returns to normal
}
```

Register another one once, for example from your own companion mod or a dev build:

```kotlin
NetworkProfiles.register(object : NetworkProfile {
    override val id = "my_condition"
    override fun apply() { /* start the condition */ }
    override fun release() { /* stop it and send anything held */ }
})
```

Then a test burst holds it around the actions:

```json
{ "name": "inventory_actions",
  "arguments": { "actions": [ {"slot": 36, "input": "pickup"}, {"slot": 36, "input": "pickup"} ],
                 "per_tick": 2, "network": "burst" } }
```

`NetworkProfiles.around(id) { … }` applies the profile, runs the block, and releases it even if the block throws. With no
profile of that name registered, `inventory_actions` reports it clearly and does nothing; without a `network` argument the
burst runs at normal timing. Leaving the server releases every profile.

## Keeping it to your server

Packet timing applies to whatever server you're on, so it needs the server's opt-in: `inventory_actions` arms a
profile, or a `drop`, only when the server runs the Foreman plugin and gives the player `foreman.test` (the bridge's
hello says so), as the rest of the testing tools need. A profile you register goes through the same check.

## Inbound drops (ignore a server packet)

The same idea on the receive side: `PacketFilter` (in `client/link/`) holds which inbound packet types a test is
dropping and answers `shouldDrop(packet)` (it never drops keepalive/ping/disconnect/login/chat-session). The inbound
hook (`ConnectionMixin.foreman$received`) cancels a packet when `shouldDrop` is true, so the client never handles it and
keeps acting on the state it had. `inventory_actions` takes a `drop` list (type-name fragments like `setslot`,
`containerset`) and arms it around the burst; leaving the server clears it.

Packet bundles are dispatched as one packet, so a type inside a `ClientboundBundlePacket` is not matched by `drop`.

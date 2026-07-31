package cloud.charging.v2g.pairing

import java.net.InetAddress
import java.net.spi.InetAddressResolver
import java.net.spi.InetAddressResolverProvider
import java.util.stream.Stream

/**
 * A name resolver that refuses to resolve, installed for this module's whole test JVM.
 *
 * The rule it enforces is `PairingWarnings.isPrivateTarget`'s: judging a scanned host must not send a
 * packet. Resolving would mean a DNS query on behalf of a code nobody has decided to trust yet — a
 * callback to whoever printed the sticker, before any human agreed to anything, carrying the fact
 * that this phone just scanned it.
 *
 * A comment cannot enforce that and a code review will not keep enforcing it, because the JVM makes
 * the wrong thing the obvious thing: `InetAddress.getByName` resolves, and reaching for it looks like
 * exactly the right call. So the ban is mechanical. Any lookup anywhere under this module's tests
 * throws, and the test that caused it fails with the host name that was about to be sent.
 *
 * Registered through `META-INF/services` (JDK 18+, JEP 418) — it applies to the entire JVM including
 * code that never heard of this test, which is the point.
 *
 * ## Why it records as well as throws
 *
 * Throwing alone is not enough, and a mutation proved it: an implementation that resolved inside a
 * `runCatching` swallowed the watchdog whole and the test stayed green. That is not a contrived
 * mutation — DNS lookups fail all the time, so wrapping one in a try/catch is what a careful person
 * would write. The exact mistake worth catching is therefore the one a throw-only watchdog cannot
 * see. So every attempt is also recorded in [attempts], which no `catch` can undo.
 */
class NoResolutionResolverProvider : InetAddressResolverProvider() {

    companion object {
        /** Every host anything tried to resolve in this JVM, in order. */
        val attempts: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    }

    override fun name(): String = "no-resolution (pairing tests)"

    override fun get(configuration: Configuration): InetAddressResolver = object : InetAddressResolver {

        override fun lookupByName(host: String, policy: InetAddressResolver.LookupPolicy): Stream<InetAddress> {
            attempts.add(host)
            throw AssertionError(
                "'$host' was resolved. Judging a scanned pairing host must not send a packet: a DNS " +
                "query on behalf of a code nobody has agreed to trust is a callback to whoever " +
                "printed it. Decide on the text — an address literal is judged, everything else is a " +
                "name, and only '.local' counts as local.")
        }

        override fun lookupByAddress(address: ByteArray): String {
            attempts.add(address.joinToString(".") { (it.toInt() and 0xFF).toString() })
            throw AssertionError("a reverse lookup was performed while judging a pairing host.")
        }
    }
}

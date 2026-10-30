# Resolver presets are IP-only

`VpnService.Builder.addDnsServer` accepts bare IP addresses. There is no port, no URL
scheme, and no DoT or DoH support, and Android will not speak encrypted DNS to a
resolver supplied through a VPN. A preset whose target is a name is therefore resolved by
the app before install, and only literals ever reach the OS.

The alternative — terminating DoT or DoH in a proxy bound to an address inside the tunnel —
is deliberately not built. The hop from the phone to a resolver inside its own tailnet is
already WireGuard-encrypted, so transport encryption on that leg buys nothing; whatever the
resolver does upstream of itself is that host's business, not this app's.

A future requirement for an encrypted or hostname-based resolver needs that proxy first.
Until then, a request to "just support DoH" should be read as a request to build it, not
as a missing configuration option.

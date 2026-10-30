# Route custom resolvers through Quad-100 to preserve MagicDNS and prevent native fallback

Tailscale for Android points Android at Quad-100 (`100.100.100.100` / `fd7a:115c:a1e0::53`),
a forwarder that runs inside the tunnel. Quad-100 answers MagicDNS queries locally and
forwards everything else to the tailnet default resolvers.

Initially, ADR 0001 attempted to replace Quad-100 with the custom preset's resolvers directly
at the TUN boundary (`builder.AddDNSServer`). However, when non-tailnet resolvers (such as
`1.1.1.1` or public DNS) are installed at the TUN boundary on Android, Android detects that
the VPN does not have routes for those resolvers (in split-tunnel mode) and silently falls
back to the underlying physical network's native resolvers. This causes MagicDNS (`*.ts.net`)
to stop resolving completely and leaks queries outside the tunnel.

To guarantee that MagicDNS works reliably and prevent Android from falling back to native
resolvers, the TUN continues to install Quad-100 into Android's `VpnService.Builder`.
Instead of replacing Quad-100 at the OS boundary, `libtailscale` intercepts the resolver
configuration in `tailscale.com/net/dns/resolver.Resolver`:
- The default catch-all route `.` in Quad-100 is pointed at the preset's addresses (`sel.addrs`).
- MagicDNS queries (`*.ts.net`) are resolved authoritatively and locally by Quad-100.
- All non-MagicDNS queries are forwarded by Quad-100 to the preset's addresses via protected
  sockets using Tailscale's `tsdial.Dialer`.
- Single-label search domains (`dcfg.SearchDomains`) remain installed in `VpnService.Builder`.

Consequences:
- Android remains pinned to Quad-100 inside the VPN tunnel, eliminating native resolver fallbacks.
- MagicDNS always resolves without requiring the custom preset to forward back to Tailscale.
- Custom presets (e.g., ad blockers, NextDNS, local Pi-holes, or public DNS) receive all catch-all
  traffic via protected sockets, working seamlessly with or without exit nodes.
- Presets store the user-facing name and a cached list of resolved literals.

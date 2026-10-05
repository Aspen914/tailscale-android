# Override this device's resolvers at the TUN, not inside Quad-100

Tailscale for Android points Android at Quad-100 (`100.100.100.100`), a forwarder that
runs inside the tunnel. Quad-100 answers MagicDNS locally and forwards everything else to
the *tailnet default resolvers* — but that catch-all route is assigned inside
`tailscale.com`'s `net/dns/manager.go`, which this repository does not own. `libtailscale`
only consumes the finished `dns.OSConfig`. So there is no seam in Kotlin for reordering what
a single device forwards to, and `ipn.Prefs` has no field for it either: `CorpDNS` is a
boolean and nothing more.

Rather than fork `tailscale.com` with a `go.mod` replace directive, the *resolver preset*
replaces Quad-100 at the OS boundary: `libtailscale`'s `updateTUN` installs the preset's
addresses in place of `dcfg.Nameservers`. Quad-100's own configuration is left alone.

Consequences worth knowing:

- In a custom preset, Android never contacts Quad-100. The chosen resolver is solely
  responsible for tailnet names, so it must forward `*.tailnet.ts.net` somewhere that
  answers — a Pi-hole does this by forwarding to `100.100.100.100` on its own tailnet
  interface. Search domains are still installed, so single-label queries still expand.
- The device ends up with two resolution paths: `tailscaled`'s internal catch-all still
  points at the tailnet resolvers, while the phone's queries go to the preset.
- `Prefs.CorpDNS` becomes a derived value of the active preset rather than an independent
  setting, which is why the preset list is the only control surface.
- Presets store the user-facing name (IP literal, MagicDNS short name, or FQDN) and a
  cached list of resolved literals. The cache is refreshed when a preset is saved, and
  re-resolved at switch time only if a probe against the cached addresses fails, so a
  changed address heals without making the common switch path depend on a lookup.

Rejected: forking `tailscale.com` (upgrade drift for a per-device setting);
a separate DNS-only `VpnService` app (Android runs one VPN per user, and a Pi-hole reached
over Tailscale needs Tailscale's tunnel); Android Private DNS (it overrides the VPN's
resolver entirely, breaking MagicDNS unless the chosen server can resolve tailnet names,
and it breaks RCS and visual voicemail).

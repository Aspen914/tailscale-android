# Tailscale for Android — DNS resolution

This context covers how DNS queries leave the phone while the Tailscale VPN is up, and
the vocabulary needed to reason about changing it.

## Tailnet and device

**Tailnet**:
The private network of Tailscale nodes, shared by everything you sign into with the same
identity. Global DNS settings (resolvers, split-DNS routes, search domains) are a property
of the tailnet, so changing them affects every device on it.
_Avoid_: account, network, org, plan

**Node**:
One Tailscale participant — a phone, laptop, or server. Has its own Tailscale IP
(`100.x.y.z`) and its own *prefs*, which are per-device.
_Avoid_: client, device, machine, host

**Prefs**:
A node's local, per-device Tailscale settings, persisted by `tailscaled` and readable and
writable by the app over LocalAPI. Currently `CorpDNS` is the only DNS-related one: a
boolean for "install the tailnet's DNS configuration".
_Avoid_: preferences, settings, config, options

## Resolution

**Quad-100**:
`100.100.100.100` (and `fd7a:115c:a1e0::53`), the Tailscale service IP. Every node serves a
DNS forwarder there that answers MagicDNS from local state and forwards everything else to
a *catch-all* resolver. When MagicDNS is enabled, this is the resolver Android is given —
the phone never queries a public resolver directly.
_Avoid_: local DNS server, Tailscale DNS server, the DNS proxy

**MagicDNS**:
Tailnet-internal name resolution. `foo.tail1234.ts.net` is answered by Quad-100 from the
netmap, never forwarded upstream.
_Avoid_: tailnet DNS, internal DNS, short names

**Catch-all resolution**:
Any query that is not MagicDNS. Handled by Quad-100's `.` route, which normally points at
the tailnet's *tailnet default resolvers*.
_Avoid_: normal DNS, public DNS, external DNS, non-MagicDNS resolution

**Tailnet default resolvers**:
The upstream resolver list the tailnet publishes, which Quad-100 forwards catch-all queries
to. Read-only from a device; `Prefs.CorpDNS` only decides whether it is used at all.
_Avoid_: account DNS, global DNS, my DNS

**Resolver preset**:
The single control that decides which addresses a node hands to Android. Exactly one preset
is active at a time, stored per *tailnet*. Two are built in and cannot be edited or removed:
**Use Tailscale DNS settings** (Quad-100, so catch-all resolution goes to the *tailnet default
resolvers*) and **Use system resolver** (nothing is handed to Android, so Android resolves).
Every other preset names one target — an IP literal, a MagicDNS short name, or a MagicDNS
FQDN — that the app resolves before install and caches as literals.
_Avoid_: DNS server option, DNS profile, DNS mode, DNS toggle, resolver

Because the preset is the only control, *prefs*' `CorpDNS` is a derived value of the active
preset rather than a setting in its own right.

**Android Private DNS**:
Android's system-wide DNS-over-TLS setting (`Off` / `Automatic` / a specific hostname).
Independent of the VPN, and in strict mode it takes over resolution from the VPN entirely —
including MagicDNS, which then breaks unless the chosen resolver itself can resolve tailnet
names. It also breaks RCS and visual voicemail. Never the mechanism for changing the
phone's resolver here.
_Avoid_: private DNS mode, DoT setting, secure DNS

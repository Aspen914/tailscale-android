# Per-device resolver presets

Status: proposed. No implementation yet.

Vocabulary is in [CONTEXT.md](../../CONTEXT.md); the two decisions that constrain this
design are in [ADR 0001](../adr/0001-override-device-resolvers-at-the-tun.md) and
[ADR 0002](../adr/0002-resolver-presets-are-ip-only.md).

## Goal

Let a node choose, at will, which upstream resolver answers non-MagicDNS queries, without
touching tailnet-global DNS settings and without Android Private DNS. Both MagicDNS and
RCS/visual voicemail must keep working in every state.

## Baseline

Captured read-only from the target device (moto g power 5G - 2024, Android 15 / SDK 35)
with the stock app installed and Tailscale up:

```
NetworkAgentInfo{network{134} ni{VPN CONNECTED extra: VPN:com.tailscale.ipn}
  lp{{InterfaceName: tun1
      LinkAddresses: [ 100.121.230.50/32 fd7a:115c:a1e0::832:e632/128 ]
      DnsAddresses: [ /100.100.100.100 /fd7a:115c:a1e0::53 ]
      Domains: taile48ed2.ts.net
      MTU: 1280
      Routes: [ ... 100.100.100.100/32 ... 192.168.1.0/24 ... ]}}
```

Three things this establishes:

- The TUN installs exactly Quad-100, both address families, and nothing else. Replacing
  `dcfg.Nameservers` in `updateTUN` is therefore the whole change — there is no second
  source of nameservers to reconcile.
- `DnsAddresses` is where a preset's addresses will appear, so it is the verification
  signal. `dumpsys dnsresolver` is *not* usable here: the service exists in `service list`
  but refuses to dump.
- Private DNS is off (`private_dns_mode=off`), and the VPN's `LinkProperties` carry no
  `UsePrivateDns`/`PrivateDnsServerName`, confirming private DNS is not applied on top of
  VPN-supplied resolvers. This is why strict-mode private DNS breaks things instead of
  merely layering over them.

The installed build is the Play-signed `1.102.4-t3caf7d9e7-g1b32d3caa` (versionCode
298177930). Installing a debug-signed build over it is impossible, so installation means
uninstalling first, which discards the node key and requires re-authentication.

## Model

The preset list is the only DNS control surface. Exactly one preset is active per profile.
Two are built in and cannot be edited, reordered, or removed:

| Preset | Installs into the TUN | `Prefs.CorpDNS` |
| --- | --- | --- |
| **Use Tailscale DNS settings** | `dcfg.Nameservers` (Quad-100) | `true` |
| **Use system resolver** | nothing — Android resolves | `false` |
| *custom* | the preset's resolved literals | `true` |

The invariant is `CorpDNS == (activePreset != "Use system resolver")`. The existing
`CorpDNS` switch in `DNSSettingsView.kt` is removed so the two cannot disagree. A custom
preset leaves MagicDNS switched off at the OS boundary, because Android is pointed at the
preset and never asks Quad-100; tailnet names resolve because the preset is expected to
forward them (a Pi-hole forwards `*.tailnet.ts.net` to `100.100.100.100`).

`MDMSettings.useTailscaleDNSSettings != UserDecides` makes the whole list read-only, which
is what that setting does today to the one switch.

A custom preset is a single user-facing target — an IP literal, a MagicDNS short name, or
a MagicDNS FQDN — plus the literals that target resolved to. One target, many addresses: a
MagicDNS name commonly yields both a `100.x` and an `fd7a:` address and both are installed,
in the order returned.

## Storage

Unencrypted `SharedPreferences` via `App.getUnencryptedPrefs()`, one key holding all
profiles, `kotlinx.serialization` JSON, self-heal to defaults on a corrupt value. Modelled
on `TaildropDirectoryStore.kt`.

```
resolver_presets ->
  {"profiles":{"<login name>":{"activeId":"builtin:tailnet","custom":[ ... ]}}}
```

```kotlin
@Serializable
data class ResolverPreset(
    val id: String,                     // stable UUID
    val label: String,                  // "Pi-hole"
    val target: String,                 // literal, short name, or FQDN
    val resolved: List<String> = emptyList(), // cached literals, refreshed on save
)

@Serializable
data class ProfileDNS(
    val activeId: String,
    val custom: List<ResolverPreset> = emptyList(),
)

@Serializable
data class ResolverStore(val profiles: Map<String, ProfileDNS> = emptyMap())
```

A single key means a profile switch is one read and one write with no possibility of
tearing across keys. The profile key is the login name from
`Notifier.netmap.value?.SelfNode?.User`, which is already delivered on every netmap update
and needs no extra LocalAPI call. When it is empty — before first login — the built-in
default applies and the DNS screen says why.

Not encrypted: this is a list of names and addresses, not a secret.

## Resolution and probe (Go)

Two methods on the `Application` interface in `libtailscale/interfaces.go`, following the
shape of `SetClientLoggingEnabled`:

```go
// ResolveResolverAddresses resolves target — an IP literal or an FQDN — against
// Quad-100, then verifies the result actually answers queries. cached is probed
// first; only if that fails is target resolved again. Returns the literals to
// install.
ResolveResolverAddresses(target string, cached []string, timeoutMillis int) ([]string, error)

// SetResolverAddresses makes the TUN install addrs in place of Quad-100.
// An empty slice restores Tailscale's own choice.
SetResolverAddresses(addrs []string)
```

Resolution uses a `net.Resolver` whose `Dial` is pinned to Quad-100, so the lookup cannot
be answered by whatever the system resolver happens to be. Kotlin expands a short name to
its FQDN first — it already holds `MagicDNSSuffix` — which keeps Go's contract to "IP
literal or FQDN" and puts tailnet-suffix knowledge on the side that has it.

The probe is two queries against each candidate address, both 2s:

- `example.com` — proves the address is reachable and answers something.
- the target's own FQDN, when the target was a name — proves `*.tailnet.ts.net`
  forwarding still works, which is the failure most likely to be silent.

Cached-first is what makes the stale-address case self-healing without making the common
switch depend on a lookup: a normal switch probes the cached literals and never resolves
the name at all; a stale literal costs one extra resolution and heals; a genuinely dead
resolver fails both and refuses the switch with a message naming which step failed.

`ResolveResolverAddresses` requires the tunnel, since Quad-100 is only reachable through
it. The editor says so when Tailscale is down rather than failing opaquely.

## Applying the preset (Go)

`SetResolverAddresses` must not touch the TUN from the calling goroutine. `cfg` is a local
variable of the `runBackend` select loop, and `b.lastCfg`/`b.lastDNSCfg` are written only
by that goroutine, so a direct `b.updateTUN(...)` from a binder goroutine is a race on the
TUN itself. The codebase already solves this for out-of-band events with
`onVPNRequested`, `onDisconnect`, and `onDNSConfigChanged`; this adds a fourth channel and
select case:

```go
case addrs := <-onResolverChange:
        b.resolverAddrs, err = parseAddrs(addrs)
        if err != nil {
                break
        }
        if state >= ipn.Starting && b.lastCfg != nil {
                if err := b.updateTUN(b.lastCfg, b.lastDNSCfg); err != nil {
                        a.closeVpnService(err, b)
                }
        }
```

`b.lastCfg` is nil while the tunnel is closed, so the guard is required; `updateTUN`
dereferences `rcfg.LocalAddrs` and would panic on nil.

The change inside `updateTUN` is four lines at `libtailscale/net.go:112`:

```go
 		nameservers := dcfg.Nameservers
+		if len(b.resolverAddrs) > 0 {
+				// A resolver preset is active: install its addresses in place of
+				// Quad-100, so catch-all resolution leaves the phone from here.
+				nameservers = b.resolverAddrs
+		}
 		if b.avoidEmptyDNS && len(nameservers) == 0 {
```

Search domains still come from `dcfg.SearchDomains`, so single-label queries keep
expanding and the preset resolves the FQDN. Quad-100's own configuration is never touched.

`VpnService.Builder` is immutable, so a nameserver change means a full re-`establish()`.
Switching presets rebuilds the tunnel and costs a brief blip. That is inherent to the
platform API, not something this design can scope away.

## Kotlin side

The active preset is pushed into the backend on app start, on profile switch, and on
selection change, mirroring `setClientLoggingEnabled`. Custom presets push their resolved
literals; both built-ins push an empty slice.

`DNSSettingsView` gains the preset list at the top and loses the `CorpDNS` switch. The
existing resolvers/domains/routes section stays, but is suppressed while a custom preset is
active — it reports what the tailnet publishes, and showing Quad9 as the active resolver
while the Pi-hole is installed would be actively wrong.

New `ResolverPresetsView` provides CRUD for custom presets: label, target, resolved
preview, and a test action that runs the probe without committing.

`MainView` gets an inline row in the `Running` column showing the effective preset; tapping
it opens a `ModalBottomSheet` picker, following `ExitNodePicker.kt`. The row is absent
while Tailscale is not running. A custom preset that is active but not currently verified
gets a persistent inline warning; there is no watchdog, no auto-revert, and no new
health warnable.

## Files

Go: `libtailscale/interfaces.go` (two signatures), `libtailscale/tailscale.go` (resolve and
probe), `libtailscale/backend.go` (field, channel, select case), `libtailscale/net.go` (the
four lines above).

Kotlin: new `ui/model/ResolverStore.kt`, `ui/viewModel/ResolverPresetsViewModel.kt`,
`ui/view/ResolverPresetsView.kt`; edits to `DNSSettingsView.kt`,
`DNSSettingsViewModel.kt`, `MainView.kt`, `MainActivity.kt`, `SettingsViewModel.kt` (new
`SettingsNav` field — `SettingsView.kt:301` builds that data class positionally and must be
updated in step), `App.kt`, and `strings.xml`.

## Verification

`dumpsys connectivity` filtered to the VPN agent is the signal:

```
adb shell dumpsys connectivity | grep 'VPN:com.tailscale.ipn' | tr ',' '\n' \
  | grep -E 'DnsAddresses|Domains'
```

For each of the three preset states, confirm `DnsAddresses` matches, then resolve a public
name, a tailnet FQDN, and a single-label tailnet name; send an RCS message and check visual
voicemail. Switch both ways, and confirm recovery after the Pi-hole reboots — that is the
cached-literal heal path.

## Known limitations

- A custom preset makes MagicDNS the preset's responsibility. Nothing in the app can verify
  that beyond the FQDN probe; a resolver that answers `example.com` but does not forward
  `*.tailnet.ts.net` will pass the probe and break tailnet names.
- `MagicDNSSuffix` and `SelfNode.User` are both absent before first login, so short-name
  targets cannot be entered or saved then.
- Storage is keyed on login name rather than the profile's stable ID. Editing a login name
  orphans its presets.
- No watchdog. If the preset becomes unreachable after a successful switch, DNS stays
  broken until the user notices and switches back.

## Out of scope

DoT/DoH resolvers (see ADR 0002), per-app DNS, changing tailnet DNS settings, and
automatically reverting to a working resolver.
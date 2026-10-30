// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/rand/v2"
	"net"
	"net/netip"
	"strings"
	"sync/atomic"
	"syscall"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/net/tsaddr"
)

// The TUN hands Android Quad-100 so all DNS queries route into the tunnel.
// In modeCustom, Quad-100's default forwarder route is overridden with the preset's
// addresses, ensuring MagicDNS queries are answered directly by Quad-100 while
// all catch-all queries route to the selected preset via protected sockets.
//
// resolverMode represents the active resolver behavior.
type resolverMode string

const (
	// modeTailscale installs the tailnet's own resolvers (dcfg.Nameservers),
	// which is how the device behaves without any override.
	modeTailscale resolverMode = "tailscale"
	// modeSystem installs no nameservers at all, letting Android fall back to
	// whatever the underlying network provides.
	modeSystem resolverMode = "system"
	// modeCustom installs the addresses supplied by the preset instead.
	modeCustom resolverMode = "custom"
)

// resolverSelection is the current resolver override. The address list is only
// consulted when mode == modeCustom.
type resolverSelection struct {
	mode resolverMode
	addrs []netip.Addr
}

var resolverSel atomic.Pointer[resolverSelection]

func currentResolverSel() resolverSelection {
	if p := resolverSel.Load(); p != nil {
		return *p
	}
	return resolverSelection{mode: modeTailscale}
}

func setResolverMode(mode resolverMode, addrs []netip.Addr) {
	resolverSel.Store(&resolverSelection{mode: mode, addrs: addrs})
}

// parseResolverAddrs validates and normalises a JSON array of IP literals.
func parseResolverAddrs(addrsJSON string) ([]netip.Addr, error) {
	var raw []string
	if strings.TrimSpace(addrsJSON) != "" {
		if err := json.Unmarshal([]byte(addrsJSON), &raw); err != nil {
			return nil, fmt.Errorf("resolver addresses are not a JSON array: %w", err)
		}
	}
	parsed := make([]netip.Addr, 0, len(raw))
	for _, s := range raw {
		addr, err := netip.ParseAddr(strings.TrimSpace(s))
		if err != nil {
			return nil, fmt.Errorf("resolver address %q is not an IP address: %w", s, err)
		}
		parsed = append(parsed, addr.Unmap())
	}
	return parsed, nil
}

// defaultResolveTimeout bounds a resolve or a probe when the caller does not say.
const defaultResolveTimeout = 2 * time.Second

// probeName is queried to confirm a candidate resolver answers something. It has
// to be a name any resolver can answer, so that "unreachable" and "configured but
// broken" are told apart rather than both looking like NXDOMAIN.
const probeName = "example.com."

// resolveResolverAddresses resolves target against Quad-100 and verifies the
// result answers queries, returning the literals to install into the TUN.
//
// target is either an IP literal or an FQDN. Short MagicDNS names are expanded by
// the caller, which is the side that knows the tailnet's DNS suffix.
//
// cached, when non-empty, is the result of a previous save. It is probed first, so
// that switching presets neither depends on Quad-100 answering nor costs a
// resolution, and target is only resolved if that probe fails. That makes a changed
// address self-healing without slowing down the common case.
//
// Resolution requires the tunnel, because Quad-100 is only reachable through it.
func resolveResolverAddresses(target string, cached []string, timeout time.Duration) ([]string, error) {
	target = strings.TrimSpace(target)
	if target == "" {
		return nil, errors.New("no resolver address given")
	}

	if _, err := netip.ParseAddr(target); err == nil {
		return probeResolverAddrs([]string{target}, timeout, "")
	}

	fqdn, ok := dnsFQDN(target)
	if !ok {
		return nil, fmt.Errorf("%q is neither an IP address nor a domain name", target)
	}

	if len(cached) > 0 {
		if addrs, err := probeResolverAddrs(cached, timeout, fqdn); err == nil {
			return addrs, nil
		}
	}

	addrs, err := resolveName(fqdn, timeout)
	if err != nil {
		return nil, fmt.Errorf("could not resolve %s through Quad-100: %w", fqdn, err)
	}
	if len(addrs) == 0 {
		return nil, fmt.Errorf("%s has no addresses", fqdn)
	}
	return probeResolverAddrs(addrs, timeout, fqdn)
}

// probeResolverAddrs returns the subset of addrs that answer probeName, in the
// order given. When mustAnswer is non-empty, an address must answer that name too,
// which is how a resolver that answers the open internet but does not forward
// *.tailnet.ts.net gets caught before it silently breaks MagicDNS.
//
// An address that answers neither is dropped rather than failing outright, since a
// MagicDNS name commonly resolves to both a 100.x and an fd7a: address and only one
// family may be reachable. It is an error only if nothing survives.
func probeResolverAddrs(addrs []string, timeout time.Duration, mustAnswer string) ([]string, error) {
	var (
		alive []string
		last  error
	)
	for _, s := range addrs {
		addr, err := netip.ParseAddr(strings.TrimSpace(s))
		if err != nil {
			last = fmt.Errorf("resolver address %q is not an IP address: %w", s, err)
			continue
		}
		if _, err := query(addr, probeName, dnsmessage.TypeA, timeout); err != nil {
			last = fmt.Errorf("%s did not answer %s: %w", addr, probeName, err)
			continue
		}
		if mustAnswer != "" {
			if _, err := query(addr, mustAnswer, dnsmessage.TypeA, timeout); err != nil {
				last = fmt.Errorf("%s did not answer %s: %w", addr, mustAnswer, err)
				continue
			}
		}
		alive = append(alive, addr.String())
	}

	if len(alive) == 0 {
		if last == nil {
			last = errors.New("no resolver addresses given")
		}
		return nil, fmt.Errorf("no resolver answered: %w", last)
	}
	return alive, nil
}

// resolveName looks up the A and AAAA records of name at Quad-100, returning the
// addresses in the order the resolver returned them.
func resolveName(name string, timeout time.Duration) ([]string, error) {
	var (
		out   []string
		last  error
		asked bool
	)
	for _, typ := range []dnsmessage.Type{dnsmessage.TypeA, dnsmessage.TypeAAAA} {
		addrs, err := queryQuad100(name, typ, timeout)
		if err != nil {
			last = err
			continue
		}
		asked = true
		out = append(out, addrs...)
	}
	if !asked {
		// Every query failed, so this is a reachability problem rather than an
		// authoritative "this name does not exist".
		return nil, last
	}
	return out, nil
}

// queryQuad100 sends one query for name to Quad-100, preferring IPv4 and falling
// back to the IPv6 service address.
func queryQuad100(name string, typ dnsmessage.Type, timeout time.Duration) ([]string, error) {
	var last error
	for _, srv := range []netip.Addr{tsaddr.TailscaleServiceIP(), tsaddr.TailscaleServiceIPv6()} {
		addrs, err := query(srv, name, typ, timeout)
		if err == nil {
			return addrs, nil
		}
		last = err
	}
	return nil, last
}

// query sends a single UDP DNS question to server and returns the addresses in the
// answer section. A truncated or non-success response is an error: neither is worth
// retrying over TCP for the small answers this asks about.
func query(server netip.Addr, name string, typ dnsmessage.Type, timeout time.Duration) ([]string, error) {
	qname, err := dnsmessage.NewName(name)
	if err != nil {
		return nil, fmt.Errorf("%q is not a valid query name: %w", name, err)
	}

	id := uint16(rand.Uint32()) //nolint:gosec // only needs to be unpredictable to off-path senders
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{
		ID:               id,
		RecursionDesired: true,
		CheckingDisabled: true,
	})
	if err := b.StartQuestions(); err != nil {
		return nil, err
	}
	if err := b.Question(dnsmessage.Question{Name: qname, Type: typ, Class: dnsmessage.ClassINET}); err != nil {
		return nil, err
	}
	if err := b.StartAnswers(); err != nil {
		return nil, err
	}
	buf, err := b.Finish()
	if err != nil {
		return nil, err
	}

	h, answers, err := exchangeUDP(server, buf, id, timeout)
	if err != nil {
		return nil, err
	}
	switch {
	case h.Truncated:
		return nil, fmt.Errorf("%s truncated the answer for %s", server, name)
	case h.RCode != dnsmessage.RCodeSuccess:
		return nil, fmt.Errorf("%s returned %v", server, h.RCode)
	}

	var out []string
	for _, a := range answers {
		switch r := a.Body.(type) {
		case *dnsmessage.AResource:
			out = append(out, netip.AddrFrom4(r.A).String())
		case *dnsmessage.AAAAResource:
			out = append(out, netip.AddrFrom16(r.AAAA).String())
		}
	}
	return out, nil
}

// exchangeUDP writes a DNS query to server:53 and returns the response header and
// answers. UDP carries no guarantee that a datagram answers *this* query, so a
// datagram with a mismatched ID is discarded and the read retried until the
// deadline.
func exchangeUDP(server netip.Addr, buf []byte, id uint16, timeout time.Duration) (dnsmessage.Header, []dnsmessage.Resource, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	var d net.Dialer
	d.Control = func(network, address string, c syscall.RawConn) error {
		return c.Control(func(fd uintptr) {
			if vpnService.service != nil {
				vpnService.service.Protect(int32(fd))
			}
		})
	}
	conn, err := d.DialContext(ctx, "udp", netip.AddrPortFrom(server, 53).String())
	if err != nil {
		return dnsmessage.Header{}, nil, err
	}
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		conn.SetDeadline(dl)
	}

	if _, err := conn.Write(buf); err != nil {
		return dnsmessage.Header{}, nil, err
	}

	rbuf := make([]byte, 4096)
	for {
		n, err := conn.Read(rbuf)
		if err != nil {
			return dnsmessage.Header{}, nil, err
		}
		var p dnsmessage.Parser
		h, err := p.Start(rbuf[:n])
		if err != nil || h.ID != id || !h.Response {
			continue
		}
		if err := p.SkipAllQuestions(); err != nil {
			return dnsmessage.Header{}, nil, err
		}
		answers, err := p.AllAnswers()
		if err != nil {
			return dnsmessage.Header{}, nil, err
		}
		return h, answers, nil
	}
}

// dnsFQDN reports whether name has the shape of a domain name and returns it with a
// trailing dot. This is a shape check only; whether the name exists is Quad-100's
// business, not ours.
func dnsFQDN(name string) (string, bool) {
	name = strings.TrimSuffix(strings.TrimSpace(name), ".")
	if name == "" || len(name) > 253 {
		return "", false
	}
	for _, label := range strings.Split(name, ".") {
		if label == "" || len(label) > 63 {
			return "", false
		}
		for _, r := range label {
			switch {
			case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_':
			default:
				return "", false
			}
		}
	}
	return name + ".", true
}

// SetResolverMode makes the TUN install the requested resolver behaviour,
// taking effect asynchronously.
//
//   mode="tailscale": install the tailnet's own resolvers (dcfg.Nameservers).
//   mode="system":    install no nameservers; Android falls back to the OS.
//   mode="custom":    install addrsJSON, a JSON array of IP literals.
//
// The TUN is rebuilt after this returns, so the call itself only records the new
// selection. JSON strings follow GetInterfacesAsJson: gobind supports scalars and
// []byte, not []string.
func (a *App) SetResolverMode(mode string, addrsJSON string) error {
	sel := resolverSelection{mode: resolverMode(strings.TrimSpace(mode))}
	switch sel.mode {
	case modeTailscale, modeSystem:
		// no address list needed
	case modeCustom:
		parsed, err := parseResolverAddrs(addrsJSON)
		if err != nil {
			return err
		}
		sel.addrs = parsed
	default:
		return fmt.Errorf("unknown resolver mode %q", mode)
	}
	// Skip the wakeup if nothing actually changed, so a repeated set of the same
	// mode does not force a full TUN teardown and re-establish.
	prev := currentResolverSel()
	if prev.mode == sel.mode && addrsEqual(prev.addrs, sel.addrs) {
		return nil
	}
	setResolverMode(sel.mode, sel.addrs)
	// A doorbell, not a payload: the run loop re-reads resolverSel, so a
	// coalesced or dropped notification cannot lose an update.
	select {
	case onResolverChange <- struct{}{}:
	default:
	}
	return nil
}

// addrsEqual reports whether a and b contain the same addresses in any order.
func addrsEqual(a, b []netip.Addr) bool {
	if len(a) != len(b) {
		return false
	}
	m := make(map[netip.Addr]int, len(a))
	for _, x := range a {
		m[x]++
	}
	for _, y := range b {
		if m[y] == 0 {
			return false
		}
		m[y]--
	}
	return true
}

// ResolveResolverAddresses resolves target against Quad-100 and verifies the
// result answers queries, returning a JSON array of the literals to install.
//
// target is an IP literal or an FQDN; short MagicDNS names are expanded by the
// caller. cachedJSON is a JSON array of literals from a previous save, which is
// probed first so that a changed address heals without a resolution. See
// resolveResolverAddresses for the full contract.
func (a *App) ResolveResolverAddresses(target string, cachedJSON string, timeoutMillis int) (string, error) {
	var cached []string
	if strings.TrimSpace(cachedJSON) != "" {
		if err := json.Unmarshal([]byte(cachedJSON), &cached); err != nil {
			return "", fmt.Errorf("cached resolver addresses are not a JSON array: %w", err)
		}
	}
	timeout := time.Duration(timeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = defaultResolveTimeout
	}
	addrs, err := resolveResolverAddresses(target, cached, timeout)
	if err != nil {
		return "", err
	}
	out, err := json.Marshal(addrs)
	if err != nil {
		return "", err
	}
	return string(out), nil
}
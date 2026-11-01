# Root-mode recovery fixes

Personal fork of [v2rayNG](https://github.com/2dust/v2rayNG) with fixes for running it in **root mode** on Android.

This is **not** an official build. There are no APK releases here — build it yourself (see below).

Branch: `root-recovery-fix`

## What was broken

Root mode captures traffic with iptables instead of `VpnService`, so the app has to manage the tun, the policy routing and the proxy processes itself. Three problems followed from that on a device that moves between networks:

1. **Changing networks killed the tunnel.** The core's outbound socket was bound to the old source address and never recovered. Nothing noticed, because the kernel keeps accepting writes to it.
2. **A reload could not fix it.** `reloadCore()` restarted the core without first removing the rules, so the app's own DNS lookup went into the dead tunnel, failed, and the new core started without an address for its server.
3. **Each reload took seconds.** A full teardown/install cycle is ~128 iptables invocations at ~39 ms each, plus a hostname resolve that sat in the system resolver while the previous network was still answering it.

## What changed

| Commit | Change |
|---|---|
| `0e6eef2` | `NetworkWatchdog` — probes the tunnel through SOCKS5 and reloads the core after two consecutive failures. Covers the cases no network callback sees: a dead core, a hung server, broken DNS. |
| `9c4d832` | `reloadCore()` tears the tunnel down before resolving and rebuilds it after. |
| `e51745d` | `NetworkMonitor.onLinkPropertiesChanged` reloads when the upstream IPv4 address changes. |
| `3e66d8f` | Root mode no longer DNATs DNS through a fixed resolver, which was overriding `dns-out` routing. |
| `dbaa15b` | All `iptables`/`ip6tables` calls pass `-w 10`. Concurrent setup and teardown were failing on `xtables.lock`. |
| `6eecf8c` | Reload no longer rebuilds the world. Flush only the marking chain, restart the helper, and bound the outbound-domain resolve with a cached fallback. |

### The reload fast path

A network handover now costs two iptables calls instead of 128:

```
suspendCapture()     flush CORE_FILTER            55 ms
coreController.stopLoop()                          2 ms
launchCore()         cached resolve, core start   ~340 ms
resumeCapture()      restore 14 marking rules     380 ms
restartTun2socks()   kill + start the helper      626 ms
```

Policy routing, the tun and its default route are left alone: the helper recreates the tun under the same name, so the `ip rule` and route table entries stay valid across the restart.

Measured over three data-toggle cycles on a VinaPhone SIM: **1.16–1.29 s per reload**, 3–5 failed probes at 0.5 s intervals (previously 6.9 s and 10 failed probes).

## Requirements

Fixes 5 and 6 assume the root helper and the `hev-socks5-tunnel` binaries are present in `app/libs/` or the native library directory. Building those is outside the scope of this branch.

## Build

Same as upstream, for a single ABI:

```bash
./gradlew assemblePlaystoreRelease -PABI_FILTERS=arm64-v8a
```

On Windows use `gradlew.bat`, and for the native tunnel binaries you need `compile-hevtun.sh` working with `ndk-build.cmd`; the upstream script assumes a Unix shell.

The APK must be signed with a key the installing app already trusts, or Android will refuse the update.

## Not verified

`onLinkPropertiesChanged` has never fired in testing, because the carrier hands back the same IPv4 address on every reconnect. The logic is in place but only the watchdog and `onAvailable` paths have been exercised on a real device.

## Licence

Upstream v2rayNG is GPL-3.0. This fork keeps that licence; see `LICENSE`.

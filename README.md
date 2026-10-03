# Plex Photos for Kiosk Satellite

A Kiosk Satellite plugin that adds a **Plex Photos** screensaver mode. It reads a Plex Media Server photo library directly, so there is no Immich server to run.

KS still owns everything around the screensaver: idle timeout, schedules, brightness, motion/face/presence wake, widgets, At a Glance, the clock and Now Playing. The plugin only supplies the pictures.

## How it works

Plugin screensavers render in a sandboxed WebView that cannot make network requests. The plugin's Java side therefore talks to Plex, asks the Plex photo transcoder for a JPEG sized to the screen, shrinks it further on the tablet if needed, and publishes each slide as a self-contained inline document (KS caps these at 512 KiB). The next photo is always downloaded while the current one is on screen, and one photo is pre-published while the kiosk is idle, so the screensaver opens on a picture with no network wait.

## Install

1. **Build the ZIP.** Either:
   - **From a GitHub release (recommended).** Publish a release with a tag such as `v1.1.0`. The *Release* workflow tests, builds and attaches the ZIP, checksum and manifest that KS's **Install from GitHub** checks. Install from the repository in Plugin Manager and skip step 2.
   - Push this folder to a GitHub repository. The *Build plugin ZIP* workflow builds it on every push. Download the `plex-photos-plugin` artifact from the Actions run and unzip it once to get `plex-photos-1.1.0.zip`.
   - Or build locally with JDK 17+ and an Android SDK (platform 35, build-tools 35):
     ```sh
     git clone https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world ks-sdk
     python3 ks-sdk/tools/build.py ./ks-plex-photos --android-platform 35
     ```
     The ZIP lands in `ks-plex-photos/dist/`.
2. In KS, open **Plugin Manager > Developer Tools > Install from ZIP** (on the kiosk or the remote admin at `http://<tablet-ip>:2324`), pick the ZIP, confirm, and enable **Plex Photos**.
3. Fill in **Server address** and **Plex token** on the plugin page.
4. **Screensaver > Screensaver mode > Plex Photos (Plex Photos)**.

To update, install the new ZIP over the old one. Settings carry over.

### Finding your Plex token

Plex Web > any photo > **...** > **Get Info** > **View XML**. The URL of the page that opens ends in `X-Plex-Token=...`.

The token is stored in the plugin settings on the tablet and is visible in the remote admin. A Plex managed user restricted to the photo library limits what that token can reach.

The token found through **View XML** belongs to the account you are signed in as. If that is the server owner, the token can manage your whole Plex account, not just this server, so prefer a restricted managed user and keep the KS remote admin locked down.

### Connecting securely

The token travels with every request. Over plain `http://` anyone on the LAN who can see the traffic can read it. In order of preference:

1. **`plex.direct` HTTPS.** Plex issues a real certificate for each server. Use `https://192-168-1-20.<server-id>.plex.direct:32400`, with your LAN IP written with dashes. The full address is a `uri` in the `connections` list at `https://plex.tv/api/v2/resources?includeHttps=1&X-Plex-Token=...`. Leave **Accept self-signed HTTPS** off. Some routers block `plex.direct` names (DNS rebinding protection) and need an exception.
2. **`https://<LAN IP>:32400` with Accept self-signed HTTPS.** The certificate does not match the IP, so it is pinned instead: the first certificate the plugin sees is saved in **Server certificate fingerprint** and every later connection must present the same one. Clear the fingerprint if the server's certificate legitimately changes. For full protection, paste the fingerprint yourself before first use.
3. **Plain `http://`.** Simplest. Only on a network you trust.

The plugin never follows redirects and refuses any path from Plex that would point at another host, so the token is only ever sent to the server address you entered.

## Settings

| Group | Setting | Notes |
| --- | --- | --- |
| Plex server | Server address | LAN address, e.g. `http://192.168.1.20:32400`. Plain HTTP on the LAN is the simplest; set Plex's *Secure connections* to *Preferred*. |
| | Plex token | See above. |
| | Accept self-signed HTTPS | Only for `https://<LAN IP>:32400` style addresses. Pins the server's certificate. |
| | Server certificate fingerprint | SHA-256, filled in on first connection. Clear it to trust a new certificate. |
| Photos | Photo libraries | Comma-separated names. Blank means every photo library. |
| | Albums | Comma-separated names, matched case-insensitively, sub-albums included. Blank means the whole library. |
| | Taken within | Any time, On this day (same date in earlier years, plus or minus three days), past month, 1, 2, 5 or 10 years. Photos with no taken date are excluded by any date filter. |
| | Shuffle | Off plays in date order. |
| | Refresh photo list | Hours between re-reading the list while the screensaver runs. |
| Slideshow | Seconds per photo | 5 to 600. |
| | Fill the screen | Off, Smart (crops only photos within about 12% of the screen's shape), Always. Uncropped photos sit over a blurred copy of themselves. |
| | Transition | Crossfade, Fade from black, None. |
| | Show date and album | Caption in the chosen corner. Camera filenames such as `IMG_1234` are never shown. |
| | Maximum image size | Long-edge cap. Also capped at the screen resolution. |

## Commands

**Next photo** and **Reload photo list from Plex** can be bound to KS gestures, added to the drawer, or exposed as ESPHome buttons in Home Assistant from the plugin page.

## Limits

- Videos in photo libraries are skipped.
- Up to 20,000 matching photos are kept per refresh. Date filters are applied while listing, so the limit counts only photos that match. When more match, Shuffle keeps a random sample of all of them and date order keeps the oldest. Listing stops after 200,000 items.
- The inline document cap means a 2560 px photo with heavy detail is recompressed harder than you might choose. 1920 px looks clean on typical wall tablets.
- KS's built-in *next/previous slide* controls do not reach plugin screensavers. Use the plugin's **Next photo** command instead.

## Licence

Apache-2.0. Kiosk Satellite permits independent plugins under its [plugin exception](https://github.com/jxlarrea/kiosk-satellite/blob/main/PLUGIN-EXCEPTION.md).

# Plex Photos for Kiosk Satellite

A Kiosk Satellite plugin that adds a **Plex Photos** screensaver mode. It reads a Plex Media Server photo library directly, so there is no Immich server to run.

[![Buy Me a Coffee](https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png)](https://buymeacoffee.com/joshmd)

KS still owns everything around the screensaver: idle timeout, schedules, brightness, motion/face/presence wake, widgets, At a Glance, the clock and Now Playing. The plugin only supplies the pictures.

## How it works

Plugin screensavers render in a sandboxed WebView that cannot make network requests. The plugin's Java side therefore talks to Plex, asks the Plex photo transcoder for a JPEG sized to the screen, shrinks it further on the tablet if needed, and publishes each slide as a self-contained inline document (KS caps these at 512 KiB). The next photo is always downloaded while the current one is on screen, and one photo is pre-published while the kiosk is idle, so the screensaver opens on a picture with no network wait.

## Install

1. **Build the ZIP.** Either:
   - **From a GitHub release (recommended).** Publish a release with a tag such as `v1.2.0`. The *Release* workflow tests, builds and attaches the ZIP, checksum and manifest that KS's **Install from GitHub** checks. Install from the repository in Plugin Manager and skip step 2.
   - Push this folder to a GitHub repository. The *Build plugin ZIP* workflow builds it on every push. Download the `plex-photos-plugin` artifact from the Actions run and unzip it once to get `plex-photos-1.2.0.zip`.
   - Or build locally with JDK 17+ and an Android SDK (platform 35, build-tools 35):
     ```sh
     git clone https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world ks-sdk
     python3 ks-sdk/tools/build.py ./ks-plex-photos --android-platform 35
     ```
     The ZIP lands in `ks-plex-photos/dist/`.
2. In KS, open **Plugin Manager > Developer Tools > Install from ZIP** (on the kiosk or the remote admin at `http://<tablet-ip>:2324`), pick the ZIP, confirm, and enable **Plex Photos**.
3. On the plugin page, turn on **Sign in with Plex** and follow the code shown (see below).
4. **Screensaver > Screensaver mode > Plex Photos (Plex Photos)**.

To update, install the new ZIP over the old one. Settings carry over.

### Signing in

Turn on **Sign in with Plex**. The kiosk shows a four-character code in a floating window, in the plugin status and, if nothing is set up yet, on the screensaver. On your phone or computer, open [plex.tv/link](https://plex.tv/link), sign in to Plex as usual and enter the code. Your Plex password is only ever typed on plex.tv.

When the code is accepted, the plugin:

- finds your server and picks an address that answers on this network, trying the secure `plex.direct` HTTPS address first. Owned servers come before shared ones. To use a particular server, enter its **Server address** before signing in.
- saves the token for that server, encrypted (see below), and switches **Sign in with Plex** off.

Each kiosk signs in separately and appears under its own name in Plex's **Authorized Devices** list, so you can sign one kiosk out without affecting another. The code expires after about 15 minutes; tap **Cancel** in the window to stop early.

Sign in as the Plex user whose photos the kiosk should show. A Plex Home user that can only see the photo library limits what the kiosk's token can reach. Signing in as the server owner gives the kiosk an owner-level token.

You can still paste a token into **Plex token** instead (Plex Web > any photo > **...** > **Get Info** > **View XML**, the value after `X-Plex-Token=`). It is encrypted as soon as it is saved.

### How the token is stored

KS shows plugin settings in plain text, including in the remote admin. The plugin therefore stores the token encrypted with an AES key kept in this kiosk's Android Keystore. The key never leaves the tablet, so the saved value is useless if copied elsewhere.

- This stops anyone who can see the settings from reading the token. It does not stop code running inside the KS app, such as another plugin, a modified KS or root access, from asking the Keystore to decrypt it. Keep the remote admin locked down, since it can install plugins.
- If KS's app data is cleared or KS is reinstalled, the key is lost. The plugin then asks you to sign in again.
- If a tablet cannot encrypt, the plugin says so in its status and stores the token unencrypted.

### Connecting securely

The token travels with every request. Over plain `http://` anyone on the LAN who can see the traffic can read it. Signing in picks the best address that works automatically. If you enter one yourself, in order of preference:

1. **`plex.direct` HTTPS.** Plex issues a real certificate for each server. Use `https://192-168-1-20.<server-id>.plex.direct:32400`, with your LAN IP written with dashes. Leave **Accept self-signed HTTPS** off. Some routers block `plex.direct` names (DNS rebinding protection) and need an exception; sign-in then falls back to plain HTTP on the LAN.
2. **`https://<LAN IP>:32400` with Accept self-signed HTTPS.** The certificate does not match the IP, so it is pinned instead: the first certificate the plugin sees is saved in **Server certificate fingerprint** and every later connection must present the same one. Clear the fingerprint if the server's certificate legitimately changes. For full protection, paste the fingerprint yourself before first use.
3. **Plain `http://`.** Simplest. Only on a network you trust.

The plugin never follows redirects and refuses any path from Plex that would point at another host, so the token is only ever sent to the server address you entered.

## Settings

| Group | Setting | Notes |
| --- | --- | --- |
| Plex server | Sign in with Plex | Shows a code for plex.tv/link. Switches itself off when done. |
| | Server address | Blank to find it at sign-in, or e.g. `http://192.168.1.20:32400`. |
| | Plex token (encrypted) | Filled in by sign-in. A pasted token is encrypted when saved. |
| | Accept self-signed HTTPS | Only for `https://<LAN IP>:32400` style addresses. Pins the server's certificate. |
| | Server certificate fingerprint | SHA-256, filled in on first connection. Clear it to trust a new certificate. |
| | Device ID | Identifies this kiosk to Plex. Filled in automatically. |
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

**Next photo**, **Reload photo list from Plex** and **Sign in with Plex** can be bound to KS gestures, added to the drawer, or exposed as ESPHome buttons in Home Assistant from the plugin page.

## Limits

- Videos in photo libraries are skipped.
- Up to 20,000 matching photos are kept per refresh. Date filters are applied while listing, so the limit counts only photos that match. When more match, Shuffle keeps a random sample of all of them and date order keeps the oldest. Listing stops after 200,000 items.
- The inline document cap means a 2560 px photo with heavy detail is recompressed harder than you might choose. 1920 px looks clean on typical wall tablets.
- KS's built-in *next/previous slide* controls do not reach plugin screensavers. Use the plugin's **Next photo** command instead.

## Licence

Apache-2.0. Kiosk Satellite permits independent plugins under its [plugin exception](https://github.com/jxlarrea/kiosk-satellite/blob/main/PLUGIN-EXCEPTION.md).

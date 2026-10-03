// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the Plex token before it is saved in the plugin settings, which KS shows in plain text.
 * The key lives in the Android Keystore on this kiosk and cannot be copied off it, so the saved
 * value is useless anywhere else. Code running inside the KS app can still ask the Keystore to
 * decrypt it, so this protects the token from people who can read the settings, not from a
 * compromised tablet.
 */
final class TokenVault {
    static final String PREFIX = "enc1:";
    /** KS limits string settings to 512 characters. */
    static final int MAX_SEALED = 512;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int TAG_BITS = 128;

    interface KeySource {
        SecretKey key() throws GeneralSecurityException;
    }

    private final KeySource keys;

    TokenVault(KeySource keys) {
        this.keys = keys;
    }

    static boolean isSealed(String value) {
        return value.startsWith(PREFIX);
    }

    String seal(String plain) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // The Keystore insists on choosing the IV itself.
        cipher.init(Cipher.ENCRYPT_MODE, keys.key());
        byte[] ct = cipher.doFinal(plain.getBytes(UTF8));
        String sealed = PREFIX + hex(cipher.getIV()) + "." + hex(ct);
        if (sealed.length() > MAX_SEALED) throw new GeneralSecurityException("Token is too long to store");
        return sealed;
    }

    String open(String sealed) throws GeneralSecurityException {
        if (!isSealed(sealed)) throw new GeneralSecurityException("Not an encrypted token");
        String body = sealed.substring(PREFIX.length());
        int dot = body.indexOf('.');
        if (dot <= 0) throw new GeneralSecurityException("Damaged encrypted token");
        byte[] iv = unhex(body.substring(0, dot));
        byte[] ct = unhex(body.substring(dot + 1));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ct), UTF8);
    }

    private static String hex(byte[] b) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = digits[(b[i] >> 4) & 0xf];
            out[2 * i + 1] = digits[b[i] & 0xf];
        }
        return new String(out);
    }

    private static byte[] unhex(String s) throws GeneralSecurityException {
        if (s.length() % 2 != 0) throw new GeneralSecurityException("Damaged encrypted token");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16);
            int lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new GeneralSecurityException("Damaged encrypted token");
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }
}

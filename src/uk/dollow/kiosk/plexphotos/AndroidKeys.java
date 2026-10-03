// SPDX-License-Identifier: Apache-2.0
package uk.dollow.kiosk.plexphotos;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/** An AES key held in the Android Keystore. It never leaves the device and survives app updates. */
final class AndroidKeys implements TokenVault.KeySource {
    private static final String STORE = "AndroidKeyStore";
    private static final String ALIAS = "uk.dollow.kiosk.plexphotos.token.v1";

    @Override
    public SecretKey key() throws GeneralSecurityException {
        synchronized (AndroidKeys.class) {
            KeyStore ks = KeyStore.getInstance(STORE);
            try {
                ks.load(null);
            } catch (IOException e) {
                throw new GeneralSecurityException("Android Keystore unavailable", e);
            }
            Key existing = ks.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE);
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
            return gen.generateKey();
        }
    }
}

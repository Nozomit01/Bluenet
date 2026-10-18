package ru.bluenet.vpn;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Identity credentials stay encrypted in app-private storage with an Android Keystore key. */
final class IdentityStore {
    private final String alias;
    private final AtomicFile file;
    IdentityStore(Context context) { this(context,"identity.enc","bluenet.identity.v1"); }
    IdentityStore(Context context,String name,String keyAlias) { alias=keyAlias;file = new AtomicFile(new File(context.getFilesDir(), name)); }
    private SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);
        if (!create) throw new IllegalStateException("Ключ профиля недоступен. Профиль сохранён для восстановления.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    synchronized String read() throws Exception {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return "";
        byte[] bytes;
        // Bound reads before allocation, including AtomicFile recovery from .bak.
        try (java.io.InputStream in = file.openRead(); java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; int count;
            while ((count = in.read(chunk)) != -1) {
                if (out.size() + count > 131072) throw new IllegalStateException("Защищённый профиль слишком большой");
                out.write(chunk, 0, count);
            }
            bytes = out.toByteArray();
        }
        if (bytes.length < 30 || bytes.length > 131072 || bytes[0] != 1) throw new IllegalStateException("Не удалось прочитать защищённый профиль");
        int n = bytes[1] & 255;
        if (n != 12 || bytes.length < 2 + n + 16) throw new IllegalStateException("Профиль повреждён");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 2, 2+n)));
        byte[] plain = cipher.doFinal(bytes, 2+n, bytes.length-2-n);
        try { return new String(plain, StandardCharsets.UTF_8); } finally { Arrays.fill(plain, (byte)0); }
    }
    synchronized void write(String identity) throws Exception {
        byte[] plain = identity.getBytes(StandardCharsets.UTF_8);
        try {
            if (plain.length > 65536) throw new IllegalArgumentException("Профиль слишком большой");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plain);
            byte[] bytes = ByteBuffer.allocate(2+iv.length+encrypted.length).put((byte)1).put((byte)iv.length).put(iv).put(encrypted).array();
            FileOutputStream output = null;
            try { output = file.startWrite(); output.write(bytes); file.finishWrite(output); }
            catch (Exception error) { if (output != null) file.failWrite(output); throw error; }
        } finally { Arrays.fill(plain, (byte)0); }
    }
}

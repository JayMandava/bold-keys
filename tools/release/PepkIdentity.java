/* Reviewed local helper: public certificate export and in-memory key-pair proof only. */
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

public final class PepkIdentity {
    public static void main(String[] args) {
        char[] storePassword = null, keyPassword = null;
        int exitCode = 0;
        try {
            if (args.length == 1 && args[0].equals("--probe-console")) {
                if (System.console() == null || !System.console().charset().equals(StandardCharsets.UTF_8))
                    throw new IllegalStateException();
                System.out.print("PEPK_CONSOLE_UTF8_OK");
                return;
            }
            if (args.length != 3) throw new IllegalArgumentException();
            storePassword = System.getenv("BOLD_RELEASE_STORE_PASSWORD").toCharArray();
            keyPassword = System.getenv("BOLD_RELEASE_KEY_PASSWORD").toCharArray();
            String alias = System.getenv("BOLD_RELEASE_KEY_ALIAS");
            KeyStore store = KeyStore.getInstance("jks");
            try (var input = Files.newInputStream(Path.of(args[0]))) {
                store.load(input, storePassword);
            }
            if (!store.entryInstanceOf(alias, KeyStore.PrivateKeyEntry.class)) throw new IllegalStateException();
            X509Certificate cert = (X509Certificate) store.getCertificate(alias);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
            if (!fingerprint.equals(args[2])) throw new IllegalStateException();
            PrivateKey key = (PrivateKey) store.getKey(alias, keyPassword);
            if (!(key instanceof RSAKey) || ((RSAKey) key).getModulus().bitLength() < 2048)
                throw new IllegalStateException();
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(challenge);
            byte[] signature = signer.sign();
            signer.initVerify(cert.getPublicKey());
            signer.update(challenge);
            if (!signer.verify(signature)) throw new IllegalStateException();
            String pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                .encodeToString(cert.getEncoded()) + "\n-----END CERTIFICATE-----\n";
            // CREATE_NEW: never overwrite or append to an existing destination.
            Files.writeString(Path.of(args[1]), pem, StandardCharsets.US_ASCII,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (Throwable error) {
            System.err.println("Selected signing identity verification failed; confidential diagnostics suppressed.");
            exitCode = 1;
        } finally {
            if (storePassword != null) Arrays.fill(storePassword, '\0');
            if (keyPassword != null) Arrays.fill(keyPassword, '\0');
        }
        if (exitCode != 0) System.exit(exitCode);
    }
}

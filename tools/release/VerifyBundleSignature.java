import java.io.InputStream;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verify every bundle payload's JAR signature without loading a keystore or private key. */
class VerifyBundleSignature {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[1].equals("signed") || args[1].equals("unsigned")) ||
                !args[2].matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid verification arguments");
        boolean signed = args[1].equals("signed");
        int payloads = 0;
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile(), true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                // Reading to EOF makes JarFile verify entry digests before signers are inspected.
                try (InputStream stream = jar.getInputStream(entry)) { stream.transferTo(java.io.OutputStream.nullOutputStream()); }
                String name = entry.getName();
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF") ||
                        name.matches("(?i)META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                payloads++;
                CodeSigner[] signers = entry.getCodeSigners();
                if (!signed) {
                    if (signers != null) throw new SecurityException("Unsigned bundle contains signed payloads");
                } else {
                    if (signers == null || signers.length != 1) throw new SecurityException("Every payload must have exactly one signer");
                    X509Certificate leaf = (X509Certificate) signers[0].getSignerCertPath().getCertificates().get(0);
                    leaf.checkValidity();
                    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
                    if (!digest.equals(args[2])) throw new SecurityException("Production certificate mismatch");
                }
            }
        }
        if (payloads == 0) throw new SecurityException("Empty bundle payload");
        System.out.println("Verified " + payloads + " " + (signed ? "production-signed" : "unsigned") + " payloads");
    }
}

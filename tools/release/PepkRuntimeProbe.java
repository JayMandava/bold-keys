/* No secrets: synthetic JCA/compiler/provider exercise and public native file inventory. */
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.security.Provider;
import java.security.KeyPairGenerator;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;

public final class PepkRuntimeProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException();
        Class.forName("com.sun.tools.javac.Main");
        if (args[0].equals("with-public-tool")) {
            Provider provider = (Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                .getDeclaredConstructor().newInstance();
            Security.addProvider(provider);
            Cipher.getInstance("RSA/NONE/OAEPWithSHA1AndMGF1Padding", provider);
        } else if (!args[0].equals("jdk-only")) throw new IllegalArgumentException();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        cipher.doFinal(new byte[32]);
        var aes = KeyGenerator.getInstance("AES");
        aes.init(256);
        aes.generateKey();
        var maps = Files.readAllBytes(Path.of("/proc/self/maps"));
        if (maps.length > 4 * 1024 * 1024) throw new IllegalStateException();
        System.out.write(maps);
    }
}

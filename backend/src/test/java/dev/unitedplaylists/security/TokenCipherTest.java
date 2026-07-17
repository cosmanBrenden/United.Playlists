package dev.unitedplaylists.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenCipherTest {

    private final TokenCipher cipher = new TokenCipher(TokenCipher.generateKey());

    @Test
    void roundTripsAToken() {
        String token = "BQD3x1_fake-refresh-token-value";

        assertThat(cipher.decrypt(cipher.encrypt(token))).isEqualTo(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "unicode: naïve café 日本語", "with\nnewlines\tand\ttabs"})
    void roundTripsAwkwardValues(String token) {
        assertThat(cipher.decrypt(cipher.encrypt(token))).isEqualTo(token);
    }

    @Test
    void passesNullThrough() {
        assertThat(cipher.encrypt(null)).isNull();
        assertThat(cipher.decrypt(null)).isNull();
    }

    @Test
    @DisplayName("the same plaintext encrypts differently every time")
    void usesAFreshIvPerEncryption() {
        String token = "identical-input";

        String first = cipher.encrypt(token);
        String second = cipher.encrypt(token);

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(token);
        assertThat(cipher.decrypt(second)).isEqualTo(token);
    }

    @Test
    void ciphertextDoesNotContainThePlaintext() {
        String token = "super-secret-refresh-token";

        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).doesNotContain(token);
        assertThat(new String(Base64.getDecoder().decode(encrypted))).doesNotContain(token);
    }

    @Test
    @DisplayName("tampering is detected rather than silently decrypting to garbage")
    void rejectsTamperedCiphertext() {
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("a-token"));
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(TokenCipher.TokenDecryptionException.class)
                .hasMessageContaining("authentication");
    }

    @Test
    void rejectsATokenEncryptedUnderADifferentKey() {
        String encrypted = new TokenCipher(TokenCipher.generateKey()).encrypt("a-token");

        assertThatThrownBy(() -> cipher.decrypt(encrypted))
                .isInstanceOf(TokenCipher.TokenDecryptionException.class);
    }

    @Test
    void rejectsTruncatedCiphertext() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

        assertThatThrownBy(() -> cipher.decrypt(tooShort))
                .isInstanceOf(TokenCipher.TokenDecryptionException.class);
    }

    @Test
    void rejectsNonBase64() {
        assertThatThrownBy(() -> cipher.decrypt("!!!not base64!!!"))
                .isInstanceOf(TokenCipher.TokenDecryptionException.class);
    }

    @Test
    @DisplayName("refuses to start without a key rather than defaulting to plaintext")
    void requiresAKey() {
        assertThatThrownBy(() -> new TokenCipher(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refusing to store tokens in the clear");
        assertThatThrownBy(() -> new TokenCipher("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWrongLengthKey() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new TokenCipher(tooShort))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 32 bytes");
    }

    @Test
    void generatesA256BitKey() {
        assertThat(Base64.getDecoder().decode(TokenCipher.generateKey())).hasSize(32);
        assertThat(TokenCipher.generateKey()).isNotEqualTo(TokenCipher.generateKey());
    }
}

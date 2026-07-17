package dev.unitedplaylists.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * A service's API credentials, as entered by the user.
 *
 * <p>Distinct from {@link ServiceConnection}: this is the app registration (which
 * Spotify or Google app is asking), while that is the signed-in account (which user
 * it is asking on behalf of). Disconnecting an account leaves this alone, because
 * the registration is still valid and re-entering it would be busywork.
 */
@Entity
@Table(name = "provider_setting")
public class ProviderSetting {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ProviderId provider;

    @Column(name = "client_id", nullable = false, length = 512)
    private String clientId;

    @Column(name = "client_secret_enc", length = 4096)
    private String clientSecretEnc;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProviderSetting() {
        // JPA
    }

    public ProviderSetting(
            ProviderId provider, String clientId, String clientSecretEnc, Instant updatedAt) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.clientId = requireClientId(clientId);
        this.clientSecretEnc = clientSecretEnc;
        this.updatedAt = updatedAt;
    }

    private static String requireClientId(String clientId) {
        Objects.requireNonNull(clientId, "clientId");
        String trimmed = clientId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Client ID must not be blank");
        }
        return trimmed;
    }

    public ProviderId getProvider() {
        return provider;
    }

    public String getClientId() {
        return clientId;
    }

    public String getClientSecretEnc() {
        return clientSecretEnc;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean hasClientSecret() {
        return clientSecretEnc != null && !clientSecretEnc.isBlank();
    }

    public void update(String newClientId, String newClientSecretEnc, Instant now) {
        this.clientId = requireClientId(newClientId);
        this.clientSecretEnc = newClientSecretEnc;
        this.updatedAt = now;
    }
}

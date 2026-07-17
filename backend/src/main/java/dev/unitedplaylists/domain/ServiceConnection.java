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
 * A service the user has connected, and the tokens to talk to it.
 *
 * <p>Keyed by {@link ProviderId}: this is a single-user local app, so one
 * connection per service is the whole model.
 *
 * <p>Token fields hold ciphertext. Nothing here decrypts them — that is
 * {@code ConnectionService}'s job — so an accidental log of this entity or a
 * careless JSON serialisation leaks nothing usable.
 */
@Entity
@Table(name = "service_connection")
public class ServiceConnection {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 32)
    private ProviderId provider;

    @Column(name = "access_token_enc", nullable = false, length = 4096)
    private String accessTokenEnc;

    @Column(name = "refresh_token_enc", length = 4096)
    private String refreshTokenEnc;

    @Column(name = "access_token_expires_at")
    private Instant accessTokenExpiresAt;

    @Column(name = "scopes", length = 1024)
    private String scopes;

    @Column(name = "account_label", length = 255)
    private String accountLabel;

    @Column(name = "market", length = 8)
    private String market;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    @Column(name = "last_refreshed_at")
    private Instant lastRefreshedAt;

    protected ServiceConnection() {
        // JPA
    }

    public ServiceConnection(
            ProviderId provider,
            String accessTokenEnc,
            String refreshTokenEnc,
            Instant accessTokenExpiresAt,
            String scopes,
            String accountLabel,
            String market,
            Instant connectedAt) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.accessTokenEnc = Objects.requireNonNull(accessTokenEnc, "accessTokenEnc");
        this.refreshTokenEnc = refreshTokenEnc;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.scopes = scopes;
        this.accountLabel = accountLabel;
        this.market = market;
        this.connectedAt = connectedAt;
    }

    public ProviderId getProvider() {
        return provider;
    }

    public String getAccessTokenEnc() {
        return accessTokenEnc;
    }

    public String getRefreshTokenEnc() {
        return refreshTokenEnc;
    }

    public Instant getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public String getScopes() {
        return scopes;
    }

    public String getAccountLabel() {
        return accountLabel;
    }

    public String getMarket() {
        return market;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getLastRefreshedAt() {
        return lastRefreshedAt;
    }

    public boolean hasRefreshToken() {
        return refreshTokenEnc != null && !refreshTokenEnc.isBlank();
    }

    /**
     * True if the access token is expired or close enough that a call started now
     * might land after it dies.
     *
     * @param skew headroom to treat as already-expired
     */
    public boolean needsRefresh(Instant now, java.time.Duration skew) {
        return accessTokenExpiresAt != null && now.plus(skew).isAfter(accessTokenExpiresAt);
    }

    /** Applies a refreshed access token. Services that rotate refresh tokens pass the new one. */
    public void applyRefresh(
            String newAccessTokenEnc,
            String newRefreshTokenEnc,
            Instant newExpiresAt,
            Instant refreshedAt) {
        this.accessTokenEnc = Objects.requireNonNull(newAccessTokenEnc, "newAccessTokenEnc");
        if (newRefreshTokenEnc != null && !newRefreshTokenEnc.isBlank()) {
            this.refreshTokenEnc = newRefreshTokenEnc;
        }
        this.accessTokenExpiresAt = newExpiresAt;
        this.lastRefreshedAt = refreshedAt;
    }
}

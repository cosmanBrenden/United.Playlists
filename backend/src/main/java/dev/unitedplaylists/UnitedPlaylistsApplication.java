package dev.unitedplaylists;

import dev.unitedplaylists.provider.oauth.OAuthProperties;
import dev.unitedplaylists.provider.spotify.SpotifyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Local backend for UnitedPlaylists.
 *
 * <p>Runs as a child process of the Electron app on a loopback port, not as a
 * hosted server. That keeps OAuth tokens on the user's machine, removes any need
 * for user accounts, and means the app has no server to pay for.
 */
@SpringBootApplication
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties({OAuthProperties.class, SpotifyProperties.class})
public class UnitedPlaylistsApplication {

    public static void main(String[] args) {
        SpringApplication.run(UnitedPlaylistsApplication.class, args);
    }
}

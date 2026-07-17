package dev.unitedplaylists.provider;

import dev.unitedplaylists.domain.ProviderId;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Looks up {@link MusicProvider} beans by {@link ProviderId}.
 *
 * <p>Spring injects every provider on the classpath, so adding a service needs no
 * edit here. Two beans claiming the same id is a wiring mistake and fails at
 * startup rather than silently letting one shadow the other.
 */
@Component
public class ProviderRegistry {

    private final Map<ProviderId, MusicProvider> byId = new EnumMap<>(ProviderId.class);

    public ProviderRegistry(List<MusicProvider> providers) {
        for (MusicProvider provider : providers) {
            MusicProvider clash = byId.put(provider.id(), provider);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two providers registered for %s: %s and %s"
                                .formatted(
                                        provider.id(),
                                        clash.getClass().getName(),
                                        provider.getClass().getName()));
            }
        }
    }

    /** Empty when no provider handles that service. */
    public Optional<MusicProvider> find(ProviderId id) {
        return Optional.ofNullable(byId.get(id));
    }

    /**
     * @throws ProviderException if no provider is registered for {@code id}
     */
    public MusicProvider require(ProviderId id) {
        return find(id).orElseThrow(() -> new ProviderException(
                id, ProviderException.Kind.UNSUPPORTED, "No provider registered for " + id));
    }

    /** Every registered provider, including unimplemented stubs. */
    public Collection<MusicProvider> all() {
        return List.copyOf(byId.values());
    }

    /** Only providers that are actually implemented. */
    public List<MusicProvider> available() {
        return byId.values().stream().filter(MusicProvider::isAvailable).toList();
    }
}

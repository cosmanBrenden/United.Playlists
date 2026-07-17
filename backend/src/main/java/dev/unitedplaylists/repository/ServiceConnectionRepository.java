package dev.unitedplaylists.repository;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.ServiceConnection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceConnectionRepository extends JpaRepository<ServiceConnection, ProviderId> {
}

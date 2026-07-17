package dev.unitedplaylists.repository;

import dev.unitedplaylists.domain.ProviderId;
import dev.unitedplaylists.domain.ProviderSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderSettingRepository extends JpaRepository<ProviderSetting, ProviderId> {
}

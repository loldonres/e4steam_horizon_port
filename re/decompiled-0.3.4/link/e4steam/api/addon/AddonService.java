package link.e4steam.api.addon;

import java.util.List;
import java.util.Optional;

public interface AddonService {
   List<AddonHandle> addons();

   Optional<AddonHandle> find(AddonId var1);

   boolean registrationsFrozen();
}

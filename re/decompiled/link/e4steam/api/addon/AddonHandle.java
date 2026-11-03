package link.e4steam.api.addon;

import java.util.Optional;

public interface AddonHandle {
   AddonDescriptor descriptor();

   AddonState state();

   Optional<AddonFailure> failure();
}

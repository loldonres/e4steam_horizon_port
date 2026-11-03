package link.e4steam.internal.addon;

import java.util.Set;
import link.e4steam.api.addon.AddonDescriptor;
import link.e4steam.api.capability.CapabilityId;

public interface CapabilityGrantPolicy {
   Set<CapabilityId> knownCapabilities();

   boolean isAllowed(AddonDescriptor var1, CapabilityId var2);
}

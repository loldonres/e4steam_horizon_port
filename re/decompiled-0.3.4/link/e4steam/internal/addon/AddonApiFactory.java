package link.e4steam.internal.addon;

import java.util.Set;
import link.e4steam.api.E4steamApi;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.addon.AddonDescriptor;
import link.e4steam.api.capability.CapabilityId;

public interface AddonApiFactory {
   E4steamApi create(AddonDescriptor var1, Set<CapabilityId> var2, ResourceScope var3);
}

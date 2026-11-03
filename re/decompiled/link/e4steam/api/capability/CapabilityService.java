package link.e4steam.api.capability;

import java.util.Set;
import link.e4steam.api.ApiResult;

public interface CapabilityService {
   Set<CapabilityId> requested();

   Set<CapabilityId> granted();

   boolean has(CapabilityId var1);

   ApiResult<CapabilityId> require(CapabilityId var1, String var2);
}

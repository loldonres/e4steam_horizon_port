package link.e4steam.api.addon;

import link.e4steam.api.E4steamApi;
import link.e4steam.api.ResourceScope;

public interface AddonContext {
   AddonDescriptor descriptor();

   E4steamApi api();

   ResourceScope resources();
}

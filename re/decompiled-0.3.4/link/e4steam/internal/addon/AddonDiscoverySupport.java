package link.e4steam.internal.addon;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import link.e4steam.api.addon.E4steamAddonEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AddonDiscoverySupport {
   private static final Logger LOGGER = LoggerFactory.getLogger("e4steam-addon-api");

   private AddonDiscoverySupport() {
   }

   public static List<AddonCandidate> serviceLoader(ClassLoader loader) {
      ArrayList<AddonCandidate> candidates = new ArrayList<>();
      ServiceLoader<E4steamAddonEntrypoint> services = ServiceLoader.load(
         E4steamAddonEntrypoint.class, loader == null ? E4steamAddonEntrypoint.class.getClassLoader() : loader
      );
      Iterator<E4steamAddonEntrypoint> iterator = services.iterator();
      int attempts = 0;

      while (attempts++ < 1000) {
         boolean hasNext;
         try {
            hasNext = iterator.hasNext();
         } catch (ServiceConfigurationError var8) {
            LOGGER.warn("Ignored an unreadable e4steam addon service provider ({})", var8.getClass().getSimpleName());
            continue;
         }

         if (!hasNext) {
            break;
         }

         try {
            E4steamAddonEntrypoint entrypoint = iterator.next();
            candidates.add(AddonCandidate.fromEntrypoint(entrypoint, "service-provider"));
         } catch (RuntimeException | ServiceConfigurationError var7) {
            LOGGER.warn("Ignored a malformed e4steam addon service provider ({})", var7.getClass().getSimpleName());
         }
      }

      return candidates;
   }
}

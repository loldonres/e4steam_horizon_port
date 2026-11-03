package link.e4steam.internal.kaleido.lib.quiltconfig.impl.util;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigCreationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

public final class ConfigsImpl {
   private static final Map<String, Map<String, Config>> CONFIGS = new TreeMap<>();

   private ConfigsImpl() {
   }

   public static void put(String familyId, Config config) {
      if (CONFIGS.containsKey(familyId) && CONFIGS.get(familyId).containsKey(config.id())) {
         throw new ConfigCreationException("Config '" + familyId + ':' + config.id() + "' already exists");
      } else {
         CONFIGS.computeIfAbsent(familyId, id -> new TreeMap<>()).put(config.id(), config);
      }
   }

   @TestOnly
   public static void removeAll() {
      CONFIGS.clear();
   }

   public static Iterable<Config> getAll() {
      return ConfigsImpl::itr;
   }

   public static Iterable<Config> getConfigs(String familyId) {
      return new ImmutableIterable<>(CONFIGS.getOrDefault(familyId, Collections.emptyMap()).values());
   }

   @Nullable
   public static Config getConfig(String familyId, String configId) {
      return CONFIGS.getOrDefault(familyId, Collections.emptyMap()).get(configId);
   }

   @NotNull
   private static Iterator<Config> itr() {
      return new ConfigsImpl.AllConfigsIterator();
   }

   private static class AllConfigsIterator implements Iterator<Config> {
      final Iterator<Map<String, Config>> itr1 = ConfigsImpl.CONFIGS.values().iterator();
      Iterator<Config> itr2;

      private AllConfigsIterator() {
         if (this.itr1.hasNext()) {
            this.itr2 = this.itr1.next().values().iterator();
         } else {
            this.itr2 = Collections.emptyIterator();
         }
      }

      @Override
      public boolean hasNext() {
         return this.itr1.hasNext() || this.itr2.hasNext();
      }

      public Config next() {
         while (!this.itr2.hasNext() && this.itr1.hasNext()) {
            this.itr2 = this.itr1.next().values().iterator();
         }

         return this.itr2.next();
      }
   }
}

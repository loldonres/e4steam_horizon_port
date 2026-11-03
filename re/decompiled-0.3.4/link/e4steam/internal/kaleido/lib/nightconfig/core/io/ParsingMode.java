package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import link.e4steam.internal.kaleido.lib.nightconfig.core.Config;
import link.e4steam.internal.kaleido.lib.nightconfig.core.IncompatibleIntermediaryLevelException;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.StringUtils;

public enum ParsingMode {
   REPLACE(Config::clear, Config::set, Map::put),
   MERGE(c -> {}, (cfg, path, value) -> {
      try {
         return cfg.set(path, value);
      } catch (IncompatibleIntermediaryLevelException var8) {
         for (int i = path.size(); i > 0; i--) {
            List<String> prefix = path.subList(0, i);
            cfg.remove(prefix);

            try {
               return cfg.set(path, value);
            } catch (IncompatibleIntermediaryLevelException var7) {
            }
         }

         return null;
      }
   }, Map::put),
   ADD(c -> {}, (cfg, path, value) -> {
      try {
         cfg.add(path, value);
      } catch (IncompatibleIntermediaryLevelException var4) {
      }

      return null;
   }, Map::putIfAbsent);

   private final Consumer<? super Config> preparationAction;
   private final ParsingMode.PutAction putAction;
   private final ParsingMode.MapPutAction mapPutAction;

   private ParsingMode(Consumer<? super Config> preparationAction, ParsingMode.PutAction putAction, ParsingMode.MapPutAction mapPutAction) {
      this.preparationAction = preparationAction;
      this.putAction = putAction;
      this.mapPutAction = mapPutAction;
   }

   public void prepareParsing(Config config) {
      this.preparationAction.accept(config);
   }

   public Object put(Config config, List<String> key, Object value) {
      return this.putAction.put(config, key, value);
   }

   public Object put(Config config, String key, Object value) {
      return this.putAction.put(config, key, value);
   }

   public Object put(Map<String, Object> map, String key, Object value) {
      return this.mapPutAction.put(map, key, value);
   }

   @FunctionalInterface
   private interface MapPutAction {
      Object put(Map<String, Object> var1, String var2, Object var3);
   }

   @FunctionalInterface
   private interface PutAction {
      Object put(Config var1, List<String> var2, Object var3);

      default Object put(Config config, String key, Object value) {
         return this.put(config, StringUtils.split(key, '.'), value);
      }
   }
}

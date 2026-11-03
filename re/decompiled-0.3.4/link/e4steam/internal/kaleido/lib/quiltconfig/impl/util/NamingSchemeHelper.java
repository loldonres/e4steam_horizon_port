package link.e4steam.internal.kaleido.lib.quiltconfig.impl.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.DisplayNameConvention;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.SerializedNameConvention;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.NamingScheme;

public final class NamingSchemeHelper {
   private final ClassLoader classLoader;
   private final Map<String, NamingScheme> customSchemeCache;

   public NamingSchemeHelper(ClassLoader classLoader) {
      this.classLoader = classLoader;
      this.customSchemeCache = new HashMap<>();
   }

   public NamingSchemeHelper() {
      this(NamingSchemeHelper.class.getClassLoader());
   }

   public NamingScheme getNamingScheme(SerializedNameConvention annotation, BiFunction<String, Throwable, RuntimeException> exceptionFactory) {
      return (NamingScheme)(annotation.custom().isEmpty() ? annotation.value() : this.createCustomNamingScheme(annotation.custom(), exceptionFactory));
   }

   public NamingScheme getNamingScheme(DisplayNameConvention annotation, BiFunction<String, Throwable, RuntimeException> exceptionFactory) {
      return (NamingScheme)(annotation.custom().isEmpty() ? annotation.value() : this.createCustomNamingScheme(annotation.custom(), exceptionFactory));
   }

   private NamingScheme createCustomNamingScheme(String className, BiFunction<String, Throwable, RuntimeException> exceptionFactory) {
      return this.customSchemeCache.computeIfAbsent(className, customSchemeName -> {
         try {
            Class<?> customSchemeClass = Class.forName(customSchemeName, true, this.classLoader);
            return (NamingScheme)customSchemeClass.newInstance();
         } catch (ClassNotFoundException var5) {
            throw (RuntimeException)exceptionFactory.apply("Couldn't find custom naming scheme class '" + customSchemeName + "'", var5);
         } catch (IllegalAccessException | InstantiationException var6) {
            throw (RuntimeException)exceptionFactory.apply("Couldn't create instance of custom name scheme class '" + customSchemeName + "'", var6);
         } catch (ClassCastException var7) {
            throw (RuntimeException)exceptionFactory.apply("Class '" + customSchemeName + "' does not implement '" + NamingScheme.class.getName() + "'", var7);
         }
      });
   }
}

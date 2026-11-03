package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigParser;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigWriter;

public final class InMemoryFormat implements ConfigFormat<Config> {
   static final Predicate<Class<?>> DEFAULT_PREDICATE = type -> type == null
      || type.isPrimitive()
      || type == Byte.class
      || type == Short.class
      || type == Character.class
      || type == Integer.class
      || type == Long.class
      || type == Float.class
      || type == Double.class
      || type == Boolean.class
      || type == String.class
      || type == NullObject.class
      || Collection.class.isAssignableFrom(type)
      || Config.class.isAssignableFrom(type)
      || Enum.class.isAssignableFrom(type);
   private static final InMemoryFormat DEFAULT_INSTANCE = new InMemoryFormat(DEFAULT_PREDICATE);
   private static final InMemoryFormat UNIVERSAL_INSTANCE = new InMemoryFormat(t -> true);
   private final Predicate<Class<?>> supportPredicate;

   public static InMemoryFormat defaultInstance() {
      return DEFAULT_INSTANCE;
   }

   public static InMemoryFormat withSupport(Predicate<Class<?>> supportPredicate) {
      return new InMemoryFormat(supportPredicate);
   }

   public static InMemoryFormat withUniversalSupport() {
      return UNIVERSAL_INSTANCE;
   }

   private InMemoryFormat(Predicate<Class<?>> supportPredicate) {
      this.supportPredicate = supportPredicate;
   }

   @Override
   public ConfigWriter createWriter() {
      throw new UnsupportedOperationException("In memory configurations aren't meant to be written.");
   }

   @Override
   public ConfigParser<Config> createParser() {
      throw new UnsupportedOperationException("In memory configurations aren't meant to be parsed.");
   }

   @Override
   public Config createConfig(Supplier<Map<String, Object>> mapCreator) {
      return Config.of(mapCreator, this);
   }

   @Override
   public boolean supportsComments() {
      return false;
   }

   @Override
   public boolean supportsType(Class<?> type) {
      return this.supportPredicate.test(type);
   }

   @Override
   public boolean isInMemory() {
      return true;
   }
}

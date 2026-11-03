package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigParser;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigWriter;

public class InMemoryCommentedFormat implements ConfigFormat<CommentedConfig> {
   private static final InMemoryCommentedFormat DEFAULT_INSTANCE = new InMemoryCommentedFormat(InMemoryFormat.DEFAULT_PREDICATE);
   private static final InMemoryCommentedFormat UNIVERSAL_INSTANCE = new InMemoryCommentedFormat(t -> true);
   private final Predicate<Class<?>> supportPredicate;

   public static InMemoryCommentedFormat defaultInstance() {
      return DEFAULT_INSTANCE;
   }

   public static InMemoryCommentedFormat withSupport(Predicate<Class<?>> supportPredicate) {
      return new InMemoryCommentedFormat(supportPredicate);
   }

   public static InMemoryCommentedFormat withUniversalSupport() {
      return UNIVERSAL_INSTANCE;
   }

   private InMemoryCommentedFormat(Predicate<Class<?>> supportPredicate) {
      this.supportPredicate = supportPredicate;
   }

   @Override
   public ConfigWriter createWriter() {
      throw new UnsupportedOperationException("In memory configurations aren't meant to be written.");
   }

   @Override
   public ConfigParser<CommentedConfig> createParser() {
      throw new UnsupportedOperationException("In memory configurations aren't meant to be parsed.");
   }

   public CommentedConfig createConfig(Supplier<Map<String, Object>> mapCreator) {
      return CommentedConfig.of(mapCreator, this);
   }

   @Override
   public boolean supportsComments() {
      return true;
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

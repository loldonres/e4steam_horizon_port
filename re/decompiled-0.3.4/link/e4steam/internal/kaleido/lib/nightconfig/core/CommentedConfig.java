package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.FakeCommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.StringUtils;

public interface CommentedConfig extends UnmodifiableCommentedConfig, Config {
   default String setComment(String path, String comment) {
      return this.setComment(StringUtils.split(path, '.'), comment);
   }

   String setComment(List<String> var1, String var2);

   default String removeComment(String path) {
      return this.removeComment(StringUtils.split(path, '.'));
   }

   String removeComment(List<String> var1);

   void clearComments();

   default void putAllComments(Map<String, UnmodifiableCommentedConfig.CommentNode> comments) {
      for (Map.Entry<String, UnmodifiableCommentedConfig.CommentNode> entry : comments.entrySet()) {
         String key = entry.getKey();
         UnmodifiableCommentedConfig.CommentNode node = entry.getValue();
         String comment = node.getComment();
         if (comment != null) {
            this.setComment(Collections.singletonList(key), comment);
         }

         Map<String, UnmodifiableCommentedConfig.CommentNode> children = node.getChildren();
         if (children != null) {
            CommentedConfig config = this.getRaw(Collections.singletonList(key));
            if (config != null) {
               config.putAllComments(children);
            }
         }
      }
   }

   default void putAllComments(UnmodifiableCommentedConfig commentedConfig) {
      for (UnmodifiableCommentedConfig.Entry entry : commentedConfig.entrySet()) {
         String key = entry.getKey();
         String comment = entry.getComment();
         if (comment != null) {
            this.setComment(Collections.singletonList(key), comment);
         }

         Object value = entry.getValue();
         if (value instanceof UnmodifiableCommentedConfig) {
            CommentedConfig config = this.getRaw(Collections.singletonList(key));
            if (config != null) {
               config.putAllComments((UnmodifiableCommentedConfig)value);
            }
         }
      }
   }

   default UnmodifiableCommentedConfig unmodifiable() {
      return new UnmodifiableCommentedConfig() {
         @Override
         public <T> T getRaw(List<String> path) {
            return CommentedConfig.this.getRaw(path);
         }

         @Override
         public String getComment(List<String> path) {
            return CommentedConfig.this.getComment(path);
         }

         @Override
         public boolean contains(List<String> path) {
            return CommentedConfig.this.contains(path);
         }

         @Override
         public boolean containsComment(List<String> path) {
            return CommentedConfig.this.containsComment(path);
         }

         @Override
         public int size() {
            return CommentedConfig.this.size();
         }

         @Override
         public Map<String, Object> valueMap() {
            return Collections.unmodifiableMap(CommentedConfig.this.valueMap());
         }

         @Override
         public Map<String, String> commentMap() {
            return Collections.unmodifiableMap(CommentedConfig.this.commentMap());
         }

         @Override
         public Map<String, UnmodifiableCommentedConfig.CommentNode> getComments() {
            return CommentedConfig.this.getComments();
         }

         @Override
         public Set<? extends UnmodifiableCommentedConfig.Entry> entrySet() {
            return CommentedConfig.this.entrySet();
         }

         @Override
         public ConfigFormat<?> configFormat() {
            return CommentedConfig.this.configFormat();
         }
      };
   }

   default CommentedConfig checked() {
      return new CheckedCommentedConfig(this);
   }

   @Deprecated
   @Override
   Map<String, String> commentMap();

   @Override
   Set<? extends CommentedConfig.Entry> entrySet();

   CommentedConfig createSubConfig();

   static CommentedConfig of(ConfigFormat<? extends CommentedConfig> format) {
      return new SimpleCommentedConfig(format, false);
   }

   static CommentedConfig of(Supplier<Map<String, Object>> mapCreator, ConfigFormat<? extends CommentedConfig> format) {
      return new SimpleCommentedConfig(mapCreator, format);
   }

   @Deprecated
   static CommentedConfig ofConcurrent(ConfigFormat<? extends CommentedConfig> format) {
      return new SimpleCommentedConfig(format, false);
   }

   static CommentedConfig inMemory() {
      return InMemoryCommentedFormat.defaultInstance().createConfig();
   }

   @Deprecated
   static CommentedConfig inMemoryConcurrent() {
      return InMemoryCommentedFormat.defaultInstance().createConcurrentConfig();
   }

   static CommentedConfig wrap(Map<String, Object> map, ConfigFormat<?> format) {
      return new SimpleCommentedConfig(map, format);
   }

   static CommentedConfig copy(UnmodifiableConfig config) {
      return new SimpleCommentedConfig(config, config.configFormat(), false);
   }

   static CommentedConfig copy(UnmodifiableConfig config, Supplier<Map<String, Object>> mapCreator) {
      return new SimpleCommentedConfig(config, mapCreator, config.configFormat());
   }

   static CommentedConfig copy(UnmodifiableConfig config, ConfigFormat<?> format) {
      return new SimpleCommentedConfig(config, format, false);
   }

   static CommentedConfig copy(UnmodifiableConfig config, Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> format) {
      return new SimpleCommentedConfig(config, mapCreator, format);
   }

   static CommentedConfig copy(UnmodifiableCommentedConfig config) {
      return new SimpleCommentedConfig(config, config.configFormat(), false);
   }

   static CommentedConfig copy(UnmodifiableCommentedConfig config, Supplier<Map<String, Object>> mapCreator) {
      return new SimpleCommentedConfig(config, mapCreator, config.configFormat());
   }

   static CommentedConfig copy(UnmodifiableCommentedConfig config, ConfigFormat<?> format) {
      return new SimpleCommentedConfig(config, format, false);
   }

   static CommentedConfig copy(UnmodifiableCommentedConfig config, Supplier<Map<String, Object>> mapCreator, ConfigFormat<? extends CommentedConfig> format) {
      return new SimpleCommentedConfig(config, mapCreator, format);
   }

   @Deprecated
   static CommentedConfig concurrentCopy(UnmodifiableConfig config) {
      return new SimpleCommentedConfig(config, config.configFormat(), true);
   }

   @Deprecated
   static CommentedConfig concurrentCopy(UnmodifiableConfig config, ConfigFormat<?> format) {
      return new SimpleCommentedConfig(config, format, true);
   }

   @Deprecated
   static CommentedConfig concurrentCopy(UnmodifiableCommentedConfig config) {
      return new SimpleCommentedConfig(config, config.configFormat(), true);
   }

   @Deprecated
   static CommentedConfig concurrentCopy(UnmodifiableCommentedConfig config, ConfigFormat<?> format) {
      return new SimpleCommentedConfig(config, format, true);
   }

   static CommentedConfig fake(Config config) {
      return (CommentedConfig)(config instanceof CommentedConfig ? (CommentedConfig)config : new FakeCommentedConfig(config));
   }

   public interface Entry extends Config.Entry, UnmodifiableCommentedConfig.Entry {
      String setComment(String var1);

      String removeComment();
   }
}

package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.FakeUnmodifiableCommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.StringUtils;

public interface UnmodifiableCommentedConfig extends UnmodifiableConfig {
   default String getComment(String path) {
      return this.getComment(StringUtils.split(path, '.'));
   }

   String getComment(List<String> var1);

   default Optional<String> getOptionalComment(String path) {
      return this.getOptionalComment(StringUtils.split(path, '.'));
   }

   default Optional<String> getOptionalComment(List<String> path) {
      return Optional.ofNullable(this.getComment(path));
   }

   default boolean containsComment(String path) {
      return this.containsComment(StringUtils.split(path, '.'));
   }

   boolean containsComment(List<String> var1);

   @Deprecated
   Map<String, String> commentMap();

   default Map<String, UnmodifiableCommentedConfig.CommentNode> getComments() {
      Map<String, UnmodifiableCommentedConfig.CommentNode> map = new HashMap<>();
      this.getComments(map);
      return map;
   }

   default void getComments(Map<String, UnmodifiableCommentedConfig.CommentNode> destination) {
      for (UnmodifiableCommentedConfig.Entry entry : this.entrySet()) {
         String key = entry.getKey();
         String comment = entry.getComment();
         Object value = entry.getValue();
         if (comment != null || value instanceof UnmodifiableCommentedConfig) {
            Map<String, UnmodifiableCommentedConfig.CommentNode> children = value instanceof UnmodifiableCommentedConfig
               ? ((UnmodifiableCommentedConfig)value).getComments()
               : null;
            UnmodifiableCommentedConfig.CommentNode node = new UnmodifiableCommentedConfig.CommentNode(comment, children);
            destination.put(key, node);
         }
      }
   }

   @Override
   Set<? extends UnmodifiableCommentedConfig.Entry> entrySet();

   static UnmodifiableCommentedConfig fake(UnmodifiableConfig config) {
      return (UnmodifiableCommentedConfig)(config instanceof UnmodifiableCommentedConfig
         ? (UnmodifiableCommentedConfig)config
         : new FakeUnmodifiableCommentedConfig(config));
   }

   public static final class CommentNode {
      private final String comment;
      private final Map<String, UnmodifiableCommentedConfig.CommentNode> children;

      public CommentNode(String comment, Map<String, UnmodifiableCommentedConfig.CommentNode> children) {
         if (comment == null && children == null) {
            throw new IllegalArgumentException("There is no point in creating a CommentNode if the comment AND the children are null.");
         } else {
            this.comment = comment;
            this.children = children;
         }
      }

      public String getComment() {
         return this.comment;
      }

      public Map<String, UnmodifiableCommentedConfig.CommentNode> getChildren() {
         return this.children;
      }
   }

   public interface Entry extends UnmodifiableConfig.Entry {
      String getComment();
   }
}

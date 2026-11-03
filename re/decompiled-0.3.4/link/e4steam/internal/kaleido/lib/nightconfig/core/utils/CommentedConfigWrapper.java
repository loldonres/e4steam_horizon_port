package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import link.e4steam.internal.kaleido.lib.nightconfig.core.CommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.UnmodifiableCommentedConfig;

public abstract class CommentedConfigWrapper<C extends CommentedConfig> extends ConfigWrapper<C> implements CommentedConfig {
   protected CommentedConfigWrapper(C config) {
      super(config);
   }

   @Override
   public String getComment(List<String> path) {
      return this.config.getComment(path);
   }

   @Override
   public boolean containsComment(List<String> path) {
      return this.config.containsComment(path);
   }

   @Override
   public String setComment(List<String> path, String comment) {
      return this.config.setComment(path, comment);
   }

   @Override
   public String removeComment(List<String> path) {
      return this.config.removeComment(path);
   }

   @Override
   public Map<String, String> commentMap() {
      return this.config.commentMap();
   }

   @Override
   public Set<? extends CommentedConfig.Entry> entrySet() {
      return this.config.entrySet();
   }

   @Override
   public void clearComments() {
      this.config.clearComments();
   }

   @Override
   public void putAllComments(Map<String, UnmodifiableCommentedConfig.CommentNode> comments) {
      this.config.putAllComments(comments);
   }

   @Override
   public void putAllComments(UnmodifiableCommentedConfig commentedConfig) {
      this.config.putAllComments(commentedConfig);
   }

   @Override
   public Map<String, UnmodifiableCommentedConfig.CommentNode> getComments() {
      return this.config.getComments();
   }

   @Override
   public CommentedConfig createSubConfig() {
      return this.config.createSubConfig();
   }

   @Override
   public String toString() {
      return this.getClass().getSimpleName() + ':' + this.config;
   }
}

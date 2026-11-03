package link.e4steam.internal.kaleido.lib.quiltconfig.impl;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.Comment;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations.ConfigFieldAnnotationProcessor;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainerBuilder;
import org.jetbrains.annotations.ApiStatus.Internal;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Internal
public @interface Comments {
   Comment[] value();

   public static final class Processor implements ConfigFieldAnnotationProcessor<Comments> {
      public void process(Comments comments, MetadataContainerBuilder<?> builder) {
         for (Comment comment : comments.value()) {
            for (String c : comment.value()) {
               builder.metadata(Comment.TYPE, b -> b.add(c));
            }
         }
      }
   }
}

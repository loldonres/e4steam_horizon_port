package link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.Comments;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.CommentsImpl;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
@Repeatable(Comments.class)
public @interface Comment {
   MetadataType<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.Comments, Comment.Builder> TYPE = MetadataType.create(
      () -> Optional.of(new CommentsImpl(Collections.emptyList())), Comment.Builder::new
   );

   String[] value();

   public static final class Builder implements MetadataType.Builder<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.Comments> {
      private final List<String> comments = new ArrayList<>(0);

      public void add(String... comments) {
         for (String comment : comments) {
            this.comments.addAll(Arrays.asList(comment.split("\n")));
         }
      }

      public link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.Comments build() {
         return new CommentsImpl(this.comments);
      }
   }
}

package link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface DisplayName {
   MetadataType<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.DisplayName, DisplayName.Builder> TYPE = MetadataType.create(
      Optional::empty, DisplayName.Builder::new
   );

   String value();

   boolean translatable() default false;

   public static final class Builder implements MetadataType.Builder<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.DisplayName> {
      private String name;
      private boolean translatable;

      public void setName(String name) {
         this.name = name;
      }

      public void setTranslatable(boolean translatable) {
         this.translatable = translatable;
      }

      public link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.DisplayName build() {
         return new link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.DisplayName(this.name, this.translatable);
      }
   }
}

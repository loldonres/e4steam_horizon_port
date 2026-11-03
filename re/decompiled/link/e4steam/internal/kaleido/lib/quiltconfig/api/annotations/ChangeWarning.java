package link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface ChangeWarning {
   MetadataType<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning, ChangeWarning.Builder> TYPE = MetadataType.create(
      Optional::empty, ChangeWarning.Builder::new, true
   );

   link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning.Type value();

   String customMessage() default "";

   public static final class Builder implements MetadataType.Builder<link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning> {
      private String message;
      private link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning.Type type;

      public void setMessage(String message) {
         this.setType(link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning.Type.Custom);
         this.message = message;
      }

      public void setType(link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning.Type type) {
         this.type = type;
      }

      public void setTranslatableMessage(String translationKey) {
         this.setType(link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning.Type.CustomTranslatable);
         this.setMessage(translationKey);
      }

      public link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning build() {
         return new link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.ChangeWarning(this.message, this.type);
      }
   }
}

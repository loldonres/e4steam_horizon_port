package link.e4steam.internal.kaleido.lib.quiltconfig.api.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.SerialName;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface SerializedName {
   MetadataType<SerialName, SerializedName.Builder> TYPE = MetadataType.create(Optional::empty, SerializedName.Builder::new);

   String value();

   public static final class Builder implements MetadataType.Builder<SerialName> {
      private String name;

      Builder() {
      }

      public void withName(String name) {
         this.name = name;
      }

      public SerialName build() {
         return new SerialName(this.name);
      }
   }
}
